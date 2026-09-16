package com.intermarche.pos.domain.payment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;

/**
 * The legal cash-rounding difference, booked as a settlement of its own
 * ({@code LC-07-03-06}).
 *
 * <p>Belgium has required since December 2019 that an amount settled in cash be
 * rounded to the nearest five cents, so the one- and two-cent coins stop
 * circulating. The customer hands over the rounded amount; the ticket still owes
 * its real total. The few cents between the two are neither a discount granted to
 * the customer nor a shortfall in the drawer — they are a settlement, and a
 * settlement is what makes the payment ledger of the sale balance.
 *
 * <p>SIGNED, BOTH WAYS. Rounding down (24,62 settled as 24,60) books +0,02 so the
 * payments still sum to the total; rounding up (23,43 settled as 23,45) books
 * −0,02. The receipt prints the OPPOSITE sign, because on paper the line reads as
 * the adjustment to what the customer has to hand over — that inversion lives in
 * the printer, in one place, and nowhere else.
 *
 * <p>It carries no field of its own: the amount and the registration order it
 * inherits say everything there is to know about it.
 */
@Entity
@DiscriminatorValue("ARRONDI")
public class RoundingPayment extends TicketPayment {

    /**
     * Default constructor for JPA.
     */
    protected RoundingPayment() {}

    /**
     * Creates a rounding settlement with its signed difference.
     *
     * @param amount the real amount minus the rounded amount, either sign
     */
    public RoundingPayment(BigDecimal amount) { super(amount); }

    /**
     * Factory discovered by CDI under the "ARRONDI" key, used by the draft
     * persistence and the store-node sync ingestion.
     */
    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {

        /**
         * Returns the unique key identifying the cash-rounding settlement.
         *
         * @return the method key "ARRONDI"
         */
        @Override
        public String getKey() { return "ARRONDI"; }

        /**
         * Creates a cash-rounding settlement entity.
         *
         * @param amount the signed rounding difference
         * @param tendered ignored (nothing is tendered for a rounding)
         * @return the created payment entity
         */
        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new RoundingPayment(amount);
        }
    }
}
