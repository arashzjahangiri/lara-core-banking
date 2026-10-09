package io.lara.payments.infrastructure.saga;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.narayana.jta.QuarkusTransaction;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.lara.payments.application.ConcurrentTransferModificationException;
import io.lara.payments.application.SagaProgress;
import io.lara.payments.application.TransferOrchestrator;
import io.lara.payments.domain.TransferId;

/**
 * Runs saga steps, each in its own transaction.
 *
 * <p>The orchestrator takes one step per call and knows nothing about transactions — it cannot,
 * since the application layer is annotation-free and the architecture test enforces that. This
 * is where the boundary actually goes, and it goes around each step individually.
 *
 * <p>That matters more than it looks. One transaction spanning the whole saga would hold a
 * database connection open across three network calls to other services, and a crash anywhere in
 * it would roll back the record of steps that genuinely happened — including a ledger posting
 * this service no longer remembered making.
 *
 * <h2>Retrying a conflict</h2>
 *
 * <p>A step that loses the optimistic lock is retried against freshly loaded state, bounded. The
 * conflict means someone else — almost always the recovery sweep — advanced the same saga first,
 * so the right response is to look again rather than to fail. A saga that keeps losing is a saga
 * something is wrong with, and spinning on it forever would turn a conflict into an outage.
 */
@ApplicationScoped
public class TransferSagaRunner {

    private static final Logger LOG = Logger.getLogger(TransferSagaRunner.class);

    private final TransferOrchestrator orchestrator;
    private final int maxSteps;
    private final int maxConflictRetries;

    public TransferSagaRunner(
            TransferOrchestrator orchestrator,
            @ConfigProperty(name = "payments.saga.max-steps", defaultValue = "12") int maxSteps,
            @ConfigProperty(name = "payments.saga.max-conflict-retries", defaultValue = "3") int maxConflictRetries) {

        this.orchestrator = orchestrator;
        this.maxSteps = maxSteps;
        this.maxConflictRetries = maxConflictRetries;
    }

    /**
     * Drives the saga until it parks, finishes, or runs out of steps.
     *
     * <p>The step cap is a guard against a bug, not a business rule. A healthy saga reaches a
     * terminal state in five steps; one that is still going after twelve is looping, and hanging
     * a request thread on it would be the worst available outcome.
     *
     * @return where the saga ended up
     */
    public SagaProgress drive(TransferId id) {
        SagaProgress progress = SagaProgress.ADVANCED;

        for (int step = 0; step < maxSteps && progress == SagaProgress.ADVANCED; step++) {
            progress = advanceWithRetry(id);
        }

        if (progress == SagaProgress.ADVANCED) {
            LOG.warnf("transfer %s did not settle within %d steps; leaving it to the recovery sweep",
                    id, maxSteps);
        }
        return progress;
    }

    /** One step, in its own transaction, retried if someone else got there first. */
    private SagaProgress advanceWithRetry(TransferId id) {
        ConcurrentTransferModificationException lastConflict = null;

        for (int attempt = 0; attempt <= maxConflictRetries; attempt++) {
            try {
                return QuarkusTransaction.requiringNew().call(() -> orchestrator.advanceOnce(id));
            } catch (ConcurrentTransferModificationException conflict) {
                // Someone advanced this saga first. Loading it again is the whole remedy.
                lastConflict = conflict;
                LOG.debugf("conflict advancing %s, attempt %d", id, attempt + 1);
            }
        }

        throw new IllegalStateException(
                "gave up advancing transfer " + id + " after " + (maxConflictRetries + 1)
                        + " conflicting attempts", lastConflict);
    }
}