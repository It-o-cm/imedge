package com.intermarche.pos.domain.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;

/**
 * A payment taken on the customer's loyalty balance (cagnotte).
 * <p>
 * Semantic contract: only offered when a fidelity card is active on the
 * ticket (the CAGNOTTE button is disabled otherwise). Until phase 7 wires
 * the valuation engine, the balance is not real — amounts are accepted as
 * typed and the EARN flow does not credit anything back; the engine
 * integration will make the balance authoritative on both sides (payments
 * here, credits from refunds and earn behaviors). Identity is the
 * discriminator value "FIDELITY".
 */
@Entity
@DiscriminatorValue("FIDELITY")
public class FidelityPayment extends TicketPayment {

    /**
     * Default constructor for JPA.
     */
    protected FidelityPayment() {}

    /**
     * Creates a loyalty payment with its applied amount.
     *
     * @param amount the amount applied to the ticket
     */
    public FidelityPayment(BigDecimal amount) { super(amount); }

    /**
     * Factory discovered by CDI under the "FIDELITY" key, used by the draft
     * persistence and the store-node sync ingestion.
     */
    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {

        /**
         * Returns the unique key identifying the loyalty payment method.
         *
         * @return the method key "FIDELITY"
         */
        @Override
        public String getKey() { return "FIDELITY"; }

        /**
         * Creates a loyalty payment entity.
         *
         * @param amount the amount applied to the ticket
         * @param tendered ignored (no change on loyalty payments)
         * @return the created payment entity
         */
        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new FidelityPayment(amount);
        }
    }
}
