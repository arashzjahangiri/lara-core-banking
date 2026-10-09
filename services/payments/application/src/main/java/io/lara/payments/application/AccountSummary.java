package io.lara.payments.application;

import java.util.Currency;
import java.util.Objects;

import io.lara.payments.domain.Iban;

/**
 * What payments needs to know about an account, and nothing else.
 *
 * <p>Notably absent: the holder's name, address, balance. This service decides workflow, not
 * eligibility, and a summary that carried more would invite code here to start deciding things
 * that belong to accounts or to the ledger.
 *
 * <p>{@code ledgerAccountId} is the one field that matters most. Accounts owns the mapping from
 * an IBAN to the ledger account behind it, and payments must never guess at it — a posting to
 * the wrong ledger account moves real money to the wrong place.
 */
public record AccountSummary(
        Iban iban,
        String customerId,
        String ledgerAccountId,
        Currency currency,
        boolean active) {

    public AccountSummary {
        Objects.requireNonNull(iban, "iban must not be null");
        Objects.requireNonNull(customerId, "customer id must not be null");
        Objects.requireNonNull(ledgerAccountId, "ledger account id must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
    }
}