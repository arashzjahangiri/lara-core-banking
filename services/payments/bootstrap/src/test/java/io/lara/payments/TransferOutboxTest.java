package io.lara.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.lara.payments.application.TransferEvents;
import io.lara.payments.application.Transfers;
import io.lara.payments.domain.Iban;
import io.lara.payments.domain.LedgerTransactionRef;
import io.lara.payments.domain.Money;
import io.lara.payments.domain.RejectionReason;
import io.lara.payments.domain.RoutingDecision;
import io.lara.payments.domain.Transfer;
import io.lara.payments.domain.TransferId;
import io.lara.payments.domain.TransferScheme;
import io.lara.payments.infrastructure.persistence.OutboxTransferEvents;

/**
 * The transfer outbox, against a real PostgreSQL.
 *
 * <p>The assertion that justifies the whole pattern is the rollback one: a saga step that fails
 * must publish nothing. An event written in its own transaction would survive the rollback and
 * announce a transfer that never finished, and a consumer acting on it would be acting on
 * something that did not happen.
 *
 * <p>The payload is checked through {@link OutboxTransferEvents#payloadFor} — the production
 * serialiser — rather than against a hand-written copy. The ledger learned that lesson in Phase 2
 * when a provider test rebuilt the payload itself and kept passing while the producer changed
 * shape underneath it.
 *
 * <p>Parsed as JSON rather than compared as a string, because PostgreSQL stores {@code jsonb} in
 * its own normalised form and re-serialises it with different spacing. A string assertion fails
 * on data that is entirely correct.
 */
@QuarkusTest
class TransferOutboxTest {

    @Inject
    Transfers transfers;

    @Inject
    TransferEvents events;

    @Inject
    OutboxTransferEvents outbox;

    @Inject
    EntityManager entityManager;

    @Inject
    Clock clock;

    @Inject
    ObjectMapper json;

    private static final Iban DEBTOR = Iban.of("DK5000400440116243");
    private static final Iban CREDITOR = Iban.of("DE89370400440532013000");

    private static final RoutingDecision SEPA = new RoutingDecision(
            TransferScheme.SEPA_CREDIT_TRANSFER, Money.of(50, "EUR"), LocalDate.of(2026, 10, 5));

    private Transfer completed() {
        Transfer transfer = newTransfer();
        transfer.startScreening();
        transfer.startPosting();
        transfer.posted(LedgerTransactionRef.of(UUID.randomUUID()));
        transfer.complete();
        return transfer;
    }

    private Transfer newTransfer() {
        return Transfer.request(
                TransferId.newId(), DEBTOR, CREDITOR, Money.of(125_000, "EUR"), SEPA, "arash", clock);
    }

    /** Stores the transfer and its event in one transaction, exactly as the orchestrator does. */
    private void storeWithEvent(Transfer transfer) {
        QuarkusTransaction.requiringNew().run(() -> {
            transfers.add(transfer);
            events.recordTerminal(transfer);
        });
    }

    private List<JsonNode> outboxRowsFor(TransferId id) {
        return QuarkusTransaction.requiringNew().call(() -> entityManager
                .createNativeQuery("""
                        select event_type, payload::text
                        from outbox
                        where aggregate_id = :id
                        order by created_at
                        """)
                .setParameter("id", id.toString())
                .getResultList()
                .stream()
                .map(row -> {
                    Object[] columns = (Object[]) row;
                    com.fasterxml.jackson.databind.node.ObjectNode node = json.createObjectNode();
                    node.put("eventType", (String) columns[0]);
                    try {
                        node.set("payload", json.readTree((String) columns[1]));
                    } catch (Exception unreadable) {
                        throw new IllegalStateException("outbox payload is not JSON", unreadable);
                    }
                    return (JsonNode) node;
                })
                .toList());
    }

    @Nested
    @DisplayName("what gets written")
    class WhatIsWritten {

        @Test
        void a_completed_transfer_announces_itself() {
            Transfer transfer = completed();

            storeWithEvent(transfer);

            List<JsonNode> rows = outboxRowsFor(transfer.id());
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("eventType").asText()).isEqualTo("TransferCompleted");
        }

        @Test
        void a_rejected_transfer_announces_itself_with_its_reason() {
            Transfer transfer = newTransfer();
            transfer.startScreening();
            transfer.reject(RejectionReason.SCREENING_BLOCKED, "sanctions match");

            storeWithEvent(transfer);

            JsonNode row = outboxRowsFor(transfer.id()).get(0);
            assertThat(row.get("eventType").asText()).isEqualTo("TransferRejected");
            assertThat(row.get("payload").get("rejectionReason").asText())
                    .isEqualTo("SCREENING_BLOCKED");
            assertThat(row.get("payload").get("ledgerTransactionId").isNull()).isTrue();
        }

        /** ADR-0011 keeps both postings, so the honest record names both. */
        @Test
        void a_compensated_transfer_names_both_transactions() {
            LedgerTransactionRef posting = LedgerTransactionRef.of(UUID.randomUUID());
            LedgerTransactionRef reversal = LedgerTransactionRef.of(UUID.randomUUID());

            Transfer transfer = newTransfer();
            transfer.startScreening();
            transfer.startPosting();
            transfer.posted(posting);
            transfer.startCompensation("scheme rejected the payment");
            transfer.compensated(reversal);

            storeWithEvent(transfer);

            JsonNode payload = outboxRowsFor(transfer.id()).get(0).get("payload");
            assertThat(payload.get("ledgerTransactionId").asText()).isEqualTo(posting.toString());
            assertThat(payload.get("reversalTransactionId").asText()).isEqualTo(reversal.toString());
        }

        /**
         * A failed saga announces nothing. It means the system does not know what happened, so
         * there is no fact to publish — and a {@code TransferFailed} event would invite consumers
         * to act on an outcome nobody has established.
         */
        @Test
        void a_failed_transfer_announces_nothing() {
            Transfer transfer = newTransfer();
            transfer.fail("out of recovery attempts");

            storeWithEvent(transfer);

            assertThat(outboxRowsFor(transfer.id())).isEmpty();
        }

        /** Nor does one that is merely in progress. */
        @Test
        void an_unfinished_transfer_announces_nothing() {
            Transfer transfer = newTransfer();
            transfer.startScreening();

            storeWithEvent(transfer);

            assertThat(outboxRowsFor(transfer.id())).isEmpty();
        }
    }

    @Nested
    @DisplayName("the transaction boundary")
    class TransactionBoundary {

        /**
         * The assertion the whole pattern exists for. The step fails, the transaction rolls back,
         * and the event goes with it — so nothing announces a transfer that never finished.
         */
        @Test
        void a_rolled_back_step_publishes_nothing() {
            Transfer transfer = completed();

            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() ->
                    QuarkusTransaction.requiringNew().run(() -> {
                        transfers.add(transfer);
                        events.recordTerminal(transfer);
                        throw new IllegalStateException("the step failed after writing the event");
                    }));

            assertThat(outboxRowsFor(transfer.id())).isEmpty();

            // And the transfer itself is gone too, which is the point: the two commit together
            // or not at all.
            assertThat(QuarkusTransaction.requiringNew().call(() -> transfers.find(transfer.id())))
                    .isEmpty();
        }

        /**
         * Writing an event outside a transaction is refused rather than quietly starting one.
         *
         * <p>{@code MANDATORY} is doing real work here. With {@code REQUIRED} the adapter would
         * open its own transaction and commit the event independently — which is exactly the
         * dual-write problem the outbox removes, reintroduced by a single annotation.
         */
        @Test
        void refuses_to_write_an_event_with_no_transaction_to_join() {
            Transfer transfer = completed();

            assertThatExceptionOfType(jakarta.transaction.TransactionalException.class)
                    .isThrownBy(() -> events.recordTerminal(transfer));
        }
    }

    @Nested
    @DisplayName("the payload")
    class Payload {

        /**
         * Verified through the production serialiser, not a copy of it. A test that rebuilt the
         * payload would pass happily while the producer changed shape underneath it.
         */
        @Test
        void is_produced_by_the_one_serialiser_and_survives_the_database() {
            Transfer transfer = completed();
            String produced = outbox.payloadFor(transfer);

            storeWithEvent(transfer);

            JsonNode stored = outboxRowsFor(transfer.id()).get(0).get("payload");

            // Parsed, not string-compared: jsonb re-serialises with its own spacing, so an exact
            // string match fails on data that is entirely correct.
            assertThat(stored).isEqualTo(readTree(produced));
        }

        @Test
        void carries_what_a_consumer_needs() {
            Transfer transfer = completed();
            storeWithEvent(transfer);

            JsonNode payload = outboxRowsFor(transfer.id()).get(0).get("payload");

            assertThat(payload.get("transferId").asText()).isEqualTo(transfer.id().toString());
            assertThat(payload.get("reference").asText()).isEqualTo(transfer.reference().value());
            assertThat(payload.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(payload.get("amountMinorUnits").asLong()).isEqualTo(125_000L);
            assertThat(payload.get("feeMinorUnits").asLong()).isEqualTo(50L);
            assertThat(payload.get("totalDebitMinorUnits").asLong()).isEqualTo(125_050L);
            assertThat(payload.get("currency").asText()).isEqualTo("EUR");
            assertThat(payload.get("scheme").asText()).isEqualTo("SEPA_CREDIT_TRANSFER");
            assertThat(payload.get("valueDate").asText()).isEqualTo("2026-10-05");
        }

        /**
         * The amount and the total debit differ whenever there is a fee, and a consumer needs
         * both: the creditor receives one, the debtor pays the other.
         */
        @Test
        void distinguishes_the_amount_from_what_the_debtor_paid() {
            Transfer transfer = completed();
            storeWithEvent(transfer);

            JsonNode payload = outboxRowsFor(transfer.id()).get(0).get("payload");

            assertThat(payload.get("amountMinorUnits").asLong())
                    .isNotEqualTo(payload.get("totalDebitMinorUnits").asLong());
        }

        private JsonNode readTree(String raw) {
            try {
                return json.readTree(raw);
            } catch (Exception unreadable) {
                throw new IllegalStateException("produced payload is not JSON", unreadable);
            }
        }
    }

    @Nested
    @DisplayName("the Kafka key")
    class Key {

        /**
         * One transfer's events share a partition, so they arrive in order. A consumer that saw
         * {@code TransferCompensated} before {@code TransferCompleted} would have to reason about
         * a payment reversed before it succeeded.
         */
        @Test
        void is_the_transfer_id() {
            Transfer transfer = completed();
            storeWithEvent(transfer);

            String aggregateId = QuarkusTransaction.requiringNew().call(() -> (String) entityManager
                    .createNativeQuery("select aggregate_id from outbox where aggregate_id = :id")
                    .setParameter("id", transfer.id().toString())
                    .getSingleResult());

            assertThat(aggregateId).isEqualTo(transfer.id().toString());
        }

        /** Routed to its own topic, separate from the ledger's. */
        @Test
        void routes_by_an_aggregate_type_of_its_own() {
            Transfer transfer = completed();
            storeWithEvent(transfer);

            String aggregateType = QuarkusTransaction.requiringNew().call(() -> (String) entityManager
                    .createNativeQuery("select aggregate_type from outbox where aggregate_id = :id")
                    .setParameter("id", transfer.id().toString())
                    .getSingleResult());

            assertThat(aggregateType).isEqualTo("payments.transfer");
        }
    }

}
