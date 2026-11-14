package com.intermarche.edge.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StorePeriodClose} (BO-03-12-02): the finders and the
 * splitting of the chained actions — null, blank and written.
 */
class StorePeriodCloseTest {

    /** The actions split one per line; none written reads as none. */
    @Test
    void actionLinesSplit() {
        StorePeriodClose close = new StorePeriodClose();
        assertTrue(close.actionLines().isEmpty());
        close.actions = " ";
        assertTrue(close.actionLines().isEmpty());
        close.actions = "Z_REPORT: a\nBANK_DEPOSIT: b";
        assertEquals(List.of("Z_REPORT: a", "BANK_DEPOSIT: b"), close.actionLines());
        assertEquals(StorePeriodClose.Status.CLOSED, close.status);
    }

    /** The day finder short-circuits a null day; the latest are paged most recent first. */
    @Test
    @SuppressWarnings("unchecked")
    void findersResolve() {
        StorePeriodClose close = new StorePeriodClose();
        LocalDate day = LocalDate.of(2026, 10, 25);
        PanacheQuery<StorePeriodClose> byDay = mock(PanacheQuery.class);
        when(byDay.firstResult()).thenReturn(close);
        PanacheQuery<StorePeriodClose> latest = mock(PanacheQuery.class);
        PanacheQuery<StorePeriodClose> page = mock(PanacheQuery.class);
        when(latest.page(0, 5)).thenReturn((PanacheQuery) page);
        when(page.list()).thenReturn(List.of(close));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> StorePeriodClose.find("closeDate", day)).thenReturn(byDay);
            ms.when(() -> StorePeriodClose.find("order by closeDate desc")).thenReturn(latest);
            assertSame(close, StorePeriodClose.findByDate(day));
            assertEquals(List.of(close), StorePeriodClose.latest(5));
        }
        assertNull(StorePeriodClose.findByDate(null));
    }
}
