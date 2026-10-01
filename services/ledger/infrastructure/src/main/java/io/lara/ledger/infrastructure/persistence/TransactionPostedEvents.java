package io.lara.ledger.infrastructure.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.lara.ledger.domain.AccountId;
import io.lara.ledger.domain.LedgerTransaction;
import io.lara.ledger.domain.PostingLeg;

/**
 * Turns a recorded transaction into outbox rows.
 *
 * <p>One row per account the transaction touched, not one per transaction. The account is the
 * aggregate id and therefore the Kafka key, so every movement affecting one account lands on the
 * same partition and stays ordered. A single event keyed by transaction id would scatter one
 * account's history across partitions, and a balance read model could then apply two movements out
 * of order and arrive at the wrong number.
 *
 * <p>A transaction that debits an account twice — the sender paying an amount and a fee — produces
 * one row carrying both legs, because they happened together and must be applied together.
 */
@ApplicationScoped
public class TransactionPostedEvents {

    private static final String AGGREGATE_TYPE = "ledger.account";
    private static final String EVENT_TYPE = "TransactionPosted";

    private final EntityManager entityManager;
    private final ObjectMapper json;

    public TransactionPostedEvents(EntityManager entityManager, ObjectMapper json) {
        this.entityManager = entityManager;
        this.json = json;
    }

    /**
     * Writes one event per affected account. Called from inside the same database transaction as
     * the postings, which is the entire point — if that transaction rolls back, these rows go with
     * it and no event is ever published for a posting that did not happen.
     */
    void recordFor(LedgerTransaction transaction) {
        Map<AccountId, List<PostingLeg>> byAccount = new LinkedHashMap<>();
        for (PostingLeg leg : transaction.legs()) {
            byAccount.computeIfAbsent(leg.account(), account -> new java.util.ArrayList<>()).add(leg);
        }

        byAccount.forEach((account, legs) -> entityManager.persist(OutboxEntity.event(
                AGGREGATE_TYPE,
                account.value(),
                EVENT_TYPE,
                payloadFor(transaction, account, legs))));
    }

    /**
     * The exact JSON published for one account of a transaction.
     *
     * <p>Visible beyond this class so the Pact provider test can verify the <em>real</em>
     * serialiser against the consumer's contract. A provider test that rebuilt the payload itself
     * would pass happily while this method changed shape underneath it, which is the one failure
     * a contract test exists to prevent.
     */
    public String payloadFor(LedgerTransaction transaction, AccountId account, List<PostingLeg> legs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId", transaction.id().toString());
        payload.put("reference", transaction.reference().value());
        payload.put("occurredAt", transaction.occurredAt().toString());
        payload.put("account", account.value());
        payload.put("currency", transaction.currency().getCurrencyCode());
        payload.put("legs", legs.stream().map(TransactionPostedEvents::legPayload).toList());

        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Unreachable with a map of strings and longs, and not worth a checked exception
            // escaping into the write path. If it ever happens, failing the posting is correct:
            // a transaction we cannot describe must not be recorded.
            throw new IllegalStateException("could not serialise outbox payload for " + transaction.id(), e);
        }
    }

    private static Map<String, Object> legPayload(PostingLeg leg) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("side", leg.side().name());
        entry.put("amountMinorUnits", leg.amount().minorUnits());
        entry.put("currency", leg.amount().currency().getCurrencyCode());
        return entry;
    }
}
