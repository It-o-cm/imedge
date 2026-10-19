package com.intermarche.pos.ui.order;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.service.CashMovementService;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.recall.BackOfficeDocumentClient;
import com.intermarche.pos.ui.recall.RecalledDocument;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderCreationService}, targeting 100% branch coverage.
 * <p>
 * Plain JUnit 5 + Mockito, no Quarkus and no database: the collaborators are
 * mocks, the state is a real {@link PosState} the tests fill by the ordinary
 * {@code addItem} funnel (LC-06-03-02), and every leg of every compound guard
 * of {@code create} has its own case — no session, training mode, the three
 * legs of the empty-ticket guard (null ticket, null items, no items), a null
 * and a non-positive deposit, a deposit over the total, and the refused
 * movement. The success path asserts the whole choreography of LC-06-03:
 * the movement carrying the order code (LC-06-03-04), the per-rate pro-rata
 * VAT detail (LC-06-03-05), the pushed document (LC-06-03-01), the printed
 * deposit ticket (LC-06-03-06), the cancelled draft and the cleared lane. The
 * cashier resolution covers both arms of the operator ternary. Each test is
 * fully isolated and asserts absolute expected values.
 */
class OrderCreationServiceTest {

    /**
     * The service under test with mocked collaborators, on a fresh state
     * carrying one 20%-rated article at 60.00 and one 5.5%-rated at 40.00 —
     * the 100.00 order the French pro-rata examples of LC-06-03-05 read on.
     *
     * @return the wired service and its state
     */
    private Wiring wired() {
        PosState state = new PosState();
        state.ticket.addItem("301", null, "LAIT", new BigDecimal("3.00"),
                new BigDecimal("20"), new BigDecimal("0.20"));
        state.ticket.addItem("302", null, "PAIN", new BigDecimal("40.00"),
                BigDecimal.ONE, new BigDecimal("0.055"));
        Wiring wiring = new Wiring();
        wiring.state = state;
        wiring.service.i18n = TestMessages.french();
        wiring.service.ticketNumberService = wiring.ticketNumberService;
        wiring.service.cashMovementService = wiring.cashMovementService;
        wiring.service.cashSessionService = wiring.cashSessionService;
        wiring.service.documentClient = wiring.documentClient;
        wiring.service.ticketPrinterService = wiring.ticketPrinterService;
        wiring.service.ticketPersistenceService = wiring.ticketPersistenceService;
        when(wiring.ticketNumberService.nextOrderCode()).thenReturn("C04-CMD000001");
        when(wiring.cashSessionService.getOpenSession()).thenReturn(new CashSession());
        when(wiring.cashMovementService.recordAcompte(any(), any(),
                any(BigDecimal.class), any())).thenReturn(mock(CashMovement.class));
        return wiring;
    }

    /**
     * The service, its state and its mocks, held together for one test.
     */
    private static class Wiring {

        /** The service under test. */
        final OrderCreationService service = new OrderCreationService();

        /** The register state the tests fill. */
        PosState state;

        /** The order-code numbering. */
        final TicketNumberService ticketNumberService = mock(TicketNumberService.class);

        /** The deposit-movement recorder. */
        final CashMovementService cashMovementService = mock(CashMovementService.class);

        /** The open-session reader. */
        final CashSessionService cashSessionService = mock(CashSessionService.class);

        /** The store-node order seam. */
        final BackOfficeDocumentClient documentClient = mock(BackOfficeDocumentClient.class);

        /** The deposit-ticket printer. */
        final TicketPrinterService ticketPrinterService = mock(TicketPrinterService.class);

        /** The draft persistence. */
        final TicketPersistenceService ticketPersistenceService = mock(TicketPersistenceService.class);
    }

    /**
     * create refuses when no session is open (first guard): nothing moves.
     */
    @Test
    void createRefusesWithoutSession() {
        Wiring wiring = wired();
        when(wiring.cashSessionService.getOpenSession()).thenReturn(null);
        OrderCreationService.Result result = wiring.service.create(wiring.state, new BigDecimal("10.00"));
        assertEquals(OrderCreationService.Outcome.NO_SESSION, result.outcome());
        assertNull(result.orderCode());
        verify(wiring.cashMovementService, never()).recordAcompte(any(), any(), any(), any());
    }

    /**
     * create refuses in training mode (second guard): no real document.
     */
    @Test
    void createRefusesInTrainingMode() {
        Wiring wiring = wired();
        wiring.state.trainingMode = true;
        OrderCreationService.Result result = wiring.service.create(wiring.state, new BigDecimal("10.00"));
        assertEquals(OrderCreationService.Outcome.TRAINING, result.outcome());
        verify(wiring.cashMovementService, never()).recordAcompte(any(), any(), any(), any());
    }

    /**
     * create refuses on a ticket with no items (third guard, empty arm).
     */
    @Test
    void createRefusesOnEmptyTicket() {
        Wiring wiring = wired();
        wiring.state.ticket.items.clear();
        OrderCreationService.Result result = wiring.service.create(wiring.state, new BigDecimal("10.00"));
        assertEquals(OrderCreationService.Outcome.EMPTY_TICKET, result.outcome());
    }

    /**
     * create refuses on a ticket whose item list is null (third guard, null
     * arm).
     */
    @Test
    void createRefusesOnNullItems() {
        Wiring wiring = wired();
        wiring.state.ticket.items = null;
        OrderCreationService.Result result = wiring.service.create(wiring.state, new BigDecimal("10.00"));
        assertEquals(OrderCreationService.Outcome.EMPTY_TICKET, result.outcome());
    }

    /**
     * create refuses on a state that carries no ticket at all (third guard,
     * null-ticket arm — defensive, the register state always holds one).
     */
    @Test
    void createRefusesOnNullTicket() {
        Wiring wiring = wired();
        wiring.state.ticket = null;
        OrderCreationService.Result result = wiring.service.create(wiring.state, new BigDecimal("10.00"));
        assertEquals(OrderCreationService.Outcome.EMPTY_TICKET, result.outcome());
    }

    /**
     * create refuses on a null deposit (fourth guard, null arm).
     */
    @Test
    void createRefusesOnNullDeposit() {
        Wiring wiring = wired();
        OrderCreationService.Result result = wiring.service.create(wiring.state, null);
        assertEquals(OrderCreationService.Outcome.BAD_AMOUNT, result.outcome());
    }

    /**
     * create refuses on a non-positive deposit (fourth guard, zero and
     * negative arms).
     */
    @Test
    void createRefusesOnNonPositiveDeposit() {
        Wiring wiring = wired();
        assertEquals(OrderCreationService.Outcome.BAD_AMOUNT,
                wiring.service.create(wiring.state, BigDecimal.ZERO).outcome());
        assertEquals(OrderCreationService.Outcome.BAD_AMOUNT,
                wiring.service.create(wiring.state, new BigDecimal("-0.01")).outcome());
    }

    /**
     * create refuses on a deposit exceeding the order total (fifth guard).
     */
    @Test
    void createRefusesOnDepositOverTotal() {
        Wiring wiring = wired();
        OrderCreationService.Result result = wiring.service.create(wiring.state, new BigDecimal("100.01"));
        assertEquals(OrderCreationService.Outcome.OVER_TOTAL, result.outcome());
        verify(wiring.cashMovementService, never()).recordAcompte(any(), any(), any(), any());
    }

    /**
     * create refuses when the deposit movement itself is refused (the null
     * return of {@code recordAcompte}): nothing is pushed, printed or cleared.
     */
    @Test
    void createRefusesWhenMovementRefused() {
        Wiring wiring = wired();
        when(wiring.cashMovementService.recordAcompte(any(), any(),
                any(BigDecimal.class), any())).thenReturn(null);
        OrderCreationService.Result result = wiring.service.create(wiring.state, new BigDecimal("10.00"));
        assertEquals(OrderCreationService.Outcome.BAD_AMOUNT, result.outcome());
        verify(wiring.documentClient, never()).pushOrder(any());
        verify(wiring.ticketPrinterService, never()).printOrderDepositTicket(any(), any(), any(), any());
        assertEquals(2, wiring.state.ticket.items.size());
    }

    /**
     * create with a logged-in operator resolves the cashier by their id and
     * hands the employee to the movement (ternary true arm).
     */
    @Test
    void createResolvesCashierFromOperator() {
        Wiring wiring = wired();
        wiring.state.auth.operatorId = 7L;
        Employee cashier = mock(Employee.class);
        try (var ms = mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(cashier);
            wiring.service.create(wiring.state, new BigDecimal("10.00"));
            verify(wiring.cashMovementService).recordAcompte(any(), eq(cashier),
                    eq(new BigDecimal("10.00")), eq("C04-CMD000001"));
        }
    }

    /**
     * create with no logged-in operator records the movement with no cashier
     * (ternary false arm).
     */
    @Test
    void createRecordsMovementWithoutCashierWhenNoOperator() {
        Wiring wiring = wired();
        wiring.service.create(wiring.state, new BigDecimal("10.00"));
        verify(wiring.cashMovementService).recordAcompte(any(), isNull(),
                eq(new BigDecimal("10.00")), eq("C04-CMD000001"));
    }

    /**
     * create choreographs the whole creation (LC-06-03-01..-06): the movement
     * carrying the order code, the order pushed to the node with its total and
     * the per-rate pro-rata VAT of the deposit, the deposit ticket printed
     * with the same detail and the barcode-bearing code, the draft cancelled
     * and the lane cleared with the creation notice.
     */
    @Test
    void createChoreographsTheCreation() {
        Wiring wiring = wired();
        wiring.state.payment.ticketDbId = 42L;
        OrderCreationService.Result result = wiring.service.create(wiring.state, new BigDecimal("10.00"));
        assertTrue(result.created());
        assertEquals("C04-CMD000001", result.orderCode());
        // LC-06-03-05: 10.00 perceived on a 60.00 @ 20% + 40.00 @ 5.5% order
        // ventilates 6.00 (VAT 1.00) and 4.00 (VAT 0.21).
        verify(wiring.ticketPrinterService).printOrderDepositTicket(eq("C04-CMD000001"),
                any(), eq(new BigDecimal("10.00")), eq("0.2:1;0.055:0.21"));
        ArgumentCaptor<RecalledDocument> pushed = ArgumentCaptor.forClass(RecalledDocument.class);
        verify(wiring.documentClient).pushOrder(pushed.capture());
        assertEquals(RecalledDocument.Kind.COMMANDE, pushed.getValue().kind);
        assertEquals("C04-CMD000001", pushed.getValue().code);
        assertEquals(2, pushed.getValue().lines.size());
        assertEquals(0, new BigDecimal("100.00").compareTo(pushed.getValue().totalAmount));
        assertEquals("0.2:1;0.055:0.21", pushed.getValue().depositVatDetail);
        verify(wiring.ticketPersistenceService).cancelDraft(42L);
        assertTrue(wiring.state.ticket.items.isEmpty());
        assertEquals("COMMANDE C04-CMD000001 CRÉÉE", wiring.state.ticket.transientError);
        assertTrue(wiring.state.ticket.transientOk);
    }

    /**
     * create without a draft (nothing registered persisted yet) cancels
     * nothing: the order came from a lane that holds no draft.
     */
    @Test
    void createWithoutDraftCancelsNothing() {
        Wiring wiring = wired();
        wiring.state.payment.ticketDbId = null;
        OrderCreationService.Result result = wiring.service.create(wiring.state, new BigDecimal("10.00"));
        assertTrue(result.created());
        verify(wiring.ticketPersistenceService, never()).cancelDraft(anyLong());
    }

    /**
     * A null line in the basket is skipped when the order is frozen (null-item
     * leg of both freezing loops).
     */
    @Test
    void createSkipsNullItemsWhenFreezing() {
        Wiring wiring = wired();
        wiring.state.ticket.items.add(null);
        OrderCreationService.Result result = wiring.service.create(wiring.state, new BigDecimal("10.00"));
        assertTrue(result.created());
        ArgumentCaptor<RecalledDocument> pushed = ArgumentCaptor.forClass(RecalledDocument.class);
        verify(wiring.documentClient).pushOrder(pushed.capture());
        assertEquals(2, pushed.getValue().lines.size());
    }
}
