package io.lara.ledger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * The hash chain is only worth having if tampering actually breaks it, so these tests edit the
 * database directly — behind the application, the way someone covering their tracks would — and
 * assert the verifier notices.
 *
 * <p>Ordered, because tampering is destructive: once a row is altered the chain stays broken for
 * every later check, so the intact case has to run first.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChainIntegrityTest {

    @Inject
    javax.sql.DataSource dataSource;

    private static final String CASH = "BANK.CASH.EUR";
    private static final String SENDER = "CUSTOMER000001";

    private static void postTransfer(long minorUnits) {
        String body = """
                {
                  "reference": "CHAIN-%s",
                  "legs": [
                    {"account": "%s", "side": "DEBIT",  "amountMinorUnits": %d, "currency": "EUR"},
                    {"account": "%s", "side": "CREDIT", "amountMinorUnits": %d, "currency": "EUR"}
                  ]
                }
                """.formatted(UUID.randomUUID(), CASH, minorUnits, SENDER, minorUnits);

        given().contentType(ContentType.JSON).body(body)
                .when().post("/ledger/transactions").then().statusCode(201);
    }

    private int executeDirectly(String sql) throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    @Test
    @Order(1)
    @DisplayName("an untouched ledger verifies clean")
    void an_untouched_ledger_verifies_clean() {
        postTransfer(10_000);
        postTransfer(25_000);
        postTransfer(500);

        given().when().get("/ledger/integrity")
                .then().statusCode(200)
                .body("intact", equalTo(true))
                .body("transactionsChecked", greaterThan(2))
                .body("firstBreak", equalTo(null));
    }

    @Test
    @Order(2)
    @DisplayName("every transaction is chained, none left unhashed")
    void every_transaction_carries_a_hash_and_a_predecessor() throws Exception {
        postTransfer(777);

        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("""
                        select count(*) from ledger_transaction
                        where content_hash is null or previous_hash is null or chain_sequence is null
                        """)) {
            rows.next();
            assertThat(rows.getLong(1)).as("transactions missing chain data").isZero();
        }
    }

    @Nested
    @DisplayName("tampering")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Tampering {

        /**
         * The scenario the chain exists for: someone with database access quietly changes an
         * amount. The append-only trigger blocks it through normal paths, so this disables the
         * trigger first — exactly what an attacker able to rewrite rows would do.
         */
        @Test
        @Order(1)
        void changing_an_amount_behind_the_application_is_detected() throws Exception {
            postTransfer(31_337);

            given().when().get("/ledger/integrity").then().body("intact", equalTo(true));

            // Disable the guard, alter a posting, put the guard back. The row now looks ordinary.
            executeDirectly("ALTER TABLE posting_leg DISABLE TRIGGER posting_leg_append_only");
            int altered = executeDirectly("UPDATE posting_leg SET amount_minor = 1 WHERE amount_minor = 31337");
            executeDirectly("ALTER TABLE posting_leg ENABLE TRIGGER posting_leg_append_only");

            assertThat(altered).as("the tampering itself succeeded").isPositive();

            given().when().get("/ledger/integrity")
                    .then().statusCode(200)
                    .body("intact", equalTo(false))
                    .body("firstBreak.reason", equalTo("CONTENT_ALTERED"))
                    .body("firstBreak.sequence", greaterThan(0));
        }

        /**
         * Everything after the break fails too, because each hash depends on the one before — so
         * a tamperer cannot repair the chain by fixing only the row they touched.
         */
        @Test
        @Order(2)
        void the_break_is_reported_at_its_first_occurrence_not_the_last() throws Exception {
            // The ledger is already broken by the previous test. Add more transactions on top.
            postTransfer(111);
            postTransfer(222);

            long reportedAt = given().when().get("/ledger/integrity")
                    .then().statusCode(200)
                    .body("intact", equalTo(false))
                    .extract().jsonPath().getLong("firstBreak.sequence");

            long total;
            try (var connection = dataSource.getConnection();
                    var statement = connection.createStatement();
                    var rows = statement.executeQuery("select max(chain_sequence) from ledger_transaction")) {
                rows.next();
                total = rows.getLong(1);
            }

            assertThat(reportedAt)
                    .as("the first break, not the most recent transaction")
                    .isLessThan(total);
        }
    }
}
