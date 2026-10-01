package io.lara.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BusinessCalendarTest {

    private static final LocalTime CUTOFF = LocalTime.of(16, 0);
    private static final BusinessCalendar TARGET2 = BusinessCalendar.target2(CUTOFF);

    /** Brussels time, so the test reads in the zone the calendar actually evaluates. */
    private static Instant brussels(String localDateTime) {
        return LocalDate.parse(localDateTime.substring(0, 10))
                .atTime(LocalTime.parse(localDateTime.substring(11)))
                .atZone(ZoneId.of("Europe/Brussels"))
                .toInstant();
    }

    @Nested
    @DisplayName("Easter")
    class Easter {

        /**
         * Computed rather than tabulated, so these are checked against published dates. A
         * hardcoded table is correct until the year it runs out and then wrong silently.
         */
        @ParameterizedTest(name = "Easter {0} is {1}")
        @CsvSource({
                "2024, 2024-03-31",
                "2025, 2025-04-20",
                "2026, 2026-04-05",
                "2027, 2027-03-28",
                "2028, 2028-04-16",
                "2030, 2030-04-21",
                "2038, 2038-04-25"
        })
        void falls_on_the_published_date(int year, LocalDate expected) {
            assertThat(Target2Holidays.easterSunday(year)).isEqualTo(expected);
        }

        @Test
        void drags_good_friday_and_easter_monday_with_it() {
            Holidays holidays = new Target2Holidays();

            // Easter 2026 is Sunday 5 April.
            assertThat(holidays.isHoliday(LocalDate.of(2026, 4, 3))).as("Good Friday").isTrue();
            assertThat(holidays.isHoliday(LocalDate.of(2026, 4, 6))).as("Easter Monday").isTrue();
            assertThat(holidays.isHoliday(LocalDate.of(2026, 4, 7))).as("the Tuesday after").isFalse();

            // Easter Sunday is not a "holiday": weekends are the calendar's concern, not the
            // holiday list's. Keeping the two separate is why a caller can supply its own
            // closing days without having to restate that Saturdays are shut.
            assertThat(holidays.isHoliday(LocalDate.of(2026, 4, 5))).as("not in the holiday list").isFalse();
            assertThat(TARGET2.isBusinessDay(LocalDate.of(2026, 4, 5))).as("but still closed").isFalse();
        }
    }

    @Nested
    @DisplayName("closing days")
    class ClosingDays {

        @ParameterizedTest(name = "{0} is closed")
        @CsvSource({ "2026-01-01", "2026-05-01", "2026-12-25", "2026-12-26" })
        void the_fixed_target2_days_are_closed(LocalDate date) {
            assertThat(TARGET2.isBusinessDay(date)).isFalse();
        }

        /** TARGET2 is one European system; a holiday in a single member state does not close it. */
        @Test
        void a_national_holiday_does_not_close_target2() {
            // 3 October, German Unity Day, a Saturday in 2026 — use 2027 where it is a Sunday...
            // 14 July 2026 is Bastille Day and a Tuesday: TARGET2 settles.
            assertThat(TARGET2.isBusinessDay(LocalDate.of(2026, 7, 14))).isTrue();
        }

        @Test
        void weekends_are_closed() {
            assertThat(TARGET2.isBusinessDay(LocalDate.of(2026, 10, 3))).as("Saturday").isFalse();
            assertThat(TARGET2.isBusinessDay(LocalDate.of(2026, 10, 4))).as("Sunday").isFalse();
            assertThat(TARGET2.isBusinessDay(LocalDate.of(2026, 10, 5))).as("Monday").isTrue();
        }
    }

    @Nested
    @DisplayName("moving between business days")
    class Moving {

        @Test
        void steps_over_a_weekend() {
            // Friday 2 October 2026 -> Monday 5 October.
            assertThat(TARGET2.nextBusinessDay(LocalDate.of(2026, 10, 2)))
                    .isEqualTo(LocalDate.of(2026, 10, 5));
            assertThat(TARGET2.previousBusinessDay(LocalDate.of(2026, 10, 5)))
                    .isEqualTo(LocalDate.of(2026, 10, 2));
        }

        /** Christmas 2026: Friday 25th and Saturday 26th closed, so the next open day is Monday 28th. */
        @Test
        void steps_over_a_holiday_adjacent_to_a_weekend() {
            assertThat(TARGET2.nextBusinessDay(LocalDate.of(2026, 12, 24)))
                    .isEqualTo(LocalDate.of(2026, 12, 28));
        }

        /** Easter 2026: Good Friday 3 April, weekend, Easter Monday 6 April — four days shut. */
        @Test
        void steps_over_the_whole_easter_weekend() {
            assertThat(TARGET2.nextBusinessDay(LocalDate.of(2026, 4, 2)))
                    .isEqualTo(LocalDate.of(2026, 4, 7));
            assertThat(TARGET2.previousBusinessDay(LocalDate.of(2026, 4, 7)))
                    .isEqualTo(LocalDate.of(2026, 4, 2));
        }

        @Test
        void always_moves_strictly_forwards_or_backwards() {
            LocalDate wednesday = LocalDate.of(2026, 10, 7);

            assertThat(TARGET2.nextBusinessDay(wednesday)).isAfter(wednesday);
            assertThat(TARGET2.previousBusinessDay(wednesday)).isBefore(wednesday);
        }

        /** A calendar that closed every day would otherwise loop forever inside a request. */
        @Test
        void refuses_to_search_forever_through_a_broken_calendar() {
            BusinessCalendar alwaysShut = new BusinessCalendar(
                    ZoneId.of("Europe/Brussels"), CUTOFF, date -> true);

            assertThatThrownBy(() -> alwaysShut.nextBusinessDay(LocalDate.of(2026, 10, 1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("holiday calendar");
        }
    }

    @Nested
    @DisplayName("value dates")
    class ValueDates {

        @Test
        void a_payment_before_cutoff_settles_the_same_day() {
            assertThat(TARGET2.valueDate(brussels("2026-10-07T15:59"))).isEqualTo(LocalDate.of(2026, 10, 7));
            assertThat(TARGET2.isBeforeCutoff(brussels("2026-10-07T15:59"))).isTrue();
        }

        /** The cutoff is exclusive: at exactly 16:00 the day's settlement run has gone. */
        @Test
        void a_payment_at_the_cutoff_rolls_to_the_next_day() {
            assertThat(TARGET2.valueDate(brussels("2026-10-07T16:00"))).isEqualTo(LocalDate.of(2026, 10, 8));
            assertThat(TARGET2.isBeforeCutoff(brussels("2026-10-07T16:00"))).isFalse();
        }

        @Test
        void a_payment_after_cutoff_on_a_friday_settles_on_monday() {
            assertThat(TARGET2.valueDate(brussels("2026-10-02T18:30"))).isEqualTo(LocalDate.of(2026, 10, 5));
        }

        @Test
        void a_payment_submitted_on_a_weekend_settles_on_monday() {
            assertThat(TARGET2.valueDate(brussels("2026-10-03T09:00"))).isEqualTo(LocalDate.of(2026, 10, 5));
            assertThat(TARGET2.valueDate(brussels("2026-10-04T23:59"))).isEqualTo(LocalDate.of(2026, 10, 5));
        }

        /** The case the whole calendar exists for. */
        @Test
        void a_payment_after_cutoff_before_easter_settles_the_following_tuesday() {
            // Thursday 2 April 2026, after cutoff. Good Friday, weekend, Easter Monday follow.
            assertThat(TARGET2.valueDate(brussels("2026-04-02T17:00"))).isEqualTo(LocalDate.of(2026, 4, 7));
        }

        @Test
        void a_payment_on_a_closing_day_settles_on_the_next_open_day() {
            assertThat(TARGET2.valueDate(brussels("2026-12-25T10:00"))).isEqualTo(LocalDate.of(2026, 12, 28));
        }

        /**
         * Evaluated in the settlement zone, not the caller's. The same instant is before cutoff
         * for one sender and after it for another only if the zone is wrong.
         */
        @Test
        void does_not_depend_on_where_the_request_came_from() {
            // 15:30 in Brussels is 22:30 in Tokyo and 09:30 in New York. One instant, one answer.
            Instant justBeforeCutoff = brussels("2026-10-07T15:30");

            assertThat(TARGET2.valueDate(justBeforeCutoff)).isEqualTo(LocalDate.of(2026, 10, 7));
            assertThat(TARGET2.zone()).isEqualTo(ZoneId.of("Europe/Brussels"));
        }

        @Test
        void honours_a_different_cutoff() {
            BusinessCalendar early = BusinessCalendar.target2(LocalTime.of(10, 0));

            assertThat(early.valueDate(brussels("2026-10-07T11:00"))).isEqualTo(LocalDate.of(2026, 10, 8));
            assertThat(TARGET2.valueDate(brussels("2026-10-07T11:00"))).isEqualTo(LocalDate.of(2026, 10, 7));
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        void accepts_a_caller_supplied_calendar() {
            LocalDate ourOwnClosure = LocalDate.of(2026, 10, 7);
            BusinessCalendar withExtra = new BusinessCalendar(
                    ZoneId.of("Europe/Brussels"), CUTOFF,
                    new Target2Holidays().and(Holidays.on(Set.of(ourOwnClosure))));

            assertThat(withExtra.isBusinessDay(ourOwnClosure)).isFalse();
            assertThat(TARGET2.isBusinessDay(ourOwnClosure)).isTrue();
        }

        @Test
        void a_calendar_with_no_holidays_still_skips_weekends() {
            BusinessCalendar weekdaysOnly = new BusinessCalendar(
                    ZoneId.of("Europe/Brussels"), CUTOFF, Holidays.none());

            assertThat(weekdaysOnly.isBusinessDay(LocalDate.of(2026, 12, 25))).isTrue();
            assertThat(weekdaysOnly.isBusinessDay(LocalDate.of(2026, 10, 3))).isFalse();
        }

        @Test
        void refuses_to_be_built_without_its_parts() {
            ZoneId zone = ZoneId.of("Europe/Brussels");
            assertThatThrownBy(() -> new BusinessCalendar(null, CUTOFF, Holidays.none()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new BusinessCalendar(zone, null, Holidays.none()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new BusinessCalendar(zone, CUTOFF, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
