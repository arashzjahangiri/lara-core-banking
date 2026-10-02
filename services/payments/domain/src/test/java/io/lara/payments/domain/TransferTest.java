package io.lara.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The state machine, exercised edge by edge.
 *
 * <p>Two properties are worth more than the rest and are tested hardest. {@code REJECTED} must be
 * unreachable once money has moved, because the whole service reads that state as "nothing
 * happened". And {@code POSTED} must not be abandonable, because a saga that can give up while
 * the ledger holds the money is a saga that loses it.
 *
 * <p>A fixed clock throughout, so the recorded history is exact rather than approximately right.
 */
class TransferTest {

    private static final Instant T0 = Instant.parse("2026-10-02T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private static final Iban DEBTOR = Iban.of("DK5000400440116243");
    private static final Iban CREDITOR = Iban.of("DE89370400440532013000");
    private static final Money AMOUNT = Money.of(125_000, "EUR");
    private static final String MAKER = "arash";

    /** A SEPA routing decision; the router itself is tested separately. */
    private static final RoutingDecision ROUTING = new RoutingDecision(
            TransferScheme.SEPA_CREDIT_TRANSFER,
            Money.of(50, "EUR"),
            java.time.LocalDate.of(2026, 10, 2));

    private static Transfer requested() {
        return Transfer.request(TransferId.newId(), DEBTOR, CREDITOR, AMOUNT, ROUTING, MAKER, CLOCK);
    }

    private static Transfer inState(TransferStatus status) {
        Transfer transfer = requested();
        switch (status) {
            case REQUESTED -> { /* already there */ }
            case SCREENING -> transfer.startScreening();
            case APPROVAL_PENDING -> {
                transfer.startScreening();
                transfer.awaitApproval();
            }
            case POSTING -> {
                transfer.startScreening();
                transfer.startPosting();
            }
            case POSTED -> {
                transfer.startScreening();
                transfer.startPosting();
                transfer.posted(someLedgerTransaction());
            }
            case COMPLETED -> {
                transfer = inState(TransferStatus.POSTED);
                transfer.complete();
            }
            case REJECTED -> transfer.reject(RejectionReason.SCREENING_BLOCKED, "sanctions match");
            case COMPENSATING -> {
                transfer = inState(TransferStatus.POSTED);
                transfer.startCompensation("scheme refused the payment");
            }
            case COMPENSATED -> {
                transfer = inState(TransferStatus.COMPENSATING);
                transfer.compensated(someLedgerTransaction());
            }
            case FAILED -> transfer.fail("out of attempts");
        }
        return transfer;
    }

    private static LedgerTransactionRef someLedgerTransaction() {
        return LedgerTransactionRef.of(java.util.UUID.randomUUID());
    }

    @Nested
    @DisplayName("a new transfer")
    class WhenRequested {

        @Test
        void starts_in_requested_with_no_history() {
            Transfer transfer = requested();

            assertThat(transfer.status()).isEqualTo(TransferStatus.REQUESTED);
            assertThat(transfer.state()).isInstanceOf(TransferState.Requested.class);
            assertThat(transfer.history()).isEmpty();
            assertThat(transfer.isTerminal()).isFalse();
            assertThat(transfer.ledgerTransaction()).isEmpty();
        }

        @Test
        void derives_its_posting_reference_from_its_id() {
            TransferId id = TransferId.newId();

            Transfer first = Transfer.request(id, DEBTOR, CREDITOR, AMOUNT, ROUTING, MAKER, CLOCK);
            Transfer second = Transfer.request(id, DEBTOR, CREDITOR, AMOUNT, ROUTING, MAKER, CLOCK);

            // The ledger deduplicates on this value, so two attempts at the same transfer must
            // quote the same reference or the idempotency guarantee buys nothing.
            assertThat(first.reference()).isEqualTo(second.reference());
            assertThat(first.reference().value()).contains(id.value().toString());
        }

        @Test
        void refuses_a_zero_or_negative_amount() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Transfer.request(
                            TransferId.newId(), DEBTOR, CREDITOR, Money.of(0, "EUR"), ROUTING, MAKER, CLOCK))
                    .withMessageContaining("positive");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Transfer.request(
                            TransferId.newId(), DEBTOR, CREDITOR, Money.of(-1, "EUR"), ROUTING, MAKER, CLOCK))
                    .withMessageContaining("positive");
        }

        @Test
        void refuses_to_send_money_to_the_account_it_came_from() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Transfer.request(
                            TransferId.newId(), DEBTOR, DEBTOR, AMOUNT, ROUTING, MAKER, CLOCK))
                    .withMessageContaining("different debtor and creditor");
        }
    }

    @Nested
    @DisplayName("legal transitions")
    class LegalEdges {

        /**
         * Every edge the diagram in {@link Transfer} draws. Listing them rather than deriving them
         * is deliberate: a derived list would change silently alongside the code it is meant to
         * pin down.
         */
        static Stream<org.junit.jupiter.params.provider.Arguments> edges() {
            return Stream.of(
                    edge(TransferStatus.REQUESTED, TransferStatus.SCREENING, Transfer::startScreening),
                    edge(TransferStatus.REQUESTED, TransferStatus.REJECTED,
                            t -> t.reject(RejectionReason.UNKNOWN_ACCOUNT, "no such creditor")),
                    edge(TransferStatus.REQUESTED, TransferStatus.FAILED, t -> t.fail("out of attempts")),

                    edge(TransferStatus.SCREENING, TransferStatus.POSTING, Transfer::startPosting),
                    edge(TransferStatus.SCREENING, TransferStatus.APPROVAL_PENDING, Transfer::awaitApproval),
                    edge(TransferStatus.SCREENING, TransferStatus.REJECTED,
                            t -> t.reject(RejectionReason.SCREENING_BLOCKED, "daily limit exceeded")),
                    edge(TransferStatus.SCREENING, TransferStatus.FAILED, t -> t.fail("risk unreachable")),

                    edge(TransferStatus.APPROVAL_PENDING, TransferStatus.POSTING, Transfer::startPosting),
                    edge(TransferStatus.APPROVAL_PENDING, TransferStatus.REJECTED,
                            t -> t.reject(RejectionReason.APPROVAL_REFUSED, "declined by checker")),
                    edge(TransferStatus.APPROVAL_PENDING, TransferStatus.FAILED,
                            t -> t.fail("approval window expired")),

                    edge(TransferStatus.POSTING, TransferStatus.POSTED,
                            t -> t.posted(someLedgerTransaction())),
                    edge(TransferStatus.POSTING, TransferStatus.REJECTED,
                            t -> t.reject(RejectionReason.LEDGER_REFUSED, "unbalanced entry")),
                    edge(TransferStatus.POSTING, TransferStatus.FAILED, t -> t.fail("ledger unreachable")),

                    edge(TransferStatus.POSTED, TransferStatus.COMPLETED, Transfer::complete),
                    edge(TransferStatus.POSTED, TransferStatus.COMPENSATING,
                            t -> t.startCompensation("scheme refused")),

                    edge(TransferStatus.COMPENSATING, TransferStatus.COMPENSATED,
                            t -> t.compensated(someLedgerTransaction())),
                    edge(TransferStatus.COMPENSATING, TransferStatus.FAILED,
                            t -> t.fail("reversal refused; money is stranded")));
        }

        private static org.junit.jupiter.params.provider.Arguments edge(
                TransferStatus from, TransferStatus to, Consumer<Transfer> move) {
            return org.junit.jupiter.params.provider.Arguments.of(from, to, move);
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @MethodSource("edges")
        void are_accepted_and_recorded(TransferStatus from, TransferStatus to, Consumer<Transfer> move) {
            Transfer transfer = inState(from);
            int historyBefore = transfer.history().size();

            move.accept(transfer);

            assertThat(transfer.status()).isEqualTo(to);
            assertThat(transfer.history()).hasSize(historyBefore + 1);

            TransferTransition last = transfer.history().get(transfer.history().size() - 1);
            assertThat(last.from()).isEqualTo(from);
            assertThat(last.to()).isEqualTo(to);
            assertThat(last.at()).isEqualTo(T0);
        }
    }

    @Nested
    @DisplayName("illegal transitions")
    class IllegalEdges {

        @Test
        void name_both_ends_of_the_move_they_rejected() {
            Transfer transfer = inState(TransferStatus.REQUESTED);

            assertThatExceptionOfType(IllegalTransferTransitionException.class)
                    .isThrownBy(() -> transfer.posted(someLedgerTransaction()))
                    .satisfies(thrown -> {
                        assertThat(thrown.from()).isEqualTo(TransferStatus.REQUESTED);
                        assertThat(thrown.to()).isEqualTo(TransferStatus.POSTED);
                    })
                    .withMessageContaining("REQUESTED")
                    .withMessageContaining("POSTED");
        }

        @Test
        void leave_the_transfer_exactly_as_it_was() {
            Transfer transfer = inState(TransferStatus.SCREENING);
            List<TransferTransition> before = new ArrayList<>(transfer.history());

            assertThatExceptionOfType(IllegalTransferTransitionException.class)
                    .isThrownBy(transfer::complete);

            assertThat(transfer.status()).isEqualTo(TransferStatus.SCREENING);
            assertThat(transfer.history()).isEqualTo(before);
        }

        /**
         * The invariant the whole service leans on. Everything downstream reads {@code REJECTED}
         * as "no money moved"; this is what makes that reading safe.
         */
        @Test
        void a_posted_transfer_can_never_be_rejected() {
            Transfer transfer = inState(TransferStatus.POSTED);

            assertThatExceptionOfType(IllegalTransferTransitionException.class)
                    .isThrownBy(() -> transfer.reject(RejectionReason.INSUFFICIENT_FUNDS, "too late"))
                    .satisfies(thrown -> assertThat(thrown.from()).isEqualTo(TransferStatus.POSTED));
        }

        /**
         * The other half of it. Once the ledger holds the money, walking away is not a move the
         * saga is allowed to make — the only exits from {@code POSTED} are finishing and reversing.
         */
        @Test
        void a_posted_transfer_can_never_simply_be_abandoned() {
            Transfer transfer = inState(TransferStatus.POSTED);

            assertThatExceptionOfType(IllegalTransferTransitionException.class)
                    .isThrownBy(() -> transfer.fail("giving up"))
                    .satisfies(thrown -> assertThat(thrown.to()).isEqualTo(TransferStatus.FAILED));
        }

        @Test
        void screening_cannot_be_skipped() {
            Transfer transfer = inState(TransferStatus.REQUESTED);

            assertThatExceptionOfType(IllegalTransferTransitionException.class)
                    .isThrownBy(transfer::startPosting);
        }

        @Test
        void an_unapproved_transfer_cannot_post_from_requested() {
            Transfer transfer = inState(TransferStatus.REQUESTED);

            assertThatExceptionOfType(IllegalTransferTransitionException.class)
                    .isThrownBy(transfer::awaitApproval);
        }
    }

    @Nested
    @DisplayName("terminal states")
    class Terminality {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = TransferStatus.class,
                names = {"COMPLETED", "REJECTED", "COMPENSATED", "FAILED"})
        void admit_no_further_transition(TransferStatus terminal) {
            Transfer transfer = inState(terminal);

            assertThat(transfer.isTerminal()).isTrue();

            // Every move, not a sample of them: a terminal state is only terminal if all of them
            // are closed, and a new transition method added later must be added here too.
            assertThatExceptionOfType(IllegalTransferTransitionException.class)
                    .isThrownBy(transfer::startScreening);
            assertThatExceptionOfType(IllegalTransferTransitionException.class)
                    .isThrownBy(transfer::startPosting);
            assertThatExceptionOfType(IllegalTransferTransitionException.class)
                    .isThrownBy(transfer::awaitApproval);
            assertThatExceptionOfType(IllegalTransferTransitionException.class)
                    .isThrownBy(() -> transfer.posted(someLedgerTransaction()));
            assertThatExceptionOfType(IllegalTransferTransitionException.class)
                    .isThrownBy(transfer::complete);
            assertThatExceptionOfType(IllegalTransferTransitionException.class)
                    .isThrownBy(() -> transfer.reject(RejectionReason.UNKNOWN_ACCOUNT, "x"));
            assertThatExceptionOfType(IllegalTransferTransitionException.class)
                    .isThrownBy(() -> transfer.startCompensation("x"));
            assertThatExceptionOfType(IllegalTransferTransitionException.class)
                    .isThrownBy(() -> transfer.compensated(someLedgerTransaction()));
            assertThatExceptionOfType(IllegalTransferTransitionException.class)
                    .isThrownBy(() -> transfer.fail("x"));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = TransferStatus.class,
                names = {"REQUESTED", "SCREENING", "APPROVAL_PENDING", "POSTING", "POSTED", "COMPENSATING"})
        void are_not_claimed_by_states_that_still_have_somewhere_to_go(TransferStatus live) {
            assertThat(inState(live).isTerminal()).isFalse();
        }
    }

    @Nested
    @DisplayName("the evidence a state carries")
    class StateData {

        @Test
        void a_completed_transfer_names_the_transaction_that_moved_the_money() {
            LedgerTransactionRef posting = someLedgerTransaction();
            Transfer transfer = inState(TransferStatus.POSTING);

            transfer.posted(posting);
            transfer.complete();

            assertThat(transfer.ledgerTransaction()).contains(posting);
            assertThat(transfer.state())
                    .isInstanceOfSatisfying(TransferState.Completed.class,
                            completed -> assertThat(completed.ledgerTransaction()).isEqualTo(posting));
        }

        /** ADR-0011 reverses by contra entry, so the honest record names both postings. */
        @Test
        void a_compensated_transfer_names_both_the_posting_and_the_reversal() {
            LedgerTransactionRef posting = someLedgerTransaction();
            LedgerTransactionRef reversal = someLedgerTransaction();

            Transfer transfer = inState(TransferStatus.POSTING);
            transfer.posted(posting);
            transfer.startCompensation("scheme refused the payment");
            transfer.compensated(reversal);

            assertThat(transfer.state())
                    .isInstanceOfSatisfying(TransferState.Compensated.class, compensated -> {
                        assertThat(compensated.ledgerTransaction()).isEqualTo(posting);
                        assertThat(compensated.reversal()).isEqualTo(reversal);
                        assertThat(compensated.cause()).isEqualTo("scheme refused the payment");
                    });
        }

        @Test
        void a_rejected_transfer_names_no_transaction_because_none_exists() {
            Transfer transfer = inState(TransferStatus.REJECTED);

            assertThat(transfer.ledgerTransaction()).isEmpty();
            assertThat(transfer.state())
                    .isInstanceOfSatisfying(TransferState.Rejected.class, rejected ->
                            assertThat(rejected.reason()).isEqualTo(RejectionReason.SCREENING_BLOCKED));
        }

        @Test
        void an_approval_pending_transfer_remembers_who_asked() {
            Transfer transfer = inState(TransferStatus.APPROVAL_PENDING);

            assertThat(transfer.state())
                    .isInstanceOfSatisfying(TransferState.ApprovalPending.class, pending ->
                            assertThat(pending.requestedBy()).isEqualTo(MAKER));
        }

        @Test
        void a_posted_state_refuses_to_exist_without_its_transaction() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TransferState.Posted(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("must name its ledger transaction");
        }
    }

    @Nested
    @DisplayName("history")
    class History {

        @Test
        void records_the_whole_path_in_order() {
            Transfer transfer = requested();
            transfer.startScreening();
            transfer.awaitApproval();
            transfer.startPosting();
            transfer.posted(someLedgerTransaction());
            transfer.complete();

            assertThat(transfer.history())
                    .extracting(TransferTransition::from, TransferTransition::to)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple(
                                    TransferStatus.REQUESTED, TransferStatus.SCREENING),
                            org.assertj.core.api.Assertions.tuple(
                                    TransferStatus.SCREENING, TransferStatus.APPROVAL_PENDING),
                            org.assertj.core.api.Assertions.tuple(
                                    TransferStatus.APPROVAL_PENDING, TransferStatus.POSTING),
                            org.assertj.core.api.Assertions.tuple(
                                    TransferStatus.POSTING, TransferStatus.POSTED),
                            org.assertj.core.api.Assertions.tuple(
                                    TransferStatus.POSTED, TransferStatus.COMPLETED));
        }

        @Test
        void cannot_be_edited_through_the_list_it_hands_out() {
            Transfer transfer = inState(TransferStatus.SCREENING);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> transfer.history().clear());
        }

        @Test
        void advances_with_the_clock() {
            Instant later = T0.plus(Duration.ofMinutes(7));
            java.util.concurrent.atomic.AtomicReference<Instant> now =
                    new java.util.concurrent.atomic.AtomicReference<>(T0);
            Clock moving = new Clock() {
                @Override
                public ZoneOffset getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(java.time.ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return now.get();
                }
            };

            Transfer transfer = Transfer.request(
                    TransferId.newId(), DEBTOR, CREDITOR, AMOUNT, ROUTING, MAKER, moving);
            transfer.startScreening();
            now.set(later);
            transfer.startPosting();

            assertThat(transfer.history()).extracting(TransferTransition::at)
                    .containsExactly(T0, later);
        }
    }

    @Nested
    @DisplayName("rehydration")
    class Rehydration {

        /**
         * Restoring a row must not re-validate the moves that produced it. A rule tightened after
         * the row was written would otherwise make it unloadable exactly when someone needs to
         * investigate why it looks like that.
         */
        @Test
        void restores_a_stored_transfer_without_replaying_its_transitions() {
            Transfer original = inState(TransferStatus.COMPENSATED);

            Transfer restored = Transfer.rehydrate(
                    original.id(),
                    original.reference(),
                    original.debtor(),
                    original.creditor(),
                    original.amount(),
                    original.routing(),
                    original.requestedBy(),
                    original.requestedAt(),
                    original.state(),
                    original.history(),
                    CLOCK);

            assertThat(restored.status()).isEqualTo(TransferStatus.COMPENSATED);
            assertThat(restored.state()).isEqualTo(original.state());
            assertThat(restored.history()).isEqualTo(original.history());
            assertThat(restored.isTerminal()).isTrue();
        }
    }
}
