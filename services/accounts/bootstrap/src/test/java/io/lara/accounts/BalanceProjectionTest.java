package io.lara.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.lara.accounts.infrastructure.messaging.LedgerPostingConsumer;
import io.quarkus.test.junit.QuarkusTest;

/**
 * The projection against a real PostgreSQL, with events pushed through the real consumer.
 *
 * <p>Events are pushed through the real consumer against a real database.
 */
@QuarkusTest
class BalanceProjectionTest {

    @Inject
    LedgerPostingConsumer consumer;

    @Inject
    javax.sql.DataSource dataSource;

    private static final String ACCOUNT = "CUSTOMER000001";

    /**
     * Calls the consumer directly rather than through a broker. That is a deliberate limit: it
     * exercises the parsing, the use case, the deduplication and the database, but not Kafka's
     * partitioning or redelivery — those belong to Debezium and Kafka and are verified against
     * the running compose stack. What it covers is this service's own responsibility: applying
     * an event exactly once, however many times it arrives.
     */
    private void deliver(String payload) {
        consumer.onPosting(payload);
    }

    private static String posting(String transactionId, String account, String side, long minorUnits) {
        return """
                {
                  "transactionId": "%s",
                  "reference": "REF-%s",
                  "occurredAt": "2026-10-01T12:00:00Z",
                  "account": "%s",
                  "currency": "EUR",
                  "legs": [
                    {"side": "%s", "amountMinorUnits": %d, "currency": "EUR"}
                  ]
                }
                """.formatted(transactionId, transactionId, account, side, minorUnits);
    }

    private long balanceOf(String account) throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "select amount_minor from customer_balance where ledger_account_id = ?")) {
            statement.setString(1, account);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    private long appliedCount(String transactionId) throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "select count(*) from applied_posting where transaction_id = ?")) {
            statement.setString(1, transactionId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    @Nested
    @DisplayName("building the projection")
    class Building {

        @Test
        void a_credit_raises_the_balance() throws Exception {
            String account = "PROJ-" + UUID.randomUUID().toString().substring(0, 8);
            long before = balanceOf(account);

            deliver(posting(UUID.randomUUID().toString(), account, "CREDIT", 50_000));

            assertThat(balanceOf(account)).isEqualTo(before + 50_000);
        }

        @Test
        void a_debit_lowers_it() throws Exception {
            String account = "PROJ-" + UUID.randomUUID().toString().substring(0, 8);

            deliver(posting(UUID.randomUUID().toString(), account, "CREDIT", 50_000));
            deliver(posting(UUID.randomUUID().toString(), account, "DEBIT", 12_500));

            assertThat(balanceOf(account)).isEqualTo(37_500);
        }

        @Test
        void an_account_never_seen_before_starts_at_zero() throws Exception {
            String account = "PROJ-" + UUID.randomUUID().toString().substring(0, 8);

            assertThat(balanceOf(account)).isZero();
            deliver(posting(UUID.randomUUID().toString(), account, "CREDIT", 7));
            assertThat(balanceOf(account)).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("at-least-once delivery")
    class Redelivery {

        /**
         * The property the whole consumer rests on, proved against the real primary key rather
         * than an in-memory set. Debezium republishes after a restart, so this is routine.
         */
        @Test
        void the_same_event_delivered_twice_moves_the_balance_once() throws Exception {
            String account = "PROJ-" + UUID.randomUUID().toString().substring(0, 8);
            String transactionId = UUID.randomUUID().toString();
            String event = posting(transactionId, account, "CREDIT", 33_000);

            deliver(event);
            deliver(event);

            assertThat(balanceOf(account)).isEqualTo(33_000);
            assertThat(appliedCount(transactionId)).isEqualTo(1);
        }

        @Test
        void a_redelivery_storm_still_moves_it_once() throws Exception {
            String account = "PROJ-" + UUID.randomUUID().toString().substring(0, 8);
            String event = posting(UUID.randomUUID().toString(), account, "CREDIT", 1_000);

            for (int delivery = 0; delivery < 8; delivery++) {
                deliver(event);
            }

            assertThat(balanceOf(account)).isEqualTo(1_000);
        }

        /** One transaction produces an event per account it touched; those are not duplicates. */
        @Test
        void the_same_transaction_on_two_accounts_applies_to_both() throws Exception {
            String transactionId = UUID.randomUUID().toString();
            String sender = "PROJ-" + UUID.randomUUID().toString().substring(0, 8);
            String receiver = "PROJ-" + UUID.randomUUID().toString().substring(0, 8);

            deliver(posting(transactionId, sender, "DEBIT", 2_500));
            deliver(posting(transactionId, receiver, "CREDIT", 2_500));

            assertThat(balanceOf(sender)).isEqualTo(-2_500);
            assertThat(balanceOf(receiver)).isEqualTo(2_500);
            assertThat(appliedCount(transactionId)).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("staleness")
    class Staleness {

        /** A customer-facing balance that cannot say how old it is invites being trusted as current. */
        @Test
        void every_balance_records_when_it_was_last_updated() throws Exception {
            String account = "PROJ-" + UUID.randomUUID().toString().substring(0, 8);
            String transactionId = UUID.randomUUID().toString();

            deliver(posting(transactionId, account, "CREDIT", 500));

            try (var connection = dataSource.getConnection();
                    var statement = connection.prepareStatement("""
                            select last_updated, last_transaction_id
                            from customer_balance where ledger_account_id = ?
                            """)) {
                statement.setString(1, account);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getTimestamp(1)).isNotNull();
                    assertThat(rows.getString(2)).isEqualTo(transactionId);
                }
            }
        }
    }
}
