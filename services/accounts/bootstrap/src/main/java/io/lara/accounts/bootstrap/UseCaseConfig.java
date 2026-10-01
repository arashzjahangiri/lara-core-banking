package io.lara.accounts.bootstrap;

import java.time.Clock;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.lara.accounts.application.AppliedPostings;
import io.lara.accounts.application.ApplyLedgerPosting;
import io.lara.accounts.application.AccountDirectory;
import io.lara.accounts.application.CustomerBalances;
import io.lara.accounts.application.OpenAccount;
import io.lara.accounts.application.RegisterCustomer;
import io.lara.accounts.application.ViewAccounts;

/**
 * The only place the annotation-free application layer meets Quarkus, as in the ledger.
 *
 * <p>Quarkus resolves each port to its infrastructure adapter at build time, so a missing binding
 * fails the build rather than surfacing as a null on the first message.
 */
@ApplicationScoped
public class UseCaseConfig {

    /** The country and bank code generated IBANs are built from. */
    @ConfigProperty(name = "accounts.iban.country", defaultValue = "DK")
    String ibanCountry;

    @ConfigProperty(name = "accounts.iban.bank-code", defaultValue = "0040")
    String ibanBankCode;

    @ConfigProperty(name = "accounts.iban.first-account-number", defaultValue = "1000000000")
    long firstAccountNumber;

    @Produces
    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }

    @Produces
    @ApplicationScoped
    ApplyLedgerPosting applyLedgerPosting(CustomerBalances balances, AppliedPostings applied, Clock clock) {
        return new ApplyLedgerPosting(balances, applied, clock);
    }

    @Produces
    @ApplicationScoped
    RegisterCustomer registerCustomer(AccountDirectory directory) {
        return new RegisterCustomer(directory);
    }

    @Produces
    @ApplicationScoped
    OpenAccount openAccount(AccountDirectory directory) {
        return new OpenAccount(directory, ibanCountry, ibanBankCode, firstAccountNumber);
    }

    @Produces
    @ApplicationScoped
    ViewAccounts viewAccounts(AccountDirectory directory, CustomerBalances balances, Clock clock) {
        return new ViewAccounts(directory, balances, clock);
    }
}
