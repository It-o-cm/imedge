package com.intermarche.edge.domain.sale;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * An EXPORT SALES SLIP (« bordereau de vente à l'exportation », BO-06-07-03):
 * the document the welcome desk prints from a customer's receipt so a customer
 * living abroad — a Swiss customer of a border shop, a fishing boat of the
 * West — gets the VAT of the purchase back once the customs stamp the slip.
 *
 * <p>Built from the TICKET: the slip states, rate by rate, the amounts sold
 * and their VAT, and the amount to refund is the VAT of the ticket. Its life
 * has two steps — ISSUED when printed, REFUNDED when the customer brings it
 * back stamped and the desk pays the VAT out — and the refund follow-up state
 * reads both.
 */
@Entity
@Table(name = "tax_refund_forms",
        indexes = @Index(name = "idx_tax_refund_created", columnList = "created_at"))
public class TaxRefundForm extends PanacheEntity {

    /** The slip is printed and awaits the customs stamp. */
    public static final String ISSUED = "ISSUED";

    /** The stamped slip came back and the VAT was paid out. */
    public static final String REFUNDED = "REFUNDED";

    /** The slip's number. */
    @Column(name = "number", nullable = false, unique = true, length = 30)
    public String number;

    /** The ticket the slip states. */
    @Column(name = "ticket_number", nullable = false, length = 30)
    public String ticketNumber;

    /** The customer's name. */
    @Column(name = "customer_name", nullable = false, length = 120)
    public String customerName;

    /** The customer's country, or the boat's registration. */
    @Column(name = "customer_country", length = 60)
    public String customerCountry;

    /** The customer's identity document number. */
    @Column(name = "customer_document", length = 60)
    public String customerDocument;

    /** When the slip was printed. */
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    /** Who printed it. */
    @Column(name = "created_by", length = 60)
    public String createdBy;

    /** The amount sold at 2,10 %, VAT included. */
    @Column(name = "sales_2_10", precision = 19, scale = 2, nullable = false)
    public BigDecimal sales210 = BigDecimal.ZERO;

    /** Its VAT. */
    @Column(name = "vat_2_10", precision = 19, scale = 2, nullable = false)
    public BigDecimal vat210 = BigDecimal.ZERO;

    /** The amount sold at 5,5 %, VAT included. */
    @Column(name = "sales_5_5", precision = 19, scale = 2, nullable = false)
    public BigDecimal sales55 = BigDecimal.ZERO;

    /** Its VAT. */
    @Column(name = "vat_5_5", precision = 19, scale = 2, nullable = false)
    public BigDecimal vat55 = BigDecimal.ZERO;

    /** The amount sold at 10 %, VAT included. */
    @Column(name = "sales_10", precision = 19, scale = 2, nullable = false)
    public BigDecimal sales10 = BigDecimal.ZERO;

    /** Its VAT. */
    @Column(name = "vat_10", precision = 19, scale = 2, nullable = false)
    public BigDecimal vat10 = BigDecimal.ZERO;

    /** The amount sold at 20 %, VAT included. */
    @Column(name = "sales_20", precision = 19, scale = 2, nullable = false)
    public BigDecimal sales20 = BigDecimal.ZERO;

    /** Its VAT. */
    @Column(name = "vat_20", precision = 19, scale = 2, nullable = false)
    public BigDecimal vat20 = BigDecimal.ZERO;

    /** The amount to refund: the VAT of the ticket. */
    @Column(name = "refund_amount", precision = 19, scale = 2, nullable = false)
    public BigDecimal refundAmount = BigDecimal.ZERO;

    /** {@link #ISSUED} or {@link #REFUNDED}. */
    @Column(name = "status", nullable = false, length = 10)
    public String status = ISSUED;

    /** When the VAT was paid out, or null. */
    @Column(name = "refunded_at")
    public LocalDateTime refundedAt;

    /** Who paid it out, or null. */
    @Column(name = "refunded_by", length = 60)
    public String refundedBy;

    /**
     * Renders the amount to refund for the screen: two decimals, a decimal
     * comma and the euro sign, as the three languages of the back office write it.
     *
     * @return the amount, for example {@code 12,34 €}
     */
    public String getRefundFormatted() {
        java.text.DecimalFormatSymbols symbols = java.text.DecimalFormatSymbols.getInstance(java.util.Locale.FRANCE);
        return new java.text.DecimalFormat("#,##0.00", symbols).format(refundAmount) + " €";
    }

    /**
     * Finds a slip by its number.
     *
     * @param number the number, possibly null
     * @return the slip, or null
     */
    public static TaxRefundForm findByNumber(String number) {
        return number == null ? null : find("number", number.trim()).firstResult();
    }

    /**
     * Lists the latest slips, newest first.
     *
     * @param limit how many
     * @return the slips
     */
    public static List<TaxRefundForm> latest(int limit) {
        return find("order by createdAt desc").page(0, Math.max(1, limit)).list();
    }
}
