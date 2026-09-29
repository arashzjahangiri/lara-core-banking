package io.lara.ledger.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import io.lara.ledger.domain.AccountBalance;
import io.lara.ledger.domain.AccountId;
import io.lara.ledger.domain.LedgerAccount;
import io.lara.ledger.domain.LedgerTransaction;

/**
 * States what an account was worth at a given moment.
 *
 * <p>Nothing is cached and no balance is stored. The figure is folded from the postings every time,
 * which is what makes an as-at query answerable at all — a stored counter only ever knows "now",
 * and can drift from the entries that produced it.
 */
public final class GetAccountBalance {

    private final LedgerAccounts accounts;
    private final LedgerTransactions transactions;
    private final Clock clock;

    public GetAccountBalance(LedgerAccounts accounts, LedgerTransactions transactions, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * @throws PostingRejectedException.UnknownAccount when no such account is open
     */
    public AccountBalance asAt(AccountId id, Instant asOf) {
        Objects.requireNonNull(id, "account id must not be null");
        Objects.requireNonNull(asOf, "asOf must not be null");

        LedgerAccount account = accounts.findById(id)
                .orElseThrow(() -> new PostingRejectedException.UnknownAccount(id));

        List<LedgerTransaction> history = transactions.findByAccountUpTo(id, asOf);
        return AccountBalance.asAt(account, history, asOf);
    }

    /** The balance as it stands now, according to the injected clock. */
    public AccountBalance current(AccountId id) {
        return asAt(id, clock.instant());
    }
}
