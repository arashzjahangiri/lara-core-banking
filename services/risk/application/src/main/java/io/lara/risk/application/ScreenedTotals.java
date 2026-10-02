package io.lara.risk.application;

import java.time.LocalDate;
import java.util.Currency;

import io.lara.risk.domain.CustomerId;
import io.lara.risk.domain.Money;

/**
 * How much a customer has already had screened on a given day.
 *
 * <p>The daily limit is cumulative, so this is what makes it mean anything: without a running
 * total, a limit is per-transfer and is defeated by sending the same money in ten pieces.
 *
 * <p>Blocked screenings are excluded. The money never moved, and counting a refusal towards the
 * limit would let a customer lock themselves out with payments that were declined.
 */
public interface ScreenedTotals {

    Money totalFor(CustomerId customer, LocalDate day, Currency currency);
}