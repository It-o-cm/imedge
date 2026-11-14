package com.intermarche.edge.domain.sale;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * An ORDER OF THE E-COMMERCE PLATFORM as the store node received it
 * (BO-10-01-03, -11): the validated order, its integration report and, where the
 * store archives them, the order exactly as the platform sent it, kept for the
 * support's analysis and purged by the end-of-period sweep after the
 * administered number of days.
 *
 * <p>The order itself is NOT the transaction: an integrated order is booked as
 * an ordinary {@link Ticket} of the e-commerce virtual register, named by
 * {@link #ticketNumber}, so it counts in the revenue, the journal and the
 * outbound flows like any sale. This row is the trace of the integration — the
 * reference the platform knows, what became of the order and why.
 */
@Entity
@Table(name = "ecommerce_orders")
public class EcommerceOrder extends PanacheEntity {

    /** What became of the order. */
    public enum Status {

        /** Booked as a transaction of the store. */
        INTEGRATED,

        /** Refused: nothing was booked; the report says why. */
        REJECTED,

        /**
         * Paid short by the platform: kept as an e-commerce basket the till
         * recalls by the reference, the platform's payments restated and the
         * remainder collected at the till (DG-03-01-05).
         */
        PENDING_PAYMENT,

        /**
         * Paid short by the platform, then settled at the till: the ticket that
         * settled it is named (DG-03-01-05).
         */
        SETTLED
    }

    /** The order reference of the e-commerce platform, unique. */
    @Column(name = "reference", unique = true, nullable = false, length = 40)
    public String reference;

    /** When the store node received the order. */
    @Column(name = "received_at", nullable = false)
    public LocalDateTime receivedAt;

    /** What became of the order. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    public Status status;

    /** The number of the ticket the order was booked as, or null when rejected. */
    @Column(name = "ticket_number", length = 30)
    public String ticketNumber;

    /** The total booked, TTC, or null when rejected. */
    @Column(name = "total_amount", precision = 19, scale = 2)
    public BigDecimal totalAmount;

    /** How many lines carried an article the referential does not know (BO-10-01-06). */
    @Column(name = "unknown_lines", nullable = false)
    public int unknownLines;

    /** How many lines carried an article forbidden to sale (BO-10-01-14). */
    @Column(name = "forbidden_lines", nullable = false)
    public int forbiddenLines;

    /** The operator-facing outcome, in French. */
    @Column(name = "message", length = 255)
    public String message;

    /** The integration report returned to the platform, as JSON (BO-10-01-03). */
    @Lob
    @Column(name = "report")
    public String report;

    /** The order as the platform sent it, or null when the store archives none (BO-10-01-11). */
    @Lob
    @Column(name = "original_payload")
    public String originalPayload;

    /** The channel the order was integrated under (BO-07-01-01), null on an older row. */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 16)
    public SalesChannel channel;

    /** The code of the third-party system that deposited it, or null (BO-07-01-01). */
    @Column(name = "system_code", length = 40)
    public String systemCode;

    /** The point of sale of origin the order counts for, or null for this node's (BO-07-01-02). */
    @Column(name = "pdv_number", length = 20)
    public String pdvNumber;

    /** The customer's NIF the transaction states, the default one when none was sent (BO-07-01-07, -08). */
    @Column(name = "customer_tax_id", length = 30)
    public String customerTaxId;

    /** Whether the loyalty earn of the order was queued for the programme (BO-07-01-03). */
    @Column(name = "fidelity_queued", nullable = false)
    public boolean fidelityQueued;

    /** The Portuguese fiscal document stating the order, or null (BO-07-01-06). */
    @Column(name = "fiscal_document", length = 60)
    public String fiscalDocument;

    /**
     * Finds an order by its platform reference.
     *
     * @param reference the reference, or null
     * @return the order, or null when the node never received it
     */
    public static EcommerceOrder findByReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        return find("reference", reference.trim()).firstResult();
    }

    /**
     * Lists every order received, most recent first, for the back-office screen.
     *
     * @return the orders, never null
     */
    public static List<EcommerceOrder> listAllOrdered() {
        return list("order by receivedAt desc, id desc");
    }

    /**
     * Whether the order was booked as a transaction.
     *
     * @return true when integrated
     */
    public boolean isIntegrated() {
        return status == Status.INTEGRATED;
    }

    /**
     * Whether the order, paid short by the platform, was settled at the till
     * (DG-03-01-05).
     *
     * @return true when settled at the till
     */
    public boolean isSettled() {
        return status == Status.SETTLED;
    }

    /**
     * Whether the order waits for its remainder at the till (DG-03-01-05).
     *
     * @return true when paid short and kept as a recallable basket
     */
    public boolean isPendingPayment() {
        return status == Status.PENDING_PAYMENT;
    }
}
