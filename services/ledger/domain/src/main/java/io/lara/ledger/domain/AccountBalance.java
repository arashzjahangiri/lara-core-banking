package io.lara.ledger.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Objects;

/**
 * What an account was worth at a given moment, derived by folding the postings that reference it.
 *
 * <p>A balance is never stored. It is a function of the ledger, recomputed from the postings, which
 * is what makes "what was this balance last Tuesday" an answerable question rather than a lost one.
 * A stored counter can drift from the entries that produced it; a derived balance cannot.
 *
 * <p>The sign follows the account's class. A customer deposit is a {@link AccountClass#LIABILITY},
 * so a credit raises it and a debit lowers it — the opposite of an {@link AccountClass#ASSET}. This
 * is why balances are computed from a {@link LedgerAccount} rather than from an
 * {@link AccountId}: the class is what gives a posting its direction.
 *
 * <p>Every balance carries the instant it was taken at. There is deliberately no factory that means
 * "now": a balance without an as-at is a number whose meaning expires silently.
 */
public record AccountBalance(LedgerAccount account, Money amount, Instant asOf) {

    public AccountBalance {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(asOf, "asOf must not be null");
        if (!amount.currency().equals(account.currency())) {
            throw new IllegalArgumentException(
                    "balance of " + account.id() + " must be in " + account.currency().getCurrencyCode()
                            + ", was " + amount.currency().getCurrencyCode());
        }
    }

    /**
     * Folds every posting for {@code account} that occurred at or before {@code asOf}.
     *
     * <p>Postings after that instant are ignored, so replaying the same transactions with an earlier
     * instant reproduces the balance as it stood then. Transactions are examined in whatever order
     * the collection yields; addition is commutative, so the result does not depend on it.
     */
    public static AccountBalance asAt(LedgerAccount account,
            Collection<LedgerTransaction> transactions,
            Instant asOf) {

        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(transactions, "transactions must not be null");
        Objects.requireNonNull(asOf, "asOf must not be null");

        Money running = account.zeroBalance();
        for (LedgerTransaction transaction : transactions) {
            if (transaction.occurredAt().isAfter(asOf)) {
                continue;
            }
            for (PostingLeg leg : transaction.legsFor(account.id())) {
                requireMatchingCurrency(account, transaction, leg);
                running = account.isIncreasedBy(leg.side())
                        ? running.plus(leg.amount())
                        : running.minus(leg.amount());
            }
        }
        return new AccountBalance(account, running, asOf);
    }

    /** The side this balance sits on: its own class's normal side, or the opposite when negative. */
    public EntrySide side() {
        return amount.isNegative() ? account.normalSide().opposite() : account.normalSide();
    }

    public boolean isZero() {
        return amount.isZero();
    }

    /** True when the balance has moved to the wrong side of its account's normal position. */
    public boolean isContrary() {
        return amount.isNegative();
    }

    @Override
    public String toString() {
        return account.id() + " " + amount + " as at " + asOf;
    }

    private static void requireMatchingCurrency(LedgerAccount account,
            LedgerTransaction transaction,
            PostingLeg leg) {

        if (!leg.amount().currency().equals(account.currency())) {
            throw new IllegalArgumentException(
                    "transaction " + transaction.id() + " posts "
                            + leg.amount().currency().getCurrencyCode() + " to account " + account.id()
                            + ", which is denominated in " + account.currency().getCurrencyCode());
        }
    }
}
