package io.lara.ledger.application;

import java.util.Objects;

import io.lara.ledger.domain.LedgerTransaction;

/**
 * What happened when a transaction was posted: the transaction now recorded under the reference,
 * and whether this call is what recorded it.
 *
 * <p>The distinction cannot be recovered afterwards. A retry is answered with the original, which
 * is indistinguishable from the original — so if the use case does not say which call created it,
 * no caller can tell, and an HTTP adapter is reduced to guessing between 201 and 200.
 *
 * <p>Both outcomes are successes. A caller that retried got exactly what it asked for: the money
 * moved once, and here is the record of it.
 */
public record PostTransactionResult(LedgerTransaction transaction, boolean created) {

    public PostTransactionResult {
        Objects.requireNonNull(transaction, "transaction must not be null");
    }

    static PostTransactionResult created(LedgerTransaction transaction) {
        return new PostTransactionResult(transaction, true);
    }

    /** The reference was already recorded; no money moved on this call. */
    static PostTransactionResult alreadyRecorded(LedgerTransaction original) {
        return new PostTransactionResult(original, false);
    }
}
