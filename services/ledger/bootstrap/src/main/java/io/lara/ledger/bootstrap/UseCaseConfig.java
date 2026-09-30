package io.lara.ledger.bootstrap;

import java.time.Clock;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.lara.ledger.application.FindTransaction;
import io.lara.ledger.application.GetAccountBalance;
import io.lara.ledger.application.LedgerAccounts;
import io.lara.ledger.application.LedgerTransactions;
import io.lara.ledger.application.PostTransaction;
import io.lara.ledger.application.PostingLimits;
import io.lara.ledger.application.ReverseTransaction;
import io.lara.ledger.domain.Money;

/**
 * The only place the two worlds meet.
 *
 * <p>Use cases carry no annotations, so something has to construct them — this is that something.
 * Quarkus resolves each port to its infrastructure adapter at build time and hands it in, and the
 * use case never learns a framework exists. A missing binding fails the <em>build</em> rather than
 * surfacing as a null at startup.
 *
 * <p>Configuration is read here and passed in as values. A use case that injected
 * {@code @ConfigProperty} itself would be bound to the runtime that supplies it and could no longer
 * be built with {@code new} in a test.
 */
@ApplicationScoped
public class UseCaseConfig {

    /**
     * Per-currency posting limits, as {@code CODE:minorUnits} pairs — for example
     * {@code EUR:100000,USD:100000}. A currency absent from this list is refused rather than
     * treated as unlimited, so a typo here removes nothing silently.
     */
    @ConfigProperty(name = "ledger.posting.limits", defaultValue = "EUR:100000000,USD:100000000")
    String configuredLimits;

    @Produces
    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }

    @Produces
    @Singleton
    PostingLimits postingLimits() {
        Map<Currency, Money> limits = new LinkedHashMap<>();
        for (String entry : configuredLimits.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "ledger.posting.limits entries must be CODE:minorUnits, was '" + trimmed + "'");
            }
            Currency currency = Currency.getInstance(parts[0].trim());
            limits.put(currency, Money.of(Long.parseLong(parts[1].trim()), currency));
        }
        return new PostingLimits(limits);
    }

    @Produces
    @ApplicationScoped
    PostTransaction postTransaction(LedgerAccounts accounts,
            LedgerTransactions transactions,
            PostingLimits limits,
            Clock clock) {

        return new PostTransaction(accounts, transactions, limits, clock);
    }

    @Produces
    @ApplicationScoped
    GetAccountBalance getAccountBalance(LedgerAccounts accounts, LedgerTransactions transactions, Clock clock) {
        return new GetAccountBalance(accounts, transactions, clock);
    }

    @Produces
    @ApplicationScoped
    FindTransaction findTransaction(LedgerTransactions transactions) {
        return new FindTransaction(transactions);
    }

    @Produces
    @ApplicationScoped
    ReverseTransaction reverseTransaction(LedgerTransactions transactions, Clock clock) {
        return new ReverseTransaction(transactions, clock);
    }
}
