package io.lara.risk.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * A decision that has been made and written down.
 *
 * <p>The distinction from {@link ScreeningDecision} is the point of this type. A decision is a
 * judgement; this is the record of one having been made, about a particular request, at a
 * particular time. Screening that is not recorded cannot be audited later, and — more immediately
 * — cannot be replayed when payments retries a call that timed out.
 *
 * <p>It keeps the inputs as well as the answer. A compliance review a year from now needs to know
 * what the limits said at the time, not what they say now, and the only way to answer that is to
 * have written down what was screened.
 */
public record RecordedScreening(
        ScreeningReference reference,
        CustomerId customer,
        Money amount,
        PartyName beneficiary,
        ScreeningDecision decision,
        Instant screenedAt) {

    public RecordedScreening {
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(customer, "customer must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(beneficiary, "beneficiary must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(screenedAt, "screenedAt must not be null");

        // PostgreSQL timestamptz stores microseconds and a Java Instant carries nanoseconds, so
        // an untruncated value changes as it round-trips. The ledger learned this the hard way
        // when it broke a hash chain over data nobody had touched.
        screenedAt = screenedAt.truncatedTo(ChronoUnit.MICROS);
    }

    public static RecordedScreening of(
            ScreeningRequest request, ScreeningDecision decision, Instant screenedAt) {

        Objects.requireNonNull(request, "request must not be null");
        return new RecordedScreening(
                request.reference(),
                request.customer(),
                request.amount(),
                request.beneficiary(),
                decision,
                screenedAt);
    }

    public ScreeningOutcome outcome() {
        return decision.outcome();
    }

    /**
     * Whether this screening counts towards the customer's daily total.
     *
     * <p>Blocked requests do not. The money never moved, so counting them would let a customer
     * exhaust their own limit with transfers that were refused — and a blocked attempt would then
     * stop the legitimate payment that followed it.
     */
    public boolean countsTowardsTheDailyTotal() {
        return decision.outcome() != ScreeningOutcome.BLOCK;
    }

    @Override
    public String toString() {
        return reference + ": " + decision.outcome() + " (" + decision.reason() + ")";
    }
}
