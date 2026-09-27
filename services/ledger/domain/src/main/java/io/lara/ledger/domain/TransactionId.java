package io.lara.ledger.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * The identity of a ledger transaction.
 *
 * <p>{@link #newId()} is the only thing here that generates a value, and it says so in its name.
 * A factory that quietly produces a random identifier is how equality checks turn into code that
 * can never match.
 */
public record TransactionId(UUID value) implements Comparable<TransactionId> {

    public TransactionId {
        Objects.requireNonNull(value, "transaction id must not be null");
    }

    /** Generates a new random identity. Non-deterministic, deliberately and visibly. */
    public static TransactionId newId() {
        return new TransactionId(UUID.randomUUID());
    }

    public static TransactionId of(UUID value) {
        return new TransactionId(value);
    }

    public static TransactionId of(String value) {
        Objects.requireNonNull(value, "transaction id must not be null");
        return new TransactionId(UUID.fromString(value));
    }

    @Override
    public int compareTo(TransactionId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
