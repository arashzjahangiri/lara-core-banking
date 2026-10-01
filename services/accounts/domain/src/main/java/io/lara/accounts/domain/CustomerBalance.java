package io.lara.accounts.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * What a customer sees when they look at their account.
 *
 * <p>A <strong>read model</strong>, not the truth. The ledger is the source of truth and this is
 * a projection of it, maintained by consuming posting events. The two can disagree briefly —
 * delivery is asynchronous — and when they do, the ledger is right.
 *
 * <p>That is why every balance carries {@code lastUpdated} and the id of the last transaction
 * applied. A customer-facing balance that cannot say how stale it is invites the assumption that
 * it is current, and the moment the consumer falls behind that assumption becomes a wrong number
 * displayed with total confidence.
 *
 * <p>Customer accounts are liabilities of the bank, so a <em>credit</em> raises this balance and a
 * <em>debit</em> lowers it — the opposite of how the bank's own cash account behaves. This service
 * projects only customer accounts, so it can encode that rather than carrying an account class.
 */
public record CustomerBalance(String ledgerAccountId,
        Money amount,
        String lastTransactionId,
        Instant lastUpdated) {

    public CustomerBalance {
        Objects.requireNonNull(ledgerAccountId, "ledger account id must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(lastUpdated, "lastUpdated must not be null");

        if (ledgerAccountId.isBlank()) {
            throw new IllegalArgumentException("ledger account id must not be blank");
        }
    }

    /** A balance before any posting has been seen. */
    public static CustomerBalance opening(String ledgerAccountId, Money zero, Instant at) {
        return new CustomerBalance(ledgerAccountId, zero, null, at);
    }

    /**
     * Applies one posting leg.
     *
     * <p>A credit raises the balance because the account is a liability of the bank: the more it
     * is credited, the more the bank owes. Getting this backwards would show a customer's savings
     * as a debt.
     */
    public CustomerBalance apply(PostingDirection direction, Money movement, String transactionId, Instant at) {
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(movement, "movement must not be null");
        Objects.requireNonNull(at, "at must not be null");

        Money updated = direction == PostingDirection.CREDIT
                ? amount.plus(movement)
                : amount.minus(movement);

        return new CustomerBalance(ledgerAccountId, updated, transactionId, at);
    }

    /** True when the customer owes the bank — an overdraft, or a reversal taking it below zero. */
    public boolean isOverdrawn() {
        return amount.isNegative();
    }

    /** How far behind the ledger this projection might be, as of {@code now}. */
    public java.time.Duration stalenessAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return java.time.Duration.between(lastUpdated, now);
    }

    @Override
    public String toString() {
        return ledgerAccountId + " " + amount + " as at " + lastUpdated;
    }
}
