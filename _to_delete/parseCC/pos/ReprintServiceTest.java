package com.intermarche.pos.ui.reprintticket;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.ticket.TicketState;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ReprintService}.
 * <p>
 * The service drives the reprint screen over a mocked {@link PosState} (carrying
 * mocked {@link ReprintState} and {@link TicketState} holders) and a mocked
 * {@link TicketPrinterService}. {@code loadHistory()} pulls the closed-ticket
 * list through the {@code Ticket.find(...)} static finder, which resolves to
 * {@link PanacheEntityBase} under plain {@code mvn test} and is intercepted with
 * {@link org.mockito.Mockito#mockStatic}. {@code print(Long)} guards on the
 * training-mode flag and on a null id. Tests assert delegation and cover both
 * arms of the training-mode guard and both arms of the null-id guard
 * ({@code loadHistory} is straight-line, 4 branches in {@code print}).
 */
class ReprintServiceTest {

    /**
     * Builds a {@link ReprintService} whose collaborators are fresh mocks wired
     * onto its package-private fields, including the {@link PosState#reprint} and
     * {@code ticket} sub-state holders so no direct field access hits a null.
     *
     * @return a service with fully mocked state and printer
     */
    private ReprintService newService() {
        ReprintService service = new ReprintService();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        service.i18n = TestMessages.french();
        service.state = mock(PosState.class);
        service.state.reprint = mock(ReprintState.class);
        service.state.ticket = mock(TicketState.class);
        service.ticketPrinterService = mock(TicketPrinterService.class);
        // The store-node client (LC-08-05-05): left un-stubbed it reports no node,
        // which is what every case written before this lot assumed.
        service.storeTicketClient = mock(StoreTicketClient.class);
        service.ticketSearchService = mock(com.intermarche.pos.ui.ticket.TicketSearchService.class);
        // The reassignment gesture (LC-03-06-01 to -06): the endorsement flow
        // answers authorized by default, so every historical case holds; the
        // endorsement-parked case stubs its own verdict.
        service.endorsementService = mock(com.intermarche.pos.ui.endorsement.EndorsementService.class);
        when(service.endorsementService.gestureAuthorized(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        service.reassignmentService = mock(TicketReassignmentService.class);
        return service;
    }

    /**
     * {@code search} with a blank pattern falls back to the full history (EXP-47,
     * blank arm) — it does NOT query the search engine.
     */
    @Test
    void searchBlankLoadsHistory() {
        ReprintService service = newService();
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            io.quarkus.hibernate.orm.panache.PanacheQuery<com.intermarche.pos.domain.sale.Ticket> q =
                    mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
            when(q.list()).thenReturn(java.util.List.of());
            panache.when(() -> com.intermarche.pos.domain.sale.Ticket.find(anyString(), any(Object[].class)))
                    .thenReturn(q);
            service.search("  ");
        }
        verifyNoInteractions(service.ticketSearchService);
    }

    /**
     * {@code search} with a pattern runs the shared engine and stores its results
     * (EXP-47, non-blank arm).
     */
    @Test
    void searchPatternUsesTheEngine() {
        ReprintService service = newService();
        java.util.List<com.intermarche.pos.domain.sale.Ticket> hits =
                java.util.List.of(mock(com.intermarche.pos.domain.sale.Ticket.class));
        when(service.ticketSearchService.searchByPattern("14/03/2026")).thenReturn(hits);
        service.search("14/03/2026");
        verify(service.state.reprint).setTickets(hits);
    }

    // --- Bon pour échange (LC-08-05-10 to -13) ---

    /**
     * {@code startExchange()} opens the preparation on a cleared selection: what was
     * named on a previous ticket never carries over.
     */
    @Test
    void startExchangeOpensOnAClearedSelection() {
        ReprintService service = newService();
        service.startExchange();
        verify(service.state.reprint).clearExchange();
        verify(service.state).touch();
    }

    /**
     * {@code cancelExchange()} leaves the preparation without printing.
     */
    @Test
    void cancelExchangeLeavesWithoutPrinting() {
        ReprintService service = newService();
        service.cancelExchange();
        verify(service.state.reprint).clearExchange();
        verifyNoInteractions(service.ticketPrinterService);
    }

    /**
     * {@code toggleExchangeLine()} hands the touched line to the state.
     */
    @Test
    void toggleExchangeLineDelegatesToTheState() {
        ReprintService service = newService();
        service.toggleExchangeLine(7L);
        verify(service.state.reprint).toggleExchangeLine(7L);
        verify(service.state).touch();
    }

    /**
     * {@code printExchange()} prints the bon and leaves the preparation
     * (training false, id non-null).
     */
    @Test
    void printExchangePrintsAndLeavesThePreparation() {
        ReprintService service = newService();
        service.state.trainingMode = false;
        service.state.reprint.exchangeSelection = new java.util.LinkedHashSet<>(java.util.List.of(3L));
        service.printExchange(5L);
        verify(service.ticketPrinterService).printExchangeVoucher(eq(5L), any());
        verify(service.state.reprint).clearExchange();
    }

    /**
     * {@code printExchange()} refuses in training: the bon carries a real ticket's
     * references (training arm true).
     */
    @Test
    void printExchangeRefusedInTraining() {
        ReprintService service = newService();
        service.state.trainingMode = true;
        service.printExchange(5L);
        verify(service.state.ticket).setError("IMPRESSION INDISPONIBLE EN FORMATION");
        verifyNoInteractions(service.ticketPrinterService);
    }

    /**
     * {@code printExchange()} prints nothing on a null id, and still leaves the
     * preparation (null-id arm).
     */
    @Test
    void printExchangeWithoutIdPrintsNothing() {
        ReprintService service = newService();
        service.state.trainingMode = false;
        service.state.reprint.exchangeSelection = new java.util.LinkedHashSet<>();
        service.printExchange(null);
        verifyNoInteractions(service.ticketPrinterService);
        verify(service.state.reprint).clearExchange();
    }

    // --- Duplicata d'une autre caisse (LC-08-05-05) ---

    /**
     * {@code printForeign()} prints verbatim what the store node returned.
     */
    @Test
    void printForeignPrintsWhatTheStoreNodeReturned() {
        ReprintService service = newService();
        service.state.trainingMode = false;
        when(service.storeTicketClient.isAvailable()).thenReturn(true);
        when(service.storeTicketClient.fetchDuplicate("C09-000012"))
                .thenReturn(java.util.Optional.of("TICKET RENDU"));
        service.printForeign("C09-000012");
        verify(service.ticketPrinterService).printRenderedTicket("TICKET RENDU");
    }

    /**
     * {@code printForeign()} refuses in training (training arm true).
     */
    @Test
    void printForeignRefusedInTraining() {
        ReprintService service = newService();
        service.state.trainingMode = true;
        service.printForeign("C09-000012");
        verifyNoInteractions(service.ticketPrinterService);
        verifyNoInteractions(service.storeTicketClient);
    }

    /**
     * {@code printForeign()} refuses a blank number without asking the node
     * (blank arm).
     */
    @Test
    void printForeignRefusesABlankNumber() {
        ReprintService service = newService();
        service.state.trainingMode = false;
        service.printForeign("   ");
        verifyNoInteractions(service.storeTicketClient);
        verifyNoInteractions(service.ticketPrinterService);
    }

    /**
     * {@code printForeign()} says so when no store node is configured — a standalone
     * register has nowhere to ask (availability arm false).
     */
    @Test
    void printForeignWithoutAStoreNodeSaysSo() {
        ReprintService service = newService();
        service.state.trainingMode = false;
        when(service.storeTicketClient.isAvailable()).thenReturn(false);
        service.printForeign("C09-000012");
        verifyNoInteractions(service.ticketPrinterService);
    }

    /**
     * {@code printForeign()} says so when the shop holds no such ticket (empty answer
     * arm).
     */
    @Test
    void printForeignWithoutAMatchSaysSo() {
        ReprintService service = newService();
        service.state.trainingMode = false;
        when(service.storeTicketClient.isAvailable()).thenReturn(true);
        when(service.storeTicketClient.fetchDuplicate("C09-000012"))
                .thenReturn(java.util.Optional.empty());
        service.printForeign("C09-000012");
        verifyNoInteractions(service.ticketPrinterService);
    }

    /**
     * {@code printForeign(null)} normalises a null number to the empty string and
     * then refuses it as blank, without ever asking the node (ternary null arm,
     * blank arm true).
     */
    @Test
    void printForeignWithANullNumberIsTreatedAsBlank() {
        ReprintService service = newService();
        service.state.trainingMode = false;
        service.printForeign(null);
        verifyNoInteractions(service.storeTicketClient);
        verifyNoInteractions(service.ticketPrinterService);
        verify(service.state).touch();
    }

    /**
     * {@code startForeign()} opens the foreign-ticket mask on an empty number and
     * cleared error, then touches the session (straight-line, no branch).
     */
    @Test
    void startForeignOpensTheForeignMask() {
        ReprintService service = newService();
        service.startForeign();
        verify(service.state).touch();
    }

    // --- loadHistory ---

    /**
     * {@code loadHistory()} loads the closed tickets, most recent first, into the
     * reprint state and touches the session.
     */
    @Test
    void loadHistoryLoadsClosedTicketsAndTouches() {
        ReprintService service = newService();
        List<Ticket> tickets = List.of(mock(Ticket.class), mock(Ticket.class));
        @SuppressWarnings("unchecked")
        PanacheQuery<Ticket> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(tickets);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.find("status = ?1 ORDER BY creationDate DESC", Ticket.TicketStatus.CLOSED))
                    .thenReturn(query);
            service.loadHistory();
        }
        verify(service.state.reprint).setTickets(tickets);
        verify(service.state).touch();
    }

    // --- print ---

    /**
     * {@code print()} refuses in training mode: it flags the error, touches the
     * session and never prints (training-mode guard true).
     */
    @Test
    void printRefusedInTrainingMode() {
        ReprintService service = newService();
        service.state.trainingMode = true;
        service.print(42L);
        verify(service.state.ticket).setError("RÉIMPRESSION INDISPONIBLE EN FORMATION");
        verify(service.state).touch();
        verifyNoInteractions(service.ticketPrinterService);
    }

    /**
     * {@code print()} delegates to the printer for a non-null id outside training
     * mode (training-mode guard false, null-id guard false).
     */
    @Test
    void printDelegatesForNonNullId() {
        ReprintService service = newService();
        service.state.trainingMode = false;
        service.print(42L);
        verify(service.ticketPrinterService).printTicket(42L);
        verify(service.state.ticket, never()).setError(any());
        verify(service.state, never()).touch();
    }

    /**
     * {@code print()} prints nothing for a null id outside training mode
     * (training-mode guard false, null-id guard true).
     */
    @Test
    void printIgnoresNullId() {
        ReprintService service = newService();
        service.state.trainingMode = false;
        service.print(null);
        verifyNoInteractions(service.ticketPrinterService);
        verify(service.state.ticket, never()).setError(any());
        verify(service.state, never()).touch();
    }

    /**
     * LC-03-06-02: entering the reassignment mode loads ONLY the closed
     * tickets no client is attached to, and the mode stays armed.
     */
    @Test
    void startReassignModeLoadsOnlyTheUnassignedTickets() {
        ReprintService service = newService();
        List<Ticket> pool = List.of(mock(Ticket.class));
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mock(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            io.quarkus.hibernate.orm.panache.PanacheQuery<com.intermarche.pos.domain.sale.Ticket> q =
                    mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
            when(q.list()).thenReturn(pool);
            panache.when(() -> com.intermarche.pos.domain.sale.Ticket.find(anyString(), any(Object[].class)))
                    .thenReturn(q);
            service.startReassignMode();
        }
        assertTrue(service.state.reprint.reassignMode);
        verify(service.state.reprint).setTickets(pool);
    }

    /**
     * LC-03-06-02: leaving the reassignment mode restores the ordinary
     * duplicata history and disarms the mode.
     */
    @Test
    void cancelReassignModeRestoresTheOrdinaryHistory() {
        ReprintService service = newService();
        service.state.reprint.reassignMode = true;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mock(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            io.quarkus.hibernate.orm.panache.PanacheQuery<com.intermarche.pos.domain.sale.Ticket> q =
                    mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
            when(q.list()).thenReturn(List.of());
            panache.when(() -> com.intermarche.pos.domain.sale.Ticket.find(anyString(), any(Object[].class)))
                    .thenReturn(q);
            service.cancelReassignMode();
        }
        assertFalse(service.state.reprint.reassignMode);
        verify(service.state.reprint).setTickets(List.of());
    }

    /**
     * LC-03-06-02: a pattern searched in the reassignment mode keeps only
     * the non-affected matches — the pool is the pool whatever found it.
     */
    @Test
    void searchInReassignModeKeepsOnlyTheUnassignedMatches() {
        ReprintService service = newService();
        service.state.reprint.reassignMode = true;
        List<Ticket> pool = List.of(mock(Ticket.class));
        when(service.ticketSearchService.searchByPattern("C04-T", true)).thenReturn(pool);
        service.search("C04-T");
        verify(service.state.reprint).setTickets(pool);
        verify(service.ticketSearchService, never()).searchByPattern("C04-T");
    }

    /**
     * LC-03-06-02: a BLANK pattern in the reassignment mode reloads the whole
     * pool rather than the ordinary history.
     */
    @Test
    void searchBlankInReassignModeReloadsThePool() {
        ReprintService service = newService();
        service.state.reprint.reassignMode = true;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     mock(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            io.quarkus.hibernate.orm.panache.PanacheQuery<com.intermarche.pos.domain.sale.Ticket> q =
                    mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
            when(q.list()).thenReturn(List.of());
            panache.when(() -> com.intermarche.pos.domain.sale.Ticket.find(anyString(), any(Object[].class)))
                    .thenReturn(q);
            service.search("   ");
        }
        assertTrue(service.state.reprint.reassignMode);
        verifyNoInteractions(service.ticketSearchService);
    }

    /**
     * LC-03-06-01: an authorized reassignment runs the chained gesture at
     * once — the endorsement is never asked.
     */
    @Test
    void applyReassignAuthorizedRunsTheChainedGesture() {
        ReprintService service = newService();
        when(service.endorsementService.gestureAuthorized(service.state, "ticket-reassign"))
                .thenReturn(true);
        service.applyReassign(1L, "2990000000019");
        verify(service.reassignmentService).perform(1L, "2990000000019");
        verify(service.endorsementService, never()).requestAuthorization(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * LC-03-06-01: a reassignment the store demands an aval for parks the
     * gesture's form — ticket and card — with its return path, and the
     * chained gesture runs only once a manager has answered.
     */
    @Test
    void applyReassignParksTheFormWhenTheAvalIsDemanded() {
        ReprintService service = newService();
        when(service.endorsementService.gestureAuthorized(service.state, "ticket-reassign"))
                .thenReturn(false);
        service.applyReassign(1L, "2990000000019");
        verifyNoInteractions(service.reassignmentService);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Map<String, String>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(service.endorsementService).requestAuthorization(
                org.mockito.ArgumentMatchers.eq(service.state),
                org.mockito.ArgumentMatchers.eq("TICKET_REASSIGN"),
                captor.capture(),
                org.mockito.ArgumentMatchers.eq("/reprint/view/1"));
        java.util.Map<String, String> parked = captor.getValue();
        assertEquals("1", parked.get("ticketId"));
        assertEquals("2990000000019", parked.get("card"));
    }
}
