package com.intermarche.edge.service.export;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * The catalogue of OUTGOING FLOWS the node produces (BO-11-01) — what accounting,
 * the gestion commerciale, the warehouse and the decisional expect to collect
 * from a point of sale.
 *
 * <p>Each entry carries its functional CODE, the file-name stem the receiving
 * systems know it by, the activation key that says whether this echelon emits it,
 * the PERIOD it covers and the SHAPE of the file it writes. Those last two are
 * what let one engine serve flows that are not alike: a monthly fold over tickets
 * and a daily fold over invoices differ by their declaration here, not by a second
 * engine. What a flow folds is not a field: the engine's member dispatch names the
 * fold, and the fold names the rows it loads.
 *
 * <p>Only the flows the node can compute from what it already holds are listed. A
 * flow absent from this enum is absent for a stated reason, never for lack of a
 * builder: the vignettes (BO-11-01-20) need a cash-side capture that does not
 * exist, and Catalina, SAFT, NEXO and the electronic labels are third-party
 * protocols — each its own chantier, and each declaring its provider in the
 * provider table rather than in a flow.
 *
 * <p>The stems {@code tranca3}, {@code tranfc} and {@code clifact} are the ones
 * the RFP names and must not be prettified, as are the three members of the panel
 * archive. The others are HOUSE stems: the RFP asks for the flow without naming
 * its file, so they follow the same short shape and are to be confirmed against
 * the receiving side's annex before any real exchange.
 */
public enum OutboundFlow {

    /**
     * Revenue by accounting day (BO-11-01-03), the flow the RFP calls
     * {@code tranca3}. One row per VAT rate, cumulative over several days when
     * several are asked for.
     */
    REVENUE("BO-11-01-03", "tranca3", "export.revenue-enabled",
            "Chiffre d'affaires par date", Period.DAY, Shape.CSV),

    /**
     * Cash book by accounting day (BO-11-01-04), the flow the RFP calls
     * {@code tranfc}: the amounts settled, by payment method.
     */
    CASH_BOOK("BO-11-01-04", "tranfc", "export.cash-book-enabled",
            "Livre de caisse par date", Period.DAY, Shape.CSV),

    /**
     * Revenue by product family AND by checkout island (BO-11-01-08). The island
     * comes from the checkout point the ticket was rung on, so the flow follows
     * the park as the back office administers it and not a list written here.
     */
    FAMILY_SALES("BO-11-01-08", "cafamil", "export.family-sales-enabled",
            "CA par famille et par ilot", Period.DAY, Shape.CSV),

    /**
     * The day's transactions, line by line (BO-11-01-14): the detail the
     * decisional re-aggregates its own way. Cancelled lines are PRESENT and
     * flagged, because a flow meant for analysis must be able to count what the
     * cashier took back, which a filtered file can no longer do.
     */
    DAILY_TRANSACTIONS("BO-11-01-14", "trandet", "export.transactions-enabled",
            "Detail quotidien des transactions", Period.DAY, Shape.CSV),

    /**
     * The closing synthesis, per register (BO-11-01-17): the counts and the
     * ticket-number range that let the receiving side check COMPLETENESS — that
     * no ticket of the day is missing — sales and non-sales alike.
     */
    PERIOD_SUMMARY("BO-11-01-17", "transyn", "export.period-summary-enabled",
            "Synthese des transactions de cloture", Period.DAY, Shape.CSV),

    /**
     * Checkout productivity (BO-11-01-21), per cashier and per SHIFT — the cash
     * session being the shift: the same cashier on two registers in one day is
     * two lines, which is what a productivity indicator is read on.
     */
    CASHIER_PRODUCTIVITY("BO-11-01-21", "prodcai", "export.productivity-enabled",
            "Productivite des caisses", Period.DAY, Shape.CSV),

    /**
     * Customer-account invoices for accounting and the gestion commerciale
     * (BO-11-01-12), the file the RFP calls {@code clifact.dat}: a FLAT,
     * positioned-field record per invoice followed by one per billed line, with
     * the civility, the coordinates and the eco-tax (BO-10-04-14).
     *
     * <p>Its activation is the one BO-11-01-06 asks for "at the Country /
     * Enseigne / Point of Sale scale", which is what an administered key gives.
     */
    CUSTOMER_INVOICES("BO-11-01-12", "clifact", "export.customer-invoices-enabled",
            "Factures clients en compte", Period.DAY, Shape.FLAT),

    /**
     * The invoices issued during the day, exposed to the ERP (BO-11-01-22) so it
     * can book them and follow the outstanding balances. Same invoices as
     * {@link #CUSTOMER_INVOICES}, one row each and no line detail: the ERP posts
     * a document, it does not re-price a basket.
     */
    ERP_INVOICES("BO-11-01-22", "facterp", "export.erp-invoices-enabled",
            "Factures du jour vers l'ERP", Period.DAY, Shape.CSV),

    /**
     * Fidelity settlements ventilated by VAT rate (BO-11-01-13), the only MONTHLY
     * flow of the catalogue: the requirement asks for a month, and a month is not
     * an accounting day the engine can round to.
     */
    FIDELITY_VAT("BO-11-01-13", "fidtva", "export.fidelity-vat-enabled",
            "Conso fidelite ventilee par TVA", Period.MONTH, Shape.CSV),

    /**
     * The VAT ventilation of the day's ORDERS (BO-11-01-23): what the accounting
     * ERP controls the collected tax on deposits with, after each day close.
     */
    ORDER_VAT("BO-11-01-23", "tvacmde", "export.order-vat-enabled",
            "Ventilation TVA des commandes", Period.DAY, Shape.CSV),

    /**
     * The marketing-scan panels (BO-11-01-19): THREE files the RFP names —
     * {@code Scannels}, {@code Scvenmag}, {@code Tlogextr} — collected in one
     * archive. The only flow of the catalogue that writes more than one member,
     * which is why the engine addresses a flow as a set of members rather than as
     * a file.
     */
    PANELS("BO-11-01-19", "MS", "export.panels-enabled",
            "Panels marketing (scan)", Period.DAY, Shape.ARCHIVE);

    /** The span a flow covers: nearly all of them a day, the fidelity one a month. */
    public enum Period {

        /** One accounting day, cut over at the administered hour. */
        DAY,

        /** One calendar month of accounting days. */
        MONTH
    }

    /**
     * The shape of the file a flow writes: a separated file, a positioned-field
     * flat file, or an archive holding several members.
     */
    public enum Shape {

        /** Separated columns, the house convention for every analytical flow. */
        CSV("csv"),

        /** Positioned fields, the convention the accounting .dat files use. */
        FLAT("dat"),

        /** A zip holding the members the flow produces together. */
        ARCHIVE("zip");

        /** The file extension this shape is written under. */
        private final String extension;

        /**
         * Builds a shape.
         *
         * @param extension the file extension
         */
        Shape(String extension) {
            this.extension = extension;
        }

        /**
         * Returns the file extension of this shape.
         *
         * @return the extension, without the dot
         */
        public String getExtension() {
            return extension;
        }
    }

    /** The requirement this flow answers, for the trace and the logs. */
    private final String requirement;

    /** The file-name stem the receiving systems know this flow by. */
    private final String stem;

    /** The administered key that activates this flow at an echelon. */
    private final String activationKey;

    /** The operator-facing label (French). */
    private final String label;

    /** The span this flow covers. */
    private final Period period;

    /** The shape of the file this flow writes. */
    private final Shape shape;

    /**
     * Builds a flow of the catalogue.
     *
     * @param requirement the RFP requirement code
     * @param stem the file-name stem
     * @param activationKey the administered activation key
     * @param label the French label
     * @param period the span it covers
     * @param shape the shape of the file it writes
     */
    OutboundFlow(String requirement, String stem, String activationKey, String label,
            Period period, Shape shape) {
        this.requirement = requirement;
        this.stem = stem;
        this.activationKey = activationKey;
        this.label = label;
        this.period = period;
        this.shape = shape;
    }

    /**
     * Returns the RFP requirement this flow answers.
     *
     * @return the requirement code
     */
    public String getRequirement() {
        return requirement;
    }

    /**
     * Returns the file-name stem the receiving systems expect.
     *
     * @return the stem
     */
    public String getStem() {
        return stem;
    }

    /**
     * Returns the administered key that activates this flow.
     *
     * @return the activation key
     */
    public String getActivationKey() {
        return activationKey;
    }

    /**
     * Returns the operator-facing label.
     *
     * @return the French label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Returns the span this flow covers.
     *
     * @return the period
     */
    public Period getPeriod() {
        return period;
    }

    /**
     * Returns the shape of the file this flow writes.
     *
     * @return the shape
     */
    public Shape getShape() {
        return shape;
    }

    /**
     * Tells whether this flow writes an archive of several members.
     *
     * @return true when the flow is an archive
     */
    public boolean isArchive() {
        return shape == Shape.ARCHIVE;
    }

    /**
     * Builds the file name of this flow for one accounting day.
     *
     * <p>The day is spelled {@code yyyyMMdd}, or {@code yyyyMM} for a monthly
     * flow, and never localised: the file name is read by another system, not by
     * a person, and a locale-dependent name is a name that changes when the
     * server does.
     *
     * @param day the accounting day the flow covers, or any day of the month for
     *     a monthly flow
     * @return the file name, stem, period and extension
     */
    public String fileName(LocalDate day) {
        String stamp = period == Period.MONTH
                ? day.format(DateTimeFormatter.ofPattern("yyyyMM"))
                : day.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return stem + "-" + stamp + "." + shape.getExtension();
    }
}
