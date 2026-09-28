package io.lara.ledger.application;

import java.util.List;
import java.util.Objects;

import io.lara.ledger.domain.PostingLeg;

/**
 * What a caller asks the ledger to record.
 *
 * <p>A command rather than a loose argument list, so adding a field later — a narrative, an
 * idempotency reference — does not change every call site and every test.
 *
 * <p>It carries no identity and no timestamp. Both are assigned by the ledger when the transaction
 * is recorded: the identity because the ledger owns it, and the instant because a caller's clock
 * is not evidence of when something was posted.
 */
public record PostTransactionCommand(List<PostingLeg> legs) {

    public PostTransactionCommand {
        Objects.requireNonNull(legs, "legs must not be null");
        legs = List.copyOf(legs);
    }

    public static PostTransactionCommand of(PostingLeg... legs) {
        return new PostTransactionCommand(List.of(legs));
    }
}
