package com.intermarche.edge.ui.supervision;

import com.intermarche.edge.domain.deploy.FunctionUsage;
import com.intermarche.edge.domain.deploy.NodeIntegration;
import com.intermarche.edge.domain.deploy.NodeInventoryItem;
import com.intermarche.edge.domain.deploy.NodeStatus;
import com.intermarche.edge.domain.store.NodeEchelon;
import com.intermarche.edge.domain.sync.IntegrationDeposit;
import com.intermarche.edge.service.deploy.NodeStatusService;
import com.intermarche.edge.service.sync.IntegrationRetryService;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Unit tests for {@link SupervisionConsoleService}. */
class SupervisionConsoleServiceTest {

    /**
     * Builds the service over mocked collaborators.
     *
     * @return the service, a three-minute offline tolerance
     */
    private SupervisionConsoleService service() {
        SupervisionConsoleService service = new SupervisionConsoleService();
        service.nodeStatusService = mock(NodeStatusService.class);
        service.integrationRetryService = mock(IntegrationRetryService.class);
        when(service.nodeStatusService.toleranceSeconds()).thenReturn(180L);
        return service;
    }

    /**
     * Places every point of sale in France, enseigne ITM; the unknown one nowhere.
     *
     * @param echelons the static mock
     */
    private void organisation(MockedStatic<NodeEchelon> echelons) {
        echelons.when(() -> NodeEchelon.of(anyString())).thenAnswer(invocation -> {
            String pdv = invocation.getArgument(0);
            return pdv.isEmpty() ? new NodeEchelon(null, null, null) : new NodeEchelon(pdv, "ITM", "FR");
        });
    }

    /**
     * Builds a healthy, online node.
     *
     * @param kind its kind
     * @param pdv its point of sale
     * @param id its identifier
     * @return the status
     */
    private NodeStatus node(String kind, String pdv, String id) {
        NodeStatus status = new NodeStatus();
        status.nodeKey = kind + ":" + pdv + ":" + id;
        status.kind = kind;
        status.pdvNumber = pdv;
        status.nodeId = id;
        status.version = "1.0";
        status.reportedAt = LocalDateTime.now();
        return status;
    }

    /**
     * Builds a filter.
     *
     * @param pairs name, value, name, value...
     * @return the filter
     */
    private ConsoleFilter filter(String... pairs) {
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.putSingle(pairs[i], pairs[i + 1]);
        }
        return ConsoleFilter.from(params);
    }

    /** BO-08-04-15: every node is listed with its four health counts and its anomalies; the chart is drawn. */
    @Test
    void activityListsEveryNode() {
        NodeStatus healthy = node("STORE", "07039", "07039");
        healthy.lastTicketAt = LocalDateTime.of(2026, 10, 24, 9, 0);
        NodeStatus broken = node("REGISTER", "07039", "C04");
        broken.reportedAt = null;
        broken.appOk = false;
        broken.peripheralOk = false;
        broken.syncOk = false;
        broken.syncDetail = "HTTP 503";
        NodeStatus app = node("REGISTER", "07039", "C05");
        app.appOk = false;
        app.appDetail = "update";
        NodeStatus central = node("CENTRAL", null, "CENTRAL");
        try (MockedStatic<NodeStatus> statuses = mockStatic(NodeStatus.class);
             MockedStatic<NodeEchelon> echelons = mockStatic(NodeEchelon.class)) {
            organisation(echelons);
            statuses.when(NodeStatus::listOrdered).thenReturn(List.of(healthy, broken, app, central));
            SupervisionConsoleService.ConsoleView view = service().activity(filter());
            assertEquals(4, view.rows.size());
            assertEquals(2, view.okCount);
            assertEquals(2, view.koCount);
            assertTrue(view.chart.startsWith("<svg"));
            SupervisionConsoleService.NodeRow first = (SupervisionConsoleService.NodeRow) view.rows.get(0);
            assertTrue(first.ok);
            assertEquals("", first.anomalies);
            assertEquals("24/10/2026 09:00", first.lastTicketAt);
            assertEquals("FR", first.country);
            SupervisionConsoleService.NodeRow second = (SupervisionConsoleService.NodeRow) view.rows.get(1);
            assertEquals("Hors ligne, Application, Périphérique, Synchronisation", second.anomalies);
            assertEquals(List.of("Hors ligne", "Application", "Périphérique", "Synchronisation"), second.anomalyLabels);
            assertEquals("HTTP 503", second.detail);
            assertNull(second.reportedAt);
            assertEquals("update", ((SupervisionConsoleService.NodeRow) view.rows.get(2)).detail);
            assertNull(((SupervisionConsoleService.NodeRow) view.rows.get(3)).country);
            List<List<Object>> lines = SupervisionConsoleService.activityLines(view);
            assertEquals("OK", lines.get(0).get(6));
            assertEquals("KO", lines.get(1).get(6));
            assertEquals("", lines.get(3).get(0));
            assertEquals(11, SupervisionConsoleService.activityColumns().size());
        }
    }

    /** BO-08-04-16: the registers in anomaly of one kind are kept; the kind, status and echelon filters apply. */
    @Test
    void theAnomalyFilterKeepsTheRegistersInTrouble() {
        NodeStatus offline = node("REGISTER", "07039", "C01");
        offline.reportedAt = LocalDateTime.now().minusHours(1);
        NodeStatus app = node("REGISTER", "07039", "C02");
        app.appOk = false;
        NodeStatus peripheral = node("REGISTER", "07039", "C03");
        peripheral.peripheralOk = false;
        NodeStatus sync = node("REGISTER", "07039", "C04");
        sync.syncOk = false;
        NodeStatus store = node("STORE", "07039", "07039");
        List<NodeStatus> all = List.of(offline, app, peripheral, sync, store);
        try (MockedStatic<NodeStatus> statuses = mockStatic(NodeStatus.class);
             MockedStatic<NodeEchelon> echelons = mockStatic(NodeEchelon.class)) {
            organisation(echelons);
            statuses.when(NodeStatus::listOrdered).thenReturn(all);
            SupervisionConsoleService service = service();
            for (String anomaly : List.of("OFFLINE", "APP", "PERIPHERAL", "SYNC")) {
                assertEquals(1, service.activity(filter("anomaly", anomaly)).rows.size(), anomaly);
            }
            assertEquals(1, service.activity(filter("kind", "STORE")).rows.size());
            assertEquals(4, service.activity(filter("status", "KO")).rows.size());
            assertEquals(0, service.activity(filter("pdv", "07040")).rows.size());
            assertEquals("", service.activity(filter("pdv", "07040")).chart);
            assertEquals(5, service.activity(filter("group", "GLOBAL")).rows.size());
        }
    }

    /** BO-08-01-17/19: integrations are listed OK or KO with their domains; the deposits come along. */
    @Test
    void integrationListsTheDomains() {
        NodeIntegration vat = new NodeIntegration();
        vat.pdvNumber = "07039";
        vat.kind = "STORE";
        vat.nodeId = "07039";
        vat.domain = "VAT_RATES";
        vat.outcome = "APPLIED";
        vat.integratedAt = LocalDateTime.of(2026, 10, 24, 8, 0);
        NodeIntegration products = new NodeIntegration();
        products.pdvNumber = "07039";
        products.kind = "REGISTER";
        products.nodeId = "C04";
        products.domain = "PRODUCTS";
        products.outcome = "FAILED";
        List<IntegrationDeposit> deposits = List.of(new IntegrationDeposit());
        SupervisionConsoleService service = service();
        when(service.integrationRetryService.latest(30)).thenReturn(deposits);
        try (MockedStatic<NodeIntegration> integrations = mockStatic(NodeIntegration.class);
             MockedStatic<NodeEchelon> echelons = mockStatic(NodeEchelon.class)) {
            organisation(echelons);
            integrations.when(NodeIntegration::listOrdered).thenReturn(List.of(vat, products));
            SupervisionConsoleService.ConsoleView view = service.integration(filter());
            assertEquals(2, view.rows.size());
            assertEquals(1, view.okCount);
            assertEquals(1, view.koCount);
            assertEquals(List.of("PRODUCTS", "VAT_RATES"), view.choices);
            assertEquals(deposits, view.deposits);
            List<List<Object>> lines = SupervisionConsoleService.integrationLines(view);
            assertEquals("24/10/2026 08:00", lines.get(0).get(7));
            assertEquals("KO", lines.get(1).get(6));
            assertEquals("", lines.get(1).get(8));
            assertEquals(1, service.integration(filter("domain", "VAT_RATES")).rows.size());
            assertEquals(1, service.integration(filter("kind", "REGISTER")).rows.size());
            assertEquals(1, service.integration(filter("status", "OK")).rows.size());
            assertEquals(0, service.integration(filter("country", "PT")).rows.size());
            assertEquals(9, SupervisionConsoleService.integrationColumns().size());
        }
    }

    /** BO-08-04-20: the inventory is listed, its peripherals charted by role, healthy or failed. */
    @Test
    void inventoryListsTheParc() {
        NodeInventoryItem printer = item("PERIPHERAL", "Imprimante", "FAILED");
        NodeInventoryItem scale = item("PERIPHERAL", "Balance", "AVAILABLE");
        NodeInventoryItem java = item("SOFTWARE", "Java", null);
        try (MockedStatic<NodeInventoryItem> items = mockStatic(NodeInventoryItem.class);
             MockedStatic<NodeEchelon> echelons = mockStatic(NodeEchelon.class)) {
            organisation(echelons);
            items.when(NodeInventoryItem::listOrdered).thenReturn(List.of(printer, scale, java));
            SupervisionConsoleService service = service();
            SupervisionConsoleService.ConsoleView view = service.inventory(filter());
            assertEquals(3, view.rows.size());
            assertEquals(1, view.okCount);
            assertEquals(1, view.koCount);
            assertEquals(List.of("PERIPHERAL", "SOFTWARE"), view.choices);
            assertTrue(view.chart.contains("Imprimante"));
            assertEquals("", SupervisionConsoleService.inventoryLines(view).get(2).get(4));
            assertEquals(1, service.inventory(filter("category", "SOFTWARE")).rows.size());
            assertEquals(1, service.inventory(filter("status", "KO")).rows.size());
            assertEquals(0, service.inventory(filter("enseigne", "BRI")).rows.size());
            assertEquals(5, SupervisionConsoleService.inventoryColumns().size());
        }
    }

    /** The screen's inventory chart names each peripheral role through the page's labels. */
    @Test
    void inventoryChartLabelsTheRoles() {
        NodeInventoryItem printer = item("PERIPHERAL", "Imprimante", "FAILED");
        try (MockedStatic<NodeInventoryItem> items = mockStatic(NodeInventoryItem.class);
             MockedStatic<NodeEchelon> echelons = mockStatic(NodeEchelon.class)) {
            organisation(echelons);
            items.when(NodeInventoryItem::listOrdered).thenReturn(List.of(printer));
            SupervisionConsoleService.ConsoleView view = service().inventory(filter(), name -> "Printer");
            assertTrue(view.chart.contains("Printer"));
            assertFalse(view.chart.contains("Imprimante"));
            assertEquals("Imprimante", ((SupervisionConsoleService.InventoryRow) view.rows.get(0)).name);
        }
    }

    /**
     * Builds an inventory line.
     *
     * @param category its category
     * @param name its name
     * @param detail its value
     * @return the line
     */
    private NodeInventoryItem item(String category, String name, String detail) {
        NodeInventoryItem item = new NodeInventoryItem();
        item.pdvNumber = "07039";
        item.nodeId = "C04";
        item.category = category;
        item.name = name;
        item.detail = detail;
        return item;
    }

    /**
     * Builds a usage counter.
     *
     * @param pdv the point of sale
     * @param node the node
     * @param channel the channel
     * @param function the function
     * @param count the uses
     * @return the counter
     */
    private FunctionUsage usage(String pdv, String node, String channel, String function, long count) {
        FunctionUsage usage = new FunctionUsage();
        usage.usageDay = LocalDate.now();
        usage.pdvNumber = pdv;
        usage.nodeId = node;
        usage.channel = channel;
        usage.functionCode = function;
        usage.useCount = count;
        return usage;
    }

    /** BO-08-02-02: the uses are summed per function across nodes, the most used first, filtered by channel. */
    @Test
    void usageSumsTheFunctions() {
        List<FunctionUsage> usages = List.of(usage("07039", "C04", "CAISSE", "/action/pay", 3),
                usage("07039", "C05", "CAISSE", "/action/pay", 4),
                usage("07039", "07039", "BACKOFFICE", "sync:relaunchPull", 9),
                usage(null, "CENTRAL", "BACKOFFICE", "deployment:console", 1));
        try (MockedStatic<FunctionUsage> counters = mockStatic(FunctionUsage.class);
             MockedStatic<NodeEchelon> echelons = mockStatic(NodeEchelon.class)) {
            organisation(echelons);
            counters.when(() -> FunctionUsage.since(any(LocalDate.class))).thenReturn(usages);
            SupervisionConsoleService service = service();
            SupervisionConsoleService.ConsoleView view = service.usage(filter());
            assertEquals(3, view.rows.size());
            assertEquals(17, view.okCount);
            SupervisionConsoleService.UsageRow top = (SupervisionConsoleService.UsageRow) view.rows.get(0);
            assertEquals("sync:relaunchPull", top.function);
            SupervisionConsoleService.UsageRow pay = (SupervisionConsoleService.UsageRow) view.rows.get(1);
            assertEquals(7, pay.count);
            assertEquals(2, pay.nodes);
            assertEquals(List.of("CAISSE", "/action/pay", 7L, 2L), SupervisionConsoleService.usageLines(view).get(1));
            assertEquals(1, service.usage(filter("channel", "CAISSE")).rows.size());
            assertEquals(2, service.usage(filter("pdv", "07039")).rows.size());
            assertEquals(4, SupervisionConsoleService.usageColumns().size());
        }
    }

    /** A moment and a text helper keep null. */
    @Test
    void helpersKeepNull() {
        assertNull(SupervisionConsoleService.moment(null));
        assertEquals("", SupervisionConsoleService.text(null));
        assertEquals("a", SupervisionConsoleService.text("a"));
        assertFalse(SupervisionConsoleService.keeps(filter("anomaly", "OFFLINE"), healthyRow()));
    }

    /**
     * Builds a healthy activity line.
     *
     * @return the line
     */
    private SupervisionConsoleService.NodeRow healthyRow() {
        SupervisionConsoleService.NodeRow row = new SupervisionConsoleService.NodeRow();
        row.online = true;
        row.appOk = true;
        row.peripheralOk = true;
        row.syncOk = true;
        row.ok = true;
        return row;
    }

    /**
     * The stored peripherals are split one by one, a role without state keeps
     * an empty state, and a null or blank string gives no peripheral.
     */
    @Test
    void peripheralsAreSplitOneByOne() {
        List<SupervisionConsoleService.PeripheralState> states =
                SupervisionConsoleService.peripheralStates("Imprimante=AVAILABLE;Balance");
        assertEquals(2, states.size());
        assertEquals("Imprimante", states.get(0).role);
        assertEquals("AVAILABLE", states.get(0).state);
        assertEquals("Balance", states.get(1).role);
        assertEquals("", states.get(1).state);
        assertTrue(SupervisionConsoleService.peripheralStates(null).isEmpty());
        assertTrue(SupervisionConsoleService.peripheralStates(" ").isEmpty());
    }
}
