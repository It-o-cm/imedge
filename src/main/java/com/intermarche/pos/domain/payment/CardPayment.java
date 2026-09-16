package com.intermarche.pos.domain.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import org.hibernate.annotations.ColumnDefault;
import java.math.BigDecimal;

/**
 * A card (CB) payment.
 * <p>
 * Semantic contract:
 * <ul>
 *   <li>Registered through {@code PaymentService.processCard}. The amount
 *       first waits on the payment terminal ({@code pendingCardAmount},
 *       whatever the {@code pos.tpe.mode} implementation behind the
 *       {@code PaymentTerminalClient} port);
 *       this entity is only created on the terminal's <em>accept</em>
 *       decision — a refuse or a register-side cancel never creates one.</li>
 *   <li>Card payments never open the drawer (no cash handled).</li>
 *   <li>An overpayment by card is not rendered as change: card amounts are
 *       capped by the caller at the remaining due.</li>
 * </ul>
 * Carries the monetique traces of the card transaction: the authorization
 * number and the degraded-mode indicator. Both DESCRIBE THE SALE (what the
 * terminal returned for this ticket), not the register's local state, so —
 * unlike {@code failedAttempts}/{@code lockedUntil}/{@code bo_password} — they
 * ARE carried by the store synchronization (see {@code SyncPayloads.PaymentDto}
 * and the outbox transport). The applied amount and registration order live on
 * {@link TicketPayment}, and the method identity is the JPA discriminator value
 * "CARD" (single-table inheritance): the two card columns are nullable/defaulted
 * on the shared table, so the other payment methods leave them untouched.
 */
@Entity
@DiscriminatorValue("CARD")
public class CardPayment extends TicketPayment {

    /**
     * The monetique authorization number returned for an accepted transaction
     * (BO-04-01-08), or null when none was returned (a card payment accepted in
     * degraded mode reaches no monetique server, so it has no authorization
     * number). Nullable on the shared payment table.
     */
    @Column(name = "card_auth_number", length = 32)
    public String authorizationNumber;

    /**
     * True when the transaction was accepted in degraded mode (BO-04-01-47/49):
     * the manual back-office toggle (BO-03-12-05) routed it to immediate
     * acceptance. The register only records this MANUAL degraded mode; a
     * secondary-monetique-server acceptance (BO-04-01-48) it cannot observe.
     * Defaulted to false on the shared payment table so the other payment
     * methods, which never write this column, read back false.
     */
    @Column(name = "card_degraded_mode")
    @ColumnDefault("false")
    public boolean degradedMode;

    /**
     * Default constructor for JPA.
     */
    protected CardPayment() {}

    /**
     * Creates a card payment with its applied amount.
     *
     * @param amount the amount applied to the ticket
     */
    public CardPayment(BigDecimal amount) { super(amount); }

    /**
     * Factory discovered by CDI under the "CARD" key, used by the draft
     * persistence when saving payment entries and by the store-node sync
     * ingestion when rebuilding pushed tickets.
     */
    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {

        /**
         * Returns the unique key identifying the card payment method.
         *
         * @return the method key "CARD"
         */
        @Override
        public String getKey() { return "CARD"; }

        /**
         * Creates a card payment entity.
         *
         * @param amount the amount applied to the ticket
         * @param tendered ignored (no change on card payments)
         * @return the created payment entity
         */
        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new CardPayment(amount);
        }
    }
}
