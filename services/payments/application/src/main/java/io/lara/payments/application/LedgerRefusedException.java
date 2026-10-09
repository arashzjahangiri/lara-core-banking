package io.lara.payments.application;

/**
 * The ledger looked at the posting and declined it.
 *
 * <p>A decision, unlike {@link RemoteServiceException}, and the distinction decides what the saga
 * does next. A refusal means no money moved and none will, so the transfer is rejected and
 * nothing needs undoing. An unreachable ledger means the outcome is unknown, which is the one
 * situation where the saga must not assume anything.
 */
public final class LedgerRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LedgerRefusedException(String message) {
        super(message);
    }
}