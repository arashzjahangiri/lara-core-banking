package io.lara.ledger.domain;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Currency;
import java.util.Objects;

/**
 * A monetary amount, held as a whole number of minor units in a single currency.
 *
 * <p>Minor units means cents for EUR, pence for GBP and whole yen for JPY — the smallest unit the
 * currency actually has. {@code Money.of(1234, "EUR")} is €12.34. Storing the amount this way means
 * every value is exact: there is no representation that can drift, and no rounding happens unless
 * it is asked for explicitly.
 *
 * <p>Floating point is not used anywhere, and cannot be: {@code 0.1 + 0.2} is not {@code 0.3} in
 * binary floating point, and a ledger that accumulates that error stops balancing. An architecture
 * test fails the build if a {@code double} or {@code float} appears in this package.
 *
 * <p>Arithmetic is exact and overflows loudly. Adding two amounts that exceed {@code long} throws
 * {@link ArithmeticException} rather than silently wrapping to a negative balance.
 *
 * <p>There is deliberately no {@code divide} method. Dividing money either loses minor units or
 * needs a rounding mode nobody remembers to think about, so splitting is done with
 * {@link #allocate(int)} and {@link #allocate(long...)}, which distribute the remainder and are
 * guaranteed to sum back to the original.
 */
public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException(
                    "currency " + currency.getCurrencyCode() + " has no minor unit and cannot hold an amount");
        }
    }

    public static Money of(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    /**
     * @param currencyCode an ISO 4217 code such as {@code EUR}
     */
    public static Money of(long minorUnits, String currencyCode) {
        Objects.requireNonNull(currencyCode, "currencyCode must not be null");
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

    public Money times(long factor) {
        return new Money(Math.multiplyExact(minorUnits, factor), currency);
    }

    public Money negated() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public Money abs() {
        return minorUnits < 0 ? negated() : this;
    }

    public boolean isZero() {
        return minorUnits == 0L;
    }

    public boolean isPositive() {
        return minorUnits > 0L;
    }

    public boolean isNegative() {
        return minorUnits < 0L;
    }

    /**
     * Splits this amount into {@code parts} shares that sum exactly back to it.
     *
     * <p>Splitting €10.00 three ways gives 3.34, 3.33, 3.33 rather than three lots of 3.33 with a
     * cent quietly discarded. The remainder is handed out one minor unit at a time from the first
     * share onwards, so the result is deterministic and reproducible.
     */
    public Money[] allocate(int parts) {
        if (parts < 1) {
            throw new IllegalArgumentException("cannot allocate into " + parts + " parts");
        }
        long[] equalWeights = new long[parts];
        Arrays.fill(equalWeights, 1L);
        return allocate(equalWeights);
    }

    /**
     * Splits this amount in proportion to {@code weights}, summing exactly back to it.
     *
     * <p>Used wherever an amount is divided unevenly — a fee split across accounts, or an FX spread
     * — without losing or inventing a minor unit.
     */
    public Money[] allocate(long... weights) {
        Objects.requireNonNull(weights, "weights must not be null");
        if (weights.length == 0) {
            throw new IllegalArgumentException("at least one weight is required");
        }

        long totalWeight = 0L;
        for (long weight : weights) {
            if (weight < 0L) {
                throw new IllegalArgumentException("weights must not be negative");
            }
            totalWeight = Math.addExact(totalWeight, weight);
        }
        if (totalWeight == 0L) {
            throw new IllegalArgumentException("weights must not all be zero");
        }

        Money[] shares = new Money[weights.length];
        long remainder = minorUnits;
        for (int i = 0; i < weights.length; i++) {
            long share = Math.multiplyExact(minorUnits, weights[i]) / totalWeight;
            shares[i] = new Money(share, currency);
            remainder -= share;
        }

        // Integer division truncates toward zero, so the remainder carries this amount's sign.
        long step = remainder < 0L ? -1L : 1L;
        for (int i = 0; remainder != 0L; i = (i + 1) % shares.length) {
            shares[i] = new Money(shares[i].minorUnits + step, currency);
            remainder -= step;
        }
        return shares;
    }

    /**
     * The amount as an exact decimal, for display and for serialisation at an adapter boundary.
     * Scaled by the currency's own minor-unit count, so JPY renders without a fractional part.
     */
    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(minorUnits, currency.getDefaultFractionDigits());
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    /**
     * @return the amount in ISO style, for example {@code EUR 12.34} or {@code JPY 1234}
     */
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
