package com.intermarche.edge.ui.journal;

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
     * The loyalty advantage zone, grouped by advantage type and ordered by the
     * received order (BO-03-03-25). A ticket predating the referential — its
     * lines carrying no type — yields a single group with an empty header, so it
     * shows the flat list it always did; a ticket with no advantage line at all
     * yields an empty list.
     */
    public final List<FidelityGroup> fidelityGroups;

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
     * @param fidelityGroups the grouped loyalty advantage zone
     */
    public JournalTicketDetail(String number, String terminal, String status, String date,
                               String time, String cashier, String cashierBadge, String store,
                               String fidelityCard, String totalIncludingTax,
                               String totalExcludingTax, String totalVat,
                               List<Line> lines, List<Payment> payments,
                               List<FidelityGroup> fidelityGroups) {
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
        this.fidelityGroups = fidelityGroups;
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
         * The real card entry mode (EXP-54: SANS_CONTACT / CODE), or null for a
         * non-card payment or a terminal that did not discriminate.
         */
        public final String entryMode;

        /**
         * Builds a detail payment.
         *
         * @param method the method key
         * @param amount the formatted amount
         * @param entryMode the card entry mode, or null
         */
        public Payment(String method, String amount, String entryMode) {
            this.method = method;
            this.amount = amount;
            this.entryMode = entryMode;
        }
    }

    /**
     * One advantage-type group of the loyalty zone: a header (the stored type
     * label, empty for the untyped flat group) and its advantage lines.
     */
    public static class FidelityGroup {

        /** The advantage-type label printed as the group header, or empty. */
        public final String header;

        /** The advantage lines under this group. */
        public final List<FidelityAdvantage> advantages;

        /**
         * Builds a group.
         *
         * @param header the group header, or an empty string when flat
         * @param advantages the advantage lines
         */
        public FidelityGroup(String header, List<FidelityAdvantage> advantages) {
            this.header = header == null ? "" : header;
            this.advantages = advantages;
        }
    }

    /**
     * One advantage line of the loyalty zone, pre-formatted.
     */
    public static class FidelityAdvantage {

        /** The advantage label, printed verbatim. */
        public final String label;

        /** The earn amount, formatted with a French comma. */
        public final String amount;

        /**
         * Builds an advantage line.
         *
         * @param label the advantage label
         * @param amount the formatted amount
         */
        public FidelityAdvantage(String label, String amount) {
            this.label = label == null ? "" : label;
            this.amount = amount;
        }
    }
}
