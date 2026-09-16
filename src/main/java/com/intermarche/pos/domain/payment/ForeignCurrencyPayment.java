package com.intermarche.pos.domain.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;

/**
 * A settlement handed over in a foreign currency ({@code LC-07-14}).
 *
 * <p>The inherited {@code amount} is the EURO value credited to the sale, because
 * that is the only figure the ticket, the VAT and the accounts understand. What
 * makes this class necessary is the three things that euro figure does not say:
 * which currency was handed over, how much of it, and at what rate — and
 * {@code LC-07-14-05} requires all three on the receipt.
 *
 * <p>THE RATE IS FROZEN HERE, copied from the currency at the moment of the sale
 * rather than read back from the referential. A shop revises its rate; a receipt
 * does not. Recomputing the rate later from the two amounts would also lose it to
 * rounding — 50 CHF at 1,053 gives the same 52,65 € as 1,0531.
 */
@Entity
@DiscriminatorValue("DEVISE")
public class ForeignCurrencyPayment extends TicketPayment {

    /** The ISO code of the currency handed over. */
    @Column(name = "currency_code", length = 3)
    public String currencyCode;

    /** The amount handed over, in that currency. */
    @Column(name = "currency_amount", precision = 12, scale = 2)
    public BigDecimal foreignAmount;

    /** The euros-for-one-unit rate applied, as it stood at sale time. */
    @Column(name = "currency_rate", precision = 12, scale = 6)
    public BigDecimal exchangeRate;

    /**
     * Default constructor for JPA.
     */
    protected ForeignCurrencyPayment() {}

    /**
     * Creates a foreign-currency settlement with its euro value.
     *
     * @param amount the euro value credited to the sale
     */
    public ForeignCurrencyPayment(BigDecimal amount) { super(amount); }

    /**
     * Returns the audit checksum, folding in what the euro amount does not say.
     *
     * @return a hash of the index, amount, currency, foreign amount and rate
     */
    @Override
    public int getChecksum() {
        return java.util.Objects.hash(super.getChecksum(), currencyCode, foreignAmount,
                exchangeRate);
    }

    /**
     * Factory discovered by CDI under the "DEVISE" key, used by the draft
     * persistence and the store-node sync ingestion.
     */
    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {

        /**
         * Returns the unique key identifying the foreign-currency method.
         *
         * @return the method key "DEVISE"
         */
        @Override
        public String getKey() { return "DEVISE"; }

        /**
         * Creates a foreign-currency payment entity.
         *
         * <p>The currency, the foreign amount and the rate are set by the caller
         * that knows them: this factory is the generic amount-and-tendered contract
         * shared by every method.
         *
         * @param amount the euro value credited to the sale
         * @param tendered ignored (the change logic works in euros)
         * @return the created payment entity
         */
        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new ForeignCurrencyPayment(amount);
        }
    }
}
