package io.lara.accounts.infrastructure.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import io.lara.accounts.application.AppliedPostings;

/**
 * Claims a posting by inserting its primary key, and lets the database decide the race.
 *
 * <p>{@code ON CONFLICT DO NOTHING} returns the number of rows inserted: one if this call claimed
 * the posting, zero if it was already there. That is the whole mechanism, and it is atomic —
 * which a read-then-write would not be. Two consumer threads handling the same redelivered event
 * can both reach this method, and exactly one of them gets the 1.
 *
 * <p>Catching a constraint violation instead would work too, but it makes an ordinary duplicate
 * into an exception on a hot path, and exceptions are expensive and easy to swallow by accident.
 */
@ApplicationScoped
public class JdbcAppliedPostings implements AppliedPostings {

    private final EntityManager entityManager;

    public JdbcAppliedPostings(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean claim(String ledgerAccountId, String transactionId) {
        int inserted = entityManager.createNativeQuery("""
                insert into applied_posting (ledger_account_id, transaction_id)
                values (:account, :transaction)
                on conflict (ledger_account_id, transaction_id) do nothing
                """)
                .setParameter("account", ledgerAccountId)
                .setParameter("transaction", transactionId)
                .executeUpdate();

        return inserted == 1;
    }
}
