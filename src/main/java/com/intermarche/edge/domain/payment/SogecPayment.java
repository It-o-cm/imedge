package com.intermarche.edge.domain.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import java.math.BigDecimal;

/**
 * A SOGEC voucher payment (EXP-58): a manufacturer coupon settled through the distinct SOGEC compensation circuit, separate from the generic GS1 coupon path, so the supplier reimbursement is traced on its own. Identity is the discriminator value "SOGEC".
 */
@Entity
@DiscriminatorValue("SOGEC")
public class SogecPayment extends TicketPayment {

    /**
     * Default constructor for JPA.
     */
    protected SogecPayment() {}

    /**
     * Creates the payment with its applied amount.
     *
     * @param amount the amount applied to the ticket
     */
    public SogecPayment(BigDecimal amount) { super(amount); }

    /**
     * Factory discovered by CDI under the "SOGEC" key, used by the draft
     * persistence and the store-node sync ingestion.
     */
    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {

        /**
         * Returns the unique key identifying this payment method.
         *
         * @return the method key "SOGEC"
         */
        @Override
        public String getKey() { return "SOGEC"; }

        /**
         * Creates the payment entity.
         *
         * @param amount the amount applied to the ticket
         * @param tendered ignored (no change on these tenders)
         * @return the created payment entity
         */
        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new SogecPayment(amount);
        }
    }
}
