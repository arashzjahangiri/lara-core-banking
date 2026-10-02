package io.lara.payments.domain;

import java.util.Objects;

/**
 * A transition the state machine refuses.
 *
 * <p>Always a bug rather than a user error, which is why it is unchecked and why it names both
 * ends of the move it rejected. "Illegal state transition" on its own sends whoever is reading
 * the log back to the source to work out which pair was involved; {@code POSTED -> SCREENING}
 * tells them immediately.
 */
public final class IllegalTransferTransitionException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final transient TransferStatus from;
    private final transient TransferStatus to;

    IllegalTransferTransitionException(TransferStatus from, TransferStatus to) {
        super("a transfer cannot go from " + from + " to " + to);
        this.from = Objects.requireNonNull(from);
        this.to = Objects.requireNonNull(to);
    }

    public TransferStatus from() {
        return from;
    }

    public TransferStatus to() {
        return to;
    }
}