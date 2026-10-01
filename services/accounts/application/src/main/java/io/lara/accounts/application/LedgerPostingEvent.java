package io.lara.accounts.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import io.lara.accounts.domain.PostingDirection;

/**
 * A posting the ledger published, as this service understands it.
 *
 * <p>This is the <strong>contract</strong> between the two services, restated in the consumer's
 * own terms rather than imported from the producer. If the ledger renames a field, this does not
 * silently follow — the consumer breaks at its boundary, which is where a contract test can catch
 * it. Importing the producer's class would make the break invisible until runtime.
 *
 * <p>One event per account per transaction, which is why {@code legs} can hold more than one: a
 * sender paying an amount and a fee is debited twice in the same movement, and the two must be
 * applied together or not at all.
 */
public record LedgerPostingEvent(String transactionId,
        String reference,
        Instant occurredAt,
        String account,
        String currency,
        List<Leg> legs) {

    public LedgerPostingEvent {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(legs, "legs must not be null");

        legs = List.copyOf(legs);
        if (legs.isEmpty()) {
            throw new IllegalArgumentException("a posting event must carry at least one leg");
        }
    }

    public record Leg(PostingDirection side, long amountMinorUnits, String currency) {

        public Leg {
            Objects.requireNonNull(side, "side must not be null");
            Objects.requireNonNull(currency, "currency must not be null");
            if (amountMinorUnits <= 0) {
                throw new IllegalArgumentException(
                        "a leg must move a positive amount, was " + amountMinorUnits);
            }
        }
    }
}
