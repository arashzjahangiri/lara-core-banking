package io.lara.payments.infrastructure.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.lara.payments.application.TransferEvents;
import io.lara.payments.domain.Transfer;
import io.lara.payments.domain.TransferState;

/**
 * Turns a finished transfer into an outbox row.
 *
 * <p>One row per terminal transfer, keyed by the transfer id so a consumer sees one transfer's
 * events on one partition and in order. That matters here more than it might look: a consumer
 * that saw {@code TransferCompensated} before {@code TransferCompleted} would have to reason
 * about a payment that was reversed before it succeeded.
 *
 * <p>{@code MANDATORY} rather than {@code REQUIRED}, deliberately. This must join the saga step's
 * transaction and never start one of its own — a separate transaction would commit the event even
 * when the step that produced it rolled back, which is precisely the dual-write problem the
 * outbox exists to remove.
 */
@ApplicationScoped
public class OutboxTransferEvents implements TransferEvents {

    private static final String AGGREGATE_TYPE = "payments.transfer";

    private final EntityManager entityManager;
    private final ObjectMapper json;

    public OutboxTransferEvents(EntityManager entityManager, ObjectMapper json) {
        this.entityManager = entityManager;
        this.json = json;
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void recordTerminal(Transfer transfer) {
        Optional<String> eventType = eventTypeFor(transfer);
        if (eventType.isEmpty()) {
            return;
        }

        entityManager.persist(OutboxEntity.event(
                AGGREGATE_TYPE,
                transfer.id().toString(),
                eventType.get(),
                payloadFor(transfer)));
    }

    /**
     * Which event a terminal state announces, if any.
     *
     * <p>Exhaustive over the sealed hierarchy with no {@code default}, so a new state stops this
     * compiling until someone has decided whether the outside world should hear about it. That
     * forced decision is the point — a {@code default} would silently publish nothing.
     *
     * <p>{@code FAILED} announces nothing. It means the system does not know what happened, and
     * there is no fact to publish; the right response is an operator, not a downstream state
     * change. The non-terminal states announce nothing because they are not finished.
     */
    private static Optional<String> eventTypeFor(Transfer transfer) {
        return switch (transfer.state()) {
            case TransferState.Completed ignored -> Optional.of("TransferCompleted");
            case TransferState.Rejected ignored -> Optional.of("TransferRejected");
            case TransferState.Compensated ignored -> Optional.of("TransferCompensated");

            case TransferState.Failed ignored -> Optional.empty();
            case TransferState.Requested ignored -> Optional.empty();
            case TransferState.Screening ignored -> Optional.empty();
            case TransferState.ApprovalPending ignored -> Optional.empty();
            case TransferState.Posting ignored -> Optional.empty();
            case TransferState.Posted ignored -> Optional.empty();
            case TransferState.Compensating ignored -> Optional.empty();
        };
    }

    /**
     * The exact JSON published for one transfer.
     *
     * <p>Public so a test can verify the <em>real</em> serialiser rather than a hand-written copy
     * of it. The ledger learned this the hard way in Phase 2: a provider test that rebuilt the
     * payload itself passed happily while the producer changed shape underneath it, which is the
     * one failure a contract test exists to prevent.
     *
     * <p>Both transaction references are included when they exist. ADR-0011 reverses by contra
     * entry rather than by deleting anything, so a compensated transfer has two postings in the
     * ledger and the honest record names both.
     */
    public String payloadFor(Transfer transfer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transferId", transfer.id().toString());
        payload.put("reference", transfer.reference().value());
        payload.put("status", transfer.status().name());
        payload.put("debtorIban", transfer.debtor().value());
        payload.put("creditorIban", transfer.creditor().value());
        payload.put("amountMinorUnits", transfer.amount().minorUnits());
        payload.put("feeMinorUnits", transfer.fee().minorUnits());
        payload.put("totalDebitMinorUnits", transfer.totalDebit().minorUnits());
        payload.put("currency", transfer.amount().currency().getCurrencyCode());
        payload.put("scheme", transfer.scheme().name());
        payload.put("valueDate", transfer.valueDate().toString());
        payload.put("requestedBy", transfer.requestedBy());
        payload.put("requestedAt", transfer.requestedAt().toString());
        payload.put("ledgerTransactionId",
                transfer.ledgerTransaction().map(Object::toString).orElse(null));
        payload.put("reversalTransactionId", reversalOf(transfer));
        payload.put("rejectionReason", rejectionReasonOf(transfer));

        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException impossible) {
            // A map of strings and longs. If this ever fails, failing the saga step is correct:
            // a transfer whose event cannot be written must not be recorded as finished either,
            // because the two commit together or not at all.
            throw new IllegalStateException(
                    "could not serialise the outbox payload for " + transfer.id(), impossible);
        }
    }

    private static String reversalOf(Transfer transfer) {
        return transfer.state() instanceof TransferState.Compensated compensated
                ? compensated.reversal().toString()
                : null;
    }

    private static String rejectionReasonOf(Transfer transfer) {
        return transfer.state() instanceof TransferState.Rejected rejected
                ? rejected.reason().name()
                : null;
    }
}
