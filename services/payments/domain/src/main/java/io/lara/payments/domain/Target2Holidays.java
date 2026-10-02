package io.lara.payments.domain;

import java.time.LocalDate;
import java.time.Month;
import java.time.MonthDay;
import java.util.Set;

/**
 * The days TARGET2 is closed, which is when euro payments do not settle.
 *
 * <p>Six closing days beyond weekends: New Year's Day, Good Friday, Easter Monday, Labour Day,
 * Christmas Day and 26 December. Note what is <em>not</em> here — national holidays are
 * irrelevant, because TARGET2 is a single European system and a bank holiday in one member state
 * does not stop settlement.
 *
 * <p>Easter is computed rather than tabulated. A hardcoded list is correct until the year it runs
 * out, and then wrong silently: value dates quietly shift by a day and nobody notices until a
 * reconciliation fails.
 */
public final class Target2Holidays implements Holidays {

    private static final Set<MonthDay> FIXED = Set.of(
            MonthDay.of(Month.JANUARY, 1),    // New Year's Day
            MonthDay.of(Month.MAY, 1),        // Labour Day
            MonthDay.of(Month.DECEMBER, 25),  // Christmas Day
            MonthDay.of(Month.DECEMBER, 26)); // 26 December

    @Override
    public boolean isHoliday(LocalDate date) {
        if (FIXED.contains(MonthDay.from(date))) {
            return true;
        }
        LocalDate easter = easterSunday(date.getYear());
        return date.equals(easter.minusDays(2)) || date.equals(easter.plusDays(1));
    }

    /**
     * Easter Sunday in the Gregorian calendar, by the anonymous Gregorian algorithm.
     *
     * <p>Opaque by nature — it is modular arithmetic over the lunar cycle, not something that
     * reads meaningfully line by line. It is correct for 1583 onwards and is verified against
     * known dates in the tests, which is the only sensible way to trust it.
     */
    static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;

        return LocalDate.of(year, month, day);
    }
}
