package io.lara.ledger.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;

import org.hibernate.exception.ConstraintViolationException;

import io.lara.ledger.application.LedgerTransactions;
import io.lara.ledger.domain.AccountId;
import io.lara.ledger.domain.LedgerTransaction;
import io.lara.ledger.domain.TransactionId;
import io.lara.ledger.domain.TransactionReference;

/**
 * Appends postings and reads them back.
 *
 * <p>The transaction and all of its legs are written in one database transaction, so a half-written
 * movement cannot exist.
 */
@ApplicationScoped
public class JpaLedgerTransactions implements LedgerTransactions {

    private static final String REFERENCE_UNIQUE_CONSTRAINT = "ledger_transaction_reference_unique";

    private final EntityManager entityManager;
    private final TransactionPostedEvents events;

    public JpaLedgerTransactions(EntityManager entityManager, TransactionPostedEvents events) {
        this.entityManager = entityManager;
        this.events = events;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The use case checks for an existing reference before calling this, but that check and this
     * insert are not one atomic step: two concurrent posts of the same reference can both find
     * nothing and both arrive here. The unique constraint on {@code reference} is what decides the
     * race, and catching its violation turns the loser into the same answer the winner got.
     *
     * <p>Relying on the constraint rather than a lock is deliberate. The database already has to
     * enforce uniqueness, so using the same mechanism for the race means there is one rule, not two
     * that could disagree.
     */
    @Override
    @Transactional
    public LedgerTransaction append(LedgerTransaction transaction) {
        try {
            entityManager.persist(LedgerTransactionEntity.from(transaction));
            // Same persistence context, same database transaction. If the insert below fails —
            // on the unique reference, say — these rows roll back with it, so no event can
            // describe a posting that did not happen.
            events.recordFor(transaction);
            entityManager.flush();
            return transaction;
        } catch (PersistenceException e) {
            if (!isDuplicateReference(e)) {
                throw e;
            }
            // Someone else recorded this reference first. Their transaction is the one that
            // happened, so answer with it rather than reporting a failure for an operation that
            // did, in the sense the caller cares about, succeed.
            entityManager.clear();
            return findByReference(transaction.reference()).orElseThrow(() -> e);
        }
    }

    @Override
    public Optional<LedgerTransaction> findByReference(TransactionReference reference) {
        return entityManager
                .createQuery("from LedgerTransactionEntity where reference = :reference",
                        LedgerTransactionEntity.class)
                .setParameter("reference", reference.value())
                .getResultStream()
                .findFirst()
                .map(LedgerTransactionEntity::toDomain);
    }

    @Override
    public Optional<LedgerTransaction> findById(TransactionId id) {
        return Optional.ofNullable(entityManager.find(LedgerTransactionEntity.class, id.value()))
                .map(LedgerTransactionEntity::toDomain);
    }

    @Override
    public List<LedgerTransaction> findByAccountUpTo(AccountId account, Instant asOf) {
        return entityManager
                .createQuery("""
                        select distinct t from LedgerTransactionEntity t
                        join t.legs leg
                        where leg.accountId = :account and t.occurredAt <= :asOf
                        order by t.occurredAt, t.id
                        """, LedgerTransactionEntity.class)
                .setParameter("account", account.value())
                .setParameter("asOf", asOf)
                .getResultList()
                .stream()
                .map(LedgerTransactionEntity::toDomain)
                .toList();
    }

    private static boolean isDuplicateReference(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                return name != null && name.toLowerCase().contains(REFERENCE_UNIQUE_CONSTRAINT);
            }
        }
        return false;
    }
}
