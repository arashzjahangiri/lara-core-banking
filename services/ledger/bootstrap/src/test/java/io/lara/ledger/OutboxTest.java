package io.lara.ledger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * The outbox exists to remove the dual-write problem: a posting and the event describing it must
 * commit together or not at all. These tests assert exactly that, by checking the rows rather than
 * by trusting that the code calls things in the right order.
 */
@QuarkusTest
class OutboxTest {

    @Inject
    EntityManager entityManager;

    @Inject
    javax.sql.DataSource dataSource;

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper json;

    private static final String CASH = "BANK.CASH.EUR";
    private static final String SENDER = "CUSTOMER000001";
    private static final String RECEIVER = "CUSTOMER000002";
    private static final String FEE_INCOME = "BANK.FEE.INCOME.EUR";

    private static String reference() {
        return "OB-" + UUID.randomUUID();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> outboxFor(String reference) {
        return entityManager.createNativeQuery("""
                select aggregate_id, event_type, payload::text
                from outbox
                where payload->>'reference' = :reference
                order by aggregate_id
                """)
                .setParameter("reference", reference)
                .getResultList();
    }

    private long outboxCount() {
        return ((Number) entityManager.createNativeQuery("select count(*) from outbox").getSingleResult())
                .longValue();
    }

    @Nested
    @DisplayName("writing the event with the posting")
    class SameTransaction {

        @Test
        void a_committed_posting_leaves_one_event_per_affected_account() {
            String reference = reference();
            String body = """
                    {
                      "reference": "%s",
                      "legs": [
                        {"account": "%s", "side": "DEBIT",  "amountMinorUnits": 10000, "currency": "EUR"},
                        {"account": "%s", "side": "CREDIT", "amountMinorUnits": 9950,  "currency": "EUR"},
                        {"account": "%s", "side": "CREDIT", "amountMinorUnits": 50,    "currency": "EUR"}
                      ]
                    }
                    """.formatted(reference, CASH, RECEIVER, FEE_INCOME);

            given().contentType(ContentType.JSON).body(body)
                    .when().post("/ledger/transactions").then().statusCode(201);

            List<Object[]> events = outboxFor(reference);

            assertThat(events).hasSize(3);
            assertThat(events).extracting(row -> (String) row[0])
                    .containsExactly(CASH, FEE_INCOME, RECEIVER);
            assertThat(events).extracting(row -> (String) row[1])
                    .containsOnly("TransactionPosted");
        }

        /**
         * The whole reason the outbox exists. A posting that never committed must leave no event
         * behind, or a consumer acts on money that did not move.
         */
        @Test
        void a_rejected_posting_leaves_no_event_at_all() {
            long before = outboxCount();
            String reference = reference();

            // Unbalanced: rejected by the domain before anything is written.
            String unbalanced = """
                    {
                      "reference": "%s",
                      "legs": [
                        {"account": "%s", "side": "DEBIT",  "amountMinorUnits": 10000, "currency": "EUR"},
                        {"account": "%s", "side": "CREDIT", "amountMinorUnits": 9950,  "currency": "EUR"}
                      ]
                    }
                    """.formatted(reference, CASH, SENDER);

            given().contentType(ContentType.JSON).body(unbalanced)
                    .when().post("/ledger/transactions").then().statusCode(422);

            assertThat(outboxFor(reference)).isEmpty();
            assertThat(outboxCount()).isEqualTo(before);
        }

        @Test
        void a_posting_rejected_for_an_unknown_account_leaves_no_event() {
            long before = outboxCount();
            String reference = reference();
            String body = """
                    {
                      "reference": "%s",
                      "legs": [
                        {"account": "%s", "side": "DEBIT",  "amountMinorUnits": 100, "currency": "EUR"},
                        {"account": "CUSTOMER999999", "side": "CREDIT", "amountMinorUnits": 100, "currency": "EUR"}
                      ]
                    }
                    """.formatted(reference, CASH);

            given().contentType(ContentType.JSON).body(body)
                    .when().post("/ledger/transactions").then().statusCode(422);

            assertThat(outboxCount()).isEqualTo(before);
        }

        /** A retry moves no money, so it must not produce a second set of events either. */
        @Test
        void a_retry_produces_no_further_events() {
            String reference = reference();
            String body = """
                    {
                      "reference": "%s",
                      "legs": [
                        {"account": "%s", "side": "DEBIT",  "amountMinorUnits": 2500, "currency": "EUR"},
                        {"account": "%s", "side": "CREDIT", "amountMinorUnits": 2500, "currency": "EUR"}
                      ]
                    }
                    """.formatted(reference, CASH, SENDER);

            given().contentType(ContentType.JSON).body(body)
                    .when().post("/ledger/transactions").then().statusCode(201);
            int afterFirst = outboxFor(reference).size();

            given().contentType(ContentType.JSON).body(body)
                    .when().post("/ledger/transactions").then().statusCode(200);

            assertThat(outboxFor(reference)).hasSize(afterFirst).hasSize(2);
        }
    }

    @Nested
    @DisplayName("the payload")
    class Payload {

        @Test
        void carries_everything_a_consumer_needs_without_reading_the_ledger() throws Exception {
            String reference = reference();
            String body = """
                    {
                      "reference": "%s",
                      "legs": [
                        {"account": "%s", "side": "DEBIT",  "amountMinorUnits": 7500, "currency": "EUR"},
                        {"account": "%s", "side": "CREDIT", "amountMinorUnits": 7500, "currency": "EUR"}
                      ]
                    }
                    """.formatted(reference, CASH, SENDER);

            given().contentType(ContentType.JSON).body(body)
                    .when().post("/ledger/transactions").then().statusCode(201);

            // Parsed rather than string-matched: PostgreSQL stores JSONB as a normalised tree and
            // re-serialises it with its own spacing, so asserting on the text shape would be
            // testing Postgres's formatter rather than our payload.
            var payload = json.readTree((String) outboxFor(reference).get(0)[2]);

            assertThat(payload.get("reference").asText()).isEqualTo(reference);
            assertThat(payload.get("account").asText()).isEqualTo(CASH);
            assertThat(payload.get("currency").asText()).isEqualTo("EUR");
            assertThat(payload.get("transactionId").asText()).isNotBlank();
            assertThat(payload.get("occurredAt").asText()).isNotBlank();
            assertThat(payload.get("legs")).hasSize(1);
            assertThat(payload.get("legs").get(0).get("side").asText()).isEqualTo("DEBIT");
            assertThat(payload.get("legs").get(0).get("amountMinorUnits").asLong()).isEqualTo(7500L);
        }

        /** Two legs on one account happened together and must be applied together. */
        @Test
        void groups_both_legs_when_one_transaction_touches_an_account_twice() throws Exception {
            String reference = reference();
            String body = """
                    {
                      "reference": "%s",
                      "legs": [
                        {"account": "%s", "side": "DEBIT",  "amountMinorUnits": 10000, "currency": "EUR"},
                        {"account": "%s", "side": "DEBIT",  "amountMinorUnits": 50,    "currency": "EUR"},
                        {"account": "%s", "side": "CREDIT", "amountMinorUnits": 10000, "currency": "EUR"},
                        {"account": "%s", "side": "CREDIT", "amountMinorUnits": 50,    "currency": "EUR"}
                      ]
                    }
                    """.formatted(reference, SENDER, SENDER, RECEIVER, FEE_INCOME);

            given().contentType(ContentType.JSON).body(body)
                    .when().post("/ledger/transactions").then().statusCode(201);

            List<Object[]> events = outboxFor(reference);

            // Three accounts touched, not four legs.
            assertThat(events).hasSize(3);

            var senderPayload = json.readTree(events.stream()
                    .filter(row -> SENDER.equals(row[0]))
                    .map(row -> (String) row[2])
                    .findFirst()
                    .orElseThrow());

            assertThat(senderPayload.get("legs")).hasSize(2);
            assertThat(senderPayload.get("legs"))
                    .extracting(leg -> leg.get("amountMinorUnits").asLong())
                    .containsExactly(10_000L, 50L);
        }
    }

    @Nested
    @DisplayName("the outbox is a queue, not a record")
    class NotAnAuditRecord {

        /**
         * Unlike the ledger tables, the outbox must be deletable — it is a transport buffer that
         * gets pruned once published. The append-only trigger is deliberately not attached here,
         * and that distinction is worth asserting so nobody "fixes" it later.
         */
        @Test
        void rows_can_be_deleted_unlike_postings() throws Exception {
            String reference = reference();
            String body = """
                    {
                      "reference": "%s",
                      "legs": [
                        {"account": "%s", "side": "DEBIT",  "amountMinorUnits": 100, "currency": "EUR"},
                        {"account": "%s", "side": "CREDIT", "amountMinorUnits": 100, "currency": "EUR"}
                      ]
                    }
                    """.formatted(reference, CASH, SENDER);

            given().contentType(ContentType.JSON).body(body)
                    .when().post("/ledger/transactions").then().statusCode(201);

            try (var connection = dataSource.getConnection();
                    var statement = connection.prepareStatement(
                            "delete from outbox where payload->>'reference' = ?")) {
                statement.setString(1, reference);
                int deleted = statement.executeUpdate();
                assertThat(deleted).isEqualTo(2);
            }

            assertThat(outboxFor(reference)).isEmpty();
        }
    }
}
