package com.intermarche.edge.ui.journal;

/**
 * The detail of a ticket of the electronic journal regrouped by adhérent
 * (BO-02-05-05): where it comes from — adhérent, point of sale, reception on
 * the central node — around the very detail the store's journal shows, and
 * the ticket as its register printed it.
 */
public class AdherentJournalDetail {

    /** The adhérent operating the point of sale, or a dash. */
    public final String adherent;

    /** The number the ticket is filed under. */
    public final String pdv;

    /** The name of the point of sale, empty when unknown. */
    public final String pdvName;

    /** The journal type code. */
    public final String kind;

    /** When the central node received it, dd/MM/yyyy HH:mm. */
    public final String receivedAt;

    /** The printed ticket, empty when none. */
    public final String printed;

    /** The ticket detail. */
    public final JournalTicketDetail ticket;

    /**
     * Builds a detail.
     *
     * @param adherent the adhérent
     * @param pdv the point of sale number
     * @param pdvName the point of sale name
     * @param kind the type code
     * @param receivedAt the formatted reception
     * @param printed the printed ticket
     * @param ticket the ticket detail
     */
    public AdherentJournalDetail(String adherent, String pdv, String pdvName, String kind, String receivedAt,
                                 String printed, JournalTicketDetail ticket) {
        this.adherent = adherent;
        this.pdv = pdv;
        this.pdvName = pdvName;
        this.kind = kind;
        this.receivedAt = receivedAt;
        this.printed = printed;
        this.ticket = ticket;
    }
}
