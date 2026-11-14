package com.intermarche.edge.service.deploy;

import com.intermarche.edge.domain.deploy.NodeIntegration;
import com.intermarche.edge.domain.deploy.NodeInventoryItem;
import com.intermarche.edge.domain.deploy.NodeStatus;
import com.intermarche.edge.service.NodeEchelonService;
import com.intermarche.edge.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Receives the NODE REPORTS and answers them (BO-08-01-17, BO-08-03-01,
 * BO-08-04-15, BO-08-04-16, BO-08-04-17, BO-08-04-20, BO-08-02-02).
 *
 * <p>A report is written as the node's current state — its status row, its
 * integrations, its inventory — and, for a store node reporting to the
 * central node, the same for each of its registers. Its function counters
 * are folded in, its maintenance outcomes stamped, its deployment entries
 * moved along. The ANSWER names the version each reported node must run and
 * hands it the maintenance orders waiting for it.
 *
 * <p>Which version: the one this node's own lots decide, and on a store
 * node whose lots name nothing, the one the central node decided for that
 * register and relayed with the store's last answer.
 */
@ApplicationScoped
public class NodeStatusService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(NodeStatusService.class);

    /** How many report periods a node may miss before it is called offline. */
    static final int MISSED_REPORTS = 3;

    /** The report cadence, which the offline tolerance derives from. */
    /** Files the revenue the stores state (BO-02-05-05). */
    @Inject
    PdvRevenueService pdvRevenueService;

    @Inject
    PosSettingsService posSettingsService;

    /** This node's point of sale, given to the registers that do not know theirs. */
    @Inject
    NodeEchelonService nodeEchelonService;

    /** The function counters. */
    @Inject
    FunctionUsageService functionUsageService;

    /** The maintenance order book. */
    @Inject
    MaintenanceService maintenanceService;

    /** The deployment authority. */
    @Inject
    DeploymentPlanner deploymentPlanner;

    /**
     * Writes a report and builds its answer.
     *
     * @param report the report, a store's carrying its registers
     * @return the versions to run and the orders waiting, for the node and its registers
     */
    @Transactional
    public DeployPayloads.ReportAnswer receive(DeployPayloads.NodeReport report) {
        LOGGER.info("Entering method receive from " + report.kind + " " + report.nodeId);
        List<DeployPayloads.NodeReport> nodes = new ArrayList<>();
        nodes.add(report);
        nodes.addAll(report.registers);
        DeployPayloads.ReportAnswer answer = new DeployPayloads.ReportAnswer();
        for (DeployPayloads.NodeReport node : nodes) {
            if (DeployPayloads.REGISTER.equals(node.kind) && node.pdvNumber == null) {
                node.pdvNumber = nodeEchelonService.currentPdv();
            }
            NodeStatus status = write(node);
            // BO-02-05-05: the revenue a store states, filed per point of sale.
            if (DeployPayloads.STORE.equals(node.kind) && pdvRevenueService != null) {
                pdvRevenueService.record(node.pdvNumber, node.revenues);
            }
            DeployPayloads.Target target = targetFor(status);
            if (target != null) {
                answer.targets.add(target);
            }
            answer.actions.addAll(maintenanceService.handOut(status.nodeKey));
        }
        functionUsageService.merge(report.usages);
        maintenanceService.recordResults(report.actionResults);
        LOGGER.info("Exiting method receive");
        return answer;
    }

    /**
     * Writes one node's report as its current state.
     *
     * @param report the node's report
     * @return the written status
     */
    @Transactional
    public NodeStatus write(DeployPayloads.NodeReport report) {
        String key = DeployPayloads.nodeKey(report.kind, report.pdvNumber, report.nodeId);
        NodeStatus status = NodeStatus.findByKey(key);
        if (status == null) {
            status = new NodeStatus();
            status.nodeKey = key;
        }
        status.kind = report.kind;
        status.nodeId = report.nodeId;
        status.pdvNumber = report.pdvNumber;
        status.version = report.version;
        status.previousVersion = report.previousVersion;
        status.updateState = report.updateState;
        status.updateTarget = report.updateTarget;
        status.updateMessage = report.updateMessage;
        status.appOk = report.appOk;
        status.appDetail = report.appDetail;
        StringBuilder peripherals = new StringBuilder();
        boolean peripheralOk = true;
        for (DeployPayloads.Peripheral peripheral : report.peripherals) {
            peripherals.append(peripherals.length() == 0 ? "" : ";").append(peripheral.role).append('=')
                    .append(peripheral.state);
            peripheralOk &= !"FAILED".equals(peripheral.state);
        }
        status.peripherals = cut(peripherals.toString(), 500);
        status.peripheralOk = peripheralOk;
        List<String> failed = new ArrayList<>();
        for (DeployPayloads.Integration integration : report.integrations) {
            if ("FAILED".equals(integration.outcome)) {
                failed.add(integration.domain);
            }
        }
        status.syncOk = report.outboxError == null && failed.isEmpty();
        status.syncDetail = status.syncOk ? null
                : cut((report.outboxError == null ? "" : report.outboxError + " ")
                + (failed.isEmpty() ? "" : "Intégrations en erreur : " + String.join(", ", failed)), 300);
        status.outboxPending = report.outboxPending;
        status.lastTicketAt = time(report.lastTicketAt);
        LocalDateTime reported = time(report.reportedAt);
        status.receivedAt = LocalDateTime.now();
        status.reportedAt = reported == null ? status.receivedAt : reported;
        status.persist();
        NodeIntegration.deleteByNode(key);
        for (DeployPayloads.Integration integration : report.integrations) {
            NodeIntegration row = new NodeIntegration();
            row.nodeKey = key;
            row.kind = report.kind;
            row.nodeId = report.nodeId;
            row.pdvNumber = report.pdvNumber;
            row.domain = cut(integration.domain, 30);
            row.outcome = integration.outcome;
            row.integratedAt = time(integration.at);
            row.detail = cut(integration.detail, 300);
            row.persist();
        }
        NodeInventoryItem.deleteByNode(key);
        for (DeployPayloads.InventoryItem item : report.inventory) {
            NodeInventoryItem row = new NodeInventoryItem();
            row.nodeKey = key;
            row.pdvNumber = report.pdvNumber;
            row.nodeId = report.nodeId;
            row.category = item.category;
            row.name = cut(item.name, 60);
            row.detail = cut(item.detail, 200);
            row.persist();
        }
        deploymentPlanner.recordProgress(status);
        return status;
    }

    /**
     * The version a node must run: this node's lots first, then on a store
     * node the central node's relayed decision.
     *
     * @param status the node's status
     * @return the target, or null when nobody asks the node to change
     */
    DeployPayloads.Target targetFor(NodeStatus status) {
        DeployPayloads.Target target = deploymentPlanner.targetFor(status.kind, status.pdvNumber, status.nodeId);
        if (target != null || status.targetVersion == null) {
            return target;
        }
        DeployPayloads.Target relayed = new DeployPayloads.Target();
        relayed.kind = status.kind;
        relayed.nodeId = status.nodeId;
        relayed.pdvNumber = status.pdvNumber;
        relayed.product = status.targetProduct;
        relayed.version = status.targetVersion;
        relayed.checksum = status.targetChecksum;
        relayed.inWindow = status.targetInWindow;
        relayed.forceToken = status.targetForceToken;
        relayed.lotCode = status.targetLot;
        return relayed;
    }

    /**
     * Keeps the version the central node decided for one of this store's
     * nodes, to relay it with that node's next answer.
     *
     * @param target the central node's decision
     */
    @Transactional
    public void keepRelayedTarget(DeployPayloads.Target target) {
        NodeStatus status = NodeStatus.findByKey(DeployPayloads.nodeKey(target.kind, target.pdvNumber,
                target.nodeId));
        if (status == null) {
            return;
        }
        status.targetProduct = target.product;
        status.targetVersion = target.version;
        status.targetChecksum = target.checksum;
        status.targetInWindow = target.inWindow;
        status.targetForceToken = target.forceToken;
        status.targetLot = target.lotCode;
        status.persist();
    }

    /**
     * Rebuilds the last report of every register of this store, for the
     * store's own report to the central node.
     *
     * @return the registers' reports
     */
    @Transactional
    public List<DeployPayloads.NodeReport> registerReports() {
        List<DeployPayloads.NodeReport> reports = new ArrayList<>();
        for (NodeStatus status : NodeStatus.listByKind(DeployPayloads.REGISTER)) {
            reports.add(toReport(status));
        }
        return reports;
    }

    /**
     * Rebuilds the report a status was written from.
     *
     * @param status the node's status
     * @return the report
     */
    DeployPayloads.NodeReport toReport(NodeStatus status) {
        DeployPayloads.NodeReport report = new DeployPayloads.NodeReport();
        report.kind = status.kind;
        report.nodeId = status.nodeId;
        report.pdvNumber = status.pdvNumber;
        report.version = status.version;
        report.previousVersion = status.previousVersion;
        report.updateState = status.updateState;
        report.updateTarget = status.updateTarget;
        report.updateMessage = status.updateMessage;
        report.appOk = status.appOk;
        report.appDetail = status.appDetail;
        report.outboxPending = status.outboxPending;
        report.outboxError = status.syncOk ? null : status.syncDetail;
        report.lastTicketAt = status.lastTicketAt == null ? null : status.lastTicketAt.toString();
        report.reportedAt = status.reportedAt == null ? null : status.reportedAt.toString();
        String peripherals = status.peripherals == null ? "" : status.peripherals;
        for (String pair : peripherals.split(";")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                DeployPayloads.Peripheral peripheral = new DeployPayloads.Peripheral();
                peripheral.role = parts[0];
                peripheral.state = parts[1];
                report.peripherals.add(peripheral);
            }
        }
        for (NodeIntegration row : NodeIntegration.listByNode(status.nodeKey)) {
            DeployPayloads.Integration integration = new DeployPayloads.Integration();
            integration.domain = row.domain;
            integration.outcome = row.outcome;
            integration.at = row.integratedAt == null ? null : row.integratedAt.toString();
            integration.detail = row.detail;
            report.integrations.add(integration);
        }
        for (NodeInventoryItem row : NodeInventoryItem.listByNode(status.nodeKey)) {
            DeployPayloads.InventoryItem item = new DeployPayloads.InventoryItem();
            item.category = row.category;
            item.name = row.name;
            item.detail = row.detail;
            report.inventory.add(item);
        }
        return report;
    }

    /**
     * Finds the status of one node.
     *
     * @param nodeKey the node's key
     * @return the status, or null when the node never reported
     */
    @Transactional
    public NodeStatus find(String nodeKey) {
        return NodeStatus.findByKey(nodeKey);
    }

    /**
     * Every node this node knows, by point of sale, kind and identifier.
     *
     * @return the statuses
     */
    @Transactional
    public List<NodeStatus> all() {
        return NodeStatus.listOrdered();
    }

    /**
     * How long a node may stay silent before it is called offline.
     *
     * @return three report periods, in seconds
     */
    public long toleranceSeconds() {
        return (long) MISSED_REPORTS * posSettingsService.supervisionReportSeconds();
    }

    /**
     * Reads an ISO date-time.
     *
     * @param text the text, possibly null
     * @return the moment, or null
     */
    static LocalDateTime time(String text) {
        return text == null ? null : LocalDateTime.parse(text);
    }

    /**
     * Shortens a text to a column's length.
     *
     * @param text the text, possibly null
     * @param length the column's length
     * @return the text, cut when too long
     */
    static String cut(String text, int length) {
        return text == null || text.length() <= length ? text : text.substring(0, length);
    }
}
