package com.intermarche.edge.service.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.sync.IntegrationDeposit;
import com.intermarche.edge.domain.sync.RefState;
import com.intermarche.edge.service.NodeEchelonService;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.sync.IntegrationRetryService;
import com.intermarche.edge.service.sync.RefPullService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The REPORT CYCLE of a store or central node about ITSELF
 * (BO-08-03-05/07, BO-08-04-01/14/15/17/20, BO-08-01-17).
 *
 * <p>Every administered period the node writes its own status where its
 * consoles read it — version, update state, integrations, inventory — and
 * then:
 * <ul>
 *   <li>a STORE node connected to a central node sends it its report, its
 *       registers' last reports, the function counters and the outcomes of
 *       the central orders; it keeps the central node's answer — the versions
 *       its registers must run, the orders for them — to relay it with their
 *       next answers;</li>
 *   <li>a CENTRAL node, or a store node without one, is its own authority and
 *       asks its own deployment lots.</li>
 * </ul>
 * Either way the node then carries out the orders addressed to itself and
 * considers the version it must run — the central brick updating itself in
 * the window of its own lot is exactly BO-08-03-05. The first cycle after a
 * start-up is the start-up update of BO-08-03-07.
 */
@ApplicationScoped
public class UpstreamReportService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(UpstreamReportService.class);

    /** The identifier the central node reports under. */
    static final String CENTRAL_ID = "CENTRAL";

    /** The identifier of a store node whose point of sale is not commissioned. */
    static final String UNNAMED_STORE = "EDGE";

    /** How many deposits are read to find the latest of each feed. */
    static final int DEPOSITS_READ = 200;

    /** The role of this node: register, store or central. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** The central node a store reports to; absent = none. */
    @ConfigProperty(name = "pos.sync.central-url")
    Optional<String> centralUrl;

    /** Shared token sent to the central node; absent = none. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** This node's point of sale. */
    /** Sums the store's revenue of its last days (BO-02-05-05). */
    @Inject
    PdvRevenueService pdvRevenueService;

    @Inject
    NodeEchelonService nodeEchelonService;

    /** The report cadence. */
    @Inject
    PosSettingsService posSettingsService;

    /** The update state machine. */
    @Inject
    NodeUpdateService nodeUpdateService;

    /** The node statuses. */
    @Inject
    NodeStatusService nodeStatusService;

    /** The deployment authority. */
    @Inject
    DeploymentPlanner deploymentPlanner;

    /** The maintenance order book. */
    @Inject
    MaintenanceService maintenanceService;

    /** The orders carried out on this node. */
    @Inject
    EdgeMaintenanceService edgeMaintenanceService;

    /** The function counters. */
    @Inject
    FunctionUsageService functionUsageService;

    /** The deposited integrations. */
    @Inject
    IntegrationRetryService integrationRetryService;

    /** The pulled referentials' errors. */
    @Inject
    RefPullService refPullService;

    /** The JSON mapper of the wire format. */
    @Inject
    ObjectMapper objectMapper;

    /** The HTTP client toward the central node. */
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Whether a cycle already ran since the node started. */
    boolean started;

    /** The cycle executor, null on a register. */
    private ScheduledExecutorService executor;

    /**
     * Starts the report cycle on a store or central node.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        if ("register".equalsIgnoreCase(role)) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "node-report");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::cycleSafely, 15, posSettingsService.supervisionReportSeconds(),
                TimeUnit.SECONDS);
    }

    /**
     * Stops the report cycle.
     */
    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * Runs one cycle, swallowing whatever it fails on: an unreachable central
     * node must not stop the next cycle.
     */
    void cycleSafely() {
        try {
            cycle();
        } catch (Exception failure) {
            LOGGER.warnf("Rapport de nœud impossible (%s)", failure.getMessage());
        }
    }

    /**
     * Runs one report cycle.
     *
     * @throws Exception when the central node cannot be reached or answers badly
     */
    void cycle() throws Exception {
        boolean startup = !started;
        started = true;
        DeployPayloads.NodeReport self = selfReport(startup);
        String selfKey = DeployPayloads.nodeKey(self.kind, self.pdvNumber, self.nodeId);
        DeployPayloads.Target own = null;
        if (relayed()) {
            self.registers = nodeStatusService.registerReports();
            self.usages = functionUsageService.recent(1);
            self.actionResults = maintenanceService.unforwarded();
            DeployPayloads.ReportAnswer answer = post(self);
            for (DeployPayloads.Target target : answer.targets) {
                if (selfKey.equals(DeployPayloads.nodeKey(target.kind, target.pdvNumber, target.nodeId))) {
                    own = target;
                } else {
                    nodeStatusService.keepRelayedTarget(target);
                }
            }
            for (DeployPayloads.Action order : answer.actions) {
                maintenanceService.relay(order);
            }
        } else {
            own = deploymentPlanner.targetFor(self.kind, self.pdvNumber, self.nodeId);
            // BO-02-05-05: a store that is its own authority files its own revenue.
            if (DeployPayloads.STORE.equals(self.kind) && pdvRevenueService != null) {
                pdvRevenueService.record(self.pdvNumber, self.revenues);
            }
        }
        List<DeployPayloads.ActionResult> results = new ArrayList<>();
        List<DeployPayloads.Action> checks = new ArrayList<>();
        for (DeployPayloads.Action order : maintenanceService.handOut(selfKey)) {
            if ("UPDATE_CHECK".equals(order.type)) {
                checks.add(order);
            } else {
                results.add(edgeMaintenanceService.execute(order));
            }
        }
        NodeUpdateService.Decision decision = nodeUpdateService.consider(own, startup || !checks.isEmpty(),
                true, this::download);
        for (DeployPayloads.Action check : checks) {
            results.add(MaintenanceService.result(check.uid, true, "Contrôle de mise à jour : " + decision,
                    LocalDateTime.now()));
        }
        maintenanceService.recordResults(results);
        nodeStatusService.write(selfReport(startup));
    }

    /**
     * Whether this node reports to a central node.
     *
     * @return true for a store node with a central URL
     */
    boolean relayed() {
        return "store".equalsIgnoreCase(role) && centralUrl.isPresent() && !centralUrl.get().isBlank();
    }

    /**
     * Builds this node's report about itself.
     *
     * @param startup whether this is the first cycle since the node started
     * @return the report
     */
    DeployPayloads.NodeReport selfReport(boolean startup) {
        DeployPayloads.NodeReport report = new DeployPayloads.NodeReport();
        boolean central = "central".equalsIgnoreCase(role);
        String pdv = central ? null : QuarkusTransaction.requiringNew().call(nodeEchelonService::currentPdv);
        report.kind = central ? DeployPayloads.CENTRAL : DeployPayloads.STORE;
        report.pdvNumber = pdv;
        report.nodeId = central ? CENTRAL_ID : pdv == null ? UNNAMED_STORE : pdv;
        com.intermarche.edge.domain.deploy.NodeVersion version = nodeUpdateService.snapshot();
        report.version = version.installedVersion;
        report.previousVersion = version.previousVersion;
        report.updateState = version.state.name();
        report.updateTarget = version.targetVersion;
        report.updateMessage = version.lastError;
        report.startup = startup;
        report.reportedAt = LocalDateTime.now().toString();
        report.integrations = integrations();
        report.inventory = inventory(report.version);
        // BO-02-05-05: a store states the revenue of its last days, which the
        // central node regroups by point of sale and by adhérent.
        if (!central && pdvRevenueService != null) {
            report.revenues = QuarkusTransaction.requiringNew()
                    .call(() -> pdvRevenueService.dayRevenues(java.time.LocalDate.now()));
        }
        return report;
    }

    /**
     * The integration state of this node: the latest deposit of each feed,
     * then the referential domains it pulled, a pull error marking its domain
     * failed.
     *
     * @return the integrations
     */
    List<DeployPayloads.Integration> integrations() {
        List<DeployPayloads.Integration> integrations = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (IntegrationDeposit deposit : integrationRetryService.latest(DEPOSITS_READ)) {
            if (seen.add(deposit.code)) {
                integrations.add(integration(deposit.code,
                        deposit.status == IntegrationDeposit.Status.APPLIED, deposit.lastAttemptAt,
                        deposit.status + " " + deposit.lastResult));
            }
        }
        Map<String, String> errors = refPullService.getLastErrors();
        List<RefState> states = QuarkusTransaction.requiringNew().call(() -> RefState.<RefState>listAll());
        for (RefState state : states) {
            if (seen.add(state.domain)) {
                String error = errors.get(state.domain);
                integrations.add(integration(state.domain, error == null, state.appliedAt, error));
            }
        }
        for (Map.Entry<String, String> error : errors.entrySet()) {
            if (seen.add(error.getKey())) {
                integrations.add(integration(error.getKey(), false, null, error.getValue()));
            }
        }
        return integrations;
    }

    /**
     * Builds one integration line.
     *
     * @param domain the domain or feed code
     * @param applied whether it went in
     * @param at when, possibly null
     * @param detail what to remember
     * @return the line
     */
    static DeployPayloads.Integration integration(String domain, boolean applied, LocalDateTime at,
                                                  String detail) {
        DeployPayloads.Integration integration = new DeployPayloads.Integration();
        integration.domain = domain;
        integration.outcome = applied ? "APPLIED" : "FAILED";
        integration.at = at == null ? null : at.toString();
        integration.detail = detail;
        return integration;
    }

    /**
     * The software and hardware this node runs on.
     *
     * @param version the application version
     * @return the inventory lines
     */
    static List<DeployPayloads.InventoryItem> inventory(String version) {
        List<DeployPayloads.InventoryItem> items = new ArrayList<>();
        items.add(item("SOFTWARE", "Application imedge", version));
        items.add(item("SOFTWARE", "Java", System.getProperty("java.version")));
        items.add(item("SOFTWARE", "Système", System.getProperty("os.name") + " " + System.getProperty("os.version")));
        items.add(item("HARDWARE", "Processeurs", String.valueOf(Runtime.getRuntime().availableProcessors())));
        items.add(item("HARDWARE", "Mémoire maximale (Mo)", String.valueOf(Runtime.getRuntime().maxMemory() / 1048576)));
        return items;
    }

    /**
     * Builds one inventory line.
     *
     * @param category HARDWARE, PERIPHERAL or SOFTWARE
     * @param name what it describes
     * @param detail its value
     * @return the line
     */
    static DeployPayloads.InventoryItem item(String category, String name, String detail) {
        DeployPayloads.InventoryItem item = new DeployPayloads.InventoryItem();
        item.category = category;
        item.name = name;
        item.detail = detail;
        return item;
    }

    /**
     * Fetches the package of this node's own target.
     *
     * @param target the target
     * @return the package content
     */
    String download(DeployPayloads.Target target) {
        String content = relayed() ? fetchFromCentral(target.product, target.version)
                : deploymentPlanner.packageContent(target.product, target.version);
        if (content == null) {
            throw new IllegalStateException("Paquet " + target.product + " " + target.version + " introuvable");
        }
        return content;
    }

    /**
     * Fetches a package from the central node, for this node or for a
     * register asking it.
     *
     * @param product IMEDGE or IMPOS
     * @param version the version
     * @return the package content, or null when there is no central node or it has no such package
     */
    public String fetchFromCentral(String product, String version) {
        if (!relayed()) {
            return null;
        }
        try {
            HttpResponse<String> response = httpClient.send(request("/api/deployment/package/" + product + "/"
                    + version).GET().build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (Exception failure) {
            LOGGER.warnf("Paquet %s %s non téléchargé (%s)", product, version, failure.getMessage());
            return null;
        }
    }

    /**
     * Sends this node's report to the central node.
     *
     * @param report the report
     * @return the central node's answer
     * @throws Exception when the central node cannot be reached or answers badly
     */
    DeployPayloads.ReportAnswer post(DeployPayloads.NodeReport report) throws Exception {
        HttpResponse<String> response = httpClient.send(request("/api/supervision/report")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(report))).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " du nœud central");
        }
        return objectMapper.readValue(response.body(), DeployPayloads.ReportAnswer.class);
    }

    /**
     * Builds a request to the central node, carrying the shared token.
     *
     * @param path the path
     * @return the prepared builder
     */
    private HttpRequest.Builder request(String path) {
        String base = centralUrl.get().trim();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path))
                .timeout(Duration.ofSeconds(15));
        String shared = token.orElse("");
        if (!shared.isBlank()) {
            builder.header("X-Sync-Token", shared);
        }
        return builder;
    }
}
