package io.lara.accounts.bootstrap;

import java.time.Clock;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import io.lara.accounts.application.AppliedPostings;
import io.lara.accounts.application.ApplyLedgerPosting;
import io.lara.accounts.application.CustomerBalances;

/**
 * The only place the annotation-free application layer meets Quarkus, as in the ledger.
 *
 * <p>Quarkus resolves each port to its infrastructure adapter at build time, so a missing binding
 * fails the build rather than surfacing as a null on the first message.
 */
@ApplicationScoped
public class UseCaseConfig {

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
}
