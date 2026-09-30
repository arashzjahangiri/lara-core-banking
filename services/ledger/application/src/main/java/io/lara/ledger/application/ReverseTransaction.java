package io.lara.ledger.application;

import java.time.Clock;
import java.util.Objects;

import io.lara.ledger.domain.LedgerTransaction;
import io.lara.ledger.domain.TransactionId;
import io.lara.ledger.domain.TransactionReference;

/**
 * Undoes a transaction by posting its opposite.
 *
 * <p>Nothing is edited and nothing is deleted. The original stays exactly as written and a second
 * transaction cancels it, so the books show both that the movement happened and that it was
 * reversed. That is the difference between an audit trail and a cache of the current state — and
 * the database enforces it anyway, since {@code UPDATE} and {@code DELETE} are refused by a
 * trigger.
 *
 * <p>The reversal's reference is derived from the original's, so reversing twice is naturally
 * idempotent: the second attempt collides with the reference already recorded and is answered with
 * the reversal that exists.
 */
public final class ReverseTransaction {

    /** Appended to the original's reference. Keeps the reversal idempotent and traceable. */
    static final String REFERENCE_SUFFIX = ":reversal";

    private final LedgerTransactions transactions;
    private final Clock clock;

    public ReverseTransaction(LedgerTransactions transactions, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * @return the reversing transaction, and whether this call is what recorded it
     * @throws PostingRejectedException.UnknownTransaction when there is nothing to reverse
     * @throws PostingRejectedException.AlreadyReversed when a reversal is already recorded
     */
    public PostTransactionResult reverse(TransactionId originalId) {
        Objects.requireNonNull(originalId, "transaction id must not be null");

        LedgerTransaction original = transactions.findById(originalId)
                .orElseThrow(() -> new PostingRejectedException.UnknownTransaction(originalId));

        TransactionReference reversalReference = reversalReferenceFor(original);

        // Reversing a reversal would be a loop with no meaning: the second one restores the
        // original movement, which is a new posting rather than an undo. Refuse it explicitly.
        if (original.reference().value().endsWith(REFERENCE_SUFFIX)) {
            throw new PostingRejectedException.AlreadyReversed(original.id(), original.reference());
        }

        transactions.findByReference(reversalReference).ifPresent(existing -> {
            throw new PostingRejectedException.AlreadyReversed(original.id(), existing.reference());
        });

        LedgerTransaction reversal = original.contra(
                TransactionId.newId(), reversalReference, clock.instant());

        LedgerTransaction recorded = transactions.append(reversal);
        return recorded.id().equals(reversal.id())
                ? PostTransactionResult.created(recorded)
                : PostTransactionResult.alreadyRecorded(recorded);
    }

    private static TransactionReference reversalReferenceFor(LedgerTransaction original) {
        return TransactionReference.of(original.reference().value() + REFERENCE_SUFFIX);
    }
}
