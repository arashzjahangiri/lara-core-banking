package io.lara.risk.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

import io.lara.risk.domain.CustomerLimits;
import io.lara.risk.domain.Money;
import io.lara.risk.domain.RecordedScreening;
import io.lara.risk.domain.ScreeningDecision;
import io.lara.risk.domain.ScreeningRequest;
import io.lara.risk.domain.TransferScreening;

/**
 * The service's one use case: decide, write it down, hand it back.
 *
 * <p>Order matters and is the whole reason this is a use case rather than a method on the domain
 * service. The decision is recorded <em>before</em> it is returned, so a screening that reached a
 * caller is always one that exists in the database. The other way round, a crash between
 * answering and saving would leave payments holding a decision this service has no memory of —
 * and the retry would then be evaluated afresh, possibly to the opposite answer.
 *
 * <p>Asking the same question twice gives the same answer. That is not a nicety: payments retries
 * a timed-out screening rather than guessing, and limits move during the day, so a re-evaluated
 * retry could allow at 14:02 what it blocked at 14:01.
 */
public final class ScreenTransfer {

    private final TransferScreening screening;
    private final SanctionsSource sanctions;
    private final CustomerLimitsDirectory limits;
    private final ScreenedTotals totals;
    private final ScreeningDecisions decisions;
    private final Money defaultDailyLimit;
    private final Money defaultReviewAbove;
    private final ZoneId businessZone;
    private final Clock clock;

    public ScreenTransfer(
            TransferScreening screening,
            SanctionsSource sanctions,
            CustomerLimitsDirectory limits,
            ScreenedTotals totals,
            ScreeningDecisions decisions,
            Money defaultDailyLimit,
            Money defaultReviewAbove,
            ZoneId businessZone,
            Clock clock) {

        this.screening = Objects.requireNonNull(screening, "screening must not be null");
        this.sanctions = Objects.requireNonNull(sanctions, "sanctions source must not be null");
        this.limits = Objects.requireNonNull(limits, "limits directory must not be null");
        this.totals = Objects.requireNonNull(totals, "totals must not be null");
        this.decisions = Objects.requireNonNull(decisions, "decisions must not be null");
        this.defaultDailyLimit = Objects.requireNonNull(defaultDailyLimit, "default daily limit must not be null");
        this.defaultReviewAbove = Objects.requireNonNull(defaultReviewAbove, "default review threshold must not be null");
        this.businessZone = Objects.requireNonNull(businessZone, "business zone must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Screens one request, or returns the answer already given to it.
     *
     * @return the recorded decision, whether it was made now or on an earlier attempt
     */
    public RecordedScreening screen(ScreeningRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        Optional<RecordedScreening> alreadyDecided = decisions.findByReference(request.reference());
        if (alreadyDecided.isPresent()) {
            return alreadyDecided.get();
        }

        Instant now = clock.instant();
        CustomerLimits applicable = limitsFor(request);
        Money alreadyToday = totals.totalFor(
                request.customer(),
                now.atZone(businessZone).toLocalDate(),
                request.amount().currency());

        // Read now, not at construction. A list loaded once when the pod started is stale for
        // as long as the pod lives.
        ScreeningDecision decision =
                screening.screen(request, applicable, alreadyToday, sanctions.current());
        RecordedScreening recorded = RecordedScreening.of(request, decision, now);

        decisions.record(recorded);
        return recorded;
    }

    /**
     * This customer's own thresholds, or the service default.
     *
     * <p>Most customers have no row. Only the ones a risk officer has looked at individually are
     * configured, so treating a missing row as an error would break every new customer's first
     * payment — and defaulting to "no limit" would be far worse than defaulting to a conservative
     * one. The default is configuration rather than a constant, so it can be lowered without a
     * deployment.
     */
    private CustomerLimits limitsFor(ScreeningRequest request) {
        return limits.forCustomer(request.customer())
                .orElseGet(() -> new CustomerLimits(
                        request.customer(), defaultDailyLimit, defaultReviewAbove));
    }
}
