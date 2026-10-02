package io.lara.payments.application;

import java.util.Objects;

import io.lara.payments.domain.Transfer;

/**
 * A saga as it was read, together with the revision it was read at.
 *
 * <p>The version exists because the lock has to survive the gap between reading and writing. A
 * saga step loads a transfer, decides what to do with it — possibly after calling another
 * service, which takes time — and only then writes back. If the adapter simply re-read the row
 * at write time it would always find a current version and the check would pass, which is the
 * one thing it must not do.
 *
 * <p>Kept out of {@link Transfer} on purpose. A revision number is a fact about a stored row, not
 * about a payment, and the domain has no business knowing that it is stored at all.
 */
public record StoredTransfer(Transfer transfer, long version) {

    public StoredTransfer {
        Objects.requireNonNull(transfer, "transfer must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version cannot be negative, was " + version);
        }
    }

    public io.lara.payments.domain.TransferId id() {
        return transfer.id();
    }

    @Override
    public String toString() {
        return transfer + " @v" + version;
    }
}