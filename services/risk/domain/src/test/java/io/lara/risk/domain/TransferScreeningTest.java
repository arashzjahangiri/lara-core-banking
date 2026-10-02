package io.lara.risk.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Screening, mostly at its boundaries.
 *
 * <p>The interesting cases in a limit check are the two minor units either side of the line, and
 * the interesting cases in a sanctions check are the spellings that should still match. Both get
 * tested directly, because both are the sort of thing that looks obviously right in the source
 * and is off by one in production.
 */
class TransferScreeningTest {

    private static final CustomerId CUSTOMER = CustomerId.of(UUID.randomUUID());

    /** 10,000.00 EUR a day; anything over 5,000.00 in one go gets a look. */
    private static final CustomerLimits LIMITS = new CustomerLimits(
            CUSTOMER, Money.of(1_000_000, "EUR"), Money.of(500_000, "EUR"));

    private static final Money NOTHING_YET = Money.zero("EUR");

    private static ScreeningRequest request(long minorUnits, String beneficiary) {
        return new ScreeningRequest(
                ScreeningReference.of("SCR-" + UUID.randomUUID()),
                CUSTOMER,
                Money.of(minorUnits, "EUR"),
                PartyName.of(beneficiary));
    }

    @Nested
    @DisplayName("the daily limit")
    class DailyLimit {

        /**
         * The boundary the ticket calls out. A limit of 10,000 that refuses 10,000 is a limit of
         * 9,999, and the customer was told the other number.
         *
         * <p>Driven with a tiny final transfer on top of a nearly-full day, which isolates the
         * daily limit from the review threshold. Screening the whole 10,000 in one go would land
         * on the limit and also clear the review threshold, so a single assertion could not tell
         * which of the two rules had answered.
         */
        @Test
        void allows_a_transfer_that_lands_exactly_on_the_limit() {
            Money oneShortOfTheLimit = Money.of(999_999, "EUR");

            ScreeningDecision decision = LIMITS.screen(Money.of(1, "EUR"), oneShortOfTheLimit);

            assertThat(decision.outcome()).isEqualTo(ScreeningOutcome.ALLOW);
        }

        @Test
        void blocks_a_transfer_one_minor_unit_over_the_limit() {
            Money oneShortOfTheLimit = Money.of(999_999, "EUR");

            ScreeningDecision decision = LIMITS.screen(Money.of(2, "EUR"), oneShortOfTheLimit);

            assertThat(decision.outcome()).isEqualTo(ScreeningOutcome.BLOCK);
            assertThat(decision.reason()).contains("daily limit");
        }

        /**
         * A transfer for the whole daily limit at once is not blocked — but it does clear the
         * review threshold on the way, so the answer is {@code REVIEW}. Worth pinning, because
         * "at the limit is allowed" is easy to misread as "at the limit is waved through".
         */
        @Test
        void sends_a_transfer_for_the_entire_daily_limit_to_review_rather_than_blocking_it() {
            ScreeningDecision decision = LIMITS.screen(Money.of(1_000_000, "EUR"), NOTHING_YET);

            assertThat(decision.outcome()).isEqualTo(ScreeningOutcome.REVIEW);
        }

        @Test
        void blocks_one_minor_unit_above_the_entire_daily_limit() {
            ScreeningDecision decision = LIMITS.screen(Money.of(1_000_001, "EUR"), NOTHING_YET);

            assertThat(decision.outcome()).isEqualTo(ScreeningOutcome.BLOCK);
            assertThat(decision.reason()).contains("daily limit");
        }

        /**
         * The reason the limit is cumulative. A per-transfer-only limit is defeated by sending
         * the same money in several pieces, which is the first thing anyone tries.
         */
        @Test
        void counts_what_was_already_screened_today() {
            Money alreadyScreened = Money.of(900_000, "EUR");

            assertThat(LIMITS.screen(Money.of(100_000, "EUR"), alreadyScreened).outcome())
                    .isEqualTo(ScreeningOutcome.ALLOW);
            assertThat(LIMITS.screen(Money.of(100_001, "EUR"), alreadyScreened).outcome())
                    .isEqualTo(ScreeningOutcome.BLOCK);
        }

        @Test
        void sends_a_large_but_legal_transfer_for_review() {
            ScreeningDecision decision = LIMITS.screen(Money.of(500_001, "EUR"), NOTHING_YET);

            assertThat(decision.outcome()).isEqualTo(ScreeningOutcome.REVIEW);
            assertThat(decision.reason()).contains("review threshold");
        }

        @Test
        void allows_a_transfer_exactly_on_the_review_threshold() {
            assertThat(LIMITS.screen(Money.of(500_000, "EUR"), NOTHING_YET).outcome())
                    .isEqualTo(ScreeningOutcome.ALLOW);
        }

        /** Over the daily limit beats over the review threshold; the stricter answer wins. */
        @Test
        void blocks_rather_than_reviews_when_both_thresholds_are_crossed() {
            assertThat(LIMITS.screen(Money.of(1_500_000, "EUR"), NOTHING_YET).outcome())
                    .isEqualTo(ScreeningOutcome.BLOCK);
        }

        @Test
        void refuses_to_screen_an_amount_in_a_currency_the_limits_are_not_set_in() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> LIMITS.screen(Money.of(100, "DKK"), NOTHING_YET))
                    .withMessageContaining("DKK")
                    .withMessageContaining("EUR");
        }
    }

    @Nested
    @DisplayName("limit configuration")
    class LimitConfiguration {

        /**
         * Configuration that can never fire is worse than configuration that is rejected: nobody
         * finds out until the review that should have happened did not.
         */
        @Test
        void refuses_a_review_threshold_above_the_daily_limit() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CustomerLimits(
                            CUSTOMER, Money.of(100, "EUR"), Money.of(101, "EUR")))
                    .withMessageContaining("can never fire");
        }

        @Test
        void refuses_a_negative_limit() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CustomerLimits(
                            CUSTOMER, Money.of(-1, "EUR"), Money.zero("EUR")))
                    .withMessageContaining("cannot be negative");
        }

        @Test
        void refuses_limits_that_disagree_about_currency() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CustomerLimits(
                            CUSTOMER, Money.of(1_000, "EUR"), Money.of(500, "DKK")))
                    .withMessageContaining("share a currency");
        }
    }

    @Nested
    @DisplayName("sanctions matching")
    class Sanctions {

        private final SanctionsList list = new SanctionsList(List.of(
                SanctionedParty.of("Åse Bjørk", "EU-2026/114"),
                SanctionedParty.of("Ivan Petrov", "UN-1267/8821")));

        @Test
        void blocks_an_exact_match() {
            ScreeningDecision decision = list.screen(PartyName.of("Ivan Petrov"));

            assertThat(decision.outcome()).isEqualTo(ScreeningOutcome.BLOCK);
            assertThat(decision.reason()).contains("UN-1267/8821");
        }

        /**
         * The spellings a list that only matched its own stored form would miss. Each of these is
         * the same person, and an accent is not a way to get money to a sanctioned party.
         */
        @ParameterizedTest(name = "''{0}'' still matches")
        @ValueSource(strings = {
                "Åse Bjørk",
                "Ase Bjork",
                "ÅSE BJØRK",
                "åse   bjørk",
                "Ase  Bjork ",
                "Åse-Bjørk",
                "Bjørk, Åse"
        })
        void is_insensitive_to_case_accents_punctuation_and_spacing(String spelling) {
            assertThat(list.screen(PartyName.of(spelling)).outcome())
                    .isEqualTo(ScreeningOutcome.BLOCK);
        }

        /** Extra names do not help: the listed name is still entirely present. */
        @Test
        void blocks_a_name_that_contains_the_listed_one() {
            assertThat(list.screen(PartyName.of("Ivan Sergeyevich Petrov")).outcome())
                    .isEqualTo(ScreeningOutcome.BLOCK);
        }

        /**
         * The case that stops screening from being switched off. Thousands of people share a
         * surname with someone on a list, and blocking all of them is both wrong and unworkable.
         */
        @Test
        void sends_a_shared_surname_for_review_rather_than_blocking_it() {
            ScreeningDecision decision = list.screen(PartyName.of("Natalia Petrov"));

            assertThat(decision.outcome()).isEqualTo(ScreeningOutcome.REVIEW);
            assertThat(decision.reason()).contains("not a match");
        }

        @Test
        void allows_a_name_that_matches_nothing() {
            assertThat(list.screen(PartyName.of("Jens Hansen")).outcome())
                    .isEqualTo(ScreeningOutcome.ALLOW);
        }

        /** An empty list has nothing to say, and saying "allow" is the honest version of that. */
        @Test
        void allows_everything_when_the_list_is_empty() {
            assertThat(SanctionsList.empty().screen(PartyName.of("Ivan Petrov")).outcome())
                    .isEqualTo(ScreeningOutcome.ALLOW);
        }

        /** A full match on one entry outranks a partial match on another. */
        @Test
        void prefers_a_full_match_over_a_partial_one() {
            SanctionsList both = new SanctionsList(List.of(
                    SanctionedParty.of("Petrov", "UN-PARTIAL"),
                    SanctionedParty.of("Ivan Petrov", "UN-FULL")));

            assertThat(both.screen(PartyName.of("Ivan Petrov")).outcome())
                    .isEqualTo(ScreeningOutcome.BLOCK);
        }
    }

    @Nested
    @DisplayName("short tokens")
    class ShortTokens {

        /**
         * Name particles are too common to mean anything alone. Without a length floor, "van"
         * would send every Dutch beneficiary for review and the review queue would be ignored
         * within a week.
         */
        @Test
        void do_not_trigger_a_review_on_their_own() {
            SanctionsList list = new SanctionsList(List.of(
                    SanctionedParty.of("Jan van Dijk", "EU-2026/9")));

            assertThat(list.screen(PartyName.of("Marieke van Leeuwen")).outcome())
                    .isEqualTo(ScreeningOutcome.ALLOW);
        }
    }

    @Nested
    @DisplayName("combining the two checks")
    class Combining {

        private final SanctionsList list = new SanctionsList(List.of(
                SanctionedParty.of("Ivan Petrov", "UN-1267/8821")));
        private final TransferScreening screening = new TransferScreening();

        @Test
        void allows_only_when_both_checks_allow() {
            ScreeningDecision decision =
                    screening.screen(request(10_000, "Jens Hansen"), LIMITS, NOTHING_YET, list);

            assertThat(decision.outcome()).isEqualTo(ScreeningOutcome.ALLOW);
        }

        /** The limit is comfortable; the beneficiary is not. Sanctions must not be overruled. */
        @Test
        void blocks_a_small_transfer_to_a_sanctioned_party() {
            ScreeningDecision decision =
                    screening.screen(request(100, "Ivan Petrov"), LIMITS, NOTHING_YET, list);

            assertThat(decision.outcome()).isEqualTo(ScreeningOutcome.BLOCK);
            assertThat(decision.reason()).contains("sanctioned party");
        }

        /** And the reverse: a clean beneficiary does not excuse breaching the limit. */
        @Test
        void blocks_an_oversized_transfer_to_an_unlisted_party() {
            ScreeningDecision decision =
                    screening.screen(request(1_000_001, "Jens Hansen"), LIMITS, NOTHING_YET, list);

            assertThat(decision.outcome()).isEqualTo(ScreeningOutcome.BLOCK);
            assertThat(decision.reason()).contains("daily limit");
        }

        /**
         * When both block, the sanctions reason is the one recorded. It is the one a compliance
         * officer needs; a limit breach is the easier of the two for a customer to work out alone.
         */
        @Test
        void reports_the_sanctions_reason_when_both_checks_block() {
            ScreeningDecision decision =
                    screening.screen(request(1_000_001, "Ivan Petrov"), LIMITS, NOTHING_YET, list);

            assertThat(decision.outcome()).isEqualTo(ScreeningOutcome.BLOCK);
            assertThat(decision.reason()).contains("sanctioned party");
        }

        @Test
        void takes_review_from_whichever_check_raised_it() {
            ScreeningDecision byAmount =
                    screening.screen(request(500_001, "Jens Hansen"), LIMITS, NOTHING_YET, list);
            ScreeningDecision byName =
                    screening.screen(request(100, "Natalia Petrov"), LIMITS, NOTHING_YET, list);

            assertThat(byAmount.outcome()).isEqualTo(ScreeningOutcome.REVIEW);
            assertThat(byName.outcome()).isEqualTo(ScreeningOutcome.REVIEW);
        }

        @Test
        void refuses_limits_belonging_to_a_different_customer() {
            CustomerLimits someoneElses = new CustomerLimits(
                    CustomerId.of(UUID.randomUUID()), Money.of(1_000_000, "EUR"), Money.of(1, "EUR"));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> screening.screen(
                            request(100, "Jens Hansen"), someoneElses, NOTHING_YET, list))
                    .withMessageContaining("but the request is for");
        }
    }

    @Nested
    @DisplayName("ordering outcomes")
    class Ordering {

        @ParameterizedTest(name = "{0} vs {1} -> {2}")
        @CsvSource({
                "ALLOW,  ALLOW,  ALLOW",
                "ALLOW,  REVIEW, REVIEW",
                "REVIEW, ALLOW,  REVIEW",
                "ALLOW,  BLOCK,  BLOCK",
                "BLOCK,  ALLOW,  BLOCK",
                "REVIEW, BLOCK,  BLOCK",
                "BLOCK,  REVIEW, BLOCK",
                "BLOCK,  BLOCK,  BLOCK"
        })
        void always_keeps_the_stricter_one(
                ScreeningOutcome first, ScreeningOutcome second, ScreeningOutcome expected) {

            ScreeningDecision combined = ScreeningDecision.mostRestrictive(
                    decisionFor(first, "first"), decisionFor(second, "second"));

            assertThat(combined.outcome()).isEqualTo(expected);
        }

        /** A tie keeps the first argument, so the reason does not depend on evaluation order. */
        @Test
        void keeps_the_first_reason_on_a_tie() {
            ScreeningDecision combined = ScreeningDecision.mostRestrictive(
                    ScreeningDecision.block("sanctions"), ScreeningDecision.block("limit"));

            assertThat(combined.reason()).isEqualTo("sanctions");
        }

        private static ScreeningDecision decisionFor(ScreeningOutcome outcome, String reason) {
            return switch (outcome) {
                case ALLOW -> ScreeningDecision.allow(reason);
                case REVIEW -> ScreeningDecision.review(reason);
                case BLOCK -> ScreeningDecision.block(reason);
            };
        }
    }

    @Nested
    @DisplayName("a decision")
    class Decisions {

        @Test
        void always_carries_a_reason() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ScreeningDecision.block("   "))
                    .withMessageContaining("must carry a reason");
        }

        @Test
        void knows_whether_the_transfer_may_simply_proceed() {
            assertThat(ScreeningDecision.allow("fine").isAllowed()).isTrue();
            assertThat(ScreeningDecision.review("maybe").isAllowed()).isFalse();
            assertThat(ScreeningDecision.block("no").isAllowed()).isFalse();
        }
    }

    @Nested
    @DisplayName("a screening request")
    class Requests {

        @Test
        void refuses_a_non_positive_amount() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> request(0, "Jens Hansen"))
                    .withMessageContaining("non-positive");
        }

        @Test
        void refuses_a_blank_beneficiary() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> PartyName.of("   "))
                    .withMessageContaining("must not be blank");
        }
    }
}
