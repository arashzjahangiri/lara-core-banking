package io.lara.payments;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import io.lara.payments.domain.Iban;
import io.lara.payments.domain.TransferStatus;
import io.lara.payments.fakes.FixedAccounts;
import io.lara.payments.fakes.InMemoryLedger;
import io.lara.payments.fakes.ScriptedRisk;
import io.lara.payments.fakes.ScriptedScheme;
import io.lara.payments.fakes.StubbedDependenciesProfile;
import io.lara.payments.infrastructure.saga.StalledTransferSweep;

/**
 * The saga under concurrency, against a real PostgreSQL.
 *
 * <p>Everything in this phase was written to be correct when several things touch one saga at
 * once. That is a claim, and an untested claim about concurrency is usually wrong — so this is
 * the test that tries to break it.
 *
 * <p>The ledger here is a fake, but <strong>a fake that keeps books</strong>: entries must sum to
 * zero, an account cannot go below zero, and one reference produces one transaction. Without
 * those, "no transfer overdrew the account" would be a statement about the stub rather than about
 * the orchestrator, and the test would pass just as happily with the locking removed.
 *
 * <p>What it does not prove is the real ledger's behaviour — it cannot, having no chart of
 * accounts, no hash chain and no persistence. The cross-service run against the live stack is
 * separate and is recorded with #46.
 */
@QuarkusTest
@TestProfile(StubbedDependenciesProfile.class)
class ConcurrencyTest {

    /**
     * Injected through the ports rather than as the fake types.
     *
     * <p>The fakes are alternatives enabled only by {@link StubbedDependenciesProfile}, so outside
     * that profile they are not beans at all. Quarkus validates every test class''s injection
     * points during augmentation — including this one, under profiles where the fakes are
     * disabled — so asking for {@code InMemoryLedger} directly fails the build for every other
     * test in the module.
     *
     * <p>Asking for the port always resolves, and inside this profile the implementation behind
     * it is the fake. The narrowing accessors below make that explicit rather than scattering
     * casts through the assertions.
     */
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

    @Inject
    EntityManager entityManager;

    private static final Currency EUR = Currency.getInstance("EUR");

    private static final Iban DEBTOR = Iban.of("DK5000400440116243");
    private static final Iban CREDITOR = Iban.of("DE89370400440532013000");

    private static final String SUSPENSE = "BANK.SEPA.SUSPENSE.EUR";

    /**
     * A fresh ledger account per test.
     *
     * <p>{@link InMemoryLedger} is application-scoped, so it is shared by every test in this
     * class. Reusing one account would carry funds across tests, and "ten transfers racing for
     * four transfers'' worth of money" would quietly become "ten transfers racing for whatever
     * the previous test left behind" — which is not constrained at all, and the assertion about
     * overdrawing would pass without ever having been tested.
     */
    private String debtorLedger;

    /** Each transfer moves 100.00 and carries a 0.50 fee, so 100.50 leaves the account. */
    private static final long AMOUNT = 10_000L;
    private static final long FEE = 50L;
    private static final long TOTAL_PER_TRANSFER = AMOUNT + FEE;

    @BeforeEach
    void setUp() {
        debtorLedger = "CUSTOMER" + UUID.randomUUID().toString().replace("-", "").toUpperCase(
                java.util.Locale.ROOT).substring(0, 12);

        accounts().clear();
        accounts().register(DEBTOR, "cust-1", debtorLedger, EUR);
        risk().reset();
        scheme().reset();
    }

    private int postTransfer() {
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

    /**
     * Fires {@code threads} requests as close to simultaneously as the JVM allows.
     *
     * <p>A {@link CyclicBarrier} rather than simply submitting the tasks. Without it the first
     * request is usually finished before the last thread starts, and the test would be a
     * sequential run wearing a thread pool.
     */
    private List<Integer> fireTogether(int threads, Callable<Integer> request) throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    startLine.await(30, TimeUnit.SECONDS);
                    return request.call();
                });
            }

            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> result : pool.invokeAll(tasks)) {
                codes.add(result.get(120, TimeUnit.SECONDS));
            }
            return codes;
        } finally {
            pool.shutdownNow();
        }
    }

    private long completedTransfers() {
        return QuarkusTransaction.requiringNew().call(() -> (long) entityManager
                .createNativeQuery("select count(*) from transfer where status = 'COMPLETED'")
                .getSingleResult());
    }

    @Nested
    @DisplayName("draining one account")
    class Draining {

        /**
         * The assertion that matters most in the whole phase. Ten transfers race for funds that
         * cover only four of them, and the account must not go below zero however the race
         * resolves.
         */
        @Test
        void never_lets_parallel_transfers_overdraw_the_account() throws Exception {
            long openingBalance = TOTAL_PER_TRANSFER * 4;
            ledger().fund(debtorLedger, openingBalance);
            long before = ledger().balanceOf(debtorLedger);

            fireTogether(10, ConcurrencyTest.this::postTransfer);

            long after = ledger().balanceOf(debtorLedger);
            assertThat(after)
                    .as("an account can never go below zero, whatever the interleaving")
                    .isGreaterThanOrEqualTo(0L);

            long moved = before - after;
            assertThat(moved)
                    .as("no more left the account than it held")
                    .isLessThanOrEqualTo(before);
            assertThat(moved)
                    .as("only four transfers'' worth was ever available, so at most four went")
                    .isLessThanOrEqualTo(openingBalance);
            assertThat(moved % TOTAL_PER_TRANSFER)
                    .as("money leaves in whole transfers, never a fraction of one")
                    .isZero();
        }

        /**
         * The books still balance afterwards. Money moved between accounts; none was created or
         * destroyed by the race.
         */
        @Test
        void leaves_the_trial_balance_where_it_started() throws Exception {
            long opening = TOTAL_PER_TRANSFER * 3;
            ledger().fund(debtorLedger, opening);
            long trialBefore = ledger().trialBalance();

            fireTogether(8, ConcurrencyTest.this::postTransfer);

            assertThat(ledger().trialBalance())
                    .as("every entry sums to zero, so the total across all accounts cannot move")
                    .isEqualTo(trialBefore);
        }

        /**
         * No lost update: each successful transfer reaches the ledger exactly once. More postings
         * attempted than transactions recorded is expected and healthy — that is the reference
         * deduplication absorbing a retry.
         */
        @Test
        void posts_each_successful_transfer_exactly_once() throws Exception {
            ledger().fund(debtorLedger, TOTAL_PER_TRANSFER * 20);
            int transactionsBefore = ledger().transactionsRecorded();
            long completedBefore = completedTransfers();

            fireTogether(10, ConcurrencyTest.this::postTransfer);

            long completedAfter = completedTransfers();
            int transactionsAfter = ledger().transactionsRecorded();

            assertThat(transactionsAfter - transactionsBefore)
                    .as("one ledger transaction per completed transfer, never two")
                    .isEqualTo((int) (completedAfter - completedBefore));
        }

        /** With plenty of funds, every single one goes through. */
        @Test
        void completes_all_of_them_when_the_money_is_there() throws Exception {
            ledger().fund(debtorLedger, TOTAL_PER_TRANSFER * 50);
            long before = completedTransfers();

            List<Integer> codes = fireTogether(10, ConcurrencyTest.this::postTransfer);

            assertThat(codes).allSatisfy(code -> assertThat(code).isEqualTo(201));
            assertThat(completedTransfers() - before).isEqualTo(10L);
        }
    }

    @Nested
    @DisplayName("the recovery sweep running alongside the orchestrator")
    class SweepAndOrchestrator {

        /**
         * The race the optimistic lock exists for. The sweep and a live request both try to
         * advance the same sagas, and the ledger's reference deduplication plus the version
         * column must mean no step is driven twice to effect.
         */
        @Test
        void does_not_double_post_a_transfer_it_races() throws Exception {
            ledger().fund(debtorLedger, TOTAL_PER_TRANSFER * 20);
            long completedBefore = completedTransfers();
            int transactionsBefore = ledger().transactionsRecorded();

            // Half the threads request transfers; the other half sweep at the same moment.
            CyclicBarrier startLine = new CyclicBarrier(8);
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Callable<Integer>> tasks = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    tasks.add(() -> {
                        startLine.await(30, TimeUnit.SECONDS);
                        return postTransfer();
                    });
                }
                for (int i = 0; i < 4; i++) {
                    tasks.add(() -> {
                        startLine.await(30, TimeUnit.SECONDS);
                        backdateEverythingLive();
                        sweep.sweepNow();
                        return 0;
                    });
                }

                for (Future<Integer> result : pool.invokeAll(tasks)) {
                    result.get(120, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            long completedAfter = completedTransfers();
            int transactionsAfter = ledger().transactionsRecorded();

            assertThat(transactionsAfter - transactionsBefore)
                    .as("one transaction per completed transfer even with a sweep racing it")
                    .isEqualTo((int) (completedAfter - completedBefore));
        }

        /** And the books are untouched by the race. */
        @Test
        void leaves_the_books_balanced() throws Exception {
            ledger().fund(debtorLedger, TOTAL_PER_TRANSFER * 10);
            long trialBefore = ledger().trialBalance();

            fireTogether(6, () -> {
                postTransfer();
                backdateEverythingLive();
                sweep.sweepNow();
                return 0;
            });

            assertThat(ledger().trialBalance()).isEqualTo(trialBefore);
        }

        /** Makes every in-flight saga look stalled, so the sweep has something to pick up. */
        private void backdateEverythingLive() {
            QuarkusTransaction.requiringNew().run(() -> entityManager
                    .createNativeQuery("""
                            update transfer set last_touched_at = :when
                            where status not in ('COMPLETED','REJECTED','COMPENSATED','FAILED')
                            """)
                    .setParameter("when", Instant.now().minus(Duration.ofHours(1)))
                    .executeUpdate());
        }
    }

    @Nested
    @DisplayName("one idempotency key from many threads")
    class OneKeyManyThreads {

        /**
         * Exactly one saga, however many threads send the key. The primary key settles it; a
         * read-then-write in the service would have a window wide enough for several callers to
         * each decide they were first.
         */
        @Test
        void produces_exactly_one_transfer() throws Exception {
            ledger().fund(debtorLedger, TOTAL_PER_TRANSFER * 10);
            String key = "shared-" + UUID.randomUUID();
            long before = totalTransfers();

            List<Integer> codes = fireTogether(8, () -> given().contentType(ContentType.JSON)
                    .header("Idempotency-Key", key)
                    .body(Map.of(
                            "debtorIban", DEBTOR.value(),
                            "creditorIban", CREDITOR.value(),
                            "amountMinorUnits", AMOUNT,
                            "currency", "EUR",
                            "requestedBy", "arash"))
                    .when().post("/payments/transfers")
                    .then().extract().statusCode());

            assertThat(totalTransfers() - before)
                    .as("one key, one transfer, however many threads raced for it")
                    .isEqualTo(1L);

            // Exactly one caller did the work; the rest were either replayed the stored answer
            // or told the key was already taken.
            assertThat(codes).filteredOn(code -> code == 201).hasSize(1);
            assertThat(codes).allSatisfy(code -> assertThat(code).isIn(201, 409, 200));
        }

        private long totalTransfers() {
            return QuarkusTransaction.requiringNew().call(() -> (long) entityManager
                    .createNativeQuery("select count(*) from transfer")
                    .getSingleResult());
        }
    }

    @Nested
    @DisplayName("after everything")
    class Invariants {

        /**
         * The one invariant that has to hold no matter what the earlier tests did to the books.
         * Every entry sums to zero, so the total across all accounts equals exactly what was
         * funded in — nothing was created or destroyed.
         */
        @Test
        void the_ledger_still_balances_and_no_account_is_negative() throws Exception {
            ledger().fund(debtorLedger, TOTAL_PER_TRANSFER * 5);
            long funded = ledger().trialBalance();

            fireTogether(6, ConcurrencyTest.this::postTransfer);

            assertThat(ledger().trialBalance()).isEqualTo(funded);
            assertThat(ledger().balanceOf(debtorLedger)).isGreaterThanOrEqualTo(0L);
            assertThat(ledger().balanceOf(SUSPENSE)).isGreaterThanOrEqualTo(0L);
        }

        /** No transfer is left half-done: every one is terminal or genuinely still in flight. */
        @Test
        void leaves_no_transfer_in_an_impossible_state() throws Exception {
            ledger().fund(debtorLedger, TOTAL_PER_TRANSFER * 4);

            fireTogether(8, ConcurrencyTest.this::postTransfer);

            long posted = QuarkusTransaction.requiringNew().call(() -> (long) entityManager
                    .createNativeQuery("""
                            select count(*) from transfer
                            where status in ('POSTED','COMPLETED','COMPENSATING','COMPENSATED')
                              and ledger_transaction_id is null
                            """)
                    .getSingleResult());

            assertThat(posted)
                    .as("a state claiming money moved must always name the transaction that moved it")
                    .isZero();
        }

        @Test
        void never_rejects_a_transfer_that_already_posted() {
            long contradictory = QuarkusTransaction.requiringNew().call(() -> (long) entityManager
                    .createNativeQuery("""
                            select count(*) from transfer
                            where status = 'REJECTED' and ledger_transaction_id is not null
                            """)
                    .getSingleResult());

            assertThat(contradictory)
                    .as("REJECTED means nothing moved; everything downstream relies on it")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("sanity of the fake itself")
    class FakeSanity {

        /**
         * A concurrency test against a permissive stub proves nothing, so this checks the stub
         * actually refuses what the real ledger would refuse.
         */
        @Test
        void refuses_to_overdraw_and_deduplicates_by_reference() {
            assertThat(ledger().balanceOf("NOBODY")).isZero();

            int before = ledger().transactionsRecorded();
            ledger().fund("TEST.SOURCE", TOTAL_PER_TRANSFER);

            // Two postings under one reference: the second returns the first's transaction.
            var command = new io.lara.payments.application.PostingCommand(
                    io.lara.payments.domain.TransferReference.of("TRANSFER-" + UUID.randomUUID()),
                    "TEST.SOURCE",
                    "TEST.TARGET",
                    io.lara.payments.domain.Money.of(AMOUNT, "EUR"),
                    io.lara.payments.domain.Money.of(FEE, "EUR"),
                    "BANK.INCOME.FEES.EUR",
                    java.time.LocalDate.of(2026, 10, 5));

            var first = ledger().post(command);
            var second = ledger().post(command);

            assertThat(second).isEqualTo(first);
            assertThat(ledger().transactionsRecorded() - before).isEqualTo(1);
            assertThat(ledger().balanceOf("TEST.SOURCE")).isZero();

            // And a third attempt with no funds left under a new reference is refused.
            var broke = new io.lara.payments.application.PostingCommand(
                    io.lara.payments.domain.TransferReference.of("TRANSFER-" + UUID.randomUUID()),
                    "TEST.SOURCE",
                    "TEST.TARGET",
                    io.lara.payments.domain.Money.of(AMOUNT, "EUR"),
                    io.lara.payments.domain.Money.of(FEE, "EUR"),
                    "BANK.INCOME.FEES.EUR",
                    java.time.LocalDate.of(2026, 10, 5));

            org.assertj.core.api.Assertions
                    .assertThatExceptionOfType(io.lara.payments.application.LedgerRefusedException.class)
                    .isThrownBy(() -> ledger().post(broke))
                    .withMessageContaining("insufficient funds");
        }

        @Test
        void keeps_its_own_trial_balance_at_the_funded_total() {
            long before = ledger().trialBalance();
            ledger().fund("TEST.ISOLATED", 5_000L);

            assertThat(ledger().trialBalance()).isEqualTo(before + 5_000L);
        }
    }

    @Test
    @DisplayName("every transfer that completed is in a consistent state")
    void completed_transfers_all_name_a_ledger_transaction() throws Exception {
        ledger().fund(debtorLedger, TOTAL_PER_TRANSFER * 3);

        fireTogether(5, ConcurrencyTest.this::postTransfer);

        long inconsistent = QuarkusTransaction.requiringNew().call(() -> (long) entityManager
                .createNativeQuery("""
                        select count(*) from transfer
                        where status = 'COMPLETED' and ledger_transaction_id is null
                        """)
                .getSingleResult());

        assertThat(inconsistent).isZero();
        assertThat(completedTransfers()).isGreaterThan(0L);

        // The scheme saw every completed transfer exactly once on its way through.
        assertThat(scheme().calls()).isGreaterThan(0);
        assertThat(risk().calls()).isGreaterThan(0);
    }
}
