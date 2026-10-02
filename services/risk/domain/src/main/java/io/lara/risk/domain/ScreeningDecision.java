package io.lara.risk.domain;

import java.util.Objects;

/**
 * The answer to the only question this service exists to answer.
 *
 * <p>Every case carries a reason, and that is not decoration. A decision travels to the payments
 * service, from there into a transfer's history, and eventually to a support agent explaining to
 * a customer why their money did not move. "Blocked" on its own ends that conversation with
 * nobody any wiser, so the reason is required rather than optional.
 *
 * <p>Sealed, so the REST layer and the orchestrator both switch over it exhaustively with no
 * {@code default}. Adding a fourth outcome then stops them compiling until someone has decided
 * what it means, which is the one moment that decision is cheap.
 */
public sealed interface ScreeningDecision {

    /** The outcome, separated from the case so decisions can be ordered and persisted. */
    ScreeningOutcome outcome();

    /** Why, in words a support agent can read out. Never empty. */
    String reason();

    /** Whether the transfer may proceed without a human looking at it first. */
    default boolean isAllowed() {
        return outcome() == ScreeningOutcome.ALLOW;
    }

    static ScreeningDecision allow(String reason) {
        return new Allow(reason);
    }

    static ScreeningDecision review(String reason) {
        return new Review(reason);
    }

    static ScreeningDecision block(String reason) {
        return new Block(reason);
    }

    /**
     * The more restrictive of two decisions.
     *
     * <p>The checks are independent and can disagree — a transfer can be comfortably under its
     * limit and still name someone on the sanctions list. Combining them by taking the stricter
     * answer is the only safe direction: the opposite rule would let either check silently
     * overrule the other, and the one that gets overruled would be the one that found something.
     *
     * <p>Ties keep the first argument, so a caller passing the checks in a stable order gets a
     * stable reason rather than one that depends on evaluation order.
     */
    static ScreeningDecision mostRestrictive(ScreeningDecision first, ScreeningDecision second) {
        Objects.requireNonNull(first, "first decision must not be null");
        Objects.requireNonNull(second, "second decision must not be null");

        return second.outcome().isStricterThan(first.outcome()) ? second : first;
    }

    /** Nothing found. The transfer proceeds. */
    record Allow(String reason) implements ScreeningDecision {

        public Allow {
            reason = requireReason(reason);
        }

        @Override
        public ScreeningOutcome outcome() {
            return ScreeningOutcome.ALLOW;
        }
    }

    /**
     * Something worth a second look, but not proof of anything.
     *
     * <p>The outcome that keeps the other two honest. Without it every borderline case has to be
     * forced into allow or block, and in practice that means the threshold gets set wherever the
     * false positives are least annoying rather than wherever the risk actually is.
     */
    record Review(String reason) implements ScreeningDecision {

        public Review {
            reason = requireReason(reason);
        }

        @Override
        public ScreeningOutcome outcome() {
            return ScreeningOutcome.REVIEW;
        }
    }

    /** A rule was broken. The transfer does not proceed. */
    record Block(String reason) implements ScreeningDecision {

        public Block {
            reason = requireReason(reason);
        }

        @Override
        public ScreeningOutcome outcome() {
            return ScreeningOutcome.BLOCK;
        }
    }

    private static String requireReason(String reason) {
        Objects.requireNonNull(reason, "a screening decision must carry a reason");
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("a screening decision must carry a reason");
        }
        return trimmed;
    }
}
