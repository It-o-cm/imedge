package com.intermarche.pos.ui.journal;

/**
 * One row of the movements journal (the third tab): the cash movements
 * consolidated by the outbox — withdrawals, deposits, expenses, customer
 * down-payments and cash-count declarations (BO-04-01-12/33/35/36/37/40/44).
 * Pre-formatted server-side like {@link JournalRow} and {@link JournalEventRow}
 * so the page renders ready strings and never touches an unfetched association.
 * <p>
 * A cash movement is neither a {@code Ticket} (transactional tab) nor a
 * {@code TechnicalEvent} (functional tab): it is a distinct consolidated
 * entity, which is why it lands on its own tab rather than being grafted onto
 * either search. The declared amount doubles as the count result the "résultat
 * des comptages" criterion (BO-04-01-44) reads, so it is carried as a formatted
 * column of its own.
 */
public class JournalMovementRow {

    /** The register (TPV) identifier that recorded the movement. */
    public final String terminal;

    /**
     * The badge (N° caissière) of the cashier who performed the movement, or an
     * empty string when the movement carries no cashier.
     */
    public final String cashier;

    /** The movement type name (WITHDRAWAL, DEPOSIT, EXPENSE, CUSTOMER_DEPOSIT, DECLARATION). */
    public final String type;

    /** The movement date, formatted dd/MM/yyyy. */
    public final String date;

    /** The movement time, formatted HH:mm. */
    public final String time;

    /** The movement amount, formatted with a French comma. */
    public final String amount;

    /** The free-text reason of the movement, or an empty string. */
    public final String reason;

    /**
     * The badge of the manager who endorsed the movement above the endorsement
     * threshold, or an empty string when none was required or given.
     */
    public final String endorsedBy;

    /**
     * Builds a movements-journal row.
     *
     * @param terminal the register identifier
     * @param cashier the cashier badge, or null
     * @param type the movement type name
     * @param date the formatted date
     * @param time the formatted time
     * @param amount the formatted amount
     * @param reason the reason text, or null
     * @param endorsedBy the endorsing-manager badge, or null
     */
    public JournalMovementRow(String terminal, String cashier, String type, String date, String time,
                              String amount, String reason, String endorsedBy) {
        this.terminal = terminal;
        this.cashier = cashier == null ? "" : cashier;
        this.type = type;
        this.date = date;
        this.time = time;
        this.amount = amount;
        this.reason = reason == null ? "" : reason;
        this.endorsedBy = endorsedBy == null ? "" : endorsedBy;
    }
}
