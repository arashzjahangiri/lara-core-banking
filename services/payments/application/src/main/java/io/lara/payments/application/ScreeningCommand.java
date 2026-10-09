package io.lara.payments.application;

import java.util.Objects;

import io.lara.payments.domain.Money;
import io.lara.payments.domain.TransferReference;

/**
 * A request for risk to decide.
 *
 * <p>The reference is the transfer's own, which is what makes retrying safe. Risk returns the
 * decision it already made for that reference rather than evaluating again, so a saga re-driven
 * after a crash gets the same answer it would have got the first time — not a fresh one taken
 * against limits that have moved on since.
 */
public record ScreeningCommand(
        TransferReference reference,
        String customerId,
        Money amount,
        String beneficiaryName) {

    public ScreeningCommand {
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(customerId, "customer id must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(beneficiaryName, "beneficiary name must not be null");
    }
}