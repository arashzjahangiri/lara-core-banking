package io.lara.ledger.domain;

import java.util.Currency;
import java.util.Objects;

/**
 * The bank's own accounts, as opposed to those it holds for customers.
 *
 * <p>These are what make the books balance. A customer deposit is a credit to a
 * {@link AccountClass#LIABILITY} account, and something has to be debited against it — that is
 * {@link #CASH}, the bank's own asset. A fee is a debit to the customer and a credit to
 * {@link #FEE_INCOME}. Without accounts on the bank's side of the balance sheet, a transfer with a
 * fee cannot be expressed at all, which is why a two-row ledger stops working the moment money
 * stops being a closed loop between customers.
 *
 * <p>Every account is denominated in exactly one currency, so each of these resolves to a concrete
 * account per currency: {@code BANK.CASH.EUR}, {@code BANK.CASH.JPY}. The database seed iterates
 * this enum rather than repeating a hand-written list, so the two cannot drift apart.
 */
public enum SystemAccount {

    /** Cash the bank holds, at the central bank or a correspondent. Rises as deposits arrive. */
    CASH("BANK.CASH", AccountClass.ASSET),

    /** Fees the bank has earned. */
    FEE_INCOME("BANK.FEE.INCOME", AccountClass.INCOME),

    /** Money in flight: sent to a scheme but not yet settled. */
    CLEARING("BANK.CLEARING", AccountClass.ASSET),

    /** Money received that cannot yet be allocated to a customer. Should trend to zero. */
    SUSPENSE("BANK.SUSPENSE", AccountClass.ASSET);

    private final String idPrefix;
    private final AccountClass accountClass;

    SystemAccount(String idPrefix, AccountClass accountClass) {
        this.idPrefix = idPrefix;
        this.accountClass = accountClass;
    }

    public AccountClass accountClass() {
        return accountClass;
    }

    public AccountId idIn(Currency currency) {
        Objects.requireNonNull(currency, "currency must not be null");
        return AccountId.of(idPrefix + "." + currency.getCurrencyCode());
    }

    public LedgerAccount in(Currency currency) {
        return new LedgerAccount(idIn(currency), accountClass, currency);
    }

    public LedgerAccount in(String currencyCode) {
        return in(Currency.getInstance(currencyCode));
    }
}
