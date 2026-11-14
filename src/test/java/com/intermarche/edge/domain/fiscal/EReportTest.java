package com.intermarche.edge.domain.fiscal;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link EReport} (BO-05-01-03): the statuses, the formatted
 * totals and the finders.
 */
class EReportTest {

    /**
     * Only an accepted period is closed for good.
     */
    @Test
    void onlyAnAcceptedPeriodIsClosed() {
        assertTrue(EReport.Status.ACCEPTED.isLast());
        assertFalse(EReport.Status.REJECTED.isLast());
    }

    /**
     * The finders read through Panache.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theFindersReadThroughPanache() {
        EReport report = new EReport();
        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 10);
        LocalDateTime now = LocalDateTime.of(2026, 10, 12, 9, 0);
        PanacheQuery<EReport> one = mock(PanacheQuery.class);
        when(one.firstResult()).thenReturn(report);
        PanacheQuery<EReport> many = mock(PanacheQuery.class);
        when(many.page(0, 3)).thenReturn(many);
        when(many.list()).thenReturn(List.of(report));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EReport.find("periodStart = ?1 and periodEnd = ?2", start, end)).thenReturn(one);
            panache.when(() -> EReport.find("ackId", "A1")).thenReturn(one);
            panache.when(() -> EReport.find("status in ?1 and (nextAttemptAt is null or nextAttemptAt <= ?2) "
                    + "and periodEnd < ?3 order by periodStart", List.of(EReport.Status.PENDING,
                    EReport.Status.FAILED), now, now.toLocalDate()))
                    .thenReturn(many);
            panache.when(() -> EReport.find("order by periodStart desc")).thenReturn(many);
            assertSame(report, EReport.forPeriod(start, end));
            assertSame(report, EReport.findByAck("A1"));
            assertEquals(List.of(report), EReport.due(now));
            assertEquals(List.of(report), EReport.latest(3));
        }
    }
}
