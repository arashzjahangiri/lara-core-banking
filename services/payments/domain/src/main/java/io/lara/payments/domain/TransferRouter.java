package io.lara.payments.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;

/**
 * Decides how a transfer travels, from nothing but its destination and the clock.
 *
 * <p>The whole decision comes out of the creditor's IBAN. An IBAN carries its country and its
 * bank, so "is this one of ours" is arithmetic on a string rather than a call to the accounts
 * service — which matters, because routing runs before the saga starts and a transfer that is
 * going to be priced should not need a network round trip to be priced.
 *
 * <p>The home country, the home bank code, the fee and the calendar all arrive through the
 * constructor. A router that read its own configuration could not be built with {@code new} in a
 * test, and the cutoff is exactly the sort of value that has to be varied across a dozen cases to
 * be trusted at all.
 */
public final class TransferRouter {

    /** Where the bank code starts in an IBAN: two country letters plus two check digits. */
    private static final int BANK_CODE_OFFSET = 4;

    private final String homeCountry;
    private final String homeBankCode;
    private final long sepaFeeMinorUnits;
    private final BusinessCalendar calendar;

    public TransferRouter(
            String homeCountry, String homeBankCode, long sepaFeeMinorUnits, BusinessCalendar calendar) {

        this.homeCountry = Objects.requireNonNull(homeCountry, "home country must not be null");
        this.homeBankCode = Objects.requireNonNull(homeBankCode, "home bank code must not be null");
        this.sepaFeeMinorUnits = sepaFeeMinorUnits;
        this.calendar = Objects.requireNonNull(calendar, "calendar must not be null");

        if (sepaFeeMinorUnits < 0) {
            throw new IllegalArgumentException("a fee cannot be negative, was " + sepaFeeMinorUnits);
        }
    }

    /**
     * Routes one transfer.
     *
     * @param creditor    where the money is going; the only input the scheme depends on
     * @param currency    the transfer's currency, which the fee is denominated in
     * @param requestedAt when the customer asked, used against the cutoff
     */
    public RoutingDecision route(Iban creditor, Currency currency, Instant requestedAt) {
        Objects.requireNonNull(creditor, "creditor must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");

        TransferScheme scheme = schemeFor(creditor);
        return new RoutingDecision(scheme, feeFor(scheme, currency), valueDateFor(scheme, requestedAt));
    }

    /**
     * Whether the destination is an account at this bank.
     *
     * <p>The bank code is read at a fixed offset, which is only safe because this check can only
     * ever match our own country — an IBAN from somewhere else has already been ruled out by the
     * time the offset is used, and the field layout of a foreign IBAN is therefore irrelevant.
     */
    public TransferScheme schemeFor(Iban creditor) {
        Objects.requireNonNull(creditor, "creditor must not be null");

        if (!creditor.countryCode().equals(homeCountry)) {
            return TransferScheme.SEPA_CREDIT_TRANSFER;
        }
        boolean sameBank = creditor.value().startsWith(homeBankCode, BANK_CODE_OFFSET);
        return sameBank ? TransferScheme.INTERNAL : TransferScheme.SEPA_CREDIT_TRANSFER;
    }

    /** Nothing for a book transfer; the configured fee otherwise, in the transfer's own currency. */
    public Money feeFor(TransferScheme scheme, Currency currency) {
        return switch (scheme) {
            case INTERNAL -> Money.zero(currency);
            case SEPA_CREDIT_TRANSFER -> Money.of(sepaFeeMinorUnits, currency);
        };
    }

    /**
     * When the money lands.
     *
     * <p>An internal transfer settles the day it is booked, cutoff or not: both accounts are in
     * this ledger and no settlement system has to be open for one entry to face another. A SEPA
     * transfer goes through TARGET2 and gets the calendar's answer, which rolls past weekends,
     * closing days and the daily cutoff.
     */
    public LocalDate valueDateFor(TransferScheme scheme, Instant requestedAt) {
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");

        return switch (scheme) {
            case INTERNAL -> requestedAt.atZone(calendar.zone()).toLocalDate();
            case SEPA_CREDIT_TRANSFER -> calendar.valueDate(requestedAt);
        };
    }

    public BusinessCalendar calendar() {
        return calendar;
    }
}
