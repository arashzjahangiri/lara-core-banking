package io.lara.payments.application;

import java.util.Objects;

import io.lara.payments.domain.TransferId;
import io.lara.payments.domain.TransferStatus;

/**
 * Somebody tried to approve a transfer that is not waiting for approval.
 *
 * <p>Almost always a stale screen rather than a bug: two checkers open the same queue, one acts,
 * and the other clicks a minute later. Naming the state the transfer is actually in turns that
 * into something the second checker can understand without calling anyone.
 */
public final class NotAwaitingApprovalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient TransferId id;
    private final transient TransferStatus actual;

    public NotAwaitingApprovalException(TransferId id, TransferStatus actual) {
        super("transfer " + id + " is not awaiting approval; it is " + actual);
        this.id = Objects.requireNonNull(id);
        this.actual = Objects.requireNonNull(actual);
    }

    public TransferId id() {
        return id;
    }

    public TransferStatus actual() {
        return actual;
    }
}
