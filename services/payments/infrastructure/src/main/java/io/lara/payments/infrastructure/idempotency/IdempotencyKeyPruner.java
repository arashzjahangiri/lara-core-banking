package io.lara.payments.infrastructure.idempotency;

import java.time.Clock;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.scheduler.Scheduled;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Deletes idempotency keys that have outlived their usefulness.
 *
 * <p>Without this the table grows for as long as the service runs. The rows are small, which is
 * exactly why it would go unnoticed until the index no longer fits in memory and every POST
 * slows down together.
 *
 * <p>Deleted in batches rather than in one statement. A single unbounded delete after a busy
 * period takes a long lock on a table that every incoming request needs, turning a cleanup job
 * into an outage.
 */
@ApplicationScoped
public class IdempotencyKeyPruner {

    private static final Logger LOG = Logger.getLogger(IdempotencyKeyPruner.class);

    private final IdempotencyKeys keys;
    private final Clock clock;
    private final int batchSize;
    private final int maxBatches;

    public IdempotencyKeyPruner(
            IdempotencyKeys keys,
            Clock clock,
            @ConfigProperty(name = "payments.idempotency.prune-batch-size", defaultValue = "500") int batchSize,
            @ConfigProperty(name = "payments.idempotency.prune-max-batches", defaultValue = "20") int maxBatches) {

        this.keys = keys;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
    }

    @Scheduled(every = "{payments.idempotency.prune-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void prune() {
        LOG.debugf("pruned %d expired idempotency keys", pruneNow());
    }

    /**
     * Runs the prune and reports how many rows went.
     *
     * <p>Separate from the scheduled method so a test can drive it directly. A test that had to
     * wait for a scheduler is a test that is either slow or flaky, and usually both.
     *
     * <p>Stops after a fixed number of batches even if more remain. The next run picks up where
     * this one left off, and a job that cannot be convinced to stop is worse than a backlog.
     */
    public int pruneNow() {
        int deleted = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            int inThisBatch = keys.pruneExpired(clock.instant(), batchSize);
            deleted += inThisBatch;
            if (inThisBatch < batchSize) {
                break;
            }
        }
        return deleted;
    }
}