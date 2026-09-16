package com.intermarche.pos.ui.journal;

/**
 * One row of the transactional journal result list (BO-04-01-50): register,
 * transaction number, cashier, date, time, amount, article count, training
 * mode and autonomous flags. The exact column set the requirement enumerates,
 * pre-formatted server-side so the page and the CSV export render the same
 * strings (BO-04-01-50's "export à l'identique").
 * <p>
 * {@code trainingMode} and {@code autonomous} are always {@code "N"} on the
 * consolidated node: training-mode sales create no ticket row at all (see
 * {@code Ticket}), and self-scanning is not a modelled channel. They are kept
 * as columns so the list matches the requirement's shape; their invariant
 * value is reported as residue rather than hidden.
 */
public class JournalRow {

    /** The ticket database id, used to open the detail view. */
    public final Long id;

    /** The register (TPV) identifier. */
    public final String terminal;

    /** The transaction (ticket) number. */
    public final String transaction;

    /** The cashier badge identifier. */
    public final String cashier;

    /** The transaction date, formatted dd/MM/yyyy. */
    public final String date;

    /** The transaction time, formatted HH:mm. */
    public final String time;

    /** The ticket amount, formatted with a French comma. */
    public final String amount;

    /** The number of article lines. */
    public final int itemCount;

    /** The training-mode flag, "O" or "N". */
    public final String trainingMode;

    /** The autonomous (self-scanning) flag, "O" or "N". */
    public final String autonomous;

    /**
     * Builds a result row.
     *
     * @param id the ticket database id
     * @param terminal the register identifier
     * @param transaction the transaction number
     * @param cashier the cashier badge
     * @param date the formatted date
     * @param time the formatted time
     * @param amount the formatted amount
     * @param itemCount the article count
     * @param trainingMode the training-mode flag
     * @param autonomous the autonomous flag
     */
    public JournalRow(Long id, String terminal, String transaction, String cashier,
                      String date, String time, String amount, int itemCount,
                      String trainingMode, String autonomous) {
        this.id = id;
        this.terminal = terminal;
        this.transaction = transaction;
        this.cashier = cashier;
        this.date = date;
        this.time = time;
        this.amount = amount;
        this.itemCount = itemCount;
        this.trainingMode = trainingMode;
        this.autonomous = autonomous;
    }
}
