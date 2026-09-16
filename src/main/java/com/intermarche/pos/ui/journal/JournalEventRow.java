package com.intermarche.pos.ui.journal;

/**
 * One row of the functional journal (BO-04-01-52's second tab): the technical
 * events consolidated by the outbox — supervisor endorsements, session
 * closings, account lockouts, cart parks. Pre-formatted server-side like
 * {@link JournalRow} so the page and the CSV export agree.
 */
public class JournalEventRow {

    /** The register (TPV) identifier that produced the event. */
    public final String terminal;

    /**
     * The badge (N° caissière) of the operator the event is about, or an empty
     * string for an event that has no operator.
     */
    public final String cashier;

    /** The event type name. */
    public final String type;

    /** The event date, formatted dd/MM/yyyy. */
    public final String date;

    /** The event time, formatted HH:mm. */
    public final String time;

    /** The short human-readable detail, or an empty string. */
    public final String detail;

    /**
     * Builds a functional-journal row.
     *
     * @param terminal the register identifier
     * @param cashier the operator badge, or null
     * @param type the event type name
     * @param date the formatted date
     * @param time the formatted time
     * @param detail the detail text, or null
     */
    public JournalEventRow(String terminal, String cashier, String type, String date, String time,
                           String detail) {
        this.terminal = terminal;
        this.cashier = cashier == null ? "" : cashier;
        this.type = type;
        this.date = date;
        this.time = time;
        this.detail = detail == null ? "" : detail;
    }
}
