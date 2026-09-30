package io.lara.ledger.application;

import static io.lara.ledger.domain.PostingLeg.credit;
import static io.lara.ledger.domain.PostingLeg.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.lara.ledger.domain.AccountClass;
import io.lara.ledger.domain.AccountId;
import io.lara.ledger.domain.EntrySide;
import io.lara.ledger.domain.LedgerAccount;
import io.lara.ledger.domain.LedgerTransaction;
import io.lara.ledger.domain.Money;
import io.lara.ledger.domain.PostingLeg;
import io.lara.ledger.domain.SystemAccount;
import io.lara.ledger.domain.TransactionId;

class ReverseTransactionTest {

    private static final Instant POSTED_AT = Instant.parse("2026-10-01T09:00:00Z");
    private static final Instant REVERSED_AT = Instant.parse("2026-10-01T15:30:00Z");

    private static final LedgerAccount SENDER =
            LedgerAccount.of("CUSTOMER000001", AccountClass.LIABILITY, "EUR");
    private static final LedgerAccount RECEIVER =
            LedgerAccount.of("CUSTOMER000002", AccountClass.LIABILITY, "EUR");
    private static final LedgerAccount FEE_INCOME = SystemAccount.FEE_INCOME.in("EUR");

    private RecordingLedgerTransactions transactions;
    private PostTransaction postTransaction;
    private ReverseTransaction reverseTransaction;

    private static Money eur(long minorUnits) {
        return Money.of(minorUnits, "EUR");
    }

    @BeforeEach
    void setUp() {
        transactions = new RecordingLedgerTransactions();
        postTransaction = new PostTransaction(
                new InMemoryLedgerAccounts().with(SENDER, RECEIVER, FEE_INCOME),
                transactions,
                PostingLimits.of(eur(1_000_00)),
                Clock.fixed(POSTED_AT, ZoneOffset.UTC));
        // A later clock, so a reversal is distinguishable from the original by its instant.
        reverseTransaction = new ReverseTransaction(transactions, Clock.fixed(REVERSED_AT, ZoneOffset.UTC));
    }

    private LedgerTransaction postTransfer(String reference) {
        return postTransaction.post(PostTransactionCommand.of(reference,
                debit(SENDER.id(), eur(10000)),
                credit(RECEIVER.id(), eur(9950)),
                credit(FEE_INCOME.id(), eur(50)))).transaction();
    }

    @Nested
    @DisplayName("reversing")
    class Reversing {

        @Test
        void posts_every_leg_on_the_opposite_side() {
            LedgerTransaction original = postTransfer("PAYMENT-1");

            LedgerTransaction reversal = reverseTransaction.reverse(original.id()).transaction();

            assertThat(reversal.legs()).hasSize(3);
            assertThat(reversal.legs()).extracting(PostingLeg::side)
                    .containsExactly(EntrySide.CREDIT, EntrySide.DEBIT, EntrySide.DEBIT);
            assertThat(reversal.totalDebits()).isEqualTo(original.totalCredits());
            assertThat(reversal.totalCredits()).isEqualTo(original.totalDebits());
        }

        /** The original must survive untouched. Correcting a ledger adds a row, it does not edit one. */
        @Test
        void leaves_the_original_exactly_as_it_was() {
            LedgerTransaction original = postTransfer("PAYMENT-1");
            List<PostingLeg> legsBefore = original.legs();

            reverseTransaction.reverse(original.id());

            LedgerTransaction stillThere = transactions.findById(original.id()).orElseThrow();
            assertThat(stillThere.legs()).isEqualTo(legsBefore);
            assertThat(stillThere.occurredAt()).isEqualTo(POSTED_AT);
            assertThat(transactions.appended()).hasSize(2);
        }

        @Test
        void stamps_the_reversal_with_the_time_it_was_reversed() {
            LedgerTransaction original = postTransfer("PAYMENT-1");

            LedgerTransaction reversal = reverseTransaction.reverse(original.id()).transaction();

            assertThat(reversal.occurredAt()).isEqualTo(REVERSED_AT);
            assertThat(reversal.id()).isNotEqualTo(original.id());
        }

        /** Derived from the original's, so the reversal is traceable and idempotent by construction. */
        @Test
        void derives_its_reference_from_the_original() {
            LedgerTransaction original = postTransfer("PAYMENT-1");

            LedgerTransaction reversal = reverseTransaction.reverse(original.id()).transaction();

            assertThat(reversal.reference().value()).isEqualTo("PAYMENT-1:reversal");
        }

        @Test
        void reports_that_this_call_recorded_it() {
            LedgerTransaction original = postTransfer("PAYMENT-1");

            assertThat(reverseTransaction.reverse(original.id()).created()).isTrue();
        }

        /** The whole point: after a reversal the account is exactly where it started. */
        @Test
        void a_transaction_and_its_reversal_net_to_zero_per_account() {
            LedgerTransaction original = postTransfer("PAYMENT-1");
            reverseTransaction.reverse(original.id());

            for (AccountId account : List.of(SENDER.id(), RECEIVER.id(), FEE_INCOME.id())) {
                Money net = transactions.appended().stream()
                        .flatMap(t -> t.legsFor(account).stream())
                        .map(PostingLeg::signedAmount)
                        .reduce(Money::plus)
                        .orElseThrow();
                assertThat(net).as("net movement on %s", account).isEqualTo(eur(0));
            }
        }
    }

    @Nested
    @DisplayName("refusing")
    class Refusing {

        @Test
        void refuses_a_transaction_that_does_not_exist() {
            TransactionId missing = TransactionId.newId();

            assertThatThrownBy(() -> reverseTransaction.reverse(missing))
                    .isInstanceOf(PostingRejectedException.UnknownTransaction.class)
                    .hasMessageContaining(missing.toString());

            assertThat(transactions.wroteNothing()).isTrue();
        }

        @Test
        void refuses_to_reverse_the_same_transaction_twice() {
            LedgerTransaction original = postTransfer("PAYMENT-1");
            reverseTransaction.reverse(original.id());
            int afterFirst = transactions.appended().size();

            assertThatThrownBy(() -> reverseTransaction.reverse(original.id()))
                    .isInstanceOf(PostingRejectedException.AlreadyReversed.class)
                    .hasMessageContaining("PAYMENT-1:reversal");

            assertThat(transactions.appended()).hasSize(afterFirst);
        }

        /**
         * Reversing a reversal would restore the original movement. That is a new posting, not an
         * undo, and should be asked for deliberately rather than arrived at by clicking twice.
         */
        @Test
        void refuses_to_reverse_a_reversal() {
            LedgerTransaction original = postTransfer("PAYMENT-1");
            LedgerTransaction reversal = reverseTransaction.reverse(original.id()).transaction();
            int afterReversal = transactions.appended().size();

            assertThatThrownBy(() -> reverseTransaction.reverse(reversal.id()))
                    .isInstanceOf(PostingRejectedException.AlreadyReversed.class);

            assertThat(transactions.appended()).hasSize(afterReversal);
        }

        @Test
        void refuses_a_null_id() {
            assertThatThrownBy(() -> reverseTransaction.reverse(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void refuses_to_be_built_without_its_collaborators() {
            Clock clock = Clock.fixed(REVERSED_AT, ZoneOffset.UTC);
            assertThatThrownBy(() -> new ReverseTransaction(null, clock))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ReverseTransaction(transactions, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
