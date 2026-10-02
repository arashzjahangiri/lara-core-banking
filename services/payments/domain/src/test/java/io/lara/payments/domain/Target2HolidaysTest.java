package io.lara.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The closing days this service believes in.
 *
 * <p>{@link BusinessCalendar} is copied from the ledger rather than shared, and a copy that drifts
 * is worse than no copy at all: the two services would quote different value dates for the same
 * transfer and nothing would fail until someone reconciled them. This file is half of what stops
 * that — it pins the same dates the ledger's own holiday test pins, so a change to one calendar
 * that is not made to the other shows up as a red build.
 *
 * <p>Easter is computed, not tabulated, so the expected dates here are taken from the published
 * ecclesiastical calendar rather than from the algorithm being tested.
 */
class Target2HolidaysTest {

    private final Target2Holidays holidays = new Target2Holidays();

    @ParameterizedTest(name = "Easter Sunday {0} is {1}")
    @CsvSource({
            "2024, 2024-03-31",
            "2025, 2025-04-20",
            "2026, 2026-04-05",
            "2027, 2027-03-28",
            "2030, 2030-04-21",
            "2038, 2038-04-25"
    })
    void computes_easter_sunday(int year, LocalDate expected) {
        assertThat(Target2Holidays.easterSunday(year)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} is closed")
    @CsvSource({
            "2026-01-01",   // New Year's Day
            "2026-04-03",   // Good Friday, two days before Easter
            "2026-04-06",   // Easter Monday
            "2026-05-01",   // Labour Day
            "2026-12-25",   // Christmas Day
            "2026-12-26"    // 26 December
    })
    void closes_on_the_six_target2_holidays(LocalDate date) {
        assertThat(holidays.isHoliday(date)).isTrue();
    }

    /**
     * What is deliberately absent. TARGET2 is one European system, so a national holiday in a
     * single member state does not stop settlement — and Easter Sunday is not on the list because
     * it is a Sunday, which the weekend rule already covers.
     */
    @ParameterizedTest(name = "{0} is open")
    @CsvSource({
            "2026-04-05",   // Easter Sunday itself: a weekend, not a listed closing day
            "2026-06-05",   // Danish Constitution Day: national, not European
            "2026-10-03",   // German Unity Day: national, not European
            "2026-07-14",   // Bastille Day: national, not European
            "2026-12-24",   // Christmas Eve: a full business day in TARGET2
            "2026-12-31"    // New Year's Eve: likewise
    })
    void stays_open_on_days_that_are_not_target2_closing_days(LocalDate date) {
        assertThat(holidays.isHoliday(date)).isFalse();
    }

    @Test
    void treats_easter_sunday_as_a_weekend_rather_than_a_holiday() {
        LocalDate easter = Target2Holidays.easterSunday(2026);
        BusinessCalendar calendar = BusinessCalendar.target2(java.time.LocalTime.of(16, 0));

        assertThat(holidays.isHoliday(easter)).isFalse();
        assertThat(calendar.isBusinessDay(easter)).isFalse();
    }
}
