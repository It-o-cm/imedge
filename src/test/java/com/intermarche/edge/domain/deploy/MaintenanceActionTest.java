package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Unit tests for {@link MaintenanceAction}. */
class MaintenanceActionTest {

    /** BO-08-04-17: each order type says which node kinds carry it out. */
    @Test
    void typesNameTheirNodes() {
        assertTrue(MaintenanceAction.ActionType.TERMINAL_RESET.appliesTo("REGISTER"));
        assertFalse(MaintenanceAction.ActionType.TERMINAL_RESET.appliesTo("STORE"));
        assertFalse(MaintenanceAction.ActionType.INTEGRATION_RETRY.appliesTo("REGISTER"));
        assertTrue(MaintenanceAction.ActionType.INTEGRATION_RETRY.appliesTo("CENTRAL"));
        assertEquals("Relance du TPV", MaintenanceAction.ActionType.TERMINAL_RESET.getLabel());
        assertEquals(MaintenanceAction.Status.PENDING, new MaintenanceAction().status);
    }

    /** BO-08-04-17: the order book is read by token, by node, by outcome to forward and by age. */
    @Test
    @SuppressWarnings("unchecked")
    void findersReadTheOrderBook() {
        MaintenanceAction row = new MaintenanceAction();
        List<Object> rows = List.of(row);
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        PanacheQuery<Object> ordered = mock(PanacheQuery.class);
        PanacheQuery<Object> page = mock(PanacheQuery.class);
        when(ordered.page(0, 10)).thenReturn(page);
        when(page.list()).thenReturn(rows);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("actionUid", "u")).thenReturn(query);
            panache.when(() -> PanacheEntityBase.list("nodeKey = ?1 and status = ?2 order by issuedAt", "K",
                    MaintenanceAction.Status.PENDING)).thenReturn(rows);
            panache.when(() -> PanacheEntityBase.list("origin = ?1 and forwarded = false and status in ?2", "CENTRAL",
                    List.of(MaintenanceAction.Status.DONE, MaintenanceAction.Status.FAILED))).thenReturn(rows);
            panache.when(() -> PanacheEntityBase.find("order by issuedAt desc, id desc")).thenReturn(ordered);
            assertSame(row, MaintenanceAction.findByUid("u"));
            assertSame(rows, MaintenanceAction.pendingFor("K"));
            assertSame(rows, MaintenanceAction.unforwardedOutcomes());
            assertSame(rows, MaintenanceAction.latest(10));
        }
    }
}
