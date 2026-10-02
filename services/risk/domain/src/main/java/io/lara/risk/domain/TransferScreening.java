package io.lara.risk.domain;

import java.util.Objects;

/**
 * Runs both checks and reconciles them.
 *
 * <p>The two questions are unrelated — how much this customer has moved today, and who the money
 * is going to — and they are kept unrelated on purpose. Either one can find something the other
 * cannot, so neither gets to short-circuit the other: both always run, and the stricter answer
 * wins.
 *
 * <p>Running both even when the first blocks costs nothing here and buys something real. The
 * recorded reason names everything that was wrong rather than whichever problem happened to be
 * checked first, and a customer who fixes one and resubmits does not then discover the second.
 *
 * <p>Stateless, and the sanctions list arrives per call rather than per instance. Holding it
 * would make this object only as fresh as whenever it was constructed, and a regulator's addition
 * has to take effect on the next screening rather than the next deployment.
 */
public final class TransferScreening {

    /**
     * Decides one request.
     *
     * @param request              what is being screened
     * @param limits               this customer's thresholds
     * @param alreadyScreenedToday their running total for the day, before this request
     * @param sanctions            the list as it stands right now
     */
    public ScreeningDecision screen(
            ScreeningRequest request,
            CustomerLimits limits,
            Money alreadyScreenedToday,
            SanctionsList sanctions) {

        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        Objects.requireNonNull(alreadyScreenedToday, "the running total must not be null");
        Objects.requireNonNull(sanctions, "sanctions list must not be null");

        if (!limits.customer().equals(request.customer())) {
            throw new IllegalArgumentException(
                    "limits belong to customer " + limits.customer()
                            + " but the request is for " + request.customer());
        }

        ScreeningDecision byLimit = limits.screen(request.amount(), alreadyScreenedToday);
        ScreeningDecision bySanctions = sanctions.screen(request.beneficiary());

        // Sanctions first, so that when both block, the recorded reason is the sanctions match.
        // That is the one a compliance officer needs to see, and a limit breach is the easier of
        // the two for a customer to discover on their own.
        return ScreeningDecision.mostRestrictive(bySanctions, byLimit);
    }
}
