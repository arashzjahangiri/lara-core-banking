package io.lara.accounts.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.lara.accounts.domain.CustomerBalance;
import io.lara.accounts.domain.Money;
import io.lara.accounts.domain.PostingDirection;

/**
 * Hand-written fakes, no container, no mocking framework — the same return on an annotation-free
 * application layer as in the ledger.
 */
class ApplyLedgerPostingTest {

    private static final Instant APPLIED_AT = Instant.parse("2026-10-01T12:00:00Z");
    private static final String ACCOUNT = "CUSTOMER000001";

    private InMemoryBalances balances;
    private InMemoryAppliedPostings applied;
    private ApplyLedgerPosting applyPosting;

    @BeforeEach
    void setUp() {
        balances = new InMemoryBalances();
        applied = new InMemoryAppliedPostings();
        applyPosting = new ApplyLedgerPosting(balances, applied, Clock.fixed(APPLIED_AT, ZoneOffset.UTC));
    }

    private static LedgerPostingEvent credit(String transactionId, long minorUnits) {
        return event(transactionId, new LedgerPostingEvent.Leg(PostingDirection.CREDIT, minorUnits, "EUR"));
    }

    private static LedgerPostingEvent debit(String transactionId, long minorUnits) {
        return event(transactionId, new LedgerPostingEvent.Leg(PostingDirection.DEBIT, minorUnits, "EUR"));
    }

    private static LedgerPostingEvent event(String transactionId, LedgerPostingEvent.Leg... legs) {
        return new LedgerPostingEvent(transactionId, "REF-" + transactionId, APPLIED_AT,
                ACCOUNT, "EUR", List.of(legs));
    }

    private Money balance() {
        return balances.findByLedgerAccount(ACCOUNT).orElseThrow().amount();
    }

    @Nested
    @DisplayName("applying a posting")
    class Applying {

        /** A customer deposit is a liability of the bank, so a credit raises what they have. */
        @Test
        void a_credit_raises_the_balance() {
            applyPosting.apply(credit(UUID.randomUUID().toString(), 50_000));

            assertThat(balance()).isEqualTo(Money.of(50_000, "EUR"));
        }

        @Test
        void a_debit_lowers_it() {
            applyPosting.apply(credit(UUID.randomUUID().toString(), 50_000));
            applyPosting.apply(debit(UUID.randomUUID().toString(), 12_500));

            assertThat(balance()).isEqualTo(Money.of(37_500, "EUR"));
        }

        @Test
        void starts_from_zero_for_an_account_never_seen_before() {
            assertThat(balances.findByLedgerAccount(ACCOUNT)).isEmpty();

            applyPosting.apply(credit(UUID.randomUUID().toString(), 1));

            assertThat(balance()).isEqualTo(Money.of(1, "EUR"));
        }

        /** Two legs on one account happened together and must be applied together. */
        @Test
        void applies_every_leg_of_one_event() {
            applyPosting.apply(credit(UUID.randomUUID().toString(), 100_000));

            applyPosting.apply(event(UUID.randomUUID().toString(),
                    new LedgerPostingEvent.Leg(PostingDirection.DEBIT, 10_000, "EUR"),
                    new LedgerPostingEvent.Leg(PostingDirection.DEBIT, 50, "EUR")));

            assertThat(balance()).isEqualTo(Money.of(89_950, "EUR"));
        }

        @Test
        void records_which_transaction_it_last_applied_and_when() {
            String transactionId = UUID.randomUUID().toString();
            applyPosting.apply(credit(transactionId, 100));

            CustomerBalance stored = balances.findByLedgerAccount(ACCOUNT).orElseThrow();
            assertThat(stored.lastTransactionId()).isEqualTo(transactionId);
            assertThat(stored.lastUpdated()).isEqualTo(APPLIED_AT);
        }

        @Test
        void can_go_below_zero_when_reversed_past_its_balance() {
            applyPosting.apply(debit(UUID.randomUUID().toString(), 2_500));

            assertThat(balance()).isEqualTo(Money.of(-2_500, "EUR"));
            assertThat(balances.findByLedgerAccount(ACCOUNT).orElseThrow().isOverdrawn()).isTrue();
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        /**
         * The property the whole consumer rests on. Debezium republishes after a restart, so this
         * is routine rather than exceptional — and applying twice moves the money twice.
         */
        @Test
        void the_same_event_delivered_twice_moves_the_balance_once() {
            LedgerPostingEvent event = credit("11111111-1111-1111-1111-111111111111", 50_000);

            applyPosting.apply(event);
            applyPosting.apply(event);

            assertThat(balance()).isEqualTo(Money.of(50_000, "EUR"));
            assertThat(balances.saves()).isEqualTo(1);
        }

        @Test
        void redelivery_many_times_still_moves_it_once() {
            LedgerPostingEvent event = credit("11111111-1111-1111-1111-111111111111", 777);

            for (int delivery = 0; delivery < 10; delivery++) {
                applyPosting.apply(event);
            }

            assertThat(balance()).isEqualTo(Money.of(777, "EUR"));
            assertThat(balances.saves()).isEqualTo(1);
        }

        @Test
        void reports_whether_this_delivery_changed_anything() {
            LedgerPostingEvent event = credit("11111111-1111-1111-1111-111111111111", 100);

            assertThat(applyPosting.apply(event).applied()).isTrue();
            assertThat(applyPosting.apply(event).applied()).isFalse();
        }

        /** Dedup is per account: one transaction produces an event for each account it touched. */
        @Test
        void the_same_transaction_on_a_different_account_is_not_a_duplicate() {
            String transactionId = "11111111-1111-1111-1111-111111111111";
            applyPosting.apply(credit(transactionId, 100));

            LedgerPostingEvent other = new LedgerPostingEvent(transactionId, "REF", APPLIED_AT,
                    "CUSTOMER000002", "EUR",
                    List.of(new LedgerPostingEvent.Leg(PostingDirection.DEBIT, 100, "EUR")));

            assertThat(applyPosting.apply(other).applied()).isTrue();
            assertThat(balances.findByLedgerAccount("CUSTOMER000002").orElseThrow().amount())
                    .isEqualTo(Money.of(-100, "EUR"));
        }

        @Test
        void different_transactions_both_apply() {
            applyPosting.apply(credit(UUID.randomUUID().toString(), 100));
            applyPosting.apply(credit(UUID.randomUUID().toString(), 100));

            assertThat(balance()).isEqualTo(Money.of(200, "EUR"));
        }
    }

    @Nested
    @DisplayName("the contract with the ledger")
    class Contract {

        /** A direction we do not recognise must stop the consumer, not be guessed at. */
        @Test
        void an_unknown_direction_is_refused_rather_than_guessed() {
            assertThatThrownBy(() -> PostingDirection.parse("SIDEWAYS"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SIDEWAYS");
            assertThatThrownBy(() -> PostingDirection.parse(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void accepts_the_spellings_the_ledger_actually_publishes() {
            assertThat(PostingDirection.parse("DEBIT")).isEqualTo(PostingDirection.DEBIT);
            assertThat(PostingDirection.parse("CREDIT")).isEqualTo(PostingDirection.CREDIT);
        }

        @Test
        void refuses_an_event_with_no_legs() {
            assertThatThrownBy(() -> new LedgerPostingEvent("t", "r", APPLIED_AT, ACCOUNT, "EUR", List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void refuses_a_leg_that_moves_nothing() {
            assertThatThrownBy(() -> new LedgerPostingEvent.Leg(PostingDirection.DEBIT, 0, "EUR"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** A fake, not a mock. Counts saves so a redelivery writing nothing can be asserted. */
    private static final class InMemoryBalances implements CustomerBalances {

        private final Map<String, CustomerBalance> stored = new HashMap<>();
        private int saves;

        @Override
        public Optional<CustomerBalance> findByLedgerAccount(String ledgerAccountId) {
            return Optional.ofNullable(stored.get(ledgerAccountId));
        }

        @Override
        public void save(CustomerBalance balance) {
            stored.put(balance.ledgerAccountId(), balance);
            saves++;
        }

        int saves() {
            return saves;
        }
    }

    /** Behaves like the real primary key: the first claim wins, every later one loses. */
    private static final class InMemoryAppliedPostings implements AppliedPostings {

        private final Set<String> claimed = new HashSet<>();

        @Override
        public boolean claim(String ledgerAccountId, String transactionId) {
            return claimed.add(ledgerAccountId + '|' + transactionId);
        }
    }
}
