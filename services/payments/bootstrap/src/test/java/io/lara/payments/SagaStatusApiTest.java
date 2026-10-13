package io.lara.payments;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.inject.Inject;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.lara.payments.application.StoredTransfer;
import io.lara.payments.application.Transfers;
import io.lara.payments.domain.Iban;
import io.lara.payments.domain.LedgerTransactionRef;
import io.lara.payments.domain.Money;
import io.lara.payments.domain.RejectionReason;
import io.lara.payments.domain.RoutingDecision;
import io.lara.payments.domain.Transfer;
import io.lara.payments.domain.TransferId;
import io.lara.payments.domain.TransferScheme;

/**
 * Reading a transfer back, against a real PostgreSQL.
 *
 * <p>This is the endpoint ADR-0003 argued orchestration for. Under choreography, answering "where
 * is my transfer" would mean correlating logs across four services; here it is one request, and
 * the history that explains how it got there comes with it.
 *
 * <p>The compensated case gets the most attention, because it is the one a support agent will
 * actually be asked about. A customer whose money left and came back wants to know both of those
 * things happened and in what order, and a response showing only the final state would read as
 * though nothing ever did.
 */
@QuarkusTest
class SagaStatusApiTest {

    @Inject
    Transfers transfers;

    @Inject
    Clock clock;

    private static final Iban DEBTOR = Iban.of("DK5000400440116243");
    private static final Iban OTHER_DEBTOR = Iban.of("DK2800400440116251");
    private static final Iban CREDITOR = Iban.of("DE89370400440532013000");

    private static final RoutingDecision SEPA = new RoutingDecision(
            TransferScheme.SEPA_CREDIT_TRANSFER, Money.of(50, "EUR"), LocalDate.of(2026, 10, 5));

    private Transfer store(Iban debtor, String requestedBy, java.util.function.Consumer<Transfer> moves) {
        Transfer transfer = Transfer.request(
                TransferId.newId(), debtor, CREDITOR, Money.of(125_000, "EUR"), SEPA, requestedBy, clock);
        moves.accept(transfer);
        QuarkusTransaction.requiringNew().run(() -> transfers.add(transfer));
        return transfer;
    }

    /** A transfer that was posted and then reversed when the scheme refused it. */
    private Transfer compensated() {
        return store(DEBTOR, "arash", transfer -> {
            transfer.startScreening();
            transfer.startPosting();
            transfer.posted(LedgerTransactionRef.of(UUID.randomUUID()));
            transfer.startCompensation("scheme rejected the payment: beneficiary account closed");
            transfer.compensated(LedgerTransactionRef.of(UUID.randomUUID()));
        });
    }

    @Nested
    @DisplayName("one transfer")
    class One {

        @Test
        void answers_where_it_is_in_a_single_request() {
            Transfer transfer = store(DEBTOR, "arash", Transfer::startScreening);

            given().when().get("/payments/transfers/" + transfer.id())
                    .then()
                    .statusCode(200)
                    .body("id", is(transfer.id().toString()))
                    .body("status", is("SCREENING"))
                    .body("reference", is(transfer.reference().value()))
                    .body("scheme", is("SEPA_CREDIT_TRANSFER"))
                    .body("valueDate", is("2026-10-05"));
        }

        /**
         * Both numbers, because they answer different questions. The creditor receives the
         * amount; the debtor pays the amount plus the fee. A client shown only the amount
         * under-reports what the payment cost.
         */
        @Test
        void reports_the_amount_the_fee_and_what_the_debtor_actually_paid() {
            Transfer transfer = store(DEBTOR, "arash", t -> { });

            given().when().get("/payments/transfers/" + transfer.id())
                    .then()
                    .body("amountMinorUnits", is(125_000))
                    .body("feeMinorUnits", is(50))
                    .body("totalDebitMinorUnits", is(125_050))
                    .body("currency", is("EUR"));
        }

        @Test
        void answers_404_with_a_typed_problem_body_for_an_unknown_id() {
            given().when().get("/payments/transfers/" + UUID.randomUUID())
                    .then()
                    .statusCode(404)
                    .body("error", is("unknown-transfer"));
        }
    }

    @Nested
    @DisplayName("a compensated transfer")
    class Compensated {

        /**
         * The case the ticket calls out. Both the original posting and the reversal are named,
         * because ADR-0011 reverses by contra entry rather than by deleting anything — the ledger
         * holds two transactions and the honest record names both.
         */
        @Test
        void names_both_the_original_posting_and_the_reversal() {
            Transfer transfer = compensated();

            JsonPath response = given().when().get("/payments/transfers/" + transfer.id())
                    .then().statusCode(200).extract().jsonPath();

            assertThat(response.getString("status")).isEqualTo("COMPENSATED");
            assertThat(response.getString("ledgerTransactionId")).isNotNull();
            assertThat(response.getString("reversalTransactionId")).isNotNull();
            assertThat(response.getString("ledgerTransactionId"))
                    .isNotEqualTo(response.getString("reversalTransactionId"));
        }

        /**
         * And the whole path that produced it. A response showing only {@code COMPENSATED} would
         * read as though nothing ever happened, when in fact the money left and came back.
         */
        @Test
        void shows_the_full_history_in_order() {
            Transfer transfer = compensated();

            given().when().get("/payments/transfers/" + transfer.id())
                    .then()
                    .statusCode(200)
                    .body("history.to", contains(
                            "SCREENING", "POSTING", "POSTED", "COMPENSATING", "COMPENSATED"))
                    .body("history[0].from", is("REQUESTED"))
                    .body("history[0].at", notNullValue())
                    .body("detail", containsString("beneficiary account closed"));
        }

        /** A rejected transfer names no transaction, because there never was one. */
        @Test
        void a_rejection_reports_no_ledger_transaction_at_all() {
            Transfer transfer = store(DEBTOR, "arash", t -> {
                t.startScreening();
                t.reject(RejectionReason.SCREENING_BLOCKED, "sanctions match");
            });

            given().when().get("/payments/transfers/" + transfer.id())
                    .then()
                    .body("status", is("REJECTED"))
                    .body("rejectionReason", is("SCREENING_BLOCKED"))
                    .body("ledgerTransactionId", nullValue())
                    .body("reversalTransactionId", nullValue());
        }
    }

    @Nested
    @DisplayName("listing")
    class Listing {

        @Test
        void filters_by_the_debtor_account() {
            Transfer mine = store(OTHER_DEBTOR, "arash", t -> { });

            JsonPath page = given()
                    .queryParam("debtorIban", OTHER_DEBTOR.value())
                    .when().get("/payments/transfers")
                    .then().statusCode(200).extract().jsonPath();

            assertThat(page.getList("transfers.debtorIban", String.class))
                    .isNotEmpty()
                    .allMatch(iban -> iban.equals(OTHER_DEBTOR.value()));
            assertThat(page.getList("transfers.id", String.class)).contains(mine.id().toString());
        }

        @Test
        void filters_by_status() {
            store(DEBTOR, "arash", t -> {
                t.startScreening();
                t.reject(RejectionReason.UNKNOWN_ACCOUNT, "no such creditor");
            });

            JsonPath page = given()
                    .queryParam("status", "REJECTED")
                    .when().get("/payments/transfers")
                    .then().statusCode(200).extract().jsonPath();

            assertThat(page.getList("transfers.status", String.class))
                    .isNotEmpty()
                    .allMatch("REJECTED"::equals);
        }

        @Test
        void filters_by_who_requested_it() {
            String maker = "maker-" + UUID.randomUUID();
            store(DEBTOR, maker, t -> { });

            given().queryParam("requestedBy", maker)
                    .when().get("/payments/transfers")
                    .then()
                    .statusCode(200)
                    .body("total", is(1))
                    .body("transfers[0].requestedBy", is(maker));
        }

        /** Case should not matter to a human typing a query string. */
        @Test
        void accepts_a_status_in_any_case() {
            given().queryParam("status", "rejected")
                    .when().get("/payments/transfers")
                    .then().statusCode(200);
        }

        /**
         * An unknown status lists the legal ones. {@code valueOf} alone throws a message naming
         * the enum class, which tells a caller nothing about what they should have sent.
         */
        @Test
        void says_which_statuses_exist_when_given_one_that_does_not() {
            given().queryParam("status", "NOT_A_STATUS")
                    .when().get("/payments/transfers")
                    .then()
                    .statusCode(422)
                    .body("error", is("domain-rule-violated"))
                    .body("detail", containsString("COMPENSATED"));
        }

        /** An absent filter means "do not filter", never "match nothing". */
        @Test
        void returns_everything_when_nothing_is_filtered() {
            store(DEBTOR, "arash", t -> { });

            given().when().get("/payments/transfers")
                    .then()
                    .statusCode(200)
                    .body("total", org.hamcrest.Matchers.greaterThan(0));
        }
    }

    @Nested
    @DisplayName("paging")
    class Paging {

        @Test
        void returns_at_most_a_page_and_says_whether_there_is_more() {
            String maker = "pager-" + UUID.randomUUID();
            for (int i = 0; i < 3; i++) {
                store(DEBTOR, maker, t -> { });
            }

            JsonPath first = given()
                    .queryParam("requestedBy", maker)
                    .queryParam("page", 0).queryParam("size", 2)
                    .when().get("/payments/transfers")
                    .then().statusCode(200).extract().jsonPath();

            assertThat(first.getList("transfers")).hasSize(2);
            assertThat(first.getLong("total")).isEqualTo(3L);
            assertThat(first.getBoolean("hasMore")).isTrue();

            JsonPath second = given()
                    .queryParam("requestedBy", maker)
                    .queryParam("page", 1).queryParam("size", 2)
                    .when().get("/payments/transfers")
                    .then().statusCode(200).extract().jsonPath();

            assertThat(second.getList("transfers")).hasSize(1);
            assertThat(second.getBoolean("hasMore")).isFalse();
        }

        /** No page repeats a row from another, which a missing tie-break would allow. */
        @Test
        void never_shows_the_same_transfer_on_two_pages() {
            String maker = "pager-" + UUID.randomUUID();
            for (int i = 0; i < 4; i++) {
                store(DEBTOR, maker, t -> { });
            }

            var firstPage = given().queryParam("requestedBy", maker)
                    .queryParam("page", 0).queryParam("size", 2)
                    .when().get("/payments/transfers")
                    .then().extract().jsonPath().getList("transfers.id", String.class);

            var secondPage = given().queryParam("requestedBy", maker)
                    .queryParam("page", 1).queryParam("size", 2)
                    .when().get("/payments/transfers")
                    .then().extract().jsonPath().getList("transfers.id", String.class);

            assertThat(firstPage).doesNotContainAnyElementsOf(secondPage);
        }

        /**
         * An unbounded list endpoint is fine until one account has fifty thousand transfers, and
         * then it is an outage.
         */
        @Test
        void refuses_a_page_size_beyond_the_cap() {
            given().queryParam("size", 5_000)
                    .when().get("/payments/transfers")
                    .then()
                    .statusCode(422)
                    .body("detail", containsString("at most 200"));
        }

        @Test
        void refuses_a_negative_page() {
            given().queryParam("page", -1)
                    .when().get("/payments/transfers")
                    .then().statusCode(422);
        }
    }

    @Nested
    @DisplayName("reading does not change anything")
    class SideEffectFree {

        /** A support agent asking where a transfer is must not be able to move it. */
        @Test
        void a_get_leaves_the_saga_exactly_where_it_was() {
            Transfer transfer = store(DEBTOR, "arash", Transfer::startScreening);

            given().when().get("/payments/transfers/" + transfer.id()).then().statusCode(200);
            given().when().get("/payments/transfers/" + transfer.id()).then().statusCode(200);

            StoredTransfer stored = QuarkusTransaction.requiringNew()
                    .call(() -> transfers.find(transfer.id())).orElseThrow();

            assertThat(stored.transfer().status())
                    .isEqualTo(io.lara.payments.domain.TransferStatus.SCREENING);
            assertThat(stored.version()).isZero();
        }
    }
}
