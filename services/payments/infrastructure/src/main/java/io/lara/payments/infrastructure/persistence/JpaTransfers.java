package io.lara.payments.infrastructure.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;

import io.lara.payments.application.ConcurrentTransferModificationException;
import io.lara.payments.application.StoredTransfer;
import io.lara.payments.application.Transfers;
import io.lara.payments.domain.Transfer;
import io.lara.payments.domain.TransferId;
import io.lara.payments.domain.TransferReference;
import io.lara.payments.domain.TransferStatus;

/**
 * Saga storage, guarded by a version column.
 *
 * <p>Optimistic rather than pessimistic, because saga steps are short and genuine contention is
 * rare — the common case is the orchestrator working alone, and making it take a row lock every
 * time would pay for a collision that almost never happens. When one does happen it is an
 * exception to retry against fresh state, not a queue to wait in.
 */
@ApplicationScoped
public class JpaTransfers implements Transfers {

    private final EntityManager entityManager;
    private final Clock clock;

    public JpaTransfers(EntityManager entityManager, Clock clock) {
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<StoredTransfer> find(TransferId id) {
        return Optional.ofNullable(entityManager.find(TransferEntity.class, id.value()))
                .map(this::stored);
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<StoredTransfer> findByReference(TransferReference reference) {
        return entityManager
                .createQuery("select t from TransferEntity t where t.reference = :reference",
                        TransferEntity.class)
                .setParameter("reference", reference.value())
                .getResultStream()
                .findFirst()
                .map(this::stored);
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void add(Transfer transfer) {
        entityManager.persist(TransferEntity.from(transfer, clock.instant()));
    }

    /**
     * Writes an advanced saga back, or refuses if someone else moved it first.
     *
     * <p>Guarded twice, because the two checks catch different races.
     *
     * <p>The explicit comparison catches the one that matters: the caller read this saga at some
     * revision, spent time deciding what to do with it, and is only now writing back. Hibernate
     * cannot catch that alone — the row is re-read here, so its generated
     * {@code update ... where version = ?} would quote the <em>current</em> revision and pass
     * every time.
     *
     * <p>Hibernate's own check then covers the narrow window between that comparison and the
     * write, where a third party commits in between.
     *
     * <p>The flush is forced rather than left to commit time. A conflict discovered at commit
     * surfaces far from the step that caused it and too late for the caller to reload and retry,
     * which is the entire reason for detecting it rather than just preventing it.
     */
    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void update(StoredTransfer stored) {
        Transfer transfer = stored.transfer();

        TransferEntity managed = entityManager.find(TransferEntity.class, transfer.id().value());
        if (managed == null) {
            throw new IllegalStateException("cannot update transfer " + transfer.id() + ": it does not exist");
        }

        // The check that actually matters. The caller read this saga at some revision, went away
        // to decide what to do — possibly calling another service in between — and is only now
        // writing back. If the row has moved on since, this write was built on a state that no
        // longer exists and must be refused.
        //
        // Hibernate's own version check cannot catch this on its own: the find() above loads
        // whatever is current, so the generated "where version = ?" would use the *current*
        // revision and pass every time.
        if (managed.version() != stored.version()) {
            throw new ConcurrentTransferModificationException(transfer.id(), null);
        }

        managed.applyState(transfer, clock.instant());

        try {
            // Force the write now rather than at commit. A conflict discovered at commit time
            // surfaces far from the step that caused it and too late for the caller to reload
            // and retry, which is the entire point of detecting it.
            //
            // This also closes the remaining gap: another transaction committing between the
            // comparison above and this flush loses here instead, because Hibernate's update
            // carries the version it loaded.
            entityManager.flush();
        } catch (OptimisticLockException conflict) {
            throw new ConcurrentTransferModificationException(transfer.id(), conflict);
        }
    }

    /**
     * Stalled sagas, oldest first.
     *
     * <p>Terminal states are dropped from the request rather than trusted not to appear in it. A
     * sweep that re-drove a completed transfer would attempt its posting again, and while the
     * ledger's idempotency would absorb that, relying on a downstream guarantee to cover a query
     * this service got wrong is not a position worth being in. The partial index backing this
     * query excludes them too, so asking for one would also be a sequential scan.
     */
    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public List<StoredTransfer> stuckIn(List<TransferStatus> states, Instant notTouchedSince, int limit) {
        List<String> live = states.stream()
                .filter(status -> !status.isTerminal())
                .map(Enum::name)
                .toList();
        if (live.isEmpty()) {
            return List.of();
        }

        return entityManager
                .createQuery("""
                        select t from TransferEntity t
                        where t.status in :states
                          and t.lastTouchedAt < :since
                        order by t.lastTouchedAt asc
                        """, TransferEntity.class)
                .setParameter("states", live)
                .setParameter("since", notTouchedSince)
                .setMaxResults(limit)
                .getResultList()
                .stream()
                .map(this::stored)
                .toList();
    }

    private StoredTransfer stored(TransferEntity entity) {
        return new StoredTransfer(entity.toDomain(clock), entity.version());
    }
}
