package io.lara.risk.domain;

import java.util.Objects;

/**
 * Everything risk needs to decide, and nothing more.
 *
 * <p>Worth noticing what is absent. There is no account number, no address, no date of birth and
 * no transfer id — this service cannot identify anyone beyond the customer id it is handed, and
 * cannot reach back into payments to find out more. Narrow inputs are what keep the blast radius
 * of a bug here at "a transfer was wrongly stopped".
 */
public record ScreeningRequest(
        ScreeningReference reference,
        CustomerId customer,
        Money amount,
        PartyName beneficiary) {

    public ScreeningRequest {
        Objects.requireNonNull(reference, "screening reference must not be null");
        Objects.requireNonNull(customer, "customer must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(beneficiary, "beneficiary must not be null");

        if (amount.isNegative() || amount.isZero()) {
            throw new IllegalArgumentException("cannot screen a non-positive amount: " + amount);
        }
    }

    @Override
    public String toString() {
        return "screening " + reference + ": " + customer + " sending " + amount + " to " + beneficiary;
    }
}