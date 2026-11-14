package com.intermarche.edge.domain.barcode;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One payment barcode read at a till — a purchase voucher, a credit note, a
 * gift card, a deposit slip — whether the system knew it or not
 * (BO-03-06-63).
 *
 * <p>The register writes one row per reading and pushes it to the store node,
 * which keeps the rows of every register of the shop for the back office's
 * list of the barcodes passed at the tills. The node's copy carries the
 * register's own id in {@link #sourceId}; on the register it stays null.
 */
@Entity
@Table(name = "scanned_barcodes")
public class ScannedBarcode extends PanacheEntity {

    /** The register the code was read on. */
    @Column(name = "terminal_id", nullable = false, length = 20)
    public String terminalId;

    /** The register's own id of the row, on the node's copy; null on the register. */
    @Column(name = "source_id")
    public Long sourceId;

    /** The code as it was read. */
    @Column(name = "code", nullable = false, length = 64)
    public String code;

    /** The administered range that recognised the code, or null. */
    @Column(name = "type_code", length = 50)
    public String typeCode;

    /** The label of that range, or null. */
    @Column(name = "type_label", length = 100)
    public String typeLabel;

    /** The sale the code was presented to, or null. */
    @Column(name = "ticket_number", length = 40)
    public String ticketNumber;

    /** The amount the code was worth at that reading, or null when unknown. */
    @Column(name = "amount", precision = 10, scale = 2)
    public BigDecimal amount;

    /** The end of validity of the instrument, or null when it has none. */
    @Column(name = "valid_until")
    public LocalDateTime validUntil;

    /** When the code was read: the date of use. */
    @Column(name = "scanned_at", nullable = false)
    public LocalDateTime scannedAt;

    /** Whether the system knew the code. */
    @Column(name = "found", nullable = false)
    public boolean found;

    /**
     * Whether the code was read as a GS1 payload — an Element String, a
     * DataMatrix, a Databar or a Digital Link — whose instrument number the
     * register extracted (BO-11-01-18).
     */
    @Column(name = "gs1", nullable = false)
    public boolean gs1;

    /**
     * Finds the node's copy of a register's row.
     *
     * @param terminalId the register
     * @param sourceId the register's id of the row
     * @return the copy, or null when it was never received
     */
    public static ScannedBarcode findBySource(String terminalId, Long sourceId) {
        return find("terminalId = ?1 and sourceId = ?2", terminalId, sourceId).firstResult();
    }

    /**
     * The readings of a period, the most recent first.
     *
     * @param from the first instant, inclusive
     * @param to the last instant, exclusive
     * @return the readings
     */
    public static List<ScannedBarcode> listBetween(LocalDateTime from, LocalDateTime to) {
        return list("scannedAt >= ?1 and scannedAt < ?2 order by scannedAt desc, id desc", from, to);
    }
}
