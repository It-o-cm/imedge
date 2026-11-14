package com.intermarche.edge.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A PROGRAMMED RELEASE of one referential domain (BO-03-12-03, BO-03-12-04):
 * the back office changed the domain, the change must only reach the registers
 * from {@link #effectiveAt}.
 *
 * <p>The store node keeps the PENDING version where it always keeps it — in its
 * tables, which the back office reads and edits as usual — and this row keeps
 * the version the registers ALREADY hold, captured just before the first
 * programmed change: the rows the export answered at that moment, per register
 * for a domain resolved per register. Until the date, the export, the
 * fingerprint and the change stamp answer this held version; from the date the
 * row is RELEASED, its capture dropped, and the tables are exposed again. A
 * pending row may be brought forward or postponed, or CANCELLED, which puts the
 * held version back into the tables.
 *
 * <p>One pending row per domain: every further change of a held domain joins
 * the pending version, since the tables carry only one.
 */
@Entity
@Table(name = "referential_releases")
public class ReferentialRelease extends PanacheEntity {

    /** Lifecycle of a programmed release. */
    public enum Status {
        /** Programmed: the registers still receive the held version. */
        PENDING,
        /** The date came (or was brought forward): the tables are exposed. */
        RELEASED,
        /** Withdrawn: the held version was put back into the tables. */
        CANCELLED
    }

    /** The referential domain, as {@code RefPayloads.DOMAINS} names it. */
    @Column(name = "domain", nullable = false, length = 30)
    public String domain;

    /** The lifecycle state, PENDING at creation. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    public Status status = Status.PENDING;

    /** The minute from which the pending version reaches the registers. */
    @Column(name = "effective_at", nullable = false)
    public LocalDateTime effectiveAt;

    /** When the release was programmed. */
    @Column(name = "programmed_at", nullable = false)
    public LocalDateTime programmedAt;

    /** The login of the administrator who programmed it. */
    @Column(name = "programmed_by", length = 60)
    public String programmedBy;

    /** The back-office screen whose change opened the release, or the complete deployment. */
    @Column(name = "origin", length = 200)
    public String origin;

    /** True for a COMPLETE deployment (BO-03-12-04), false for a partial one (BO-03-12-03). */
    @Column(name = "complete_deployment", nullable = false)
    public boolean complete;

    /** When the release was released or cancelled. */
    @Column(name = "closed_at")
    public LocalDateTime closedAt;

    /** Who released or cancelled it, null for the release that came on its date. */
    @Column(name = "closed_by", length = 60)
    public String closedBy;

    /** The held version as JSON, dropped once the release is closed. */
    @Column(name = "held", columnDefinition = "CLOB")
    public String held;

    /**
     * Lists the pending releases, the nearest date first.
     *
     * @return the pending rows, never null
     */
    public static List<ReferentialRelease> listPending() {
        return list("status = ?1 order by effectiveAt, domain", Status.PENDING);
    }

    /**
     * Finds the pending release of a domain.
     *
     * @param domain the referential domain
     * @return the pending row, or null when the domain is not held
     */
    public static ReferentialRelease findPending(String domain) {
        return find("status = ?1 and domain = ?2", Status.PENDING, domain).firstResult();
    }

    /**
     * Lists the pending releases whose date has come.
     *
     * @param now the reference minute
     * @return the due rows, never null
     */
    public static List<ReferentialRelease> listDue(LocalDateTime now) {
        return list("status = ?1 and effectiveAt <= ?2", Status.PENDING, now);
    }

    /**
     * Lists the closed releases, the latest first.
     *
     * @param limit how many rows at most
     * @return the history, never null
     */
    public static List<ReferentialRelease> listHistory(int limit) {
        return find("status <> ?1 order by closedAt desc, id desc", Status.PENDING).page(0, limit).list();
    }
}
