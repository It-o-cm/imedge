package com.intermarche.edge.service.deploy;

import java.util.ArrayList;
import java.util.List;

/**
 * The WIRE FORMAT of the node reports and of their answers (BO-08-01-17,
 * BO-08-03-*, BO-08-04-15/16/17/20, BO-08-02-02).
 *
 * <p>ONE CONVERSATION, TWO HOPS. A register reports to its store node, a
 * store node reports to the central node, and both carry the same payload:
 * the version the node runs and where its update stands, its peripherals,
 * its hardware and software inventory, the state of its integrations, the
 * functions it counted, the outcome of the maintenance orders it carried out.
 * A store adds its registers' last reports, so the central node sees the
 * whole line without ever reaching a register. The ANSWER travels back down
 * the same way: the version each node must run, and the maintenance orders
 * waiting for it.
 *
 * <p>Plain public fields and default constructors: the payload is mapped by
 * Jackson on both ends, and the lists start empty so an absent list reads as
 * nothing to report rather than as a null to guard.
 */
public final class DeployPayloads {

    /** A register, a point of contact d'encaissement. */
    public static final String REGISTER = "REGISTER";

    /** A store node, the edge of a point of sale. */
    public static final String STORE = "STORE";

    /** The central node, the brique centrale. */
    public static final String CENTRAL = "CENTRAL";

    /** Utility holder: no instance. */
    private DeployPayloads() {
    }

    /**
     * The stable key of a node across the organisation: a register
     * identifier is unique only inside its point of sale.
     *
     * @param kind {@link #REGISTER}, {@link #STORE} or {@link #CENTRAL}
     * @param pdvNumber the point of sale, possibly null
     * @param nodeId the node's identifier
     * @return the key, {@code KIND:PDV:ID}
     */
    public static String nodeKey(String kind, String pdvNumber, String nodeId) {
        return kind + ":" + (pdvNumber == null ? "" : pdvNumber) + ":" + nodeId;
    }

    /** What one node says about itself. */
    public static class NodeReport {
        /** {@link #REGISTER}, {@link #STORE} or {@link #CENTRAL}. */
        public String kind;
        /** The register identifier, or the point-of-sale number of a store. */
        public String nodeId;
        /** The point of sale the node belongs to. */
        public String pdvNumber;
        /** The application version the node runs. */
        public String version;
        /** The version its last update replaced, null before any update. */
        public String previousVersion;
        /** Where its update stands (a {@code NodeVersion.State} name). */
        public String updateState;
        /** The version its last update aimed at, null before any update. */
        public String updateTarget;
        /** Why its last update failed, null when it did not. */
        public String updateMessage;
        /** Whether this is the first report since the node started. */
        public boolean startup;
        /** Whether the application itself is healthy. */
        public boolean appOk = true;
        /** What is wrong with the application, null when healthy. */
        public String appDetail;
        /** When the node last closed a ticket, ISO date-time, null when never. */
        public String lastTicketAt;
        /** How many documents still wait to be pushed upstream. */
        public long outboxPending;
        /** The last push error, null when none. */
        public String outboxError;
        /** The peripherals and their state. */
        public List<Peripheral> peripherals = new ArrayList<>();
        /** The hardware and software inventory. */
        public List<InventoryItem> inventory = new ArrayList<>();
        /** The state of each referential integration. */
        public List<Integration> integrations = new ArrayList<>();
        /** The function-usage counters of the last days. */
        public List<Usage> usages = new ArrayList<>();
        /** The outcome of the maintenance orders carried out since the last report. */
        public List<ActionResult> actionResults = new ArrayList<>();
        /** A store's registers, as they last reported; empty for a register. */
        public List<NodeReport> registers = new ArrayList<>();
        /**
         * A store's revenue of its last days, day by day (BO-02-05-05): what
         * lets the central node report the revenue of every point of sale and
         * regroup it by adhérent. Empty for a register.
         */
        public List<DayRevenue> revenues = new ArrayList<>();
        /** When the report was built, ISO date-time. */
        public String reportedAt;
    }

    /** The revenue of one point of sale on one day (BO-02-05-05). */
    public static class DayRevenue {
        /** The day, ISO date. */
        public String day;
        /** The revenue of the closed sales of that day, tax included. */
        public java.math.BigDecimal revenue;
        /** How many sales were closed that day. */
        public long tickets;
    }

    /** One peripheral of a register. */
    public static class Peripheral {
        /** The peripheral's role (printer, scale, scanner...). */
        public String role;
        /** AVAILABLE, ABSENT or FAILED. */
        public String state;
    }

    /** One line of a node's hardware or software inventory. */
    public static class InventoryItem {
        /** HARDWARE or SOFTWARE. */
        public String category;
        /** What the line describes. */
        public String name;
        /** Its value: a model, a version, a count. */
        public String detail;
    }

    /** The state of one referential integration on a node. */
    public static class Integration {
        /** The referential domain or the deposited feed code. */
        public String domain;
        /** APPLIED or FAILED. */
        public String outcome;
        /** When it was last integrated, ISO date-time. */
        public String at;
        /** What to remember of it: counts or the error. */
        public String detail;
    }

    /** One function-usage counter. */
    public static class Usage {
        /** The day, ISO date. */
        public String day;
        /** The node the function was used on. */
        public String nodeId;
        /** Its point of sale. */
        public String pdvNumber;
        /** CAISSE or BACKOFFICE. */
        public String channel;
        /** The function. */
        public String function;
        /** How many times. */
        public long count;
    }

    /** The outcome of one maintenance order. */
    public static class ActionResult {
        /** The order's token. */
        public String uid;
        /** Whether it succeeded. */
        public boolean success;
        /** What the node answered. */
        public String result;
        /** When it was carried out, ISO date-time. */
        public String doneAt;
    }

    /** What the upstream node answers to a report. */
    public static class ReportAnswer {
        /** The version each concerned node must run. */
        public List<Target> targets = new ArrayList<>();
        /** The maintenance orders waiting for the concerned nodes. */
        public List<Action> actions = new ArrayList<>();
    }

    /** The version one node must run. */
    public static class Target {
        /** The node's kind. */
        public String kind;
        /** The node's identifier. */
        public String nodeId;
        /** The node's point of sale. */
        public String pdvNumber;
        /** IMEDGE or IMPOS. */
        public String product;
        /** The version to run. */
        public String version;
        /** The SHA-256 the package must have. */
        public String checksum;
        /** Whether the lot's time window is open now. */
        public boolean inWindow;
        /** A manual forcing token, null when none (BO-08-04-02). */
        public String forceToken;
        /** The deployment lot this target comes from. */
        public String lotCode;
    }

    /** One maintenance order for one node. */
    public static class Action {
        /** The order's token. */
        public String uid;
        /** The node's kind. */
        public String kind;
        /** The node's identifier. */
        public String nodeId;
        /** The node's point of sale. */
        public String pdvNumber;
        /** What to do (a {@code MaintenanceAction.ActionType} name). */
        public String type;
        /** Who asked. */
        public String issuedBy;
        /** Why. */
        public String reason;
    }
}
