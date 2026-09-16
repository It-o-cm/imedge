package com.intermarche.pos.domain.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;

/**
 * A meal-voucher (titre-restaurant) payment.
 * <p>
 * Semantic contract: the drawer opens at registration so the cashier can
 * store the paper titles. The legal cap on eligible products is NOT enforced
 * yet — {@code PaymentService.processTicketResto} accepts any amount up to
 * the remaining due; phase 7 makes the valuation engine's MEAL_VOUCHER
 * behavior authoritative for the eligible ceiling per ticket. Identity is
 * the discriminator value "TR".
 */
@Entity
@DiscriminatorValue("TR")
public class TicketRestoPayment extends TicketPayment {

    /**
     * Default constructor for JPA.
     */
    protected TicketRestoPayment() {}

    /**
     * Creates a meal-voucher payment with its applied amount.
     *
     * @param amount the amount applied to the ticket
     */
    public TicketRestoPayment(BigDecimal amount) { super(amount); }

    /**
     * Factory discovered by CDI under the "TR" key, used by the draft
     * persistence and the store-node sync ingestion.
     */
    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {

        /**
         * Returns the unique key identifying the meal-voucher payment method.
         *
         * @return the method key "TR"
         */
        @Override
        public String getKey() { return "TR"; }

        /**
         * Creates a meal-voucher payment entity.
         *
         * @param amount the amount applied to the ticket
         * @param tendered ignored (no change on meal vouchers by store rule)
         * @return the created payment entity
         */
        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new TicketRestoPayment(amount);
        }
    }
}
