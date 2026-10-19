package com.intermarche.edge.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One RESULT of a referential integration, dated (BO-10-08-10, BO-10-08-23).
 *
 * <p>{@code RefState} says what the node currently holds — one row per domain,
 * overwritten at every apply. That is the state, and the state has no memory:
 * a paramétreur asking "when did the articles last fail, and why" reads nothing
 * from it. This table is that memory, one row per integration attempt, which is
 * what the questionnaire calls the referential-update audit.
 *
 * <p>WRITTEN ONLY WHERE THE SHOP ASKS (BO-10-08-23): a node that integrates
 * fifteen domains every pull cycle would otherwise fill a table nobody reads.
 * And purged like every other history, on its own administered delay
 * (BO-10-08-10) — a trace kept forever is a trace that ends up deleted by hand.
 */
@Entity
@Table(name = "ref_apply_audits")
public class RefApplyAudit extends PanacheEntity {

    /** What an integration attempt ended as. */
    public enum Outcome {

        /** The snapshot was applied and the fingerprint recorded. */
        APPLIED,

        /** The snapshot was refused; the node kept what it had. */
        FAILED
    }

    /** The referential domain this attempt concerned. */
    @Column(name = "domain", nullable = false, length = 20)
    public String domain;

    /** When the attempt ended. */
    @Column(name = "applied_at", nullable = false)
    public LocalDateTime appliedAt;

    /** The fingerprint the node was trying to reach, null when unknown. */
    @Column(name = "fingerprint", length = 64)
    public String fingerprint;

    /** How the attempt ended. */
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 10)
    public Outcome outcome;

    /** What went wrong, for a failed attempt; null for an applied one. */
    @Column(name = "detail", length = 500)
    public String detail;

    /**
     * The most recent attempts, newest first.
     *
     * @param limit how many rows to read
     * @return the attempts, newest first
     */
    public static java.util.List<RefApplyAudit> latest(int limit) {
        return find("order by appliedAt desc, id desc").page(0, limit).list();
    }
}
