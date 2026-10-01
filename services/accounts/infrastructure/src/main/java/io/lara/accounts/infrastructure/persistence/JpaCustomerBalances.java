package io.lara.accounts.infrastructure.persistence;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import io.lara.accounts.application.CustomerBalances;
import io.lara.accounts.domain.CustomerBalance;

@ApplicationScoped
public class JpaCustomerBalances implements CustomerBalances {

    private final EntityManager entityManager;

    public JpaCustomerBalances(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<CustomerBalance> findByLedgerAccount(String ledgerAccountId) {
        return Optional.ofNullable(entityManager.find(CustomerBalanceEntity.class, ledgerAccountId))
                .map(CustomerBalanceEntity::toDomain);
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void save(CustomerBalance balance) {
        CustomerBalanceEntity existing =
                entityManager.find(CustomerBalanceEntity.class, balance.ledgerAccountId());

        if (existing == null) {
            entityManager.persist(CustomerBalanceEntity.from(balance));
        } else {
            existing.apply(balance);
        }
    }
}
