package io.lara.ledger.domain;

import static io.lara.ledger.domain.PostingLeg.credit;
import static io.lara.ledger.domain.PostingLeg.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LedgerTransactionTest {

    private static final Instant NOW = Instant.parse("2026-09-29T10:15:30Z");
    private static final String SENDER = "CUSTOMER000001";
    private static final String RECEIVER = "CUSTOMER000002";
    private static final String FEE_INCOME = "BANK.FEE.INCOME.EUR";

    private static Money eur(long minorUnits) {
        return Money.of(minorUnits, "EUR");
    }

    private static LedgerTransaction transaction(PostingLeg... legs) {
        return LedgerTransaction.of(TransactionId.newId(), NOW, legs);
    }

    @Nested
    @DisplayName("a leg")
    class Leg {

        @Test
        void carries_an_account_a_side_and_a_positive_amount() {
            PostingLeg leg = debit(SENDER, eur(10000));

            assertThat(leg.account()).isEqualTo(AccountId.of(SENDER));
            assertThat(leg.side()).isEqualTo(EntrySide.DEBIT);
            assertThat(leg.amount()).isEqualTo(eur(10000));
            assertThat(leg.isDebit()).isTrue();
            assertThat(leg.isCredit()).isFalse();
        }

        /** Direction lives in the side, never in the sign, so a negative leg is meaningless. */
        @Test
        void refuses_a_negative_or_zero_amount() {
            assertThatThrownBy(() -> debit(SENDER, eur(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
            assertThatThrownBy(() -> credit(SENDER, eur(0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }

        @Test
        void signs_itself_for_arithmetic() {
            assertThat(debit(SENDER, eur(500)).signedAmount()).isEqualTo(eur(500));
            assertThat(credit(SENDER, eur(500)).signedAmount()).isEqualTo(eur(-500));
        }

        @Test
        void produces_a_contra_on_the_opposite_side() {
            PostingLeg contra = debit(SENDER, eur(500)).contra();

            assertThat(contra.side()).isEqualTo(EntrySide.CREDIT);
            assertThat(contra.account()).isEqualTo(AccountId.of(SENDER));
            assertThat(contra.amount()).isEqualTo(eur(500));
        }

        @Test
        void rejects_missing_parts() {
            assertThatThrownBy(() -> new PostingLeg(null, EntrySide.DEBIT, eur(1)))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new PostingLeg(AccountId.of("X"), null, eur(1)))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new PostingLeg(AccountId.of("X"), EntrySide.DEBIT, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("the balancing invariant")
    class Balancing {

        @Test
        void accepts_a_simple_two_leg_transfer() {
            LedgerTransaction transfer = transaction(
                    debit(SENDER, eur(10000)),
                    credit(RECEIVER, eur(10000)));

            assertThat(transfer.legs()).hasSize(2);
            assertThat(transfer.totalDebits()).isEqualTo(eur(10000));
            assertThat(transfer.totalCredits()).isEqualTo(eur(10000));
        }

        /** The reason the model is N legs rather than a pair: a fee needs a third destination. */
        @Test
        void accepts_a_transfer_carrying_a_fee_as_three_legs() {
            LedgerTransaction withFee = transaction(
                    debit(SENDER, eur(10000)),
                    credit(RECEIVER, eur(9950)),
                    credit(FEE_INCOME, eur(50)));

            assertThat(withFee.legs()).hasSize(3);
            assertThat(withFee.totalDebits()).isEqualTo(withFee.totalCredits());
        }

        @Test
        void rejects_a_transaction_that_does_not_balance() {
            assertThatThrownBy(() -> transaction(
                    debit(SENDER, eur(10000)),
                    credit(RECEIVER, eur(9950))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not balance")
                    .hasMessageContaining("EUR 100.00")
                    .hasMessageContaining("EUR 99.50");
        }

        @Test
        void rejects_a_transaction_that_is_all_debits() {
            assertThatThrownBy(() -> transaction(
                    debit(SENDER, eur(100)),
                    debit(RECEIVER, eur(100))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not balance");
        }

        @Test
        void rejects_fewer_than_two_legs() {
            assertThatThrownBy(() -> transaction(debit(SENDER, eur(100))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least 2 legs");
            assertThatThrownBy(() -> LedgerTransaction.of(TransactionId.newId(), NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejects_legs_in_different_currencies() {
            assertThatThrownBy(() -> transaction(
                    debit(SENDER, eur(10000)),
                    credit(RECEIVER, Money.of(10000, "USD"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("one currency");
        }

        @Test
        void allows_the_same_account_on_more_than_one_leg() {
            // A sender pays the amount and the fee: two debits on one account.
            LedgerTransaction withFee = transaction(
                    debit(SENDER, eur(10000)),
                    debit(SENDER, eur(50)),
                    credit(RECEIVER, eur(10000)),
                    credit(FEE_INCOME, eur(50)));

            assertThat(withFee.legsFor(AccountId.of(SENDER))).hasSize(2);
            assertThat(withFee.totalDebits()).isEqualTo(eur(10050));
        }
    }

    @Nested
    @DisplayName("immutability")
    class Immutability {

        @Test
        void copies_the_legs_it_was_given() {
            List<PostingLeg> mutable = new ArrayList<>(List.of(
                    debit(SENDER, eur(100)),
                    credit(RECEIVER, eur(100))));

            LedgerTransaction transaction = new LedgerTransaction(TransactionId.newId(), NOW, mutable);
            mutable.add(debit(FEE_INCOME, eur(999)));

            assertThat(transaction.legs()).hasSize(2);
        }

        @Test
        void exposes_legs_that_cannot_be_modified() {
            LedgerTransaction transaction = transaction(
                    debit(SENDER, eur(100)),
                    credit(RECEIVER, eur(100)));

            assertThatThrownBy(() -> transaction.legs().add(debit(FEE_INCOME, eur(1))))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void rejects_a_null_leg() {
            List<PostingLeg> withNull = new ArrayList<>();
            withNull.add(debit(SENDER, eur(100)));
            withNull.add(null);

            assertThatThrownBy(() -> new LedgerTransaction(TransactionId.newId(), NOW, withNull))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("reversal")
    class Reversal {

        /** Correcting a posting means writing its opposite, never editing or deleting it. */
        @Test
        void a_contra_transaction_mirrors_every_leg() {
            LedgerTransaction original = transaction(
                    debit(SENDER, eur(10000)),
                    credit(RECEIVER, eur(9950)),
                    credit(FEE_INCOME, eur(50)));

            TransactionId reversalId = TransactionId.newId();
            LedgerTransaction reversal = original.contra(reversalId, NOW.plusSeconds(60));

            assertThat(reversal.id()).isEqualTo(reversalId);
            assertThat(reversal.legs()).hasSize(3);
            assertThat(reversal.legs().get(0).side()).isEqualTo(EntrySide.CREDIT);
            assertThat(reversal.legs().get(1).side()).isEqualTo(EntrySide.DEBIT);
            assertThat(reversal.totalDebits()).isEqualTo(original.totalCredits());
            assertThat(reversal.totalCredits()).isEqualTo(original.totalDebits());
        }

        @Test
        void a_transaction_and_its_contra_cancel_out_per_account() {
            LedgerTransaction original = transaction(
                    debit(SENDER, eur(10000)),
                    credit(RECEIVER, eur(10000)));
            LedgerTransaction reversal = original.contra(TransactionId.newId(), NOW.plusSeconds(1));

            Money netForSender = netSigned(List.of(original, reversal), AccountId.of(SENDER));
            assertThat(netForSender).isEqualTo(eur(0));
        }

        private static Money netSigned(List<LedgerTransaction> transactions, AccountId account) {
            return transactions.stream()
                    .flatMap(t -> t.legsFor(account).stream())
                    .map(PostingLeg::signedAmount)
                    .reduce(Money::plus)
                    .orElseThrow();
        }
    }

    @Nested
    @DisplayName("the trial balance")
    class TrialBalance {

        /**
         * The strongest statement the ledger can make: across any set of transactions, total debits
         * equal total credits. If that ever diverges, money has been created or destroyed.
         */
        @Test
        void holds_across_many_transactions() {
            List<LedgerTransaction> book = List.of(
                    transaction(debit("BANK.CASH.EUR", eur(50000)), credit(SENDER, eur(50000))),
                    transaction(debit(SENDER, eur(10000)), credit(RECEIVER, eur(9950)), credit(FEE_INCOME, eur(50))),
                    transaction(debit(SENDER, eur(250)), credit(FEE_INCOME, eur(250))),
                    transaction(debit(RECEIVER, eur(9950)), credit("BANK.CLEARING.EUR", eur(9950))));

            Money debits = book.stream().map(LedgerTransaction::totalDebits).reduce(Money::plus).orElseThrow();
            Money credits = book.stream().map(LedgerTransaction::totalCredits).reduce(Money::plus).orElseThrow();

            assertThat(debits).isEqualTo(credits);

            Money netOfEveryLeg = book.stream()
                    .flatMap(t -> t.legs().stream())
                    .map(PostingLeg::signedAmount)
                    .reduce(Money::plus)
                    .orElseThrow();
            assertThat(netOfEveryLeg).isEqualTo(eur(0));
        }
    }
}
