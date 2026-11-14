package com.intermarche.edge.domain.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import java.math.BigDecimal;

/**
 * A supplier gift-card payment (EXP-66): a third-party gift title accepted only after its issuer registry has confirmed the card valid and returned an available balance, the payment being capped at that balance. Identity is the discriminator value "GIFTCARD".
 */
@Entity
@DiscriminatorValue("GIFTCARD")
public class SupplierGiftCardPayment extends TicketPayment {

    /**
     * Default constructor for JPA.
     */
    protected SupplierGiftCardPayment() {}

    /**
     * Creates the payment with its applied amount.
     *
     * @param amount the amount applied to the ticket
     */
    public SupplierGiftCardPayment(BigDecimal amount) { super(amount); }

    /**
     * Factory discovered by CDI under the "GIFTCARD" key, used by the draft
     * persistence and the store-node sync ingestion.
     */
    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {

        /**
         * Returns the unique key identifying this payment method.
         *
         * @return the method key "GIFTCARD"
         */
        @Override
        public String getKey() { return "GIFTCARD"; }

        /**
         * Creates the payment entity.
         *
         * @param amount the amount applied to the ticket
         * @param tendered ignored (no change on these tenders)
         * @return the created payment entity
         */
        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new SupplierGiftCardPayment(amount);
        }
    }
}
