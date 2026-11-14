package com.intermarche.edge.ui.supervision;

import com.intermarche.edge.domain.deploy.FunctionUsage;
import com.intermarche.edge.domain.deploy.NodeIntegration;
import com.intermarche.edge.domain.deploy.NodeInventoryItem;
import com.intermarche.edge.domain.deploy.NodeStatus;
import com.intermarche.edge.domain.store.NodeEchelon;
import com.intermarche.edge.domain.sync.IntegrationDeposit;
import com.intermarche.edge.service.deploy.NodeStatusService;
import com.intermarche.edge.service.sync.IntegrationRetryService;
import com.intermarche.edge.ui.report.ReportColumn;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * Assembles the SUPERVISION CONSOLES of the back office: activity and
 * register health (BO-08-04-15, BO-08-04-16), integration (BO-08-01-17,
 * BO-08-01-19, BO-08-04-03 to -13), hardware and software inventory
 * (BO-08-04-20) and function usage (BO-08-02-02).
 *
 * <p>Every console reads the NODE REPORTS this node received — on the
 * central node, the whole organisation; on a store node, its own line — and
 * places each line in the organisation through its point of sale, its
 * enseigne and its country. A console is a list, a chart of the OK and KO
 * counts per group, and the same list as an export.
 */
@ApplicationScoped
public class SupervisionConsoleService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SupervisionConsoleService.class);

    /** How moments are shown. */
    static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** How many deposits the integration console lists. */
    static final int DEPOSITS_SHOWN = 30;

    /** The node reports and their offline tolerance. */
    @Inject
    NodeStatusService nodeStatusService;

    /** The deposited integrations. */
    @Inject
    IntegrationRetryService integrationRetryService;

    /** What a console shows. */
    public static class ConsoleView {
        /** The filter in force. */
        public ConsoleFilter filter;
        /** The lines kept. */
        public List<Object> rows = new ArrayList<>();
        /** The chart, empty when there is nothing to draw. */
        public String chart = "";
        /** How many lines are OK. */
        public long okCount;
        /** How many lines are KO. */
        public long koCount;
        /** The values the domain or category filter offers. */
        public List<String> choices = new ArrayList<>();
        /** The deposits kept on the node, for the integration console. */
        public List<IntegrationDeposit> deposits = new ArrayList<>();
    }

    /** One node of the activity console. */
    public static class NodeRow {
        /** The node's key. */
        public String nodeKey;
        /** Its point of sale. */
        public String pdv;
        /** Its enseigne. */
        public String enseigne;
        /** Its country. */
        public String country;
        /** REGISTER, STORE or CENTRAL. */
        public String kind;
        /** Its identifier. */
        public String nodeId;
        /** The version it runs. */
        public String version;
        /** Where its update stands. */
        public String updateState;
        /** Whether it reported recently (the server ping). */
        public boolean online;
        /** Whether its application is healthy. */
        public boolean appOk;
        /** Whether its peripherals answer. */
        public boolean peripheralOk;
        /** Whether its synchronization is healthy. */
        public boolean syncOk;
        /** Whether the node is free of every anomaly. */
        public boolean ok;
        /** Its anomalies, French, comma separated (the export's cell). */
        public String anomalies;
        /** Its anomalies, French, one label each (the screen's badges). */
        public List<String> anomalyLabels = new ArrayList<>();
        /** Its peripherals and their state. */
        public String peripherals;
        /** Its peripherals one by one, for the screen to label each role and state. */
        public List<PeripheralState> peripheralStates = new ArrayList<>();
        /** What is wrong, French. */
        public String detail;
        /** How many documents wait to go up. */
        public long outboxPending;
        /** When it last reported. */
        public String reportedAt;
        /** When it last closed a ticket (the ticket heartbeat). */
        public String lastTicketAt;
    }

    /** One peripheral of a node and its availability, as the activity console shows it. */
    public static class PeripheralState {
        /** The peripheral's role, as the node names it. */
        public String role;
        /** Its availability code (AVAILABLE, ABSENT, FAILED), empty when the node gave none. */
        public String state;
    }

    /** One integration of one node. */
    public static class IntegrationRow {
        /** Its point of sale. */
        public String pdv;
        /** Its enseigne. */
        public String enseigne;
        /** Its country. */
        public String country;
        /** The node's kind. */
        public String kind;
        /** The node's identifier. */
        public String nodeId;
        /** The domain. */
        public String domain;
        /** Whether it went in. */
        public boolean ok;
        /** When. */
        public String at;
        /** What to remember. */
        public String detail;
    }

    /** One inventory line of one node. */
    public static class InventoryRow {
        /** Its point of sale. */
        public String pdv;
        /** The node's identifier. */
        public String nodeId;
        /** HARDWARE, PERIPHERAL or SOFTWARE. */
        public String category;
        /** What it describes. */
        public String name;
        /** Its value. */
        public String detail;
        /** Whether the line is healthy (a failed peripheral is not). */
        public boolean ok;
    }

    /** One function and how often it was used. */
    public static class UsageRow {
        /** CAISSE or BACKOFFICE. */
        public String channel;
        /** The function. */
        public String function;
        /** How many uses. */
        public long count;
        /** On how many nodes. */
        public int nodes;
    }

    /**
     * The activity console, which is also the register-health console when
     * filtered on the registers in anomaly (BO-08-04-15, BO-08-04-16).
     *
     * @param filter the filter
     * @return the view
     */
    @Transactional
    public ConsoleView activity(ConsoleFilter filter) {
        LOGGER.info("Entering method activity");
        ConsoleView view = new ConsoleView();
        view.filter = filter;
        Map<String, NodeEchelon> echelons = new HashMap<>();
        Map<String, long[]> groups = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();
        long tolerance = nodeStatusService.toleranceSeconds();
        for (NodeStatus status : NodeStatus.listOrdered()) {
            NodeEchelon echelon = echelon(echelons, status.pdvNumber);
            NodeRow row = nodeRow(status, echelon, now, tolerance);
            if (keeps(filter, row)) {
                view.rows.add(row);
                count(view, groups, filter.groupOf(row.country, row.enseigne, row.pdv), row.ok);
            }
        }
        view.chart = StatusChart.svg(bars(groups));
        return view;
    }

    /**
     * Whether an activity line passes the filter.
     *
     * @param filter the filter
     * @param row the line
     * @return true when kept
     */
    static boolean keeps(ConsoleFilter filter, NodeRow row) {
        boolean anomaly = filter.anomaly == null
                || "OFFLINE".equals(filter.anomaly) && !row.online
                || "APP".equals(filter.anomaly) && !row.appOk
                || "PERIPHERAL".equals(filter.anomaly) && !row.peripheralOk
                || "SYNC".equals(filter.anomaly) && !row.syncOk;
        return anomaly && ConsoleFilter.keeps(filter.kind, row.kind) && filter.keepsStatus(row.ok)
                && filter.keepsEchelon(row.country, row.enseigne, row.pdv);
    }

    /**
     * Builds the activity line of a node.
     *
     * @param status the node's status
     * @param echelon its place in the organisation
     * @param now the present moment
     * @param tolerance how long a node may stay silent
     * @return the line
     */
    static NodeRow nodeRow(NodeStatus status, NodeEchelon echelon, LocalDateTime now, long tolerance) {
        NodeRow row = new NodeRow();
        row.nodeKey = status.nodeKey;
        row.pdv = status.pdvNumber;
        row.enseigne = echelon.enseigne;
        row.country = echelon.country;
        row.kind = status.kind;
        row.nodeId = status.nodeId;
        row.version = status.version;
        row.updateState = status.updateState;
        row.online = status.online(now, tolerance);
        row.appOk = status.appOk;
        row.peripheralOk = status.peripheralOk;
        row.syncOk = status.syncOk;
        row.ok = row.online && row.appOk && row.peripheralOk && row.syncOk;
        List<String> anomalies = new ArrayList<>();
        if (!row.online) {
            anomalies.add("Hors ligne");
        }
        if (!row.appOk) {
            anomalies.add("Application");
        }
        if (!row.peripheralOk) {
            anomalies.add("Périphérique");
        }
        if (!row.syncOk) {
            anomalies.add("Synchronisation");
        }
        row.anomalyLabels = anomalies;
        row.anomalies = String.join(", ", anomalies);
        row.peripherals = status.peripherals;
        row.peripheralStates = peripheralStates(status.peripherals);
        row.detail = status.syncDetail != null ? status.syncDetail : status.appDetail;
        row.outboxPending = status.outboxPending;
        row.reportedAt = moment(status.reportedAt);
        row.lastTicketAt = moment(status.lastTicketAt);
        return row;
    }

    /**
     * Splits the stored {@code role=STATE;role=STATE} peripherals of a node
     * into one entry per peripheral, so that the screen labels each role and
     * each state in the page's language instead of showing the raw string.
     *
     * @param joined the stored peripherals, possibly null or blank
     * @return the peripherals in report order, empty when none
     */
    static List<PeripheralState> peripheralStates(String joined) {
        List<PeripheralState> states = new ArrayList<>();
        if (joined == null || joined.isBlank()) {
            return states;
        }
        for (String part : joined.split(";")) {
            int equals = part.indexOf('=');
            PeripheralState state = new PeripheralState();
            state.role = equals < 0 ? part : part.substring(0, equals);
            state.state = equals < 0 ? "" : part.substring(equals + 1);
            states.add(state);
        }
        return states;
    }

    /**
     * The integration console (BO-08-01-17, BO-08-01-19): every reported
     * integration, and the deposits kept on this node with their replay.
     *
     * @param filter the filter
     * @return the view
     */
    @Transactional
    public ConsoleView integration(ConsoleFilter filter) {
        LOGGER.info("Entering method integration");
        ConsoleView view = new ConsoleView();
        view.filter = filter;
        Map<String, NodeEchelon> echelons = new HashMap<>();
        Map<String, long[]> groups = new LinkedHashMap<>();
        TreeSet<String> domains = new TreeSet<>();
        for (NodeIntegration integration : NodeIntegration.listOrdered()) {
            domains.add(integration.domain);
            NodeEchelon echelon = echelon(echelons, integration.pdvNumber);
            IntegrationRow row = new IntegrationRow();
            row.pdv = integration.pdvNumber;
            row.enseigne = echelon.enseigne;
            row.country = echelon.country;
            row.kind = integration.kind;
            row.nodeId = integration.nodeId;
            row.domain = integration.domain;
            row.ok = "APPLIED".equals(integration.outcome);
            row.at = moment(integration.integratedAt);
            row.detail = integration.detail;
            if (ConsoleFilter.keeps(filter.domain, row.domain) && ConsoleFilter.keeps(filter.kind, row.kind)
                    && filter.keepsStatus(row.ok) && filter.keepsEchelon(row.country, row.enseigne, row.pdv)) {
                view.rows.add(row);
                count(view, groups, filter.groupOf(row.country, row.enseigne, row.pdv), row.ok);
            }
        }
        view.choices.addAll(domains);
        view.chart = StatusChart.svg(bars(groups));
        view.deposits = integrationRetryService.latest(DEPOSITS_SHOWN);
        return view;
    }

    /**
     * The hardware and software inventory (BO-08-04-20).
     *
     * @param filter the filter
     * @return the view, its chart counting healthy and failed peripherals by role
     */
    @Transactional
    public ConsoleView inventory(ConsoleFilter filter) {
        return inventory(filter, UnaryOperator.identity());
    }

    /**
     * The inventory console, its chart labelling each peripheral role through
     * the given function (the screen passes the page's translation; the
     * export keeps the reported names).
     *
     * @param filter the filter
     * @param label the label of a peripheral role on the chart
     * @return the view
     */
    @Transactional
    public ConsoleView inventory(ConsoleFilter filter, UnaryOperator<String> label) {
        LOGGER.info("Entering method inventory");
        ConsoleView view = new ConsoleView();
        view.filter = filter;
        Map<String, NodeEchelon> echelons = new HashMap<>();
        Map<String, long[]> groups = new LinkedHashMap<>();
        TreeSet<String> categories = new TreeSet<>();
        for (NodeInventoryItem item : NodeInventoryItem.listOrdered()) {
            categories.add(item.category);
            NodeEchelon echelon = echelon(echelons, item.pdvNumber);
            InventoryRow row = new InventoryRow();
            row.pdv = item.pdvNumber;
            row.nodeId = item.nodeId;
            row.category = item.category;
            row.name = item.name;
            row.detail = item.detail;
            row.ok = !"FAILED".equals(item.detail);
            if (ConsoleFilter.keeps(filter.category, row.category) && filter.keepsStatus(row.ok)
                    && filter.keepsEchelon(echelon.country, echelon.enseigne, row.pdv)) {
                view.rows.add(row);
                if ("PERIPHERAL".equals(row.category)) {
                    count(view, groups, label.apply(row.name), row.ok);
                }
            }
        }
        view.choices.addAll(categories);
        view.chart = StatusChart.svg(bars(groups));
        return view;
    }

    /**
     * The function-usage statistics (BO-08-02-02): the uses of each function
     * over the last days, on the points of sale and enseignes kept.
     *
     * @param filter the filter
     * @return the view, the most used functions first
     */
    @Transactional
    public ConsoleView usage(ConsoleFilter filter) {
        LOGGER.info("Entering method usage");
        ConsoleView view = new ConsoleView();
        view.filter = filter;
        Map<String, NodeEchelon> echelons = new HashMap<>();
        Map<String, UsageRow> functions = new LinkedHashMap<>();
        Map<String, TreeSet<String>> nodes = new HashMap<>();
        for (FunctionUsage usage : FunctionUsage.since(LocalDate.now().minusDays(filter.days))) {
            NodeEchelon echelon = echelon(echelons, usage.pdvNumber);
            if (ConsoleFilter.keeps(filter.channel, usage.channel)
                    && filter.keepsEchelon(echelon.country, echelon.enseigne, usage.pdvNumber)) {
                String key = usage.channel + " " + usage.functionCode;
                UsageRow row = functions.computeIfAbsent(key, k -> new UsageRow());
                row.channel = usage.channel;
                row.function = usage.functionCode;
                row.count += usage.useCount;
                TreeSet<String> seen = nodes.computeIfAbsent(key, k -> new TreeSet<>());
                seen.add(usage.pdvNumber + "/" + usage.nodeId);
                row.nodes = seen.size();
            }
        }
        List<UsageRow> rows = new ArrayList<>(functions.values());
        rows.sort((a, b) -> Long.compare(b.count, a.count));
        List<StatusChart.Bar> bars = new ArrayList<>();
        for (UsageRow row : rows) {
            view.rows.add(row);
            view.okCount += row.count;
            bars.add(new StatusChart.Bar(row.function, row.count, 0));
        }
        view.chart = StatusChart.svg(bars);
        return view;
    }

    /**
     * The columns of the activity export.
     *
     * @return the columns
     */
    public static List<ReportColumn> activityColumns() {
        return List.of(ReportColumn.text("pdv", "PDV"), ReportColumn.text("enseigne", "Enseigne"),
                ReportColumn.text("country", "Pays"), ReportColumn.text("kind", "Type"),
                ReportColumn.text("node", "Nœud"), ReportColumn.text("version", "Version"),
                ReportColumn.text("status", "Statut"), ReportColumn.text("anomalies", "Anomalies"),
                ReportColumn.text("peripherals", "Périphériques"), ReportColumn.text("reported", "Dernier rapport"),
                ReportColumn.text("ticket", "Dernier ticket"));
    }

    /**
     * The rows of the activity export.
     *
     * @param view the activity view
     * @return the rows
     */
    public static List<List<Object>> activityLines(ConsoleView view) {
        List<List<Object>> lines = new ArrayList<>();
        for (Object item : view.rows) {
            NodeRow row = (NodeRow) item;
            lines.add(List.of(text(row.pdv), text(row.enseigne), text(row.country), row.kind, row.nodeId,
                    text(row.version), row.ok ? "OK" : "KO", row.anomalies, text(row.peripherals),
                    text(row.reportedAt), text(row.lastTicketAt)));
        }
        return lines;
    }

    /**
     * The columns of the integration export.
     *
     * @return the columns
     */
    public static List<ReportColumn> integrationColumns() {
        return List.of(ReportColumn.text("pdv", "PDV"), ReportColumn.text("enseigne", "Enseigne"),
                ReportColumn.text("country", "Pays"), ReportColumn.text("kind", "Type"),
                ReportColumn.text("node", "Nœud"), ReportColumn.text("domain", "Domaine"),
                ReportColumn.text("status", "Statut"), ReportColumn.text("at", "Intégré le"),
                ReportColumn.text("detail", "Détail"));
    }

    /**
     * The rows of the integration export.
     *
     * @param view the integration view
     * @return the rows
     */
    public static List<List<Object>> integrationLines(ConsoleView view) {
        List<List<Object>> lines = new ArrayList<>();
        for (Object item : view.rows) {
            IntegrationRow row = (IntegrationRow) item;
            lines.add(List.of(text(row.pdv), text(row.enseigne), text(row.country), row.kind, row.nodeId,
                    row.domain, row.ok ? "OK" : "KO", text(row.at), text(row.detail)));
        }
        return lines;
    }

    /**
     * The columns of the inventory export.
     *
     * @return the columns
     */
    public static List<ReportColumn> inventoryColumns() {
        return List.of(ReportColumn.text("pdv", "PDV"), ReportColumn.text("node", "Nœud"),
                ReportColumn.text("category", "Catégorie"), ReportColumn.text("name", "Élément"),
                ReportColumn.text("detail", "Valeur"));
    }

    /**
     * The rows of the inventory export.
     *
     * @param view the inventory view
     * @return the rows
     */
    public static List<List<Object>> inventoryLines(ConsoleView view) {
        List<List<Object>> lines = new ArrayList<>();
        for (Object item : view.rows) {
            InventoryRow row = (InventoryRow) item;
            lines.add(List.of(text(row.pdv), row.nodeId, row.category, row.name, text(row.detail)));
        }
        return lines;
    }

    /**
     * The columns of the usage export.
     *
     * @return the columns
     */
    public static List<ReportColumn> usageColumns() {
        return List.of(ReportColumn.text("channel", "Canal"), ReportColumn.text("function", "Fonction"),
                ReportColumn.count("count", "Utilisations"), ReportColumn.count("nodes", "Nœuds"));
    }

    /**
     * The rows of the usage export.
     *
     * @param view the usage view
     * @return the rows
     */
    public static List<List<Object>> usageLines(ConsoleView view) {
        List<List<Object>> lines = new ArrayList<>();
        for (Object item : view.rows) {
            UsageRow row = (UsageRow) item;
            lines.add(List.of(row.channel, row.function, row.count, (long) row.nodes));
        }
        return lines;
    }

    /**
     * Places a point of sale in the organisation, once per console.
     *
     * @param cache the places already resolved
     * @param pdv the point of sale, possibly null
     * @return its place
     */
    static NodeEchelon echelon(Map<String, NodeEchelon> cache, String pdv) {
        return cache.computeIfAbsent(pdv == null ? "" : pdv, NodeEchelon::of);
    }

    /**
     * Counts one line in the totals and in its group.
     *
     * @param view the view
     * @param groups the groups' counts
     * @param group the line's group
     * @param ok whether the line is OK
     */
    static void count(ConsoleView view, Map<String, long[]> groups, String group, boolean ok) {
        long[] counts = groups.computeIfAbsent(group, g -> new long[2]);
        if (ok) {
            counts[0]++;
            view.okCount++;
        } else {
            counts[1]++;
            view.koCount++;
        }
    }

    /**
     * Turns the groups' counts into chart bars.
     *
     * @param groups the groups' counts
     * @return the bars, in group order
     */
    static List<StatusChart.Bar> bars(Map<String, long[]> groups) {
        List<StatusChart.Bar> bars = new ArrayList<>();
        for (Map.Entry<String, long[]> group : groups.entrySet()) {
            bars.add(new StatusChart.Bar(group.getKey(), group.getValue()[0], group.getValue()[1]));
        }
        return bars;
    }

    /**
     * Shows a moment.
     *
     * @param moment the moment, possibly null
     * @return the formatted moment, or null
     */
    static String moment(LocalDateTime moment) {
        return moment == null ? null : MOMENT.format(moment);
    }

    /**
     * An export cell for an optional text.
     *
     * @param value the value, possibly null
     * @return the value, empty when null
     */
    static String text(String value) {
        return value == null ? "" : value;
    }
}
