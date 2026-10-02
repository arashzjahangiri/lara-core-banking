package io.lara.payments.domain;

import java.time.LocalDate;
import java.util.Set;

/**
 * Which days a settlement system is closed.
 *
 * <p>An interface rather than a set of dates, because holidays are not all fixed: Good Friday and
 * Easter Monday move every year and have to be computed. A caller supplying its own calendar —
 * a different currency, a bank's own closing days — implements this.
 */
@FunctionalInterface
public interface Holidays {

    boolean isHoliday(LocalDate date);

    /** A calendar with no holidays at all. Useful in tests that only care about weekends. */
    static Holidays none() {
        return date -> false;
    }

    /** A fixed set of dates, for a calendar that is published rather than computed. */
    static Holidays on(Set<LocalDate> dates) {
        Set<LocalDate> copy = Set.copyOf(dates);
        return copy::contains;
    }

    /** This calendar's days plus another's. */
    default Holidays and(Holidays other) {
        return date -> isHoliday(date) || other.isHoliday(date);
    }
}
