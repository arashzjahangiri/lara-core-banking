package io.lara.payments;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

import jakarta.inject.Inject;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.lara.payments.application.StoredTransfer;
import io.lara.payments.application.Transfers;
import io.lara.payments.domain.Iban;
import io.lara.payments.domain.Money;
import io.lara.payments.domain.RoutingDecision;
import io.lara.payments.domain.Transfer;
import io.lara.payments.domain.TransferId;
import io.lara.payments.domain.TransferScheme;
import io.lara.payments.domain.TransferStatus;

/**
 * The approval endpoints, against a real PostgreSQL.
 *
 * <p>Transfers are parked directly rather than driven there through the saga, because the saga's
 * dependencies are not running here and the thing under test is the approval, not the route to
 * it. What matters is a row in {@code APPROVAL_PENDING} and what the two endpoints do with it.
 *
 * <p>The self-approval case is the one worth having at this level as well as in the domain. The
 * rule is enforced in the aggregate, but a reader wants to know it survives the HTTP layer and
 * comes back as 403 rather than a 500 or, worse, a silent success.
 */
@QuarkusTest
class ApprovalApiTest {

    @Inject
    Transfers transfers;

    @Inject
    Clock clock;

    private static final Iban DEBTOR = Iban.of("DK5000400440116243");
    private static final Iban CREDITOR = Iban.of("DE89370400440532013000");
    private static final String MAKER = "arash";

    private static final RoutingDecision SEPA = new RoutingDecision(
            TransferScheme.SEPA_CREDIT_TRANSFER, Money.of(50, "EUR"), LocalDate.of(2026, 10, 5));

    /** Persists a transfer already parked for a second pair of eyes. */
    private TransferId parked() {
        Transfer transfer = Transfer.request(
                TransferId.newId(), DEBTOR, CREDITOR, Money.of(2_000_000, "EUR"), SEPA, MAKER, clock);

        QuarkusTransaction.requiringNew().run(() -> transfers.add(transfer));

        StoredTransfer stored = load(transfer.id());
        stored.transfer().startScreening();
        stored.transfer().awaitApproval("over the four-eyes threshold");
        QuarkusTransaction.requiringNew().run(() -> transfers.update(stored));

        return transfer.id();
    }

    private StoredTransfer load(TransferId id) {
        return QuarkusTransaction.requiringNew().call(() -> transfers.find(id)).orElseThrow();
    }

    private static Map<String, String> decision(String actor, String reason) {
        return reason == null ? Map.of("actor", actor) : Map.of("actor", actor, "reason", reason);
    }

    @Nested
    @DisplayName("approving")
    class Approving {

        /**
         * The maker is refused at the HTTP layer too, with 403 rather than 422: the request was
         * understood perfectly and the caller is not allowed to make it. A checker who sees 422
         * goes looking for a malformed field, which is the wrong place entirely.
         */
        @Test
        void refuses_the_person_who_requested_it() {
            TransferId id = parked();

            given().contentType(ContentType.JSON)
                    .body(decision(MAKER, null))
                    .when().post("/payments/transfers/" + id + "/approval")
                    .then()
                    .statusCode(403)
                    .body("error", is("four-eyes-violation"))
                    .body("detail", containsString(MAKER));

            assertThat(load(id).transfer().status()).isEqualTo(TransferStatus.APPROVAL_PENDING);
        }

        @Test
        void is_not_defeated_by_different_casing() {
            TransferId id = parked();

            given().contentType(ContentType.JSON)
                    .body(decision("ARASH", null))
                    .when().post("/payments/transfers/" + id + "/approval")
                    .then().statusCode(403);
        }

        /**
         * A different person unparks it. The saga then tries to post and cannot, because the
         * ledger is not running here — so the transfer stays in {@code POSTING}, which is
         * correct and recoverable. The approval itself committed regardless, which is the point
         * of doing it in its own transaction.
         */
        @Test
        void lets_a_second_person_unpark_it() {
            TransferId id = parked();

            given().contentType(ContentType.JSON)
                    .body(decision("mette", null))
                    .when().post("/payments/transfers/" + id + "/approval")
                    .then().statusCode(anyOf503Or200());

            assertThat(load(id).transfer().status()).isNotEqualTo(TransferStatus.APPROVAL_PENDING);
        }

        @Test
        void records_who_approved() {
            TransferId id = parked();

            given().contentType(ContentType.JSON)
                    .body(decision("mette", null))
                    .when().post("/payments/transfers/" + id + "/approval")
                    .then().statusCode(anyOf503Or200());

            assertThat(load(id).transfer().history())
                    .anySatisfy(transition ->
                            assertThat(transition.note()).isEqualTo("approved by mette"));
        }

        @Test
        void requires_an_actor() {
            TransferId id = parked();

            given().contentType(ContentType.JSON)
                    .body(Map.of("reason", "looks fine"))
                    .when().post("/payments/transfers/" + id + "/approval")
                    .then().statusCode(400);
        }
    }

    @Nested
    @DisplayName("declining")
    class Declining {

        @Test
        void ends_the_transfer_rejected_with_no_money_moved() {
            TransferId id = parked();

            given().contentType(ContentType.JSON)
                    .body(decision("mette", "beneficiary not recognised"))
                    .when().post("/payments/transfers/" + id + "/decline")
                    .then()
                    .statusCode(200)
                    .body("status", is("REJECTED"))
                    .body("rejectionReason", is("APPROVAL_REFUSED"))
                    .body("detail", containsString("beneficiary not recognised"))
                    .body("ledgerTransactionId", org.hamcrest.Matchers.nullValue());

            assertThat(load(id).transfer().ledgerTransaction()).isEmpty();
        }

        @Test
        void refuses_the_person_who_requested_it() {
            TransferId id = parked();

            given().contentType(ContentType.JSON)
                    .body(decision(MAKER, "changed my mind"))
                    .when().post("/payments/transfers/" + id + "/decline")
                    .then().statusCode(403);

            assertThat(load(id).transfer().status()).isEqualTo(TransferStatus.APPROVAL_PENDING);
        }

        /** A reason is optional on the wire, so a decline never fails for want of prose. */
        @Test
        void works_without_a_reason() {
            TransferId id = parked();

            given().contentType(ContentType.JSON)
                    .body(decision("mette", null))
                    .when().post("/payments/transfers/" + id + "/decline")
                    .then()
                    .statusCode(200)
                    .body("detail", containsString("no reason given"));
        }
    }

    @Nested
    @DisplayName("deciding twice")
    class StaleScreen {

        /**
         * Two checkers open the same queue, one acts, the other clicks a minute later. 409 with
         * the state the transfer is actually in, so the second checker can work out what
         * happened without calling anyone.
         */
        @Test
        void answers_409_naming_the_state_it_is_actually_in() {
            TransferId id = parked();

            given().contentType(ContentType.JSON)
                    .body(decision("mette", "no"))
                    .when().post("/payments/transfers/" + id + "/decline")
                    .then().statusCode(200);

            given().contentType(ContentType.JSON)
                    .body(decision("jens", "also no"))
                    .when().post("/payments/transfers/" + id + "/decline")
                    .then()
                    .statusCode(409)
                    .body("error", is("not-awaiting-approval"))
                    .body("detail", containsString("REJECTED"));
        }

        @Test
        void refuses_to_approve_something_that_was_never_parked() {
            Transfer fresh = Transfer.request(
                    TransferId.newId(), DEBTOR, CREDITOR, Money.of(100, "EUR"), SEPA, MAKER, clock);
            QuarkusTransaction.requiringNew().run(() -> transfers.add(fresh));

            given().contentType(ContentType.JSON)
                    .body(decision("mette", null))
                    .when().post("/payments/transfers/" + fresh.id() + "/approval")
                    .then()
                    .statusCode(409)
                    .body("detail", containsString("REQUESTED"));
        }
    }

    /**
     * Approving drives the saga, which cannot reach the ledger in this test — so the response is
     * 503. If the dependencies were up it would be 200. Either is a pass here, because what is
     * being asserted is the approval, and the approval committed before the saga ran.
     */
    private static org.hamcrest.Matcher<Integer> anyOf503Or200() {
        return org.hamcrest.Matchers.anyOf(is(200), is(503));
    }
}
