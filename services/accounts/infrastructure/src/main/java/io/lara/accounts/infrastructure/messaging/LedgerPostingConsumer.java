package io.lara.accounts.infrastructure.messaging;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import io.lara.accounts.application.ApplyLedgerPosting;
import io.lara.accounts.application.LedgerPostingEvent;
import io.lara.accounts.domain.PostingDirection;

/**
 * Consumes postings the ledger published and feeds them to the balance projection.
 *
 * <p>The JSON is parsed here rather than bound to a shared class, deliberately. Importing the
 * ledger's event type would couple the two services' releases — the point of the contract test is
 * that the consumer states its own expectations and finds out when the producer stops meeting
 * them, rather than silently compiling against whatever the producer currently emits.
 *
 * <p>The whole handler runs in one database transaction, so the claim that deduplicates and the
 * balance it guards commit together. Claiming without applying would lose a posting permanently —
 * the event would be marked seen while the balance never moved, and no redelivery would fix it.
 */
@ApplicationScoped
public class LedgerPostingConsumer {

    private static final Logger LOG = Logger.getLogger(LedgerPostingConsumer.class);

    private final ApplyLedgerPosting applyPosting;
    private final ObjectMapper json;

    public LedgerPostingConsumer(ApplyLedgerPosting applyPosting, ObjectMapper json) {
        this.applyPosting = applyPosting;
        this.json = json;
    }

    @Incoming("ledger-postings")
    @Transactional
    public void onPosting(String payload) {
        LedgerPostingEvent event = parse(payload);

        ApplyLedgerPosting.Outcome outcome = applyPosting.apply(event);

        if (outcome.applied()) {
            LOG.debugv("Applied {0} to {1}", event.transactionId(), event.account());
        } else {
            // Routine, not exceptional: Debezium republishes after a restart. Logged at debug so
            // a redelivery storm does not look like an incident.
            LOG.debugv("Skipped {0} on {1}: already applied", event.transactionId(), event.account());
        }
    }

    private LedgerPostingEvent parse(String payload) {
        try {
            JsonNode root = json.readTree(payload);

            List<LedgerPostingEvent.Leg> legs = new ArrayList<>();
            for (JsonNode leg : root.path("legs")) {
                legs.add(new LedgerPostingEvent.Leg(
                        PostingDirection.parse(leg.path("side").asText()),
                        leg.path("amountMinorUnits").asLong(),
                        leg.path("currency").asText()));
            }

            return new LedgerPostingEvent(
                    root.path("transactionId").asText(),
                    root.path("reference").asText(),
                    Instant.parse(root.path("occurredAt").asText()),
                    root.path("account").asText(),
                    root.path("currency").asText(),
                    legs);

        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            // An unreadable event must stop this message rather than be skipped. Skipping would
            // lose a movement silently; failing sends it to the dead-letter topic where somebody
            // can look at it.
            throw new IllegalStateException("could not read ledger posting event: " + payload, e);
        }
    }
}
