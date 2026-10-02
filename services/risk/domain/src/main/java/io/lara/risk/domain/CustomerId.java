package io.lara.risk.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Who is sending the money.
 *
 * <p>Risk does not own customers and cannot look one up — accounts does. What arrives here is an
 * identifier and an amount, and this service's entire job is to decide against its own limits and
 * lists without needing to know anything else about the person.
 *
 * <p>That narrowness is deliberate. A screening service that could read customer records would
 * accumulate reasons to, and the blast radius of a bug in it would stop being "a transfer was
 * wrongly blocked".
 */
public record CustomerId(UUID value) implements Comparable<CustomerId> {

    public CustomerId {
        Objects.requireNonNull(value, "customer id must not be null");
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