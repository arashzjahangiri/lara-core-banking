package io.lara.ledger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * End to end against a real PostgreSQL, started automatically by Dev Services. No in-memory
 * database pretending to be Postgres: the triggers, check constraints and unique index in the
 * migrations only exist in the real thing, and they are half the behaviour being tested.
 */
@QuarkusTest
class LedgerApiTest {

    @Inject
    EntityManager entityManager;

    @Inject
    javax.sql.DataSource dataSource;

    private static final String SENDER = "CUSTOMER000001";
    private static final String RECEIVER = "CUSTOMER000002";
    private static final String CASH = "BANK.CASH.EUR";
    private static final String FEE_INCOME = "BANK.FEE.INCOME.EUR";

    private static String reference() {
        return "IT-" + UUID.randomUUID();
    }

    private static String transfer(String reference, String from, String to, long minorUnits) {
        return """
                {
                  "reference": "%s",
                  "legs": [
                    {"account": "%s", "side": "DEBIT",  "amountMinorUnits": %d, "currency": "EUR"},
                    {"account": "%s", "side": "CREDIT", "amountMinorUnits": %d, "currency": "EUR"}
                  ]
                }
                """.formatted(reference, from, minorUnits, to, minorUnits);
    }

    @Nested
    @DisplayName("the seeded chart of accounts")
    class Seed {

        /** The seed migration and the SystemAccount enum must not drift apart. */
        @Test
        void contains_every_system_account_for_every_seeded_currency() {
            for (io.lara.ledger.domain.SystemAccount account : io.lara.ledger.domain.SystemAccount.values()) {
                for (String currency : new String[] { "EUR", "USD" }) {
                    String id = account.idIn(java.util.Currency.getInstance(currency)).value();
                    Long found = entityManager
                            .createQuery("select count(a) from LedgerAccountEntity a where a.id = :id", Long.class)
                            .setParameter("id", id)
                            .getSingleResult();
                    assertThat(found).as("seeded account %s", id).isEqualTo(1L);
                }
            }
        }
    }

    @Nested
    @DisplayName("posting and reading back")
    class RoundTrip {

        @Test
        void records_a_transfer_and_returns_it_by_id() {
            String reference = reference();

            String id = given().contentType(ContentType.JSON)
                    .body(transfer(reference, CASH, SENDER, 50_000))
                    .when().post("/ledger/transactions")
                    .then().statusCode(201)
                    .body("reference", equalTo(reference))
                    .body("legs", hasSize(2))
                    .body("totalMinorUnits", equalTo(50_000))
                    .extract().path("id");

            given().when().get("/ledger/transactions/{id}", id)
                    .then().statusCode(200)
                    .body("id", equalTo(id))
                    .body("reference", equalTo(reference));
        }

        @Test
        void finds_a_transaction_by_the_caller_own_reference() {
            String reference = reference();
            given().contentType(ContentType.JSON).body(transfer(reference, CASH, SENDER, 1_000))
                    .when().post("/ledger/transactions").then().statusCode(201);

            given().queryParam("reference", reference)
                    .when().get("/ledger/transactions")
                    .then().statusCode(200)
                    .body("reference", equalTo(reference));
        }

        @Test
        void returns_404_for_a_transaction_that_does_not_exist() {
            given().when().get("/ledger/transactions/{id}", UUID.randomUUID().toString())
                    .then().statusCode(404);
        }

        /** Money crosses the wire as minor units and comes back exactly, with no decimal rounding. */
        @Test
        void renders_the_amount_without_losing_a_minor_unit() {
            given().contentType(ContentType.JSON).body(transfer(reference(), CASH, SENDER, 5))
                    .when().post("/ledger/transactions")
                    .then().statusCode(201)
                    .body("legs[0].amountMinorUnits", equalTo(5))
                    .body("legs[0].amount", equalTo("0.05"));
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        /** 201 the first time, 200 on a retry. Both successes; the money moved once. */
        @Test
        void a_retry_returns_200_and_the_same_transaction() {
            String reference = reference();
            String body = transfer(reference, CASH, SENDER, 2_500);

            String first = given().contentType(ContentType.JSON).body(body)
                    .when().post("/ledger/transactions")
                    .then().statusCode(201).extract().path("id");

            String retry = given().contentType(ContentType.JSON).body(body)
                    .when().post("/ledger/transactions")
                    .then().statusCode(200).extract().path("id");

            assertThat(retry).isEqualTo(first);
        }

        @Test
        void the_same_reference_with_different_legs_is_a_conflict() {
            String reference = reference();
            given().contentType(ContentType.JSON).body(transfer(reference, CASH, SENDER, 2_500))
                    .when().post("/ledger/transactions").then().statusCode(201);

            given().contentType(ContentType.JSON).body(transfer(reference, CASH, SENDER, 9_999))
                    .when().post("/ledger/transactions")
                    .then().statusCode(409)
                    .body("error", equalTo("reference-reused"));
        }
    }

    @Nested
    @DisplayName("rejection")
    class Rejection {

        @Test
        void an_unbalanced_transaction_is_422() {
            String body = """
                    {
                      "reference": "%s",
                      "legs": [
                        {"account": "%s", "side": "DEBIT",  "amountMinorUnits": 10000, "currency": "EUR"},
                        {"account": "%s", "side": "CREDIT", "amountMinorUnits": 9950,  "currency": "EUR"}
                      ]
                    }
                    """.formatted(reference(), CASH, SENDER);

            given().contentType(ContentType.JSON).body(body)
                    .when().post("/ledger/transactions")
                    .then().statusCode(422)
                    .body("error", equalTo("domain-rule-violated"));
        }

        @Test
        void an_unknown_account_is_422() {
            given().contentType(ContentType.JSON)
                    .body(transfer(reference(), CASH, "CUSTOMER999999", 100))
                    .when().post("/ledger/transactions")
                    .then().statusCode(422)
                    .body("error", equalTo("unknown-account"));
        }

        @Test
        void a_malformed_body_is_400_not_422() {
            String body = """
                    {"reference": "", "legs": []}
                    """;

            given().contentType(ContentType.JSON).body(body)
                    .when().post("/ledger/transactions")
                    .then().statusCode(400);
        }

        @Test
        void a_negative_amount_is_rejected_at_the_boundary() {
            String body = """
                    {
                      "reference": "%s",
                      "legs": [
                        {"account": "%s", "side": "DEBIT",  "amountMinorUnits": -1, "currency": "EUR"},
                        {"account": "%s", "side": "CREDIT", "amountMinorUnits": -1, "currency": "EUR"}
                      ]
                    }
                    """.formatted(reference(), CASH, SENDER);

            given().contentType(ContentType.JSON).body(body)
                    .when().post("/ledger/transactions")
                    .then().statusCode(400);
        }
    }

    @Nested
    @DisplayName("balances")
    class Balances {

        @Test
        void a_credit_raises_a_liability_and_a_debit_raises_an_asset() {
            long before = balanceOf(SENDER);
            long cashBefore = balanceOf(CASH);

            given().contentType(ContentType.JSON).body(transfer(reference(), CASH, SENDER, 7_500))
                    .when().post("/ledger/transactions").then().statusCode(201);

            assertThat(balanceOf(SENDER)).isEqualTo(before + 7_500);
            assertThat(balanceOf(CASH)).isEqualTo(cashBefore + 7_500);
        }

        @Test
        void reports_the_account_class_and_the_side_it_sits_on() {
            given().when().get("/ledger/accounts/{id}/balance", CASH)
                    .then().statusCode(200)
                    .body("accountClass", equalTo("ASSET"))
                    .body("side", equalTo("DEBIT"))
                    .body("currency", equalTo("EUR"));
        }

        @Test
        void an_unknown_account_has_no_balance() {
            given().when().get("/ledger/accounts/{id}/balance", "CUSTOMER999999")
                    .then().statusCode(422)
                    .body("error", equalTo("unknown-account"));
        }

        private static long balanceOf(String account) {
            return given().when().get("/ledger/accounts/{id}/balance", account)
                    .then().statusCode(200)
                    .extract().jsonPath().getLong("minorUnits");
        }
    }

    @Nested
    @DisplayName("the append-only guarantee")
    class AppendOnly {

        /**
         * Enforced by a database trigger, not by the application avoiding it. Without this, one
         * careless UPDATE rewrites history and the audit trail silently becomes fiction.
         */
        @Test
        void the_database_refuses_to_update_a_posting() {
            given().contentType(ContentType.JSON).body(transfer(reference(), CASH, SENDER, 100))
                    .when().post("/ledger/transactions").then().statusCode(201);

            assertThat(mutationFails("update posting_leg set amount_minor = 1"))
                    .contains("append-only");
        }

        @Test
        void the_database_refuses_to_delete_a_transaction() {
            given().contentType(ContentType.JSON).body(transfer(reference(), CASH, SENDER, 100))
                    .when().post("/ledger/transactions").then().statusCode(201);

            assertThat(mutationFails("delete from ledger_transaction"))
                    .contains("append-only");
        }

        /**
         * Straight JDBC, deliberately. Going through Hibernate would prove only that the mapping
         * refuses; this proves the <em>database</em> refuses, which is what protects the ledger from
         * anything holding a connection — a migration, a console session, another service.
         */
        private String mutationFails(String sql) {
            try (var connection = dataSource.getConnection();
                    var statement = connection.createStatement()) {
                statement.executeUpdate(sql);
                return "no failure: the mutation was allowed";
            } catch (java.sql.SQLException expected) {
                return expected.getMessage();
            }
        }
    }

    @Nested
    @DisplayName("the trial balance")
    class TrialBalance {

        /**
         * The strongest statement this system can make. Sum every debit and every credit across the
         * whole database; if they ever differ, money has been created or destroyed.
         */
        @Test
        void total_debits_equal_total_credits_across_the_whole_database() {
            given().contentType(ContentType.JSON).body(transfer(reference(), CASH, SENDER, 50_000))
                    .when().post("/ledger/transactions").then().statusCode(201);

            String withFee = """
                    {
                      "reference": "%s",
                      "legs": [
                        {"account": "%s", "side": "DEBIT",  "amountMinorUnits": 10000, "currency": "EUR"},
                        {"account": "%s", "side": "CREDIT", "amountMinorUnits": 9950,  "currency": "EUR"},
                        {"account": "%s", "side": "CREDIT", "amountMinorUnits": 50,    "currency": "EUR"}
                      ]
                    }
                    """.formatted(reference(), SENDER, RECEIVER, FEE_INCOME);
            given().contentType(ContentType.JSON).body(withFee)
                    .when().post("/ledger/transactions").then().statusCode(201);

            Number net = (Number) entityManager.createNativeQuery("""
                    select coalesce(sum(case when side = 'DEBIT' then amount_minor else -amount_minor end), 0)
                    from posting_leg
                    """).getSingleResult();

            assertThat(net.longValue()).as("every debit has a matching credit").isZero();
        }

        @Test
        void every_recorded_transaction_balances_on_its_own() {
            given().contentType(ContentType.JSON).body(transfer(reference(), CASH, SENDER, 1_234))
                    .when().post("/ledger/transactions").then().statusCode(201);

            Number unbalanced = (Number) entityManager.createNativeQuery("""
                    select count(*) from (
                      select transaction_id,
                             sum(case when side = 'DEBIT' then amount_minor else -amount_minor end) as net
                      from posting_leg group by transaction_id
                    ) per_transaction where net <> 0
                    """).getSingleResult();

            assertThat(unbalanced.longValue()).as("transactions that do not balance").isZero();
        }
    }
}
