package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.*;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TicketParkingService}.
 * <p>
 * The service owns no persistence of its own: it reads and writes the draft
 * through Panache active-record static access ({@code Ticket.findById},
 * {@code Ticket.list}), all intercepted with {@link org.mockito.Mockito#mockStatic}
 * on {@link PanacheEntityBase}; the drafts themselves are Mockito mocks whose
 * {@code persist()} is a no-op and whose public fields are read and written
 * directly. No database and no Quarkus context are booted. The injected
 * {@link PosState} is a real instance so the in-memory ticket, payment and
 * version behave exactly as in production, while the four collaborators
 * ({@link TicketPersistenceService}, {@link TicketRecoveryService},
 * {@link TicketNumberService}, {@link TechnicalEventService}) are Mockito mocks
 * assigned to the package-private injection fields. Every branch of the three
 * public methods is covered: parking (empty cart, payment in progress, paid
 * amount present, sync failure, draft missing, draft not OPEN, success), the
 * parked listing, and resuming (cart in progress, draft missing, draft not
 * PARKED, foreign terminal, success).
 */
class TicketParkingServiceTest {

    /** The terminal identifier used across the tests. */
    private static final String TERMINAL = "C04";

    /**
     * Builds a service instance with a real {@link PosState} and the four
     * collaborators mocked, with the terminal id resolved through the ticket
     * number service.
     *
     * @return a ready-to-use service with a real state and mocked collaborators
     */
    private TicketParkingService newService() {
        TicketParkingService service = new TicketParkingService();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        service.i18n = TestMessages.french();
        service.state = new PosState();
        service.ticketPersistenceService = mock(TicketPersistenceService.class);
        service.ticketRecoveryService = mock(TicketRecoveryService.class);
        service.ticketNumberService = mock(TicketNumberService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        service.ticketPrinterService = mock(TicketPrinterService.class);
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.parkingPrintReceipt()).thenReturn(true);
        // EXP-46: the inter-till seam defaults to the OFFLINE stub (no node) — it
        // reaches nothing, so a number not parked locally stays "introuvable".
        service.interTillResumeClient = mock(InterTillResumeClient.class);
        when(service.ticketNumberService.getTerminalId()).thenReturn(TERMINAL);
        return service;
    }

    /**
     * Appends one empty line to the given state's ticket so that the cart is
     * no longer considered empty.
     *
     * @param service the service whose in-memory ticket must be filled
     */
    private void addOneItem(TicketParkingService service) {
        service.state.ticket.items.add(new TicketState.TicketItem());
    }

    /**
     * Creates a mocked draft with the given status, ticket number and terminal.
     *
     * @param status the lifecycle status of the draft
     * @param ticketNumber the portable ticket number
     * @param terminalId the owning register id
     * @return the configured mocked draft
     */
    private Ticket draft(Ticket.TicketStatus status, String ticketNumber, String terminalId) {
        Ticket draft = mock(Ticket.class);
        draft.id = 42L;
        draft.status = status;
        draft.ticketNumber = ticketNumber;
        draft.terminalId = terminalId;
        return draft;
    }

    /**
     * Covers the empty-cart guard of {@code parkCurrent}: the parking is refused
     * and no draft is synchronized, journaled or cleared.
     */
    @Test
    void parkCurrentRefusedWhenCartEmpty() {
        TicketParkingService service = newService();
        assertEquals("AUCUN TICKET À METTRE EN ATTENTE", service.parkCurrent());
        verifyNoInteractions(service.ticketPersistenceService);
        verifyNoInteractions(service.technicalEventService);
    }

    /**
     * Covers the first arm of the payment guard of {@code parkCurrent}: a
     * payment is in progress, so parking is refused before any sync.
     */
    @Test
    void parkCurrentRefusedWhenPaymentInProgress() {
        TicketParkingService service = newService();
        addOneItem(service);
        service.state.payment.paymentInProgress = true;
        assertEquals("PAIEMENT EN COURS - MISE EN ATTENTE IMPOSSIBLE", service.parkCurrent());
        verifyNoInteractions(service.ticketPersistenceService);
    }

    /**
     * Covers the second arm of the payment guard of {@code parkCurrent}: no
     * payment is in progress but a positive amount is already paid, so parking
     * is refused before any sync.
     */
    @Test
    void parkCurrentRefusedWhenAmountAlreadyPaid() {
        TicketParkingService service = newService();
        addOneItem(service);
        service.state.payment.paymentInProgress = false;
        service.state.payment.paidAmount = new BigDecimal("10.00");
        assertEquals("PAIEMENT EN COURS - MISE EN ATTENTE IMPOSSIBLE", service.parkCurrent());
        verifyNoInteractions(service.ticketPersistenceService);
    }

    /**
     * Covers the sync-failure arm of {@code parkCurrent}: the draft sync returns
     * null (training mode), so parking answers the synchronization error.
     */
    @Test
    void parkCurrentRefusedWhenSyncReturnsNull() {
        TicketParkingService service = newService();
        addOneItem(service);
        when(service.ticketPersistenceService.syncDraft(service.state)).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            // EXP-45: parkCurrent now checks listParked() first — no ticket parked.
            mocked.when(() -> Ticket.list(anyString(), any(Object[].class)))
                    .thenReturn(java.util.List.of());
            assertEquals("SYNCHRONISATION IMPOSSIBLE", service.parkCurrent());
        }
        verifyNoInteractions(service.technicalEventService);
    }

    /**
     * Covers the EXP-45 single-parked guard: a ticket is already parked, so a
     * second park is refused before any sync.
     */
    @Test
    void parkCurrentRefusedWhenOneAlreadyParked() {
        TicketParkingService service = newService();
        addOneItem(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list(anyString(), any(Object[].class)))
                    .thenReturn(java.util.List.of(mock(Ticket.class)));
            assertEquals("UN TICKET EST DÉJÀ EN ATTENTE - REPRENEZ-LE D'ABORD",
                    service.parkCurrent());
        }
        verifyNoInteractions(service.ticketPersistenceService);
        verifyNoInteractions(service.technicalEventService);
    }

    /**
     * Covers the missing-draft arm of {@code parkCurrent}: the sync yields an id
     * but the finder resolves to null, so parking answers the not-found error.
     */
    @Test
    void parkCurrentRefusedWhenDraftNotFound() {
        TicketParkingService service = newService();
        addOneItem(service);
        when(service.ticketPersistenceService.syncDraft(service.state)).thenReturn(7L);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list(anyString(), any(Object[].class))).thenReturn(java.util.List.of());
            mocked.when(() -> Ticket.findById(7L)).thenReturn(null);
            assertEquals("TICKET INTROUVABLE", service.parkCurrent());
            verifyNoInteractions(service.technicalEventService);
        }
    }

    /**
     * Covers the wrong-status arm of {@code parkCurrent}: the draft is found but
     * is not OPEN, so parking answers the not-found error and nothing is flipped
     * or journaled.
     */
    @Test
    void parkCurrentRefusedWhenDraftNotOpen() {
        TicketParkingService service = newService();
        addOneItem(service);
        when(service.ticketPersistenceService.syncDraft(service.state)).thenReturn(7L);
        Ticket draft = draft(Ticket.TicketStatus.PARKED, "C04-000001", TERMINAL);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list(anyString(), any(Object[].class))).thenReturn(java.util.List.of());
            mocked.when(() -> Ticket.findById(7L)).thenReturn(draft);
            assertEquals("TICKET INTROUVABLE", service.parkCurrent());
            verify(draft, never()).persist();
            verifyNoInteractions(service.technicalEventService);
        }
    }

    /**
     * Covers the nominal parking of {@code parkCurrent}: the OPEN draft is
     * flipped to PARKED, persisted, journaled, and the in-memory state is
     * cleared and touched.
     */
    @Test
    void parkCurrentSucceeds() {
        TicketParkingService service = newService();
        addOneItem(service);
        long versionBefore = service.state.version;
        when(service.ticketPersistenceService.syncDraft(service.state)).thenReturn(7L);
        Ticket draft = draft(Ticket.TicketStatus.OPEN, "C04-000001", TERMINAL);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list(anyString(), any(Object[].class))).thenReturn(java.util.List.of());
            mocked.when(() -> Ticket.findById(7L)).thenReturn(draft);
            assertNull(service.parkCurrent());
            assertEquals(Ticket.TicketStatus.PARKED, draft.status);
            verify(draft, times(1)).persist();
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.TICKET_PARKED, "C04-000001");
            // LC-04-01-02: the parked receipt goes out with the resume number.
            verify(service.ticketPrinterService).printParkedTicket(draft);
            assertTrue(service.state.ticket.items.isEmpty());
            assertEquals(versionBefore + 2, service.state.version);
        }
    }

    /**
     * Verifies that {@code listParked} returns the terminal-scoped, oldest-first
     * parked listing produced by the finder.
     */
    @Test
    void listParkedReturnsFinderResult() {
        TicketParkingService service = newService();
        Ticket parked = mock(Ticket.class);
        List<Ticket> expected = List.of(parked);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("terminalId = ?1 and status = ?2 order by id",
                    TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(expected);
            assertSame(expected, service.listParked());
        }
    }

    /**
     * Covers the in-progress-cart guard of {@code resume}: a cart is already
     * open, so the resume is refused and no draft is looked up.
     */
    @Test
    void resumeRefusedWhenCartInProgress() {
        TicketParkingService service = newService();
        addOneItem(service);
        assertEquals("TICKET EN COURS - METTEZ-LE EN ATTENTE D'ABORD", service.resume(7L));
        verifyNoInteractions(service.ticketRecoveryService);
        verifyNoInteractions(service.technicalEventService);
    }

    /**
     * Covers the missing-draft arm of {@code resume}: the finder resolves to
     * null, so the resume answers the not-found error.
     */
    @Test
    void resumeRefusedWhenDraftNotFound() {
        TicketParkingService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list(anyString(), any(Object[].class))).thenReturn(java.util.List.of());
            mocked.when(() -> Ticket.findById(7L)).thenReturn(null);
            assertEquals("TICKET EN ATTENTE INTROUVABLE", service.resume(7L));
            verifyNoInteractions(service.ticketRecoveryService);
        }
    }

    /**
     * Covers the wrong-status arm of {@code resume}: the draft is found but is
     * not PARKED, so the resume answers the not-found error and nothing is
     * restored.
     */
    @Test
    void resumeRefusedWhenDraftNotParked() {
        TicketParkingService service = newService();
        Ticket draft = draft(Ticket.TicketStatus.OPEN, "C04-000001", TERMINAL);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list(anyString(), any(Object[].class))).thenReturn(java.util.List.of());
            mocked.when(() -> Ticket.findById(7L)).thenReturn(draft);
            assertEquals("TICKET EN ATTENTE INTROUVABLE", service.resume(7L));
            verify(draft, never()).persist();
            verifyNoInteractions(service.ticketRecoveryService);
        }
    }

    /**
     * Covers the foreign-terminal arm of {@code resume}: the draft is PARKED but
     * belongs to another register, so the resume answers the not-found error.
     */
    @Test
    void resumeRefusedWhenDraftOnOtherTerminal() {
        TicketParkingService service = newService();
        Ticket draft = draft(Ticket.TicketStatus.PARKED, "C04-000001", "C09");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list(anyString(), any(Object[].class))).thenReturn(java.util.List.of());
            mocked.when(() -> Ticket.findById(7L)).thenReturn(draft);
            assertEquals("TICKET EN ATTENTE INTROUVABLE", service.resume(7L));
            verifyNoInteractions(service.ticketRecoveryService);
        }
    }

    /**
     * Covers the nominal resume of {@code resume}: the PARKED draft of this
     * register is flipped back to OPEN, persisted, restored into the state and
     * journaled.
     */
    @Test
    void resumeSucceeds() {
        TicketParkingService service = newService();
        Ticket draft = draft(Ticket.TicketStatus.PARKED, "C04-000001", TERMINAL);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list(anyString(), any(Object[].class))).thenReturn(java.util.List.of());
            mocked.when(() -> Ticket.findById(7L)).thenReturn(draft);
            assertNull(service.resume(7L));
            assertEquals(Ticket.TicketStatus.OPEN, draft.status);
            verify(draft, times(1)).persist();
            verify(service.ticketRecoveryService).restoreDraft(draft);
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.TICKET_RESUMED, "C04-000001");
        }
    }

    /**
     * BO-03-03-07: a local resume prints its own document, which states the
     * resumed number as BOTH numbers — the ticket keeps the number the parked
     * paper carried.
     */
    @Test
    void resumePrintsTheResumeDocument() {
        TicketParkingService service = newService();
        Ticket draft = draft(Ticket.TicketStatus.PARKED, "C04-000001", TERMINAL);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list(anyString(), any(Object[].class)))
                    .thenReturn(java.util.List.of());
            mocked.when(() -> Ticket.findById(7L)).thenReturn(draft);
            assertNull(service.resume(7L));
            verify(service.ticketPrinterService).printResumeTicket(draft, "C04-000001");
        }
    }

    /**
     * BO-03-03-07 with EXP-46: an inter-till resume states the number the
     * PARKED paper carried, read before the ticket is re-homed — and that is
     * the number a dispute two hours later turns on.
     */
    @Test
    void resumeForeignPrintsTheParkedNumberItCameWith() {
        TicketParkingService service = newService();
        Ticket foreign = draft(Ticket.TicketStatus.PARKED, "C09-000009", "C09");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Ticket> none = query(null);
            when(service.interTillResumeClient.fetchParkedTicket("C09-000009")).thenReturn(foreign);
            mocked.when(() -> Ticket.find("ticketNumber = ?1 and terminalId = ?2 and status = ?3",
                    "C09-000009", TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(none);
            assertNull(service.resumeByNumber("C09-000009"));
            verify(service.ticketPrinterService).printResumeTicket(foreign, "C09-000009");
        }
    }

    /**
     * Covers the print-disabled arm of {@code parkCurrent}: with the parking
     * receipt parameter off, the OPEN draft is parked but nothing is printed.
     */
    @Test
    void parkCurrentDoesNotPrintWhenReceiptDisabled() {
        TicketParkingService service = newService();
        when(service.posSettingsService.parkingPrintReceipt()).thenReturn(false);
        addOneItem(service);
        when(service.ticketPersistenceService.syncDraft(service.state)).thenReturn(7L);
        Ticket draft = draft(Ticket.TicketStatus.OPEN, "C04-000001", TERMINAL);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list(anyString(), any(Object[].class))).thenReturn(java.util.List.of());
            mocked.when(() -> Ticket.findById(7L)).thenReturn(draft);
            assertNull(service.parkCurrent());
            assertEquals(Ticket.TicketStatus.PARKED, draft.status);
            verify(service.ticketPrinterService, never()).printParkedTicket(draft);
        }
    }

    /**
     * Builds a Panache query whose {@code firstResult} resolves to the draft.
     *
     * @param draft the draft the query returns, or null
     * @return the mocked query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<Ticket> query(Ticket draft) {
        PanacheQuery<Ticket> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(draft);
        return query;
    }

    /**
     * Covers the not-found arm of {@code resumeByNumber}: no PARKED draft
     * carries the number on this terminal, so the resume-by-number answers the
     * not-found message.
     */
    @Test
    void resumeByNumberNotFound() {
        TicketParkingService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Ticket> none = query(null);
            mocked.when(() -> Ticket.find("ticketNumber = ?1 and terminalId = ?2 and status = ?3",
                    "C04-000001", TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(none);
            assertEquals("TICKET EN ATTENTE INTROUVABLE", service.resumeByNumber("C04-000001"));
            verifyNoInteractions(service.ticketRecoveryService);
        }
    }

    /**
     * Covers the terminal scoping of {@code resumeByNumber}: the lookup is
     * bound to THIS register's terminal id, so a foreign ticket cannot be
     * resumed by number (implicit wrong-terminal arm — the finder never
     * matches).
     */
    @Test
    void resumeByNumberIsTerminalScoped() {
        TicketParkingService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Ticket> none = query(null);
            mocked.when(() -> Ticket.find("ticketNumber = ?1 and terminalId = ?2 and status = ?3",
                    "C04-000001", TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(none);
            service.resumeByNumber("C04-000001");
            mocked.verify(() -> Ticket.find("ticketNumber = ?1 and terminalId = ?2 and status = ?3",
                    "C04-000001", TERMINAL, Ticket.TicketStatus.PARKED));
        }
    }

    /**
     * Covers the success arm of {@code resumeByNumber}: the PARKED draft is
     * found by number on this terminal and delegated to {@code resume}, which
     * flips it OPEN, restores it and journals the resume.
     */
    @Test
    void resumeByNumberSucceeds() {
        TicketParkingService service = newService();
        Ticket draft = draft(Ticket.TicketStatus.PARKED, "C04-000001", TERMINAL);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Ticket> found = query(draft);
            mocked.when(() -> Ticket.find("ticketNumber = ?1 and terminalId = ?2 and status = ?3",
                    "C04-000001", TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(found);
            mocked.when(() -> Ticket.findById(42L)).thenReturn(draft);
            assertNull(service.resumeByNumber("C04-000001"));
            assertEquals(Ticket.TicketStatus.OPEN, draft.status);
            verify(draft, times(1)).persist();
            verify(service.ticketRecoveryService).restoreDraft(draft);
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.TICKET_RESUMED, "C04-000001");
        }
    }

    /**
     * Covers the CONNECTED inter-till arm of {@code resumeByNumber} (EXP-46): the
     * number is not parked on this till, so the store-node client hands over a
     * ticket parked on ANOTHER till; it is re-homed to this register, flipped
     * OPEN, restored and journaled.
     */
    @Test
    void resumeByNumberResumesForeignTicketWhenNotLocal() {
        TicketParkingService service = newService();
        Ticket foreign = draft(Ticket.TicketStatus.PARKED, "C09-000009", "C09");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Ticket> none = query(null);
            when(service.interTillResumeClient.fetchParkedTicket("C09-000009")).thenReturn(foreign);
            mocked.when(() -> Ticket.find("ticketNumber = ?1 and terminalId = ?2 and status = ?3",
                    "C09-000009", TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(none);
            assertNull(service.resumeByNumber("C09-000009"));
            assertEquals(Ticket.TicketStatus.OPEN, foreign.status);
            assertEquals(TERMINAL, foreign.terminalId);
            verify(foreign, times(1)).persist();
            verify(service.ticketRecoveryService).restoreDraft(foreign);
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.TICKET_RESUMED, "C09-000009");
        }
    }

    /**
     * Covers the non-PARKED foreign arm: the node returns a ticket that is not
     * PARKED, so the inter-till resume answers the not-found message and touches
     * nothing.
     */
    @Test
    void resumeByNumberIgnoresNonParkedForeignTicket() {
        TicketParkingService service = newService();
        Ticket foreign = draft(Ticket.TicketStatus.CLOSED, "C09-000009", "C09");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Ticket> none = query(null);
            when(service.interTillResumeClient.fetchParkedTicket("C09-000009")).thenReturn(foreign);
            mocked.when(() -> Ticket.find("ticketNumber = ?1 and terminalId = ?2 and status = ?3",
                    "C09-000009", TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(none);
            assertEquals("TICKET EN ATTENTE INTROUVABLE", service.resumeByNumber("C09-000009"));
            verifyNoInteractions(service.ticketRecoveryService);
        }
    }

    /**
     * Covers the in-progress guard of {@code resumeForeign}: an inter-till resume
     * is refused while a cart is already in progress, like every resume.
     */
    @Test
    void resumeForeignRefusedWhenCartInProgress() {
        TicketParkingService service = newService();
        addOneItem(service);
        Ticket foreign = draft(Ticket.TicketStatus.PARKED, "C09-000009", "C09");
        assertEquals("TICKET EN COURS - METTEZ-LE EN ATTENTE D'ABORD",
                service.resumeForeign(foreign));
        verifyNoInteractions(service.ticketRecoveryService);
    }
}
