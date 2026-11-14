package com.intermarche.edge.domain.sale;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * The INALTERABLE TRACE of a ticket put on hold (LC-04-01-03, article 88 of the
 * law against VAT fraud): every putting on hold, every resumption and every purge
 * of a ticket left on hold at the Z closing is recorded with the content the
 * ticket had at that moment, signed and chained to the previous record of the
 * same register.
 *
 * <p>The ticket on hold is a draft the register keeps changing; this record is
 * what it was, frozen. The signature is a SHA-256 over the record's identifying
 * fields, its content and the signature of the record before it ({@code GENESIS}
 * for the first one): changing, removing or inserting a record breaks the chain
 * from that record on, and {@link #computeSignature()} lets anyone holding the
 * rows check it. Records are never updated nor deleted; the register pushes each
 * one to the store node, which keeps its copy under the register's own id and
 * checks the same chain.
 *
 * <p>Shared by the register and the store node, identical on both.
 */
@Entity
@Table(name = "parked_ticket_records")
public class ParkedTicketRecord extends PanacheEntity {

    /** The anchor of the first record of a register. */
    public static final String GENESIS = "GENESIS";

    /** What happened to the ticket on hold. */
    public enum Event {

        /** The ticket was put on hold. */
        PARKED,

        /** The ticket was taken back. */
        RESUMED,

        /** The ticket, never taken back, was purged at the Z closing. */
        PURGED
    }

    /** The register the record belongs to. */
    @Column(name = "terminal_id", length = 20)
    public String terminalId;

    /** The register's own id of the record, on the store node's copy; null on the register. */
    @Column(name = "source_id")
    public Long sourceId;

    /** The number of the ticket on hold. */
    @Column(name = "ticket_number", length = 40)
    public String ticketNumber;

    /** What happened to it. */
    @Enumerated(EnumType.STRING)
    @Column(name = "event", length = 10)
    public Event event;

    /** When it happened. */
    @Column(name = "event_date")
    public LocalDateTime eventDate;

    /** Who did it: the operator's badge, or the Z closing. */
    @Column(name = "operator", length = 40)
    public String operator;

    /** The number of articles the ticket carried. */
    @Column(name = "item_count")
    public int itemCount;

    /** The total the ticket carried, tax included. */
    @Column(name = "total_amount", precision = 19, scale = 2)
    public BigDecimal totalAmount;

    /** The ticket's lines at that moment, one per line: quantity, label, unit price, total. */
    @Lob
    @Column(name = "content")
    public String content;

    /** The signature of the previous record of the register, or {@link #GENESIS}. */
    @Column(name = "previous_signature", length = 64)
    public String previousSignature;

    /** The SHA-256 signature of this record, chained to the previous one. */
    @Column(name = "signature", length = 64)
    public String signature;

    /**
     * Computes the signature the record must carry: SHA-256 over its fields in a
     * fixed order and the previous signature, lowercase hex.
     *
     * @return the 64-character signature
     */
    public String computeSignature() {
        String payload = String.join("|",
                String.valueOf(terminalId),
                String.valueOf(ticketNumber),
                String.valueOf(event),
                String.valueOf(eventDate),
                String.valueOf(operator),
                String.valueOf(itemCount),
                totalAmount == null ? "null" : totalAmount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                String.valueOf(content),
                String.valueOf(previousSignature));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    /**
     * Checks a chain of records: each one must carry the signature of the one
     * before it and the signature its own content gives.
     *
     * @param records the records of one register, in chain order from its first
     * @return the first record that breaks the chain, or null when it holds
     */
    public static ParkedTicketRecord firstBroken(List<ParkedTicketRecord> records) {
        String expectedPrevious = GENESIS;
        for (ParkedTicketRecord record : records) {
            if (!expectedPrevious.equals(record.previousSignature)
                    || !record.computeSignature().equals(record.signature)) {
                return record;
            }
            expectedPrevious = record.signature;
        }
        return null;
    }

    /**
     * Lists every record of a register on the store node, in the register's
     * chain order, for its verification.
     *
     * @param terminalId the register
     * @return the records, never null
     */
    public static List<ParkedTicketRecord> listChain(String terminalId) {
        return list("terminalId = ?1 order by sourceId, id", terminalId);
    }

    /**
     * The last record of a register on the register itself, anchor of its chain.
     *
     * @param terminalId the register
     * @return the last record, or null when there is none
     */
    public static ParkedTicketRecord findLastByTerminal(String terminalId) {
        return find("terminalId = ?1 and sourceId is null order by id desc", terminalId).firstResult();
    }

    /**
     * Finds the store node's copy of a register's record.
     *
     * @param terminalId the register
     * @param sourceId the register's id of the record
     * @return the copy, or null when none was pushed yet
     */
    public static ParkedTicketRecord findBySource(String terminalId, Long sourceId) {
        return find("terminalId = ?1 and sourceId = ?2", terminalId, sourceId).firstResult();
    }

    /**
     * Lists, on the store node, the records of the tickets STILL ON HOLD
     * (BO-09-01-05, -08): a putting on hold no later record of the same
     * ticket — a resumption, a purge — followed in its register's chain.
     *
     * @return the records, by register then chain order, never null
     */
    public static List<ParkedTicketRecord> listPending() {
        return list("from ParkedTicketRecord r where r.event = ?1 and r.sourceId is not null and not exists"
                + " (select o.id from ParkedTicketRecord o where o.terminalId = r.terminalId"
                + " and o.ticketNumber = r.ticketNumber and o.sourceId > r.sourceId)"
                + " order by r.terminalId, r.sourceId", Event.PARKED);
    }

    /**
     * Lists the records of a period, in chain order per register.
     *
     * @param from the first instant, inclusive
     * @param to the last instant, exclusive
     * @return the records, never null
     */
    public static List<ParkedTicketRecord> listBetween(LocalDateTime from, LocalDateTime to) {
        return list("eventDate >= ?1 and eventDate < ?2 order by terminalId, eventDate, id", from, to);
    }
}
