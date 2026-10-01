package io.lara.accounts.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IbanTest {

    @Nested
    @DisplayName("accepting")
    class Accepting {

        /** Published examples from ISO 13616, so the algorithm is checked against real data. */
        @ParameterizedTest
        @ValueSource(strings = {
                "DK5000400440116243",
                "DE89370400440532013000",
                "GB82WEST12345698765432",
                "FR1420041010050500013M02606",
                "NL91ABNA0417164300",
                "BE68539007547034",
                "NO9386011117947",
                "SE4550000000058398257466"
        })
        void accepts_a_valid_iban(String candidate) {
            assertThat(Iban.of(candidate).value()).isEqualTo(candidate);
        }

        @Test
        void ignores_the_spaces_people_type() {
            assertThat(Iban.of("DK50 0040 0440 1162 43").value()).isEqualTo("DK5000400440116243");
        }

        @Test
        void normalises_to_uppercase() {
            assertThat(Iban.of("dk5000400440116243").value()).isEqualTo("DK5000400440116243");
        }

        @Test
        void prints_grouped_in_fours_for_a_statement() {
            assertThat(Iban.of("DK5000400440116243").formatted()).isEqualTo("DK50 0040 0440 1162 43");
        }

        @Test
        void knows_its_country() {
            assertThat(Iban.of("DK5000400440116243").countryCode()).isEqualTo("DK");
        }
    }

    @Nested
    @DisplayName("rejecting")
    class Rejecting {

        /**
         * The point of the check digits. One wrong character in an otherwise plausible IBAN is
         * the common failure, and a payment sent to a valid-looking wrong account is expensive.
         */
        @Test
        void rejects_a_single_mistyped_digit() {
            assertThat(Iban.of("DK5000400440116243")).isNotNull();

            assertThatThrownBy(() -> Iban.of("DK5000400440116244"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("check digits");
        }

        /** MOD-97 catches transpositions, which a simple checksum would not. */
        @Test
        void rejects_two_transposed_digits() {
            assertThatThrownBy(() -> Iban.of("DK5000400440116234"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("check digits");
        }

        @Test
        void rejects_the_wrong_length_for_the_country() {
            // Valid German shape and check digits, but claiming to be Danish.
            assertThatThrownBy(() -> Iban.of("DK89370400440532013000"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("18 characters");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "", "DK", "DK50", "5000400440116243", "DKXX00400440116243",
                "DK50-0040-0440", "DK50_0040"
        })
        void rejects_anything_that_is_not_an_iban(String candidate) {
            assertThatThrownBy(() -> Iban.of(candidate)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejects_null() {
            assertThatThrownBy(() -> Iban.of(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("generating")
    class Generating {

        @Test
        void computes_check_digits_that_validate() {
            Iban generated = Iban.generate("DK", "0040", "0440116243");

            assertThat(generated.value()).isEqualTo("DK5000400440116243");
            assertThat(Iban.of(generated.value())).isEqualTo(generated);
        }

        /** Whatever is generated must pass the same check applied to anything from outside. */
        @Test
        void every_generated_iban_round_trips_through_validation() {
            for (int account = 0; account < 50; account++) {
                Iban generated = Iban.generate("DK", "0040", String.format("%010d", account));
                assertThat(Iban.of(generated.value())).isEqualTo(generated);
            }
        }

        @Test
        void produces_a_different_iban_for_a_different_account() {
            assertThat(Iban.generate("DK", "0040", "0440116243"))
                    .isNotEqualTo(Iban.generate("DK", "0040", "0440116244"));
        }

        @Test
        void refuses_missing_parts() {
            assertThatThrownBy(() -> Iban.generate(null, "0040", "1"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> Iban.generate("DK", null, "1"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> Iban.generate("DK", "0040", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        void two_spellings_of_one_iban_are_equal() {
            assertThat(Iban.of("DK50 0040 0440 1162 43")).isEqualTo(Iban.of("dk5000400440116243"));
        }

        @Test
        void sorts_by_its_text() {
            assertThat(Iban.of("BE68539007547034")).isLessThan(Iban.of("DK5000400440116243"));
        }
    }
}
