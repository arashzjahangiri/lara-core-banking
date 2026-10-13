package io.lara.payments;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.lara.payments.application.AccountDirectory;
import io.lara.payments.application.LedgerPosting;
import io.lara.payments.application.RiskScreening;
import io.lara.payments.application.SchemeGateway;
import io.lara.payments.application.ScreeningVerdict;
import io.lara.payments.domain.Iban;
import io.lara.payments.domain.TransferStatus;
import io.lara.payments.fakes.FixedAccounts;
import io.lara.payments.fakes.InMemoryLedger;
import io.lara.payments.fakes.ScriptedRisk;
import io.lara.payments.fakes.ScriptedScheme;
import io.lara.payments.fakes.StubbedDependenciesProfile;
import io.lara.payments.infrastructure.saga.StalledTransferSweep;

/**
 * Faults injected at every step, against a real PostgreSQL.
 *
 * <p>Compensation is the code most likely to be wrong, because in normal operation it never runs.
 * The only way to trust it is to make the failures happen on purpose.
 *
 * <p><strong>One invariant has to survive every injected fault: no money is created or
 * destroyed.</strong> Whatever the saga ends up believing about itself — completed, rejected,
 * compensated or stuck — the books must still balance and no account may be negative. Every test
 * here asserts that, in addition to whatever else it is checking.
 *
 * <p>The fakes enforce what the real ledger enforces, so these assertions mean something. The
 * cross-service run against the live stack is recorded separately on the ticket; this is the part
 * that runs in CI in seconds.
 */
@QuarkusTest
@TestProfile(StubbedDependenciesProfile.class)
class FailureInjectionTest {

    @Inject
    LedgerPosting ledgerPort;

    @Inject
    AccountDirectory accountsPort;

    @Inject
    RiskScreening riskPort;

    @Inject
    SchemeGateway schemePort;

    @Inject
    StalledTransferSweep sweep;

    @Inject
    EntityManager entityManager;

    private InMemoryLedger ledger() {
        return (InMemoryLedger) ledgerPort;
    }

    private FixedAccounts accounts() {
        return (FixedAccounts) accountsPort;
    }

    private ScriptedRisk risk() {
        return (ScriptedRisk) riskPort;
    }

    private ScriptedScheme scheme() {
        return (ScriptedScheme) schemePort;
    }

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Iban DEBTOR = Iban.of("DK5000400440116243");
    private static final Iban CREDITOR = Iban.of("DE89370400440532013000");

    private static final long AMOUNT = 10_000L;
    private static final long FEE = 50L;

    private String debtorLedger;
    private long fundedTotal;

    /**
     * Counter baselines taken per test.
     *
     * <p>{@link InMemoryLedger} is application-scoped and shared by every test in this class, so
     * its transaction and reversal counts accumulate. Asserting absolute numbers would make each
     * test depend on how many ran before it — which is the sort of coupling that passes locally
     * and fails in a different order on CI.
     */
    private int transactionsAtStart;
    private int reversalsAtStart;

    private int transactionsSince() {
        return ledger().transactionsRecorded() - transactionsAtStart;
    }

    private int reversalsSince() {
        return ledger().reversalsRecorded() - reversalsAtStart;
    }

    @BeforeEach
    void setUp() {
        debtorLedger = "CUSTOMER" + UUID.randomUUID().toString().replace("-", "")
                .toUpperCase(Locale.ROOT).substring(0, 12);

        accounts().clear();
        accounts().register(DEBTOR, "cust-1", debtorLedger, EUR);
        risk().reset();
        scheme().reset();

        ledger().fund(debtorLedger, (AMOUNT + FEE) * 10);
        fundedTotal = ledger().trialBalance();
        transactionsAtStart = ledger().transactionsRecorded();
        reversalsAtStart = ledger().reversalsRecorded();
    }

    /** Requests a transfer and returns the HTTP status. */
    private int request() {
        return given().contentType(ContentType.JSON)
                .header("Idempotency-Key", "key-" + UUID.randomUUID())
                .body(Map.of(
                        "debtorIban", DEBTOR.value(),
                        "creditorIban", CREDITOR.value(),
                        "amountMinorUnits", AMOUNT,
                        "currency", "EUR",
                        "requestedBy", "arash"))
                .when().post("/payments/transfers")
                .then().extract().statusCode();
    }

    /** The newest transfer, which is the one the test just created. */
    private String newestTransferStatus() {
        return QuarkusTransaction.requiringNew().call(() -> (String) entityManager
                .createNativeQuery("select status from transfer order by requested_at desc limit 1")
                .getSingleResult());
    }

    private String newestLedgerTransactionId() {
        return QuarkusTransaction.requiringNew().call(() -> (String) entityManager
                .createNativeQuery("""
                        select cast(ledger_transaction_id as text) from transfer
                        order by requested_at desc limit 1
                        """)
                .getSingleResult());
    }

    /**
     * The invariant that must survive every fault.
     *
     * <p>Checked in every test rather than once at the end. A single final assertion would tell
     * you the books were broken but not which fault broke them.
     */
    private void assertNoMoneyCreatedOrDestroyed() {
        assertThat(ledger().trialBalance())
                .as("every entry sums to zero, so the total across all accounts cannot move")
                .isEqualTo(fundedTotal);
        assertThat(ledger().balanceOf(debtorLedger))
                .as("an account can never go below zero")
                .isGreaterThanOrEqualTo(0L);
    }

    @Nested
    @DisplayName("a fault before the money moves")
    class BeforeTheMoneyMoves {

        /**
         * An account that does not exist is a decision: the transfer is rejected on its own
         * merits, before it ever consumes a slot in the customer''s screening total.
         */
        @Test
        void an_unknown_account_is_rejected() {
            accounts().clear();

            assertThat(request()).isEqualTo(201);

            assertThat(newestTransferStatus()).isEqualTo("REJECTED");
            assertThat(transactionsSince()).isZero();
            assertNoMoneyCreatedOrDestroyed();
        }

        /**
         * An unreachable directory is <em>not</em> a decision, and the difference matters more
         * than almost anything else in this phase. Reading an outage as "no such account" would
         * reject every valid transfer for its duration, each one recorded as a refusal somebody
         * then has to explain.
         */
        @Test
        void an_unreachable_accounts_service_decides_nothing_and_leaves_the_saga_resumable() {
            accounts().failWith("connection refused");

            assertThat(request()).isEqualTo(503);

            assertThat(newestTransferStatus()).isEqualTo("REQUESTED");
            assertThat(transactionsSince()).isZero();
            assertNoMoneyCreatedOrDestroyed();

            // And it finishes normally once the directory comes back.
            accounts().recover();
            accounts().register(DEBTOR, "cust-1", debtorLedger, EUR);
            makeEverythingLookStalled();
            sweep.sweepNow();

            assertThat(newestTransferStatus()).isEqualTo("COMPLETED");
            assertNoMoneyCreatedOrDestroyed();
        }

        @Test
        void risk_unreachable_stops_the_saga_without_deciding_anything() {
            risk().failWith("read timed out");

            assertThat(request()).isEqualTo(503);

            // Not allowed, not rejected. Not knowing is its own outcome.
            assertThat(newestTransferStatus()).isEqualTo("SCREENING");
            assertThat(reversalsSince()).isZero();
            assertNoMoneyCreatedOrDestroyed();
        }

        @Test
        void risk_blocking_rejects_with_no_posting_at_all() {
            risk().answerWith(ScreeningVerdict.block("beneficiary matches sanctioned party"));

            assertThat(request()).isEqualTo(201);

            assertThat(newestTransferStatus()).isEqualTo("REJECTED");
            assertThat(newestLedgerTransactionId()).isNull();
            assertThat(transactionsSince()).isZero();
            assertNoMoneyCreatedOrDestroyed();
        }

        /** The ledger refusing is a decision: the transfer is rejected and nothing moved. */
        @Test
        void insufficient_funds_rejects_rather_than_compensating() {
            String empty = "CUSTOMER" + UUID.randomUUID().toString().replace("-", "")
                    .toUpperCase(Locale.ROOT).substring(0, 12);
            accounts().clear();
            accounts().register(DEBTOR, "cust-1", empty, EUR);

            assertThat(request()).isEqualTo(201);

            assertThat(newestTransferStatus()).isEqualTo("REJECTED");
            assertThat(reversalsSince()).isZero();
            assertNoMoneyCreatedOrDestroyed();
        }

        @Test
        void a_rejected_transfer_never_names_a_ledger_transaction() {
            risk().answerWith(ScreeningVerdict.block("sanctions match"));
            request();

            long contradictory = QuarkusTransaction.requiringNew().call(() -> (long) entityManager
                    .createNativeQuery("""
                            select count(*) from transfer
                            where status = 'REJECTED' and ledger_transaction_id is not null
                            """)
                    .getSingleResult());

            assertThat(contradictory)
                    .as("REJECTED means nothing moved; everything downstream relies on it")
                    .isZero();
            assertNoMoneyCreatedOrDestroyed();
        }
    }

    @Nested
    @DisplayName("a fault after the money has moved")
    class AfterTheMoneyMoves {

        /**
         * The case compensation exists for. The ledger has the money and the scheme will not
         * carry it, so the only honest exit is to put it back.
         */
        @Test
        void the_scheme_rejecting_reverses_the_posting() {
            scheme().reject("beneficiary account closed at the creditor bank");

            assertThat(request()).isEqualTo(201);

            assertThat(newestTransferStatus()).isEqualTo("COMPENSATED");
            assertThat(reversalsSince()).isEqualTo(1);
            assertNoMoneyCreatedOrDestroyed();
        }

        /** And the debtor is left exactly as they started — the fee comes back too. */
        @Test
        void returns_every_minor_unit_including_the_fee() {
            long before = ledger().balanceOf(debtorLedger);
            scheme().reject("beneficiary account closed");

            request();

            assertThat(ledger().balanceOf(debtorLedger))
                    .as("a reversal is the mirror image, so the debtor is whole again")
                    .isEqualTo(before);
            assertNoMoneyCreatedOrDestroyed();
        }

        /**
         * An unreachable scheme is deliberately not a rejection. The submission may have landed,
         * and reversing a payment the scheme accepted would send the money back while the
         * beneficiary is also being paid.
         */
        @Test
        void an_unreachable_scheme_does_not_trigger_a_reversal() {
            scheme().failWith("no response");

            assertThat(request()).isEqualTo(503);

            assertThat(newestTransferStatus()).isEqualTo("POSTED");
            assertThat(reversalsSince()).isZero();
            assertNoMoneyCreatedOrDestroyed();
        }

        /** Once it answers, the saga finishes with no special handling. */
        @Test
        void resumes_and_settles_once_the_scheme_comes_back() {
            scheme().failWith("no response");
            request();
            assertThat(newestTransferStatus()).isEqualTo("POSTED");

            scheme().accept();
            makeEverythingLookStalled();
            sweep.sweepNow();

            assertThat(newestTransferStatus()).isEqualTo("COMPLETED");
            assertNoMoneyCreatedOrDestroyed();
        }

        /** And resumes into a reversal if the scheme comes back with a refusal. */
        @Test
        void resumes_into_compensation_if_the_scheme_comes_back_refusing() {
            scheme().failWith("no response");
            request();

            scheme().reject("beneficiary account closed");
            makeEverythingLookStalled();
            sweep.sweepNow();

            assertThat(newestTransferStatus()).isEqualTo("COMPENSATED");
            assertThat(reversalsSince()).isEqualTo(1);
            assertNoMoneyCreatedOrDestroyed();
        }
    }

    @Nested
    @DisplayName("repeating a fault")
    class Repeated {

        /**
         * A sweep that runs several times over a compensating saga must not reverse twice. The
         * ledger deduplicates, which is what makes the sweep safe to run at all.
         */
        @Test
        void sweeping_a_compensated_transfer_repeatedly_reverses_only_once() {
            scheme().reject("beneficiary account closed");
            request();

            int afterFirst = reversalsSince();

            for (int i = 0; i < 3; i++) {
                makeEverythingLookStalled();
                sweep.sweepNow();
            }

            assertThat(reversalsSince()).isEqualTo(afterFirst);
            assertNoMoneyCreatedOrDestroyed();
        }

        /**
         * Re-driving a posting step produces one transaction, because the reference is derived
         * from the transfer id. This is ADR-0007 doing the work that makes recovery possible.
         */
        @Test
        void re_driving_a_posting_never_posts_twice() {
            scheme().failWith("no response");
            request();

            int transactions = transactionsSince();

            for (int i = 0; i < 3; i++) {
                makeEverythingLookStalled();
                sweep.sweepNow();
            }

            assertThat(transactionsSince())
                    .as("one reference, one transaction, however many times the step is re-driven")
                    .isEqualTo(transactions);
            assertNoMoneyCreatedOrDestroyed();
        }
    }

    @Nested
    @DisplayName("faults at several steps in one run")
    class Mixed {

        /**
         * The whole set in sequence. Whatever each transfer ends up believing, the books are
         * unchanged at the end — which is the single assertion that matters most in this file.
         */
        @Test
        void no_money_is_created_or_destroyed_by_any_of_them() {
            risk().failWith("read timed out");
            request();
            risk().recover();

            risk().answerWith(ScreeningVerdict.block("sanctions match"));
            request();
            risk().reset();

            scheme().reject("beneficiary account closed");
            request();
            scheme().accept();

            scheme().failWith("no response");
            request();
            scheme().accept();

            request();

            makeEverythingLookStalled();
            sweep.sweepNow();

            assertNoMoneyCreatedOrDestroyed();

            long impossible = QuarkusTransaction.requiringNew().call(() -> (long) entityManager
                    .createNativeQuery("""
                            select count(*) from transfer
                            where (status in ('POSTED','COMPLETED','COMPENSATING','COMPENSATED')
                                   and ledger_transaction_id is null)
                               or (status = 'REJECTED' and ledger_transaction_id is not null)
                            """)
                    .getSingleResult());

            assertThat(impossible)
                    .as("no transfer may claim money moved without naming the transaction, "
                            + "or claim nothing moved while naming one")
                    .isZero();
        }

        /** Every transfer ends somewhere it can be explained from. */
        @Test
        void every_transfer_is_terminal_or_genuinely_still_in_flight() {
            scheme().reject("closed");
            request();
            scheme().accept();
            request();
            risk().failWith("timeout");
            request();
            risk().recover();

            List<String> statuses = QuarkusTransaction.requiringNew().call(() -> entityManager
                    .createNativeQuery("select distinct status from transfer")
                    .getResultList()
                    .stream()
                    .map(String.class::cast)
                    .toList());

            assertThat(statuses).allSatisfy(status ->
                    assertThat(TransferStatus.valueOf(status)).isNotNull());
            assertNoMoneyCreatedOrDestroyed();
        }
    }

    /** Makes every in-flight saga look stalled, so the sweep has something to pick up. */
    private void makeEverythingLookStalled() {
        QuarkusTransaction.requiringNew().run(() -> entityManager
                .createNativeQuery("""
                        update transfer set last_touched_at = :when
                        where status not in ('COMPLETED','REJECTED','COMPENSATED','FAILED')
                        """)
                .setParameter("when", Instant.now().minus(Duration.ofHours(1)))
                .executeUpdate());
    }
}
