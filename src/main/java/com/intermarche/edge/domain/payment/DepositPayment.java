package com.intermarche.edge.domain.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The recovery of a down-payment already collected on an order — the
 * "Reprise d'acompte" settlement of {@code LC-06-03-07}. When a customer
 * comes back to settle the balance of an order they left a deposit on, the
 * deposit is credited automatically as a payment of its own, so the client
 * only hands over what is left.
 *
 * <p>The money MOVED when the deposit was taken — a {@code CUSTOMER_DEPOSIT}
 * cash movement carrying the order reference — so this row is not new money:
 * it is the named transfer of an amount the store already holds, and the
 * ticket's tenders sum to the order total while the drawer only ever saw the
 * deposit once.
 *
 * <p>THE VAT OF THE DEPOSIT TRAVELS WITH THE LINE ({@code LC-06-03-05/-08}).
 * The deposit's VAT was ventilated per rate at collection time and printed
 * on the deposit slip; the final receipt must restate exactly those figures —
 * "la TVA déjà perçue sur l'acompte" — and not a recomputation on a basket
 * the customer may have edited since. The ventilation rides as the compact
 * {@link DepositVat} detail string, and the order code names the order the
 * deposit came from so the store node can account the recovery against it
 * (comptes d'attente par taux de TVA).
 */
@Entity
@DiscriminatorValue("ACOMPTE")
public class DepositPayment extends TicketPayment {

    /**
     * The per-rate VAT of the deposit this line recovers, in the compact
     * {@link DepositVat} detail form ({@code rate:amount;rate:amount}), or
     * null when the recalled document carried none.
     */
    @Column(name = "vat_detail", length = 500)
    public String vatDetail;

    /**
     * The code of the order the recovered deposit was taken on, or null.
     */
    @Column(name = "order_code", length = 40)
    public String orderCode;

    /**
     * Default constructor for JPA.
     */
    protected DepositPayment() {}

    /**
     * Creates a deposit-recovery payment with its applied amount.
     *
     * @param amount the amount applied to the ticket
     */
    public DepositPayment(BigDecimal amount) { super(amount); }

    /**
     * Returns the audit checksum of the payment, including the deposit's VAT
     * detail: two recoveries of the same amount for two orders are not the
     * same settlement, and a change of either figure is a change of the row.
     *
     * @return a hash of the index, amount, VAT detail and order code
     */
    @Override
    public int getChecksum() {
        return Objects.hash(paymentIndex, amount, vatDetail, orderCode);
    }

    /**
     * Factory discovered by CDI under the "ACOMPTE" key, used by the draft
     * persistence and the store-node sync ingestion.
     */
    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {

        /**
         * Returns the unique key identifying the deposit-recovery method.
         *
         * @return the method key "ACOMPTE"
         */
        @Override
        public String getKey() { return "ACOMPTE"; }

        /**
         * Creates a deposit-recovery payment entity.
         *
         * @param amount the amount applied to the ticket
         * @param tendered ignored (nothing is handed over on a recovery)
         * @return the created payment entity
         */
        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new DepositPayment(amount);
        }
    }
}
