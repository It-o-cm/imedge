package com.intermarche.edge.service;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PeriodScheduleService}.
 *
 * <p>Three parameters and one answer, so the tests enumerate what an
 * administrator can type: a named weekday and an unknown one, a day of month
 * inside the month, past it and at zero, a year-end date well formed, badly
 * formed and absent — plus the settings table that cannot be read at all,
 * which must leave the defaults standing rather than stop the end of period.
 * The day itself is always an end of period, and every test checks it.
 */
class PeriodScheduleServiceTest {

    /**
     * Builds a service whose parameters are a fresh mock answering the catalog
     * defaults.
     *
     * @return the service under test
     */
    private PeriodScheduleService service() {
        PeriodScheduleService service = new PeriodScheduleService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.periodWeekEndDay()).thenReturn("SUNDAY");
        when(service.posSettingsService.periodMonthEndDay()).thenReturn(0);
        when(service.posSettingsService.periodYearEndDate()).thenReturn("31/12");
        return service;
    }

    /** An ordinary Wednesday closes the day and nothing else. */
    @Test
    void anOrdinaryDayClosesTheDayAlone() {
        Set<PeriodScheduleService.PeriodEnd> ending =
                service().endingOn(LocalDate.of(2026, 10, 14));
        assertEquals(Set.of(PeriodScheduleService.PeriodEnd.DAY), ending);
    }

    /** The administered weekday closes the week as well as the day. */
    @Test
    void theAdministeredWeekdayClosesTheWeek() {
        Set<PeriodScheduleService.PeriodEnd> ending =
                service().endingOn(LocalDate.of(2026, 10, 18));
        assertTrue(ending.contains(PeriodScheduleService.PeriodEnd.WEEK));
        assertTrue(ending.contains(PeriodScheduleService.PeriodEnd.DAY));
    }

    /** Another administered weekday moves the week end with it. */
    @Test
    void anotherWeekdayMovesTheWeekEnd() {
        PeriodScheduleService service = service();
        when(service.posSettingsService.periodWeekEndDay()).thenReturn("friday");
        assertEquals(DayOfWeek.FRIDAY, service.weekEndDay());
        assertTrue(service.endingOn(LocalDate.of(2026, 10, 16))
                .contains(PeriodScheduleService.PeriodEnd.WEEK));
        assertFalse(service.endingOn(LocalDate.of(2026, 10, 18))
                .contains(PeriodScheduleService.PeriodEnd.WEEK));
    }

    /** An unknown, blank or null weekday falls back on Sunday. */
    @Test
    void anUnknownWeekdayFallsBackOnSunday() {
        PeriodScheduleService service = service();
        when(service.posSettingsService.periodWeekEndDay()).thenReturn("SAMEDI");
        assertEquals(DayOfWeek.SUNDAY, service.weekEndDay());
        when(service.posSettingsService.periodWeekEndDay()).thenReturn("   ");
        assertEquals(DayOfWeek.SUNDAY, service.weekEndDay());
        when(service.posSettingsService.periodWeekEndDay()).thenReturn(null);
        assertEquals(DayOfWeek.SUNDAY, service.weekEndDay());
    }

    /** A settings table that cannot be read leaves the defaults standing. */
    @Test
    void unreadableSettingsLeaveTheDefaults() {
        PeriodScheduleService service = service();
        when(service.posSettingsService.periodWeekEndDay())
                .thenThrow(new IllegalStateException("no db"));
        when(service.posSettingsService.periodMonthEndDay())
                .thenThrow(new IllegalStateException("no db"));
        when(service.posSettingsService.periodYearEndDate())
                .thenThrow(new IllegalStateException("no db"));
        assertEquals(DayOfWeek.SUNDAY, service.weekEndDay());
        assertEquals(31, service.monthEndDay(LocalDate.of(2026, 12, 1)));
        assertEquals(31, service.yearEndDate()[0]);
        assertEquals(12, service.yearEndDate()[1]);
        assertTrue(service.endingOn(LocalDate.of(2026, 12, 31))
                .contains(PeriodScheduleService.PeriodEnd.YEAR));
    }

    /** Zero means the month's LAST day, whichever month it is. */
    @Test
    void zeroMeansTheLastDayOfTheMonth() {
        PeriodScheduleService service = service();
        assertEquals(28, service.monthEndDay(LocalDate.of(2026, 2, 1)));
        assertEquals(31, service.monthEndDay(LocalDate.of(2026, 1, 1)));
        assertTrue(service.endingOn(LocalDate.of(2026, 2, 28))
                .contains(PeriodScheduleService.PeriodEnd.MONTH));
        assertFalse(service.endingOn(LocalDate.of(2026, 2, 27))
                .contains(PeriodScheduleService.PeriodEnd.MONTH));
    }

    /** A day inside the month is taken as it is. */
    @Test
    void anAdministeredDayInsideTheMonthIsTakenAsItIs() {
        PeriodScheduleService service = service();
        when(service.posSettingsService.periodMonthEndDay()).thenReturn(25);
        assertEquals(25, service.monthEndDay(LocalDate.of(2026, 3, 1)));
        assertTrue(service.endingOn(LocalDate.of(2026, 3, 25))
                .contains(PeriodScheduleService.PeriodEnd.MONTH));
    }

    /** A day past the month's length means its last day. */
    @Test
    void aDayPastTheMonthMeansItsLastDay() {
        PeriodScheduleService service = service();
        when(service.posSettingsService.periodMonthEndDay()).thenReturn(31);
        assertEquals(28, service.monthEndDay(LocalDate.of(2026, 2, 1)));
        assertTrue(service.endingOn(LocalDate.of(2026, 2, 28))
                .contains(PeriodScheduleService.PeriodEnd.MONTH));
    }

    /** A negative day means the month's last day too. */
    @Test
    void aNegativeDayMeansTheLastDayToo() {
        PeriodScheduleService service = service();
        when(service.posSettingsService.periodMonthEndDay()).thenReturn(-3);
        assertEquals(30, service.monthEndDay(LocalDate.of(2026, 4, 1)));
    }

    /** The administered year-end date closes the year, the month and the day. */
    @Test
    void theAdministeredYearEndClosesEverything() {
        Set<PeriodScheduleService.PeriodEnd> ending =
                service().endingOn(LocalDate.of(2026, 12, 31));
        assertTrue(ending.contains(PeriodScheduleService.PeriodEnd.YEAR));
        assertTrue(ending.contains(PeriodScheduleService.PeriodEnd.MONTH));
        assertTrue(ending.contains(PeriodScheduleService.PeriodEnd.DAY));
    }

    /** Another year-end date moves the year end with it. */
    @Test
    void anotherYearEndDateMovesIt() {
        PeriodScheduleService service = service();
        when(service.posSettingsService.periodYearEndDate()).thenReturn(" 30 / 6 ");
        assertEquals(30, service.yearEndDate()[0]);
        assertEquals(6, service.yearEndDate()[1]);
        assertTrue(service.endingOn(LocalDate.of(2026, 6, 30))
                .contains(PeriodScheduleService.PeriodEnd.YEAR));
    }

    /** A year-end date past its month falls on that month's last day. */
    @Test
    void aYearEndPastItsMonthFallsOnItsLastDay() {
        PeriodScheduleService service = service();
        when(service.posSettingsService.periodYearEndDate()).thenReturn("31/02");
        assertTrue(service.endingOn(LocalDate.of(2026, 2, 28))
                .contains(PeriodScheduleService.PeriodEnd.YEAR));
    }

    /** Every malformed year-end date falls back on the thirty-first of December. */
    @Test
    void aMalformedYearEndFallsBackOnNewYearsEve() {
        PeriodScheduleService service = service();
        for (String raw : new String[]{"31-12", "abc/12", "31/abc", "0/12", "31/13", "32/12", null, "   "}) {
            when(service.posSettingsService.periodYearEndDate()).thenReturn(raw);
            assertEquals(31, service.yearEndDate()[0], "day of " + raw);
            assertEquals(12, service.yearEndDate()[1], "month of " + raw);
        }
    }

    /** The four declared periods are the only ones. */
    @Test
    void thePeriodEnumHasFourConstants() {
        assertEquals(4, PeriodScheduleService.PeriodEnd.values().length);
    }
}
