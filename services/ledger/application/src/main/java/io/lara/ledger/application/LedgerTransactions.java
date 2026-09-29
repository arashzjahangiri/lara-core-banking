package io.lara.ledger.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.lara.ledger.domain.AccountId;
import io.lara.ledger.domain.LedgerTransaction;
import io.lara.ledger.domain.TransactionId;
import io.lara.ledger.domain.TransactionReference;

/**
 * A port for the ledger's postings.
 *
 * <p>Separate from {@link LedgerAccounts} because they are different capabilities with different
 * lifetimes: accounts are read often and change rarely, while postings are append-only. Splitting
 * them keeps each one trivially fakeable and leaves the adapters free to be backed by different
 * tables, or eventually different stores.
 *
 * <p>There is no {@code update} and no {@code delete}, and there never will be. A ledger corrects
 * itself by posting a contra transaction, which is what makes it an audit record rather than a
 * cache of the current state.
 */
public interface LedgerTransactions {

    /**
     * Appends a balanced transaction and all of its legs atomically, and returns what is now
     * recorded under its reference.
     *
     * <p>Normally that is the transaction passed in. Under a race it may not be: two concurrent
     * posts of one reference can both find nothing and both try to insert, and only one can win.
     * The loser is answered with the winner's transaction rather than an error, because both
     * callers asked for the same thing and exactly one movement happened.
     *
     * <p>The transaction is already valid by construction — it could not have been built otherwise
     * — so an implementation does not re-check the balancing rule.
     */
    LedgerTransaction append(LedgerTransaction transaction);

    /**
     * The transaction previously recorded under this caller-supplied reference, if any.
     *
     * <p>This is what makes posting idempotent: a retry carries the same reference, finds the
     * original and returns it rather than moving the money again.
     */
    Optional<LedgerTransaction> findByReference(TransactionReference reference);

    /** The transaction with this ledger-assigned identity, if it exists. */
    Optional<LedgerTransaction> findById(TransactionId id);

    /**
     * Every transaction touching {@code account} that occurred at or before {@code asOf}, which is
     * what a balance is folded from.
     */
    List<LedgerTransaction> findByAccountUpTo(AccountId account, Instant asOf);
}
