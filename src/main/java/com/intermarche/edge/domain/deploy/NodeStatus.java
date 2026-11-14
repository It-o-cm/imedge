package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The LAST REPORT of one node, as this node received it (BO-08-01-17,
 * BO-08-03-01, BO-08-04-15, BO-08-04-16, BO-08-04-20).
 *
 * <p>A store node holds one row per register of its line, plus its own; the
 * central node holds one row per store node and per register of the
 * organisation, the registers' rows relayed by their store. The row is
 * REPLACED at every report: it is the current state of the node, the
 * supervision consoles read it, the deployment console compares its version
 * with the lots. What the node said yesterday is not kept here — its
 * integrations and update steps have their own memory on the node itself.
 *
 * <p>On a store node, the TARGET columns hold the version the central node
 * asked a register to run, relayed down with the register's next answer.
 */
@Entity
@Table(name = "node_status")
public class NodeStatus extends PanacheEntity {

    /** The organisation-wide key of the node, {@code KIND:PDV:ID}. */
    @Column(name = "node_key", nullable = false, unique = true, length = 64)
    public String nodeKey;

    /** REGISTER, STORE or CENTRAL. */
    @Column(name = "kind", nullable = false, length = 10)
    public String kind;

    /** The register identifier, or the point-of-sale number of a store node. */
    @Column(name = "node_id", nullable = false, length = 40)
    public String nodeId;

    /** The point of sale of the node, null for the central node. */
    @Column(name = "pdv_number", length = 5)
    public String pdvNumber;

    /** The application version the node runs. */
    @Column(name = "version", length = 40)
    public String version;

    /** The version its last update replaced. */
    @Column(name = "previous_version", length = 40)
    public String previousVersion;

    /** Where its update stands. */
    @Column(name = "update_state", length = 16)
    public String updateState;

    /** The version its last update aimed at. */
    @Column(name = "update_target", length = 40)
    public String updateTarget;

    /** Why its last update failed. */
    @Column(name = "update_message", length = 300)
    public String updateMessage;

    /** Whether the application is healthy. */
    @Column(name = "app_ok", nullable = false)
    public boolean appOk = true;

    /** What is wrong with the application. */
    @Column(name = "app_detail", length = 300)
    public String appDetail;

    /** Whether every peripheral expected on the node answers. */
    @Column(name = "peripheral_ok", nullable = false)
    public boolean peripheralOk = true;

    /** The peripherals and their state, {@code role=STATE} separated by semicolons. */
    @Column(name = "peripherals", length = 500)
    public String peripherals;

    /** Whether the node's data synchronization is healthy. */
    @Column(name = "sync_ok", nullable = false)
    public boolean syncOk = true;

    /** What is wrong with its synchronization. */
    @Column(name = "sync_detail", length = 300)
    public String syncDetail;

    /** How many documents wait to be pushed upstream. */
    @Column(name = "outbox_pending", nullable = false)
    public long outboxPending;

    /** When the node last closed a ticket (the ticket heartbeat), null when never. */
    @Column(name = "last_ticket_at")
    public LocalDateTime lastTicketAt;

    /** When the node built its last report. */
    @Column(name = "reported_at")
    public LocalDateTime reportedAt;

    /** When this node received it. */
    @Column(name = "received_at")
    public LocalDateTime receivedAt;

    /** The product of the relayed target, IMEDGE or IMPOS. */
    @Column(name = "target_product", length = 10)
    public String targetProduct;

    /** The relayed target version, null when none. */
    @Column(name = "target_version", length = 40)
    public String targetVersion;

    /** The checksum of the relayed target's package. */
    @Column(name = "target_checksum", length = 64)
    public String targetChecksum;

    /** Whether the relayed target's window was open. */
    @Column(name = "target_in_window", nullable = false)
    public boolean targetInWindow;

    /** The relayed manual forcing token. */
    @Column(name = "target_force_token", length = 64)
    public String targetForceToken;

    /** The lot the relayed target comes from. */
    @Column(name = "target_lot", length = 40)
    public String targetLot;

    /**
     * Finds the row of a node.
     *
     * @param nodeKey the node's key
     * @return the row, or null when the node never reported
     */
    public static NodeStatus findByKey(String nodeKey) {
        return find("nodeKey", nodeKey).firstResult();
    }

    /**
     * Every node, by point of sale, kind and identifier.
     *
     * @return the rows, possibly empty
     */
    public static List<NodeStatus> listOrdered() {
        return list("order by pdvNumber, kind, nodeId");
    }

    /**
     * The nodes of one kind.
     *
     * @param kind REGISTER, STORE or CENTRAL
     * @return the rows, by point of sale and identifier
     */
    public static List<NodeStatus> listByKind(String kind) {
        return list("kind = ?1 order by pdvNumber, nodeId", kind);
    }

    /**
     * Whether the node reported recently enough to be called online.
     *
     * @param now the present moment
     * @param toleranceSeconds how long a node may stay silent
     * @return true when its last report is within the tolerance
     */
    public boolean online(LocalDateTime now, long toleranceSeconds) {
        return reportedAt != null && !reportedAt.isBefore(now.minusSeconds(toleranceSeconds));
    }
}
