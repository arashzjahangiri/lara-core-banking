package io.lara.risk.domain;

import java.util.Objects;

/**
 * What one customer is allowed to move in a day, and where a human gets involved.
 *
 * <p>Two thresholds rather than one. The daily limit is a hard stop; {@code reviewAbove} is the
 * point at which a single transfer is large enough to be looked at even though it breaks no rule.
 * Collapsing them into one number forces every borderline payment to be either waved through or
 * refused, and in practice the threshold then drifts to wherever the complaints are quietest.
 *
 * <p>Data, not code. These values are seeded by migration and differ per customer, because a
 * limit compiled into the service is a limit that needs a deployment to change — and a risk team
 * that has to file a release ticket to lower someone's limit will not lower it in time.
 */
public record CustomerLimits(CustomerId customer, Money dailyLimit, Money reviewAbove) {

    public CustomerLimits {
        Objects.requireNonNull(customer, "customer must not be null");
        Objects.requireNonNull(dailyLimit, "daily limit must not be null");
        Objects.requireNonNull(reviewAbove, "review threshold must not be null");

        if (dailyLimit.isNegative()) {
            throw new IllegalArgumentException("a daily limit cannot be negative, was " + dailyLimit);
        }
        if (reviewAbove.isNegative()) {
            throw new IllegalArgumentException("a review threshold cannot be negative, was " + reviewAbove);
        }
        if (!dailyLimit.currency().equals(reviewAbove.currency())) {
            throw new IllegalArgumentException(
                    "limits must share a currency: daily is " + dailyLimit.currency().getCurrencyCode()
                            + ", review is " + reviewAbove.currency().getCurrencyCode());
        }
        // A review threshold above the daily limit can never fire: anything large enough to reach
        // it is already blocked. Silently useless configuration is worse than rejected
        // configuration, because nobody finds out until the review that should have happened did not.
        if (reviewAbove.compareTo(dailyLimit) > 0) {
            throw new IllegalArgumentException(
                    "a review threshold above the daily limit can never fire: review " + reviewAbove
                            + " exceeds daily " + dailyLimit);
        }
    }

    /**
     * Decides this transfer against the customer's day so far.
     *
     * <p>The limit is cumulative, so the question is never "is this transfer too big" on its own —
     * it is whether this transfer plus everything already screened today crosses the line. A
     * per-transfer-only limit is trivially defeated by sending the same money in ten pieces.
     *
     * @param amount          what is being screened now
     * @param alreadyScreenedToday the total already screened for this customer today
     */
    public ScreeningDecision screen(Money amount, Money alreadyScreenedToday) {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(alreadyScreenedToday, "the running total must not be null");
        requireSameCurrencyAsLimits(amount);
        requireSameCurrencyAsLimits(alreadyScreenedToday);

        Money cumulative = alreadyScreenedToday.plus(amount);

        // Strictly greater: exactly at the limit is within it. A limit of 10,000 that refuses
        // 10,000 is a limit of 9,999, and the customer was told the other number.
        if (cumulative.compareTo(dailyLimit) > 0) {
            return ScreeningDecision.block(
                    "daily limit of " + dailyLimit + " exceeded: " + alreadyScreenedToday
                            + " already screened today, plus " + amount + " is " + cumulative);
        }
        if (amount.compareTo(reviewAbove) > 0) {
            return ScreeningDecision.review(
                    "single transfer of " + amount + " is above the review threshold of " + reviewAbove);
        }
        return ScreeningDecision.allow(
                "within the daily limit of " + dailyLimit + " (" + cumulative + " screened today)");
    }

    private void requireSameCurrencyAsLimits(Money money) {
        if (!money.currency().equals(dailyLimit.currency())) {
            throw new IllegalArgumentException(
                    "cannot screen " + money.currency().getCurrencyCode()
                            + " against limits set in " + dailyLimit.currency().getCurrencyCode());
        }
    }
}
