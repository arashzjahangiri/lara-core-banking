package io.lara.payments.bootstrap;

import java.time.Clock;
import java.time.LocalTime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.lara.payments.domain.BusinessCalendar;
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
    @Singleton
    TransferRouter transferRouter() {
        return new TransferRouter(
                homeCountry,
                homeBankCode,
                sepaFeeMinor,
                BusinessCalendar.target2(LocalTime.parse(sepaCutoff)));
    }
}