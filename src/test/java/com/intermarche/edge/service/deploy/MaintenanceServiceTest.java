package com.intermarche.edge.service.deploy;

import com.intermarche.edge.domain.deploy.MaintenanceAction;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link MaintenanceService}. */
class MaintenanceServiceTest {

    /**
     * Makes the token finder answer an order.
     *
     * @param panache the static mock
     * @param row the order, possibly null
     */
    @SuppressWarnings("unchecked")
    private void byUid(MockedStatic<PanacheEntityBase> panache, MaintenanceAction row) {
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        panache.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class))).thenReturn(query);
    }

    /**
     * Builds an order as it travels.
     *
     * @param uid its token
     * @return the order
     */
    private DeployPayloads.Action order(String uid) {
        DeployPayloads.Action order = new DeployPayloads.Action();
        order.uid = uid;
        order.kind = "REGISTER";
        order.pdvNumber = "07039";
        order.nodeId = "C04";
        order.type = "TERMINAL_RESET";
        order.issuedBy = "Admin";
        order.reason = "TPV figé";
        return order;
    }

    /** BO-08-04-17: an order a node kind cannot carry out is refused; another is kept pending. */
    @Test
    void issueKeepsAnApplicableOrder() {
        MaintenanceService service = new MaintenanceService();
        assertNull(service.issue("STORE", "07039", "07039", MaintenanceAction.ActionType.TERMINAL_RESET,
                "Admin", null, "STORE"));
        try (MockedConstruction<MaintenanceAction> rows = mockConstruction(MaintenanceAction.class)) {
            MaintenanceAction action = service.issue("REGISTER", "07039", "C04",
                    MaintenanceAction.ActionType.TERMINAL_RESET, "Admin", "TPV figé", "CENTRAL");
            assertSame(rows.constructed().get(0), action);
            assertEquals("REGISTER:07039:C04", action.nodeKey);
            assertEquals("CENTRAL", action.origin);
            assertNotNull(action.actionUid);
            assertNotNull(action.issuedAt);
            verify(action).persist();
        }
    }

    /** BO-08-04-17: a central order is relayed once under its own token. */
    @Test
    void relayKeepsACentralOrderOnce() {
        MaintenanceService service = new MaintenanceService();
        MaintenanceAction known = new MaintenanceAction();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<MaintenanceAction> rows = mockConstruction(MaintenanceAction.class)) {
            byUid(panache, known);
            service.relay(order("u1"));
            assertEquals(0, rows.constructed().size());
        }
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<MaintenanceAction> rows = mockConstruction(MaintenanceAction.class)) {
            byUid(panache, null);
            service.relay(order("u1"));
            MaintenanceAction kept = rows.constructed().get(0);
            assertEquals("u1", kept.actionUid);
            assertEquals("CENTRAL", kept.origin);
            assertEquals(MaintenanceAction.ActionType.TERMINAL_RESET, kept.actionType);
            assertEquals("REGISTER:07039:C04", kept.nodeKey);
            verify(kept).persist();
        }
    }

    /** BO-08-04-17: the pending orders are handed out and marked sent. */
    @Test
    void handOutMarksTheOrdersSent() {
        MaintenanceAction action = mock(MaintenanceAction.class);
        action.actionUid = "u1";
        action.kind = "REGISTER";
        action.nodeId = "C04";
        action.pdvNumber = "07039";
        action.actionType = MaintenanceAction.ActionType.PRINTER_RESET;
        action.issuedBy = "Admin";
        action.reason = "papier";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class))).thenReturn(List.of(action));
            List<DeployPayloads.Action> orders = new MaintenanceService().handOut("REGISTER:07039:C04");
            assertEquals(1, orders.size());
            assertEquals("PRINTER_RESET", orders.get(0).type);
            assertEquals("u1", orders.get(0).uid);
            assertEquals("papier", orders.get(0).reason);
        }
        assertEquals(MaintenanceAction.Status.SENT, action.status);
        assertNotNull(action.sentAt);
        verify(action).persist();
    }

    /** BO-08-04-17: outcomes are stamped, an unknown token ignored, a long result cut, a missing date dated now. */
    @Test
    void recordResultsStampsTheOutcomes() {
        MaintenanceAction action = mock(MaintenanceAction.class);
        DeployPayloads.ActionResult failed = MaintenanceService.result("u1", false, "x".repeat(400), null);
        DeployPayloads.ActionResult done = MaintenanceService.result("u1", true, null,
                LocalDateTime.of(2026, 10, 24, 9, 30));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            byUid(panache, action);
            new MaintenanceService().recordResults(List.of(failed));
            assertEquals(MaintenanceAction.Status.FAILED, action.status);
            assertEquals(300, action.result.length());
            assertNotNull(action.doneAt);
            new MaintenanceService().recordResults(List.of(done));
            assertEquals(MaintenanceAction.Status.DONE, action.status);
            assertEquals("", action.result);
            assertEquals(LocalDateTime.of(2026, 10, 24, 9, 30), action.doneAt);
        }
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            byUid(panache, null);
            new MaintenanceService().recordResults(List.of(done));
        }
    }

    /** BO-08-04-17: the central orders' outcomes go back up once. */
    @Test
    void unforwardedSendsTheOutcomesOnce() {
        MaintenanceAction done = mock(MaintenanceAction.class);
        done.actionUid = "u1";
        done.status = MaintenanceAction.Status.DONE;
        done.result = "ok";
        done.doneAt = LocalDateTime.of(2026, 10, 24, 9, 30);
        MaintenanceAction failed = mock(MaintenanceAction.class);
        failed.actionUid = "u2";
        failed.status = MaintenanceAction.Status.FAILED;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class)))
                    .thenReturn(List.of(done, failed));
            List<DeployPayloads.ActionResult> results = new MaintenanceService().unforwarded();
            assertTrue(results.get(0).success);
            assertEquals("2026-10-24T09:30", results.get(0).doneAt);
            assertFalse(results.get(1).success);
            assertNull(results.get(1).doneAt);
        }
        assertTrue(done.forwarded && failed.forwarded);
        verify(done).persist();
    }

    /** BO-08-04-17: the history reads the latest orders. */
    @Test
    @SuppressWarnings("unchecked")
    void historyReadsTheLatestOrders() {
        List<Object> rows = List.of(new MaintenanceAction());
        PanacheQuery<Object> ordered = mock(PanacheQuery.class);
        PanacheQuery<Object> page = mock(PanacheQuery.class);
        when(ordered.page(0, 5)).thenReturn(page);
        when(page.list()).thenReturn(rows);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("order by issuedAt desc, id desc")).thenReturn(ordered);
            assertSame(rows, new MaintenanceService().history(5));
        }
    }
}
