package com.intermarche.pos.ui.reprintticket;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ReprintState}, covering both arms of every list and
 * detail pagination guard.
 */
class ReprintStateTest {

    /**
     * Builds a ticket carrying the given number of freshly created lines.
     *
     * @param lineCount how many lines to attach
     * @return a ticket whose {@code lines} list holds {@code lineCount} entries
     */
    private Ticket ticketWithLines(int lineCount) {
        Ticket ticket = new Ticket();
        List<TicketLine> lines = new ArrayList<>();
        for (int i = 0; i < lineCount; i++) lines.add(new TicketLine());
        ticket.lines = lines;
        return ticket;
    }

    /**
     * Builds a list of freshly created tickets.
     *
     * @param count how many tickets to create
     * @return a list holding {@code count} tickets
     */
    private List<Ticket> tickets(int count) {
        List<Ticket> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add(new Ticket());
        return list;
    }

    /**
     * A brand-new state exposes an empty history and reset pagings.
     */
    @Test
    void freshStateHasEmptyDefaults() {
        ReprintState state = new ReprintState();
        assertTrue(state.tickets.isEmpty());
        assertEquals(0, state.listPage);
        assertNull(state.viewedTicket);
        assertEquals(0, state.detailPage);
    }

    /**
     * {@code setTickets} installs the history and resets both pagings and the
     * viewed ticket.
     */
    @Test
    void setTicketsResetsPagingsAndViewedTicket() {
        ReprintState state = new ReprintState();
        state.listPage = 3;
        state.detailPage = 2;
        state.viewedTicket = new Ticket();
        List<Ticket> history = tickets(4);
        state.setTickets(history);
        assertSame(history, state.tickets);
        assertEquals(0, state.listPage);
        assertNull(state.viewedTicket);
        assertEquals(0, state.detailPage);
    }

    /**
     * {@code isHasListPrev} is false on the first page (guard false arm).
     */
    @Test
    void hasListPrevFalseOnFirstPage() {
        ReprintState state = new ReprintState();
        state.listPage = 0;
        assertFalse(state.isHasListPrev());
    }

    /**
     * {@code isHasListPrev} is true past the first page (guard true arm).
     */
    @Test
    void hasListPrevTruePastFirstPage() {
        ReprintState state = new ReprintState();
        state.listPage = 1;
        assertTrue(state.isHasListPrev());
    }

    /**
     * {@code isHasListNext} is true when more tickets follow the current page
     * (comparison true arm).
     */
    @Test
    void hasListNextTrueWhenMoreTicketsFollow() {
        ReprintState state = new ReprintState();
        state.setTickets(tickets(7));
        assertTrue(state.isHasListNext());
    }

    /**
     * {@code isHasListNext} is false when the current page is the last
     * (comparison false arm).
     */
    @Test
    void hasListNextFalseOnLastPage() {
        ReprintState state = new ReprintState();
        state.setTickets(tickets(6));
        assertFalse(state.isHasListNext());
    }

    /**
     * {@code getVisibleTickets} returns an empty slice for an empty history
     * (isEmpty true arm).
     */
    @Test
    void visibleTicketsEmptyWhenNoHistory() {
        ReprintState state = new ReprintState();
        state.setTickets(new ArrayList<>());
        assertTrue(state.getVisibleTickets().isEmpty());
    }

    /**
     * {@code getVisibleTickets} returns the first six rows of a longer history
     * (isEmpty false arm, from-in-range false arm).
     */
    @Test
    void visibleTicketsReturnsFirstPageSlice() {
        ReprintState state = new ReprintState();
        state.setTickets(tickets(8));
        List<Ticket> visible = state.getVisibleTickets();
        assertEquals(6, visible.size());
        assertSame(state.tickets.get(0), visible.get(0));
        assertSame(state.tickets.get(5), visible.get(5));
    }

    /**
     * {@code getVisibleTickets} returns the shorter tail slice of the last page.
     */
    @Test
    void visibleTicketsReturnsTailSliceOnLastPage() {
        ReprintState state = new ReprintState();
        state.setTickets(tickets(8));
        state.listPage = 1;
        List<Ticket> visible = state.getVisibleTickets();
        assertEquals(2, visible.size());
        assertSame(state.tickets.get(6), visible.get(0));
        assertSame(state.tickets.get(7), visible.get(1));
    }

    /**
     * {@code getVisibleTickets} returns an empty slice when a stale page index
     * points past the (non-empty) history (from-out-of-range true arm).
     */
    @Test
    void visibleTicketsEmptyWhenPageIndexStale() {
        ReprintState state = new ReprintState();
        state.setTickets(tickets(3));
        state.listPage = 2;
        assertTrue(state.getVisibleTickets().isEmpty());
    }

    /**
     * {@code getListPageDisplay} converts the zero-based index to a 1-based
     * number.
     */
    @Test
    void listPageDisplayIsOneBased() {
        ReprintState state = new ReprintState();
        state.listPage = 4;
        assertEquals(5, state.getListPageDisplay());
    }

    /**
     * {@code setViewedTicket} stores the ticket and resets its detail paging.
     */
    @Test
    void setViewedTicketResetsDetailPage() {
        ReprintState state = new ReprintState();
        state.detailPage = 3;
        Ticket ticket = ticketWithLines(2);
        state.setViewedTicket(ticket);
        assertSame(ticket, state.viewedTicket);
        assertEquals(0, state.detailPage);
    }

    /**
     * {@code isHasDetailPrev} is false on the first detail page (guard false
     * arm).
     */
    @Test
    void hasDetailPrevFalseOnFirstPage() {
        ReprintState state = new ReprintState();
        state.detailPage = 0;
        assertFalse(state.isHasDetailPrev());
    }

    /**
     * {@code isHasDetailPrev} is true past the first detail page (guard true
     * arm).
     */
    @Test
    void hasDetailPrevTruePastFirstPage() {
        ReprintState state = new ReprintState();
        state.detailPage = 1;
        assertTrue(state.isHasDetailPrev());
    }

    /**
     * {@code isHasDetailNext} is false with no viewed ticket (null-ticket short
     * circuit).
     */
    @Test
    void hasDetailNextFalseWhenNoViewedTicket() {
        ReprintState state = new ReprintState();
        state.viewedTicket = null;
        assertFalse(state.isHasDetailNext());
    }

    /**
     * {@code isHasDetailNext} is false when the viewed ticket has null lines
     * (null-lines arm).
     */
    @Test
    void hasDetailNextFalseWhenLinesNull() {
        ReprintState state = new ReprintState();
        Ticket ticket = new Ticket();
        ticket.lines = null;
        state.viewedTicket = ticket;
        assertFalse(state.isHasDetailNext());
    }

    /**
     * {@code isHasDetailNext} is true when more lines follow the current detail
     * page (comparison true arm, both null guards false).
     */
    @Test
    void hasDetailNextTrueWhenMoreLinesFollow() {
        ReprintState state = new ReprintState();
        state.setViewedTicket(ticketWithLines(7));
        assertTrue(state.isHasDetailNext());
    }

    /**
     * {@code isHasDetailNext} is false when the current detail page is the last
     * (comparison false arm).
     */
    @Test
    void hasDetailNextFalseOnLastPage() {
        ReprintState state = new ReprintState();
        state.setViewedTicket(ticketWithLines(6));
        assertFalse(state.isHasDetailNext());
    }

    /**
     * {@code getVisibleLines} returns an empty slice with no viewed ticket
     * (null-ticket short circuit).
     */
    @Test
    void visibleLinesEmptyWhenNoViewedTicket() {
        ReprintState state = new ReprintState();
        state.viewedTicket = null;
        assertTrue(state.getVisibleLines().isEmpty());
    }

    /**
     * {@code getVisibleLines} returns an empty slice when the viewed ticket has
     * null lines (null-lines arm).
     */
    @Test
    void visibleLinesEmptyWhenLinesNull() {
        ReprintState state = new ReprintState();
        Ticket ticket = new Ticket();
        ticket.lines = null;
        state.viewedTicket = ticket;
        assertTrue(state.getVisibleLines().isEmpty());
    }

    /**
     * {@code getVisibleLines} returns the first six lines of a longer ticket
     * (both null guards false, clamp false arm).
     */
    @Test
    void visibleLinesReturnsFirstPageSlice() {
        ReprintState state = new ReprintState();
        state.setViewedTicket(ticketWithLines(8));
        List<TicketLine> visible = state.getVisibleLines();
        assertEquals(6, visible.size());
        assertSame(state.viewedTicket.lines.get(0), visible.get(0));
        assertSame(state.viewedTicket.lines.get(5), visible.get(5));
    }

    /**
     * {@code getVisibleLines} excludes a cancelled article (lot C4,
     * BO-04-01-16): a reprint reproduces the ticket as sold, so a cancelled
     * line — a journal witness only — never appears (the {@code !l.cancelled}
     * filter true arm).
     */
    @Test
    void visibleLinesExcludesCancelledLines() {
        Ticket ticket = new Ticket();
        List<TicketLine> lines = new ArrayList<>();
        TicketLine sold = new TicketLine();
        TicketLine cancelled = new TicketLine();
        cancelled.cancelled = true;
        lines.add(sold);
        lines.add(cancelled);
        ticket.lines = lines;
        ReprintState state = new ReprintState();
        state.setViewedTicket(ticket);
        List<TicketLine> visible = state.getVisibleLines();
        assertEquals(1, visible.size());
        assertSame(sold, visible.get(0));
    }

    /**
     * {@code getVisibleLines} returns the tail slice of the last detail page.
     */
    @Test
    void visibleLinesReturnsTailSliceOnLastPage() {
        ReprintState state = new ReprintState();
        state.setViewedTicket(ticketWithLines(8));
        state.detailPage = 1;
        List<TicketLine> visible = state.getVisibleLines();
        assertEquals(2, visible.size());
        assertSame(state.viewedTicket.lines.get(6), visible.get(0));
        assertSame(state.viewedTicket.lines.get(7), visible.get(1));
    }

    /**
     * {@code getVisibleLines} self-clamps a stale detail page left by a longer
     * ticket, correcting the index on read (clamp true arm).
     */
    @Test
    void visibleLinesClampsStaleDetailPage() {
        ReprintState state = new ReprintState();
        state.setViewedTicket(ticketWithLines(3));
        state.detailPage = 5;
        List<TicketLine> visible = state.getVisibleLines();
        assertEquals(0, state.detailPage);
        assertEquals(3, visible.size());
        assertSame(state.viewedTicket.lines.get(0), visible.get(0));
    }

    /**
     * {@code getDetailPageDisplay} converts the zero-based index to a 1-based
     * number.
     */
    @Test
    void detailPageDisplayIsOneBased() {
        ReprintState state = new ReprintState();
        state.detailPage = 2;
        assertEquals(3, state.getDetailPageDisplay());
    }

    /**
     * {@code getDetailTotalPages} returns one with no viewed ticket
     * (null-ticket short circuit).
     */
    @Test
    void detailTotalPagesOneWhenNoViewedTicket() {
        ReprintState state = new ReprintState();
        state.viewedTicket = null;
        assertEquals(1, state.getDetailTotalPages());
    }

    /**
     * {@code getDetailTotalPages} returns one when the viewed ticket has null
     * lines (null-lines arm).
     */
    @Test
    void detailTotalPagesOneWhenLinesNull() {
        ReprintState state = new ReprintState();
        Ticket ticket = new Ticket();
        ticket.lines = null;
        state.viewedTicket = ticket;
        assertEquals(1, state.getDetailTotalPages());
    }

    /**
     * {@code getDetailTotalPages} returns one for an empty line list
     * (isEmpty true arm).
     */
    @Test
    void detailTotalPagesOneWhenLinesEmpty() {
        ReprintState state = new ReprintState();
        state.setViewedTicket(ticketWithLines(0));
        assertEquals(1, state.getDetailTotalPages());
    }

    /**
     * {@code getDetailTotalPages} rounds up the line count over the page size
     * (isEmpty false arm).
     */
    @Test
    void detailTotalPagesRoundsUp() {
        ReprintState state = new ReprintState();
        state.setViewedTicket(ticketWithLines(7));
        assertEquals(2, state.getDetailTotalPages());
    }

    // --- Bon pour échange : la sélection (LC-08-05-12) ---

    /**
     * A fresh state prepares no bon and names no line.
     */
    @Test
    void freshStatePreparesNoExchange() {
        ReprintState state = new ReprintState();
        assertFalse(state.exchangeMode);
        assertTrue(state.exchangeSelection.isEmpty());
        assertTrue(state.isWholeTicketExchange());
    }

    /**
     * Touching a line names it; touching it again unnames it.
     */
    @Test
    void togglingALineNamesThenUnnamesIt() {
        ReprintState state = new ReprintState();
        state.toggleExchangeLine(7L);
        assertTrue(state.isSelected(7L));
        assertFalse(state.isWholeTicketExchange());
        state.toggleExchangeLine(7L);
        assertFalse(state.isSelected(7L));
        assertTrue(state.isWholeTicketExchange());
    }

    /**
     * A line that was never touched carries no mark.
     */
    @Test
    void anUntouchedLineIsNotSelected() {
        ReprintState state = new ReprintState();
        state.toggleExchangeLine(7L);
        assertFalse(state.isSelected(8L));
    }

    /**
     * A missing line id names nothing rather than failing (null arm).
     */
    @Test
    void togglingANullLineNamesNothing() {
        ReprintState state = new ReprintState();
        state.toggleExchangeLine(null);
        assertTrue(state.exchangeSelection.isEmpty());
    }

    /**
     * A missing line id carries no mark either (null arm of the read).
     */
    @Test
    void aNullLineIsNotSelected() {
        assertFalse(new ReprintState().isSelected(null));
    }

    /**
     * Several lines can be named, and they keep the order they were touched in.
     */
    @Test
    void severalLinesCanBeNamed() {
        ReprintState state = new ReprintState();
        state.toggleExchangeLine(3L);
        state.toggleExchangeLine(1L);
        assertEquals(List.of(3L, 1L), new ArrayList<>(state.exchangeSelection));
    }

    /**
     * Leaving the preparation forgets both the mode and what was named.
     */
    @Test
    void clearExchangeForgetsEverything() {
        ReprintState state = new ReprintState();
        state.exchangeMode = true;
        state.toggleExchangeLine(7L);
        state.clearExchange();
        assertFalse(state.exchangeMode);
        assertTrue(state.exchangeSelection.isEmpty());
    }

    /**
     * Opening another ticket forgets the selection made on the previous one: a
     * selection belongs to the ticket it was made on.
     */
    @Test
    void openingAnotherTicketForgetsTheSelection() {
        ReprintState state = new ReprintState();
        state.exchangeMode = true;
        state.toggleExchangeLine(7L);
        state.setViewedTicket(new Ticket());
        assertFalse(state.exchangeMode);
        assertTrue(state.exchangeSelection.isEmpty());
    }

    /**
     * Reloading the history forgets it too.
     */
    @Test
    void reloadingTheHistoryForgetsTheSelection() {
        ReprintState state = new ReprintState();
        state.exchangeMode = true;
        state.toggleExchangeLine(7L);
        state.setTickets(List.of());
        assertFalse(state.exchangeMode);
        assertTrue(state.exchangeSelection.isEmpty());
    }
}
