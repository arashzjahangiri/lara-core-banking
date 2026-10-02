package io.lara.payments.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A reference to a transaction owned by the ledger.
 *
 * <p>Named a reference rather than an id to keep one thing visible: this value is not ours. The
 * ledger minted it, the ledger is the authority on what it means, and payments only ever stores
 * it and quotes it back. ADR-0003 is blunt about the boundary — this service holds workflow
 * state and must never be what decides a balance.
 *
 * <p>It is also the evidence that money moved. A transfer in {@code POSTED} without one would be
 * a claim with nothing behind it, so the state that carries it requires it.
 */
public record LedgerTransactionRef(UUID value) {

    public LedgerTransactionRef {
        Objects.requireNonNull(value, "ledger transaction reference must not be null");
    }

    public static LedgerTransactionRef of(UUID value) {
        return new LedgerTransactionRef(value);
    }

    public static LedgerTransactionRef of(String value) {
        Objects.requireNonNull(value, "ledger transaction reference must not be null");
        return new LedgerTransactionRef(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}