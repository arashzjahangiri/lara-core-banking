package io.lara.risk;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The screening API, end to end against a real PostgreSQL.
 *
 * <p>Quarkus Dev Services starts the database, so there is no connection string in this file and
 * nothing to install before running it. Real Postgres rather than an in-memory substitute,
 * because the things most worth checking here are the things a substitute gets wrong: that
 * Hibernate's mapping actually matches the Flyway schema, that {@code on conflict} behaves, and
 * that a {@code timestamptz} survives the round trip unchanged.
 *
 * <p>The schema check is not incidental. Hibernate runs on {@code validate}, so a column this
 * service thinks exists and the migration never created is a startup failure — which means every
 * test in this class is also a test that the two agree.
 */
@QuarkusTest
class ScreeningApiTest {

    @Inject
    EntityManager entityManager;

    private static String newReference() {
        return "SCR-" + UUID.randomUUID();
    }

    private static Map<String, Object> body(
            String reference, String customer, long amount, String beneficiary) {
        return Map.of(
                "reference", reference,
                "customerId", customer,
                "amountMinorUnits", amount,
                "currency", "EUR",
                "beneficiaryName", beneficiary);
    }

    @Nested
    @DisplayName("POST /risk/screenings")
    class Screening {

        @Test
        void allows_an_ordinary_transfer_and_says_why() {
            given().contentType(ContentType.JSON)
                    .body(body(newReference(), UUID.randomUUID().toString(), 10_000, "Jens Hansen"))
                    .when().post("/risk/screenings")
                    .then()
                    .statusCode(200)
                    .body("outcome", is("ALLOW"))
                    .body("reason", containsString("within the daily limit"))
                    .body("currency", is("EUR"))
                    .body("amountMinorUnits", is(10_000));
        }

        /**
         * The seeded list is what makes this work, and the spelling is the point: the row says
         * "Åse Bjørk" and the request says "Ase Bjork". A list that only matched its own stored
         * form would be defeated by an accent.
         */
        @Test
        void blocks_a_beneficiary_on_the_seeded_sanctions_list() {
            given().contentType(ContentType.JSON)
                    .body(body(newReference(), UUID.randomUUID().toString(), 10_000, "Ase Bjork"))
                    .when().post("/risk/screenings")
                    .then()
                    .statusCode(200)
                    .body("outcome", is("BLOCK"))
                    .body("reason", containsString("DEMO-EU-2026/114"));
        }

        @Test
        void sends_a_shared_surname_for_review() {
            given().contentType(ContentType.JSON)
                    .body(body(newReference(), UUID.randomUUID().toString(), 10_000, "Natalia Petrov"))
                    .when().post("/risk/screenings")
                    .then()
                    .statusCode(200)
                    .body("outcome", is("REVIEW"))
                    .body("reason", containsString("not a match"));
        }

        @Test
        void blocks_a_transfer_over_the_default_daily_limit() {
            given().contentType(ContentType.JSON)
                    .body(body(newReference(), UUID.randomUUID().toString(), 1_000_001, "Jens Hansen"))
                    .when().post("/risk/screenings")
                    .then()
                    .statusCode(200)
                    .body("outcome", is("BLOCK"))
                    .body("reason", containsString("daily limit"));
        }

        /**
         * The cumulative rule, through the real query. Two transfers that are each fine and
         * together are not — which a per-transfer limit would wave straight through.
         */
        @Test
        void counts_a_customers_earlier_screenings_from_the_same_day() {
            String customer = UUID.randomUUID().toString();

            given().contentType(ContentType.JSON)
                    .body(body(newReference(), customer, 600_000, "Jens Hansen"))
                    .when().post("/risk/screenings")
                    .then().statusCode(200).body("outcome", is("REVIEW"));

            given().contentType(ContentType.JSON)
                    .body(body(newReference(), customer, 400_001, "Jens Hansen"))
                    .when().post("/risk/screenings")
                    .then()
                    .statusCode(200)
                    .body("outcome", is("BLOCK"))
                    .body("reason", containsString("EUR 6000.00 already screened today"));
        }

        /**
         * A blocked attempt moved no money, so it must not consume the customer's day. Without
         * this, anyone could lock themselves out with payments that were refused.
         */
        @Test
        void does_not_count_a_blocked_screening_towards_the_day() {
            String customer = UUID.randomUUID().toString();

            given().contentType(ContentType.JSON)
                    .body(body(newReference(), customer, 10_000, "Ivan Petrov"))
                    .when().post("/risk/screenings")
                    .then().statusCode(200).body("outcome", is("BLOCK"));

            // The whole daily limit is still available.
            given().contentType(ContentType.JSON)
                    .body(body(newReference(), customer, 1_000_000, "Jens Hansen"))
                    .when().post("/risk/screenings")
                    .then().statusCode(200).body("outcome", is("REVIEW"));
        }
    }

    @Nested
    @DisplayName("persistence")
    class Persistence {

        /**
         * The ordering the use case exists to guarantee. A decision that reached a caller must
         * already be in the database, because the alternative — answering and then crashing
         * before saving — leaves payments holding a decision this service has no memory of.
         */
        @Test
        void writes_the_decision_down_before_answering() {
            String reference = newReference();

            given().contentType(ContentType.JSON)
                    .body(body(reference, UUID.randomUUID().toString(), 10_000, "Jens Hansen"))
                    .when().post("/risk/screenings")
                    .then().statusCode(200);

            assertThat(storedOutcome(reference)).isEqualTo("ALLOW");
        }

        /** The timestamp must survive Postgres unchanged, or a replay would not equal the original. */
        @Test
        void returns_a_screened_at_that_round_trips_through_the_database() {
            String reference = newReference();

            String first = given().contentType(ContentType.JSON)
                    .body(body(reference, UUID.randomUUID().toString(), 10_000, "Jens Hansen"))
                    .when().post("/risk/screenings")
                    .then().statusCode(200)
                    .extract().path("screenedAt");

            String replayed = given()
                    .when().get("/risk/screenings/" + reference)
                    .then().statusCode(200)
                    .extract().path("screenedAt");

            assertThat(replayed).isEqualTo(first);
        }


    }

    @Nested
    @DisplayName("asking the same question twice")
    class Idempotency {

        /**
         * Payments retries a timed-out screening rather than guessing. The second call must
         * return the first answer, not a fresh evaluation against a world that has moved on.
         */
        @Test
        void returns_the_original_decision_unchanged() {
            String reference = newReference();
            String customer = UUID.randomUUID().toString();

            var first = given().contentType(ContentType.JSON)
                    .body(body(reference, customer, 10_000, "Jens Hansen"))
                    .when().post("/risk/screenings")
                    .then().statusCode(200).extract().jsonPath();

            // Use up the customer's day, so a re-evaluation would now block.
            given().contentType(ContentType.JSON)
                    .body(body(newReference(), customer, 990_000, "Jens Hansen"))
                    .when().post("/risk/screenings")
                    .then().statusCode(200);

            given().contentType(ContentType.JSON)
                    .body(body(reference, customer, 10_000, "Jens Hansen"))
                    .when().post("/risk/screenings")
                    .then()
                    .statusCode(200)
                    .body("outcome", is(first.getString("outcome")))
                    .body("reason", is(first.getString("reason")))
                    .body("screenedAt", is(first.getString("screenedAt")));
        }

        /** One row, not two, however many times it is asked. */
        @Test
        void writes_only_one_row() {
            String reference = newReference();
            String customer = UUID.randomUUID().toString();

            for (int attempt = 0; attempt < 3; attempt++) {
                given().contentType(ContentType.JSON)
                        .body(body(reference, customer, 10_000, "Jens Hansen"))
                        .when().post("/risk/screenings")
                        .then().statusCode(200);
            }

            assertThat(rowsFor(reference)).isEqualTo(1L);
        }


    }

    @Nested
    @DisplayName("looking a decision up")
    class Lookup {

        @Test
        void returns_a_decision_that_was_already_made() {
            String reference = newReference();

            given().contentType(ContentType.JSON)
                    .body(body(reference, UUID.randomUUID().toString(), 10_000, "Jens Hansen"))
                    .when().post("/risk/screenings")
                    .then().statusCode(200);

            given().when().get("/risk/screenings/" + reference)
                    .then()
                    .statusCode(200)
                    .body("reference", is(reference))
                    .body("outcome", is("ALLOW"));
        }

        /** Asking must not be a way to cause a screening. */
        @Test
        void does_not_create_one_that_does_not_exist() {
            String reference = newReference();

            given().when().get("/risk/screenings/" + reference)
                    .then()
                    .statusCode(404)
                    .body("error", is("unknown-screening"));

        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        /** Well formed, but the currency is not one that exists. */
        @Test
        void answers_422_for_an_unknown_currency() {
            given().contentType(ContentType.JSON)
                    .body(Map.of(
                            "reference", newReference(),
                            "customerId", UUID.randomUUID().toString(),
                            "amountMinorUnits", 10_000,
                            "currency", "XYZ",
                            "beneficiaryName", "Jens Hansen"))
                    .when().post("/risk/screenings")
                    .then()
                    .statusCode(422)
                    .body("error", is("domain-rule-violated"));
        }

        /** Malformed rather than unactionable, so 400 rather than 422. */
        @Test
        void answers_400_for_a_missing_reference() {
            given().contentType(ContentType.JSON)
                    .body(Map.of(
                            "customerId", UUID.randomUUID().toString(),
                            "amountMinorUnits", 10_000,
                            "currency", "EUR",
                            "beneficiaryName", "Jens Hansen"))
                    .when().post("/risk/screenings")
                    .then()
                    .statusCode(400);
        }

        @Test
        void answers_400_for_a_non_positive_amount() {
            given().contentType(ContentType.JSON)
                    .body(body(newReference(), UUID.randomUUID().toString(), 0, "Jens Hansen"))
                    .when().post("/risk/screenings")
                    .then()
                    .statusCode(400);
        }
    }

    @Nested
    @DisplayName("the seeded sanctions list")
    class SeededData {

        /** The migration is what puts these here; an empty list would allow everything. */
        @Test
        void is_present_after_migration() {
            assertThat(sanctionedPartyCount()).isGreaterThanOrEqualTo(3L);
        }


    }

    // ---------------------------------------------------------------- reading the database back
    //
    // On the outer class, and wrapped with QuarkusTransaction rather than annotated.
    // @Transactional is an interceptor binding, and interceptors are not applied to methods
    // declared on a @Nested inner class — the annotation would be silently ignored and the
    // EntityManager would fail for want of a transaction.

    private String storedOutcome(String reference) {
        return QuarkusTransaction.requiringNew().call(() -> (String) entityManager
                .createNativeQuery("select outcome from screening_decision where reference = :ref")
                .setParameter("ref", reference)
                .getSingleResult());
    }

    private long rowsFor(String reference) {
        return QuarkusTransaction.requiringNew().call(() -> (long) entityManager
                .createNativeQuery("select count(*) from screening_decision where reference = :ref")
                .setParameter("ref", reference)
                .getSingleResult());
    }

    private long sanctionedPartyCount() {
        return QuarkusTransaction.requiringNew().call(() -> (long) entityManager
                .createNativeQuery("select count(*) from sanctioned_party")
                .getSingleResult());
    }

    @Test
    @DisplayName("the service reports itself ready")
    void health_is_up() {
        given().when().get("/health/ready").then().statusCode(200).body("status", equalTo("UP"));
    }
}
