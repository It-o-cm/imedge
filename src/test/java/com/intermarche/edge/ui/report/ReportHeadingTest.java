package com.intermarche.edge.ui.report;

import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link ReportHeading}, exercising every arm of the midnight
 * compound guard and of the single-day/range ternary, plus the exact rendered
 * heading sentence.
 */
class ReportHeadingTest {

    /**
     * A whole-day period (both bounds at midnight, one day apart) renders as the
     * single inclusive day, not as a « du … au … » range.
     */
    @Test
    void periodRendersSingleWholeDay() {
        ReportCriteria criteria = new ReportCriteria();
        criteria.period = ReportPeriod.DAY;
        criteria.reference = LocalDate.of(2025, 9, 1);
        Assertions.assertEquals("Jour : 01/09/2025", ReportHeading.period(criteria));
    }

    /**
     * A whole-day period spanning several days (both bounds at midnight, more
     * than one day apart) renders the inclusive « du … au … » range, dropping the
     * exclusive upper bound by one day.
     */
    @Test
    void periodRendersWholeDayRange() {
        ReportCriteria criteria = new ReportCriteria();
        criteria.period = ReportPeriod.RANGE;
        criteria.rangeFrom = LocalDate.of(2025, 9, 1);
        criteria.rangeTo = LocalDate.of(2025, 9, 3);
        Assertions.assertEquals("Plage de dates : du 01/09/2025 au 03/09/2025",
                ReportHeading.period(criteria));
    }

    /**
     * When the upper bound is not midnight, the first condition of the compound
     * guard is false and the heading uses the day-and-time format for both bounds.
     */
    @Test
    void periodRendersMomentWhenUpperBoundNotMidnight() {
        ReportCriteria criteria = Mockito.mock(ReportCriteria.class);
        criteria.period = ReportPeriod.MONTH;
        Mockito.when(criteria.from()).thenReturn(LocalDateTime.of(2025, 9, 1, 8, 30));
        Mockito.when(criteria.to()).thenReturn(LocalDateTime.of(2025, 9, 2, 18, 45));
        Assertions.assertEquals("Mois : du 01/09/2025 08:30 au 02/09/2025 18:45",
                ReportHeading.period(criteria));
        Mockito.verify(criteria).from();
        Mockito.verify(criteria).to();
    }

    /**
     * When the upper bound is midnight but the lower bound is not, the first
     * condition of the compound guard is true and the second false, so the
     * heading still uses the day-and-time format.
     */
    @Test
    void periodRendersMomentWhenLowerBoundNotMidnight() {
        ReportCriteria criteria = Mockito.mock(ReportCriteria.class);
        criteria.period = ReportPeriod.YEAR;
        Mockito.when(criteria.from()).thenReturn(LocalDateTime.of(2025, 9, 1, 8, 30));
        Mockito.when(criteria.to()).thenReturn(LocalDateTime.of(2025, 9, 2, 0, 0));
        Assertions.assertEquals("Année : du 01/09/2025 08:30 au 02/09/2025 00:00",
                ReportHeading.period(criteria));
        Mockito.verify(criteria).from();
        Mockito.verify(criteria).to();
    }

    /**
     * The utility class exposes only a private constructor; invoking it through
     * reflection proves it is not instantiable through normal use and completes
     * instruction coverage.
     */
    @Test
    void constructorIsPrivate() throws Exception {
        Constructor<ReportHeading> constructor = ReportHeading.class.getDeclaredConstructor();
        Assertions.assertFalse(constructor.canAccess(null));
        constructor.setAccessible(true);
        Assertions.assertNotNull(constructor.newInstance());
    }
}
