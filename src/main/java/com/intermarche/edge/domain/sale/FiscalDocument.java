package com.intermarche.edge.domain.sale;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A PORTUGUESE FISCAL DOCUMENT issued by this register: a simplified invoice, an
 * invoice, a credit note or a receipt (LC-08-01-05, LC-08-04-18 to -23, LC-05-07-03).
 *
 * <p>The document is what the Portuguese tax administration reads: its number in its
 * series, its ATCUD, its place in the signature chain of the series and the QR payload
 * printed at its foot. The sale or the return it states stays where it is — the
 * {@link Ticket} or the {@link Refund} — and the document only points at it, with the
 * part of the sale it covers when one sale is stated on two documents (LC-08-01-06).
 *
 * <p>Issued by the register, and carried to the store node with the sale or the
 * return it states (BO-10-06-03): the node keeps the copy its SAF-T export is drawn
 * from, and never issues nor renumbers a document itself.
 */
@Entity
@Table(name = "fiscal_documents",
        indexes = {
                @Index(name = "idx_fiscal_document_number", columnList = "document_number", unique = true),
                @Index(name = "idx_fiscal_document_ticket", columnList = "ticket_id"),
                @Index(name = "idx_fiscal_document_series", columnList = "document_type, series, series_rank")
        })
public class FiscalDocument extends PanacheEntity {

    /** The four documents of the Portuguese chain. */
    public enum Type {

        /** Fatura simplificada: the default document of a sale (LC-08-01-05). */
        FS,

        /** Fatura: an invoice stating the customer (LC-08-04-18). */
        FT,

        /** Nota de crédito: a credit note, after a return or cancelling a document. */
        NC,

        /** Recibo: the proof of payment of an invoice (LC-08-04-22). */
        RC
    }

    /** The document type. */
    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 2)
    public Type documentType;

    /** The series the document is numbered in: the register and the year. */
    @Column(name = "series", nullable = false, length = 30)
    public String series;

    /** The rank of the document in its series, from one. */
    @Column(name = "series_rank", nullable = false)
    public long rank;

    /** The document number as printed, {@code TYPE SERIES/RANK}. */
    @Column(name = "document_number", nullable = false, length = 60)
    public String number;

    /** The ATCUD: the validation code of the series, a dash, the rank. */
    @Column(name = "atcud", nullable = false, length = 60)
    public String atcud;

    /** The register that issued the document. */
    @Column(name = "terminal_id", nullable = false, length = 20)
    public String terminalId;

    /** When the document was issued. */
    @Column(name = "issued_at", nullable = false)
    public LocalDateTime issuedAt;

    /** The sale the document states, or null for a credit note of a return. */
    @Column(name = "ticket_id")
    public Long ticketId;

    /** The return a credit note states, or null. */
    @Column(name = "refund_id")
    public Long refundId;

    /** The part of a sale stated on two documents (1 or 2), zero for the whole sale. */
    @Column(name = "sale_part", nullable = false)
    public int part;

    /** The uids of the lines a part covers, comma separated; null for the whole sale. */
    @Column(name = "line_uids", length = 4000)
    public String lineUids;

    /**
     * The share of each sale payment a part carries, as {@code INDEX:AMOUNT}
     * separated by semicolons, the index being the payment's position on the sale;
     * null for the whole sale.
     */
    @Column(name = "payment_shares", length = 1000)
    public String paymentShares;

    /** The number of the document this one refers to (a credit note, a receipt), or null. */
    @Column(name = "source_number", length = 60)
    public String sourceNumber;

    /** The customer's tax number as stated on the document. */
    @Column(name = "customer_tax_id", length = 30)
    public String customerTaxId;

    /** The customer's name, stated on an invoice; null otherwise. */
    @Column(name = "customer_name", length = 120)
    public String customerName;

    /** The customer's address, stated on an invoice; null otherwise. */
    @Column(name = "customer_address", length = 200)
    public String customerAddress;

    /** The gross total of the document, taxes included, never negative. */
    @Column(name = "gross_total", precision = 19, scale = 2, nullable = false)
    public BigDecimal grossTotal;

    /** The tax the document states. */
    @Column(name = "tax_total", precision = 19, scale = 2, nullable = false)
    public BigDecimal taxTotal;

    /** The signature of the document, Base64, chained to the previous one of its series. */
    @Column(name = "signature_hash", nullable = false, length = 1024)
    public String hash;

    /** The signature of the previous document of the series, empty for the first. */
    @Column(name = "previous_hash", nullable = false, length = 1024)
    public String previousHash;

    /** The fiscal QR payload printed at the foot of the document. */
    @Column(name = "qr_payload", nullable = false, length = 1000)
    public String qrPayload;

    /** True once a credit note cancelled the document (LC-08-04-21). */
    @Column(name = "cancelled", nullable = false)
    public boolean cancelled;

    /**
     * The numeric SAF-T identifier of the document's type, as administered when
     * it was issued (BO-10-06-01, BO-10-06-02), or null for a type that carries
     * none.
     */
    @Column(name = "saft_document_id")
    public Integer saftDocumentId;

    /**
     * Where the document was produced, as the SAF-T file states it
     * (BO-07-01-06): null for a document this software produced, {@code I} for
     * one integrated from another application — the e-commerce platform's own
     * invoice, copied as it was issued.
     */
    @Column(name = "source_billing", length = 1)
    public String sourceBilling;

    /**
     * The four characters of the signature printed on the document: the 1st, 11th,
     * 21st and 31st, as the certification rules ask.
     *
     * @return the four characters, fewer when the signature is shorter
     */
    public String hashCharacters() {
        StringBuilder characters = new StringBuilder();
        for (int position = 0; position < 31 && position < hash.length(); position += 10) {
            characters.append(hash.charAt(position));
        }
        return characters.toString();
    }

    /**
     * Tells whether the document states a line of its sale: every line still sold
     * for a whole-sale document, only the listed ones for a part.
     *
     * @param line a line of the sale
     * @return true when the line is stated on this document
     */
    public boolean covers(TicketLine line) {
        if (line.cancelled) {
            return false;
        }
        return lineUids == null || List.of(lineUids.split(",")).contains(line.lineUid);
    }

    /**
     * The share of each sale payment this document carries (LC-08-01-06).
     *
     * @return the amount carried per payment position, null for a whole-sale
     *         document, which carries every payment whole
     */
    public java.util.Map<Integer, BigDecimal> shares() {
        if (paymentShares == null) {
            return null;
        }
        java.util.Map<Integer, BigDecimal> shares = new java.util.LinkedHashMap<>();
        for (String entry : paymentShares.split(";")) {
            String[] pair = entry.split(":");
            if (pair.length == 2) {
                shares.put(Integer.valueOf(pair[0]), new BigDecimal(pair[1]));
            }
        }
        return shares;
    }

    /**
     * The documents currently stating a sale, in part order: the simplified invoices
     * or invoices not cancelled.
     *
     * @param ticketId the sale
     * @return the documents, empty when the sale states none
     */
    public static List<FiscalDocument> activeForTicket(Long ticketId) {
        return list("ticketId = ?1 and cancelled = false and documentType in ?2 order by part",
                ticketId, List.of(Type.FS, Type.FT));
    }

    /**
     * Finds a document by its number, unique across the registers of a store.
     *
     * @param number the document number
     * @return the document, or null when none carries that number
     */
    public static FiscalDocument findByNumber(String number) {
        return find("number", number).firstResult();
    }

    /**
     * The documents issued in a period, in issuing order — what a SAF-T
     * export states (BO-10-06-03).
     *
     * @param from the first instant of the period, inclusive
     * @param to the end of the period, exclusive
     * @return the documents, empty when the period states none
     */
    public static List<FiscalDocument> issuedBetween(LocalDateTime from, LocalDateTime to) {
        return list("issuedAt >= ?1 and issuedAt < ?2 order by documentType, series, rank", from, to);
    }

    /**
     * The credit note issued for a return.
     *
     * @param refundId the return
     * @return the credit note, or null when none was issued
     */
    public static FiscalDocument forRefund(Long refundId) {
        List<FiscalDocument> found = list("refundId", refundId);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * The documents of a type that refer to a given document, such as the
     * receipts of an invoice.
     *
     * @param type the type of the referring documents
     * @param sourceNumber the number of the document referred to
     * @return the referring documents, empty when there is none
     */
    public static List<FiscalDocument> referring(Type type, String sourceNumber) {
        return list("documentType = ?1 and sourceNumber = ?2", type, sourceNumber);
    }

    /**
     * The last document of a series, whose signature the next one chains to.
     *
     * @param type the document type
     * @param series the series
     * @return the last document, or null for a new series
     */
    public static FiscalDocument lastOf(Type type, String series) {
        List<FiscalDocument> found = list("documentType = ?1 and series = ?2 order by rank desc", type, series);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Finds a document as an operator states it: its type, the register, the day
     * and its rank (LC-08-04-21, -22).
     *
     * @param type the document type
     * @param terminalId the register that issued it
     * @param day the day it was issued
     * @param rank its rank in its series
     * @return the document, or null when none matches
     */
    public static FiscalDocument findIssued(Type type, String terminalId, LocalDate day, long rank) {
        List<FiscalDocument> found = list(
                "documentType = ?1 and terminalId = ?2 and rank = ?3 and issuedAt >= ?4 and issuedAt < ?5",
                type, terminalId, rank, day.atStartOfDay(), day.plusDays(1).atStartOfDay());
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Whether a customer tax number names nobody: absent, or the final
     * consumer's number 999999990, with or without its country prefix
     * (LC-08-06-11).
     *
     * @param taxId the tax number as stated, possibly null
     * @return true for the final consumer
     */
    public static boolean isFinalConsumer(String taxId) {
        String number = numberOf(taxId);
        return number.isEmpty() || "999999990".equals(number.replaceAll("[^0-9]", ""));
    }

    /**
     * The country of a customer tax number (LC-08-06-11): the two-letter prefix
     * it carries, Portugal when it carries none.
     *
     * @param taxId the tax number as stated, possibly null
     * @return the ISO country code, upper case
     */
    public static String countryOf(String taxId) {
        String trimmed = taxId == null ? "" : taxId.trim();
        if (trimmed.length() > 2 && Character.isLetter(trimmed.charAt(0)) && Character.isLetter(trimmed.charAt(1))) {
            return trimmed.substring(0, 2).toUpperCase(java.util.Locale.ROOT);
        }
        return "PT";
    }

    /**
     * The number of a customer tax number without its country prefix
     * (LC-08-06-11).
     *
     * @param taxId the tax number as stated, possibly null
     * @return the number, without spaces, empty when none
     */
    public static String numberOf(String taxId) {
        String trimmed = taxId == null ? "" : taxId.trim();
        if (trimmed.length() > 2 && Character.isLetter(trimmed.charAt(0)) && Character.isLetter(trimmed.charAt(1))) {
            trimmed = trimmed.substring(2);
        }
        return trimmed.replace(" ", "");
    }
}
