package io.lara.accounts.infrastructure.persistence;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.lara.accounts.domain.Account;
import io.lara.accounts.domain.AccountStatus;
import io.lara.accounts.domain.CustomerId;
import io.lara.accounts.domain.Iban;

@Entity
@Table(name = "account")
public class AccountEntity {

    @Id
    @Column(name = "iban", length = 34, nullable = false)
    private String iban;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "ledger_account_id", length = 64, nullable = false)
    private String ledgerAccountId;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private AccountStatus status;

    @Column(name = "opened_at", nullable = false, insertable = false, updatable = false)
    private Instant openedAt;

    protected AccountEntity() {
        // required by Hibernate
    }

    static AccountEntity from(Account account) {
        AccountEntity entity = new AccountEntity();
        entity.iban = account.iban().value();
        entity.customerId = account.customer().value();
        entity.ledgerAccountId = account.ledgerAccountId();
        entity.currency = account.currency().getCurrencyCode();
        entity.status = account.status();
        return entity;
    }

    void apply(Account account) {
        // IBAN, owner, ledger account and currency are fixed for the life of an account; only
        // its status moves. Letting the others change here would silently repoint an account at
        // different money.
        this.status = account.status();
    }

    Account toDomain() {
        return new Account(
                Iban.of(iban),
                CustomerId.of(customerId),
                ledgerAccountId,
                Currency.getInstance(currency),
                status);
    }
}
