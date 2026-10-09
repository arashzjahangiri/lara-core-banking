package io.lara.payments.infrastructure.rest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import io.lara.payments.domain.Transfer;
import io.lara.payments.domain.TransferState;
import io.lara.payments.domain.TransferTransition;

/**
 * The wire shapes for the transfer API.
 *
 * <p>Separate records rather than serialising {@link Transfer} directly. The aggregate holds a
 * sealed {@code TransferState} whose cases carry different data, a {@code Clock} and a mutable
 * history — none of which has a sensible JSON form, and exposing them would turn an internal
 * refactor into a breaking API change.
 */
final class TransferDtos {

    private TransferDtos() {
    }

    record RequestTransferBody(
            @NotBlank @Size(max = 34) String debtorIban,
            @NotBlank @Size(max = 34) String creditorIban,
            @Positive long amountMinorUnits,
            @NotBlank @Size(max = 3) String currency,
            @NotBlank @Size(max = 100) String requestedBy) {
    }

    /**
     * A transfer, as a client sees it.
     *
     * <p>{@code fee} and {@code totalDebit} are both present because they answer different
     * questions: what the creditor receives, and what leaves the debtor's account. A client shown
     * only the amount would under-report what the payment actually cost.
     */
    record TransferResponse(
            String id,
            String reference,
            String status,
            String debtorIban,
            String creditorIban,
            long amountMinorUnits,
            long feeMinorUnits,
            long totalDebitMinorUnits,
            String currency,
            String scheme,
            LocalDate valueDate,
            String requestedBy,
            Instant requestedAt,
            String ledgerTransactionId,
            String reversalTransactionId,
            String rejectionReason,
            String detail,
            List<TransitionResponse> history) {

        static TransferResponse of(Transfer transfer) {
            return new TransferResponse(
                    transfer.id().toString(),
                    transfer.reference().value(),
                    transfer.status().name(),
                    transfer.debtor().value(),
                    transfer.creditor().value(),
                    transfer.amount().minorUnits(),
                    transfer.fee().minorUnits(),
                    transfer.totalDebit().minorUnits(),
                    transfer.amount().currency().getCurrencyCode(),
                    transfer.scheme().name(),
                    transfer.valueDate(),
                    transfer.requestedBy(),
                    transfer.requestedAt(),
                    transfer.ledgerTransaction().map(Object::toString).orElse(null),
                    reversalOf(transfer),
                    rejectionReasonOf(transfer),
                    detailOf(transfer),
                    transfer.history().stream().map(TransitionResponse::of).toList());
        }

        /**
         * Pulls the per-state extras out of the sealed hierarchy.
         *
         * <p>Exhaustive with no default, so a new state stops this compiling until someone has
         * decided what a client should see of it.
         */
        private static String reversalOf(Transfer transfer) {
            return switch (transfer.state()) {
                case TransferState.Compensated compensated -> compensated.reversal().toString();
                case TransferState.Requested ignored -> null;
                case TransferState.Screening ignored -> null;
                case TransferState.ApprovalPending ignored -> null;
                case TransferState.Posting ignored -> null;
                case TransferState.Posted ignored -> null;
                case TransferState.Completed ignored -> null;
                case TransferState.Rejected ignored -> null;
                case TransferState.Compensating ignored -> null;
                case TransferState.Failed ignored -> null;
            };
        }

        private static String rejectionReasonOf(Transfer transfer) {
            return transfer.state() instanceof TransferState.Rejected rejected
                    ? rejected.reason().name()
                    : null;
        }

        private static String detailOf(Transfer transfer) {
            return switch (transfer.state()) {
                case TransferState.Rejected rejected -> rejected.detail();
                case TransferState.Compensating compensating -> compensating.cause();
                case TransferState.Compensated compensated -> compensated.cause();
                case TransferState.Failed failed -> failed.cause();
                case TransferState.ApprovalPending pending ->
                        "awaiting approval; requested by " + pending.requestedBy();
                case TransferState.Requested ignored -> null;
                case TransferState.Screening ignored -> null;
                case TransferState.Posting ignored -> null;
                case TransferState.Posted ignored -> null;
                case TransferState.Completed ignored -> null;
            };
        }
    }

    record TransitionResponse(String from, String to, Instant at, String note) {

        static TransitionResponse of(TransferTransition transition) {
            return new TransitionResponse(
                    transition.from().name(),
                    transition.to().name(),
                    transition.at(),
                    transition.note());
        }
    }

    /** The same problem shape the other services use, so one client can parse all of them. */
    record ProblemResponse(String error, String detail) {
    }
}