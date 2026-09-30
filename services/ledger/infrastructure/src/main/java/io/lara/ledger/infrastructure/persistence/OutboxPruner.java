package io.lara.ledger.infrastructure.persistence;

import java.time.Duration;
import java.time.Instant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.scheduler.Scheduled;

/**
 * Deletes outbox rows once they are safely published.
 *
 * <p>The outbox is a transport buffer, not an audit record — the postings themselves are the audit
 * record, and those are never deleted. Left unpruned the table grows without bound and the index
 * that CDC relies on degrades with it.
 *
 * <p>Deletion is safe because Debezium captures the <em>insert</em> from the write-ahead log, not
 * the row's continued existence. Once the connector's replication slot has advanced past an
 * insert, the row has done its job. The retention window is the margin for a connector that is
 * behind or briefly down: anything younger than the window is left alone, so a connector can be
 * stopped for an hour without losing events.
 *
 * <p>Widen the window rather than narrow it if the connector is ever offline for longer. The cost
 * of keeping rows too long is disk; the cost of deleting too early is a lost event.
 */
@ApplicationScoped
public class OutboxPruner {

    private static final Logger LOG = Logger.getLogger(OutboxPruner.class);

    private final EntityManager entityManager;
    private final Duration retention;

    public OutboxPruner(EntityManager entityManager,
            @ConfigProperty(name = "ledger.outbox.retention", defaultValue = "PT1H") Duration retention) {

        this.entityManager = entityManager;
        this.retention = retention;
    }

    @Scheduled(every = "{ledger.outbox.prune-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    public void prune() {
        Instant cutoff = Instant.now().minus(retention);

        int deleted = entityManager
                .createNativeQuery("delete from outbox where created_at < :cutoff")
                .setParameter("cutoff", cutoff)
                .executeUpdate();

        if (deleted > 0) {
            LOG.infov("Pruned {0} outbox rows published before {1}", deleted, cutoff);
        }
    }
}
