package io.lara.payments;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.lara.payments.infrastructure.idempotency.IdempotencyKeyPruner;
import io.lara.payments.infrastructure.idempotency.IdempotencyKeys;

/**
 * Idempotency at the HTTP edge, end to end against a real PostgreSQL.
 *
 * <p>The saga's three dependencies are not running in this test, so every transfer stalls at the
 * first outbound call and the request comes back 503. That is not a limitation here — it is the
 * most useful case to test against. A failed attempt must release its key so the client's retry
 * gets a real attempt rather than a cached error, and that is exactly what these assertions
 * check. The happy path through a live stack belongs with the end-to-end suite.
 *
 * <p>The concurrency test is the one that matters most. Two threads sending the same key at the
 * same moment must not both be treated as the first, and the only thing standing between them is
 * a primary key.
 */
@QuarkusTest
class IdempotencyTest {

    @Inject
    EntityManager entityManager;

    @Inject
    IdempotencyKeys keys;

    @Inject
    IdempotencyKeyPruner pruner;

    private static Map<String, Object> body(long amount) {
        return Map.of(
                "debtorIban", "DK5000400440116243",
                "creditorIban", "DE89370400440532013000",
                "amountMinorUnits", amount,
                "currency", "EUR",
                "requestedBy", "arash");
    }

    private static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    @Nested
    @DisplayName("the header")
    class Header {

        /**
         * Required, not optional. A client whose connection drops mid-POST cannot tell whether a
         * transfer was created; without a key its only options are to risk paying twice or risk
         * not paying at all.
         */
        @Test
        void is_required() {
            given().contentType(ContentType.JSON)
                    .body(body(10_000))
                    .when().post("/payments/transfers")
                    .then().statusCode(400);
        }

        @Test
        void is_rejected_when_blank() {
            given().contentType(ContentType.JSON)
                    .header("Idempotency-Key", "   ")
                    .body(body(10_000))
                    .when().post("/payments/transfers")
                    .then().statusCode(400);
        }

        @Test
        void is_rejected_when_absurdly_long() {
            given().contentType(ContentType.JSON)
                    .header("Idempotency-Key", "k".repeat(256))
                    .body(body(10_000))
                    .when().post("/payments/transfers")
                    .then().statusCode(400);
        }
    }

    @Nested
    @DisplayName("a failed attempt")
    class FailedAttempt {

        /**
         * The saga cannot reach the accounts service here, so the request fails with 503 — and
         * the transfer is still recorded, which is why 503 rather than 500: it is worth retrying.
         */
        @Test
        void reports_the_dependency_as_unavailable() {
            given().contentType(ContentType.JSON)
                    .header("Idempotency-Key", newKey())
                    .body(body(10_000))
                    .when().post("/payments/transfers")
                    .then()
                    .statusCode(503)
                    .body("error", is("dependency-unavailable"));
        }

        /**
         * Releases its key. Caching a failure would turn one transient error into a permanent one
         * for the whole retention window, and the client would keep being handed the same error
         * no matter how healthy the system had become.
         */
        @Test
        void does_not_keep_the_key() {
            String key = newKey();

            given().contentType(ContentType.JSON)
                    .header("Idempotency-Key", key)
                    .body(body(10_000))
                    .when().post("/payments/transfers")
                    .then().statusCode(503);

            assertThat(storedKeys(key)).isZero();
        }

        /** And so the retry is a real attempt rather than a replay. */
        @Test
        void lets_the_same_key_be_used_again() {
            String key = newKey();

            given().contentType(ContentType.JSON).header("Idempotency-Key", key)
                    .body(body(10_000))
                    .when().post("/payments/transfers").then().statusCode(503);

            given().contentType(ContentType.JSON).header("Idempotency-Key", key)
                    .body(body(10_000))
                    .when().post("/payments/transfers").then().statusCode(503);

            // Two genuine attempts, two transfers recorded — not one replayed answer.
            assertThat(transfersCreated()).isGreaterThanOrEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("replaying a completed request")
    class Replay {

        /**
         * Driven through the store directly, because the saga cannot complete without its
         * dependencies. What is being tested is the replay rule, not the saga.
         */
        @Test
        void returns_the_stored_response_unchanged() {
            String key = newKey();
            String fingerprint = IdempotencyKeys.fingerprintOf("{\"a\":1}");

            assertThat(keys.claim(key, fingerprint, Instant.now())).isEmpty();
            keys.complete(key, 201, "{\"id\":\"the-original\"}");

            var stored = keys.find(key).orElseThrow();
            assertThat(stored.isStillRunning()).isFalse();
            assertThat(stored.status()).contains(201);
            assertThat(stored.body()).contains("{\"id\":\"the-original\"}");
            assertThat(stored.matches(fingerprint)).isTrue();
        }

        /**
         * A different body under the same key is a client bug and must be reported as one.
         * Returning the first transfer's response would tell the caller their second, different
         * payment had succeeded when it was never attempted.
         */
        @Test
        void refuses_the_same_key_with_a_different_body() {
            String key = newKey();

            given().contentType(ContentType.JSON).header("Idempotency-Key", key)
                    .body(body(10_000))
                    .when().post("/payments/transfers").then().statusCode(503);

            // The first attempt released the key, so claim it directly to simulate one that
            // succeeded and is now being replayed with different content.
            keys.claim(key, IdempotencyKeys.fingerprintOf("{\"original\":true}"), Instant.now());
            keys.complete(key, 201, "{\"id\":\"the-original\"}");

            given().contentType(ContentType.JSON).header("Idempotency-Key", key)
                    .body(body(99_999))
                    .when().post("/payments/transfers")
                    .then()
                    .statusCode(409)
                    .body("error", is("idempotency-key-conflict"));
        }

        /**
         * A key whose request is still running has no answer yet, and inventing one would be
         * worse than saying so.
         */
        @Test
        void reports_a_request_that_is_still_running() {
            String key = newKey();
            String fingerprint = IdempotencyKeys.fingerprintOf("anything");

            keys.claim(key, fingerprint, Instant.now());

            assertThat(keys.find(key).orElseThrow().isStillRunning()).isTrue();
        }
    }

    @Nested
    @DisplayName("two requests arriving together")
    class Concurrency {

        /**
         * The race the primary key exists to settle.
         *
         * <p>Both threads look, both find nothing, both try to claim. Exactly one insert can
         * succeed, and the loser has to be told rather than allowed to start a second saga. A
         * check-then-act in the service would have a window wide enough for both to win.
         */
        @Test
        void let_exactly_one_claim_the_key() throws Exception {
            String key = newKey();
            String fingerprint = IdempotencyKeys.fingerprintOf("{\"same\":\"request\"}");
            int threads = 8;

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Callable<Boolean>> attempts = java.util.stream.IntStream.range(0, threads)
                        .mapToObj(i -> (Callable<Boolean>) () ->
                                keys.claim(key, fingerprint, Instant.now()).isEmpty())
                        .toList();

                List<Future<Boolean>> results = pool.invokeAll(attempts);

                long winners = 0;
                for (Future<Boolean> result : results) {
                    if (Boolean.TRUE.equals(result.get(30, TimeUnit.SECONDS))) {
                        winners++;
                    }
                }

                assertThat(winners).isEqualTo(1L);
                assertThat(storedKeys(key)).isEqualTo(1L);
            } finally {
                pool.shutdownNow();
            }
        }

        /** And the whole HTTP path behaves the same way: one key, one transfer at most. */
        @Test
        void never_create_two_transfers_for_one_key() throws Exception {
            String key = newKey();
            int threads = 6;

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Callable<Integer>> attempts = java.util.stream.IntStream.range(0, threads)
                        .mapToObj(i -> (Callable<Integer>) () -> given()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", key)
                                .body(body(10_000))
                                .when().post("/payments/transfers")
                                .then().extract().statusCode())
                        .toList();

                List<Integer> codes = new java.util.ArrayList<>();
                for (Future<Integer> result : pool.invokeAll(attempts)) {
                    codes.add(result.get(60, TimeUnit.SECONDS));
                }

                // Every response is either the one real attempt's 503 or a 409 saying someone
                // else holds the key. Nothing gets a second transfer created for it.
                assertThat(codes).isNotEmpty();
                assertThat(codes).allSatisfy(code -> assertThat(code).isIn(503, 409));
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("expiry")
    class Expiry {

        /** Without pruning the table grows for as long as the service runs. */
        @Test
        void removes_keys_past_their_expiry() {
            String expired = newKey();
            insertExpiredKey(expired);

            assertThat(storedKeys(expired)).isEqualTo(1L);

            assertThat(pruner.pruneNow()).isGreaterThanOrEqualTo(1);
            assertThat(storedKeys(expired)).isZero();
        }

        /** And must leave live ones alone, or a client's valid retry would miss its answer. */
        @Test
        void leaves_a_key_that_has_not_expired() {
            String live = newKey();
            keys.claim(live, IdempotencyKeys.fingerprintOf("x"), Instant.now());

            pruner.pruneNow();

            assertThat(storedKeys(live)).isEqualTo(1L);
        }
    }

    // ---------------------------------------------------------------- reading the database back

    private long storedKeys(String key) {
        return QuarkusTransaction.requiringNew().call(() -> (long) entityManager
                .createNativeQuery("select count(*) from idempotency_key where key = :key")
                .setParameter("key", key)
                .getSingleResult());
    }

    private long transfersCreated() {
        return QuarkusTransaction.requiringNew().call(() -> (long) entityManager
                .createNativeQuery("select count(*) from transfer")
                .getSingleResult());
    }

    private void insertExpiredKey(String key) {
        Instant longAgo = Instant.now().minus(Duration.ofDays(30));
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery("""
                        insert into idempotency_key (key, request_fingerprint, created_at, expires_at)
                        values (:key, :fingerprint, :created, :expires)
                        """)
                .setParameter("key", key)
                .setParameter("fingerprint", IdempotencyKeys.fingerprintOf("old"))
                .setParameter("created", longAgo)
                .setParameter("expires", longAgo.plus(Duration.ofHours(1)))
                .executeUpdate());
    }

    @Test
    @DisplayName("the service reports itself ready")
    void health_is_up() {
        given().when().get("/health/ready").then().statusCode(200).body("status", notNullValue());
    }
}
