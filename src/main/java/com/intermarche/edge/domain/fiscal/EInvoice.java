package com.intermarche.edge.domain.fiscal;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * An ELECTRONIC INVOICE ({@code BO-05-01-02}): an invoice the registers issued
 * to a professional customer subject to VAT in France, in its structured form,
 * and its life cycle on the dematerialisation platform.
 *
 * <p>The structured form is NOT a PDF: it is the UN/CEFACT Cross Industry
 * Invoice (profile EN 16931) the reform asks for, built from the invoice as the
 * register issued it and frozen at the time it is built, so what was deposited
 * is what is shown. The statuses are those of the reform's life cycle, each
 * with its code; the two first are the node's own — not yet deposited, deposit
 * failed and to be tried again.
 */
@Entity
@Table(name = "e_invoices",
        indexes = {
                @Index(name = "idx_e_invoice_number", columnList = "invoice_number", unique = true),
                @Index(name = "idx_e_invoice_status", columnList = "status")
        })
public class EInvoice extends PanacheEntity {

    /** The life cycle of an electronic invoice. */
    public enum Status {

        /** Built, not yet deposited. */
        PENDING(0, "À déposer", false),

        /** The deposit failed; it is tried again. */
        FAILED(1, "Dépôt en échec", false),

        /** Deposited on the platform. */
        DEPOSITED(200, "Déposée", false),

        /** Issued by the platform. */
        ISSUED(201, "Émise par la plateforme", false),

        /** Received by the buyer's platform. */
        RECEIVED(202, "Reçue par la plateforme", false),

        /** Made available to the buyer. */
        MADE_AVAILABLE(203, "Mise à disposition", false),

        /** Taken in charge by the buyer. */
        TAKEN_IN_CHARGE(204, "Prise en charge", false),

        /** Approved by the buyer. */
        APPROVED(205, "Approuvée", false),

        /** Partially approved by the buyer. */
        PARTIALLY_APPROVED(206, "Approuvée partiellement", false),

        /** Disputed by the buyer. */
        DISPUTED(207, "En litige", false),

        /** Suspended by the buyer. */
        SUSPENDED(208, "Suspendue", false),

        /** Refused by the buyer. */
        REFUSED(210, "Refusée", true),

        /** The buyer transmitted the payment. */
        PAYMENT_SENT(211, "Paiement transmis", false),

        /** Cashed by the seller. */
        CASHED(212, "Encaissée", true),

        /** Rejected by the platform. */
        REJECTED(213, "Rejetée", true);

        /** The code of the status in the reform's life cycle. */
        private final int code;

        /** The operator-facing label (French). */
        private final String label;

        /** Whether the invoice's cycle ends there. */
        private final boolean last;

        /**
         * Builds a status.
         *
         * @param code its code
         * @param label its label
         * @param last whether the cycle ends there
         */
        Status(int code, String label, boolean last) {
            this.code = code;
            this.label = label;
            this.last = last;
        }

        /**
         * Returns the code of the status.
         *
         * @return the code
         */
        public int getCode() {
            return code;
        }

        /**
         * Returns the label of the status.
         *
         * @return the label
         */
        public String getLabel() {
            return label;
        }

        /**
         * Tells whether the invoice's cycle ends with this status.
         *
         * @return true for a final status
         */
        public boolean isLast() {
            return last;
        }

        /**
         * Reads a status the platform states, by its code or its name.
         *
         * @param value the code or the name, possibly null
         * @return the status, or null when the value names none
         */
        public static Status of(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String trimmed = value.trim();
            for (Status status : values()) {
                if (status.name().equalsIgnoreCase(trimmed) || String.valueOf(status.code).equals(trimmed)) {
                    return status;
                }
            }
            return null;
        }
    }

    /** The number of the invoice, as the register issued it. */
    @Column(name = "invoice_number", nullable = false, length = 40)
    public String invoiceNumber;

    /** The node's identifier of the invoice. */
    @Column(name = "invoice_id")
    public Long invoiceId;

    /** When the invoice was issued. */
    @Column(name = "issued_at", nullable = false)
    public LocalDateTime issuedAt;

    /** The buyer's name. */
    @Column(name = "buyer_name", length = 120)
    public String buyerName;

    /** The buyer's SIREN. */
    @Column(name = "buyer_siren", length = 20)
    public String buyerSiren;

    /** The invoice's total, tax included. */
    @Column(name = "total_including_tax", nullable = false, precision = 19, scale = 2)
    public BigDecimal totalIncludingTax = BigDecimal.ZERO;

    /** The structured invoice, frozen when built. */
    @Lob
    @Column(name = "payload", nullable = false)
    public String payload;

    /** Where the invoice stands. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    public Status status = Status.PENDING;

    /** The platform's identifier of the deposited invoice, or null. */
    @Column(name = "platform_id", length = 80)
    public String platformId;

    /** How many deposits failed. */
    @Column(name = "attempts", nullable = false)
    public int attempts;

    /** The last failure, or the reason the platform gave, or null. */
    @Column(name = "last_error", length = 255)
    public String lastError;

    /** When the next deposit or refresh may be tried. */
    @Column(name = "next_attempt_at")
    public LocalDateTime nextAttemptAt;

    /** When the invoice was built. */
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    /** When it was deposited, or null. */
    @Column(name = "submitted_at")
    public LocalDateTime submittedAt;

    /** When its status last changed. */
    @Column(name = "status_at")
    public LocalDateTime statusAt;

    /** The statuses it went through, as {@code CODE@instant;...}. */
    @Column(name = "history", length = 2000)
    public String history;

    /**
     * Moves the invoice to a status and keeps it in its history.
     *
     * @param next the new status
     * @param at when it changed
     */
    public void move(Status next, LocalDateTime at) {
        status = next;
        statusAt = at;
        String entry = next.getCode() + "@" + at.withNano(0);
        String joined = history == null || history.isEmpty() ? entry : history + ";" + entry;
        history = joined.length() <= 2000 ? joined : joined.substring(joined.length() - 2000);
    }

    /**
     * Finds the electronic invoice of an invoice number.
     *
     * @param number the invoice number
     * @return the electronic invoice, or null
     */
    public static EInvoice findByNumber(String number) {
        return EInvoice.<EInvoice>find("invoiceNumber", number).firstResult();
    }

    /**
     * Finds an electronic invoice by the platform's identifier.
     *
     * @param platformId the identifier
     * @return the electronic invoice, or null
     */
    public static EInvoice findByPlatformId(String platformId) {
        return EInvoice.<EInvoice>find("platformId", platformId).firstResult();
    }

    /**
     * The electronic invoices whose deposit is due.
     *
     * @param now the instant of the tick
     * @param limit how many at most
     * @return the pending ones and the failed ones whose delay elapsed
     */
    public static List<EInvoice> dueForDeposit(LocalDateTime now, int limit) {
        return EInvoice.<EInvoice>find("status in ?1 and (nextAttemptAt is null or nextAttemptAt <= ?2) order by id",
                List.of(Status.PENDING, Status.FAILED), now).page(0, limit).list();
    }

    /**
     * The deposited electronic invoices whose status is due for a refresh.
     *
     * @param now the instant of the tick
     * @param limit how many at most
     * @return the invoices still in their cycle, not refreshed recently
     */
    public static List<EInvoice> dueForRefresh(LocalDateTime now, int limit) {
        List<Status> open = new java.util.ArrayList<>();
        for (Status status : Status.values()) {
            if (status.getCode() >= 200 && !status.isLast()) {
                open.add(status);
            }
        }
        return EInvoice.<EInvoice>find("status in ?1 and (nextAttemptAt is null or nextAttemptAt <= ?2) order by id",
                open, now).page(0, limit).list();
    }

    /**
     * The latest electronic invoices, newest first, of one status or all.
     *
     * @param status the status, null for all
     * @param limit how many at most
     * @return the invoices
     */
    public static List<EInvoice> latest(Status status, int limit) {
        return status == null
                ? EInvoice.<EInvoice>find("order by id desc").page(0, limit).list()
                : EInvoice.<EInvoice>find("status = ?1 order by id desc", status).page(0, limit).list();
    }
}
