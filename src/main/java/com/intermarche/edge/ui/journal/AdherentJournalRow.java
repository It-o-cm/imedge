package com.intermarche.edge.ui.journal;

/**
 * One row of the electronic journal regrouped by adhérent (BO-02-05-05): the
 * adhérent and the point of sale the ticket belongs to, then the journal
 * columns — register, number, cashier, date, time, amount, article count and
 * type — pre-formatted server-side.
 */
public class AdherentJournalRow {

    /** The row's database id, used to open the detail. */
    public final Long id;

    /** The adhérent operating the point of sale, or a dash. */
    public final String adherent;

    /** The number the ticket is filed under. */
    public final String pdv;

    /** The name of the point of sale, empty when unknown. */
    public final String pdvName;

    /** The register identifier. */
    public final String terminal;

    /** The ticket number. */
    public final String number;

    /** The cashier badge, empty when none. */
    public final String cashier;

    /** The date, dd/MM/yyyy. */
    public final String date;

    /** The time, HH:mm. */
    public final String time;

    /** The tax-included amount, French comma. */
    public final String amount;

    /** The number of article lines. */
    public final int itemCount;

    /** The journal type code. */
    public final String kind;

    /**
     * Builds a row.
     *
     * @param id the database id
     * @param adherent the adhérent
     * @param pdv the point of sale number
     * @param pdvName the point of sale name
     * @param terminal the register
     * @param number the ticket number
     * @param cashier the cashier badge
     * @param date the formatted date
     * @param time the formatted time
     * @param amount the formatted amount
     * @param itemCount the article count
     * @param kind the type code
     */
    public AdherentJournalRow(Long id, String adherent, String pdv, String pdvName, String terminal, String number,
                              String cashier, String date, String time, String amount, int itemCount, String kind) {
        this.id = id;
        this.adherent = adherent;
        this.pdv = pdv;
        this.pdvName = pdvName;
        this.terminal = terminal;
        this.number = number;
        this.cashier = cashier;
        this.date = date;
        this.time = time;
        this.amount = amount;
        this.itemCount = itemCount;
        this.kind = kind;
    }
}
