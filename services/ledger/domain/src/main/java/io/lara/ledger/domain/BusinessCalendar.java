package io.lara.ledger.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * When a payment actually settles.
 *
 * <p>A ledger records two different times and they are not the same fact. {@code occurredAt} is
 * when the movement was booked; the <strong>value date</strong> is the business day it settles
 * on, and it depends on weekends, closing days and a daily cutoff. A transfer submitted at
 * 18:00 on the Friday before Easter does not settle until the Tuesday.
 *
 * <p>Without this rule, having two date columns is ornamental. With it, they mean different
 * things and interest, reconciliation and statements can be computed correctly.
 *
 * <p>The zone, the cutoff and the holiday calendar all arrive through the constructor. A calendar
 * that read its own configuration could not be built with {@code new} in a test, and the cutoff
 * is exactly the sort of value that needs to be varied across a dozen cases.
 */
public final class BusinessCalendar {

    /** A hard stop: a date more than this far out means the holiday calendar is wrong. */
    private static final int MAX_CONSECUTIVE_CLOSED_DAYS = 30;

    private final ZoneId zone;
    private final LocalTime cutoff;
    private final Holidays holidays;

    public BusinessCalendar(ZoneId zone, LocalTime cutoff, Holidays holidays) {
        this.zone = Objects.requireNonNull(zone, "zone must not be null");
        this.cutoff = Objects.requireNonNull(cutoff, "cutoff must not be null");
        this.holidays = Objects.requireNonNull(holidays, "holidays must not be null");
    }

    /** The euro settlement calendar: TARGET2 closing days, in Central European time. */
    public static BusinessCalendar target2(LocalTime cutoff) {
        return new BusinessCalendar(ZoneId.of("Europe/Brussels"), cutoff, new Target2Holidays());
    }

    public boolean isBusinessDay(LocalDate date) {
        Objects.requireNonNull(date, "date must not be null");
        return !isWeekend(date) && !holidays.isHoliday(date);
    }

    /** The first business day strictly after {@code from}. */
    public LocalDate nextBusinessDay(LocalDate from) {
        Objects.requireNonNull(from, "from must not be null");
        return searchFrom(from.plusDays(1), 1);
    }

    /** The last business day strictly before {@code from}. */
    public LocalDate previousBusinessDay(LocalDate from) {
        Objects.requireNonNull(from, "from must not be null");
        return searchFrom(from.minusDays(1), -1);
    }

    /**
     * The business day a payment submitted at this instant settles on.
     *
     * <p>Same day if it arrives on a business day before the cutoff. Otherwise the next business
     * day — the cutoff is the point after which the day's settlement run has already gone.
     *
     * <p>Evaluated in the calendar's own zone, not the caller's. A payment submitted at 23:00 in
     * Copenhagen and one submitted at 23:00 in Tokyo are not the same moment in the settlement
     * day, and the ledger's answer must not depend on where the request came from.
     */
    public LocalDate valueDate(Instant submittedAt) {
        Objects.requireNonNull(submittedAt, "submittedAt must not be null");

        ZonedDateTime local = submittedAt.atZone(zone);
        LocalDate day = local.toLocalDate();

        boolean inTime = isBusinessDay(day) && local.toLocalTime().isBefore(cutoff);
        return inTime ? day : nextBusinessDay(day);
    }

    /** Whether a payment submitted at this instant still makes the day's settlement run. */
    public boolean isBeforeCutoff(Instant submittedAt) {
        Objects.requireNonNull(submittedAt, "submittedAt must not be null");
        ZonedDateTime local = submittedAt.atZone(zone);
        return isBusinessDay(local.toLocalDate()) && local.toLocalTime().isBefore(cutoff);
    }

    public LocalTime cutoff() {
        return cutoff;
    }

    public ZoneId zone() {
        return zone;
    }

    private static boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    private LocalDate searchFrom(LocalDate start, int step) {
        LocalDate candidate = start;
        for (int tried = 0; tried < MAX_CONSECUTIVE_CLOSED_DAYS; tried++) {
            if (isBusinessDay(candidate)) {
                return candidate;
            }
            candidate = candidate.plusDays(step);
        }
        // A month of closed days means the calendar is misconfigured. Looping forever would turn
        // that into a hung request rather than an error anyone could diagnose.
        throw new IllegalStateException(
                "no business day within " + MAX_CONSECUTIVE_CLOSED_DAYS + " days of " + start
                        + "; the holiday calendar is almost certainly wrong");
    }
}
