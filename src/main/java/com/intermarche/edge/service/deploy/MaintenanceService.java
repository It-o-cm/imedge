package com.intermarche.edge.service.deploy;

import com.intermarche.edge.domain.deploy.MaintenanceAction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The ORDER BOOK of the remote maintenance (BO-08-04-17): what was asked of
 * which node, by whom, and what the node answered.
 *
 * <p>Issue an order, hand the pending ones to the node with its next report
 * answer, stamp the outcome the node reports back. A store node also RELAYS
 * the central node's orders to its registers under the same token, and sends
 * their outcome back up once — so the central screen that issued an order is
 * the screen that sees it carried out.
 */
@ApplicationScoped
public class MaintenanceService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(MaintenanceService.class);

    /** The longest result the table holds. */
    static final int RESULT_LENGTH = 300;

    /**
     * Leaves an order for a node.
     *
     * @param kind the node's kind
     * @param pdvNumber the node's point of sale
     * @param nodeId the node's identifier
     * @param type what to do
     * @param issuedBy who asks
     * @param reason why, possibly null
     * @param origin CENTRAL or STORE
     * @return the order, or null when the node kind cannot carry it out
     */
    @Transactional
    public MaintenanceAction issue(String kind, String pdvNumber, String nodeId,
                                   MaintenanceAction.ActionType type, String issuedBy, String reason,
                                   String origin) {
        if (!type.appliesTo(kind)) {
            return null;
        }
        LOGGER.info("Entering method issue with type: " + type + ", node: " + nodeId);
        MaintenanceAction action = new MaintenanceAction();
        action.actionUid = UUID.randomUUID().toString();
        action.kind = kind;
        action.pdvNumber = pdvNumber;
        action.nodeId = nodeId;
        action.nodeKey = DeployPayloads.nodeKey(kind, pdvNumber, nodeId);
        action.actionType = type;
        action.issuedBy = issuedBy;
        action.reason = reason;
        action.origin = origin;
        action.issuedAt = LocalDateTime.now();
        action.persist();
        return action;
    }

    /**
     * Keeps a copy of an order the central node left for a node of this
     * store, unless it is already known.
     *
     * @param order the order as it travelled
     */
    @Transactional
    public void relay(DeployPayloads.Action order) {
        if (MaintenanceAction.findByUid(order.uid) != null) {
            return;
        }
        MaintenanceAction action = new MaintenanceAction();
        action.actionUid = order.uid;
        action.kind = order.kind;
        action.pdvNumber = order.pdvNumber;
        action.nodeId = order.nodeId;
        action.nodeKey = DeployPayloads.nodeKey(order.kind, order.pdvNumber, order.nodeId);
        action.actionType = MaintenanceAction.ActionType.valueOf(order.type);
        action.issuedBy = order.issuedBy;
        action.reason = order.reason;
        action.origin = "CENTRAL";
        action.issuedAt = LocalDateTime.now();
        action.persist();
    }

    /**
     * Hands a node its pending orders and marks them sent.
     *
     * @param nodeKey the node's key
     * @return the orders as they travel
     */
    @Transactional
    public List<DeployPayloads.Action> handOut(String nodeKey) {
        List<DeployPayloads.Action> orders = new ArrayList<>();
        for (MaintenanceAction action : MaintenanceAction.pendingFor(nodeKey)) {
            action.status = MaintenanceAction.Status.SENT;
            action.sentAt = LocalDateTime.now();
            action.persist();
            DeployPayloads.Action order = new DeployPayloads.Action();
            order.uid = action.actionUid;
            order.kind = action.kind;
            order.nodeId = action.nodeId;
            order.pdvNumber = action.pdvNumber;
            order.type = action.actionType.name();
            order.issuedBy = action.issuedBy;
            order.reason = action.reason;
            orders.add(order);
        }
        return orders;
    }

    /**
     * Stamps the outcomes a node reported.
     *
     * @param results the outcomes, an unknown token being ignored
     */
    @Transactional
    public void recordResults(List<DeployPayloads.ActionResult> results) {
        for (DeployPayloads.ActionResult result : results) {
            MaintenanceAction action = MaintenanceAction.findByUid(result.uid);
            if (action == null) {
                continue;
            }
            action.status = result.success ? MaintenanceAction.Status.DONE : MaintenanceAction.Status.FAILED;
            String text = result.result == null ? "" : result.result;
            action.result = text.length() > RESULT_LENGTH ? text.substring(0, RESULT_LENGTH) : text;
            action.doneAt = result.doneAt == null ? LocalDateTime.now() : LocalDateTime.parse(result.doneAt);
            action.persist();
        }
    }

    /**
     * The outcomes of the central node's orders not yet sent back up, marked
     * forwarded as they are handed out.
     *
     * @return the outcomes as they travel
     */
    @Transactional
    public List<DeployPayloads.ActionResult> unforwarded() {
        List<DeployPayloads.ActionResult> results = new ArrayList<>();
        for (MaintenanceAction action : MaintenanceAction.unforwardedOutcomes()) {
            action.forwarded = true;
            action.persist();
            results.add(result(action.actionUid, action.status == MaintenanceAction.Status.DONE,
                    action.result, action.doneAt));
        }
        return results;
    }

    /**
     * The most recent orders, for the maintenance screen.
     *
     * @param limit how many to read
     * @return the orders, newest first
     */
    @Transactional
    public List<MaintenanceAction> history(int limit) {
        return MaintenanceAction.latest(limit);
    }

    /**
     * Builds an outcome as it travels.
     *
     * @param uid the order's token
     * @param success whether it succeeded
     * @param text what the node answered
     * @param doneAt when, possibly null
     * @return the outcome
     */
    public static DeployPayloads.ActionResult result(String uid, boolean success, String text,
                                                     LocalDateTime doneAt) {
        DeployPayloads.ActionResult result = new DeployPayloads.ActionResult();
        result.uid = uid;
        result.success = success;
        result.result = text;
        result.doneAt = doneAt == null ? null : doneAt.toString();
        return result;
    }
}
