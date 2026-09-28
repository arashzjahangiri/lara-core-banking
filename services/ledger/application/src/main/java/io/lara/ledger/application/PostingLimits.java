package io.lara.ledger.application;

import java.util.Currency;
import java.util.Map;
import java.util.Objects;

import io.lara.ledger.domain.Money;

/**
 * The largest amount a single transaction may move, per currency.
 *
 * <p>Limits are per currency because an amount in one currency cannot be compared to a limit in
 * another without a rate, and a rate is a business decision that does not belong in a guard.
 *
 * <p><strong>Fails closed.</strong> A currency with no configured limit is refused rather than
 * waved through. The alternative — treating "not configured" as "unlimited" — means a typo in a
 * property file silently removes a control, which is the wrong way round for a bank.
 *
 * <p>This arrives as a constructor argument to the use case. The use case never reads configuration
 * itself: a class that resolves its own config is bound to the runtime that supplies it and can no
 * longer be built with {@code new} in a test.
 */
public record PostingLimits(Map<Currency, Money> maximumPerTransaction) {

    public PostingLimits {
        Objects.requireNonNull(maximumPerTransaction, "limits must not be null");
        maximumPerTransaction = Map.copyOf(maximumPerTransaction);
        maximumPerTransaction.forEach((currency, limit) -> {
            if (!limit.currency().equals(currency)) {
                throw new IllegalArgumentException(
                        "limit for " + currency.getCurrencyCode() + " was given in "
                                + limit.currency().getCurrencyCode());
            }
            if (!limit.isPositive()) {
                throw new IllegalArgumentException(
                        "limit for " + currency.getCurrencyCode() + " must be positive, was " + limit);
            }
        });
    }

    public static PostingLimits of(Money... limits) {
        Map<Currency, Money> byCurrency = new java.util.LinkedHashMap<>();
        for (Money limit : limits) {
            Objects.requireNonNull(limit, "limit must not be null");
            byCurrency.put(limit.currency(), limit);
        }
        return new PostingLimits(byCurrency);
    }

    /**
     * @throws PostingRejectedException.PostingLimitExceeded when the amount is over the limit, or
     *     when its currency has no limit configured at all
     */
    void check(Money amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        Money limit = maximumPerTransaction.get(amount.currency());
        if (limit == null) {
            throw new PostingRejectedException.PostingLimitExceeded(amount, Money.zero(amount.currency()));
        }
        if (amount.compareTo(limit) > 0) {
            throw new PostingRejectedException.PostingLimitExceeded(amount, limit);
        }
    }
}
