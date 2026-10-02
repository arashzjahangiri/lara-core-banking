package io.lara.payments.domain;

import java.util.Objects;

/**
 * The reference this transfer quotes when it posts to the ledger.
 *
 * <p>ADR-0007 made the ledger idempotent by exactly this value: posting twice with one reference
 * records one transaction. That is what makes a retried posting safe, and therefore what makes
 * crash recovery safe — a resumed saga can re-drive its posting step without having to first
 * work out whether the previous attempt got through.
 *
 * <p>Derived from the transfer id rather than supplied, because a reference that varied between
 * attempts would defeat the entire mechanism. {@link #reversalOf()} follows the same rule: the
 * compensating entry's reference is a function of the original, so compensating twice also
 * collapses to one posting.
 */
public record TransferReference(String value) implements Comparable<TransferReference> {

    private static final int MAX_LENGTH = 64;
    private static final String REVERSAL_SUFFIX = "-REV";

    public TransferReference {
        Objects.requireNonNull(value, "transfer reference must not be null");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("transfer reference must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "transfer reference must be at most " + MAX_LENGTH + " characters: " + value);
        }
    }

    /** The posting reference for a transfer. Deterministic: the same id always yields the same one. */
    public static TransferReference forTransfer(TransferId id) {
        Objects.requireNonNull(id, "transfer id must not be null");
        return new TransferReference("TRANSFER-" + id.value());
    }

    public static TransferReference of(String value) {
        return new TransferReference(value);
    }

    /**
     * The reference the compensating entry posts under.
     *
     * <p>Appending rather than generating is the whole point: a recovery sweep that compensates
     * the same transfer twice sends the same reference twice, and the ledger collapses it to one
     * reversal instead of moving the money back two times.
     */
    public TransferReference reversalOf() {
        return new TransferReference(value + REVERSAL_SUFFIX);
    }

    @Override
    public int compareTo(TransferReference other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}