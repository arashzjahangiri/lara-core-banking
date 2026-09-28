package io.lara.ledger.application;

import java.util.Optional;

import io.lara.ledger.domain.LedgerTransaction;
import io.lara.ledger.domain.TransactionReference;

/**
 * A port for writing to the ledger.
 *
 * <p>Separate from {@link LedgerAccounts} because they are different capabilities with different
 * lifetimes: accounts are read often and change rarely, while postings are append-only and never
 * read on the write path. Splitting them keeps each one trivially fakeable and leaves the adapters
 * free to be backed by different tables, or eventually different stores.
 *
 * <p>There is no {@code update} and no {@code delete}, and there never will be. A ledger corrects
 * itself by posting a contra transaction, which is what makes it an audit record rather than a
 * cache of the current state.
 */
public interface LedgerTransactions {

    /**
     * Appends a balanced transaction and all of its legs atomically.
     *
     * <p>The transaction is already valid by construction — it could not have been built otherwise
     * — so an implementation does not re-check the balancing rule.
     */
    void append(LedgerTransaction transaction);

    /**
     * The transaction previously recorded under this caller-supplied reference, if any.
     *
     * <p>This is what makes posting idempotent: a retry carries the same reference, finds the
     * original and returns it rather than moving the money again.
     */
    Optional<LedgerTransaction> findByReference(TransactionReference reference);
}
