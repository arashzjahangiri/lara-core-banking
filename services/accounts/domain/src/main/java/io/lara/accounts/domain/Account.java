package io.lara.accounts.domain;

import java.util.Currency;
import java.util.Objects;

/**
 * A customer's account: who owns it, its IBAN, its currency, and where it lives in the ledger.
 *
 * <p>Holds <strong>no balance</strong>. The ledger owns money; this service owns identity. The
 * balance a customer sees comes from a read model built by consuming ledger events, and the
 * ledger remains the source of truth — so a disagreement between the two is always resolved in
 * the ledger's favour.
 *
 * <p>{@code ledgerAccountId} is the only thing tying the two services together, and it is a plain
 * string rather than a shared type. Sharing a library between services would couple their release
 * cycles, which is most of what having separate services is meant to avoid.
 */
public record Account(Iban iban,
        CustomerId customer,
        String ledgerAccountId,
        Currency currency,
        AccountStatus status) {

    public Account {
        Objects.requireNonNull(iban, "IBAN must not be null");
        Objects.requireNonNull(customer, "customer must not be null");
        Objects.requireNonNull(ledgerAccountId, "ledger account id must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(status, "status must not be null");

        if (ledgerAccountId.isBlank()) {
            throw new IllegalArgumentException("ledger account id must not be blank");
        }
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException(
                    "currency " + currency.getCurrencyCode() + " cannot denominate an account");
        }
    }

    /** A newly opened account, not yet usable. */
    public static Account opening(Iban iban, CustomerId customer, String ledgerAccountId, Currency currency) {
        return new Account(iban, customer, ledgerAccountId, currency, AccountStatus.OPENING);
    }

    /**
     * @throws IllegalStateException when the transition is not allowed — most importantly, when
     *     the account is closed, which is terminal
     */
    public Account moveTo(AccountStatus next) {
        Objects.requireNonNull(next, "next status must not be null");
        if (!status.canMoveTo(next)) {
            throw new IllegalStateException(
                    "account " + iban + " cannot move from " + status + " to " + next);
        }
        return new Account(iban, customer, ledgerAccountId, currency, next);
    }

    public Account activate() {
        return moveTo(AccountStatus.OPEN);
    }

    public Account freeze() {
        return moveTo(AccountStatus.FROZEN);
    }

    public Account close() {
        return moveTo(AccountStatus.CLOSED);
    }

    public boolean permitsMovement() {
        return status.permitsMovement();
    }

    @Override
    public String toString() {
        return iban + " (" + currency.getCurrencyCode() + ", " + status + ")";
    }
}
