package com.intermarche.edge.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomerUplink} (BO-02-04-13): its finders resolve to
 * {@link PanacheEntityBase} and are intercepted by a static mock.
 */
class CustomerUplinkTest {

    /**
     * {@code findByAccount} answers null on a null account without querying,
     * and the queried row otherwise.
     */
    @Test
    @SuppressWarnings("unchecked")
    void findByAccountReadsTheRowOfTheAccount() {
        CustomerUplink row = new CustomerUplink();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertNull(CustomerUplink.findByAccount(null));
            panache.verifyNoInteractions();
            PanacheQuery<CustomerUplink> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(row);
            panache.when(() -> CustomerUplink.find("accountNumber", "C-1")).thenReturn(query);
            assertSame(row, CustomerUplink.findByAccount("C-1"));
        }
    }

    /**
     * The pending rows are read oldest first, a page at a time, and counted.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thePendingRowsAreReadAndCounted() {
        CustomerUplink row = new CustomerUplink();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<CustomerUplink> query = mock(PanacheQuery.class);
            PanacheQuery<CustomerUplink> page = mock(PanacheQuery.class);
            when(query.page(0, 5)).thenReturn(page);
            when(page.list()).thenReturn(List.of(row));
            panache.when(() -> CustomerUplink.find("acknowledgedAt is null order by id")).thenReturn(query);
            panache.when(() -> CustomerUplink.count("acknowledgedAt is null")).thenReturn(3L);
            assertEquals(List.of(row), CustomerUplink.nextPending(5));
            assertEquals(3L, CustomerUplink.countPending());
        }
    }

    /**
     * A row is pending until it is acknowledged.
     */
    @Test
    void aRowIsPendingUntilAcknowledged() {
        CustomerUplink row = new CustomerUplink();
        assertTrue(row.isPending());
        row.acknowledgedAt = LocalDateTime.of(2026, 10, 25, 10, 0);
        assertFalse(row.isPending());
    }
}
