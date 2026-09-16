package com.intermarche.pos.domain.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;

/**
 * A bank-cheque payment.
 * <p>
 * Semantic contract: the drawer opens at registration so the cashier can
 * store the cheque; the amount is taken as typed (a cheque above the
 * remaining due is a cashier decision, change is rendered in cash and
 * computed by the shared change logic of {@code PaymentService}).
 * <p>
 * Carries the magnetic line the reader read off the cheque. Like the card traces,
 * it DESCRIBES THE SALE — which cheque settled this ticket — and not the register's
 * local state, so it belongs on the entity and travels with the store synchronization.
 * It is kept RAW, exactly as read: the bank, branch and account codes are derived from
 * it and can be derived again, whereas a line stored already split could never be
 * re-split differently the day a layout turns out to be wrong. Nullable on the shared
 * payment table: a cheque registered in training mode, or before this till had a
 * reader, has none.
 */
@Entity
@DiscriminatorValue("CHEQUE")
public class ChequePayment extends TicketPayment {

    /**
     * The CMC7 magnetic line as the reader read it, or null when the cheque was not
     * read — training mode, or a till with no reader. Nullable on the shared payment
     * table, so the other payment methods leave the column untouched.
     */
    @Column(name = "cheque_micr_line", length = 64)
    public String magneticLine;

    /**
     * Default constructor for JPA.
     */
    protected ChequePayment() {}

    /**
     * Creates a cheque payment with its applied amount.
     *
     * @param amount the amount applied to the ticket
     */
    public ChequePayment(BigDecimal amount) { super(amount); }

    /**
     * Factory discovered by CDI under the "CHEQUE" key, used by the draft
     * persistence and the store-node sync ingestion.
     */
    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {

        /**
         * Returns the unique key identifying the cheque payment method.
         *
         * @return the method key "CHEQUE"
         */
        @Override
        public String getKey() { return "CHEQUE"; }

        /**
         * Creates a cheque payment entity.
         *
         * @param amount the amount applied to the ticket
         * @param tendered ignored (the applied amount is the cheque amount)
         * @return the created payment entity
         */
        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new ChequePayment(amount);
        }
    }
}
