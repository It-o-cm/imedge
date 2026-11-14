package com.intermarche.edge.domain.session;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link ReportSchedule}: its two finders query what they name,
 * and its due moment reads as the screens show it.
 */
class ReportScheduleTest {

    /**
     * The due extractions are the ones not run whose moment came, oldest first;
     * a report's extractions are listed most recent first.
     */
    @Test
    void theFindersQueryWhatTheyName() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 25, 6, 0);
        List<ReportSchedule> rows = List.of(new ReportSchedule());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("done = false and runAt <= ?1 order by runAt", now)).thenReturn(rows);
            panache.when(() -> PanacheEntityBase.list("reportCode = ?1 order by runAt desc", "rpt")).thenReturn(rows);
            assertSame(rows, ReportSchedule.due(now));
            assertSame(rows, ReportSchedule.ofReport("rpt"));
        }
    }

    /**
     * The due moment reads {@code dd/MM/yyyy HH:mm}, empty when unset.
     */
    @Test
    void theDueMomentIsFormatted() {
        ReportSchedule schedule = new ReportSchedule();
        org.junit.jupiter.api.Assertions.assertEquals("", schedule.getRunAtFormatted());
        schedule.runAt = LocalDateTime.of(2026, 10, 25, 6, 5);
        org.junit.jupiter.api.Assertions.assertEquals("25/10/2026 06:05", schedule.getRunAtFormatted());
    }
}
