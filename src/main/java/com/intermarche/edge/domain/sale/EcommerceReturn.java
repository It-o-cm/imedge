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
 * A RETURN OF THE E-COMMERCE PLATFORM as the store node recorded it
 * (DG-01-03): the return the platform accepted from its customer, recorded at
 * the point of sale as a refund of the e-commerce virtual register — of the
 * integrated order it names, or as a return ticket when it names none — with
 * its reason, its return type and the report the platform was answered.
 *
 * <p>Like {@link EcommerceOrder}, this row is the trace of the recording, not
 * the transaction: the money is the {@link Refund} (or the return ticket)
 * named by {@link #refundNumber}. It makes the call idempotent: a reference
 * already recorded is answered again, never refunded twice.
 */
@Entity
@Table(name = "ecommerce_returns")
public class EcommerceReturn extends PanacheEntity {

    /** The return reference of the e-commerce platform, unique. */
    @Column(name = "reference", unique = true, nullable = false, length = 40)
    public String reference;

    /** When the store node recorded the return. */
    @Column(name = "received_at", nullable = false)
    public LocalDateTime receivedAt;

    /** The order the return names, or null. */
    @Column(name = "order_reference", length = 40)
    public String orderReference;

    /** The ticket the return refunds, or null for a return without origin. */
    @Column(name = "original_ticket_number", length = 30)
    public String originalTicketNumber;

    /** The refund (or the return ticket) the return was booked as. */
    @Column(name = "refund_number", length = 30)
    public String refundNumber;

    /** The refunded total, TTC. */
    @Column(name = "total_amount", precision = 19, scale = 2)
    public BigDecimal totalAmount;

    /** The code of the return reason (DG-01-03-02, -05), or null. */
    @Column(name = "reason_code", length = 40)
    public String reasonCode;

    /** The label of the return reason, or null. */
    @Column(name = "reason_label", length = 80)
    public String reasonLabel;

    /** The return type the platform named (BO-10-01-04, -05), or null. */
    @Column(name = "return_type", length = 60)
    public String returnType;

    /** The operator-facing outcome, in French. */
    @Column(name = "message", length = 255)
    public String message;

    /** The return report answered to the platform, as JSON. */
    @Lob
    @Column(name = "report")
    public String report;

    /** The return as the platform sent it, or null when the store archives none. */
    @Lob
    @Column(name = "original_payload")
    public String originalPayload;

    /**
     * Finds a return by its platform reference.
     *
     * @param reference the reference, or null
     * @return the return, or null when the node never recorded it
     */
    public static EcommerceReturn findByReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        return find("reference", reference.trim()).firstResult();
    }

    /**
     * Lists every return recorded, most recent first, for the back-office screen.
     *
     * @return the returns, never null
     */
    public static List<EcommerceReturn> listAllOrdered() {
        return list("order by receivedAt desc, id desc");
    }
}
