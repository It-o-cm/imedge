package com.intermarche.edge.domain.safe;

import com.intermarche.edge.domain.BaseEntity;
import com.intermarche.edge.domain.people.Employee;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A COIN ORDER ({@code BO-05-02-04}) and its delivery ({@code BO-05-02-05}).
 *
 * <p>Two requirements, one object, because they are two moments of the same
 * fact: the shop states the denominations it needs, prints the order to hand to
 * the bank or the cash carrier, and days later records what actually came back
 * against the same row. Splitting them into two entities would leave nothing to
 * recall when the delivery arrives, which is precisely what {@code -05} asks for.
 *
 * <p>Only the DELIVERY moves the safe. An order is a piece of paper; the money
 * enters the safe when it is delivered, and that is the moment the
 * {@link SafeMovementType#COIN_DELIVERY} movement is written.
 */
@Entity
@Table(name = "coin_orders",
        indexes = {
                @Index(name = "idx_coin_order_number", columnList = "order_number", unique = true),
                @Index(name = "idx_coin_order_status", columnList = "status")
        }
)
public class CoinOrder extends BaseEntity {

    /** Where an order stands. */
    public enum OrderStatus {

        /** Entered and printable, nothing received yet. */
        REQUESTED,

        /** The delivery has been recorded against it. */
        RECEIVED,

        /** Abandoned before any delivery. */
        CANCELLED
    }

    /** The order's own number, as the shop hands it to the bank. */
    @Column(name = "order_number", nullable = false, unique = true, length = 30)
    public String orderNumber;

    /** The operator-facing wording of the order. */
    @Column(name = "label", length = 120)
    public String label;

    /** The day the order was entered. */
    @Column(name = "requested_on", nullable = false)
    public LocalDate requestedOn;

    /** Who entered it, null on a row whose author is not known. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by")
    public Employee requestedBy;

    /** Where the order stands. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    public OrderStatus status = OrderStatus.REQUESTED;

    /** When the delivery was recorded, null while nothing came back. */
    @Column(name = "received_at")
    public LocalDateTime receivedAt;

    /** Who recorded the delivery, null while nothing came back. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "received_by")
    public Employee receivedBy;

    /** The movement the delivery wrote into the safe, null while nothing came back. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movement_id")
    public SafeMovement movement;

    /** The denominations ordered, in the order they were entered. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "coin_order_lines", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "line_number")
    public List<CoinOrderLine> lines = new ArrayList<>();

    /**
     * The total amount asked for.
     *
     * @return the sum of the lines' requested amounts, never null
     */
    public BigDecimal requestedAmount() {
        BigDecimal total = BigDecimal.ZERO;
        if (lines == null) {
            return total;
        }
        for (CoinOrderLine line : lines) {
            total = total.add(line.requestedAmount());
        }
        return total;
    }

    /**
     * The total amount that came back.
     *
     * @return the sum of the lines' received amounts, zero while nothing has been
     *         received
     */
    public BigDecimal receivedAmount() {
        BigDecimal total = BigDecimal.ZERO;
        if (lines == null) {
            return total;
        }
        for (CoinOrderLine line : lines) {
            total = total.add(line.receivedAmount());
        }
        return total;
    }

    /**
     * Tells whether the delivery matched the order line for line
     * ({@code BO-05-02-05}).
     *
     * @return true when every line came back in full
     */
    public boolean isFullyFulfilled() {
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        for (CoinOrderLine line : lines) {
            if (!line.isFulfilled()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tells whether a delivery may still be recorded against this order.
     *
     * @return true while the order is waiting for its delivery
     */
    public boolean isAwaitingDelivery() {
        return status == OrderStatus.REQUESTED;
    }

    /**
     * Returns the requested amount for a template, two decimals.
     *
     * @return the amount, two decimals
     */
    public String getRequestedAmountFormatted() {
        return requestedAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Returns the received amount for a template, two decimals.
     *
     * @return the amount, two decimals
     */
    public String getReceivedAmountFormatted() {
        return receivedAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * The orders still waiting for their delivery, oldest first.
     *
     * @return the pending orders
     */
    public static List<CoinOrder> awaitingDelivery() {
        return CoinOrder.<CoinOrder>find("status = ?1 order by requestedOn", OrderStatus.REQUESTED)
                .list();
    }

    /**
     * Every order, newest first.
     *
     * @return the orders, the most recent first
     */
    public static List<CoinOrder> listNewestFirst() {
        return CoinOrder.<CoinOrder>find("order by requestedOn desc, id desc").list();
    }

    /**
     * Returns the audit checksum of the order.
     *
     * @return a hash of the identifying and financial fields
     */
    @Override
    public int getChecksum() {
        return Objects.hash(orderNumber, label, requestedOn, status, receivedAt,
                lines == null ? 0 : lines.size());
    }
}
