package com.intermarche.edge.ui.report;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * The EDITION PERIOD every report offers: the current day, the day, week, month
 * or year containing a chosen date, or a free range.
 *
 * <p>Nearly every report of the back office repeats the same sentence —
 * « journée en cours, période (jour, semaine, mois ou année), plages de dates »
 * (BO-06-04-01, BO-06-06-01, -04, -05, -07, -09, -10). Written once here, it is
 * the same period on every report, which is what makes two reports comparable:
 * « la semaine » means the same interval on the cashier report and on the
 * settlement report.
 *
 * <p>The PREVIOUS periods — J-1, S-1, M-1 and A-1 (BO-06-06-06) — are the
 * day, week, month or year just before the one holding the chosen date: the
 * state a shop checks every morning is yesterday's, and the reader should not
 * have to compute the date of yesterday to edit it.
 *
 * <p>A period is resolved to a HALF-OPEN interval [from, to[ whose bounds are
 * date-times, never dates: a ticket closed at 23:59:30 belongs to its day, and
 * an interval ending at midnight would drop it.
 */
public enum ReportPeriod {

    /** Today, from midnight to the next midnight. */
    TODAY("Journée en cours"),

    /** The day containing the chosen date. */
    DAY("Jour"),

    /** The week containing the chosen date, Monday to Monday. */
    WEEK("Semaine"),

    /** The month containing the chosen date. */
    MONTH("Mois"),

    /** The year containing the chosen date. */
    YEAR("Année"),

    /** The free range the operator typed, bound to bound. */
    RANGE("Plage de dates"),

    /** The day BEFORE the chosen date, J-1 (BO-06-06-06). */
    PREVIOUS_DAY("Jour précédent (J-1)"),

    /** The week before the one containing the chosen date, S-1 (BO-06-06-06). */
    PREVIOUS_WEEK("Semaine précédente (S-1)"),

    /** The month before the one containing the chosen date, M-1 (BO-06-06-06). */
    PREVIOUS_MONTH("Mois précédent (M-1)"),

    /** The year before the one containing the chosen date, A-1 (BO-06-06-06). */
    PREVIOUS_YEAR("Année précédente (A-1)");

    /** The label shown in the period selector. */
    private final String label;

    /**
     * Builds a period.
     *
     * @param label the label shown in the selector
     */
    ReportPeriod(String label) {
        this.label = label;
    }

    /**
     * Returns the label shown in the period selector.
     *
     * @return the French label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Resolves the inclusive first instant of the period.
     *
     * @param reference the date the operator chose, or null for today
     * @param rangeFrom the first day of a free range, used by {@link #RANGE}
     * @return the first instant, never null
     */
    public LocalDateTime from(LocalDate reference, LocalDate rangeFrom) {
        LocalDate day = reference == null ? LocalDate.now() : reference;
        return switch (this) {
            case TODAY -> LocalDate.now().atStartOfDay();
            case DAY -> day.atStartOfDay();
            case WEEK -> day.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).atStartOfDay();
            case MONTH -> day.withDayOfMonth(1).atStartOfDay();
            case YEAR -> day.withDayOfYear(1).atStartOfDay();
            case RANGE -> (rangeFrom == null ? day : rangeFrom).atStartOfDay();
            case PREVIOUS_DAY -> day.minusDays(1).atStartOfDay();
            case PREVIOUS_WEEK -> WEEK.from(day.minusWeeks(1), null);
            case PREVIOUS_MONTH -> MONTH.from(day.minusMonths(1), null);
            case PREVIOUS_YEAR -> YEAR.from(day.minusYears(1), null);
        };
    }

    /**
     * Resolves the EXCLUSIVE last instant of the period.
     *
     * <p>Exclusive, and always the start of the day AFTER the last one: the
     * alternative — the last day at 23:59:59 — silently loses whatever happened
     * in the final second, and a register that closes its day at midnight sharp
     * puts documents there.
     *
     * @param reference the date the operator chose, or null for today
     * @param rangeTo the last day of a free range, used by {@link #RANGE}
     * @return the first instant NOT in the period, never null
     */
    public LocalDateTime to(LocalDate reference, LocalDate rangeTo) {
        LocalDate day = reference == null ? LocalDate.now() : reference;
        return switch (this) {
            case TODAY -> LocalDate.now().plusDays(1).atStartOfDay();
            case DAY -> day.plusDays(1).atStartOfDay();
            case WEEK -> day.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                    .plusWeeks(1).atStartOfDay();
            case MONTH -> day.withDayOfMonth(1).plusMonths(1).atStartOfDay();
            case YEAR -> day.withDayOfYear(1).plusYears(1).atStartOfDay();
            case RANGE -> (rangeTo == null ? day : rangeTo).plusDays(1).atStartOfDay();
            case PREVIOUS_DAY -> day.atStartOfDay();
            case PREVIOUS_WEEK -> WEEK.from(day, null);
            case PREVIOUS_MONTH -> MONTH.from(day, null);
            case PREVIOUS_YEAR -> YEAR.from(day, null);
        };
    }
}
