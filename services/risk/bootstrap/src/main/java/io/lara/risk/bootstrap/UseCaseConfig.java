package io.lara.risk.bootstrap;

import java.time.Clock;
import java.time.ZoneId;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.lara.risk.application.CustomerLimitsDirectory;
import io.lara.risk.application.ScreenTransfer;
import io.lara.risk.application.ScreenedTotals;
import io.lara.risk.application.ScreeningDecisions;
import io.lara.risk.application.SanctionsSource;
import io.lara.risk.domain.Money;
import io.lara.risk.domain.TransferScreening;

/**
 * The only place the annotation-free application layer meets Quarkus, as in the other services.
 *
 * <p>Quarkus resolves each port to its adapter at build time, so a missing binding fails the
 * build rather than surfacing as a null on the first screening.
 */
@ApplicationScoped
public class UseCaseConfig {

    /**
     * The limits applied to a customer with no row of their own, which is most of them.
     *
     * <p>Configuration rather than a constant so it can be lowered without a deployment —
     * the same reason the per-customer rows are data.
     */
    @ConfigProperty(name = "risk.default-daily-limit-minor", defaultValue = "1000000")
    long defaultDailyLimitMinor;

    @ConfigProperty(name = "risk.default-review-above-minor", defaultValue = "500000")
    long defaultReviewAboveMinor;

    @ConfigProperty(name = "risk.currency", defaultValue = "EUR")
    String currency;

    /** Which zone the daily limit's day is measured in. Settlement runs on Brussels time. */
    @ConfigProperty(name = "risk.business-zone", defaultValue = "Europe/Brussels")
    String businessZone;

    @Produces
    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The screening rules.
     *
     * <p>Safe to hold for the lifetime of the application because it is stateless. The sanctions
     * list is not part of it — that arrives per call, so a regulator's addition takes effect on
     * the next screening rather than the next deployment.
     */
    @Produces
    @ApplicationScoped
    TransferScreening transferScreening() {
        return new TransferScreening();
    }

    @Produces
    @ApplicationScoped
    ScreenTransfer screenTransfer(
            TransferScreening screening,
            SanctionsSource sanctions,
            CustomerLimitsDirectory limits,
            ScreenedTotals totals,
            ScreeningDecisions decisions,
            Clock clock) {

        return new ScreenTransfer(
                screening,
                sanctions,
                limits,
                totals,
                decisions,
                Money.of(defaultDailyLimitMinor, currency),
                Money.of(defaultReviewAboveMinor, currency),
                ZoneId.of(businessZone),
                clock);
    }
}
