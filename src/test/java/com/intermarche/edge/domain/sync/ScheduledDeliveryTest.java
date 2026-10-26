package com.intermarche.edge.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link ScheduledDelivery}: the two custom finders that delegate
 * to the Panache {@code list} static. Under plain {@code mvn test} the entity is
 * not enhanced, so {@code list} resolves to {@link PanacheEntityBase} and is
 * intercepted with {@code mockStatic}; the finders' own bodies (query string and
 * arguments) run for real against the mock.
 */
class ScheduledDeliveryTest {

    /**
     * {@code findDuePending} asks for the PENDING rows due at or before now,
     * oldest first, and returns the list the store hands back verbatim.
     */
    @Test
    @SuppressWarnings("unchecked")
    void findDuePendingQueriesPendingRowsDueNowOldestFirst() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 26, 14, 30);
        List<ScheduledDelivery> expected = List.of(mock(ScheduledDelivery.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ScheduledDelivery.list(
                    "status = ?1 and scheduledAt <= ?2 order by scheduledAt",
                    ScheduledDelivery.Status.PENDING, now)).thenReturn(expected);
            List<ScheduledDelivery> due = ScheduledDelivery.findDuePending(now);
            assertSame(expected, due);
            assertEquals(1, due.size());
        }
    }

    /**
     * {@code listRecent} asks for every row, newest booking first, and returns
     * the list the store hands back verbatim.
     */
    @Test
    @SuppressWarnings("unchecked")
    void listRecentQueriesEveryRowNewestFirst() {
        List<ScheduledDelivery> expected = List.of(mock(ScheduledDelivery.class), mock(ScheduledDelivery.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ScheduledDelivery.list("order by scheduledAt desc")).thenReturn(expected);
            List<ScheduledDelivery> all = ScheduledDelivery.listRecent();
            assertSame(expected, all);
            assertEquals(2, all.size());
        }
    }

    /**
     * A fresh booking carries no feed (every feed), no status and no message
     * until the keeper fills them in.
     */
    @Test
    void aFreshBookingIsBlank() {
        ScheduledDelivery fresh = new ScheduledDelivery();
        assertNull(fresh.feedCode);
        assertNull(fresh.status);
        assertNull(fresh.message);
        assertEquals(3, ScheduledDelivery.Status.values().length);
    }
}
