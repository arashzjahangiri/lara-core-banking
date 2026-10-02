package io.lara.risk.infrastructure.persistence;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.lara.risk.application.ScreenedTotals;
import io.lara.risk.domain.CustomerId;
import io.lara.risk.domain.Money;

/**
 * Sums what a customer has already had screened on one business day.
 *
 * <p>Summed on demand rather than kept as a running counter. A counter would need to be
 * incremented in the same transaction as the decision and reset at a day boundary, and both of
 * those are things that go wrong quietly — a counter that drifts high blocks legitimate payments
 * and nobody can explain why. The sum is derived from the decisions themselves, so it cannot
 * disagree with them.
 *
 * <p>Blocked screenings are excluded in SQL rather than filtered afterwards: the money never
 * moved, and counting refusals towards the limit would let a customer lock themselves out.
 */
@ApplicationScoped
public class JpaScreenedTotals implements ScreenedTotals {

    private final EntityManager entityManager;
    private final ZoneId businessZone;

    public JpaScreenedTotals(
            EntityManager entityManager,
            @ConfigProperty(name = "risk.business-zone", defaultValue = "Europe/Brussels") String zone) {

        this.entityManager = entityManager;
        this.businessZone = ZoneId.of(zone);
    }

    @Override
    public Money totalFor(CustomerId customer, LocalDate day, Currency currency) {
        // The day is a window in the business zone, not a UTC calendar date. Comparing against a
        // date would put a payment made at 00:30 Brussels time on the previous day's total.
        var startOfDay = day.atStartOfDay(businessZone).toInstant();
        var startOfNextDay = day.plusDays(1).atStartOfDay(businessZone).toInstant();

        Long total = entityManager.createQuery("""
                        select coalesce(sum(d.amountMinor), 0)
                        from ScreeningDecisionEntity d
                        where d.customerId = :customer
                          and d.currency = :currency
                          and d.outcome <> 'BLOCK'
                          and d.screenedAt >= :from
                          and d.screenedAt < :to
                        """, Long.class)
                .setParameter("customer", customer.value())
                .setParameter("currency", currency.getCurrencyCode())
                .setParameter("from", startOfDay)
                .setParameter("to", startOfNextDay)
                .getSingleResult();

        return Money.of(total, currency);
    }
}