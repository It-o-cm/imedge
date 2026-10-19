package com.intermarche.edge.ui.report;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReportPeriod}.
 * <p>
 * Branch enumeration: {@code from} and {@code to} each switch on the six
 * periods, and each has the null / non-null arm of the reference date;
 * {@link ReportPeriod#RANGE} has in addition the null / non-null arm of its own
 * bound. The reference used throughout is a WEDNESDAY, so the week's bounds are
 * visibly the Monday before and the Monday after rather than the reference
 * itself.
 */
class ReportPeriodTest {

    /** A Wednesday, mid-month, mid-year. */
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 9, 16);

    /**
     * The day period runs from the reference's midnight to the next.
     */
    @Test
    void dayRunsFromMidnightToMidnight() {
        assertEquals(LocalDateTime.of(2026, 9, 16, 0, 0), ReportPeriod.DAY.from(WEDNESDAY, null));
        assertEquals(LocalDateTime.of(2026, 9, 17, 0, 0), ReportPeriod.DAY.to(WEDNESDAY, null));
    }

    /**
     * The week starts on the Monday before the reference and ends on the next.
     */
    @Test
    void weekStartsOnTheMondayBefore() {
        assertEquals(LocalDateTime.of(2026, 9, 14, 0, 0), ReportPeriod.WEEK.from(WEDNESDAY, null));
        assertEquals(LocalDateTime.of(2026, 9, 21, 0, 0), ReportPeriod.WEEK.to(WEDNESDAY, null));
    }

    /**
     * The month runs from its first day to the first of the next.
     */
    @Test
    void monthRunsFromTheFirstToTheFirst() {
        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), ReportPeriod.MONTH.from(WEDNESDAY, null));
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), ReportPeriod.MONTH.to(WEDNESDAY, null));
    }

    /**
     * The year runs from the first of January to the next.
     */
    @Test
    void yearRunsFromJanuaryToJanuary() {
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), ReportPeriod.YEAR.from(WEDNESDAY, null));
        assertEquals(LocalDateTime.of(2027, 1, 1, 0, 0), ReportPeriod.YEAR.to(WEDNESDAY, null));
    }

    /**
     * The current day ignores the reference entirely and reads the clock.
     */
    @Test
    void todayIgnoresTheReference() {
        assertEquals(LocalDate.now().atStartOfDay(), ReportPeriod.TODAY.from(WEDNESDAY, null));
        assertEquals(LocalDate.now().plusDays(1).atStartOfDay(), ReportPeriod.TODAY.to(WEDNESDAY, null));
    }

    /**
     * A free range runs from its first day to the day after its last, so the
     * last day is covered whole.
     */
    @Test
    void rangeCoversItsLastDayWhole() {
        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0),
                ReportPeriod.RANGE.from(WEDNESDAY, LocalDate.of(2026, 9, 1)));
        assertEquals(LocalDateTime.of(2026, 9, 11, 0, 0),
                ReportPeriod.RANGE.to(WEDNESDAY, LocalDate.of(2026, 9, 10)));
    }

    /**
     * A range with no bound of its own falls back to the reference date (the
     * null arm of both range bounds).
     */
    @Test
    void rangeWithoutBoundsFallsBackToTheReference() {
        assertEquals(LocalDateTime.of(2026, 9, 16, 0, 0), ReportPeriod.RANGE.from(WEDNESDAY, null));
        assertEquals(LocalDateTime.of(2026, 9, 17, 0, 0), ReportPeriod.RANGE.to(WEDNESDAY, null));
    }

    /**
     * A null reference is read as today, on both bounds (the null arm of the
     * reference, on a period that uses it).
     */
    @Test
    void nullReferenceIsReadAsToday() {
        assertEquals(LocalDate.now().atStartOfDay(), ReportPeriod.DAY.from(null, null));
        assertEquals(LocalDate.now().plusDays(1).atStartOfDay(), ReportPeriod.DAY.to(null, null));
    }

    /**
     * Every period carries a label for its selector.
     */
    @Test
    void everyPeriodCarriesALabel() {
        for (ReportPeriod period : ReportPeriod.values()) {
            assertTrue(period.getLabel() != null && !period.getLabel().isBlank());
        }
    }
}
