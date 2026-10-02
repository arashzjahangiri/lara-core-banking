package io.lara.payments.domain;

/**
 * The name of a state, separated from the state itself.
 *
 * <p>{@link TransferState} is a sealed hierarchy because most states carry data — a completed
 * transfer knows its ledger transaction, a rejected one knows why. But a database column and a
 * JSON field need a plain, stable token, and deriving one from a class name would mean a rename
 * silently rewrote history that was already stored.
 *
 * <p>So the token is declared rather than derived. These names are persisted and published; they
 * are not free to change.
 */
public enum TransferStatus {

    /** Accepted and recorded, nothing attempted yet. */
    REQUESTED(false),

    /** Waiting on the risk service's answer. */
    SCREENING(false),

    /**
     * Waiting on a second person. The only state with no timer driving it forward — see
     * {@code ADR} on four-eyes; a transfer can sit here indefinitely and that is correct.
     */
    APPROVAL_PENDING(false),

    /** The ledger has been asked to move the money; the outcome is not yet known. */
    POSTING(false),

    /**
     * The money has moved and the saga is not finished.
     *
     * <p>The state that makes compensation possible at all. Without it, "the ledger accepted the
     * posting" and "the transfer succeeded" would be the same state, and a failure between those
     * two facts would have nowhere to be recorded.
     */
    POSTED(false),

    /** Finished, money moved. */
    COMPLETED(true),

    /** Finished, no money moved and none ever will. */
    REJECTED(true),

    /** Money moved and must be moved back. A reversal is in flight. */
    COMPENSATING(false),

    /** Finished, money moved and was reversed. The ledger holds both postings. */
    COMPENSATED(true),

    /**
     * Stuck, and a person has to look at it.
     *
     * <p>Not the same as rejected. Rejected is an answer; this is the absence of one.
     */
    FAILED(true);

    private final boolean terminal;

    TransferStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** Terminal states are final: no transition leaves them, and the sweep ignores them. */
    public boolean isTerminal() {
        return terminal;
    }
}
