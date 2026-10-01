package io.lara.ledger.application;

import java.util.Objects;
import java.util.Optional;

import io.lara.ledger.domain.TransactionId;

/**
 * The result of walking the ledger's hash chain.
 *
 * <p>Either every transaction's recorded hash matches what its content and predecessor produce,
 * or it does not — and if it does not, the first break is the interesting one. Everything after it
 * will also fail, because each hash depends on the one before.
 */
public record ChainIntegrity(long transactionsChecked, Optional<Break> firstBreak) {

    public ChainIntegrity {
        Objects.requireNonNull(firstBreak, "firstBreak must not be null");
    }

    public static ChainIntegrity intact(long checked) {
        return new ChainIntegrity(checked, Optional.empty());
    }

    public static ChainIntegrity broken(long checked, Break firstBreak) {
        return new ChainIntegrity(checked, Optional.of(Objects.requireNonNull(firstBreak)));
    }

    public boolean isIntact() {
        return firstBreak.isEmpty();
    }

    /**
     * Where the chain stops adding up.
     *
     * @param reason what failed to match — the recorded hash, or the link to the predecessor
     */
    public record Break(long sequence, TransactionId transaction, Reason reason, String expected, String actual) {

        public Break {
            Objects.requireNonNull(transaction, "transaction must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    public enum Reason {

        /**
         * The transaction's content no longer hashes to its recorded hash: a posting was altered.
         */
        CONTENT_ALTERED,

        /**
         * The transaction's recorded predecessor is not the hash of the transaction before it:
         * a row was inserted, removed, or reordered.
         */
        CHAIN_BROKEN,

        /** A transaction was recorded without a hash, so the chain cannot be followed through it. */
        HASH_MISSING
    }
}
