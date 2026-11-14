package com.intermarche.edge.domain.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import java.math.BigDecimal;

/**
 * A SOCIAL PURCHASE CARD payment — the cartão de compras social (LC-08-01-06).
 * <p>
 * Semantic contract: like the meal vouchers it is bound to an ELIGIBLE BASE, the
 * lines whose article carries the social-card eligibility; a social card settles
 * at most what those lines are worth. Where the register issues the Portuguese
 * fiscal documents, the eligible lines it paid are stated on a simplified
 * invoice of their own. Identity is the discriminator value "CAS", the code the
 * restricted-tender list already gives the card.
 */
@Entity
@DiscriminatorValue("CAS")
public class SocialCardPayment extends TicketPayment {

    /**
     * Default constructor for JPA.
     */
    protected SocialCardPayment() {}

    /**
     * Creates a social-card payment with its applied amount.
     *
     * @param amount the amount applied to the ticket
     */
    public SocialCardPayment(BigDecimal amount) { super(amount); }

    /**
     * Factory discovered by CDI under the "CAS" key, used by the draft
     * persistence and the store-node sync ingestion.
     */
    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {

        /**
         * Returns the unique key identifying the social-card payment method.
         *
         * @return the method key "CAS"
         */
        @Override
        public String getKey() { return "CAS"; }

        /**
         * Creates a social-card payment entity.
         *
         * @param amount the amount applied to the ticket
         * @param tendered ignored (a card gives no change)
         * @return the created payment entity
         */
        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new SocialCardPayment(amount);
        }
    }
}
