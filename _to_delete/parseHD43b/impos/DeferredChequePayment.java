package com.intermarche.pos.domain.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;

/** A manual cheque kept separate for deferred bank deposit (LC-07-05-05). */
@Entity
@DiscriminatorValue("CHEQUE_DEFERRED")
public class DeferredChequePayment extends ChequePayment {

    /** Creates the entity for JPA (LC-07-05-05). */
    protected DeferredChequePayment() {
    }

    /**
     * Creates a deferred manual cheque without contacting monetics (LC-07-05-05).
     * @param amount the applied amount
     */
    public DeferredChequePayment(BigDecimal amount) {
        super(amount);
    }

    /** CDI factory preserving the accounting tender identity (LC-07-05-05). */
    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {

        /** Creates the CDI factory (LC-07-05-05). */
        public Factory() {
        }

        /** @return the distinct deferred-cheque tender key (LC-07-05-05) */
        @Override
        public String getKey() {
            return "CHEQUE_DEFERRED";
        }

        /**
         * Creates the accounting entry (LC-07-05-05).
         * @param amount the applied amount
         * @param tendered unused for a cheque
         * @return the deferred cheque
         */
        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new DeferredChequePayment(amount);
        }
    }
}
