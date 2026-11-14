package com.intermarche.edge.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Unit tests for {@link ReferentialRelease} (BO-03-12-03, BO-03-12-04). */
class ReferentialReleaseTest {

    /** A new release is pending and partial until told otherwise. */
    @Test
    void aNewReleaseIsPendingAndPartial() {
        ReferentialRelease release = new ReferentialRelease();
        assertEquals(ReferentialRelease.Status.PENDING, release.status);
        assertFalse(release.complete);
        assertEquals(3, ReferentialRelease.Status.values().length);
    }

    /** The finders read the pending, the due, one domain's and the closed releases. */
    @Test
    @SuppressWarnings("unchecked")
    void findersReadTheReleases() {
        ReferentialRelease release = new ReferentialRelease();
        List<Object> rows = List.of(release);
        LocalDateTime now = LocalDateTime.of(2026, 10, 25, 10, 0);
        PanacheQuery<Object> one = mock(PanacheQuery.class);
        when(one.firstResult()).thenReturn(release);
        PanacheQuery<Object> history = mock(PanacheQuery.class);
        PanacheQuery<Object> page = mock(PanacheQuery.class);
        when(history.page(0, 20)).thenReturn(page);
        when(page.list()).thenReturn(rows);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("status = ?1 order by effectiveAt, domain",
                    ReferentialRelease.Status.PENDING)).thenReturn(rows);
            panache.when(() -> PanacheEntityBase.find("status = ?1 and domain = ?2",
                    ReferentialRelease.Status.PENDING, "PRICES")).thenReturn(one);
            panache.when(() -> PanacheEntityBase.list("status = ?1 and effectiveAt <= ?2",
                    ReferentialRelease.Status.PENDING, now)).thenReturn(rows);
            panache.when(() -> PanacheEntityBase.find("status <> ?1 order by closedAt desc, id desc",
                    ReferentialRelease.Status.PENDING)).thenReturn(history);
            assertSame(rows, ReferentialRelease.listPending());
            assertSame(release, ReferentialRelease.findPending("PRICES"));
            assertSame(rows, ReferentialRelease.listDue(now));
            assertSame(rows, ReferentialRelease.listHistory(20));
        }
    }
}
