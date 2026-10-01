package io.lara.accounts.domain;

/**
 * Which way a posting moves an account.
 *
 * <p>Mirrors the ledger's {@code EntrySide} by value, not by import. The two services agree on the
 * names {@code DEBIT} and {@code CREDIT} through the event contract rather than through a shared
 * class — which is exactly what the Pact tests pin down.
 */
public enum PostingDirection {

    DEBIT,
    CREDIT;

    public static PostingDirection parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("posting direction must not be null");
        }
        return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "DEBIT" -> DEBIT;
            case "CREDIT" -> CREDIT;
            // A direction we do not recognise must stop the consumer rather than be guessed at.
            // Guessing would silently apply a movement the wrong way round.
            default -> throw new IllegalArgumentException("unknown posting direction: '" + value + "'");
        };
    }
}
