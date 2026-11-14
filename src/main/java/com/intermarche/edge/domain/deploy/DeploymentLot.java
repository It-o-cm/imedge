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
 * A DEPLOYMENT LOT: one version of one application, a set of target nodes
 * and the time window of the update (BO-08-03-02/03/04/05/06/09/10).
 *
 * <p>A lot targets store nodes (product IMEDGE: the store back office, or the
 * central brick itself) or registers (product IMPOS: the line of checkouts).
 * A PILOT lot targets a group of points of sale only; the others keep the
 * version they run, because a node only ever receives the version of a lot
 * that names it (BO-08-03-09/10). A lot does nothing while it is a DRAFT:
 * once SCHEDULED, the nodes it names update inside its window, and those
 * that were switched off catch up at their next start-up (BO-08-03-07).
 */
@Entity
@Table(name = "deployment_lot")
public class DeploymentLot extends PanacheEntity {

    /** Where a lot stands. */
    public enum Status {
        /** Being composed: distributes nothing. */
        DRAFT,
        /** Launched: its targets receive its version from the window's start. */
        SCHEDULED,
        /** Closed: distributes nothing any more. */
        CLOSED
    }

    /** The lot's code, unique. */
    @Column(name = "code", nullable = false, unique = true, length = 40)
    public String code;

    /** What the lot is for. */
    @Column(name = "label", length = 120)
    public String label;

    /** IMEDGE or IMPOS. */
    @Column(name = "product", nullable = false, length = 10)
    public String product;

    /** The version the lot distributes. */
    @Column(name = "version", nullable = false, length = 40)
    public String version;

    /** Whether the lot is a pilot phase on a group of points of sale. */
    @Column(name = "pilot", nullable = false)
    public boolean pilot;

    /** The lowest version a node must run to take this update, null for any (BO-08-03-01). */
    @Column(name = "min_version", length = 40)
    public String minVersion;

    /** The start of the update window. */
    @Column(name = "window_start", nullable = false)
    public LocalDateTime windowStart;

    /** The end of the update window. */
    @Column(name = "window_end", nullable = false)
    public LocalDateTime windowEnd;

    /** Where the lot stands. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    public Status status = Status.DRAFT;

    /** Who created it. */
    @Column(name = "created_by", length = 80)
    public String createdBy;

    /** When it was created. */
    @Column(name = "created_at")
    public LocalDateTime createdAt;

    /**
     * Whether the lot distributes its version at a moment: launched and its
     * window started.
     *
     * @param now the moment
     * @return true when its targets must run its version
     */
    public boolean started(LocalDateTime now) {
        return status == Status.SCHEDULED && !now.isBefore(windowStart);
    }

    /**
     * Whether the lot's window is open at a moment.
     *
     * @param now the moment
     * @return true when started and not yet past its end
     */
    public boolean inWindow(LocalDateTime now) {
        return started(now) && !now.isAfter(windowEnd);
    }

    /**
     * Finds a lot by its code.
     *
     * @param code the code
     * @return the lot, or null
     */
    public static DeploymentLot findByCode(String code) {
        return find("code", code).firstResult();
    }

    /**
     * Every lot, the most recent window first.
     *
     * @return the lots
     */
    public static List<DeploymentLot> listOrdered() {
        return list("order by windowStart desc, id desc");
    }

    /**
     * The launched lots of one product, the most recent window first.
     *
     * @param product IMEDGE or IMPOS
     * @return the lots
     */
    public static List<DeploymentLot> listScheduled(String product) {
        return list("status = ?1 and product = ?2 order by windowStart desc, id desc", Status.SCHEDULED, product);
    }
}
