package io.lara.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.lara.payments.domain.Iban;
import io.lara.payments.domain.LedgerTransactionRef;
import io.lara.payments.domain.Money;
import io.lara.payments.domain.RejectionReason;
import io.lara.payments.domain.RoutingDecision;
import io.lara.payments.domain.Transfer;
import io.lara.payments.domain.TransferId;
import io.lara.payments.domain.TransferReference;
import io.lara.payments.domain.TransferScheme;
import io.lara.payments.domain.TransferState;
import io.lara.payments.domain.TransferStatus;

/**
 * The orchestrator, driven with in-memory ports and no network.
 *
 * <p>This is the test the whole port-and-adapter arrangement exists to make possible. Every
 * failure branch — a missing account, a block, a refused posting, three different dependencies
 * being unreachable — is reachable here in milliseconds, where against real services most of them
 * would be somewhere between awkward and impossible to arrange.
 *
 * <p>Two properties get the most attention. A step that fails must never leave the saga sitting
 * in its own state pretending nothing happened. And an outage must never be mistaken for an
 * answer: an unreachable risk service is not an allow and not a block.
 */
class TransferOrchestratorTest {

    private static final Instant T0 = Instant.parse("2026-10-02T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final Currency EUR = Currency.getInstance("EUR");

    private static final Iban DEBTOR = Iban.of("DK5000400440116243");
    private static final Iban INTERNAL_CREDITOR = Iban.of("DK2800400440116251");
    private static final Iban FOREIGN_CREDITOR = Iban.of("DE89370400440532013000");

    private static final String DEBTOR_LEDGER = "CUSTOMER000001";
    private static final String CREDITOR_LEDGER = "CUSTOMER000002";
    private static final String SUSPENSE = "BANK.SEPA.SUSPENSE.EUR";
    private static final String FEE_INCOME = "BANK.INCOME.FEES.EUR";

    private static final Money FOUR_EYES_THRESHOLD = Money.of(1_000_000, "EUR");

    private final InMemoryTransfers transfers = new InMemoryTransfers();
    private final StubAccounts accounts = new StubAccounts();
    private final StubRisk risk = new StubRisk();
    private final StubLedger ledger = new StubLedger();
    private final StubScheme scheme = new StubScheme();

    private TransferOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new TransferOrchestrator(
                transfers, accounts, risk, ledger, scheme, SUSPENSE, FEE_INCOME, FOUR_EYES_THRESHOLD);

        accounts.register(new AccountSummary(DEBTOR, "cust-1", DEBTOR_LEDGER, EUR, true));
        accounts.register(new AccountSummary(INTERNAL_CREDITOR, "cust-2", CREDITOR_LEDGER, EUR, true));
    }

    private Transfer sepaTransfer(long minorUnits) {
        return store(FOREIGN_CREDITOR, minorUnits,
                new RoutingDecision(TransferScheme.SEPA_CREDIT_TRANSFER,
                        Money.of(50, "EUR"), LocalDate.of(2026, 10, 5)));
    }

    private Transfer internalTransfer(long minorUnits) {
        return store(INTERNAL_CREDITOR, minorUnits,
                new RoutingDecision(TransferScheme.INTERNAL,
                        Money.zero("EUR"), LocalDate.of(2026, 10, 2)));
    }

    private Transfer store(Iban creditor, long minorUnits, RoutingDecision routing) {
        Transfer transfer = Transfer.request(
                TransferId.newId(), DEBTOR, creditor, Money.of(minorUnits, "EUR"),
                routing, "arash", CLOCK);
        transfers.add(transfer);
        return transfer;
    }

    /** Runs steps until the saga parks or finishes, with a cap so a bug cannot hang the suite. */
    private SagaProgress drive(TransferId id) {
        SagaProgress progress = SagaProgress.ADVANCED;
        for (int step = 0; step < 10 && progress == SagaProgress.ADVANCED; step++) {
            progress = orchestrator.advanceOnce(id);
        }
        return progress;
    }

    private Transfer reload(TransferId id) {
        return transfers.find(id).orElseThrow().transfer();
    }

    @Nested
    @DisplayName("the happy path")
    class HappyPath {

        @Test
        void takes_a_sepa_transfer_all_the_way_to_completed() {
            Transfer transfer = sepaTransfer(125_000);

            assertThat(drive(transfer.id())).isEqualTo(SagaProgress.FINISHED);
            assertThat(reload(transfer.id()).status()).isEqualTo(TransferStatus.COMPLETED);
        }

        /** Every state the transfer passed through, in order. */
        @Test
        void walks_the_states_in_order() {
            Transfer transfer = sepaTransfer(125_000);

            drive(transfer.id());

            assertThat(reload(transfer.id()).history())
                    .extracting(h -> h.to().name())
                    .containsExactly("SCREENING", "POSTING", "POSTED", "COMPLETED");
        }

        /**
         * The money goes to suspense, not to the beneficiary's IBAN. The creditor is at another
         * bank and this ledger has no account for them, so posting anywhere else would either
         * fail or credit the wrong party.
         */
        @Test
        void posts_a_sepa_transfer_to_the_suspense_account() {
            Transfer transfer = sepaTransfer(125_000);

            drive(transfer.id());

            PostingCommand posting = ledger.lastPosting();
            assertThat(posting.debtorLedgerAccount()).isEqualTo(DEBTOR_LEDGER);
            assertThat(posting.creditorLedgerAccount()).isEqualTo(SUSPENSE);
            assertThat(posting.amount()).isEqualTo(Money.of(125_000, "EUR"));
            assertThat(posting.fee()).isEqualTo(Money.of(50, "EUR"));
            assertThat(posting.feeIncomeAccount()).isEqualTo(FEE_INCOME);
            assertThat(posting.valueDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        }

        /** An internal transfer goes straight to the other customer's ledger account, free. */
        @Test
        void posts_an_internal_transfer_between_the_two_customer_accounts() {
            Transfer transfer = internalTransfer(125_000);

            drive(transfer.id());

            PostingCommand posting = ledger.lastPosting();
            assertThat(posting.debtorLedgerAccount()).isEqualTo(DEBTOR_LEDGER);
            assertThat(posting.creditorLedgerAccount()).isEqualTo(CREDITOR_LEDGER);
            assertThat(posting.fee().isZero()).isTrue();
        }

        /**
         * Risk is asked about the total leaving the account, not the amount the creditor gets.
         * A limit applied to the amount alone would be quietly exceeded by every fee.
         */
        @Test
        void screens_the_total_debit_rather_than_the_amount() {
            Transfer transfer = sepaTransfer(125_000);

            drive(transfer.id());

            assertThat(risk.lastCommand().amount()).isEqualTo(Money.of(125_050, "EUR"));
            assertThat(risk.lastCommand().customerId()).isEqualTo("cust-1");
            assertThat(risk.lastCommand().reference()).isEqualTo(transfer.reference());
        }

        @Test
        void records_the_ledger_transaction_that_moved_the_money() {
            Transfer transfer = sepaTransfer(125_000);

            drive(transfer.id());

            assertThat(reload(transfer.id()).ledgerTransaction()).contains(ledger.lastPosted());
        }
    }

    @Nested
    @DisplayName("one step at a time")
    class OneStepAtATime {

        /**
         * The property that makes each step commit separately. A single call that ran the whole
         * saga could not have a transaction boundary between its steps.
         */
        @Test
        void each_call_takes_exactly_one_step() {
            Transfer transfer = sepaTransfer(125_000);

            assertThat(orchestrator.advanceOnce(transfer.id())).isEqualTo(SagaProgress.ADVANCED);
            assertThat(reload(transfer.id()).status()).isEqualTo(TransferStatus.SCREENING);

            assertThat(orchestrator.advanceOnce(transfer.id())).isEqualTo(SagaProgress.ADVANCED);
            assertThat(reload(transfer.id()).status()).isEqualTo(TransferStatus.POSTING);

            assertThat(orchestrator.advanceOnce(transfer.id())).isEqualTo(SagaProgress.ADVANCED);
            assertThat(reload(transfer.id()).status()).isEqualTo(TransferStatus.POSTED);

            assertThat(orchestrator.advanceOnce(transfer.id())).isEqualTo(SagaProgress.FINISHED);
            assertThat(reload(transfer.id()).status()).isEqualTo(TransferStatus.COMPLETED);
        }

        /**
         * Entering screening and acting on its answer are separate steps, so the saga is saved in
         * SCREENING before risk is called. A crash during that call then leaves a state that says
         * which call was in flight.
         */
        @Test
        void saves_the_state_before_making_the_call_that_belongs_to_it() {
            Transfer transfer = sepaTransfer(125_000);

            orchestrator.advanceOnce(transfer.id());

            assertThat(reload(transfer.id()).status()).isEqualTo(TransferStatus.SCREENING);
            assertThat(risk.callCount).isZero();
        }

        @Test
        void does_nothing_more_once_the_saga_is_finished() {
            Transfer transfer = sepaTransfer(125_000);
            drive(transfer.id());

            assertThat(orchestrator.advanceOnce(transfer.id())).isEqualTo(SagaProgress.FINISHED);
            assertThat(reload(transfer.id()).status()).isEqualTo(TransferStatus.COMPLETED);
        }

        @Test
        void refuses_an_unknown_transfer() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> orchestrator.advanceOnce(TransferId.newId()))
                    .withMessageContaining("no such transfer");
        }
    }

    @Nested
    @DisplayName("failing before the money moves")
    class RejectedBeforePosting {

        @Test
        void rejects_a_transfer_from_an_account_that_does_not_exist() {
            accounts.forget(DEBTOR);
            Transfer transfer = sepaTransfer(125_000);

            assertThat(drive(transfer.id())).isEqualTo(SagaProgress.FINISHED);
            assertRejected(transfer.id(), RejectionReason.UNKNOWN_ACCOUNT);
            assertThat(ledger.postings).isEmpty();
        }

        @Test
        void rejects_a_transfer_from_a_frozen_account() {
            accounts.register(new AccountSummary(DEBTOR, "cust-1", DEBTOR_LEDGER, EUR, false));
            Transfer transfer = sepaTransfer(125_000);

            drive(transfer.id());

            assertRejected(transfer.id(), RejectionReason.ACCOUNT_NOT_ACTIVE);
        }

        /** Posting EUR out of a DKK account would be a currency conversion nobody asked for. */
        @Test
        void rejects_a_transfer_in_a_currency_the_account_is_not_held_in() {
            accounts.register(new AccountSummary(
                    DEBTOR, "cust-1", DEBTOR_LEDGER, Currency.getInstance("DKK"), true));
            Transfer transfer = sepaTransfer(125_000);

            drive(transfer.id());

            assertRejected(transfer.id(), RejectionReason.ACCOUNT_NOT_ACTIVE);
        }

        /** An internal transfer needs both ends to exist; a SEPA one does not. */
        @Test
        void rejects_an_internal_transfer_to_an_account_that_does_not_exist() {
            accounts.forget(INTERNAL_CREDITOR);
            Transfer transfer = internalTransfer(125_000);

            drive(transfer.id());

            assertRejected(transfer.id(), RejectionReason.UNKNOWN_ACCOUNT);
        }

        @Test
        void does_not_require_a_sepa_beneficiary_to_exist_here() {
            Transfer transfer = sepaTransfer(125_000);

            assertThat(drive(transfer.id())).isEqualTo(SagaProgress.FINISHED);
            assertThat(reload(transfer.id()).status()).isEqualTo(TransferStatus.COMPLETED);
        }

        @Test
        void rejects_a_transfer_risk_blocked() {
            risk.answer = ScreeningVerdict.block("beneficiary matches sanctioned party");
            Transfer transfer = sepaTransfer(125_000);

            drive(transfer.id());

            assertRejected(transfer.id(), RejectionReason.SCREENING_BLOCKED);
            assertThat(ledger.postings).isEmpty();
        }

        @Test
        void rejects_a_transfer_the_ledger_refused() {
            ledger.refuseWith = "insufficient funds";
            Transfer transfer = sepaTransfer(125_000);

            drive(transfer.id());

            assertRejected(transfer.id(), RejectionReason.LEDGER_REFUSED);
        }

        private void assertRejected(TransferId id, RejectionReason expected) {
            Transfer transfer = reload(id);

            assertThat(transfer.status()).isEqualTo(TransferStatus.REJECTED);
            assertThat(transfer.state())
                    .isInstanceOfSatisfying(TransferState.Rejected.class, rejected ->
                            assertThat(rejected.reason()).isEqualTo(expected));

            // The invariant everything downstream depends on: REJECTED means nothing moved.
            assertThat(transfer.ledgerTransaction()).isEmpty();
        }
    }

    @Nested
    @DisplayName("parking for a person")
    class Parked {

        @Test
        void parks_a_transfer_risk_wants_reviewed() {
            risk.answer = ScreeningVerdict.review("beneficiary partially matches a listed party");
            Transfer transfer = sepaTransfer(125_000);

            assertThat(drive(transfer.id())).isEqualTo(SagaProgress.PARKED);
            assertThat(reload(transfer.id()).status()).isEqualTo(TransferStatus.APPROVAL_PENDING);
            assertThat(ledger.postings).isEmpty();
        }

        @Test
        void parks_a_transfer_over_the_four_eyes_threshold() {
            Transfer transfer = sepaTransfer(1_000_001);

            assertThat(drive(transfer.id())).isEqualTo(SagaProgress.PARKED);
            assertThat(reload(transfer.id()).status()).isEqualTo(TransferStatus.APPROVAL_PENDING);
        }

        @Test
        void lets_a_transfer_exactly_on_the_threshold_through() {
            Transfer transfer = sepaTransfer(1_000_000);

            assertThat(drive(transfer.id())).isEqualTo(SagaProgress.FINISHED);
            assertThat(reload(transfer.id()).status()).isEqualTo(TransferStatus.COMPLETED);
        }

        /** Nothing automatic moves a parked transfer. Calling again must not quietly resume it. */
        @Test
        void stays_parked_however_many_times_it_is_driven() {
            Transfer transfer = sepaTransfer(1_000_001);
            drive(transfer.id());

            assertThat(orchestrator.advanceOnce(transfer.id())).isEqualTo(SagaProgress.PARKED);
            assertThat(reload(transfer.id()).status()).isEqualTo(TransferStatus.APPROVAL_PENDING);
        }

        /** The history says which of the two reasons parked it, because they are different. */
        @Test
        void records_why_it_parked() {
            risk.answer = ScreeningVerdict.review("partial sanctions match");
            Transfer reviewed = sepaTransfer(100);
            drive(reviewed.id());

            risk.answer = ScreeningVerdict.allow("fine");
            Transfer large = sepaTransfer(1_000_001);
            drive(large.id());

            assertThat(lastNote(reviewed.id())).contains("risk asked for a review");
            assertThat(lastNote(large.id())).contains("four-eyes threshold");
        }

        private String lastNote(TransferId id) {
            List<io.lara.payments.domain.TransferTransition> history = reload(id).history();
            return history.get(history.size() - 1).note();
        }
    }

    @Nested
    @DisplayName("a dependency being unreachable")
    class Unreachable {

        /**
         * The distinction the whole {@link RemoteServiceException} type exists for. A risk
         * service that times out has decided nothing, so the saga must not read it as an allow
         * and must not read it as a block.
         */
        @Test
        void leaves_the_saga_where_it_is_rather_than_guessing() {
            risk.unreachable = true;
            Transfer transfer = sepaTransfer(125_000);

            orchestrator.advanceOnce(transfer.id());

            assertThatExceptionOfType(RemoteServiceException.class)
                    .isThrownBy(() -> orchestrator.advanceOnce(transfer.id()))
                    .satisfies(failure -> assertThat(failure.service()).isEqualTo("risk"));

            assertThat(reload(transfer.id()).status()).isEqualTo(TransferStatus.SCREENING);
            assertThat(ledger.postings).isEmpty();
        }

        /**
         * The most dangerous one. An unreachable ledger means the posting may or may not have
         * happened, so the saga stays in POSTING and recovery re-drives it — which is safe only
         * because the reference is derived from the transfer id.
         */
        @Test
        void leaves_a_transfer_in_posting_when_the_ledger_cannot_be_reached() {
            ledger.unreachable = true;
            Transfer transfer = sepaTransfer(125_000);

            orchestrator.advanceOnce(transfer.id());
            orchestrator.advanceOnce(transfer.id());

            assertThatExceptionOfType(RemoteServiceException.class)
                    .isThrownBy(() -> orchestrator.advanceOnce(transfer.id()));

            Transfer stalled = reload(transfer.id());
            assertThat(stalled.status()).isEqualTo(TransferStatus.POSTING);
            assertThat(stalled.isTerminal()).isFalse();
        }

        /** Once the dependency comes back, re-driving finishes the job with no special handling. */
        @Test
        void resumes_cleanly_once_the_dependency_recovers() {
            ledger.unreachable = true;
            Transfer transfer = sepaTransfer(125_000);

            orchestrator.advanceOnce(transfer.id());
            orchestrator.advanceOnce(transfer.id());
            assertThatExceptionOfType(RemoteServiceException.class)
                    .isThrownBy(() -> orchestrator.advanceOnce(transfer.id()));

            ledger.unreachable = false;

            assertThat(drive(transfer.id())).isEqualTo(SagaProgress.FINISHED);
            assertThat(reload(transfer.id()).status()).isEqualTo(TransferStatus.COMPLETED);
        }

        @Test
        void leaves_a_transfer_in_requested_when_accounts_cannot_be_reached() {
            accounts.unreachable = true;
            Transfer transfer = sepaTransfer(125_000);

            assertThatExceptionOfType(RemoteServiceException.class)
                    .isThrownBy(() -> orchestrator.advanceOnce(transfer.id()))
                    .satisfies(failure -> assertThat(failure.service()).isEqualTo("accounts"));

            assertThat(reload(transfer.id()).status()).isEqualTo(TransferStatus.REQUESTED);
        }
    }

    @Nested
    @DisplayName("when the scheme rejects a payment that has already been posted")
    class SchemeRejection {

        /**
         * The whole reason compensation exists. The ledger has moved the money, and the party
         * that was going to carry it onward has declined — so there is nothing to reject and
         * something real to undo.
         */
        @Test
        void compensates_rather_than_rejecting() {
            scheme.answer = SchemeAcknowledgement.rejected("beneficiary account closed");
            Transfer transfer = sepaTransfer(125_000);

            assertThat(drive(transfer.id())).isEqualTo(SagaProgress.FINISHED);

            Transfer settled = reload(transfer.id());
            assertThat(settled.status()).isEqualTo(TransferStatus.COMPENSATED);
            assertThat(settled.state())
                    .isInstanceOfSatisfying(TransferState.Compensated.class, compensated -> {
                        assertThat(compensated.ledgerTransaction()).isEqualTo(ledger.lastPosted());
                        assertThat(compensated.reversal()).isEqualTo(ledger.lastReversal());
                        assertThat(compensated.cause()).contains("beneficiary account closed");
                    });
        }

        /** Exactly one posting and exactly one reversal. The money went out and came back once. */
        @Test
        void posts_once_and_reverses_once() {
            scheme.answer = SchemeAcknowledgement.rejected("beneficiary account closed");
            Transfer transfer = sepaTransfer(125_000);

            drive(transfer.id());

            assertThat(ledger.postings).hasSize(1);
            assertThat(ledger.reversals).hasSize(1);
            assertThat(ledger.reversals.get(0)).isEqualTo(ledger.lastPosted());
        }

        @Test
        void records_both_the_posting_and_the_reversal_in_the_history() {
            scheme.answer = SchemeAcknowledgement.rejected("beneficiary account closed");
            Transfer transfer = sepaTransfer(125_000);

            drive(transfer.id());

            assertThat(reload(transfer.id()).history())
                    .extracting(h -> h.to().name())
                    .containsExactly("SCREENING", "POSTING", "POSTED", "COMPENSATING", "COMPENSATED");
        }

        /**
         * An internal transfer never touches a scheme, so this path cannot reach it. Both
         * accounts are at this bank and the ledger entry is the settlement.
         */
        @Test
        void never_happens_to_an_internal_transfer() {
            scheme.answer = SchemeAcknowledgement.rejected("would reject if asked");
            Transfer transfer = internalTransfer(125_000);

            assertThat(drive(transfer.id())).isEqualTo(SagaProgress.FINISHED);
            assertThat(reload(transfer.id()).status()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(scheme.submissions).isEmpty();
            assertThat(ledger.reversals).isEmpty();
        }

        /**
         * An unreachable scheme is not a rejection. The submission may well have landed, and
         * reversing a payment the scheme accepted would send the money back while the
         * beneficiary is also being paid — a far more expensive mistake than waiting.
         */
        @Test
        void does_not_compensate_merely_because_the_scheme_is_unreachable() {
            scheme.unreachable = true;
            Transfer transfer = sepaTransfer(125_000);

            orchestrator.advanceOnce(transfer.id());
            orchestrator.advanceOnce(transfer.id());
            orchestrator.advanceOnce(transfer.id());

            assertThatExceptionOfType(RemoteServiceException.class)
                    .isThrownBy(() -> orchestrator.advanceOnce(transfer.id()))
                    .satisfies(failure -> assertThat(failure.service()).isEqualTo("scheme"));

            assertThat(reload(transfer.id()).status()).isEqualTo(TransferStatus.POSTED);
            assertThat(ledger.reversals).isEmpty();
        }

        /** Once the scheme answers, the saga finishes normally with no special handling. */
        @Test
        void resumes_when_the_scheme_comes_back() {
            scheme.unreachable = true;
            Transfer transfer = sepaTransfer(125_000);
            for (int step = 0; step < 3; step++) {
                orchestrator.advanceOnce(transfer.id());
            }
            assertThatExceptionOfType(RemoteServiceException.class)
                    .isThrownBy(() -> orchestrator.advanceOnce(transfer.id()));

            scheme.unreachable = false;

            assertThat(drive(transfer.id())).isEqualTo(SagaProgress.FINISHED);
            assertThat(reload(transfer.id()).status()).isEqualTo(TransferStatus.COMPLETED);
        }

        /**
         * Nothing that fails before the posting may compensate: there is nothing to undo, and a
         * reversal of a posting that never happened would move money that was never moved.
         */
        @Test
        void a_failure_before_the_posting_never_reverses_anything() {
            risk.answer = ScreeningVerdict.block("sanctions match");
            Transfer transfer = sepaTransfer(125_000);

            drive(transfer.id());

            assertThat(reload(transfer.id()).status()).isEqualTo(TransferStatus.REJECTED);
            assertThat(ledger.postings).isEmpty();
            assertThat(ledger.reversals).isEmpty();
        }
    }

    @Nested
    @DisplayName("compensating")
    class Compensating {

        @Test
        void reverses_the_posting_and_ends_compensated() {
            Transfer transfer = sepaTransfer(125_000);
            orchestrator.advanceOnce(transfer.id());
            orchestrator.advanceOnce(transfer.id());
            orchestrator.advanceOnce(transfer.id());

            // Something downstream refuses the payment after the money has already moved.
            StoredTransfer posted = transfers.find(transfer.id()).orElseThrow();
            posted.transfer().startCompensation("scheme refused the payment");
            transfers.update(posted);

            assertThat(orchestrator.advanceOnce(transfer.id())).isEqualTo(SagaProgress.FINISHED);

            assertThat(reload(transfer.id()).state())
                    .isInstanceOfSatisfying(TransferState.Compensated.class, compensated -> {
                        assertThat(compensated.ledgerTransaction()).isEqualTo(ledger.lastPosted());
                        assertThat(compensated.reversal()).isEqualTo(ledger.lastReversal());
                    });
        }

        /** The reversal names the posting it is undoing, which is what the ledger reverses by. */
        @Test
        void reverses_the_transaction_that_moved_the_money() {
            Transfer transfer = sepaTransfer(125_000);
            orchestrator.advanceOnce(transfer.id());
            orchestrator.advanceOnce(transfer.id());
            orchestrator.advanceOnce(transfer.id());

            StoredTransfer posted = transfers.find(transfer.id()).orElseThrow();
            posted.transfer().startCompensation("scheme refused");
            transfers.update(posted);
            orchestrator.advanceOnce(transfer.id());

            assertThat(ledger.lastReversed).isEqualTo(ledger.lastPosted());
        }
    }

    // ------------------------------------------------------------------ in-memory ports

    /**
     * Storage with the same version semantics as the real adapter.
     *
     * <p>Modelling the optimistic lock rather than ignoring it matters: a double is only useful
     * if it fails the way the real thing fails, and a store that accepted every write would hide
     * exactly the bug the version column exists to catch.
     */
    private static final class InMemoryTransfers implements Transfers {

        private final Map<TransferId, StoredTransfer> rows = new HashMap<>();

        @Override
        public Optional<StoredTransfer> find(TransferId id) {
            // A fresh aggregate per read, so a caller cannot mutate the stored copy by accident.
            return Optional.ofNullable(rows.get(id)).map(TransferOrchestratorTest::copyOf);
        }

        @Override
        public Optional<StoredTransfer> findByReference(TransferReference reference) {
            return rows.values().stream()
                    .filter(stored -> stored.transfer().reference().equals(reference))
                    .findFirst()
                    .map(TransferOrchestratorTest::copyOf);
        }

        @Override
        public void add(Transfer transfer) {
            rows.put(transfer.id(), StoredTransfer.of(transfer, 0L));
        }

        @Override
        public void update(StoredTransfer stored) {
            StoredTransfer current = rows.get(stored.id());
            if (current == null) {
                throw new IllegalStateException("cannot update " + stored.id() + ": it does not exist");
            }
            if (current.version() != stored.version()) {
                throw new ConcurrentTransferModificationException(stored.id(), null);
            }
            rows.put(stored.id(), new StoredTransfer(stored.transfer(), stored.version() + 1, stored.recoveryAttempts()));
        }

        @Override
        public int recordRecoveryAttempt(TransferId id) {
            StoredTransfer current = rows.get(id);
            int attempts = current.recoveryAttempts() + 1;
            rows.put(id, new StoredTransfer(current.transfer(), current.version(), attempts));
            return attempts;
        }

        @Override
        public List<StoredTransfer> stuckIn(
                List<TransferStatus> states, Instant notTouchedSince, int limit) {
            return rows.values().stream()
                    .filter(stored -> !stored.transfer().isTerminal())
                    .filter(stored -> states.contains(stored.transfer().status()))
                    .limit(limit)
                    .toList();
        }
    }

    private static StoredTransfer copyOf(StoredTransfer stored) {
        Transfer original = stored.transfer();
        return new StoredTransfer(
                Transfer.rehydrate(
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
                        CLOCK),
                stored.version(),
                stored.recoveryAttempts());
    }

    private static final class StubAccounts implements AccountDirectory {

        private final Map<Iban, AccountSummary> known = new HashMap<>();
        private boolean unreachable;

        void register(AccountSummary account) {
            known.put(account.iban(), account);
        }

        void forget(Iban iban) {
            known.remove(iban);
        }

        @Override
        public Optional<AccountSummary> find(Iban iban) {
            if (unreachable) {
                throw new RemoteServiceException("accounts", "connection refused");
            }
            return Optional.ofNullable(known.get(iban));
        }
    }

    private static final class StubRisk implements RiskScreening {

        private ScreeningVerdict answer = ScreeningVerdict.allow("no sanctions match");
        private boolean unreachable;
        private int callCount;
        private final List<ScreeningCommand> commands = new ArrayList<>();

        @Override
        public ScreeningVerdict screen(ScreeningCommand command) {
            if (unreachable) {
                throw new RemoteServiceException("risk", "read timed out");
            }
            callCount++;
            commands.add(command);
            return answer;
        }

        ScreeningCommand lastCommand() {
            return commands.get(commands.size() - 1);
        }
    }

    private static final class StubScheme implements SchemeGateway {

        private SchemeAcknowledgement answer = SchemeAcknowledgement.accepted("accepted");
        private boolean unreachable;
        private final List<SchemeSubmission> submissions = new ArrayList<>();

        @Override
        public SchemeAcknowledgement submit(SchemeSubmission submission) {
            if (unreachable) {
                throw new RemoteServiceException("scheme", "no response");
            }
            submissions.add(submission);
            return answer;
        }
    }

    private static final class StubLedger implements LedgerPosting {

        private final List<PostingCommand> postings = new ArrayList<>();
        private final List<LedgerTransactionRef> posted = new ArrayList<>();
        private boolean unreachable;
        private String refuseWith;
        private final List<LedgerTransactionRef> reversals = new ArrayList<>();
        private LedgerTransactionRef lastReversal;
        private LedgerTransactionRef lastReversed;

        @Override
        public LedgerTransactionRef post(PostingCommand command) {
            if (unreachable) {
                throw new RemoteServiceException("ledger", "connection reset");
            }
            if (refuseWith != null) {
                throw new LedgerRefusedException(refuseWith);
            }
            postings.add(command);
            LedgerTransactionRef ref = LedgerTransactionRef.of(UUID.randomUUID());
            posted.add(ref);
            return ref;
        }

        @Override
        public LedgerTransactionRef reverse(LedgerTransactionRef original, String reason) {
            if (unreachable) {
                throw new RemoteServiceException("ledger", "connection reset");
            }
            lastReversed = original;
            reversals.add(original);
            lastReversal = LedgerTransactionRef.of(UUID.randomUUID());
            return lastReversal;
        }

        PostingCommand lastPosting() {
            return postings.get(postings.size() - 1);
        }

        LedgerTransactionRef lastPosted() {
            return posted.get(posted.size() - 1);
        }

        LedgerTransactionRef lastReversal() {
            return lastReversal;
        }
    }
}
