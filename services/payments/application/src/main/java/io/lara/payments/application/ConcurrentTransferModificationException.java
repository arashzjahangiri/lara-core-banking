package io.lara.payments.application;

import java.util.Objects;

import io.lara.payments.domain.TransferId;

/**
 * Someone else changed this saga while we were deciding what to do with it.
 *
 * <p>Not a failure, and that distinction is the whole reason this type exists. It means the write
 * was correctly refused and the work should be retried against fresh state — the version column
 * doing its job. Letting a raw {@code OptimisticLockException} escape would leak the persistence
 * provider into the orchestrator's control flow, and the orchestrator has to branch on this.
 *
 * <p>Retries are bounded by the caller. A saga that keeps losing the race is a saga something is
 * wrong with, and spinning on it forever turns a conflict into an outage.
 */
public final class ConcurrentTransferModificationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient TransferId id;

    public ConcurrentTransferModificationException(TransferId id, Throwable cause) {
        super("transfer " + id + " was modified concurrently", cause);
        this.id = Objects.requireNonNull(id);
    }

    public TransferId id() {
        return id;
    }
}