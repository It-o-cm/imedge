package com.intermarche.pos.ui.journal;

import java.util.List;

/**
 * A fully materialized ticket detail for the journal detail view: header,
 * article lines and payments, all pre-formatted server-side. Built in one
 * pass by {@link JournalService} so the detail template never navigates a lazy
 * association at render time — the same "dumb renderer" posture the
 * supervision dashboard takes.
 */
public class JournalTicketDetail {

    /** The transaction (ticket) number. */
    public final String number;

    /** The register (TPV) identifier. */
    public final String terminal;

    /** The lifecycle status name. */
    public final String status;

    /** The creation date, formatted dd/MM/yyyy. */
    public final String date;

    /** The creation time, formatted HH:mm. */
    public final String time;

    /** The cashier full name. */
    public final String cashier;

    /** The cashier badge identifier. */
    public final String cashierBadge;

    /** The store name, or an empty string. */
    public final String store;

    /** The fidelity card presented, or an empty string. */
    public final String fidelityCard;

    /** The tax-included total, formatted with a French comma. */
    public final String totalIncludingTax;

    /** The tax-excluded total, formatted with a French comma. */
    public final String totalExcludingTax;

    /** The VAT total, formatted with a French comma. */
    public final String totalVat;

    /** The article lines. */
    public final List<Line> lines;

    /** The payments. */
    public final List<Payment> payments;

    /**
     * Builds a ticket detail.
     *
     * @param number the transaction number
     * @param terminal the register identifier
     * @param status the status name
     * @param date the formatted date
     * @param time the formatted time
     * @param cashier the cashier full name
     * @param cashierBadge the cashier badge
     * @param store the store name
     * @param fidelityCard the fidelity card
     * @param totalIncludingTax the formatted TTC total
     * @param totalExcludingTax the formatted HT total
     * @param totalVat the formatted VAT total
     * @param lines the article lines
     * @param payments the payments
     */
    public JournalTicketDetail(String number, String terminal, String status, String date,
                               String time, String cashier, String cashierBadge, String store,
                               String fidelityCard, String totalIncludingTax,
                               String totalExcludingTax, String totalVat,
                               List<Line> lines, List<Payment> payments) {
        this.number = number;
        this.terminal = terminal;
        this.status = status;
        this.date = date;
        this.time = time;
        this.cashier = cashier;
        this.cashierBadge = cashierBadge;
        this.store = store;
        this.fidelityCard = fidelityCard;
        this.totalIncludingTax = totalIncludingTax;
        this.totalExcludingTax = totalExcludingTax;
        this.totalVat = totalVat;
        this.lines = lines;
        this.payments = payments;
    }

    /**
     * One article line of the detail, pre-formatted.
     */
    public static class Line {

        /** The 1-based line number. */
        public final int number;

        /** The product label. */
        public final String label;

        /** The EAN as entered, or an empty string. */
        public final String ean;

        /** The PLU as entered, or an empty string. */
        public final String plu;

        /** The quantity, formatted with a French comma. */
        public final String quantity;

        /** The line total, formatted with a French comma. */
        public final String total;

        /** The price-modification label, or an empty string. */
        public final String modifier;

        /**
         * Whether the article was cancelled during the sale and kept as a
         * witness (lot C4, BO-04-01-16).
         * <p>
         * The detail SHOWS cancelled lines — a control tool that hides them
         * cannot answer "what did this cashier do at 15:42" — but it must
         * never let one pass for a sold article: such a line moved no money,
         * and the ticket total is the sum of the OTHER lines only.
         */
        public final boolean cancelled;

        /**
         * Builds a detail line.
         *
         * @param number the line number
         * @param label the product label
         * @param ean the EAN
         * @param plu the PLU
         * @param quantity the formatted quantity
         * @param total the formatted total
         * @param modifier the modifier label
         * @param cancelled whether the article was cancelled during the sale
         */
        public Line(int number, String label, String ean, String plu,
                    String quantity, String total, String modifier, boolean cancelled) {
            this.number = number;
            this.label = label;
            this.ean = ean == null ? "" : ean;
            this.plu = plu == null ? "" : plu;
            this.quantity = quantity;
            this.total = total;
            this.modifier = modifier == null ? "" : modifier;
            this.cancelled = cancelled;
        }
    }

    /**
     * One payment of the detail, pre-formatted.
     */
    public static class Payment {

        /** The payment method key (discriminator value). */
        public final String method;

        /** The applied amount, formatted with a French comma. */
        public final String amount;

        /**
         * Builds a detail payment.
         *
         * @param method the method key
         * @param amount the formatted amount
         */
        public Payment(String method, String amount) {
            this.method = method;
            this.amount = amount;
        }
    }
}
