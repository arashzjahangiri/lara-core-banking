package io.lara.payments.infrastructure.client;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;

import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.smallrye.faulttolerance.api.CircuitBreakerName;

import io.lara.payments.application.LedgerPosting;
import io.lara.payments.application.LedgerRefusedException;
import io.lara.payments.application.PostingCommand;
import io.lara.payments.application.RemoteServiceException;
import io.lara.payments.domain.LedgerTransactionRef;
import io.lara.payments.infrastructure.client.LedgerClient.PostTransactionRequest;
import io.lara.payments.infrastructure.client.LedgerClient.PostingLegRequest;
import io.lara.payments.infrastructure.client.LedgerClient.TransactionResponse;

/**
 * Turns an instruction to move money into double-entry legs, and a failure into the right kind
 * of failure.
 *
 * <h2>Building the entry</h2>
 *
 * <p>This is the one place in payments that knows transfers become legs. The orchestrator says
 * what should happen; this says how the ledger records it. A transfer with a fee has three legs
 * rather than two, because the debtor pays more than the creditor receives and the difference is
 * the bank's income — collapsing it into two would either short the creditor or lose the fee.
 *
 * <p>The legs sum to zero, which the ledger enforces anyway. Getting it wrong here is a refused
 * posting rather than a silent imbalance, and that is the correct direction for the mistake to
 * fall in.
 *
 * <h2>Telling a refusal from an outage</h2>
 *
 * <p>The distinction the saga branches on. A 4xx means the ledger looked at the entry and said
 * no, so no money moved and the transfer is rejected. A timeout or a 5xx means the outcome is
 * unknown — the posting may well have succeeded — so the saga must not conclude anything and
 * recovery re-drives the step instead.
 *
 * <p>There is deliberately no fallback. A synthetic success here would tell the saga money had
 * moved when it had not, and the transfer would be marked complete over a posting that does not
 * exist.
 *
 * <h2>Why retrying a posting is safe</h2>
 *
 * <p>Only because of ADR-0007. The reference is derived from the transfer id, so a repeated post
 * returns the transaction the ledger already recorded rather than moving the money again. Retry
 * on a call without that guarantee is how one payment becomes three.
 *
 * <p>{@code abortOn} is the other half and matters more. A refusal is a decision — the entry is
 * invalid, or the funds are not there — and retrying it four times produces the same refusal four
 * times while the customer waits. Only an unreachable ledger is worth another attempt.
 */
@ApplicationScoped
public class RestLedgerPosting implements LedgerPosting {

    private static final String SERVICE = "ledger";
    private static final String DEBIT = "DEBIT";
    private static final String CREDIT = "CREDIT";

    private final LedgerClient ledger;

    public RestLedgerPosting(@RestClient LedgerClient ledger) {
        this.ledger = ledger;
    }

    @Override
    @Retry(maxRetries = 3, delay = 200, jitter = 150,
            retryOn = RemoteServiceException.class,
            abortOn = LedgerRefusedException.class)
    @CircuitBreaker(requestVolumeThreshold = 8, failureRatio = 0.5, delay = 10_000,
            failOn = RemoteServiceException.class)
    @CircuitBreakerName("ledger")
    public LedgerTransactionRef post(PostingCommand command) {
        try {
            TransactionResponse response = ledger.post(new PostTransactionRequest(
                    command.reference().value(), legsFor(command)));

            return LedgerTransactionRef.of(response.id());

        } catch (WebApplicationException answered) {
            throw interpret(answered, "posting " + command.reference());
        } catch (ProcessingException unreachable) {
            throw new RemoteServiceException(SERVICE,
                    "could not post " + command.reference(), unreachable);
        }
    }

    /**
     * Retried, but deliberately not behind a breaker.
     *
     * <p>A breaker exists to stop pointless work against a dependency that is clearly down. That
     * reasoning does not hold here: when a reversal is outstanding, money has already left a
     * customer''s account and is sitting in suspense, so the cost of one more attempt is nothing
     * against the cost of not making it.
     *
     * <p>Sharing the posting breaker would be worse still. A burst of failed postings would open
     * it, and the reversals that burst created would then be refused without ever reaching the
     * ledger — the system would stop putting money back precisely when it had most to put back.
     */
    @Override
    @Retry(maxRetries = 3, delay = 200, jitter = 150,
            retryOn = RemoteServiceException.class,
            abortOn = LedgerRefusedException.class)
    public LedgerTransactionRef reverse(LedgerTransactionRef original, String reason) {
        try {
            return LedgerTransactionRef.of(ledger.reverse(original.value().toString()).id());

        } catch (WebApplicationException answered) {
            throw interpret(answered, "reversing " + original);
        } catch (ProcessingException unreachable) {
            throw new RemoteServiceException(SERVICE, "could not reverse " + original, unreachable);
        }
    }

    /**
     * The legs for one transfer.
     *
     * <p>Two when there is no fee, three when there is. The fee leg is only added when it is
     * non-zero, because a zero-amount leg is noise in the ledger and the ledger rejects it.
     */
    private static List<PostingLegRequest> legsFor(PostingCommand command) {
        String currency = command.amount().currency().getCurrencyCode();
        List<PostingLegRequest> legs = new ArrayList<>(3);

        // The debtor pays the amount plus the fee; the creditor receives only the amount.
        long totalDebit = command.amount().minorUnits() + command.fee().minorUnits();

        legs.add(new PostingLegRequest(command.debtorLedgerAccount(), DEBIT, totalDebit, currency));
        legs.add(new PostingLegRequest(
                command.creditorLedgerAccount(), CREDIT, command.amount().minorUnits(), currency));

        if (!command.fee().isZero()) {
            legs.add(new PostingLegRequest(
                    command.feeIncomeAccount(), CREDIT, command.fee().minorUnits(), currency));
        }
        return legs;
    }

    /**
     * Decides what a response code means for the saga.
     *
     * <p>4xx is a decision and 5xx is not, which is the whole of it. The boundary matters:
     * calling a 503 a refusal would reject valid transfers for the length of an outage, and
     * calling a 422 an outage would retry an entry the ledger will never accept.
     */
    private static RuntimeException interpret(WebApplicationException answered, String what) {
        int status = answered.getResponse().getStatus();

        if (status >= 400 && status < 500) {
            return new LedgerRefusedException(
                    "the ledger refused " + what + " with " + status + ": " + detailOf(answered));
        }
        return new RemoteServiceException(SERVICE, what + " failed with " + status, answered);
    }

    /** The ledger's own problem detail, which names the rule that was broken. */
    private static String detailOf(WebApplicationException answered) {
        try {
            return answered.getResponse().readEntity(String.class);
        } catch (RuntimeException unreadable) {
            // Never let a failure to read an error body replace the error itself.
            return "(no readable detail)";
        }
    }
}
