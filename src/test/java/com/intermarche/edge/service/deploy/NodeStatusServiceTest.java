package com.intermarche.edge.service.deploy;

import com.intermarche.edge.domain.deploy.NodeIntegration;
import com.intermarche.edge.domain.deploy.NodeInventoryItem;
import com.intermarche.edge.domain.deploy.NodeStatus;
import com.intermarche.edge.service.NodeEchelonService;
import com.intermarche.edge.service.PosSettingsService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link NodeStatusService}. */
class NodeStatusServiceTest {

    /**
     * Builds the service over mocked collaborators.
     *
     * @return the service
     */
    private NodeStatusService service() {
        NodeStatusService service = new NodeStatusService();
        service.posSettingsService = mock(PosSettingsService.class);
        service.nodeEchelonService = mock(NodeEchelonService.class);
        service.functionUsageService = mock(FunctionUsageService.class);
        service.maintenanceService = mock(MaintenanceService.class);
        service.deploymentPlanner = mock(DeploymentPlanner.class);
        return service;
    }

    /**
     * Builds a report.
     *
     * @param kind its kind
     * @param pdv its point of sale
     * @param id its identifier
     * @return the report
     */
    private DeployPayloads.NodeReport report(String kind, String pdv, String id) {
        DeployPayloads.NodeReport report = new DeployPayloads.NodeReport();
        report.kind = kind;
        report.pdvNumber = pdv;
        report.nodeId = id;
        report.version = "1.0";
        return report;
    }

    /** BO-08-04-15/16/20: a register's report is written whole, its peripherals, integrations and inventory. */
    @Test
    void writeRecordsTheWholeReport() {
        DeployPayloads.NodeReport report = report("REGISTER", "07039", "C04");
        report.updateState = "FAILED";
        report.updateTarget = "2.0";
        report.updateMessage = "boom";
        report.appOk = false;
        report.appDetail = "boom";
        report.outboxPending = 2;
        report.lastTicketAt = "2026-10-24T09:00";
        report.reportedAt = "2026-10-24T09:05";
        DeployPayloads.Peripheral printer = new DeployPayloads.Peripheral();
        printer.role = "Imprimante";
        printer.state = "FAILED";
        DeployPayloads.Peripheral scale = new DeployPayloads.Peripheral();
        scale.role = "Balance";
        scale.state = "AVAILABLE";
        report.peripherals = List.of(printer, scale);
        report.integrations = List.of(UpstreamReportService.integration("VAT_RATES", false, null, "down"),
                UpstreamReportService.integration("PRODUCTS", true, LocalDateTime.of(2026, 10, 24, 8, 0), null));
        report.inventory = List.of(UpstreamReportService.item("SOFTWARE", "Java", "21"));
        NodeStatusService service = service();
        try (MockedStatic<NodeStatus> statuses = mockStatic(NodeStatus.class);
             MockedStatic<NodeIntegration> integrations = mockStatic(NodeIntegration.class);
             MockedStatic<NodeInventoryItem> inventory = mockStatic(NodeInventoryItem.class);
             MockedConstruction<NodeStatus> rows = mockConstruction(NodeStatus.class);
             MockedConstruction<NodeIntegration> lines = mockConstruction(NodeIntegration.class);
             MockedConstruction<NodeInventoryItem> items = mockConstruction(NodeInventoryItem.class)) {
            NodeStatus status = service.write(report);
            assertSame(rows.constructed().get(0), status);
            assertEquals("REGISTER:07039:C04", status.nodeKey);
            assertEquals("Imprimante=FAILED;Balance=AVAILABLE", status.peripherals);
            assertFalse(status.peripheralOk);
            assertFalse(status.syncOk);
            assertEquals("Intégrations en erreur : VAT_RATES", status.syncDetail);
            assertEquals(LocalDateTime.of(2026, 10, 24, 9, 5), status.reportedAt);
            assertEquals(LocalDateTime.of(2026, 10, 24, 9, 0), status.lastTicketAt);
            assertEquals("2.0", status.updateTarget);
            assertEquals(2, lines.constructed().size());
            assertNull(lines.constructed().get(0).integratedAt);
            assertEquals("Java", items.constructed().get(0).name);
            verify(status).persist();
            integrations.verify(() -> NodeIntegration.deleteByNode("REGISTER:07039:C04"));
            inventory.verify(() -> NodeInventoryItem.deleteByNode("REGISTER:07039:C04"));
            verify(service.deploymentPlanner).recordProgress(status);
        }
    }

    /** BO-08-04-16: a push error alone, or with failed integrations, makes the sync KO; a healthy report reads OK. */
    @Test
    void writeComputesTheSyncHealth() {
        NodeStatus existing = mock(NodeStatus.class);
        NodeStatusService service = service();
        try (MockedStatic<NodeStatus> statuses = mockStatic(NodeStatus.class);
             MockedStatic<NodeIntegration> integrations = mockStatic(NodeIntegration.class);
             MockedStatic<NodeInventoryItem> inventory = mockStatic(NodeInventoryItem.class);
             MockedConstruction<NodeIntegration> lines = mockConstruction(NodeIntegration.class)) {
            statuses.when(() -> NodeStatus.findByKey(anyString())).thenReturn(existing);
            DeployPayloads.NodeReport report = report("STORE", "07039", "07039");
            report.outboxError = "HTTP 503";
            assertSame(existing, service.write(report));
            assertFalse(existing.syncOk);
            assertEquals("HTTP 503 ", existing.syncDetail);
            assertNotNull(existing.reportedAt);
            report.integrations = List.of(UpstreamReportService.integration("PRODUCTS", false, null, "x"));
            service.write(report);
            assertEquals("HTTP 503 Intégrations en erreur : PRODUCTS", existing.syncDetail);
            report.outboxError = null;
            report.integrations = List.of();
            service.write(report);
            assertTrue(existing.syncOk);
            assertTrue(existing.peripheralOk);
            assertNull(existing.syncDetail);
            assertEquals("", existing.peripherals);
        }
    }

    /**
     * BO-08-03-*, BO-08-04-17: a store's report writes its registers too, a register without point of sale
     * gets this node's, each node gets its target and its orders, the counters and outcomes are folded in.
     */
    @Test
    void receiveWritesAndAnswers() {
        DeployPayloads.NodeReport store = report("STORE", "07039", "07039");
        DeployPayloads.NodeReport unnamed = report("REGISTER", null, "C04");
        DeployPayloads.NodeReport named = report("REGISTER", "07039", "C05");
        store.registers = List.of(unnamed, named);
        DeployPayloads.Action order = new DeployPayloads.Action();
        DeployPayloads.Target target = new DeployPayloads.Target();
        NodeStatusService service = service();
        when(service.nodeEchelonService.currentPdv()).thenReturn("07039");
        when(service.deploymentPlanner.targetFor("REGISTER", "07039", "C04")).thenReturn(target);
        when(service.maintenanceService.handOut("STORE:07039:07039")).thenReturn(List.of(order));
        try (MockedStatic<NodeStatus> statuses = mockStatic(NodeStatus.class);
             MockedStatic<NodeIntegration> integrations = mockStatic(NodeIntegration.class);
             MockedStatic<NodeInventoryItem> inventory = mockStatic(NodeInventoryItem.class);
             MockedConstruction<NodeStatus> rows = mockConstruction(NodeStatus.class)) {
            DeployPayloads.ReportAnswer answer = service.receive(store);
            assertEquals(List.of(target), answer.targets);
            assertEquals(List.of(order), answer.actions);
            assertEquals("07039", unnamed.pdvNumber);
            assertEquals(3, rows.constructed().size());
        }
        verify(service.functionUsageService).merge(store.usages);
        verify(service.maintenanceService).recordResults(store.actionResults);
    }

    /** BO-08-03-*: without a local lot, a store relays the central node's decision; without either, nothing. */
    @Test
    void targetForRelaysTheCentralDecision() {
        NodeStatusService service = service();
        NodeStatus status = new NodeStatus();
        status.kind = "REGISTER";
        status.pdvNumber = "07039";
        status.nodeId = "C04";
        assertNull(service.targetFor(status));
        status.targetVersion = "2.0";
        status.targetProduct = "IMPOS";
        status.targetChecksum = "abc";
        status.targetInWindow = true;
        status.targetForceToken = "tok";
        status.targetLot = "L1";
        DeployPayloads.Target relayed = service.targetFor(status);
        assertEquals("2.0", relayed.version);
        assertEquals("abc", relayed.checksum);
        assertTrue(relayed.inWindow);
        assertEquals("tok", relayed.forceToken);
        assertEquals("L1", relayed.lotCode);
        assertEquals("IMPOS", relayed.product);
        assertEquals("C04", relayed.nodeId);
    }

    /** BO-08-03-*: the central node's decision is kept on a known register; an unknown one is ignored. */
    @Test
    void keepRelayedTargetStoresTheDecision() {
        DeployPayloads.Target target = new DeployPayloads.Target();
        target.kind = "REGISTER";
        target.pdvNumber = "07039";
        target.nodeId = "C04";
        target.product = "IMPOS";
        target.version = "2.0";
        target.checksum = "abc";
        target.inWindow = true;
        target.forceToken = "tok";
        target.lotCode = "L1";
        NodeStatus status = mock(NodeStatus.class);
        try (MockedStatic<NodeStatus> statuses = mockStatic(NodeStatus.class)) {
            service().keepRelayedTarget(target);
            statuses.when(() -> NodeStatus.findByKey("REGISTER:07039:C04")).thenReturn(status);
            service().keepRelayedTarget(target);
        }
        assertEquals("2.0", status.targetVersion);
        assertEquals("L1", status.targetLot);
        assertTrue(status.targetInWindow);
        verify(status).persist();
    }

    /** BO-08-04-20: a register's last report is rebuilt for the store's report to the central node. */
    @Test
    void registerReportsRebuildTheReports() {
        NodeStatus full = new NodeStatus();
        full.nodeKey = "REGISTER:07039:C04";
        full.kind = "REGISTER";
        full.nodeId = "C04";
        full.pdvNumber = "07039";
        full.peripherals = "Imprimante=FAILED;broken";
        full.syncOk = false;
        full.syncDetail = "HTTP 503";
        full.lastTicketAt = LocalDateTime.of(2026, 10, 24, 9, 0);
        full.reportedAt = LocalDateTime.of(2026, 10, 24, 9, 5);
        NodeStatus bare = new NodeStatus();
        bare.nodeKey = "REGISTER:07039:C05";
        NodeIntegration dated = new NodeIntegration();
        dated.domain = "PRODUCTS";
        dated.outcome = "APPLIED";
        dated.integratedAt = LocalDateTime.of(2026, 10, 24, 8, 0);
        NodeIntegration undated = new NodeIntegration();
        undated.domain = "VAT_RATES";
        NodeInventoryItem item = new NodeInventoryItem();
        item.category = "SOFTWARE";
        item.name = "Java";
        item.detail = "21";
        try (MockedStatic<NodeStatus> statuses = mockStatic(NodeStatus.class);
             MockedStatic<NodeIntegration> integrations = mockStatic(NodeIntegration.class);
             MockedStatic<NodeInventoryItem> inventory = mockStatic(NodeInventoryItem.class)) {
            statuses.when(() -> NodeStatus.listByKind("REGISTER")).thenReturn(List.of(full, bare));
            integrations.when(() -> NodeIntegration.listByNode(full.nodeKey)).thenReturn(List.of(dated, undated));
            integrations.when(() -> NodeIntegration.listByNode(bare.nodeKey)).thenReturn(List.of());
            inventory.when(() -> NodeInventoryItem.listByNode(anyString())).thenReturn(List.of(item));
            List<DeployPayloads.NodeReport> reports = service().registerReports();
            DeployPayloads.NodeReport first = reports.get(0);
            assertEquals(1, first.peripherals.size());
            assertEquals("FAILED", first.peripherals.get(0).state);
            assertEquals("HTTP 503", first.outboxError);
            assertEquals("2026-10-24T09:00", first.lastTicketAt);
            assertEquals("2026-10-24T09:05", first.reportedAt);
            assertEquals("2026-10-24T08:00", first.integrations.get(0).at);
            assertNull(first.integrations.get(1).at);
            assertEquals("Java", first.inventory.get(0).name);
            DeployPayloads.NodeReport second = reports.get(1);
            assertNull(second.outboxError);
            assertNull(second.lastTicketAt);
            assertNull(second.reportedAt);
            assertTrue(second.peripherals.isEmpty());
        }
    }

    /** The statuses are read, the tolerance is three report periods, the helpers keep null and cut long texts. */
    @Test
    void readersAndHelpers() {
        NodeStatusService service = service();
        when(service.posSettingsService.supervisionReportSeconds()).thenReturn(60);
        NodeStatus status = new NodeStatus();
        try (MockedStatic<NodeStatus> statuses = mockStatic(NodeStatus.class)) {
            statuses.when(() -> NodeStatus.findByKey("K")).thenReturn(status);
            statuses.when(NodeStatus::listOrdered).thenReturn(List.of(status));
            assertSame(status, service.find("K"));
            assertEquals(List.of(status), service.all());
        }
        assertEquals(180L, service.toleranceSeconds());
        assertNull(NodeStatusService.time(null));
        assertNull(NodeStatusService.cut(null, 3));
        assertEquals("abc", NodeStatusService.cut("abcdef", 3));
        assertEquals("ab", NodeStatusService.cut("ab", 3));
        verify(service.posSettingsService).supervisionReportSeconds();
    }
    /**
     * BO-02-05-05: the revenue a store states is filed under its point of sale;
     * its registers state none and file none.
     */
    @Test
    void receiveFilesTheRevenueOfTheStore() {
        DeployPayloads.NodeReport store = report("STORE", "07039", "07039");
        store.registers = List.of(report("REGISTER", "07039", "C05"));
        NodeStatusService service = service();
        service.pdvRevenueService = mock(PdvRevenueService.class);
        try (MockedStatic<NodeStatus> statuses = mockStatic(NodeStatus.class);
             MockedStatic<NodeIntegration> integrations = mockStatic(NodeIntegration.class);
             MockedStatic<NodeInventoryItem> inventory = mockStatic(NodeInventoryItem.class);
             MockedConstruction<NodeStatus> rows = mockConstruction(NodeStatus.class)) {
            service.receive(store);
        }
        verify(service.pdvRevenueService, org.mockito.Mockito.times(1)).record("07039", store.revenues);
    }
}
