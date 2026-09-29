package io.lara.ledger.infrastructure.persistence;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import io.lara.ledger.application.LedgerAccounts;
import io.lara.ledger.domain.AccountId;
import io.lara.ledger.domain.LedgerAccount;

/**
 * Resolves accounts from the chart of accounts.
 *
 * <p>The annotations stop here. The use case that depends on {@link LedgerAccounts} has no idea an
 * entity manager exists, which is what lets it be tested with a twelve-line fake.
 */
@ApplicationScoped
public class JpaLedgerAccounts implements LedgerAccounts {

    private final EntityManager entityManager;

    public JpaLedgerAccounts(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<LedgerAccount> findById(AccountId id) {
        return Optional.ofNullable(entityManager.find(LedgerAccountEntity.class, id.value()))
                .map(LedgerAccountEntity::toDomain);
    }
}
