package io.lara.ledger.domain;

import static io.lara.ledger.domain.EntrySide.CREDIT;
import static io.lara.ledger.domain.EntrySide.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AccountClassTest {

    /**
     * The rule that makes this a ledger rather than a table of numbers. Getting it backwards would
     * report a customer's savings as a debt they owe.
     */
    @Test
    void assets_and_expenses_are_debit_balances_everything_else_is_credit() {
        assertThat(AccountClass.ASSET.normalSide()).isEqualTo(DEBIT);
        assertThat(AccountClass.EXPENSE.normalSide()).isEqualTo(DEBIT);

        assertThat(AccountClass.LIABILITY.normalSide()).isEqualTo(CREDIT);
        assertThat(AccountClass.EQUITY.normalSide()).isEqualTo(CREDIT);
        assertThat(AccountClass.INCOME.normalSide()).isEqualTo(CREDIT);
    }

    /** A debit raises an asset and lowers a liability. Debit does not mean "increase". */
    @Test
    void a_debit_raises_an_asset_and_lowers_a_liability() {
        assertThat(AccountClass.ASSET.isIncreasedBy(DEBIT)).isTrue();
        assertThat(AccountClass.ASSET.isIncreasedBy(CREDIT)).isFalse();

        assertThat(AccountClass.LIABILITY.isIncreasedBy(CREDIT)).isTrue();
        assertThat(AccountClass.LIABILITY.isIncreasedBy(DEBIT)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(AccountClass.class)
    void the_effect_of_the_two_sides_is_always_opposite(AccountClass accountClass) {
        assertThat(accountClass.effectOf(DEBIT)).isEqualTo(-accountClass.effectOf(CREDIT));
        assertThat(Math.abs(accountClass.effectOf(DEBIT))).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(AccountClass.class)
    void a_posting_on_the_normal_side_always_increases_the_balance(AccountClass accountClass) {
        assertThat(accountClass.isIncreasedBy(accountClass.normalSide())).isTrue();
        assertThat(accountClass.effectOf(accountClass.normalSide())).isEqualTo(1);
        assertThat(accountClass.effectOf(accountClass.normalSide().opposite())).isEqualTo(-1);
    }

    @Test
    void the_two_sides_are_each_other_opposite() {
        assertThat(DEBIT.opposite()).isEqualTo(CREDIT);
        assertThat(CREDIT.opposite()).isEqualTo(DEBIT);
        assertThat(DEBIT.opposite().opposite()).isEqualTo(DEBIT);
    }
}
