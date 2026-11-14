package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Unit tests for {@link DeploymentLot}. */
class DeploymentLotTest {

    /**
     * Builds a lot with a window.
     *
     * @param status its status
     * @return the lot, 10:00 to 12:00
     */
    private DeploymentLot lot(DeploymentLot.Status status) {
        DeploymentLot lot = new DeploymentLot();
        lot.status = status;
        lot.windowStart = LocalDateTime.of(2026, 10, 24, 10, 0);
        lot.windowEnd = LocalDateTime.of(2026, 10, 24, 12, 0);
        return lot;
    }

    /** BO-08-03-04: a draft distributes nothing; a launched lot from its start, in its window until its end. */
    @Test
    void theWindowGatesTheDistribution() {
        assertEquals(DeploymentLot.Status.DRAFT, new DeploymentLot().status);
        LocalDateTime inside = LocalDateTime.of(2026, 10, 24, 11, 0);
        assertFalse(lot(DeploymentLot.Status.DRAFT).started(inside));
        DeploymentLot lot = lot(DeploymentLot.Status.SCHEDULED);
        assertFalse(lot.started(LocalDateTime.of(2026, 10, 24, 9, 59)));
        assertTrue(lot.started(inside));
        assertTrue(lot.inWindow(inside));
        assertFalse(lot.inWindow(LocalDateTime.of(2026, 10, 24, 12, 1)));
        assertTrue(lot.started(LocalDateTime.of(2026, 10, 24, 12, 1)));
        assertFalse(lot.inWindow(LocalDateTime.of(2026, 10, 24, 9, 0)));
    }

    /** The lots are found by code, listed newest first, and the launched ones per product. */
    @Test
    @SuppressWarnings("unchecked")
    void findersReadTheLots() {
        DeploymentLot row = new DeploymentLot();
        List<Object> rows = List.of(row);
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "L1")).thenReturn(query);
            panache.when(() -> PanacheEntityBase.list("order by windowStart desc, id desc")).thenReturn(rows);
            panache.when(() -> PanacheEntityBase.list("status = ?1 and product = ?2 order by windowStart desc, id desc",
                    DeploymentLot.Status.SCHEDULED, "IMPOS")).thenReturn(rows);
            assertSame(row, DeploymentLot.findByCode("L1"));
            assertSame(rows, DeploymentLot.listOrdered());
            assertSame(rows, DeploymentLot.listScheduled("IMPOS"));
        }
    }
}
