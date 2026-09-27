package io.lara.ledger.domain;

import java.util.Currency;
import java.util.Objects;

/**
 * An account in the chart of accounts: an identifier, what kind of account it is, and the one
 * currency it is denominated in.
 *
 * <p>An account holds no balance. A balance is derived by folding the postings that reference the
 * account, never stored as a counter that gets incremented — which is what makes "what was this
 * balance last Tuesday" an answerable question rather than a lost one.
 *
 * <p>Single-currency by design. An account that could hold two currencies would need a rate to
 * state its balance, and a rate is a business decision that does not belong in a ledger. Multi
 * currency is modelled as separate accounts plus an explicit exchange transaction.
 */
public record LedgerAccount(AccountId id, AccountClass accountClass, Currency currency) {

    public LedgerAccount {
        Objects.requireNonNull(id, "account id must not be null");
        Objects.requireNonNull(accountClass, "account class must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException(
                    "currency " + currency.getCurrencyCode() + " has no minor unit and cannot denominate an account");
        }
    }

    public static LedgerAccount of(String id, AccountClass accountClass, String currencyCode) {
        return new LedgerAccount(AccountId.of(id), accountClass, Currency.getInstance(currencyCode));
    }

    /** The side on which a positive balance of this account sits. */
    public EntrySide normalSide() {
        return accountClass.normalSide();
    }

    /** Whether a posting on {@code side} raises this account's balance. */
    public boolean isIncreasedBy(EntrySide side) {
        return accountClass.isIncreasedBy(side);
    }

    /** A zero amount in this account's own currency, the starting point for folding postings. */
    public Money zeroBalance() {
        return Money.zero(currency);
    }

    @Override
    public String toString() {
        return id + " (" + accountClass + ", " + currency.getCurrencyCode() + ")";
    }
}
