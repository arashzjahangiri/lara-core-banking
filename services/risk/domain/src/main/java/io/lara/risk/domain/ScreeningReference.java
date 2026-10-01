package io.lara.risk.domain;

import java.util.Objects;

/**
 * The caller's name for one screening, and the thing that makes asking twice safe.
 *
 * <p>Payments retries a timed-out screening rather than guessing, so the same reference has to
 * return the same decision rather than re-evaluating against whatever the limits look like now.
 * Without that, a retry seconds after a timeout could allow what the first call blocked.
 *
 * <p>Supplied by the caller, never generated here. A reference this service invented would be
 * different on every attempt, which is exactly the property it must not have.
 */
public record ScreeningReference(String value) implements Comparable<ScreeningReference> {

    private static final int MAX_LENGTH = 64;

    public ScreeningReference {
        Objects.requireNonNull(value, "screening reference must not be null");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("screening reference must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "screening reference must be at most " + MAX_LENGTH + " characters: " + value);
        }
    }

    public static ScreeningReference of(String value) {
        return new ScreeningReference(value);
    }

    @Override
    public int compareTo(ScreeningReference other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
