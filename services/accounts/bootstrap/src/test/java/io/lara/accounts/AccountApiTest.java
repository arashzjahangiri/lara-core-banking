package io.lara.accounts;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.lara.accounts.infrastructure.messaging.LedgerPostingConsumer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class AccountApiTest {

    @Inject
    LedgerPostingConsumer consumer;

    private static String registerCustomer(String name) {
        return given().contentType(ContentType.JSON).body("""
                {"name": "%s"}
                """.formatted(name))
                .when().post("/accounts-api/customers")
                .then().statusCode(201)
                .extract().path("id");
    }

    private static void verify(String customerId) {
        given().when().post("/accounts-api/customers/{id}/verification", customerId)
                .then().statusCode(200).body("kyc", equalTo("VERIFIED"));
    }

    private static String openAccount(String customerId, String ledgerAccountId) {
        return given().contentType(ContentType.JSON).body("""
                {"customerId": "%s", "currency": "EUR", "ledgerAccountId": "%s"}
                """.formatted(customerId, ledgerAccountId))
                .when().post("/accounts-api/accounts")
                .then().statusCode(201)
                .extract().path("iban");
    }

    private static String ledgerAccount() {
        return "LEDGER" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    @Nested
    @DisplayName("opening an account")
    class Opening {

        @Test
        void registers_a_customer_opens_an_account_and_activates_it() {
            String customerId = registerCustomer("Arash Zand Jahangiri");
            verify(customerId);

            String iban = openAccount(customerId, ledgerAccount());

            given().when().get("/accounts-api/accounts/{iban}", iban)
                    .then().statusCode(200)
                    .body("status", equalTo("OPENING"))
                    .body("permitsMovement", equalTo(false))
                    .body("ibanFormatted", notNullValue());

            given().when().post("/accounts-api/accounts/{iban}/activation", iban)
                    .then().statusCode(200)
                    .body("status", equalTo("OPEN"))
                    .body("permitsMovement", equalTo(true));
        }

        /** The generated IBAN must pass the same MOD-97 check as one arriving from outside. */
        @Test
        void the_generated_iban_is_valid() {
            String customerId = registerCustomer("A Customer");
            verify(customerId);

            String iban = openAccount(customerId, ledgerAccount());

            assertThat(io.lara.accounts.domain.Iban.of(iban).value()).isEqualTo(iban);
            assertThat(iban).startsWith("DK").hasSize(18);
        }

        @Test
        void an_unverified_customer_cannot_have_a_usable_account() {
            String customerId = registerCustomer("Unverified Person");

            given().contentType(ContentType.JSON).body("""
                    {"customerId": "%s", "currency": "EUR", "ledgerAccountId": "%s"}
                    """.formatted(customerId, ledgerAccount()))
                    .when().post("/accounts-api/accounts")
                    .then().statusCode(422)
                    .body("error", equalTo("customer-not-verified"));
        }

        /** Two accounts pointing at one ledger account would both claim the same money. */
        @Test
        void a_ledger_account_cannot_be_claimed_twice() {
            String customerId = registerCustomer("A Customer");
            verify(customerId);
            String ledgerAccountId = ledgerAccount();
            openAccount(customerId, ledgerAccountId);

            given().contentType(ContentType.JSON).body("""
                    {"customerId": "%s", "currency": "EUR", "ledgerAccountId": "%s"}
                    """.formatted(customerId, ledgerAccountId))
                    .when().post("/accounts-api/accounts")
                    .then().statusCode(409)
                    .body("error", equalTo("ledger-account-taken"));
        }

        @Test
        void an_unknown_customer_is_404() {
            given().contentType(ContentType.JSON).body("""
                    {"customerId": "%s", "currency": "EUR", "ledgerAccountId": "%s"}
                    """.formatted(UUID.randomUUID(), ledgerAccount()))
                    .when().post("/accounts-api/accounts")
                    .then().statusCode(404)
                    .body("error", equalTo("unknown-customer"));
        }

        @Test
        void a_malformed_request_is_400() {
            given().contentType(ContentType.JSON).body("""
                    {"customerId": "", "currency": "EURO", "ledgerAccountId": ""}
                    """)
                    .when().post("/accounts-api/accounts")
                    .then().statusCode(400);
        }

        @Test
        void an_invalid_iban_in_the_path_is_422() {
            given().when().get("/accounts-api/accounts/{iban}", "DK5000400440116244")
                    .then().statusCode(422)
                    .body("error", equalTo("domain-rule-violated"));
        }
    }

    @Nested
    @DisplayName("listing")
    class Listing {

        @Test
        void lists_every_account_a_customer_holds() {
            String customerId = registerCustomer("Multi Account Holder");
            verify(customerId);
            openAccount(customerId, ledgerAccount());
            openAccount(customerId, ledgerAccount());

            given().when().get("/accounts-api/customers/{id}/accounts", customerId)
                    .then().statusCode(200)
                    .body("size()", equalTo(2));
        }

        @Test
        void an_unknown_customer_has_no_list() {
            given().when().get("/accounts-api/customers/{id}/accounts", UUID.randomUUID().toString())
                    .then().statusCode(404);
        }
    }

    @Nested
    @DisplayName("balances")
    class Balances {

        /** A new account has no projected row yet. That is a zero balance, not an error. */
        @Test
        void a_new_account_reads_as_zero_rather_than_missing() {
            String customerId = registerCustomer("New Holder");
            verify(customerId);
            String iban = openAccount(customerId, ledgerAccount());

            given().when().get("/accounts-api/accounts/{iban}/balance", iban)
                    .then().statusCode(200)
                    .body("minorUnits", equalTo(0))
                    .body("overdrawn", equalTo(false))
                    .body("currency", equalTo("EUR"));
        }

        /** Served from the projection, fed by the ledger's events — never by calling the ledger. */
        @Test
        void reflects_postings_the_ledger_published() {
            String customerId = registerCustomer("Active Holder");
            verify(customerId);
            String ledgerAccountId = ledgerAccount();
            String iban = openAccount(customerId, ledgerAccountId);

            consumer.onPosting("""
                    {
                      "transactionId": "%s", "reference": "REF-1",
                      "occurredAt": "2026-10-01T12:00:00Z",
                      "account": "%s", "currency": "EUR",
                      "legs": [{"side": "CREDIT", "amountMinorUnits": 125000, "currency": "EUR"}]
                    }
                    """.formatted(UUID.randomUUID(), ledgerAccountId));

            given().when().get("/accounts-api/accounts/{iban}/balance", iban)
                    .then().statusCode(200)
                    .body("minorUnits", equalTo(125000))
                    .body("amount", equalTo(1250.00f))
                    .body("lastTransactionId", notNullValue());
        }

        /**
         * The response states how old the figure is. A client that cannot see the age of an
         * asynchronously-fed number will assume it is live — wrong exactly when it matters.
         */
        @Test
        void states_how_stale_the_figure_is() {
            String customerId = registerCustomer("Staleness Holder");
            verify(customerId);
            String ledgerAccountId = ledgerAccount();
            String iban = openAccount(customerId, ledgerAccountId);

            consumer.onPosting("""
                    {
                      "transactionId": "%s", "reference": "REF-2",
                      "occurredAt": "2026-10-01T12:00:00Z",
                      "account": "%s", "currency": "EUR",
                      "legs": [{"side": "CREDIT", "amountMinorUnits": 100, "currency": "EUR"}]
                    }
                    """.formatted(UUID.randomUUID(), ledgerAccountId));

            given().when().get("/accounts-api/accounts/{iban}/balance", iban)
                    .then().statusCode(200)
                    .body("asOf", notNullValue())
                    .body("staleSeconds", greaterThanOrEqualTo(0));
        }

        @Test
        void an_overdrawn_account_says_so() {
            String customerId = registerCustomer("Overdrawn Holder");
            verify(customerId);
            String ledgerAccountId = ledgerAccount();
            String iban = openAccount(customerId, ledgerAccountId);

            consumer.onPosting("""
                    {
                      "transactionId": "%s", "reference": "REF-3",
                      "occurredAt": "2026-10-01T12:00:00Z",
                      "account": "%s", "currency": "EUR",
                      "legs": [{"side": "DEBIT", "amountMinorUnits": 2500, "currency": "EUR"}]
                    }
                    """.formatted(UUID.randomUUID(), ledgerAccountId));

            given().when().get("/accounts-api/accounts/{iban}/balance", iban)
                    .then().statusCode(200)
                    .body("minorUnits", equalTo(-2500))
                    .body("overdrawn", equalTo(true));
        }
    }
}
