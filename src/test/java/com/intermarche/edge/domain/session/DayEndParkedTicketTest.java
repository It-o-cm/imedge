package com.intermarche.edge.domain.session;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DayEndParkedTicket} (BO-09-01-05, -08, BO-09-02-01):
 * a fresh record is a recorded one; it is found by day and number, and the
 * latest are paged most recent first.
 */
class DayEndParkedTicketTest {

    /**
     * A fresh record says the ticket was recorded and left on hold.
     */
    @Test
    void aFreshRecordIsRecorded() {
        assertEquals(DayEndParkedTicket.Fate.RECORDED, new DayEndParkedTicket().fate);
    }

    /**
     * The finders query by day and number, and page the latest.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theFindersQueryTheRecords() {
        DayEndParkedTicket row = new DayEndParkedTicket();
        PanacheQuery<PanacheEntityBase> one = mock(PanacheQuery.class);
        when(one.firstResult()).thenReturn(row);
        PanacheQuery<PanacheEntityBase> all = mock(PanacheQuery.class);
        when(all.page(0, 5)).thenReturn(all);
        when(all.list()).thenReturn(List.of(row));
        LocalDate day = LocalDate.of(2026, 10, 24);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> PanacheEntityBase.find("closeDay = ?1 and ticketNumber = ?2", day, "C04-1")).thenReturn(one);
            ms.when(() -> PanacheEntityBase.find("order by recordedAt desc, id desc")).thenReturn(all);
            assertSame(row, DayEndParkedTicket.findFor(day, "C04-1"));
            assertEquals(List.of(row), DayEndParkedTicket.latest(5));
        }
    }
}
