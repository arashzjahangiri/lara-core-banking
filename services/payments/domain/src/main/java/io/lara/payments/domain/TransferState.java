package io.lara.payments.domain;

import java.util.Objects;

/**
 * Where a transfer is, and what that position implies.
 *
 * <p>Sealed rather than an enum because most states carry evidence. A completed transfer knows
 * which ledger transaction moved the money; a compensated one knows both that transaction and the
 * reversal that undid it; a rejected one knows why. Holding those on the aggregate as nullable
 * fields would make "completed with no ledger transaction" a representable value, and the first
 * job of this type is that it should not be.
 *
 * <p>The other half of that job belongs to {@link Transfer}, which owns the transitions. This
 * file says what a state <em>is</em>; the aggregate says which ones may follow which.
 *
 * <p>Switching over this hierarchy with no {@code default} is deliberate throughout the service.
 * Adding a state then stops the mappers and the orchestrator compiling until someone has decided
 * what it means, which is the one moment where that decision is cheap to make.
 */
public sealed interface TransferState {

    /** The stable, persisted name of this state. */
    TransferStatus status();

    /** Terminal states are final. Nothing transitions out of one and the recovery sweep skips them. */
    default boolean isTerminal() {
        return status().isTerminal();
    }

    /** Accepted and recorded, nothing attempted yet. */
    record Requested() implements TransferState {
        @Override
        public TransferStatus status() {
            return TransferStatus.REQUESTED;
        }
    }

    /** Waiting on the risk service's answer. */
    record Screening() implements TransferState {
        @Override
        public TransferStatus status() {
            return TransferStatus.SCREENING;
        }
    }

    /**
     * Waiting on a second person.
     *
     * <p>Carries who asked, so the rule that the maker may not also be the checker can be decided
     * from the state itself rather than by re-reading the request.
     */
    record ApprovalPending(String requestedBy) implements TransferState {

        public ApprovalPending {
            Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        }

        @Override
        public TransferStatus status() {
            return TransferStatus.APPROVAL_PENDING;
        }
    }

    /**
     * The ledger has been asked to move the money and has not yet answered.
     *
     * <p>The genuinely dangerous state, and the reason recovery exists. A crash here leaves a
     * transfer whose outcome is unknown to this service but entirely settled in the ledger, which
     * is why the posting reference is deterministic: re-driving the step is how the saga finds
     * out, and it cannot double-post while finding out.
     */
    record Posting() implements TransferState {
        @Override
        public TransferStatus status() {
            return TransferStatus.POSTING;
        }
    }

    /**
     * The money has moved and the saga is not finished.
     *
     * <p>This state is what makes compensation expressible. Collapsing it into {@code COMPLETED}
     * would leave a failure between "the ledger accepted the posting" and "the transfer
     * succeeded" with nowhere to be recorded, and that gap is precisely where money goes missing.
     */
    record Posted(LedgerTransactionRef ledgerTransaction) implements TransferState {

        public Posted {
            Objects.requireNonNull(ledgerTransaction, "a posted transfer must name its ledger transaction");
        }

        @Override
        public TransferStatus status() {
            return TransferStatus.POSTED;
        }
    }

    /** Finished, money moved. */
    record Completed(LedgerTransactionRef ledgerTransaction) implements TransferState {

        public Completed {
            Objects.requireNonNull(ledgerTransaction, "a completed transfer must name its ledger transaction");
        }

        @Override
        public TransferStatus status() {
            return TransferStatus.COMPLETED;
        }
    }

    /**
     * Finished, and no money moved.
     *
     * <p>Reachable only from states before the posting succeeded. That is enforced by the
     * transitions rather than stated here, but it is the invariant that lets a caller read
     * {@code REJECTED} as "nothing happened" with no further checking.
     */
    record Rejected(RejectionReason reason, String detail) implements TransferState {

        public Rejected {
            Objects.requireNonNull(reason, "a rejection must have a reason");
            Objects.requireNonNull(detail, "a rejection must have a detail");
        }

        @Override
        public TransferStatus status() {
            return TransferStatus.REJECTED;
        }
    }

    /** Money moved and is being moved back. */
    record Compensating(LedgerTransactionRef ledgerTransaction, String cause) implements TransferState {

        public Compensating {
            Objects.requireNonNull(ledgerTransaction, "compensation must name the posting it is undoing");
            Objects.requireNonNull(cause, "compensation must record why it started");
        }

        @Override
        public TransferStatus status() {
            return TransferStatus.COMPENSATING;
        }
    }

    /**
     * Finished, money moved and was moved back.
     *
     * <p>Both transaction references are kept. ADR-0011 reverses by contra entry rather than by
     * deleting anything, so the ledger holds two postings and the honest record of what happened
     * names both of them.
     */
    record Compensated(
            LedgerTransactionRef ledgerTransaction,
            LedgerTransactionRef reversal,
            String cause) implements TransferState {

        public Compensated {
            Objects.requireNonNull(ledgerTransaction, "a compensated transfer must name the original posting");
            Objects.requireNonNull(reversal, "a compensated transfer must name the reversal");
            Objects.requireNonNull(cause, "a compensated transfer must record why it was reversed");
        }

        @Override
        public TransferStatus status() {
            return TransferStatus.COMPENSATED;
        }
    }

    /**
     * Stuck, and a person has to look at it.
     *
     * <p>Distinct from rejected, which is an answer. This is the absence of one: the saga ran out
     * of attempts, or compensation itself failed and the money is sitting somewhere it should not
     * be. Nothing automatic will fix it, which is exactly why it is terminal.
     */
    record Failed(String cause) implements TransferState {

        public Failed {
            Objects.requireNonNull(cause, "a failed transfer must record why");
        }

        @Override
        public TransferStatus status() {
            return TransferStatus.FAILED;
        }
    }
}
