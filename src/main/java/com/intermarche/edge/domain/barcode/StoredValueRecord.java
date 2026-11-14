package com.intermarche.edge.domain.barcode;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The store node's copy of an instrument a register printed — a credit note
 * or a gift card — as its registers last reported it (BO-03-06-62, -48).
 *
 * <p>One row per instrument, keyed by the register that ISSUED it and its
 * number: the same number printed by two registers is two instruments, and a
 * register that adopted an instrument printed elsewhere reports under the
 * issuer's key, so its redemptions update the same row. The balance is the
 * last one reported.
 */
@Entity
@Table(name = "stored_value_records",
        uniqueConstraints = @UniqueConstraint(columnNames = {"pdv_number", "issuing_terminal_id", "number"}))
public class StoredValueRecord extends PanacheEntity {

    /**
     * The point of sale the instrument was printed in (BO-03-06-48): on a
     * store node its own, or the issuing shop's for an instrument it learnt
     * from the central node; on the central node, the shop that reported it.
     * Two shops' registers share terminal identifiers, so the central node
     * keys an instrument by its shop too.
     */
    @Column(name = "pdv_number", length = 20)
    public String pdvNumber;

    /** The register that printed the instrument: the N° TPV of the list. */
    @Column(name = "issuing_terminal_id", nullable = false, length = 20)
    public String issuingTerminalId;

    /** The printed number, the barcode the customer hands back. */
    @Column(name = "number", nullable = false, length = 40)
    public String number;

    /** CREDIT_NOTE or GIFT_CARD. */
    @Column(name = "kind", nullable = false, length = 15)
    public String kind;

    /** The amount the instrument was issued with. */
    @Column(name = "initial_amount", precision = 10, scale = 2, nullable = false)
    public BigDecimal initialAmount;

    /** The balance last reported. */
    @Column(name = "balance", precision = 10, scale = 2, nullable = false)
    public BigDecimal balance;

    /** ACTIVE or EXHAUSTED, as last reported. */
    @Column(name = "status", nullable = false, length = 12)
    public String status;

    /** When the instrument was printed. */
    @Column(name = "issued_at", nullable = false)
    public LocalDateTime issuedAt;

    /** The end of validity, or null when it never expires. */
    @Column(name = "valid_until")
    public LocalDateTime validUntil;

    /** When the balance reached zero, or null. */
    @Column(name = "exhausted_at")
    public LocalDateTime exhaustedAt;

    /** The sale or refund that printed it: the N° transaction of the list. */
    @Column(name = "issuing_document_number", length = 40)
    public String issuingDocumentNumber;

    /** The register that reported last. */
    @Column(name = "reported_by", length = 20)
    public String reportedBy;

    /** When the node last received a report. */
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    /**
     * Finds an instrument by its issuer and number.
     *
     * @param issuingTerminalId the register that printed it
     * @param number the printed number
     * @return the row, or null
     */
    public static StoredValueRecord findByIssuer(String issuingTerminalId, String number) {
        return find("issuingTerminalId = ?1 and number = ?2", issuingTerminalId, number).firstResult();
    }

    /**
     * Finds an instrument by its shop, its issuer and its number — the key the
     * central node holds every shop's instruments under (BO-03-06-48).
     *
     * @param pdvNumber the shop it was printed in
     * @param issuingTerminalId the register that printed it
     * @param number the printed number
     * @return the row, or null
     */
    public static StoredValueRecord findByIssuer(String pdvNumber, String issuingTerminalId, String number) {
        return find("pdvNumber = ?1 and issuingTerminalId = ?2 and number = ?3",
                pdvNumber, issuingTerminalId, number).firstResult();
    }

    /**
     * Finds an instrument by its number alone, the most recently reported one
     * when two registers printed the same number.
     *
     * @param number the printed number
     * @return the row, or null when no register of the shop printed it
     */
    public static StoredValueRecord findByNumber(String number) {
        return find("number = ?1 order by updatedAt desc, id desc", number).firstResult();
    }

    /**
     * The instruments printed over a period, the most recent first.
     *
     * @param from the first instant, inclusive
     * @param to the last instant, exclusive
     * @return the instruments
     */
    public static List<StoredValueRecord> listIssuedBetween(LocalDateTime from, LocalDateTime to) {
        return list("issuedAt >= ?1 and issuedAt < ?2 order by issuedAt desc, id desc", from, to);
    }
}
