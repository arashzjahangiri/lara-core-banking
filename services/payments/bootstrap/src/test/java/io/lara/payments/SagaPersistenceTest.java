package io.lara.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.lara.payments.application.ConcurrentTransferModificationException;
import io.lara.payments.application.StoredTransfer;
import io.lara.payments.application.Transfers;
import io.lara.payments.domain.Iban;
import io.lara.payments.domain.LedgerTransactionRef;
import io.lara.payments.domain.Money;
import io.lara.payments.domain.RejectionReason;
import io.lara.payments.domain.RoutingDecision;
import io.lara.payments.domain.Transfer;
import io.lara.payments.domain.TransferId;
import io.lara.payments.domain.TransferScheme;
import io.lara.payments.domain.TransferState;
import io.lara.payments.domain.TransferStatus;
import io.lara.payments.domain.TransferTransition;

/**
 * Saga storage against a real PostgreSQL.
 *
 * <p>Three things can only be checked here. That the version column genuinely refuses a stale
 * write — an assertion about Hibernate's behaviour, not about our code. That the sealed state
 * hierarchy survives being flattened into columns and rebuilt. And that the schema Hibernate
 * validates against is the one Flyway created, which every test in this class exercises simply
 * by starting.
 */
@QuarkusTest
class SagaPersistenceTest {

    @Inject
    Transfers transfers;

    @Inject
    Clock clock;

    private static final Iban DEBTOR = Iban.of("DK5000400440116243");
    private static final Iban CREDITOR = Iban.of("DE89370400440532013000");

    private static final RoutingDecision SEPA = new RoutingDecision(
            TransferScheme.SEPA_CREDIT_TRANSFER, Money.of(50, "EUR"), LocalDate.of(2026, 10, 5));

    private Transfer newTransfer() {
        return Transfer.request(
                TransferId.newId(), DEBTOR, CREDITOR, Money.of(125_000, "EUR"), SEPA, "arash", clock);
    }

    private Transfer persisted() {
        Transfer transfer = newTransfer();
        QuarkusTransaction.requiringNew().run(() -> transfers.add(transfer));
        return transfer;
    }

    /** Reads the saga back together with the revision it was read at. */
    private StoredTransfer reload(TransferId id) {
        return QuarkusTransaction.requiringNew()
                .call(() -> transfers.find(id))
                .orElseThrow(() -> new AssertionError("transfer " + id + " was not stored"));
    }

    private Transfer reloaded(TransferId id) {
        return reload(id).transfer();
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        void stores_and_restores_a_new_transfer() {
            Transfer original = persisted();

            Transfer restored = reloaded(original.id());

            assertThat(restored.id()).isEqualTo(original.id());
            assertThat(restored.reference()).isEqualTo(original.reference());
            assertThat(restored.debtor()).isEqualTo(DEBTOR);
            assertThat(restored.creditor()).isEqualTo(CREDITOR);
            assertThat(restored.amount()).isEqualTo(Money.of(125_000, "EUR"));
            assertThat(restored.fee()).isEqualTo(Money.of(50, "EUR"));
            assertThat(restored.totalDebit()).isEqualTo(Money.of(125_050, "EUR"));
            assertThat(restored.scheme()).isEqualTo(TransferScheme.SEPA_CREDIT_TRANSFER);
            assertThat(restored.valueDate()).isEqualTo(LocalDate.of(2026, 10, 5));
            assertThat(restored.status()).isEqualTo(TransferStatus.REQUESTED);
        }

        /**
         * {@code requestedAt} is truncated to microseconds by the aggregate because that is all
         * {@code timestamptz} stores. The ledger learned this the hard way when an untruncated
         * nanosecond broke a hash chain over data nobody had touched.
         */
        @Test
        void keeps_the_requested_at_instant_exactly() {
            Transfer original = persisted();

            assertThat(reloaded(original.id()).requestedAt()).isEqualTo(original.requestedAt());
        }

        @Test
        void finds_a_transfer_by_its_posting_reference() {
            Transfer original = persisted();

            Optional<StoredTransfer> found = QuarkusTransaction.requiringNew()
                    .call(() -> transfers.findByReference(original.reference()));

            assertThat(found).isPresent();
            assertThat(found.get().transfer().id()).isEqualTo(original.id());
        }
    }

    @Nested
    @DisplayName("the flattened state")
    class FlattenedState {

        @Test
        void survives_a_posting() {
            LedgerTransactionRef posting = LedgerTransactionRef.of(java.util.UUID.randomUUID());
            Transfer transfer = persisted();

            advance(transfer.id(), t -> {
                t.startScreening();
                t.startPosting();
                t.posted(posting);
            });

            assertThat(reloaded(transfer.id()).state())
                    .isInstanceOfSatisfying(TransferState.Posted.class, posted ->
                            assertThat(posted.ledgerTransaction()).isEqualTo(posting));
        }

        /** The case with the most to carry: two transaction references and a cause. */
        @Test
        void survives_a_compensation() {
            LedgerTransactionRef posting = LedgerTransactionRef.of(java.util.UUID.randomUUID());
            LedgerTransactionRef reversal = LedgerTransactionRef.of(java.util.UUID.randomUUID());
            Transfer transfer = persisted();

            advance(transfer.id(), t -> {
                t.startScreening();
                t.startPosting();
                t.posted(posting);
                t.startCompensation("scheme refused the payment");
                t.compensated(reversal);
            });

            assertThat(reloaded(transfer.id()).state())
                    .isInstanceOfSatisfying(TransferState.Compensated.class, compensated -> {
                        assertThat(compensated.ledgerTransaction()).isEqualTo(posting);
                        assertThat(compensated.reversal()).isEqualTo(reversal);
                        assertThat(compensated.cause()).isEqualTo("scheme refused the payment");
                    });
        }

        @Test
        void survives_a_rejection_with_its_reason() {
            Transfer transfer = persisted();

            advance(transfer.id(), t -> {
                t.startScreening();
                t.reject(RejectionReason.SCREENING_BLOCKED, "sanctions match on beneficiary");
            });

            assertThat(reloaded(transfer.id()).state())
                    .isInstanceOfSatisfying(TransferState.Rejected.class, rejected -> {
                        assertThat(rejected.reason()).isEqualTo(RejectionReason.SCREENING_BLOCKED);
                        assertThat(rejected.detail()).isEqualTo("sanctions match on beneficiary");
                    });
        }

        @Test
        void survives_waiting_for_approval_and_remembers_the_maker() {
            Transfer transfer = persisted();

            advance(transfer.id(), t -> {
                t.startScreening();
                t.awaitApproval("over the four-eyes threshold");
            });

            assertThat(reloaded(transfer.id()).state())
                    .isInstanceOfSatisfying(TransferState.ApprovalPending.class, pending ->
                            assertThat(pending.requestedBy()).isEqualTo("arash"));
        }
    }

    @Nested
    @DisplayName("history")
    class History {

        @Test
        void is_appended_rather_than_rewritten() {
            Transfer transfer = persisted();

            advance(transfer.id(), Transfer::startScreening);
            advance(transfer.id(), Transfer::startPosting);

            assertThat(reloaded(transfer.id()).history())
                    .extracting(TransferTransition::from, TransferTransition::to)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple(
                                    TransferStatus.REQUESTED, TransferStatus.SCREENING),
                            org.assertj.core.api.Assertions.tuple(
                                    TransferStatus.SCREENING, TransferStatus.POSTING));
        }
    }

    @Nested
    @DisplayName("optimistic locking")
    class OptimisticLocking {

        /**
         * The assertion the whole ticket exists for.
         *
         * <p>Two callers load the same saga, both decide what to do, and both try to write. The
         * second write is built from a version that no longer exists, so it must be refused —
         * not merged, and above all not allowed to win. A silent last-write-wins here would put
         * a completed transfer back in flight.
         */
        @Test
        void refuses_a_write_built_from_a_stale_read() {
            Transfer saga = persisted();

            StoredTransfer firstReader = reload(saga.id());
            StoredTransfer secondReader = reload(saga.id());
            assertThat(secondReader.version()).isEqualTo(firstReader.version());

            // The first caller advances and commits.
            firstReader.transfer().startScreening();
            QuarkusTransaction.requiringNew().run(() -> transfers.update(firstReader));

            // The second is still holding the revision it read, which no longer exists.
            secondReader.transfer().startScreening();

            assertThatExceptionOfType(ConcurrentTransferModificationException.class)
                    .isThrownBy(() -> QuarkusTransaction.requiringNew()
                            .run(() -> transfers.update(secondReader)))
                    .satisfies(conflict -> assertThat(conflict.id()).isEqualTo(saga.id()));
        }

        /** The loser's attempt must leave no trace. The winner's state is what stands. */
        @Test
        void leaves_the_winners_state_in_place() {
            Transfer saga = persisted();
            StoredTransfer loser = reload(saga.id());

            StoredTransfer winner = reload(saga.id());
            winner.transfer().startScreening();
            winner.transfer().awaitApproval("over the four-eyes threshold");
            QuarkusTransaction.requiringNew().run(() -> transfers.update(winner));

            loser.transfer().startScreening();
            try {
                QuarkusTransaction.requiringNew().run(() -> transfers.update(loser));
            } catch (ConcurrentTransferModificationException expected) {
                // The point of the test.
            }

            assertThat(reloaded(saga.id()).status()).isEqualTo(TransferStatus.APPROVAL_PENDING);
        }

        /** Retrying against fresh state is what the conflict is telling the caller to do. */
        @Test
        void succeeds_when_the_loser_reloads_and_tries_again() {
            Transfer saga = persisted();
            StoredTransfer loser = reload(saga.id());

            StoredTransfer winner = reload(saga.id());
            winner.transfer().startScreening();
            QuarkusTransaction.requiringNew().run(() -> transfers.update(winner));

            loser.transfer().startScreening();
            assertThatExceptionOfType(ConcurrentTransferModificationException.class)
                    .isThrownBy(() -> QuarkusTransaction.requiringNew()
                            .run(() -> transfers.update(loser)));

            // Reloading is exactly what the conflict is telling the caller to do.
            StoredTransfer retried = reload(saga.id());
            retried.transfer().startPosting();
            QuarkusTransaction.requiringNew().run(() -> transfers.update(retried));

            assertThat(reloaded(saga.id()).status()).isEqualTo(TransferStatus.POSTING);
        }
    }

    @Nested
    @DisplayName("finding stalled sagas")
    class Stalled {

        /** What the recovery sweep asks for: non-terminal sagas that have stopped moving. */
        @Test
        void returns_a_saga_sitting_in_a_non_terminal_state() {
            Transfer transfer = persisted();
            advance(transfer.id(), Transfer::startScreening);

            List<StoredTransfer> stuck = QuarkusTransaction.requiringNew().call(() -> transfers.stuckIn(
                    List.of(TransferStatus.SCREENING, TransferStatus.POSTING),
                    Instant.now().plus(Duration.ofMinutes(1)),
                    50));

            assertThat(stuck).extracting(StoredTransfer::id).contains(transfer.id());
        }

        /** A saga that has just moved is not stalled, however long it has existed. */
        @Test
        void ignores_one_that_moved_recently() {
            Transfer transfer = persisted();
            advance(transfer.id(), Transfer::startScreening);

            List<StoredTransfer> stuck = QuarkusTransaction.requiringNew().call(() -> transfers.stuckIn(
                    List.of(TransferStatus.SCREENING),
                    Instant.now().minus(Duration.ofMinutes(5)),
                    50));

            assertThat(stuck).extracting(StoredTransfer::id).doesNotContain(transfer.id());
        }

        /** Finished transfers only accumulate, and the sweep must never pick one up. */
        @Test
        void never_returns_a_terminal_saga() {
            Transfer transfer = persisted();
            advance(transfer.id(), t -> {
                t.startScreening();
                t.reject(RejectionReason.SCREENING_BLOCKED, "blocked");
            });

            List<StoredTransfer> stuck = QuarkusTransaction.requiringNew().call(() -> transfers.stuckIn(
                    List.of(TransferStatus.REJECTED),
                    Instant.now().plus(Duration.ofMinutes(1)),
                    50));

            assertThat(stuck).extracting(StoredTransfer::id).doesNotContain(transfer.id());
        }
    }

    /** Applies some moves to a saga read at a known revision and writes it back. */
    private void advance(StoredTransfer stored, java.util.function.Consumer<Transfer> moves) {
        moves.accept(stored.transfer());
        QuarkusTransaction.requiringNew().run(() -> transfers.update(stored));
    }

    /** Loads the saga fresh, applies some moves and writes it back. */
    private void advance(TransferId id, java.util.function.Consumer<Transfer> moves) {
        advance(reload(id), moves);
    }
}
