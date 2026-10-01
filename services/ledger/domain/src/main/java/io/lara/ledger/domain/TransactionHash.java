package io.lara.ledger.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A link in the ledger's hash chain.
 *
 * <p>Each transaction's hash covers its own content <em>and</em> the hash of the transaction
 * before it. Change any posting and its hash changes, which changes the next hash, and so on to
 * the end — so tampering anywhere leaves a visible break rather than a plausible row.
 *
 * <p>The append-only trigger stops mutation through the database. This catches what the trigger
 * cannot: a restored backup, a dropped trigger, or someone editing the files underneath.
 *
 * <p><strong>What this does not do.</strong> It detects tampering, it does not prevent it. Anyone
 * able to rewrite rows can recompute the chain from the edit forward and leave it consistent.
 * Preventing that needs the hashes published somewhere the operator does not control — a
 * counterparty, a notary, an append-only log on different infrastructure — which is out of scope
 * here and would be the next step for a system that genuinely needed it.
 */
public record TransactionHash(String value) {

    private static final String ALGORITHM = "SHA-256";
    private static final int HEX_LENGTH = 64;

    /**
     * ASCII unit separator. Cannot appear in an account id, a reference or a number, so no two
     * different transactions can serialise to the same string.
     */
    private static final char FIELD_SEPARATOR = '\u001F';

    /**
     * The chain's starting point: what the first transaction links back to.
     *
     * <p>Sixty-four zeros rather than a random value, so the chain is reproducible from the data
     * alone. A genesis nobody can rederive would make verification depend on a stored secret.
     */
    public static final TransactionHash GENESIS = new TransactionHash("0".repeat(HEX_LENGTH));

    public TransactionHash {
        Objects.requireNonNull(value, "hash must not be null");
        if (value.length() != HEX_LENGTH) {
            throw new IllegalArgumentException(
                    "a SHA-256 hash is " + HEX_LENGTH + " hex characters, was " + value.length());
        }
        if (!value.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            throw new IllegalArgumentException("hash must be lowercase hexadecimal, was '" + value + "'");
        }
    }

    /**
     * Hashes a transaction together with the hash preceding it.
     *
     * <p>The digested form covers everything that would change the transaction's meaning: its
     * identity, the caller's reference, when it happened, and every leg in order with its account,
     * side and exact amount. Altering any of them produces a different hash.
     *
     * <p>Fields are separated by a character that cannot appear inside any of them, so no two
     * different transactions can serialise to the same string — without that, an account id ending
     * in a digit and an amount beginning with one could run together ambiguously.
     */
    public static TransactionHash of(LedgerTransaction transaction, TransactionHash previous) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        Objects.requireNonNull(previous, "previous hash must not be null");

        StringBuilder digested = new StringBuilder()
                .append(previous.value).append(FIELD_SEPARATOR)
                .append(transaction.id()).append(FIELD_SEPARATOR)
                .append(transaction.reference().value()).append(FIELD_SEPARATOR)
                .append(transaction.occurredAt()).append(FIELD_SEPARATOR);

        for (PostingLeg leg : transaction.legs()) {
            digested.append(leg.account().value()).append(FIELD_SEPARATOR)
                    .append(leg.side()).append(FIELD_SEPARATOR)
                    .append(leg.amount().minorUnits()).append(FIELD_SEPARATOR)
                    .append(leg.amount().currency().getCurrencyCode()).append(FIELD_SEPARATOR);
        }

        return new TransactionHash(sha256(digested.toString()));
    }

    public boolean isGenesis() {
        return GENESIS.equals(this);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform, so this cannot happen.
            throw new IllegalStateException(ALGORITHM + " is unavailable", e);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
