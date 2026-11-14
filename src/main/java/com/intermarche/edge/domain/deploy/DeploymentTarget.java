package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One NODE named by a deployment lot, and how its update went
 * (BO-08-03-02/03/06, BO-08-04-01/02).
 *
 * <p>A point of sale's store node, one register of a point of sale, or the
 * central brick. The state follows the node's reports: in progress while it
 * downloads, applied once it reports the lot's version, failed or rolled back
 * when it says so. The FORCE token is the manual forcing (BO-08-04-02): a new
 * token travels to the node, which resets its attempts and runs at once.
 */
@Entity
@Table(name = "deployment_target")
public class DeploymentTarget extends PanacheEntity {

    /** How a target's update went. */
    public enum State {
        /** Not attempted yet. */
        PENDING,
        /** The node is downloading, verifying or installing. */
        IN_PROGRESS,
        /** The node runs the lot's version. */
        APPLIED,
        /** The update failed. */
        FAILED,
        /** The version failed its self-test and the previous one was restored. */
        ROLLED_BACK
    }

    /** The lot. */
    @Column(name = "lot_id", nullable = false)
    public Long lotId;

    /** The node's key. */
    @Column(name = "node_key", nullable = false, length = 64)
    public String nodeKey;

    /** REGISTER, STORE or CENTRAL. */
    @Column(name = "kind", nullable = false, length = 10)
    public String kind;

    /** The node's identifier. */
    @Column(name = "node_id", nullable = false, length = 40)
    public String nodeId;

    /** The node's point of sale. */
    @Column(name = "pdv_number", length = 5)
    public String pdvNumber;

    /** How the update went. */
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 12)
    public State state = State.PENDING;

    /** The version the node last reported. */
    @Column(name = "reported_version", length = 40)
    public String reportedVersion;

    /** What the node last said about its update. */
    @Column(name = "message", length = 300)
    public String message;

    /** When the state last changed. */
    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    /** The current manual forcing token, null when never forced. */
    @Column(name = "force_token", length = 64)
    public String forceToken;

    /**
     * The targets of a lot.
     *
     * @param lotId the lot
     * @return its targets, by point of sale and node
     */
    public static List<DeploymentTarget> listByLot(Long lotId) {
        return list("lotId = ?1 order by pdvNumber, kind, nodeId", lotId);
    }

    /**
     * Every lot entry of one node.
     *
     * @param nodeKey the node's key
     * @return its targets
     */
    public static List<DeploymentTarget> listByNode(String nodeKey) {
        return list("nodeKey", nodeKey);
    }

    /**
     * Finds the entry of one node in one lot.
     *
     * @param lotId the lot
     * @param nodeKey the node's key
     * @return the target, or null
     */
    public static DeploymentTarget findKey(Long lotId, String nodeKey) {
        return find("lotId = ?1 and nodeKey = ?2", lotId, nodeKey).firstResult();
    }
}
