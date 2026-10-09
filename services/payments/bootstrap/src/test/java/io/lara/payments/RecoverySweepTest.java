package io.lara.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.lara.payments.application.StoredTransfer;
import io.lara.payments.application.Transfers;
import io.lara.payments.domain.Iban;
import io.lara.payments.domain.LedgerTransactionRef;
import io.lara.payments.domain.Money;
import io.lara.payments.domain.RoutingDecision;
import io.lara.payments.domain.Transfer;
import io.lara.payments.domain.TransferId;
import io.lara.payments.domain.TransferScheme;
import io.lara.payments.domain.TransferStatus;
import io.lara.payments.infrastructure.saga.StalledTransferSweep;

/**
 * Crash recovery, against a real PostgreSQL.
 *
 * <p>A crash is simulated by persisting a saga in a non-terminal state with nothing driving it —
 * which is exactly what a pod restart mid-step leaves behind, and is far more controllable than
 * actually killing a process. What matters is the row, not how it got there.
 *
 * <p>The saga's dependencies are not running, so resumption cannot complete a transfer here. That
 * is the useful case to pin down anyway: the sweep must pick the saga up, count the attempt,
 * leave it recoverable, and eventually give up rather than retrying forever. Completing a
 * recovered transfer against live services belongs with the end-to-end suite.
 */
@QuarkusTest
class RecoverySweepTest {

    @Inject
    Transfers transfers;

    @Inject
    StalledTransferSweep sweep;

    @Inject
    Clock clock;

    @Inject
    EntityManager entityManager;

    private static final Iban DEBTOR = Iban.of("DK5000400440116243");
    private static final Iban CREDITOR = Iban.of("DE89370400440532013000");

    private static final RoutingDecision SEPA = new RoutingDecision(
            TransferScheme.SEPA_CREDIT_TRANSFER, Money.of(50, "EUR"), LocalDate.of(2026, 10, 5));

    /**
     * Persists a saga in the given state and backdates it past the stall timeout.
     *
     * <p>The backdating is what makes it visible to the sweep: a saga that moved a second ago is
     * not stalled, however stuck it may be about to become.
     */
    private TransferId stalledIn(TransferStatus status) {
        Transfer transfer = Transfer.request(
                TransferId.newId(), DEBTOR, CREDITOR, Money.of(125_000, "EUR"), SEPA, "arash", clock);

        QuarkusTransaction.requiringNew().run(() -> transfers.add(transfer));

        if (status != TransferStatus.REQUESTED) {
            StoredTransfer stored = load(transfer.id());
            Transfer loaded = stored.transfer();
            switch (status) {
                case SCREENING -> loaded.startScreening();
                case POSTING -> {
                    loaded.startScreening();
                    loaded.startPosting();
                }
                case POSTED -> {
                    loaded.startScreening();
                    loaded.startPosting();
                    loaded.posted(LedgerTransactionRef.of(UUID.randomUUID()));
                }
                case APPROVAL_PENDING -> {
                    loaded.startScreening();
                    loaded.awaitApproval("over the four-eyes threshold");
                }
                default -> throw new IllegalArgumentException("unsupported start state " + status);
            }
            QuarkusTransaction.requiringNew().run(() -> transfers.update(stored));
        }

        backdate(transfer.id(), Duration.ofHours(1));
        return transfer.id();
    }

    private StoredTransfer load(TransferId id) {
        return QuarkusTransaction.requiringNew().call(() -> transfers.find(id)).orElseThrow();
    }

    /** Makes a saga look as though it stopped moving a while ago. */
    private void backdate(TransferId id, Duration by) {
        QuarkusTransaction.requiringNew().run(() -> entityManager
                .createNativeQuery("update transfer set last_touched_at = :when where id = :id")
                .setParameter("when", Instant.now().minus(by))
                .setParameter("id", id.value())
                .executeUpdate());
    }

    private int attemptsFor(TransferId id) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager
                .createNativeQuery("select recovery_attempts from transfer where id = :id")
                .setParameter("id", id.value())
                .getSingleResult()).intValue());
    }

    @Nested
    @DisplayName("what the sweep picks up")
    class Selection {

        @Test
        void finds_a_saga_left_in_flight_by_a_crash() {
            TransferId stalled = stalledIn(TransferStatus.SCREENING);

            sweep.sweepNow();

            assertThat(attemptsFor(stalled)).isEqualTo(1);
        }

        @Test
        void finds_one_stalled_at_every_recoverable_step() {
            List<TransferId> stalled = List.of(
                    stalledIn(TransferStatus.REQUESTED),
                    stalledIn(TransferStatus.SCREENING),
                    stalledIn(TransferStatus.POSTING),
                    stalledIn(TransferStatus.POSTED));

            sweep.sweepNow();

            assertThat(stalled).allSatisfy(id -> assertThat(attemptsFor(id)).isEqualTo(1));
        }

        /**
         * A saga that moved recently is not stalled. Sweeping it would race a live orchestrator
         * mid-step for no reason.
         */
        @Test
        void ignores_one_that_moved_recently() {
            TransferId fresh = stalledIn(TransferStatus.SCREENING);
            backdate(fresh, Duration.ZERO);

            sweep.sweepNow();

            assertThat(attemptsFor(fresh)).isZero();
        }

        /**
         * Waiting for a person is not being stuck. A transfer can sit in {@code APPROVAL_PENDING}
         * for days and that is the feature working, so resuming it would either push through a
         * payment nobody approved or fill the log with false alarms.
         */
        @Test
        void never_touches_a_transfer_waiting_for_approval() {
            TransferId waiting = stalledIn(TransferStatus.APPROVAL_PENDING);

            sweep.sweepNow();

            assertThat(attemptsFor(waiting)).isZero();
            assertThat(load(waiting).transfer().status()).isEqualTo(TransferStatus.APPROVAL_PENDING);
        }

        /** Finished transfers only accumulate; picking one up would re-drive a settled payment. */
        @Test
        void never_touches_a_terminal_transfer() {
            TransferId done = stalledIn(TransferStatus.SCREENING);
            StoredTransfer stored = load(done);
            stored.transfer().reject(
                    io.lara.payments.domain.RejectionReason.SCREENING_BLOCKED, "blocked");
            QuarkusTransaction.requiringNew().run(() -> transfers.update(stored));
            backdate(done, Duration.ofHours(1));

            sweep.sweepNow();

            assertThat(attemptsFor(done)).isZero();
        }
    }

    @Nested
    @DisplayName("resumption")
    class Resumption {

        /**
         * Re-drives the step the saga is in rather than starting over. A restarted saga would
         * re-screen and re-post a transfer that had already done both.
         */
        @Test
        void leaves_the_saga_in_a_state_it_can_still_be_resumed_from() {
            TransferId stalled = stalledIn(TransferStatus.POSTING);

            sweep.sweepNow();

            // The ledger is not running, so the step cannot complete — and the saga must stay
            // exactly where it was rather than being dragged somewhere it does not belong.
            assertThat(load(stalled).transfer().status()).isEqualTo(TransferStatus.POSTING);
            assertThat(load(stalled).transfer().isTerminal()).isFalse();
        }

        /** One attempt per sweep, counted whether or not the resumption got anywhere. */
        @Test
        void counts_one_attempt_per_sweep() {
            TransferId stalled = stalledIn(TransferStatus.SCREENING);

            sweep.sweepNow();
            backdate(stalled, Duration.ofHours(1));
            sweep.sweepNow();
            backdate(stalled, Duration.ofHours(1));
            sweep.sweepNow();

            assertThat(attemptsFor(stalled)).isEqualTo(3);
        }

        /** One broken saga must not stop the sweep reaching the rest of the batch. */
        @Test
        void keeps_going_past_a_saga_it_cannot_resume() {
            TransferId first = stalledIn(TransferStatus.POSTING);
            TransferId second = stalledIn(TransferStatus.SCREENING);

            sweep.sweepNow();

            assertThat(attemptsFor(first)).isEqualTo(1);
            assertThat(attemptsFor(second)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("giving up")
    class GivingUp {

        /**
         * Past the cap the saga moves to {@code FAILED}, which is terminal — so the next sweep
         * does not see it. A transfer nothing automatic can fix should stop generating noise and
         * start generating an alert.
         */
        @Test
        void fails_a_saga_that_has_exhausted_its_attempts() {
            TransferId stalled = stalledIn(TransferStatus.SCREENING);
            exhaustAttempts(stalled);

            sweep.sweepNow();

            assertThat(load(stalled).transfer().status()).isEqualTo(TransferStatus.FAILED);
        }

        @Test
        void stops_picking_it_up_afterwards() {
            TransferId stalled = stalledIn(TransferStatus.SCREENING);
            exhaustAttempts(stalled);
            sweep.sweepNow();

            int attemptsWhenFailed = attemptsFor(stalled);
            backdate(stalled, Duration.ofHours(1));
            sweep.sweepNow();

            assertThat(attemptsFor(stalled)).isEqualTo(attemptsWhenFailed);
        }

        /**
         * The one case where giving up is not allowed. The ledger holds the money, and a saga
         * that walked away would leave a row claiming to be finished while the funds sat in
         * suspense. The state machine forbids it; the sweep logs for a person instead.
         */
        @Test
        void refuses_to_abandon_a_transfer_whose_money_has_already_moved() {
            TransferId posted = stalledIn(TransferStatus.POSTED);
            exhaustAttempts(posted);

            sweep.sweepNow();

            Transfer transfer = load(posted).transfer();
            assertThat(transfer.status()).isEqualTo(TransferStatus.POSTED);
            assertThat(transfer.ledgerTransaction()).isPresent();
        }

        /** Drives the counter to the cap without going through the sweep. */
        private void exhaustAttempts(TransferId id) {
            QuarkusTransaction.requiringNew().run(() -> entityManager
                    .createNativeQuery("update transfer set recovery_attempts = 99 where id = :id")
                    .setParameter("id", id.value())
                    .executeUpdate());
        }
    }

    @Nested
    @DisplayName("the report")
    class Report {

        @Test
        void counts_what_it_attempted_and_what_it_gave_up_on() {
            stalledIn(TransferStatus.SCREENING);
            stalledIn(TransferStatus.REQUESTED);

            StalledTransferSweep.SweepReport report = sweep.sweepNow();

            assertThat(report.attempted()).isGreaterThanOrEqualTo(2);
            assertThat(report.resumed() + report.abandoned())
                    .isLessThanOrEqualTo(report.attempted());
        }

        /** A quiet system produces a quiet sweep, and no log line at all. */
        @Test
        void reports_nothing_when_there_is_nothing_stalled() {
            QuarkusTransaction.requiringNew().run(() -> entityManager
                    .createNativeQuery("update transfer set last_touched_at = now()")
                    .executeUpdate());

            assertThat(sweep.sweepNow().attempted()).isZero();
        }
    }
}
