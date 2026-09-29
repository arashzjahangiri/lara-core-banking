package io.lara.ledger.application;

import java.util.Objects;
import java.util.Optional;

import io.lara.ledger.domain.LedgerTransaction;
import io.lara.ledger.domain.TransactionId;
import io.lara.ledger.domain.TransactionReference;

/**
 * Reads back a recorded transaction, by the ledger's identity or by the caller's own reference.
 *
 * <p>Lookup by reference is what lets a caller that lost a response work out whether its posting
 * landed, without re-sending it.
 */
public final class FindTransaction {

    private final LedgerTransactions transactions;

    public FindTransaction(LedgerTransactions transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    }

    public Optional<LedgerTransaction> byId(TransactionId id) {
        Objects.requireNonNull(id, "transaction id must not be null");
        return transactions.findById(id);
    }

    public Optional<LedgerTransaction> byReference(TransactionReference reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        return transactions.findByReference(reference);
    }
}
