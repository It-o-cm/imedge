package com.intermarche.pos.ui.reprintticket;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import com.intermarche.pos.ui.auth.AuthState;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.valuation.ValuationReconciler;
import com.intermarche.pos.ui.valuation.ValuationService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketReassignmentService}, the chained gesture of
 * LC-03-06-01 to -06.
 * <p>
 * The state graph is exercised on a REAL {@link ReprintState} so every
 * outcome assertion reads the fields the screen renders, while the
 * collaborators — engine call, reconciler, journal, store push, printer — are
 * mocks. {@code Ticket.findById} resolves to {@link PanacheEntityBase} under
 * plain {@code mvn test} and is intercepted with {@code mockStatic}. Every
 * guard of the gesture, every valuation outcome and the administered duplicata
 * are covered, refusal legs included.
 */
class TicketReassignmentServiceTest {

    /** The card a closed ticket is reassigned to. */
    private static final String CARD = "2990000000019";

    /**
     * Builds a service with a real reprint state and mocked collaborators.
     *
     * @return a service whose outcomes are readable on its state
     */
    private TicketReassignmentService newService() {
        TicketReassignmentService service = new TicketReassignmentService();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        service.i18n = TestMessages.french();
        service.state = mock(PosState.class);
        service.state.reprint = new ReprintState();
        service.state.auth = mock(AuthState.class);
        service.posSettingsService = mock(PosSettingsService.class);
        service.ticketNumberService = mock(TicketNumberService.class);
        when(service.ticketNumberService.getTerminalId()).thenReturn("C04");
        service.technicalEventService = mock(TechnicalEventService.class);
        service.syncOutboxService = mock(SyncOutboxService.class);
        service.valuationService = mock(ValuationService.class);
        service.valuationReconciler = mock(ValuationReconciler.class);
        service.ticketPrinterService = mock(TicketPrinterService.class);
        return service;
    }

    /**
     * Builds a closed, non-affected ticket — a Mockito mock, so its
     * {@code persist()} is a no-op under the plain test just as the recovery
     * tests model it.
     *
     * @param id the database id
     * @return the ticket offered to the reassignment
     */
    private Ticket closedTicket(Long id) {
        Ticket ticket = mock(Ticket.class);
        ticket.id = id;
        ticket.status = Ticket.TicketStatus.CLOSED;
        ticket.ticketNumber = "C04-00000001";
        ticket.creationDate = LocalDateTime.of(2026, 10, 17, 10, 0);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.lines = new java.util.ArrayList<>();
        return ticket;
    }

    /**
     * LC-03-06-01: training refuses the gesture — a training session must
     * never rewrite a real closed ticket's advantages nor bump its print
     * counter, both fiscal records.
     */
    @Test
    void trainingRefusesTheGesture() {
        TicketReassignmentService service = newService();
        service.state.trainingMode = true;
        service.perform(1L, CARD);
        assertEquals("RÉAFFECTATION INDISPONIBLE EN FORMATION",
                service.state.reprint.reassignError);
        assertTrue(service.state.reprint.reassignMaskOpen);
        verifyNoInteractions(service.valuationService);
        verifyNoInteractions(service.ticketPrinterService);
    }

    /**
     * LC-03-06-03: a blank card number is refused with its own sentence —
     * nothing is looked up, nothing is recalculated.
     */
    @Test
    void aBlankCardIsRefused() {
        TicketReassignmentService service = newService();
        service.perform(1L, "   ");
        assertEquals("NUMÉRO DE CARTE FIDÉLITÉ REQUIS",
                service.state.reprint.reassignError);
        verifyNoInteractions(service.valuationService);
        verifyNoInteractions(service.ticketPrinterService);
    }

    /**
     * LC-03-06-01: a ticket nobody can find is refused by name.
     */
    @Test
    void anUnknownTicketIsRefused() {
        TicketReassignmentService service = newService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(1L)).thenReturn(null);
            service.perform(1L, CARD);
        }
        assertEquals("TICKET INTROUVABLE", service.state.reprint.reassignError);
        verifyNoInteractions(service.valuationService);
        verifyNoInteractions(service.ticketPrinterService);
    }

    /**
     * LC-03-06-01: a ticket that is not CLOSED is refused — the reassignment
     * answers for settled money, and an open draft is not it.
     */
    @Test
    void aNonClosedTicketIsRefused() {
        TicketReassignmentService service = newService();
        Ticket open = closedTicket(1L);
        open.status = Ticket.TicketStatus.OPEN;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(1L)).thenReturn(open);
            service.perform(1L, CARD);
        }
        assertEquals("SEULS LES TICKETS CLÔTURÉS SE RÉAFFECTENT",
                service.state.reprint.reassignError);
        verifyNoInteractions(service.valuationService);
        verifyNoInteractions(service.ticketPrinterService);
    }

    /**
     * LC-03-06-02: a ticket a client is ALREADY attached to is refused —
     * only the non-affected ones are offered, and the service holds that line
     * even when a stale page posts one.
     */
    @Test
    void anAlreadyAffectedTicketIsRefused() {
        TicketReassignmentService service = newService();
        Ticket affected = closedTicket(1L);
        affected.fidelityCard = "2990000000099";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(1L)).thenReturn(affected);
            service.perform(1L, CARD);
        }
        assertEquals("TICKET DÉJÀ AFFECTÉ À UN CLIENT",
                service.state.reprint.reassignError);
        verifyNoInteractions(service.valuationService);
        verifyNoInteractions(service.ticketPrinterService);
    }

    /**
     * LC-03-06-04, the engine arm: the advantages are recalculated with the
     * card, the reconciler writes them onto the stored ticket, and a zero
     * adjustment prints the gesture's own success word.
     */
    @Test
    void anEngineRecalculationWithNoGapReassignsAndPrints() {
        TicketReassignmentService service = newService();
        Ticket ticket = closedTicket(1L);
        when(service.valuationService.valuate(any(), eq(CARD), any())).thenReturn(
                new ValuationService.ValuationOutcome("ENGINE", "{}", new BigDecimal("12.00")));
        when(service.valuationReconciler.apply(any(), eq(1L), eq("{}")))
                .thenReturn(BigDecimal.ZERO);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            service.perform(1L, CARD);
        }
        assertEquals(CARD, ticket.fidelityCard);
        verify(service.syncOutboxService).enqueue(SyncOutbox.EntityType.TICKET, 1L);
        verify(service.technicalEventService).log(
                eq(TechnicalEvent.EventType.TICKET_REASSIGNED), anyString(), any());
        verify(service.ticketPrinterService, times(1)).printTicket(1L);
        assertEquals("RÉAFFECTATION EFFECTUÉE", service.state.reprint.reassignError);
        assertFalse(service.state.reprint.reassignMaskOpen);
        assertEquals("", service.state.reprint.reassignCard);
        assertSame(ticket, service.state.reprint.viewedTicket);
    }

    /**
     * LC-03-06-04: a recalculation that MOVES the money announces its gap —
     * the settled amount is never silently rewritten, and the operator reads
     * the difference in euros with the advantages' word.
     */
    @Test
    void aRecalculationWithAGapAnnouncesIt() {
        TicketReassignmentService service = newService();
        Ticket ticket = closedTicket(1L);
        when(service.valuationService.valuate(any(), eq(CARD), any())).thenReturn(
                new ValuationService.ValuationOutcome("ENGINE", "{}", new BigDecimal("10.00")));
        when(service.valuationReconciler.apply(any(), eq(1L), eq("{}")))
                .thenReturn(new BigDecimal("-2.00"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            service.perform(1L, CARD);
        }
        assertEquals(CARD, ticket.fidelityCard);
        assertEquals("AVANTAGES RECALCULÉS — ÉCART DE -2.00 €",
                service.state.reprint.reassignError);
        verify(service.ticketPrinterService, times(1)).printTicket(1L);
    }

    /**
     * LC-03-06-05: the shop's duplicata is administered — the customer's new
     * ticket prints, and the point de vente's copy prints after it.
     */
    @Test
    void theAdministeredPdvDuplicatePrintsACopy() {
        TicketReassignmentService service = newService();
        when(service.posSettingsService.reassignPdvDuplicate()).thenReturn(true);
        Ticket ticket = closedTicket(1L);
        when(service.valuationService.valuate(any(), eq(CARD), any())).thenReturn(
                new ValuationService.ValuationOutcome("LOCAL", null, null));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            service.perform(1L, CARD);
        }
        verify(service.ticketPrinterService, times(2)).printTicket(1L);
    }

    /**
     * LC-03-06-04, the degraded arm: an engine that does not answer must not
     * block the reassignment — the card attaches, the operation is journaled
     * and printed, and the outcome names the recalculation that did not
     * happen rather than staying silent about it.
     */
    @Test
    void aDegradedEngineReassignsWithoutARecalculation() {
        TicketReassignmentService service = newService();
        Ticket ticket = closedTicket(1L);
        when(service.valuationService.valuate(any(), eq(CARD), any())).thenReturn(
                new ValuationService.ValuationOutcome("DEGRADED", null, null));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            service.perform(1L, CARD);
        }
        assertEquals(CARD, ticket.fidelityCard);
        verifyNoInteractions(service.valuationReconciler);
        assertEquals("RÉAFFECTÉE SANS RECALCUL — MOTEUR DE VALORISATION INDISPONIBLE",
                service.state.reprint.reassignError);
        verify(service.ticketPrinterService, times(1)).printTicket(1L);
    }

    /**
     * LC-03-06-04, the local arm: a basket with nothing to price is
     * reassigned with no recalculation and the plain success word — the
     * LOCAL outcome is the ordinary answer of an empty or ineligible cart.
     */
    @Test
    void aLocalOutcomeReassignsWithThePlainSuccessWord() {
        TicketReassignmentService service = newService();
        Ticket ticket = closedTicket(1L);
        when(service.valuationService.valuate(any(), eq(CARD), any())).thenReturn(
                new ValuationService.ValuationOutcome("LOCAL", null, null));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            service.perform(1L, CARD);
        }
        verifyNoInteractions(service.valuationReconciler);
        assertEquals("RÉAFFECTATION EFFECTUÉE", service.state.reprint.reassignError);
    }

    /**
     * LC-03-06-06: the journal event carries the WHOLE characteristic set
     * the line names — the card, the amount, the advantages, the till and
     * the operator — and travels to the node on the EVENT push.
     */
    @Test
    void theJournalEventCarriesTheWholeCharacteristicSet() {
        TicketReassignmentService service = newService();
        service.state.auth.operatorBadgeId = "12341234";
        Ticket ticket = closedTicket(1L);
        when(service.valuationService.valuate(any(), eq(CARD), any())).thenReturn(
                new ValuationService.ValuationOutcome("ENGINE", "{}", new BigDecimal("10.00")));
        when(service.valuationReconciler.apply(any(), eq(1L), eq("{}")))
                .thenReturn(new BigDecimal("-2.00"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(1L)).thenReturn(ticket);
            service.perform(1L, CARD);
        }
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(service.technicalEventService).log(
                eq(TechnicalEvent.EventType.TICKET_REASSIGNED), detail.capture(), eq("12341234"));
        assertTrue(detail.getValue().contains("carte=" + CARD), detail.getValue());
        assertTrue(detail.getValue().contains("montant=12.00"), detail.getValue());
        assertTrue(detail.getValue().contains("avantages=-2.00"), detail.getValue());
        assertTrue(detail.getValue().contains("caisse=C04"), detail.getValue());
        assertNull(service.state.reprint.reassignCard);
    }
}
