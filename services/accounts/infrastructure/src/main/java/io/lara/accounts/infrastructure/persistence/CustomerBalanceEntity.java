package io.lara.accounts.infrastructure.persistence;

import java.time.Instant;
import java.util.Currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.lara.accounts.domain.CustomerBalance;
import io.lara.accounts.domain.Money;

/** The stored shape of the balance projection. Never leaves this package. */
@Entity
@Table(name = "customer_balance")
public class CustomerBalanceEntity {

    @Id
    @Column(name = "ledger_account_id", length = 64, nullable = false)
    private String ledgerAccountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "last_transaction_id", length = 64)
    private String lastTransactionId;

    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    protected CustomerBalanceEntity() {
        // required by Hibernate
    }

    static CustomerBalanceEntity from(CustomerBalance balance) {
        CustomerBalanceEntity entity = new CustomerBalanceEntity();
        entity.apply(balance);
        return entity;
    }

    void apply(CustomerBalance balance) {
        this.ledgerAccountId = balance.ledgerAccountId();
        this.amountMinor = balance.amount().minorUnits();
        this.currency = balance.amount().currency().getCurrencyCode();
        this.lastTransactionId = balance.lastTransactionId();
        this.lastUpdated = balance.lastUpdated();
    }

    CustomerBalance toDomain() {
        return new CustomerBalance(
                ledgerAccountId,
                Money.of(amountMinor, Currency.getInstance(currency)),
                lastTransactionId,
                lastUpdated);
    }
}
