package io.lara.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Currency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency JPY = Currency.getInstance("JPY");

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        void rejects_a_null_currency() {
            assertThatThrownBy(() -> new Money(100L, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("currency");
        }

        /** XXX is the ISO code for "no currency" and has no minor unit, so it cannot hold an amount. */
        @Test
        void rejects_a_currency_with_no_minor_unit() {
            Currency noCurrency = Currency.getInstance("XXX");
            assertThatThrownBy(() -> Money.of(100L, noCurrency))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("XXX");
        }

        /**
         * Postings carry a positive amount and an {@link EntrySide}, so a leg is never negative.
         * Balances are, though: an overdrawn account or a reversed position both go below zero.
         */
        @Test
        void accepts_negative_amounts_because_a_balance_can_go_below_zero() {
            assertThat(Money.of(-2500L, EUR).minorUnits()).isEqualTo(-2500L);
        }

        @Test
        void accepts_the_extremes_of_long() {
            assertThat(Money.of(Long.MAX_VALUE, EUR).minorUnits()).isEqualTo(Long.MAX_VALUE);
            assertThat(Money.of(Long.MIN_VALUE, EUR).minorUnits()).isEqualTo(Long.MIN_VALUE);
        }

        @Test
        void zero_is_zero_in_its_own_currency() {
            assertThat(Money.zero(EUR).isZero()).isTrue();
            assertThat(Money.zero("JPY")).isEqualTo(Money.of(0L, JPY));
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        void adds_and_subtracts_within_one_currency() {
            assertThat(Money.of(1234L, EUR).plus(Money.of(766L, EUR))).isEqualTo(Money.of(2000L, EUR));
            assertThat(Money.of(1234L, EUR).minus(Money.of(1300L, EUR))).isEqualTo(Money.of(-66L, EUR));
        }

        @Test
        void refuses_to_mix_currencies() {
            Money euros = Money.of(100L, EUR);
            Money dollars = Money.of(100L, USD);

            assertThatThrownBy(() -> euros.plus(dollars))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("EUR")
                    .hasMessageContaining("USD");
            assertThatThrownBy(() -> euros.minus(dollars)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> euros.compareTo(dollars)).isInstanceOf(IllegalArgumentException.class);
        }

        /** Silent wraparound would turn a very large credit into a debit. It must throw instead. */
        @Test
        void overflows_loudly_rather_than_wrapping() {
            Money max = Money.of(Long.MAX_VALUE, EUR);
            Money min = Money.of(Long.MIN_VALUE, EUR);

            assertThatThrownBy(() -> max.plus(Money.of(1L, EUR))).isInstanceOf(ArithmeticException.class);
            assertThatThrownBy(() -> min.minus(Money.of(1L, EUR))).isInstanceOf(ArithmeticException.class);
            assertThatThrownBy(() -> max.times(2L)).isInstanceOf(ArithmeticException.class);
            assertThatThrownBy(min::negated).isInstanceOf(ArithmeticException.class);
        }

        @Test
        void multiplies_and_negates() {
            assertThat(Money.of(250L, EUR).times(4L)).isEqualTo(Money.of(1000L, EUR));
            assertThat(Money.of(250L, EUR).negated()).isEqualTo(Money.of(-250L, EUR));
            assertThat(Money.of(-250L, EUR).abs()).isEqualTo(Money.of(250L, EUR));
            assertThat(Money.of(250L, EUR).abs()).isEqualTo(Money.of(250L, EUR));
        }

        @Test
        void reports_its_sign() {
            assertThat(Money.of(1L, EUR).isPositive()).isTrue();
            assertThat(Money.of(-1L, EUR).isNegative()).isTrue();
            assertThat(Money.zero(EUR).isPositive()).isFalse();
            assertThat(Money.zero(EUR).isNegative()).isFalse();
        }

        @Test
        void orders_amounts_of_the_same_currency() {
            assertThat(Money.of(100L, EUR)).isLessThan(Money.of(200L, EUR));
            assertThat(Money.of(-100L, EUR)).isLessThan(Money.zero(EUR));
            assertThat(Money.of(100L, EUR)).isEqualByComparingTo(Money.of(100L, EUR));
        }
    }

    @Nested
    @DisplayName("allocation")
    class Allocation {

        /** The classic case: a third of €10.00 cannot be represented, so the cent must go somewhere. */
        @Test
        void splitting_ten_euros_three_ways_loses_nothing() {
            Money[] shares = Money.of(1000L, EUR).allocate(3);

            assertThat(shares).containsExactly(
                    Money.of(334L, EUR), Money.of(333L, EUR), Money.of(333L, EUR));
            assertThat(sum(shares)).isEqualTo(Money.of(1000L, EUR));
        }

        @Test
        void splits_in_proportion_to_weights() {
            Money[] shares = Money.of(10000L, EUR).allocate(3L, 7L);

            assertThat(shares).containsExactly(Money.of(3000L, EUR), Money.of(7000L, EUR));
            assertThat(sum(shares)).isEqualTo(Money.of(10000L, EUR));
        }

        @Test
        void distributes_the_remainder_of_an_uneven_weighting() {
            Money[] shares = Money.of(100L, EUR).allocate(1L, 1L, 1L);

            assertThat(sum(shares)).isEqualTo(Money.of(100L, EUR));
            assertThat(shares).containsExactly(Money.of(34L, EUR), Money.of(33L, EUR), Money.of(33L, EUR));
        }

        /** A negative amount is a credit leg, and it must split without inventing a minor unit either. */
        @Test
        void allocates_negative_amounts_exactly() {
            Money[] shares = Money.of(-1000L, EUR).allocate(3);

            assertThat(sum(shares)).isEqualTo(Money.of(-1000L, EUR));
            assertThat(shares).containsExactly(
                    Money.of(-334L, EUR), Money.of(-333L, EUR), Money.of(-333L, EUR));
        }

        @ParameterizedTest(name = "{0} minor units into {1} parts sums back exactly")
        @CsvSource({
                "1000, 3", "1, 3", "0, 5", "-1, 7", "7, 7", "99999, 13",
                "-100000, 6", "5, 100", "123456789, 17"
        })
        void every_allocation_sums_back_to_the_original(long amount, int parts) {
            Money original = Money.of(amount, EUR);
            assertThat(sum(original.allocate(parts))).isEqualTo(original);
        }

        @Test
        void shares_never_differ_by_more_than_one_minor_unit() {
            Money[] shares = Money.of(1000L, EUR).allocate(7);

            long min = Arrays.stream(shares).mapToLong(Money::minorUnits).min().orElseThrow();
            long max = Arrays.stream(shares).mapToLong(Money::minorUnits).max().orElseThrow();
            assertThat(max - min).isLessThanOrEqualTo(1L);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        void rejects_a_nonsensical_number_of_parts(int parts) {
            assertThatThrownBy(() -> Money.of(100L, EUR).allocate(parts))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejects_weights_that_cannot_divide_anything() {
            Money amount = Money.of(100L, EUR);

            assertThatThrownBy(() -> amount.allocate(new long[0]))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> amount.allocate(0L, 0L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("zero");
            assertThatThrownBy(() -> amount.allocate(1L, -1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
        }

        private static Money sum(Money[] shares) {
            return Arrays.stream(shares).reduce(Money::plus).orElseThrow();
        }
    }

    @Nested
    @DisplayName("rendering")
    class Rendering {

        @Test
        void uses_the_currency_own_minor_unit_count() {
            assertThat(Money.of(1234L, EUR).toDecimal()).isEqualByComparingTo(new BigDecimal("12.34"));
            // JPY has no minor unit: 1234 yen is 1234, not 12.34.
            assertThat(Money.of(1234L, JPY).toDecimal()).isEqualByComparingTo(new BigDecimal("1234"));
        }

        @ParameterizedTest(name = "{0} {1} renders as \"{2}\"")
        @CsvSource({
                "1234,  EUR, EUR 12.34",
                "-1234, EUR, EUR -12.34",
                "0,     EUR, EUR 0.00",
                "5,     EUR, EUR 0.05",
                "1234,  JPY, JPY 1234"
        })
        void renders_in_iso_style(long minorUnits, String code, String expected) {
            assertThat(Money.of(minorUnits, code)).hasToString(expected);
        }

        @Test
        void decimal_conversion_is_exact_for_the_largest_amount() {
            assertThat(Money.of(Long.MAX_VALUE, EUR).toDecimal().unscaledValue().longValueExact())
                    .isEqualTo(Long.MAX_VALUE);
        }
    }

    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        void equal_amounts_in_the_same_currency_are_equal() {
            assertThat(Money.of(100L, EUR)).isEqualTo(Money.of(100L, "EUR"));
            assertThat(Money.of(100L, EUR)).hasSameHashCodeAs(Money.of(100L, "EUR"));
        }

        @Test
        void the_same_number_in_different_currencies_is_not_equal() {
            assertThat(Money.of(100L, EUR)).isNotEqualTo(Money.of(100L, USD));
        }
    }
}
