package io.lara.payments.application;

import java.util.Objects;

/**
 * What risk said, in the only shape payments needs.
 *
 * <p>Deliberately not the risk service's own {@code ScreeningDecision}. The two services share no
 * code — that is most of what separate services are for — so this is payments' own reading of the
 * answer, mapped at the adapter. If risk adds a fourth outcome, the mapping fails in one place
 * rather than the new value propagating silently into the saga.
 */
public record ScreeningVerdict(Outcome outcome, String reason) {

    public ScreeningVerdict {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }

    public enum Outcome {

        /** Proceed. */
        ALLOW,

        /** A person has to look at it first. */
        REVIEW,

        /** Do not proceed. */
        BLOCK
    }

    public static ScreeningVerdict allow(String reason) {
        return new ScreeningVerdict(Outcome.ALLOW, reason);
    }

    public static ScreeningVerdict review(String reason) {
        return new ScreeningVerdict(Outcome.REVIEW, reason);
    }

    public static ScreeningVerdict block(String reason) {
        return new ScreeningVerdict(Outcome.BLOCK, reason);
    }
}