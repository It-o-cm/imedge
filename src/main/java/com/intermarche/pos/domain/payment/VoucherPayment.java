package com.intermarche.pos.domain.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import java.math.BigDecimal;

/**
 * A payment made with a voucher (gift voucher, loyalty cheque, Catalina,
 * generic coupon).
 * <p>
 * Semantic contract: only coupon types NOT flagged {@code depositLine} can
 * pay (deposit-return vouchers become negative ticket lines at scan time,
 * never payments). A scan is treated as a payment voucher only while a
 * payment is in progress — the explicit {@code paymentInProgress} flag, not
 * the draft id, which exists from the first article. Store vouchers printed
 * by voucher refunds close the loop: their number matches the STORE_VOUCHER
 * pattern and comes back through this entity when scanned on a later ticket.
 * {@link #voucherLabel} and {@link #voucherNumber} travel in the sync
 * payloads so the store node rebuilds the exact voucher identity.
 */
@Entity
@DiscriminatorValue("VOUCHER")
public class VoucherPayment extends TicketPayment {

    /**
     * The label of the voucher type as shown to the cashier (for example, "Chèque cadeau").
     */
    @Column(name = "voucher_label", length = 100)
    public String voucherLabel;

    /**
     * The voucher number, or null for a numberless voucher.
     */
    @Column(name = "voucher_number", length = 64)
    public String voucherNumber;

    /**
     * Default constructor required by JPA.
     */
    protected VoucherPayment() {}

    /**
     * Creates a voucher payment with its amount, type label and number.
     *
     * @param amount the paid amount
     * @param voucherLabel the voucher type label
     * @param voucherNumber the voucher number, or null when there is none
     */
    public VoucherPayment(BigDecimal amount, String voucherLabel, String voucherNumber) {
        super(amount);
        this.voucherLabel = voucherLabel;
        this.voucherNumber = voucherNumber;
    }

    /**
     * Factory creating {@link VoucherPayment} instances for the persistence layer.
     */
    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {

        /**
         * Returns the unique key identifying the voucher payment method.
         *
         * @return the method key "VOUCHER"
         */
        @Override
        public String getKey() {
            return "VOUCHER";
        }

        /**
         * Creates a voucher payment entity from an amount.
         *
         * @param amount the paid amount
         * @param tendered the tendered amount (unused for vouchers)
         * @return the created payment entity
         */
        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new VoucherPayment(amount, null, null);
        }
    }
}
