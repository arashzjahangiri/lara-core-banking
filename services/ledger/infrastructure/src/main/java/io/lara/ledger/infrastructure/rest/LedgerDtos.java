package io.lara.ledger.infrastructure.rest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import io.lara.ledger.domain.AccountBalance;
import io.lara.ledger.domain.LedgerTransaction;
import io.lara.ledger.domain.PostingLeg;

/**
 * The wire shapes, kept separate from the domain on purpose.
 *
 * <p>Bean Validation annotations live here and nowhere else. A domain record validates itself in
 * its constructor — a transaction whose legs do not balance cannot be built — and putting
 * {@code @NotNull} on it would suggest the rule is optional, enforced by whoever remembered to say
 * {@code @Valid}. These annotations guard the boundary, not the model.
 *
 * <p>Serialising a domain type directly would also freeze its internals into the public API. The
 * mapping here is the seam that lets the two change independently.
 */
final class LedgerDtos {

    private LedgerDtos() {
    }

    /** Amounts cross the wire as minor units, never as a decimal, so no client can round them. */
    record PostingLegRequest(
            @NotNull @Size(max = 64) @Pattern(regexp = "[A-Z0-9]+(\\.[A-Z0-9]+)*",
                    message = "must be uppercase dot-separated alphanumerics") String account,
            @NotNull @Pattern(regexp = "DEBIT|CREDIT") String side,
            @NotNull @Positive Long amountMinorUnits,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {
    }

    record PostTransactionRequest(
            @NotNull @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*",
                    message = "must be alphanumerics with . _ : or -") String reference,
            @NotEmpty @Valid List<PostingLegRequest> legs) {
    }

    record PostingLegResponse(String account, String side, long amountMinorUnits, String currency, String amount) {

        static PostingLegResponse of(PostingLeg leg) {
            return new PostingLegResponse(
                    leg.account().value(),
                    leg.side().name(),
                    leg.amount().minorUnits(),
                    leg.amount().currency().getCurrencyCode(),
                    leg.amount().toDecimal().toPlainString());
        }
    }

    record TransactionResponse(
            String id,
            String reference,
            Instant occurredAt,
            String currency,
            long totalMinorUnits,
            List<PostingLegResponse> legs) {

        static TransactionResponse of(LedgerTransaction transaction) {
            return new TransactionResponse(
                    transaction.id().toString(),
                    transaction.reference().value(),
                    transaction.occurredAt(),
                    transaction.currency().getCurrencyCode(),
                    transaction.totalDebits().minorUnits(),
                    transaction.legs().stream().map(PostingLegResponse::of).toList());
        }
    }

    record BalanceResponse(
            String account,
            String accountClass,
            String currency,
            long minorUnits,
            BigDecimal amount,
            String side,
            Instant asOf) {

        static BalanceResponse of(AccountBalance balance) {
            return new BalanceResponse(
                    balance.account().id().value(),
                    balance.account().accountClass().name(),
                    balance.amount().currency().getCurrencyCode(),
                    balance.amount().minorUnits(),
                    balance.amount().toDecimal(),
                    balance.side().name(),
                    balance.asOf());
        }
    }

    /** Whether the hash chain still adds up, and where it stops if it does not. */
    record IntegrityResponse(boolean intact, long transactionsChecked, BreakResponse firstBreak) {

        static IntegrityResponse of(io.lara.ledger.application.ChainIntegrity integrity) {
            return new IntegrityResponse(
                    integrity.isIntact(),
                    integrity.transactionsChecked(),
                    integrity.firstBreak().map(BreakResponse::of).orElse(null));
        }
    }

    record BreakResponse(long sequence, String transaction, String reason, String expected, String actual) {

        static BreakResponse of(io.lara.ledger.application.ChainIntegrity.Break failure) {
            return new BreakResponse(
                    failure.sequence(),
                    failure.transaction().toString(),
                    failure.reason().name(),
                    failure.expected(),
                    failure.actual());
        }
    }

    /** A problem the caller can act on, rather than a stack trace. */
    record ProblemResponse(String error, String detail) {
    }
}
