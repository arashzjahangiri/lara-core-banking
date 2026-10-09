package io.lara.payments.bootstrap;

import java.time.Clock;
import java.time.LocalTime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.lara.payments.application.AccountDirectory;
import io.lara.payments.application.LedgerPosting;
import io.lara.payments.application.RequestTransfer;
import io.lara.payments.application.RiskScreening;
import io.lara.payments.application.SchemeGateway;
import io.lara.payments.application.TransferOrchestrator;
import io.lara.payments.application.Transfers;
import io.lara.payments.domain.BusinessCalendar;
import io.lara.payments.domain.Money;
import io.lara.payments.domain.TransferRouter;

/**
 * The only place the annotation-free application layer meets Quarkus, as in the other services.
 *
 * <p>Quarkus resolves each port to its adapter at build time, so a missing binding fails the
 * build rather than surfacing as a null on the first transfer.
 */
@ApplicationScoped
public class UseCaseConfig {

    /** Which country and bank an IBAN has to name to count as an internal book transfer. */
    @ConfigProperty(name = "payments.home-country", defaultValue = "DK")
    String homeCountry;

    @ConfigProperty(name = "payments.home-bank-code", defaultValue = "0040")
    String homeBankCode;

    @ConfigProperty(name = "payments.sepa-fee-minor", defaultValue = "50")
    long sepaFeeMinor;

    /** The settlement cutoff, in the calendar's own zone. After this, a SEPA transfer waits. */
    @ConfigProperty(name = "payments.sepa-cutoff", defaultValue = "16:00")
    String sepaCutoff;

    /** Where a SEPA transfer's money sits until the scheme settles it. */
    @ConfigProperty(name = "payments.sepa-suspense-account", defaultValue = "BANK.SEPA.SUSPENSE.EUR")
    String sepaSuspenseAccount;

    @ConfigProperty(name = "payments.fee-income-account", defaultValue = "BANK.INCOME.FEES.EUR")
    String feeIncomeAccount;

    /** Above this, a second person has to approve. Configuration, never a constant. */
    @ConfigProperty(name = "payments.four-eyes-threshold-minor", defaultValue = "1000000")
    long fourEyesThresholdMinor;

    @ConfigProperty(name = "payments.currency", defaultValue = "EUR")
    String currency;

    @Produces
    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Routing.
     *
     * <p>Safe to hold for the application's lifetime: the calendar is computed rather than
     * loaded, so unlike the sanctions list there is nothing here that can go stale.
     */
    @Produces
    @ApplicationScoped
    RequestTransfer requestTransfer(Transfers transfers, TransferRouter router, Clock clock) {
        return new RequestTransfer(transfers, router, clock);
    }

    @Produces
    @ApplicationScoped
    TransferOrchestrator transferOrchestrator(
            Transfers transfers,
            AccountDirectory accounts,
            RiskScreening risk,
            LedgerPosting ledger,
            SchemeGateway scheme) {

        return new TransferOrchestrator(
                transfers,
                accounts,
                risk,
                ledger,
                scheme,
                sepaSuspenseAccount,
                feeIncomeAccount,
                Money.of(fourEyesThresholdMinor, currency));
    }

    @Produces
    @Singleton
    TransferRouter transferRouter() {
        return new TransferRouter(
                homeCountry,
                homeBankCode,
                sepaFeeMinor,
                BusinessCalendar.target2(LocalTime.parse(sepaCutoff)));
    }
}