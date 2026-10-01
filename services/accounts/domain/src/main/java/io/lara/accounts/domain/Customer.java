package io.lara.accounts.domain;

import java.util.Objects;

/**
 * A person or organisation the bank holds accounts for.
 *
 * <p>Deliberately thin. A real bank holds addresses, identity documents, tax residency and a
 * great deal more besides, and none of it changes how money moves — so modelling it here would
 * add bulk without adding anything a reviewer has not seen.
 *
 * <p>{@code KycStatus} is the exception, because it genuinely gates behaviour: an account cannot
 * become usable while its owner is unverified.
 */
public record Customer(CustomerId id, String name, KycStatus kyc) {

    private static final int MAX_NAME_LENGTH = 200;

    public Customer {
        Objects.requireNonNull(id, "customer id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(kyc, "KYC status must not be null");

        name = name.strip();
        if (name.isBlank()) {
            throw new IllegalArgumentException("customer name must not be blank");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "customer name must be at most " + MAX_NAME_LENGTH + " characters");
        }
    }

    /** A customer who has just been registered and not yet verified. */
    public static Customer unverified(String name) {
        return new Customer(CustomerId.newId(), name, KycStatus.PENDING);
    }

    public Customer verified() {
        return new Customer(id, name, KycStatus.VERIFIED);
    }

    public Customer rejected() {
        return new Customer(id, name, KycStatus.REJECTED);
    }

    /** Whether this customer may hold a usable account. */
    public boolean mayHoldAnOpenAccount() {
        return kyc == KycStatus.VERIFIED;
    }

    public enum KycStatus {

        /** Registered, identity not yet confirmed. Accounts stay in OPENING. */
        PENDING,

        /** Identity confirmed. Accounts may be activated. */
        VERIFIED,

        /** Identity checks failed. No account may become usable. */
        REJECTED
    }
}
