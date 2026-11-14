package com.intermarche.edge.domain.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import java.math.BigDecimal;

/**
 * A Resto Flash mobile meal-voucher payment (EXP-67): a titre-restaurant settled from the customer's phone, bound to the ticket's MEAL-VOUCHER ELIGIBLE base like the paper and card meal vouchers, confirmed through the Resto Flash mobile client. Identity is the discriminator value "RESTOFLASH".
 */
@Entity
@DiscriminatorValue("RESTOFLASH")
public class RestoFlashPayment extends TicketPayment {

    /**
     * Default constructor for JPA.
     */
    protected RestoFlashPayment() {}

    /**
     * Creates the payment with its applied amount.
     *
     * @param amount the amount applied to the ticket
     */
    public RestoFlashPayment(BigDecimal amount) { super(amount); }

    /**
     * Factory discovered by CDI under the "RESTOFLASH" key, used by the draft
     * persistence and the store-node sync ingestion.
     */
    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {

        /**
         * Returns the unique key identifying this payment method.
         *
         * @return the method key "RESTOFLASH"
         */
        @Override
        public String getKey() { return "RESTOFLASH"; }

        /**
         * Creates the payment entity.
         *
         * @param amount the amount applied to the ticket
         * @param tendered ignored (no change on these tenders)
         * @return the created payment entity
         */
        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new RestoFlashPayment(amount);
        }
    }
}
