package io.lara.payments;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.hamcrest.Matchers.is;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.inject.Inject;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.lara.payments.application.AccountDirectory;
import io.lara.payments.application.LedgerPosting;
import io.lara.payments.application.RiskScreening;
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
 * What happens when a dependency is not there.
 *
 * <p>None of the three services are running in this test, so every outbound call fails — which is
 * precisely the condition worth pinning down. The assertion that matters is a negative one: an
 * unreachable risk service must never let a transfer through. A fallback that returned a
 * synthetic {@code ALLOW} would be the single worst failure this system can have, and it is also
 * the easiest one to introduce by accident, because it makes the tests go green.
 *
 * <p>Fault tolerance is disabled under the test profile, so retries and breakers do not slow this
 * down or trip partway through the suite. What is being checked here is the <em>behaviour</em>
 * the policy protects, not the policy's own timing.
 */
@QuarkusTest
class ResilienceTest {

    @Inject
    Transfers transfers;

    @Inject
    Clock clock;

    @Inject
    AccountDirectory accounts;

    @Inject
    RiskScreening risk;

    @Inject
    LedgerPosting ledger;

    private static final Iban DEBTOR = Iban.of("DK5000400440116243");
    private static final Iban CREDITOR = Iban.of("DE89370400440532013000");

    private static final RoutingDecision SEPA = new RoutingDecision(
            TransferScheme.SEPA_CREDIT_TRANSFER, Money.of(50, "EUR"), LocalDate.of(2026, 10, 5));

    private static Map<String, Object> body() {
        return Map.of(
                "debtorIban", DEBTOR.value(),
                "creditorIban", CREDITOR.value(),
                "amountMinorUnits", 10_000,
                "currency", "EUR",
                "requestedBy", "arash");
    }

    @Nested
    @DisplayName("an unreachable dependency")
    class Unreachable {

        /**
         * The assertion the ticket exists for. Not knowing is not an allow.
         */
        @Test
        void never_lets_a_transfer_through_when_risk_cannot_be_asked() {
            Transfer attempted = postAndFind();

            assertThat(attempted.status()).isNotEqualTo(TransferStatus.COMPLETED);
            assertThat(attempted.ledgerTransaction()).isEmpty();
        }

        /**
         * And not a reject either. A rejection is an answer, and the system does not have one —
         * saying no would turn every outage into a wave of wrongly refused payments.
         */
        @Test
        void does_not_reject_the_transfer_either() {
            assertThat(postAndFind().status()).isNotEqualTo(TransferStatus.REJECTED);
        }

        /**
         * 503 rather than 500. The request was fine and is worth retrying; the transfer is
         * recorded and the recovery sweep owns it, so the caller has lost nothing.
         */
        @Test
        void reports_the_dependency_rather_than_blaming_the_request() {
            given().contentType(ContentType.JSON)
                    .header("Idempotency-Key", "key-" + UUID.randomUUID())
                    .body(body())
                    .when().post("/payments/transfers")
                    .then()
                    .statusCode(503)
                    .body("error", is("dependency-unavailable"));
        }

        /** The saga stays somewhere it can be resumed from, never in a terminal state. */
        @Test
        void leaves_the_saga_recoverable() {
            Transfer attempted = postAndFind();

            assertThat(attempted.isTerminal()).isFalse();
            assertThat(attempted.status()).isEqualTo(TransferStatus.REQUESTED);
        }
    }

    @Nested
    @DisplayName("the adapters themselves")
    class Adapters {

        /**
         * Each one raises {@code RemoteServiceException} naming which dependency failed, rather
         * than letting a connection error escape as whatever the HTTP client happened to throw.
         * The orchestrator branches on that type, so an unmapped exception would take a different
         * path entirely.
         */
        @Test
        void all_three_report_the_service_that_could_not_be_reached() {
            assertThat(serviceNameFrom(() -> accounts.find(DEBTOR))).isEqualTo("accounts");

            assertThat(serviceNameFrom(() -> risk.screen(
                    new io.lara.payments.application.ScreeningCommand(
                            io.lara.payments.domain.TransferReference.of("TRANSFER-x"),
                            "cust-1",
                            Money.of(100, "EUR"),
                            CREDITOR.value()))))
                    .isEqualTo("risk");

            assertThat(serviceNameFrom(() -> ledger.post(
                    new io.lara.payments.application.PostingCommand(
                            io.lara.payments.domain.TransferReference.of("TRANSFER-y"),
                            "CUSTOMER000001",
                            "BANK.SEPA.SUSPENSE.EUR",
                            Money.of(100, "EUR"),
                            Money.zero("EUR"),
                            "BANK.INCOME.FEES.EUR",
                            LocalDate.of(2026, 10, 5)))))
                    .isEqualTo("ledger");
        }

        /**
         * An unreachable accounts service must throw rather than return empty.
         *
         * <p>The sharpest version of the fabricated-success trap. {@code Optional.empty()} is a
         * perfectly reasonable-looking thing for a failed lookup to return, and the orchestrator
         * reads it as "no such account" — so a transfer would be rejected as invalid during an
         * outage, with a reason the customer would find baffling.
         */
        @Test
        void an_unreachable_lookup_throws_rather_than_reading_as_a_missing_account() {
            assertThatExceptionOfType(io.lara.payments.application.RemoteServiceException.class)
                    .isThrownBy(() -> accounts.find(DEBTOR));
        }

        private String serviceNameFrom(Runnable call) {
            try {
                call.run();
                throw new AssertionError("expected the call to fail, but it returned");
            } catch (io.lara.payments.application.RemoteServiceException unreachable) {
                return unreachable.service();
            }
        }
    }

    @Nested
    @DisplayName("the health endpoint")
    class Health {

        /**
         * The breaker states are published as data and the check still reports UP.
         *
         * <p>Readiness controls whether traffic is routed here, and an open breaker says a
         * dependency is down rather than that this instance is unhealthy. Every instance talks to
         * the same risk service, so failing readiness would take the whole deployment out of
         * rotation — turning one dependency's outage into a total outage, including the endpoints
         * that still work.
         */
        @Test
        void publishes_each_breaker_without_failing_readiness() {
            given().when().get("/health/ready")
                    .then()
                    .statusCode(200)
                    .body("status", is("UP"))
                    .body("checks.find { it.name == 'outbound dependencies' }.status", is("UP"))
                    .body("checks.find { it.name == 'outbound dependencies' }.data.risk",
                            org.hamcrest.Matchers.notNullValue())
                    .body("checks.find { it.name == 'outbound dependencies' }.data.ledger",
                            org.hamcrest.Matchers.notNullValue())
                    .body("checks.find { it.name == 'outbound dependencies' }.data.accounts",
                            org.hamcrest.Matchers.notNullValue());
        }
    }

    /**
     * Posts a transfer, expects the 503, and returns the row it created.
     *
     * <p>Found by diffing the live transfers before and after, because the failure response
     * carries a problem body rather than an id. Scoping to this one row matters: the database is
     * shared across test classes, and {@code RecoverySweepTest} deliberately plants {@code POSTED}
     * rows with a fabricated ledger reference — so any assertion over "all transfers" would be
     * asserting something about another test''s fixtures.
     */
    private Transfer postAndFind() {
        Set<TransferId> before = liveTransferIds();

        given().contentType(ContentType.JSON)
                .header("Idempotency-Key", "key-" + UUID.randomUUID())
                .body(body())
                .when().post("/payments/transfers")
                .then().statusCode(503);

        Set<TransferId> after = liveTransferIds();
        after.removeAll(before);

        assertThat(after)
                .as("exactly one transfer should have been created")
                .hasSize(1);

        return QuarkusTransaction.requiringNew()
                .call(() -> transfers.find(after.iterator().next()))
                .orElseThrow()
                .transfer();
    }

    private Set<TransferId> liveTransferIds() {
        return QuarkusTransaction.requiringNew().call(() -> transfers
                .stuckIn(List.of(
                        TransferStatus.REQUESTED, TransferStatus.SCREENING,
                        TransferStatus.POSTING, TransferStatus.POSTED,
                        TransferStatus.COMPENSATING), java.time.Instant.now().plusSeconds(60), 500)
                .stream()
                .map(StoredTransfer::id)
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new)));
    }

}
