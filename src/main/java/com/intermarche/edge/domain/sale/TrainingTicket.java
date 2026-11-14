package com.intermarche.edge.domain.sale;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A sale made in TRAINING mode (LC-13-05-06), kept in a table of its own.
 *
 * <p>A training sale is no real sale: it consumes no ticket number, enters no
 * fiscal chain and must never count in the revenue or the accounting flows. Yet
 * the back office consults it in the electronic journal. It therefore travels
 * up to the store node in this dedicated table, which no revenue report, no Z
 * and no accounting export reads, rather than as a ticket every one of them
 * would have to learn to exclude.
 *
 * <p>Shared by the register, which writes the row and pushes it, and the store
 * node, which keeps the pushed copy under the register's own id.
 */
@Entity
@Table(name = "training_tickets")
public class TrainingTicket extends PanacheEntity {

    /** The register the training sale was made on. */
    @Column(name = "terminal_id", length = 20)
    public String terminalId;

    /** The register's own id of the row, on the store node's copy; null on the register. */
    @Column(name = "source_id")
    public Long sourceId;

    /** The login of the operator who trained, or null. */
    @Column(name = "cashier_login", length = 60)
    public String cashierLogin;

    /** When the training sale ended. */
    @Column(name = "issued_at")
    public LocalDateTime issuedAt;

    /** The number of articles sold. */
    @Column(name = "item_count")
    public int itemCount;

    /** The total of the training sale, TTC. */
    @Column(name = "total_including_tax", precision = 19, scale = 2)
    public BigDecimal totalIncludingTax;

    /** The training receipt as printed, 42-column text. */
    @Lob
    @Column(name = "content")
    public String content;

    /**
     * When the training cart was PUT ON HOLD (BO-06-06-01), null for a
     * training sale: a cart parked in school mode is kept as a row of its own,
     * so the state of pending tickets lists it with its mode.
     */
    @Column(name = "parked_at")
    public LocalDateTime parkedAt;

    /** The badge of the cashier who parked it, or null. */
    @Column(name = "parked_by", length = 20)
    public String parkedBy;

    /** When the parked training cart was recalled, or null while it waits. */
    @Column(name = "resumed_at")
    public LocalDateTime resumedAt;

    /** The register that recalled it, or null. */
    @Column(name = "resumed_terminal_id", length = 20)
    public String resumedTerminalId;

    /** The badge of the cashier who recalled it, or null. */
    @Column(name = "resumed_by", length = 20)
    public String resumedBy;

    /**
     * Finds the store node's copy of a register's row.
     *
     * @param terminalId the register
     * @param sourceId the register's id of the row
     * @return the copy, or null when none was pushed yet
     */
    public static TrainingTicket findBySource(String terminalId, Long sourceId) {
        return find("terminalId = ?1 and sourceId = ?2", terminalId, sourceId).firstResult();
    }

    /**
     * Lists the training sales of a period, most recent first, for the journal.
     *
     * @param from the first instant, inclusive
     * @param to the last instant, exclusive
     * @return the rows, never null
     */
    public static List<TrainingTicket> listBetween(LocalDateTime from, LocalDateTime to) {
        return list("parkedAt is null and issuedAt >= ?1 and issuedAt < ?2 order by issuedAt desc", from, to);
    }
}
