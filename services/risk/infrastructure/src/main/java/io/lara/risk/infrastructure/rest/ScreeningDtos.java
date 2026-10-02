package io.lara.risk.infrastructure.rest;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import io.lara.risk.domain.RecordedScreening;

/**
 * The wire shapes, kept out of the domain.
 *
 * <p>Separate records rather than serialising {@code RecordedScreening} directly. The domain type
 * holds a {@code PartyName} with its normalised tokens and a sealed {@code ScreeningDecision},
 * neither of which has a sensible JSON form — and exposing them would make an internal refactor
 * into a breaking API change.
 */
final class ScreeningDtos {

    private ScreeningDtos() {
    }

    /**
     * A request to screen.
     *
     * <p>The reference is supplied by the caller and required. It is what makes a retry safe, so
     * a request without one is a request that cannot be retried safely — better refused at the
     * edge than accepted and then ambiguous.
     */
    record ScreeningRequestBody(
            @NotBlank @Size(max = 64) String reference,
            @NotBlank String customerId,
            @Positive long amountMinorUnits,
            @NotBlank @Size(max = 3) String currency,
            @NotBlank @Size(max = 200) String beneficiaryName) {
    }

    /**
     * A decision.
     *
     * <p>{@code screenedAt} is included so a caller can tell a fresh decision from a replayed
     * one. Payments records it in the transfer's history, and a timestamp that silently moved on
     * every retry would make that history read as several screenings rather than one.
     */
    record ScreeningResponse(
            String reference,
            String customerId,
            long amountMinorUnits,
            String currency,
            String beneficiaryName,
            String outcome,
            String reason,
            Instant screenedAt) {

        static ScreeningResponse of(RecordedScreening screening) {
            return new ScreeningResponse(
                    screening.reference().value(),
                    screening.customer().toString(),
                    screening.amount().minorUnits(),
                    screening.amount().currency().getCurrencyCode(),
                    screening.beneficiary().original(),
                    screening.outcome().name(),
                    screening.decision().reason(),
                    screening.screenedAt());
        }
    }

    /** The same problem shape the other services use, so one client can parse all of them. */
    record ProblemResponse(String error, String detail) {
    }
}
