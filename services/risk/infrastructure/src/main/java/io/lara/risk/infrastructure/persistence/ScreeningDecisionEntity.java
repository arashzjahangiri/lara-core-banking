package io.lara.risk.infrastructure.persistence;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.lara.risk.domain.CustomerId;
import io.lara.risk.domain.Money;
import io.lara.risk.domain.PartyName;
import io.lara.risk.domain.RecordedScreening;
import io.lara.risk.domain.ScreeningDecision;
import io.lara.risk.domain.ScreeningOutcome;
import io.lara.risk.domain.ScreeningReference;

/**
 * A decision that was made, as a row.
 *
 * <p>The reference is the primary key rather than a surrogate id, which is what makes a retried
 * screening find the first answer instead of producing a second. The uniqueness is enforced by
 * the database rather than by a check in the service, so two concurrent first attempts cannot
 * both decide they are the first.
 *
 * <p>The outcome is stored as its declared name and the reason as free text beside it. Code
 * branches on the outcome; the reason is for the human reading it.
 */
@Entity
@Table(name = "screening_decision")
public class ScreeningDecisionEntity {

    @Id
    @Column(name = "reference", nullable = false, length = 64)
    private String reference;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "beneficiary", nullable = false, length = 200)
    private String beneficiary;

    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "screened_at", nullable = false)
    private Instant screenedAt;

    protected ScreeningDecisionEntity() {
        // Hibernate.
    }

    public static ScreeningDecisionEntity from(RecordedScreening screening) {
        ScreeningDecisionEntity entity = new ScreeningDecisionEntity();
        entity.reference = screening.reference().value();
        entity.customerId = screening.customer().value();
        entity.amountMinor = screening.amount().minorUnits();
        entity.currency = screening.amount().currency().getCurrencyCode();
        entity.beneficiary = screening.beneficiary().original();
        entity.outcome = screening.outcome().name();
        entity.reason = screening.decision().reason();
        entity.screenedAt = screening.screenedAt();
        return entity;
    }

    public RecordedScreening toDomain() {
        ScreeningOutcome storedOutcome = ScreeningOutcome.valueOf(outcome);
        ScreeningDecision decision = switch (storedOutcome) {
            case ALLOW -> ScreeningDecision.allow(reason);
            case REVIEW -> ScreeningDecision.review(reason);
            case BLOCK -> ScreeningDecision.block(reason);
        };

        return new RecordedScreening(
                ScreeningReference.of(reference),
                CustomerId.of(customerId),
                Money.of(amountMinor, Currency.getInstance(currency)),
                PartyName.of(beneficiary),
                decision,
                screenedAt);
    }
}