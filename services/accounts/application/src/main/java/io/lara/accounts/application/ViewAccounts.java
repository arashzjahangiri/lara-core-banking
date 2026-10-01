package io.lara.accounts.application;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

import io.lara.accounts.domain.Account;
import io.lara.accounts.domain.CustomerBalance;
import io.lara.accounts.domain.CustomerId;
import io.lara.accounts.domain.Iban;
import io.lara.accounts.domain.Money;

/**
 * Reads accounts and the balances projected for them.
 *
 * <p>Balances are served from the projection, never by calling the ledger. That is the entire
 * reason the projection exists: a customer opening their app must not put load on the service
 * that moves money, and must not fail when it is briefly unavailable.
 *
 * <p>The price is staleness, and the answer carries it rather than hiding it.
 */
public final class ViewAccounts {

    private final AccountDirectory directory;
    private final CustomerBalances balances;
    private final Clock clock;

    public ViewAccounts(AccountDirectory directory, CustomerBalances balances, Clock clock) {
        this.directory = Objects.requireNonNull(directory, "directory must not be null");
        this.balances = Objects.requireNonNull(balances, "balances must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public Account byIban(Iban iban) {
        Objects.requireNonNull(iban, "IBAN must not be null");
        return directory.findAccount(iban)
                .orElseThrow(() -> new AccountRejectedException.UnknownAccount(iban));
    }

    public List<Account> of(CustomerId customer) {
        Objects.requireNonNull(customer, "customer must not be null");
        directory.findCustomer(customer)
                .orElseThrow(() -> new AccountRejectedException.UnknownCustomer(customer));
        return directory.accountsOf(customer);
    }

    /**
     * The balance as the projection currently holds it.
     *
     * <p>An account with no postings yet has no projected row, which is not an error — it is a
     * zero balance that has never moved. Returning an empty result there would make callers treat
     * a new account as broken.
     */
    public ProjectedBalance balanceOf(Iban iban) {
        Account account = byIban(iban);

        CustomerBalance projected = balances.findByLedgerAccount(account.ledgerAccountId())
                .orElseGet(() -> CustomerBalance.opening(
                        account.ledgerAccountId(), Money.zero(account.currency()), clock.instant()));

        return new ProjectedBalance(account, projected, projected.stalenessAt(clock.instant()));
    }

    /**
     * A balance together with how far behind the ledger it might be.
     *
     * @param staleness time since the projection last moved. Not a guarantee of lag — a quiet
     *     account is stale and perfectly correct — but it is what a caller needs to decide
     *     whether to trust the number for anything consequential.
     */
    public record ProjectedBalance(Account account, CustomerBalance balance, java.time.Duration staleness) {

        public ProjectedBalance {
            Objects.requireNonNull(account, "account must not be null");
            Objects.requireNonNull(balance, "balance must not be null");
            Objects.requireNonNull(staleness, "staleness must not be null");
        }
    }
}
