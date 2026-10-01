package io.lara.payments.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * The identity of a transfer, and the handle a customer is given when they ask for one.
 *
 * <p>{@link #newId()} is the only thing here that generates a value, and it says so in its name.
 * That matters more in this service than in the ledger: an idempotent retry has to return the
 * <em>original</em> id, so any factory that quietly minted a fresh one would turn a replay into a
 * second transfer without failing anything.
 */
public record TransferId(UUID value) implements Comparable<TransferId> {

    public TransferId {
        Objects.requireNonNull(value, "transfer id must not be null");
    }

    /** Generates a new random identity. Non-deterministic, deliberately and visibly. */
    public static TransferId newId() {
        return new TransferId(UUID.randomUUID());
    }

    public static TransferId of(UUID value) {
        return new TransferId(value);
    }

    public static TransferId of(String value) {
        Objects.requireNonNull(value, "transfer id must not be null");
        return new TransferId(UUID.fromString(value));
    }

    @Override
    public int compareTo(TransferId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
