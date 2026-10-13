package io.lara.payments.domain;

import java.util.Objects;

/**
 * Somebody tried to approve or decline their own transfer.
 *
 * <p>The oldest control in banking and the one most often implemented as a checkbox in a UI.
 * Putting it in the domain means it holds for every path into the aggregate, not just the screen
 * that happens to have the button on it.
 *
 * <p>Its own type rather than an {@code IllegalArgumentException}, because the REST layer has to
 * answer it differently: this is not a malformed request, it is a well-formed request the caller
 * is not permitted to make. That is a 403, and a mapper cannot tell the difference from a
 * message.
 */
public final class FourEyesViolationException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final transient String maker;
    private final transient String attemptedAction;

    FourEyesViolationException(String maker, String attemptedAction) {
        super("a transfer requested by " + maker + " cannot be " + attemptedAction
                + "d by the same person");
        this.maker = Objects.requireNonNull(maker);
        this.attemptedAction = Objects.requireNonNull(attemptedAction);
    }

    public String maker() {
        return maker;
    }

    public String attemptedAction() {
        return attemptedAction;
    }
}
