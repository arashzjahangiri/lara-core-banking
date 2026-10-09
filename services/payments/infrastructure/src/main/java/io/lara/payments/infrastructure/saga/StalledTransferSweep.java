package io.lara.payments.infrastructure.saga;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.lara.payments.application.ConcurrentTransferModificationException;
import io.lara.payments.application.RemoteServiceException;
import io.lara.payments.application.StoredTransfer;
import io.lara.payments.application.Transfers;
import io.lara.payments.domain.TransferStatus;

/**
 * Finds sagas that have stopped moving and starts them again.
 *
 * <p>Everything else in this service assumes the orchestrator stays alive long enough to finish
 * what it started. It will not. A pod restart mid-saga leaves a row saying {@code POSTING} with
 * nothing driving it, and without this that transfer sits there until somebody notices — which,
 * for a payment, means until the customer calls.
 *
 * <h2>Why resumption is safe</h2>
 *
 * <p>The sweep re-drives the step the saga is sitting in rather than restarting it, and that is
 * only safe because every downstream call is idempotent. The posting reference is derived from
 * the transfer id, so re-posting returns the transaction the ledger already recorded; risk
 * returns the decision it already made; reversal collapses to the contra entry that already
 * exists. None of that is incidental — it is why this ticket comes after those.
 *
 * <h2>Why it cannot race the live orchestrator</h2>
 *
 * <p>It goes through exactly the same path, and that path takes the optimistic lock. If a live
 * orchestrator advances a saga while the sweep is deciding what to do with it, the sweep's write
 * is refused and it reloads — which is the correct outcome, because the saga did move, just not
 * by this hand.
 *
 * <h2>What it leaves alone</h2>
 *
 * <p>Transfers waiting for a person. {@code APPROVAL_PENDING} has no timer and is not stalled: it
 * is working exactly as intended and can sit for days. Sweeping it would either resume a payment
 * nobody approved or fill the log with transfers that are not actually stuck.
 */
@ApplicationScoped
public class StalledTransferSweep {

    private static final Logger LOG = Logger.getLogger(StalledTransferSweep.class);

    /**
     * The states a saga can stall in.
     *
     * <p>Every non-terminal state except {@code APPROVAL_PENDING}, which is waiting on a human
     * rather than stalled. Listed rather than derived, so adding a state is a decision about
     * whether recovery should touch it rather than something that happens by default.
     */
    private static final List<TransferStatus> RECOVERABLE = List.of(
            TransferStatus.REQUESTED,
            TransferStatus.SCREENING,
            TransferStatus.POSTING,
            TransferStatus.POSTED,
            TransferStatus.COMPENSATING);

    private final Transfers transfers;
    private final TransferSagaRunner runner;
    private final Clock clock;
    private final Duration stallTimeout;
    private final int batchSize;
    private final int maxAttempts;

    public StalledTransferSweep(
            Transfers transfers,
            TransferSagaRunner runner,
            Clock clock,
            @ConfigProperty(name = "payments.recovery.stall-timeout", defaultValue = "PT2M") String stallTimeout,
            @ConfigProperty(name = "payments.recovery.batch-size", defaultValue = "50") int batchSize,
            @ConfigProperty(name = "payments.recovery.max-attempts", defaultValue = "5") int maxAttempts) {

        this.transfers = transfers;
        this.runner = runner;
        this.clock = clock;
        this.stallTimeout = Duration.parse(stallTimeout);
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    /**
     * {@code SKIP} rather than queueing. If one run is still working, starting a second would put
     * two sweeps on the same batch of sagas — they would spend the time losing optimistic locks
     * to each other rather than making progress.
     */
    @Scheduled(every = "{payments.recovery.interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        SweepReport report = sweepNow();
        if (report.attempted() > 0) {
            LOG.infof("recovery swept %d stalled transfers: %d resumed, %d given up",
                    report.attempted(), report.resumed(), report.abandoned());
        }
    }

    /**
     * Runs one sweep and reports what it did.
     *
     * <p>Separate from the scheduled method so a test can drive it directly. A test that waits
     * for a scheduler is either slow or flaky, and usually both.
     */
    public SweepReport sweepNow() {
        List<StoredTransfer> stalled = QuarkusTransaction.requiringNew().call(() ->
                transfers.stuckIn(RECOVERABLE, clock.instant().minus(stallTimeout), batchSize));

        int resumed = 0;
        int abandoned = 0;

        for (StoredTransfer stored : stalled) {
            // Counted before the attempt, in its own transaction. A resumption that fails and
            // rolls back must still leave the record of having been tried, or the counter never
            // moves and this saga is retried forever.
            int attempts = transfers.recordRecoveryAttempt(stored.id());

            if (attempts > maxAttempts) {
                abandon(stored, attempts);
                abandoned++;
                continue;
            }

            if (resume(stored, attempts)) {
                resumed++;
            }
        }

        return new SweepReport(stalled.size(), resumed, abandoned);
    }

    /** Re-drives the step the saga is sitting in. */
    private boolean resume(StoredTransfer stored, int attempts) {
        try {
            LOG.debugf("resuming %s from %s (attempt %d)",
                    stored.id(), stored.transfer().status(), attempts);
            runner.drive(stored.id());
            return true;

        } catch (ConcurrentTransferModificationException raced) {
            // The live orchestrator got there first. The saga moved, which is the point.
            LOG.debugf("transfer %s was advanced by someone else while sweeping", stored.id());
            return false;

        } catch (RemoteServiceException stillDown) {
            // The dependency has not come back. Leave the saga where it is and try next time.
            LOG.debugf("transfer %s still blocked on %s", stored.id(), stillDown.service());
            return false;

        } catch (RuntimeException unexpected) {
            // One bad saga must not stop the sweep reaching the rest of the batch.
            LOG.warnf(unexpected, "could not resume transfer %s", stored.id());
            return false;
        }
    }

    /**
     * Gives up on a saga and asks for a person.
     *
     * <p>{@code FAILED} is terminal, so the next sweep will not see it again. That is the
     * intended effect: a transfer nothing automatic can fix should stop generating noise and
     * start generating an alert.
     *
     * <p>A transfer in {@code POSTED} or {@code COMPENSATING} cannot be failed — the state
     * machine forbids abandoning money that has moved — so those are left alone and logged
     * loudly instead. That is the right trade: an operator looking at a stuck reversal is far
     * better than a row that claims to be finished while the money sits in suspense.
     */
    private void abandon(StoredTransfer stored, int attempts) {
        String cause = "gave up after " + (attempts - 1) + " recovery attempts";

        if (stored.transfer().status() == TransferStatus.POSTED
                || stored.transfer().status() == TransferStatus.COMPENSATING) {

            LOG.errorf("transfer %s is stuck in %s after %d attempts and holds money that has "
                            + "moved; it needs a person",
                    stored.id(), stored.transfer().status(), attempts - 1);
            return;
        }

        try {
            QuarkusTransaction.requiringNew().run(() -> {
                stored.transfer().fail(cause);
                transfers.update(stored);
            });
            LOG.warnf("transfer %s %s", stored.id(), cause);

        } catch (ConcurrentTransferModificationException raced) {
            LOG.debugf("transfer %s moved while being abandoned; leaving it", stored.id());
        }
    }

    /** What one sweep did. */
    public record SweepReport(int attempted, int resumed, int abandoned) {
    }
}
