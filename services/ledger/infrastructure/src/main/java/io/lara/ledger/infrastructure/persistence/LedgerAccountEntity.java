package io.lara.ledger.infrastructure.persistence;

import java.time.Instant;
import java.util.Currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.lara.ledger.domain.AccountClass;
import io.lara.ledger.domain.AccountId;
import io.lara.ledger.domain.LedgerAccount;

/**
 * The persistence shape of an account. Never leaves this package.
 *
 * <p>The domain's {@link LedgerAccount} is a record with no annotations, and this is the mutable,
 * annotated thing Hibernate needs. Keeping them separate is the cost of a framework-free domain,
 * and {@link #toDomain()} is where that cost is paid.
 */
@Entity
@Table(name = "ledger_account")
public class LedgerAccountEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_class", length = 16, nullable = false)
    private AccountClass accountClass;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "opened_at", nullable = false, insertable = false, updatable = false)
    private Instant openedAt;

    protected LedgerAccountEntity() {
        // required by Hibernate
    }

    public LedgerAccount toDomain() {
        return new LedgerAccount(AccountId.of(id), accountClass, Currency.getInstance(currency));
    }

    public String id() {
        return id;
    }

    public Instant openedAt() {
        return openedAt;
    }
}
