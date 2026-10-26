package com.intermarche.edge.domain.sale;

import com.intermarche.edge.domain.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A CUSTOMER ORDER recalled at the till by its code — the single mechanism the
 * store's four onglet-G documents are built on (EXP-90/91/92): the back office
 * enters the order and hands the customer a code, the till reads the code and the
 * lines appear, and the customer pays in full or leaves a deposit (EXP-85).
 *
 * <p>The four documents are one entity distinguished by {@link #kind}: a quote, a
 * firm order, a sale voucher, a click-and-collect basket. Writing them as one
 * avoids three parallel implementations of the same gesture. The deposit rule
 * follows the kind ({@link OrderKind#isDepositAllowed()}); the lifecycle follows
 * {@link OrderStatus}.
 *
 * <p>Held assumption (named as such): the retention depth of a recalled order is
 * the one the base already carries; no dedicated purge is added here.
 */
@Entity
@Table(name = "customer_orders")
public class CustomerOrder extends BaseEntity {

    /** The code handed to the customer, the recall key (unique). */
    @Column(name = "code", unique = true, nullable = false, length = 40)
    public String code;

    /** The kind of document this order is (EXP-90/91/92). */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    public OrderKind kind = OrderKind.COMMANDE;

    /** Where the order stands in its lifecycle. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    public OrderStatus status = OrderStatus.OPEN;

    /** The account-customer number the order is for, or null for a passing customer. */
    @Column(name = "customer_account_number", length = 40)
    public String customerAccountNumber;

    /** The operator-facing label of the order. */
    @Column(name = "label", length = 120)
    public String label;

    /**
     * When the order was taken — its BUSINESS date, not an audit timestamp.
     *
     * <p>It exists because accounting scopes a period on it (BO-11-01-23: the VAT
     * ventilation of the day's orders). The audit {@code createdAt} of
     * {@code BaseEntity} cannot serve: it moves to whichever day the row was last
     * written, so an order corrected a week later would leave the day it was taken
     * on and enter the day it was fixed on — twice wrong, once in each declaration.
     */
    @Column(name = "order_date")
    public LocalDateTime orderDate;

    /** The total amount of the order, in euros. */
    @Column(name = "total_amount", precision = 19, scale = 2)
    public BigDecimal totalAmount = BigDecimal.ZERO;

    /** The amount already settled as a deposit, in euros (EXP-85). */
    @Column(name = "deposit_paid", precision = 19, scale = 2)
    public BigDecimal depositPaid = BigDecimal.ZERO;

    /**
     * The per-rate VAT of the deposit taken on the order at a till, in the
     * compact {@code DepositVat} form ({@code rate:amount;...}), or null when
     * no ventilation was computed (LC-06-03-05): the back office reads it at
     * period end for the suspense accounts by rate, and the recall hands it
     * back so the final ticket restates it (LC-06-03-08). The deposit's MONEY
     * is not here — it travels as the register's {@code CUSTOMER_DEPOSIT}
     * movement, imputed by the order reference.
     */
    @Column(name = "deposit_vat_detail", length = 500)
    public String depositVatDetail;

    /**
     * Whether the till may take a deposit on the document (LC-06-02-04): NONE,
     * OPTIONAL or MANDATORY, as the commercial management tool that issued it
     * states; null reads as none, the rule of every order a till created.
     */
    @Column(name = "deposit_rule", length = 12)
    public String depositRule;

    /**
     * The control a selfscanning device asks for on its basket (LC-06-05-04, -05):
     * NONE, PARTIAL or TOTAL; null reads as none.
     */
    @Column(name = "control_request", length = 8)
    public String controlRequest;

    /**
     * The reduction vouchers a selfscanning device recorded on its basket, applied
     * automatically when the basket is paid at the till (LC-06-05-02).
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "customer_order_vouchers",
            joinColumns = @JoinColumn(name = "order_id"))
    public List<OrderVoucher> vouchers = new ArrayList<>();

    /**
     * The settlements a mobility device already collected on its transaction,
     * restated at the till when the transaction is integrated (BO-10-01-39).
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "customer_order_payments",
            joinColumns = @JoinColumn(name = "order_id"))
    public List<OrderPayment> payments = new ArrayList<>();

    /** The order lines, frozen as administered. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "customer_order_lines",
            joinColumns = @JoinColumn(name = "order_id"))
    public List<OrderLine> lines = new ArrayList<>();

    /**
     * The amount still owed on the order: total minus deposit, never negative.
     *
     * @return the remaining amount in euros
     */
    public BigDecimal remaining() {
        BigDecimal total = totalAmount == null ? BigDecimal.ZERO : totalAmount;
        BigDecimal paid = depositPaid == null ? BigDecimal.ZERO : depositPaid;
        BigDecimal remaining = total.subtract(paid);
        return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
    }

    /**
     * Whether this order can still be recalled and settled — it is open or only
     * partially paid, not already paid, collected or cancelled.
     *
     * @return true when the order is recallable
     */
    public boolean isRecallable() {
        return status == OrderStatus.OPEN || status == OrderStatus.PARTIALLY_PAID;
    }

    /**
     * Finds an order by its recall code.
     *
     * @param code the code handed to the customer, or null
     * @return the order, or null when no row carries that code
     */
    public static CustomerOrder findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return find("code", code.trim()).firstResult();
    }

    /**
     * Returns the deposit's per-rate VAT formatted for the back-office screen
     * ({@code LC-06-03-05}), one entry per rate the deposit was ventilated on:
     * {@code 20,00% : 8,26 E}. Empty when the order carries no detail — a
     * deposit the back office took itself ventilates nothing here.
     *
     * @return the formatted ventilation, or an empty string
     */
    public String getDepositVatDisplay() {
        StringBuilder sb = new StringBuilder();
        for (com.intermarche.edge.domain.payment.DepositVat.Row row
                : com.intermarche.edge.domain.payment.DepositVat.rows(depositVatDetail)) {
            if (sb.length() > 0) {
                sb.append(" ; ");
            }
            sb.append(row.getRateFormatted()).append(" : ").append(row.getAmountFormatted()).append(" E");
        }
        return sb.toString();
    }

    /**
     * Finds the latest order of a kind issued for a customer and still
     * recallable (BO-10-01-18): the self-scanning basket a customer built, named
     * at the till by the customer number rather than by its own code.
     *
     * @param customerNumber the customer number, or null
     * @param kind the kind looked for
     * @return the order, or null when the customer has none recallable
     */
    public static CustomerOrder findLatestForCustomer(String customerNumber, OrderKind kind) {
        if (customerNumber == null || customerNumber.isBlank()) {
            return null;
        }
        return find("customerAccountNumber = ?1 and kind = ?2 and status in ?3 order by id desc",
                customerNumber.trim(), kind, List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_PAID)).firstResult();
    }

    /**
     * Lists every order, most recent first, for the back-office screen.
     *
     * @return the orders, never null
     */
    public static List<CustomerOrder> listAllOrdered() {
        return list("order by id desc");
    }

    /**
     * Hashes the salient business fields for change detection.
     *
     * @return the checksum of the order
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, kind, status, customerAccountNumber, label,
                totalAmount, depositPaid, depositVatDetail);
    }

    /**
     * A reduction voucher recorded on a selfscanning basket (LC-06-05-02).
     */
    @Embeddable
    public static class OrderVoucher {

        /** The voucher's code, or null for a numberless voucher. */
        @Column(name = "voucher_code", length = 64)
        public String code;

        /** The voucher's label. */
        @Column(name = "voucher_label", length = 100)
        public String label;

        /** The voucher's amount, TTC. */
        @Column(name = "voucher_amount", precision = 19, scale = 2)
        public BigDecimal amount;

        /**
         * Builds an empty voucher (JPA).
         */
        public OrderVoucher() {
        }

        /**
         * Builds a voucher.
         *
         * @param code the voucher's code, or null
         * @param label the voucher's label
         * @param amount the voucher's amount, TTC
         */
        public OrderVoucher(String code, String label, BigDecimal amount) {
            this.code = code;
            this.label = label;
            this.amount = amount;
        }
    }

    /**
     * A settlement a mobility device collected on its transaction (BO-10-01-39).
     */
    @Embeddable
    public static class OrderPayment {

        /** The settlement key the device used (CASH, CARD...), or null. */
        @Column(name = "payment_tender", length = 20)
        public String tender;

        /** The amount paid, TTC. */
        @Column(name = "payment_amount", precision = 19, scale = 2)
        public BigDecimal amount;

        /**
         * Builds an empty settlement (JPA).
         */
        public OrderPayment() {
        }

        /**
         * Builds a settlement.
         *
         * @param tender the settlement key, or null
         * @param amount the amount paid, TTC
         */
        public OrderPayment(String tender, BigDecimal amount) {
            this.tender = tender;
            this.amount = amount;
        }
    }

    /**
     * One line of a customer order, frozen as administered.
     */
    @Embeddable
    public static class OrderLine {

        /** The article EAN, or null for a free line. */
        @Column(name = "ean", length = 20)
        public String ean;

        /** The line label. */
        @Column(name = "label", length = 120)
        public String label;

        /** The quantity ordered. */
        @Column(name = "quantity", precision = 12, scale = 3)
        public BigDecimal quantity;

        /** The unit price, in euros — the shape impos's RecalledDocument.Line expects. */
        @Column(name = "unit_price", precision = 19, scale = 2)
        public BigDecimal unitPrice;

        /** The VAT rate of the line. */
        @Column(name = "vat_rate", precision = 6, scale = 4)
        public BigDecimal vatRate;

        /**
         * The quantity delivered, when the document states it beside the ordered
         * one (LC-06-02-02, -12); null otherwise.
         */
        @Column(name = "delivered_quantity", precision = 12, scale = 3)
        public BigDecimal deliveredQuantity;

        /** The line total, in euros — kept for the back-office display. */
        @Column(name = "total_price", precision = 19, scale = 2)
        public BigDecimal totalPrice;

        /**
         * Builds an empty line (JPA).
         */
        public OrderLine() {
        }

        /**
         * Builds an order line, carrying the unit price and VAT rate the register's
         * recalled-document model expects, plus the line total for display.
         *
         * @param ean the article EAN, or null
         * @param label the line label
         * @param quantity the quantity
         * @param unitPrice the unit price
         * @param vatRate the VAT rate
         * @param totalPrice the line total
         */
        public OrderLine(String ean, String label, BigDecimal quantity, BigDecimal unitPrice,
                         BigDecimal vatRate, BigDecimal totalPrice) {
            this.ean = ean;
            this.label = label;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.vatRate = vatRate;
            this.totalPrice = totalPrice;
        }
    }
}
