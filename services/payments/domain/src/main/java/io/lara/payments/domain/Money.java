package io.lara.payments.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * A monetary amount in minor units.
 *
 * <p>The third near-duplicate in this repository, and deliberate for the reason the first one\n * gives: a shared money library would couple the services' release cycles, so the ledger could\n * not change its money type without a coordinated deploy of all four. That coupling is most of\n * what separate services exist to avoid, and the duplication is the cheaper of the two costs.\n *\n * <p>This copy carries no allocation either. A transfer's fee is derived from its scheme rather\n * than split out of the amount, so nothing here ever divides money.
 *
 * <p>Integer minor units for the same reason as in the ledger: floating point cannot represent
 * money exactly, and a balance that drifts is worse than one that is missing.
 */
public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException(
                    "currency " + currency.getCurrencyCode() + " has no minor unit");
        }
    }

    public static Money of(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    public static Money of(long minorUnits, String currencyCode) {
        return new Money(minorUnits, Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(0L, currency);
    }

    public static Money zero(String currencyCode) {
        return of(0L, currencyCode);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public boolean isNegative() {
        return minorUnits < 0L;
    }

    public boolean isZero() {
        return minorUnits == 0L;
    }

    /** Exact decimal, scaled by the currency, for display and serialisation at an adapter. */
    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(minorUnits, currency.getDefaultFractionDigits());
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + toDecimal().toPlainString();
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "cannot combine " + currency.getCurrencyCode() + " with " + other.currency.getCurrencyCode());
        }
    }
}
