package com.intermarche.edge.domain.deploy;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One STEP of an application update on this node, dated (BO-08-03-07,
 * BO-08-04-01, BO-08-04-14).
 *
 * <p>{@link NodeVersion} says where the update stands; this table says how it
 * got there — downloaded, verified, installed, rolled back — so a failed
 * update can be explained afterwards, on the node and in the reports it
 * sends upstream.
 */
@Entity
@Table(name = "node_update_log")
public class NodeUpdateLog extends PanacheEntity {

    /** When the step ended. */
    @Column(name = "logged_at", nullable = false)
    public LocalDateTime loggedAt;

    /** The version the node ran when the step started. */
    @Column(name = "from_version", length = 40)
    public String fromVersion;

    /** The version the update aimed at. */
    @Column(name = "to_version", length = 40)
    public String toVersion;

    /** The step, named after the state it reached. */
    @Column(name = "step", nullable = false, length = 16)
    public String step;

    /** Whether the step succeeded. */
    @Column(name = "success", nullable = false)
    public boolean success;

    /** What to remember of it. */
    @Column(name = "message", length = 300)
    public String message;

    /**
     * The most recent steps, newest first.
     *
     * @param limit how many rows to read
     * @return the steps, newest first
     */
    public static List<NodeUpdateLog> latest(int limit) {
        return find("order by loggedAt desc, id desc").page(0, limit).list();
    }
}
