package io.lara.ledger.domain;

import static io.lara.ledger.domain.PostingLeg.credit;
import static io.lara.ledger.domain.PostingLeg.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AccountBalanceTest {

    private static final Instant MONDAY = Instant.parse("2026-09-21T10:00:00Z");
    private static final Instant TUESDAY = Instant.parse("2026-09-22T10:00:00Z");
    private static final Instant WEDNESDAY = Instant.parse("2026-09-23T10:00:00Z");

    private static final LedgerAccount DEPOSIT =
            LedgerAccount.of("CUSTOMER000001", AccountClass.LIABILITY, "EUR");
    private static final LedgerAccount CASH = SystemAccount.CASH.in("EUR");
    private static final LedgerAccount FEE_INCOME = SystemAccount.FEE_INCOME.in("EUR");

    private static Money eur(long minorUnits) {
        return Money.of(minorUnits, "EUR");
    }

    /** Funding: the bank's cash rises and it now owes the customer the same amount. */
    private static LedgerTransaction funding(Instant at, long minorUnits) {
        return LedgerTransaction.of(TransactionId.newId(), at,
                debit(CASH.id(), eur(minorUnits)),
                credit(DEPOSIT.id(), eur(minorUnits)));
    }

    @Nested
    @DisplayName("folding postings")
    class Folding {

        @Test
        void an_account_with_no_postings_is_zero() {
            AccountBalance balance = AccountBalance.asAt(DEPOSIT, List.of(), WEDNESDAY);

            assertThat(balance.amount()).isEqualTo(eur(0));
            assertThat(balance.isZero()).isTrue();
            assertThat(balance.asOf()).isEqualTo(WEDNESDAY);
        }

        /**
         * The rule that separates a ledger from a counter. A credit raises a liability, so the
         * customer's deposit grows. The same posting lowers an asset.
         */
        @Test
        void a_credit_raises_a_liability_and_a_debit_raises_an_asset() {
            List<LedgerTransaction> book = List.of(funding(MONDAY, 50000));

            assertThat(AccountBalance.asAt(DEPOSIT, book, WEDNESDAY).amount()).isEqualTo(eur(50000));
            assertThat(AccountBalance.asAt(CASH, book, WEDNESDAY).amount()).isEqualTo(eur(50000));
        }

        @Test
        void a_debit_lowers_a_liability() {
            List<LedgerTransaction> book = List.of(
                    funding(MONDAY, 50000),
                    LedgerTransaction.of(TransactionId.newId(), TUESDAY,
                            debit(DEPOSIT.id(), eur(12500)),
                            credit(CASH.id(), eur(12500))));

            assertThat(AccountBalance.asAt(DEPOSIT, book, WEDNESDAY).amount()).isEqualTo(eur(37500));
            assertThat(AccountBalance.asAt(CASH, book, WEDNESDAY).amount()).isEqualTo(eur(37500));
        }

        /** A sender pays an amount and a fee, so one transaction debits the account twice. */
        @Test
        void sums_every_leg_when_one_transaction_touches_an_account_more_than_once() {
            List<LedgerTransaction> book = List.of(
                    funding(MONDAY, 50000),
                    LedgerTransaction.of(TransactionId.newId(), TUESDAY,
                            debit(DEPOSIT.id(), eur(10000)),
                            debit(DEPOSIT.id(), eur(50)),
                            credit(CASH.id(), eur(10000)),
                            credit(FEE_INCOME.id(), eur(50))));

            assertThat(AccountBalance.asAt(DEPOSIT, book, WEDNESDAY).amount()).isEqualTo(eur(39950));
            assertThat(AccountBalance.asAt(FEE_INCOME, book, WEDNESDAY).amount()).isEqualTo(eur(50));
        }

        @Test
        void ignores_transactions_that_do_not_touch_the_account() {
            List<LedgerTransaction> book = List.of(
                    LedgerTransaction.of(TransactionId.newId(), MONDAY,
                            debit(CASH.id(), eur(999)),
                            credit(FEE_INCOME.id(), eur(999))));

            assertThat(AccountBalance.asAt(DEPOSIT, book, WEDNESDAY).amount()).isEqualTo(eur(0));
        }

        @Test
        void does_not_depend_on_the_order_transactions_are_supplied_in() {
            LedgerTransaction first = funding(MONDAY, 50000);
            LedgerTransaction second = LedgerTransaction.of(TransactionId.newId(), TUESDAY,
                    debit(DEPOSIT.id(), eur(12500)),
                    credit(CASH.id(), eur(12500)));

            Money forwards = AccountBalance.asAt(DEPOSIT, List.of(first, second), WEDNESDAY).amount();
            Money backwards = AccountBalance.asAt(DEPOSIT, List.of(second, first), WEDNESDAY).amount();

            assertThat(forwards).isEqualTo(backwards).isEqualTo(eur(37500));
        }
    }

    @Nested
    @DisplayName("as at a past instant")
    class PointInTime {

        private final List<LedgerTransaction> book = List.of(
                funding(MONDAY, 50000),
                LedgerTransaction.of(TransactionId.newId(), TUESDAY,
                        debit(DEPOSIT.id(), eur(12500)),
                        credit(CASH.id(), eur(12500))),
                LedgerTransaction.of(TransactionId.newId(), WEDNESDAY,
                        debit(DEPOSIT.id(), eur(7500)),
                        credit(CASH.id(), eur(7500))));

        /** The question an auditor asks, and the reason balances are derived rather than stored. */
        @Test
        void reports_the_balance_as_it_stood_on_each_day() {
            assertThat(AccountBalance.asAt(DEPOSIT, book, MONDAY).amount()).isEqualTo(eur(50000));
            assertThat(AccountBalance.asAt(DEPOSIT, book, TUESDAY).amount()).isEqualTo(eur(37500));
            assertThat(AccountBalance.asAt(DEPOSIT, book, WEDNESDAY).amount()).isEqualTo(eur(30000));
        }

        @Test
        void is_zero_before_anything_was_posted() {
            assertThat(AccountBalance.asAt(DEPOSIT, book, MONDAY.minusSeconds(1)).amount()).isEqualTo(eur(0));
        }

        @Test
        void includes_a_posting_made_at_exactly_that_instant() {
            assertThat(AccountBalance.asAt(DEPOSIT, book, MONDAY).amount()).isEqualTo(eur(50000));
            assertThat(AccountBalance.asAt(DEPOSIT, book, MONDAY.minusNanos(1)).amount()).isEqualTo(eur(0));
        }

        @Test
        void carries_the_instant_it_was_taken_at() {
            assertThat(AccountBalance.asAt(DEPOSIT, book, TUESDAY).asOf()).isEqualTo(TUESDAY);
        }
    }

    @Nested
    @DisplayName("sides and contrary balances")
    class Sides {

        @Test
        void a_positive_balance_sits_on_its_account_normal_side() {
            List<LedgerTransaction> book = List.of(funding(MONDAY, 50000));

            assertThat(AccountBalance.asAt(DEPOSIT, book, WEDNESDAY).side()).isEqualTo(EntrySide.CREDIT);
            assertThat(AccountBalance.asAt(CASH, book, WEDNESDAY).side()).isEqualTo(EntrySide.DEBIT);
        }

        /** An overdrawn deposit: the customer owes the bank, so the liability has gone contrary. */
        @Test
        void a_negative_balance_reports_the_opposite_side() {
            List<LedgerTransaction> book = List.of(
                    LedgerTransaction.of(TransactionId.newId(), MONDAY,
                            debit(DEPOSIT.id(), eur(2500)),
                            credit(CASH.id(), eur(2500))));

            AccountBalance balance = AccountBalance.asAt(DEPOSIT, book, WEDNESDAY);

            assertThat(balance.amount()).isEqualTo(eur(-2500));
            assertThat(balance.side()).isEqualTo(EntrySide.DEBIT);
            assertThat(balance.isContrary()).isTrue();
        }
    }

    @Nested
    @DisplayName("the ledger as a whole")
    class WholeLedger {

        /**
         * Every balance across every account must sum to zero. If it does not, money has been
         * created or destroyed. This is the in-memory form of the trial balance in #15.
         */
        @Test
        void every_balance_summed_across_all_accounts_is_zero() {
            List<LedgerTransaction> book = List.of(
                    funding(MONDAY, 50000),
                    LedgerTransaction.of(TransactionId.newId(), TUESDAY,
                            debit(DEPOSIT.id(), eur(10000)),
                            debit(DEPOSIT.id(), eur(50)),
                            credit(CASH.id(), eur(10000)),
                            credit(FEE_INCOME.id(), eur(50))));

            // Signed by normal side: assets debit-positive, liabilities and income credit-positive.
            Money assets = AccountBalance.asAt(CASH, book, WEDNESDAY).amount();
            Money liabilities = AccountBalance.asAt(DEPOSIT, book, WEDNESDAY).amount();
            Money income = AccountBalance.asAt(FEE_INCOME, book, WEDNESDAY).amount();

            assertThat(assets).isEqualTo(liabilities.plus(income));
        }
    }

    @Nested
    @DisplayName("guards")
    class Guards {

        @Test
        void refuses_to_post_a_foreign_currency_to_an_account() {
            LedgerAccount yenAccount = LedgerAccount.of("CUSTOMER000009", AccountClass.LIABILITY, "JPY");
            List<LedgerTransaction> book = List.of(
                    LedgerTransaction.of(TransactionId.newId(), MONDAY,
                            debit(CASH.id(), eur(100)),
                            credit(yenAccount.id(), eur(100))));

            assertThatThrownBy(() -> AccountBalance.asAt(yenAccount, book, WEDNESDAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("EUR")
                    .hasMessageContaining("JPY");
        }

        @Test
        void refuses_a_balance_in_the_wrong_currency() {
            assertThatThrownBy(() -> new AccountBalance(DEPOSIT, Money.of(1, "USD"), MONDAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("EUR");
        }

        @Test
        void rejects_missing_arguments() {
            assertThatThrownBy(() -> AccountBalance.asAt(null, List.of(), MONDAY))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> AccountBalance.asAt(DEPOSIT, null, MONDAY))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> AccountBalance.asAt(DEPOSIT, List.of(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
