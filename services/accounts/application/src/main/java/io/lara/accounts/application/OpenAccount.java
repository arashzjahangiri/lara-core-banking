package io.lara.accounts.application;

import java.util.Currency;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import io.lara.accounts.domain.Account;
import io.lara.accounts.domain.Customer;
import io.lara.accounts.domain.CustomerId;
import io.lara.accounts.domain.Iban;

/**
 * Opens an account for a customer.
 *
 * <p>Two things have to line up before money can move: the customer's identity must be verified,
 * and the account must claim a ledger account nobody else holds. Both are checked here rather
 * than in the REST layer, because both depend on stored state the domain cannot see.
 *
 * <p>The account opens in {@code OPENING} and is activated separately. Opening and activating are
 * different events with different approvals behind them in a real bank, and collapsing them would
 * mean an account becomes usable the instant it is created.
 */
public final class OpenAccount {

    private final AccountDirectory directory;
    private final String countryCode;
    private final String bankCode;
    private final AtomicLong accountNumbers;

    /**
     * @param firstAccountNumber where the generated account numbers start. Supplied rather than
     *     fixed, so a test can make the IBANs it expects deterministic.
     */
    public OpenAccount(AccountDirectory directory, String countryCode, String bankCode, long firstAccountNumber) {
        this.directory = Objects.requireNonNull(directory, "directory must not be null");
        this.countryCode = Objects.requireNonNull(countryCode, "country code must not be null");
        this.bankCode = Objects.requireNonNull(bankCode, "bank code must not be null");
        this.accountNumbers = new AtomicLong(firstAccountNumber);
    }

    /**
     * @throws AccountRejectedException.UnknownCustomer when there is no such customer
     * @throws AccountRejectedException.CustomerNotVerified when identity checks are incomplete
     * @throws AccountRejectedException.LedgerAccountTaken when another account already holds it
     */
    public Account open(CustomerId customerId, Currency currency, String ledgerAccountId) {
        Objects.requireNonNull(customerId, "customer id must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(ledgerAccountId, "ledger account id must not be null");

        Customer customer = directory.findCustomer(customerId)
                .orElseThrow(() -> new AccountRejectedException.UnknownCustomer(customerId));

        if (!customer.mayHoldAnOpenAccount()) {
            throw new AccountRejectedException.CustomerNotVerified(customerId);
        }
        if (directory.isLedgerAccountTaken(ledgerAccountId)) {
            throw new AccountRejectedException.LedgerAccountTaken(ledgerAccountId);
        }

        Iban iban = Iban.generate(countryCode, bankCode, String.format("%010d", accountNumbers.getAndIncrement()));
        Account account = Account.opening(iban, customerId, ledgerAccountId, currency);

        directory.saveAccount(account);
        return account;
    }

    /** Makes an account usable. Separate from opening, because they are different decisions. */
    public Account activate(Iban iban) {
        Objects.requireNonNull(iban, "IBAN must not be null");

        Account account = directory.findAccount(iban)
                .orElseThrow(() -> new AccountRejectedException.UnknownAccount(iban));

        Customer customer = directory.findCustomer(account.customer())
                .orElseThrow(() -> new AccountRejectedException.UnknownCustomer(account.customer()));

        // Re-checked at activation, not only at opening. Verification can be withdrawn between
        // the two, and the moment that matters is the one where money becomes able to move.
        if (!customer.mayHoldAnOpenAccount()) {
            throw new AccountRejectedException.CustomerNotVerified(customer.id());
        }

        Account activated = account.activate();
        directory.saveAccount(activated);
        return activated;
    }
}
