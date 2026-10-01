package io.lara.accounts.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * The identity of a customer.
 *
 * <p>A type rather than a bare UUID, so a customer id cannot be passed where an account id or a
 * transaction id was meant. The compiler catches an entire class of argument-ordering mistakes.
 */
public record CustomerId(UUID value) implements Comparable<CustomerId> {

    public CustomerId {
        Objects.requireNonNull(value, "customer id must not be null");
    }

    /** Generates a new identity. Non-deterministic, and says so in its name. */
    public static CustomerId newId() {
        return new CustomerId(UUID.randomUUID());
    }

    public static CustomerId of(UUID value) {
        return new CustomerId(value);
    }

    public static CustomerId of(String value) {
        Objects.requireNonNull(value, "customer id must not be null");
        return new CustomerId(UUID.fromString(value));
    }

    @Override
    public int compareTo(CustomerId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
