package com.intermarche.edge.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One FILE DEPOSITED on this node's import door, and how its integration
 * went (BO-08-01-17, BO-08-04-01 to BO-08-04-13).
 *
 * <p>The central echelon integrates by file deposit, so a failed integration
 * there used to have no retry but a new deposit. This row is the deposit
 * kept: a file whose integration failed or left rejected lines KEEPS ITS
 * CONTENT, and the node replays it — automatically at the administered
 * cadence, or on demand from the integration console — until it goes in or
 * the attempts are spent. A successful deposit keeps its outcome and drops
 * its content: the row is then the integration's trace, not a copy of the
 * file.
 */
@Entity
@Table(name = "integration_deposit")
public class IntegrationDeposit extends PanacheEntity {

    /** How a deposit's integration stands. */
    public enum Status {
        /** Integrated with no rejected line. */
        APPLIED,
        /** Integrated with rejected lines: kept for replay. */
        PARTIAL,
        /** The integration failed: kept for replay. */
        FAILED,
        /** The file was refused as unreadable: replayed only on demand. */
        REJECTED,
        /** The automatic attempts are spent: replayed only on demand. */
        ABANDONED
    }

    /** The feed code the file was deposited under. */
    @Column(name = "code", nullable = false, length = 30)
    public String code;

    /** The import mode, for the customer feed. */
    @Column(name = "import_mode", length = 20)
    public String mode;

    /** The nomenclature scheme. */
    @Column(name = "scheme", length = 40)
    public String scheme;

    /** The nomenclature scheme label. */
    @Column(name = "scheme_label", length = 120)
    public String schemeLabel;

    /** The enseigne the nomenclature classifies for. */
    @Column(name = "enseigne", length = 8)
    public String enseigne;

    /** The file, kept while it must be replayed; null once integrated. */
    @Lob
    @Column(name = "content")
    public String content;

    /**
     * The SHA-256 of the file, kept once its content is dropped, so that the
     * integration pull recognises a file it already integrated (BO-11-01-01).
     */
    @Column(name = "content_hash", length = 64)
    public String contentHash;

    /** How the integration stands. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    public Status status;

    /** How many times the file was integrated. */
    @Column(name = "attempts", nullable = false)
    public int attempts;

    /** What the last integration answered. */
    @Column(name = "last_result", length = 500)
    public String lastResult;

    /** When the file was deposited. */
    @Column(name = "deposited_at", nullable = false)
    public LocalDateTime depositedAt;

    /** When it was last integrated. */
    @Column(name = "last_attempt_at")
    public LocalDateTime lastAttemptAt;

    /**
     * The deposits the automatic replay must look at.
     *
     * @return the failed and partial deposits, oldest first
     */
    public static List<IntegrationDeposit> retryable() {
        return list("status in ?1 order by depositedAt", List.of(Status.FAILED, Status.PARTIAL));
    }

    /**
     * The most recent deposits, newest first.
     *
     * @param limit how many rows to read
     * @return the deposits
     */
    public static List<IntegrationDeposit> latest(int limit) {
        return find("order by depositedAt desc, id desc").page(0, limit).list();
    }
}
