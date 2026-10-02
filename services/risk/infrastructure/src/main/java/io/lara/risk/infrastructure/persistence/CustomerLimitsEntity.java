package io.lara.risk.infrastructure.persistence;

import java.util.Currency;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.lara.risk.domain.CustomerId;
import io.lara.risk.domain.CustomerLimits;
import io.lara.risk.domain.Money;

/**
 * One customer's thresholds, as a row.
 *
 * <p>Separate from {@link CustomerLimits} rather than annotating it. The domain type validates
 * its own invariants in a compact constructor and has no no-arg constructor to offer Hibernate;
 * forcing one on it would mean a {@code CustomerLimits} could exist in an invalid state for as
 * long as Hibernate took to populate it.
 *
 * <p>Amounts are {@code BIGINT} minor units, per ADR-0010. A currency column sits beside them
 * because a bare number is not an amount.
 */
@Entity
@Table(name = "customer_limits")
public class CustomerLimitsEntity {

    @Id
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "daily_limit_minor", nullable = false)
    private long dailyLimitMinor;

    @Column(name = "review_above_minor", nullable = false)
    private long reviewAboveMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    protected CustomerLimitsEntity() {
        // Hibernate.
    }

    public CustomerLimits toDomain() {
        Currency denomination = Currency.getInstance(currency);
        return new CustomerLimits(
                CustomerId.of(customerId),
                Money.of(dailyLimitMinor, denomination),
                Money.of(reviewAboveMinor, denomination));
    }
}
