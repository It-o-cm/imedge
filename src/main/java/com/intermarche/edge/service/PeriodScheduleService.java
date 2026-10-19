package com.intermarche.edge.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Says which periods END on a given day ({@code BO-09-01-07}).
 *
 * <p>The node already closes its registers every day at an administered hour,
 * switched on by an administered flag. What it could not say is whether the
 * day that ends is also the end of a WEEK, a MONTH or a YEAR — and the
 * requirement asks for exactly that: the hour, the activation, and the chosen
 * day of week-end, month-end and year-end.
 *
 * <p>Three parameters, read defensively. A weekday nobody recognises falls
 * back on Sunday, a month day of zero or past the month's length means its
 * LAST day — which is the only reading that works in February — and a
 * year-end date that cannot be parsed falls back on the thirty-first of
 * December. A parameter typed by hand must never be able to stop the end of
 * period from happening.
 *
 * <p>The day itself is always an end of period: that is the conduct the node
 * has always had, and the week, the month and the year are added to it, never
 * substituted for it.
 */
@ApplicationScoped
public class PeriodScheduleService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PeriodScheduleService.class);

    /** The weekday a week ends on when the parameter cannot be read. */
    private static final DayOfWeek DEFAULT_WEEK_END = DayOfWeek.SUNDAY;

    /** The day of month a year ends on when the parameter cannot be read. */
    private static final int DEFAULT_YEAR_END_DAY = 31;

    /** The month a year ends in when the parameter cannot be read. */
    private static final int DEFAULT_YEAR_END_MONTH = 12;

    /** The parameters carrying the three chosen days. */
    @Inject
    PosSettingsService posSettingsService;

    /** A period the shop closes. */
    public enum PeriodEnd {

        /** The day, which always ends. */
        DAY,

        /** The week, on the administered weekday. */
        WEEK,

        /** The month, on the administered day of month. */
        MONTH,

        /** The year, on the administered day and month. */
        YEAR
    }

    /**
     * The periods ending on a day ({@code BO-09-01-07}).
     *
     * @param day the day examined
     * @return the periods that end, always holding at least {@link PeriodEnd#DAY}
     */
    public Set<PeriodEnd> endingOn(LocalDate day) {
        LOGGER.info("Entering method endingOn with day: " + day);
        Set<PeriodEnd> ending = EnumSet.of(PeriodEnd.DAY);
        if (day.getDayOfWeek() == weekEndDay()) {
            ending.add(PeriodEnd.WEEK);
        }
        if (day.getDayOfMonth() == monthEndDay(day)) {
            ending.add(PeriodEnd.MONTH);
        }
        int[] yearEnd = yearEndDate();
        if (day.getMonthValue() == yearEnd[1]
                && day.getDayOfMonth() == Math.min(yearEnd[0], day.lengthOfMonth())) {
            ending.add(PeriodEnd.YEAR);
        }
        LOGGER.info("Exiting method endingOn with " + ending);
        return ending;
    }

    /**
     * The administered weekday a week ends on.
     *
     * @return the weekday, Sunday when the parameter names nothing known
     */
    DayOfWeek weekEndDay() {
        String raw = read(() -> posSettingsService.periodWeekEndDay());
        if (raw == null || raw.isBlank()) {
            return DEFAULT_WEEK_END;
        }
        try {
            return DayOfWeek.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            LOGGER.debugf("Jour de fin de semaine inconnu (%s)", raw);
            return DEFAULT_WEEK_END;
        }
    }

    /**
     * The administered day of month a month ends on, for one month.
     *
     * <p>Resolved against the month examined: zero, a negative value and any
     * day past the month's length all mean its LAST day, which is what makes
     * "the 31st" work in February.
     *
     * @param day the day examined, read for the length of its month
     * @return the day of month, always within the month
     */
    int monthEndDay(LocalDate day) {
        int administered = read(() -> posSettingsService.periodMonthEndDay(), 0);
        return administered <= 0 || administered > day.lengthOfMonth()
                ? day.lengthOfMonth() : administered;
    }

    /**
     * The administered day and month a year ends on.
     *
     * @return a two-cell array, the day then the month
     */
    int[] yearEndDate() {
        String raw = read(() -> posSettingsService.periodYearEndDate());
        if (raw != null && raw.contains("/")) {
            String[] parts = raw.trim().split("/");
            try {
                int dayOfMonth = Integer.parseInt(parts[0].trim());
                int month = Integer.parseInt(parts[1].trim());
                if (dayOfMonth >= 1 && dayOfMonth <= 31 && month >= 1 && month <= 12) {
                    return new int[]{dayOfMonth, month};
                }
            } catch (NumberFormatException unreadable) {
                LOGGER.debugf("Date de fin d'année illisible (%s)", raw);
            }
        }
        return new int[]{DEFAULT_YEAR_END_DAY, DEFAULT_YEAR_END_MONTH};
    }

    /**
     * Reads a text parameter, answering null when the settings table cannot be
     * reached at all.
     *
     * @param reader the accessor to call
     * @return the value, or null when it could not be read
     */
    private String read(java.util.function.Supplier<String> reader) {
        try {
            return reader.get();
        } catch (Exception unreadable) {
            LOGGER.debugf("Paramètre de fin de période illisible (%s)", unreadable.getMessage());
            return null;
        }
    }

    /**
     * Reads an integer parameter, answering a fallback when the settings table
     * cannot be reached at all.
     *
     * @param reader the accessor to call
     * @param fallback the value to answer when it could not be read
     * @return the value, or the fallback
     */
    private int read(java.util.function.IntSupplier reader, int fallback) {
        try {
            return reader.getAsInt();
        } catch (Exception unreadable) {
            LOGGER.debugf("Paramètre de fin de période illisible (%s)", unreadable.getMessage());
            return fallback;
        }
    }
}
