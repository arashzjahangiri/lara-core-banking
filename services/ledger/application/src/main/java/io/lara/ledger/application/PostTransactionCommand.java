package io.lara.ledger.application;

import java.util.List;
import java.util.Objects;

import io.lara.ledger.domain.PostingLeg;
import io.lara.ledger.domain.TransactionReference;

/**
 * What a caller asks the ledger to record.
 *
 * <p>A command rather than a loose argument list, so adding a field later does not change every
 * call site and every test.
 *
 * <p>The reference comes from the caller and is what makes the posting idempotent. Everything else
 * about the recorded transaction — its identity and the instant it happened — is assigned by the
 * ledger: the identity because the ledger owns it, the instant because a caller's clock is not
 * evidence of when something was posted.
 */
public record PostTransactionCommand(TransactionReference reference, List<PostingLeg> legs) {

    public PostTransactionCommand {
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(legs, "legs must not be null");
        legs = List.copyOf(legs);
    }

    public static PostTransactionCommand of(TransactionReference reference, PostingLeg... legs) {
        return new PostTransactionCommand(reference, List.of(legs));
    }

    public static PostTransactionCommand of(String reference, PostingLeg... legs) {
        return of(TransactionReference.of(reference), legs);
    }
}
