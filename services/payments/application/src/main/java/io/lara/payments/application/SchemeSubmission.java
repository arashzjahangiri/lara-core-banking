package io.lara.payments.application;

import java.util.Objects;

import io.lara.payments.domain.Iban;
import io.lara.payments.domain.Money;
import io.lara.payments.domain.TransferReference;

/**
 * What the settlement scheme is asked to carry.
 *
 * <p>Submitted after the ledger posting, never before. That ordering is what creates the one
 * situation compensation exists for: the money has already moved inside this bank, and the party
 * that was going to take it onward has declined.
 */
public record SchemeSubmission(
        TransferReference reference,
        Iban debtor,
        Iban creditor,
        Money amount,
        java.time.LocalDate valueDate) {

    public SchemeSubmission {
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(debtor, "debtor must not be null");
        Objects.requireNonNull(creditor, "creditor must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(valueDate, "value date must not be null");
    }
}