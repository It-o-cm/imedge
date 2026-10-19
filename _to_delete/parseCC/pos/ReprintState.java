package com.intermarche.pos.ui.reprintticket;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * In-memory state of the reprint screen: paged closed-ticket history and
 * paged detail of the ticket under review.
 * <p>
 * The tickets shown are PERSISTED entities loaded by the service — the
 * state pages over them, it never copies them, so what the cashier reviews
 * is exactly what the duplicata will print. Six rows per page on both
 * levels: the touch-screen constraint that shaped every list of the
 * register. The detail page self-clamps when a shorter ticket is opened
 * after a longer one (stale page index is corrected on read, not on set).
 */
public class ReprintState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Rows per page of the closed-ticket list (touch-screen sizing). */
    private static final int LIST_PAGE_SIZE = 6;

    /** Rows per page of the ticket detail (touch-screen sizing). */
    private static final int DETAIL_PAGE_SIZE = 6;

    // Données liste
    public List<Ticket> tickets = Collections.emptyList();
    public int listPage = 0;

    // Données détail
    public Ticket viewedTicket = null;
    public int detailPage = 0;

    /**
     * True while the operator is preparing a BON POUR ECHANGE of the viewed ticket
     * (LC-08-05-10): the detail rows become selectable instead of being read-only.
     */
    public boolean exchangeMode = false;

    /**
     * The lines named for the bon pour échange (LC-08-05-12), by database id.
     * <p>
     * EMPTY MEANS THE WHOLE TICKET, which is the plain "duplicata sans prix" of
     * LC-08-05-10: an operator who wants the lot touches nothing. Insertion-ordered so
     * the screen shows the marks in a stable order.
     */
    public java.util.Set<Long> exchangeSelection = new java.util.LinkedHashSet<>();

    /**
     * The number of a ticket held by ANOTHER register, as the operator typed it
     * (LC-08-05-05). Blank until the foreign-duplicata mask is used.
     */
    public String foreignTicketNumber = "";

    /**
     * The operator-facing error of the last foreign-duplicata attempt, empty when it
     * worked or when none was attempted.
     */
    public String foreignError = "";

    /**
     * True while the reprint screen is browsing for a REASSIGNMENT
     * (LC-03-06-02): the list and the search then keep only the closed tickets
     * no client is attached to, and the detail of such a ticket offers the
     * reassignment gesture.
     */
    public boolean reassignMode = false;

    /**
     * True while the card mask of the reassignment is open on the ticket
     * detail (LC-03-06-03): the mask owns the page until the operator
     * validates a card or leaves.
     */
    public boolean reassignMaskOpen = false;

    /**
     * The card number typed in the reassignment mask, blank until the mask is
     * opened (LC-03-06-03).
     */
    public String reassignCard = "";

    /**
     * The operator-facing outcome of the last reassignment attempt — a
     * refusal or the announced amount gap of a successful one — empty when
     * none was attempted (LC-03-06-04).
     */
    public String reassignError = "";

    // --- Logique Liste ---

    /**
     * Installs a fresh history and resets both pagings and the viewed ticket.
     *
     * @param tickets the closed tickets, most recent first
     */
    public void setTickets(List<Ticket> tickets) {
        this.tickets = tickets;
        this.listPage = 0;
        this.viewedTicket = null;
        this.detailPage = 0;
        clearExchange();
    }

    /**
     * Tells whether a previous list page exists.
     *
     * @return true when the list can page back
     */
    public boolean isHasListPrev() { return listPage > 0; }
    /**
     * Tells whether a next list page exists.
     *
     * @return true when the list can page forward
     */
    public boolean isHasListNext() { return (listPage + 1) * LIST_PAGE_SIZE < tickets.size(); }

    /**
     * Returns the tickets of the current list page.
     *
     * @return the visible slice of the history
     */
    public List<Ticket> getVisibleTickets() {
        if (tickets.isEmpty()) return Collections.emptyList();
        int from = listPage * LIST_PAGE_SIZE;
        int to = Math.min(from + LIST_PAGE_SIZE, tickets.size());
        if (from >= tickets.size()) return Collections.emptyList();
        return tickets.subList(from, to);
    }

    /**
     * Returns the 1-based list page number for display.
     *
     * @return the displayed page number
     */
    public int getListPageDisplay() { return listPage + 1; }

    // --- Logique Détail ---

    /**
     * Opens a ticket in the detail view, resetting its paging.
     *
     * @param ticket the persisted ticket to review
     */
    public void setViewedTicket(Ticket ticket) {
        this.viewedTicket = ticket;
        this.detailPage = 0;
        clearExchange();
    }

    /**
     * Leaves the bon-pour-échange preparation and forgets what was named: a selection
     * belongs to the ticket it was made on, never to the next one.
     */
    public void clearExchange() {
        this.exchangeMode = false;
        this.exchangeSelection.clear();
    }

    /**
     * Names a line for the bon pour échange, or unnames it when it already was
     * (LC-08-05-12).
     *
     * @param lineId the database id of the line touched
     */
    public void toggleExchangeLine(Long lineId) {
        if (lineId == null) {
            return;
        }
        if (!exchangeSelection.remove(lineId)) {
            exchangeSelection.add(lineId);
        }
    }

    /**
     * Tells whether a line is named for the bon pour échange.
     *
     * @param lineId the database id of the line
     * @return true when the line carries a mark
     */
    public boolean isSelected(Long lineId) {
        return lineId != null && exchangeSelection.contains(lineId);
    }

    /**
     * Tells whether the bon pour échange covers the whole ticket, which is what an
     * empty selection means.
     *
     * @return true when nothing was named
     */
    public boolean isWholeTicketExchange() {
        return exchangeSelection.isEmpty();
    }

    /**
     * Tells whether a previous detail page exists.
     *
     * @return true when the detail can page back
     */
    public boolean isHasDetailPrev() { return detailPage > 0; }
    /**
     * Tells whether a next detail page exists.
     *
     * @return true when the detail can page forward
     */
    public boolean isHasDetailNext() {
        if (viewedTicket == null || viewedTicket.lines == null) return false;
        return (detailPage + 1) * DETAIL_PAGE_SIZE < sellableLines().size();
    }

    /**
     * Returns the reprintable lines of the viewed ticket: the sold lines, with
     * the cancelled articles filtered out (lot C4, BO-04-01-16). A reprint
     * reproduces the ticket as sold, so a cancelled article — a journal witness
     * only — never appears on it, nor in its pagination.
     *
     * @return the non-cancelled lines of the viewed ticket
     */
    private List<TicketLine> sellableLines() {
        return viewedTicket.lines.stream().filter(l -> !l.cancelled).toList();
    }

    /**
     * Returns the lines of the current detail page, clamping a stale page
     * index left by a previously viewed longer ticket.
     *
     * @return the visible slice of the ticket's lines
     */
    public List<TicketLine> getVisibleLines() {
        if (viewedTicket == null || viewedTicket.lines == null) return Collections.emptyList();
        List<TicketLine> allLines = sellableLines();

        int maxPage = Math.max(0, (allLines.size() - 1) / DETAIL_PAGE_SIZE);
        if (detailPage > maxPage) detailPage = maxPage;

        // The clamp above keeps the window inside the list: with a non-empty
        // ticket, from = page * DETAIL_PAGE_SIZE <= maxPage * DETAIL_PAGE_SIZE
        // <= size - 1. No further bound check is reachable — one used to sit
        // here and could never fire.
        int from = detailPage * DETAIL_PAGE_SIZE;
        int to = Math.min(from + DETAIL_PAGE_SIZE, allLines.size());
        return allLines.subList(from, to);
    }

    /**
     * Returns the 1-based detail page number for display.
     *
     * @return the displayed page number
     */
    public int getDetailPageDisplay() { return detailPage + 1; }
    /**
     * Returns the total number of detail pages (at least one).
     *
     * @return the detail page count
     */
    public int getDetailTotalPages() {
        if (viewedTicket == null || viewedTicket.lines == null || sellableLines().isEmpty()) return 1;
        return (int) Math.ceil((double) sellableLines().size() / DETAIL_PAGE_SIZE);
    }
}