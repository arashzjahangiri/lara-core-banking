package io.lara.risk.domain;

/**
 * How restrictive a decision is, declared in order from least to most.
 *
 * <p>The order is the whole point: two independent checks can disagree, and combining them means
 * taking the stricter answer. Declaring the order here rather than writing comparisons at each
 * call site means there is one place to be wrong, and it is a place with a test on it.
 *
 * <p>These names are persisted and published, so they are not free to change.
 */
public enum ScreeningOutcome {

    /** Nothing found. */
    ALLOW,

    /** Worth a second look, but not proof of anything. */
    REVIEW,

    /** A rule was broken. */
    BLOCK;

    /**
     * Whether this outcome stops more than the other one does.
     *
     * <p>Leans on declaration order, which is why the constants above are ordered deliberately
     * and why adding one in the middle is a decision rather than an edit.
     */
    public boolean isStricterThan(ScreeningOutcome other) {
        return ordinal() > other.ordinal();
    }
}