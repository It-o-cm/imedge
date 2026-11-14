package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * The APPLICATION VERSION this node runs, and where its last update stands
 * (BO-08-03-01, BO-08-03-07, BO-08-04-01, BO-08-04-14).
 *
 * <p>ONE ROW PER NODE. The version a node was built with is a build property;
 * what this row adds is what happened since: the version an update installed,
 * the one it replaced (the rollback target, BO-08-04-14), the state of the
 * update in progress and how many times it was attempted (the automatic
 * retry, BO-08-04-01). A node that never updated has no row and runs its
 * build version.
 *
 * <p>Held identically by a register, a store node and the central node: the
 * three run the same update state machine, only the upstream they download
 * from differs.
 */
@Entity
@Table(name = "node_version")
public class NodeVersion extends PanacheEntity {

    /** Where the update of this node stands. */
    public enum State {

        /** No update was ever attempted, or the last one left nothing pending. */
        IDLE,

        /** The package of the target version is being fetched. */
        DOWNLOADING,

        /** The package is being checked against the announced checksum. */
        VERIFYING,

        /** The new version is being put in place. */
        INSTALLING,

        /** The target version runs and passed its self-test. */
        APPLIED,

        /** The update failed before anything changed; the node kept its version. */
        FAILED,

        /** The new version failed its self-test and the previous one was restored. */
        ROLLED_BACK
    }

    /** The version this node runs. */
    @Column(name = "installed_version", nullable = false, length = 40)
    public String installedVersion;

    /** The version the last update replaced, the rollback target; null before any update. */
    @Column(name = "previous_version", length = 40)
    public String previousVersion;

    /** The version the last update aimed at, null before any update. */
    @Column(name = "target_version", length = 40)
    public String targetVersion;

    /** Where the update stands. */
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    public State state = State.IDLE;

    /** How many times the current target was attempted (the automatic retry). */
    @Column(name = "attempts", nullable = false)
    public int attempts;

    /** Why the last attempt failed, null when it did not. */
    @Column(name = "last_error", length = 300)
    public String lastError;

    /** The last manual forcing this node obeyed (BO-08-04-02), null when none. */
    @Column(name = "last_force_token", length = 64)
    public String lastForceToken;

    /** When the row last changed. */
    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    /**
     * The row of this node.
     *
     * @return the row, or null when the node never updated
     */
    public static NodeVersion current() {
        return findAll().firstResult();
    }
}
