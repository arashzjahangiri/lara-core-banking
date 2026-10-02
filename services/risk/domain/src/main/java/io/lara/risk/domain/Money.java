package io.lara.risk.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * A monetary amount in minor units.
 *
 * <p>The fourth near-duplicate in this repository, for the reason the ledger's copy gives: a\n * shared money library would couple all four services' release cycles, and that coupling is most\n * of what separate services exist to avoid.\n *\n * <p>This copy is the one that does the least. Risk compares amounts against limits and never\n * moves, splits or sums anyone's money, so {@code plus} is here only to add a day's screened\n * total to the amount being screened.
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
