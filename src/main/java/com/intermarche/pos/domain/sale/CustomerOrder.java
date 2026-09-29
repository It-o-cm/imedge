package com.intermarche.pos.domain.sale;

import com.intermarche.pos.domain.BaseEntity;
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
    public OrderKind kind = OrderKind.ORDER;

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

    /** The total amount of the order, in euros. */
    @Column(name = "total_amount", precision = 19, scale = 2)
    public BigDecimal totalAmount = BigDecimal.ZERO;

    /** The amount already settled as a deposit, in euros (EXP-85). */
    @Column(name = "deposit_paid", precision = 19, scale = 2)
    public BigDecimal depositPaid = BigDecimal.ZERO;

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
        return Objects.hash(code, kind, status, customerAccountNumber, label, totalAmount, depositPaid);
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

        /** The line total, in euros. */
        @Column(name = "total_price", precision = 19, scale = 2)
        public BigDecimal totalPrice;

        /**
         * Builds an empty line (JPA).
         */
        public OrderLine() {
        }

        /**
         * Builds an order line.
         *
         * @param ean the article EAN, or null
         * @param label the line label
         * @param quantity the quantity
         * @param totalPrice the line total
         */
        public OrderLine(String ean, String label, BigDecimal quantity, BigDecimal totalPrice) {
            this.ean = ean;
            this.label = label;
            this.quantity = quantity;
            this.totalPrice = totalPrice;
        }
    }
}
