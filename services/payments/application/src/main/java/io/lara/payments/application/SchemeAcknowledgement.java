package io.lara.payments.application;

import java.util.Objects;

/**
 * The scheme's answer.
 *
 * <p>Two outcomes and no third, because from this service's point of view there is no third: the
 * scheme has taken responsibility for the payment or it has not. "Pending" would be a lie —
 * either we are holding the money or they are.
 *
 * <p>A rejection carries its reason, which ends up in the transfer's history and, eventually, in
 * what a customer is told about why their payment came back.
 */
public record SchemeAcknowledgement(boolean accepted, String reason) {

    public SchemeAcknowledgement {
        Objects.requireNonNull(reason, "reason must not be null");
    }

    public static SchemeAcknowledgement accepted(String reason) {
        return new SchemeAcknowledgement(true, reason);
    }

    public static SchemeAcknowledgement rejected(String reason) {
        return new SchemeAcknowledgement(false, reason);
    }
}