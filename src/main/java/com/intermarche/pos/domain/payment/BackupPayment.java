package com.intermarche.pos.domain.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;

/**
 * A settlement taken on a MOBILE terminal while the integrated monetics is down
 * ({@code LC-07-07-06} to {@code -09}).
 *
 * <p>When the shop's own monetics fails, the card is taken on a handheld running
 * another provider entirely. That terminal knows nothing about this sale, and this
 * register knows nothing about that terminal: the two meet through two QR codes,
 * one carrying the request out and one carrying the outcome back. So the settlement
 * is real money, taken by a system the register never spoke to.
 *
 * <p>Which is exactly why it is its own kind of payment rather than a card payment.
 * A {@code CardPayment} states that the shop's monetics authorized the sale; this
 * one states that somebody ELSE did, and the shop reconciling its day needs to be
 * able to tell those two apart without reading a comment.
 *
 * <p>{@link #manual} is the fact that matters most for that reconciliation: a
 * settlement validated by scanning the terminal's answer carries a verified
 * signature, whereas one keyed in by hand carries an operator's word
 * ({@code LC-07-07-09}). Both are legitimate; they are not the same evidence.
 */
@Entity
@DiscriminatorValue("SECOURS")
public class BackupPayment extends TicketPayment {

    /** The scheme the mobile terminal reported (CB EMV, AMERICAN EXPRESS, TRD…). */
    @Column(name = "backup_method_label", length = 40)
    public String methodLabel;

    /** The transaction number the two QR codes were matched on. */
    @Column(name = "backup_transaction", length = 40)
    public String transactionNumber;

    /**
     * True when the operator keyed the outcome in instead of scanning it, so no
     * signature was verified ({@code LC-07-07-09}).
     */
    @Column(name = "backup_manual")
    public boolean manual = false;

    /**
     * Default constructor for JPA.
     */
    protected BackupPayment() {}

    /**
     * Creates a backup-monetics settlement with its accepted amount.
     *
     * @param amount the amount the mobile terminal accepted
     */
    public BackupPayment(BigDecimal amount) { super(amount); }

    /**
     * Returns the audit checksum, folding in the scheme, the transaction and how
     * the outcome was obtained.
     *
     * @return a hash of the index, amount, scheme, transaction and manual flag
     */
    @Override
    public int getChecksum() {
        return java.util.Objects.hash(super.getChecksum(), methodLabel, transactionNumber, manual);
    }

    /**
     * Factory discovered by CDI under the "SECOURS" key, used by the draft
     * persistence and the store-node sync ingestion.
     */
    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {

        /**
         * Returns the unique key identifying the backup-monetics method.
         *
         * @return the method key "SECOURS"
         */
        @Override
        public String getKey() { return "SECOURS"; }

        /**
         * Creates a backup-monetics payment entity.
         *
         * @param amount the amount the mobile terminal accepted
         * @param tendered ignored (nothing is tendered at this till)
         * @return the created payment entity
         */
        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new BackupPayment(amount);
        }
    }
}
