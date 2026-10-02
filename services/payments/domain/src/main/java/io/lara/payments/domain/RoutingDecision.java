package io.lara.payments.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * What routing worked out about a transfer: how it travels, what it costs and when it lands.
 *
 * <p>Decided once, when the transfer is requested, and then carried with it. That ordering is the
 * point — the customer is quoted a fee and a value date before they commit, so recomputing either
 * one later would mean the transfer silently stopped matching what they agreed to. ADR-0003's
 * note that a transfer must be answerable from one {@code GET} depends on these being stored
 * facts rather than values derived afresh on every read.
 *
 * <p>The fee is {@link Money}, never a fraction of the amount computed in floating point. It is
 * also separate from the amount: the creditor receives the amount, and the debtor pays the amount
 * plus the fee, which is two different numbers that a single field would conflate.
 */
public record RoutingDecision(TransferScheme scheme, Money fee, LocalDate valueDate) {

    public RoutingDecision {
        Objects.requireNonNull(scheme, "scheme must not be null");
        Objects.requireNonNull(fee, "fee must not be null");
        Objects.requireNonNull(valueDate, "value date must not be null");

        if (fee.isNegative()) {
            throw new IllegalArgumentException("a fee cannot be negative, was " + fee);
        }
        if (scheme == TransferScheme.INTERNAL && !fee.isZero()) {
            throw new IllegalArgumentException(
                    "an internal book transfer involves no outside party and cannot carry a fee, was " + fee);
        }
    }

    public boolean isFree() {
        return fee.isZero();
    }

    @Override
    public String toString() {
        return scheme + " fee " + fee + " value " + valueDate;
    }
}
