package com.intermarche.edge.domain.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import java.math.BigDecimal;

/**
 * A dematerialized meal-voucher (carte TRD) payment (EXP-60).
 * <p>
 * Semantic contract: like the paper meal voucher {@link TicketRestoPayment} it
 * is bound to the ticket's MEAL-VOUCHER ELIGIBLE base — a TRD card settles at
 * most what the eligible lines are worth — but unlike the paper title it also
 * carries a legal per-transaction CAP (plafond), administered at the point of
 * vente. Without its own discriminator it would record as a plain "CARD" with
 * neither base nor cap, which is precisely the gap this closes. Identity is the
 * discriminator value "TRD".
 */
@Entity
@DiscriminatorValue("TRD")
public class TrdCardPayment extends TicketPayment {

    /**
     * Default constructor for JPA.
     */
    protected TrdCardPayment() {}

    /**
     * Creates a TRD-card payment with its applied amount.
     *
     * @param amount the amount applied to the ticket
     */
    public TrdCardPayment(BigDecimal amount) { super(amount); }

    /**
     * Factory discovered by CDI under the "TRD" key, used by the draft
     * persistence and the store-node sync ingestion.
     */
    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {

        /**
         * Returns the unique key identifying the TRD-card payment method.
         *
         * @return the method key "TRD"
         */
        @Override
        public String getKey() { return "TRD"; }

        /**
         * Creates a TRD-card payment entity.
         *
         * @param amount the amount applied to the ticket
         * @param tendered ignored (no change on meal vouchers by store rule)
         * @return the created payment entity
         */
        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new TrdCardPayment(amount);
        }
    }
}
