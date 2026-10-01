package io.lara.accounts.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import io.lara.accounts.application.AccountDirectory;
import io.lara.accounts.domain.Account;
import io.lara.accounts.domain.Customer;
import io.lara.accounts.domain.CustomerId;
import io.lara.accounts.domain.Iban;

@ApplicationScoped
public class JpaAccountDirectory implements AccountDirectory {

    private final EntityManager entityManager;

    public JpaAccountDirectory(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Customer> findCustomer(CustomerId id) {
        return Optional.ofNullable(entityManager.find(CustomerEntity.class, id.value()))
                .map(CustomerEntity::toDomain);
    }

    @Override
    public Optional<Account> findAccount(Iban iban) {
        return Optional.ofNullable(entityManager.find(AccountEntity.class, iban.value()))
                .map(AccountEntity::toDomain);
    }

    @Override
    public List<Account> accountsOf(CustomerId customer) {
        return entityManager
                .createQuery("from AccountEntity where customerId = :customer order by iban", AccountEntity.class)
                .setParameter("customer", customer.value())
                .getResultList()
                .stream()
                .map(AccountEntity::toDomain)
                .toList();
    }

    @Override
    public boolean isLedgerAccountTaken(String ledgerAccountId) {
        return entityManager
                .createQuery("select count(a) from AccountEntity a where a.ledgerAccountId = :id", Long.class)
                .setParameter("id", ledgerAccountId)
                .getSingleResult() > 0;
    }

    @Override
    @Transactional
    public void saveCustomer(Customer customer) {
        CustomerEntity existing = entityManager.find(CustomerEntity.class, customer.id().value());
        if (existing == null) {
            entityManager.persist(CustomerEntity.from(customer));
        } else {
            existing.apply(customer);
        }
    }

    @Override
    @Transactional
    public void saveAccount(Account account) {
        AccountEntity existing = entityManager.find(AccountEntity.class, account.iban().value());
        if (existing == null) {
            entityManager.persist(AccountEntity.from(account));
        } else {
            existing.apply(account);
        }
    }
}
