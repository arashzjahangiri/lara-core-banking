package io.lara.payments.application;

import java.util.Objects;

import io.lara.payments.domain.Transfer;
import io.lara.payments.domain.TransferId;
import io.lara.payments.domain.TransferStatus;

/**
 * What a checker does to a parked transfer.
 *
 * <p>Small on purpose. The rule that matters — a maker cannot approve their own payment — lives
 * in the aggregate, not here, so this use case only loads, applies and saves. A use case that
 * re-checked it would be a second copy of the rule, and two copies of a control drift.
 *
 * <p>Approving does not drive the saga. It unparks the transfer and leaves it in {@code POSTING};
 * whoever called is responsible for driving it, and if nobody does, the recovery sweep will. That
 * keeps the approval itself quick and atomic — a checker clicking approve should not wait on
 * three downstream services, and their decision should be recorded even if the posting then
 * fails.
 */
public final class DecideApproval {

    private final Transfers transfers;

    public DecideApproval(Transfers transfers) {
        this.transfers = Objects.requireNonNull(transfers, "transfers must not be null");
    }

    /** Approves a parked transfer, moving it to {@code POSTING}. */
    public Transfer approve(TransferId id, String checker) {
        StoredTransfer stored = load(id);
        stored.transfer().approve(checker);
        transfers.update(stored);
        return stored.transfer();
    }

    /** Declines a parked transfer, ending it in {@code REJECTED} with no money ever moved. */
    public Transfer decline(TransferId id, String checker, String reason) {
        StoredTransfer stored = load(id);
        stored.transfer().declineApproval(checker, reason);
        transfers.update(stored);
        return stored.transfer();
    }

    /**
     * Loads a transfer that is actually waiting for a decision.
     *
     * <p>The state check is here as well as in the aggregate because the two produce different
     * answers. The aggregate throws an illegal-transition error, which is correct but reads as a
     * bug; this says the plainer thing — that there is nothing here to approve — which is what a
     * checker opening a stale browser tab needs to be told.
     */
    private StoredTransfer load(TransferId id) {
        StoredTransfer stored = transfers.find(id).orElseThrow(
                () -> new IllegalArgumentException("no such transfer: " + id));

        if (stored.transfer().status() != TransferStatus.APPROVAL_PENDING) {
            throw new NotAwaitingApprovalException(id, stored.transfer().status());
        }
        return stored;
    }
}
