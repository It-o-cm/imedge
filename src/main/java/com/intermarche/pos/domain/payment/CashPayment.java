package com.intermarche.pos.domain.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * A cash payment.
 * <p>
 * Semantic contract:
 * <ul>
 *   <li>Two amounts coexist: {@link TicketPayment#amount} is what the ticket
 *       receives (capped at the remaining due), {@link #tenderedAmount} is
 *       what the customer physically handed over. The change is their
 *       difference, computed by {@code PaymentService} and remembered as
 *       {@code lastChangeAmount} for the completion modal and the customer
 *       display.</li>
 *   <li>Registration opens the drawer — except in training mode, where the
 *       drawer stays shut (phase 6).</li>
 *   <li>Cash refunds are NOT negative cash payments: they are
 *       {@link Refund} documents whose cash method lowers the session's
 *       theoretical drawer amount.</li>
 * </ul>
 * Identity is the discriminator value "CASH" (single-table inheritance).
 */
@Entity
@DiscriminatorValue("CASH")
public class CashPayment extends TicketPayment {

    /**
     * The amount physically handed over by the customer; the change rendered
     * is {@code tenderedAmount - amount}. Defaults to the applied amount when
     * the exact sum was given.
     */
    @Column(name = "tendered_amount", precision = 19, scale = 4)
    public BigDecimal tenderedAmount;

    /**
     * Default constructor for JPA.
     */
    protected CashPayment() {}

    /**
     * Creates a cash payment with its applied and tendered amounts.
     *
     * @param amount the amount applied to the ticket
     * @param tenderedAmount the amount physically handed over
     */
    public CashPayment(BigDecimal amount, BigDecimal tenderedAmount) {
        super(amount);
        this.tenderedAmount = tenderedAmount;
    }

    /**
     * Returns the audit checksum, extending the base with the tendered
     * amount (a tampered change would otherwise be invisible).
     *
     * @return a hash of the base checksum and the tendered amount
     */
    @Override
    public int getChecksum() {
        return Objects.hash(super.getChecksum(), tenderedAmount);
    }

    // --- Factory Interne ---

    /**
     * Factory discovered by CDI under the "CASH" key, used by the draft
     * persistence and the store-node sync ingestion.
     */
    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {

        /**
         * Returns the unique key identifying the cash payment method.
         *
         * @return the method key "CASH"
         */
        @Override
        public String getKey() {
            return "CASH";
        }

        /**
         * Creates a cash payment entity; a null tendered amount means the
         * exact sum was given.
         *
         * @param amount the amount applied to the ticket
         * @param tendered the tendered amount, or null for the exact sum
         * @return the created payment entity
         */
        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new CashPayment(amount, tendered != null ? tendered : amount);
        }
    }
}
