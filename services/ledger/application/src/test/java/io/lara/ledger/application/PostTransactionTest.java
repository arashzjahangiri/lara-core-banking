package io.lara.ledger.application;

import static io.lara.ledger.domain.PostingLeg.credit;
import static io.lara.ledger.domain.PostingLeg.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.lara.ledger.domain.AccountClass;
import io.lara.ledger.domain.AccountId;
import io.lara.ledger.domain.LedgerAccount;
import io.lara.ledger.domain.LedgerTransaction;
import io.lara.ledger.domain.Money;
import io.lara.ledger.domain.SystemAccount;
import io.lara.ledger.domain.TransactionReference;

/**
 * Built entirely with {@code new} and two hand-written fakes. No container starts, no mocking
 * framework is on the classpath, and the whole class runs in milliseconds — which is the return on
 * keeping this module annotation-free.
 */
class PostTransactionTest {

    private static final Instant POSTED_AT = Instant.parse("2026-09-29T12:00:00Z");

    private static final LedgerAccount SENDER =
            LedgerAccount.of("CUSTOMER000001", AccountClass.LIABILITY, "EUR");
    private static final LedgerAccount RECEIVER =
            LedgerAccount.of("CUSTOMER000002", AccountClass.LIABILITY, "EUR");
    private static final LedgerAccount FEE_INCOME = SystemAccount.FEE_INCOME.in("EUR");
    private static final LedgerAccount YEN_ACCOUNT =
            LedgerAccount.of("CUSTOMER000009", AccountClass.LIABILITY, "JPY");

    private InMemoryLedgerAccounts accounts;
    private RecordingLedgerTransactions transactions;
    private PostTransaction postTransaction;

    private static Money eur(long minorUnits) {
        return Money.of(minorUnits, "EUR");
    }

    private static final AtomicInteger REFERENCES = new AtomicInteger();

    /** A distinct reference per transaction, so idempotency does not collapse unrelated tests. */
    private static TransactionReference nextReference() {
        return TransactionReference.of("TEST-" + REFERENCES.incrementAndGet());
    }

    @BeforeEach
    void setUp() {
        accounts = new InMemoryLedgerAccounts().with(SENDER, RECEIVER, FEE_INCOME, YEN_ACCOUNT);
        transactions = new RecordingLedgerTransactions();
        postTransaction = new PostTransaction(
                accounts,
                transactions,
                PostingLimits.of(eur(1_000_00), Money.of(100_000, "JPY")),
                Clock.fixed(POSTED_AT, ZoneOffset.UTC));
    }

    @Nested
    @DisplayName("recording a transaction")
    class Recording {

        @Test
        void appends_a_balanced_transfer_and_returns_it() {
            LedgerTransaction posted = postTransaction.post(PostTransactionCommand.of(nextReference(), 
                    debit(SENDER.id(), eur(10000)),
                    credit(RECEIVER.id(), eur(10000))));

            assertThat(transactions.appended()).containsExactly(posted);
            assertThat(posted.totalDebits()).isEqualTo(eur(10000));
            assertThat(posted.legs()).hasSize(2);
        }

        @Test
        void accepts_a_transfer_carrying_a_fee() {
            LedgerTransaction posted = postTransaction.post(PostTransactionCommand.of(nextReference(), 
                    debit(SENDER.id(), eur(10000)),
                    credit(RECEIVER.id(), eur(9950)),
                    credit(FEE_INCOME.id(), eur(50))));

            assertThat(posted.legs()).hasSize(3);
            assertThat(transactions.appended()).hasSize(1);
        }

        /** The ledger assigns the instant, from the injected clock. A caller's clock is not evidence. */
        @Test
        void stamps_the_instant_from_the_injected_clock() {
            LedgerTransaction posted = postTransaction.post(PostTransactionCommand.of(nextReference(), 
                    debit(SENDER.id(), eur(100)),
                    credit(RECEIVER.id(), eur(100))));

            assertThat(posted.occurredAt()).isEqualTo(POSTED_AT);
        }

        @Test
        void assigns_a_distinct_identity_to_every_transaction() {
            LedgerTransaction first = postTransaction.post(PostTransactionCommand.of(nextReference(), 
                    debit(SENDER.id(), eur(100)), credit(RECEIVER.id(), eur(100))));
            LedgerTransaction second = postTransaction.post(PostTransactionCommand.of(nextReference(), 
                    debit(SENDER.id(), eur(100)), credit(RECEIVER.id(), eur(100))));

            assertThat(first.id()).isNotEqualTo(second.id());
            assertThat(transactions.appended()).hasSize(2);
        }

        @Test
        void posts_in_another_currency_when_the_accounts_and_limits_allow_it() {
            LedgerAccount otherYen = LedgerAccount.of("CUSTOMER000010", AccountClass.LIABILITY, "JPY");
            accounts.with(otherYen);

            LedgerTransaction posted = postTransaction.post(PostTransactionCommand.of(nextReference(), 
                    debit(YEN_ACCOUNT.id(), Money.of(5000, "JPY")),
                    credit(otherYen.id(), Money.of(5000, "JPY"))));

            assertThat(posted.currency().getCurrencyCode()).isEqualTo("JPY");
        }
    }

    @Nested
    @DisplayName("rejection, and writing nothing when rejected")
    class Rejection {

        @Test
        void refuses_an_account_that_is_not_open() {
            AccountId missing = AccountId.of("CUSTOMER999999");

            assertThatThrownBy(() -> postTransaction.post(PostTransactionCommand.of(nextReference(), 
                    debit(missing, eur(100)),
                    credit(RECEIVER.id(), eur(100)))))
                    .isInstanceOf(PostingRejectedException.UnknownAccount.class)
                    .hasMessageContaining("CUSTOMER999999");

            assertThat(transactions.wroteNothing()).isTrue();
        }

        @Test
        void refuses_a_currency_the_account_cannot_hold() {
            assertThatThrownBy(() -> postTransaction.post(PostTransactionCommand.of(nextReference(), 
                    debit(YEN_ACCOUNT.id(), eur(100)),
                    credit(RECEIVER.id(), eur(100)))))
                    .isInstanceOf(PostingRejectedException.AccountCurrencyMismatch.class)
                    .hasMessageContaining("JPY")
                    .hasMessageContaining("EUR");

            assertThat(transactions.wroteNothing()).isTrue();
        }

        @Test
        void refuses_an_amount_over_the_limit() {
            assertThatThrownBy(() -> postTransaction.post(PostTransactionCommand.of(nextReference(), 
                    debit(SENDER.id(), eur(1_000_01)),
                    credit(RECEIVER.id(), eur(1_000_01)))))
                    .isInstanceOf(PostingRejectedException.PostingLimitExceeded.class);

            assertThat(transactions.wroteNothing()).isTrue();
        }

        @Test
        void accepts_an_amount_exactly_at_the_limit() {
            LedgerTransaction posted = postTransaction.post(PostTransactionCommand.of(nextReference(), 
                    debit(SENDER.id(), eur(1_000_00)),
                    credit(RECEIVER.id(), eur(1_000_00))));

            assertThat(transactions.appended()).containsExactly(posted);
        }

        /** The domain rejects what is impossible, before any port is touched. */
        @Test
        void refuses_legs_that_do_not_balance_without_consulting_the_ports() {
            assertThatThrownBy(() -> postTransaction.post(PostTransactionCommand.of(nextReference(), 
                    debit(SENDER.id(), eur(10000)),
                    credit(RECEIVER.id(), eur(9950)))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not balance");

            assertThat(transactions.wroteNothing()).isTrue();
        }

        @Test
        void refuses_a_null_command() {
            assertThatThrownBy(() -> postTransaction.post(null))
                    .isInstanceOf(NullPointerException.class);
            assertThat(transactions.wroteNothing()).isTrue();
        }
    }

    @Nested
    @DisplayName("limits")
    class Limits {

        /** Not configured means refused, never unlimited. A typo must not remove a control. */
        @Test
        void a_currency_with_no_configured_limit_is_refused() {
            LedgerAccount poundsOne = LedgerAccount.of("CUSTOMER000020", AccountClass.LIABILITY, "GBP");
            LedgerAccount poundsTwo = LedgerAccount.of("CUSTOMER000021", AccountClass.LIABILITY, "GBP");
            accounts.with(poundsOne, poundsTwo);

            assertThatThrownBy(() -> postTransaction.post(PostTransactionCommand.of(nextReference(), 
                    debit(poundsOne.id(), Money.of(1, "GBP")),
                    credit(poundsTwo.id(), Money.of(1, "GBP")))))
                    .isInstanceOf(PostingRejectedException.PostingLimitExceeded.class);

            assertThat(transactions.wroteNothing()).isTrue();
        }

        @Test
        void a_limit_must_be_stated_in_its_own_currency() {
            assertThatThrownBy(() -> new PostingLimits(
                    java.util.Map.of(java.util.Currency.getInstance("EUR"), Money.of(100, "USD"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("EUR");
        }

        @Test
        void a_limit_must_be_positive() {
            assertThatThrownBy(() -> PostingLimits.of(eur(0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }

        @Test
        void the_limit_applies_to_the_total_moved_not_to_each_leg() {
            // Three legs of 400.00 each on the debit side total 1200.00, over the 1000.00 limit,
            // even though no single leg is.
            LedgerAccount third = LedgerAccount.of("CUSTOMER000003", AccountClass.LIABILITY, "EUR");
            accounts.with(third);

            assertThatThrownBy(() -> postTransaction.post(PostTransactionCommand.of(nextReference(), 
                    debit(SENDER.id(), eur(400_00)),
                    debit(RECEIVER.id(), eur(400_00)),
                    debit(third.id(), eur(400_00)),
                    credit(FEE_INCOME.id(), eur(1_200_00)))))
                    .isInstanceOf(PostingRejectedException.PostingLimitExceeded.class);
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        void refuses_to_be_built_without_its_collaborators() {
            PostingLimits limits = PostingLimits.of(eur(100));
            Clock clock = Clock.fixed(POSTED_AT, ZoneOffset.UTC);

            assertThatThrownBy(() -> new PostTransaction(null, transactions, limits, clock))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new PostTransaction(accounts, null, limits, clock))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new PostTransaction(accounts, transactions, null, clock))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new PostTransaction(accounts, transactions, limits, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        private static final TransactionReference SAME = TransactionReference.of("PAYMENT-4417");

        /**
         * The property that matters most. Networks time out and callers re-send; without this, a
         * retried transfer moves the money twice and the ledger is wrong in the one way it must
         * never be.
         */
        @Test
        void a_retry_returns_the_original_and_moves_no_money_again() {
            LedgerTransaction first = postTransaction.post(PostTransactionCommand.of(SAME,
                    debit(SENDER.id(), eur(10000)),
                    credit(RECEIVER.id(), eur(10000))));

            LedgerTransaction retry = postTransaction.post(PostTransactionCommand.of(SAME,
                    debit(SENDER.id(), eur(10000)),
                    credit(RECEIVER.id(), eur(10000))));

            assertThat(retry).isEqualTo(first);
            assertThat(retry.id()).isEqualTo(first.id());
            assertThat(retry.occurredAt()).isEqualTo(first.occurredAt());
            assertThat(transactions.appended()).hasSize(1);
        }

        @Test
        void a_retry_is_still_answered_after_many_attempts() {
            LedgerTransaction first = postTransaction.post(PostTransactionCommand.of(SAME,
                    debit(SENDER.id(), eur(500)), credit(RECEIVER.id(), eur(500))));

            for (int attempt = 0; attempt < 5; attempt++) {
                assertThat(postTransaction.post(PostTransactionCommand.of(SAME,
                        debit(SENDER.id(), eur(500)), credit(RECEIVER.id(), eur(500))))).isEqualTo(first);
            }
            assertThat(transactions.appended()).hasSize(1);
        }

        /**
         * Different legs under one reference is a caller bug, not a retry. Returning the original
         * would hide it and silently drop the second movement.
         */
        @Test
        void the_same_reference_with_different_legs_is_refused() {
            postTransaction.post(PostTransactionCommand.of(SAME,
                    debit(SENDER.id(), eur(10000)),
                    credit(RECEIVER.id(), eur(10000))));

            assertThatThrownBy(() -> postTransaction.post(PostTransactionCommand.of(SAME,
                    debit(SENDER.id(), eur(2500)),
                    credit(RECEIVER.id(), eur(2500)))))
                    .isInstanceOf(PostingRejectedException.ReferenceReused.class)
                    .hasMessageContaining("PAYMENT-4417");

            assertThat(transactions.appended()).hasSize(1);
        }

        @Test
        void different_references_post_separately() {
            postTransaction.post(PostTransactionCommand.of("PAYMENT-1",
                    debit(SENDER.id(), eur(100)), credit(RECEIVER.id(), eur(100))));
            postTransaction.post(PostTransactionCommand.of("PAYMENT-2",
                    debit(SENDER.id(), eur(100)), credit(RECEIVER.id(), eur(100))));

            assertThat(transactions.appended()).hasSize(2);
        }

        /** A retry is answered before the limit is consulted, so tightening policy cannot strand one. */
        @Test
        void a_retry_is_answered_even_if_the_limit_has_since_been_lowered() {
            LedgerTransaction first = postTransaction.post(PostTransactionCommand.of(SAME,
                    debit(SENDER.id(), eur(900_00)),
                    credit(RECEIVER.id(), eur(900_00))));

            PostTransaction stricter = new PostTransaction(accounts, transactions,
                    PostingLimits.of(eur(100_00)), Clock.fixed(POSTED_AT, ZoneOffset.UTC));

            assertThat(stricter.post(PostTransactionCommand.of(SAME,
                    debit(SENDER.id(), eur(900_00)),
                    credit(RECEIVER.id(), eur(900_00))))).isEqualTo(first);
            assertThat(transactions.appended()).hasSize(1);
        }

        @Test
        void a_command_without_a_reference_is_refused() {
            assertThatThrownBy(() -> PostTransactionCommand.of((TransactionReference) null,
                    debit(SENDER.id(), eur(100)), credit(RECEIVER.id(), eur(100))))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
