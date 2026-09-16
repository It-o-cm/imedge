package com.intermarche.pos.service;

import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.domain.sale.Refund;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.payment.TicketPayment;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CashSessionService}.
 * <p>
 * The service reads and writes exclusively through Panache active-record static
 * access ({@code CashSession.find}, {@code Employee.findById}, {@code Ticket.list},
 * {@code Refund.list}, {@code CashMovement.list}); all of it is intercepted with
 * {@link org.mockito.Mockito#mockStatic} on {@link PanacheEntityBase}, and the
 * {@code new CashSession()} of the opening path is intercepted with
 * {@link org.mockito.Mockito#mockConstruction} so its {@code persist()} is a
 * no-op. No database and no Quarkus context are booted. The three collaborators
 * ({@link TicketNumberService}, {@link TechnicalEventService},
 * {@link SyncOutboxService}) are Mockito mocks assigned to the package-private
 * injection fields. Every branch of the four public methods is covered:
 * opening (already-open, null cashier id, unknown cashier, float present, float
 * defaulted), report building (empty vs. populated, cash vs. non-cash payment,
 * cash vs. non-cash refund, and every movement sign plus the null-amount guard)
 * and closing (no session, parked present with cashier and amounts, parked
 * absent with null cashier and null amounts).
 */
class CashSessionServiceTest {

    /** The terminal identifier used across the tests. */
    private static final String TERMINAL = "C04";

    /**
     * Builds a service instance with the three collaborators mocked and its
     * terminal id resolved through the ticket number service.
     *
     * @return a ready-to-use service with mocked collaborators
     */
    private CashSessionService newService() {
        CashSessionService service = new CashSessionService();
        service.ticketNumberService = mock(TicketNumberService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        service.syncOutboxService = mock(SyncOutboxService.class);
        when(service.ticketNumberService.getTerminalId()).thenReturn(TERMINAL);
        return service;
    }

    /**
     * Creates a mocked Panache query whose {@code firstResult} resolves to the
     * given session (or {@code null} to simulate no open session).
     *
     * @param result the session the query must return, possibly {@code null}
     * @return the configured mocked query
     */
    private PanacheQuery<CashSession> queryReturning(CashSession result) {
        @SuppressWarnings("unchecked")
        PanacheQuery<CashSession> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * Stubs {@code CashSession.find(...)} on the given static mock so that the
     * open-session lookup of this register resolves to the given session.
     *
     * @param mocked the active Panache static mock
     * @param result the open session to return, possibly {@code null}
     */
    private void stubOpenSession(MockedStatic<PanacheEntityBase> mocked, CashSession result) {
        PanacheQuery<CashSession> query = queryReturning(result);
        mocked.when(() -> CashSession.find("terminalId = ?1 and status = ?2",
                TERMINAL, CashSession.SessionStatus.OPEN)).thenReturn(query);
    }

    /**
     * Stubs the TENDER referential to administer nothing (BO-03-02): every
     * settlement key resolves to no row, so every reader of the report falls
     * back to the register's own answer — the behaviour every case written
     * before the referential existed relies on.
     *
     * @param mocked the active Panache static mock
     */
    private void stubNoAdministeredTenders(MockedStatic<PanacheEntityBase> mocked) {
        for (String key : com.intermarche.pos.domain.payment.PaymentTypes.keys()) {
            mocked.when(() -> com.intermarche.pos.domain.payment.TenderDefinition
                    .find("code", key)).thenReturn(noTender);
        }
        mocked.when(() -> com.intermarche.pos.domain.payment.TenderDefinition
                .find("code", CashMovement.CASH)).thenReturn(noTender);
    }

    /**
     * The query every settlement key resolves to when the store administers
     * nothing.
     *
     * <p>Built once per test instance and OUTSIDE any static mock: stubbing an
     * instance mock while a static mock is open is what raises
     * {@code UnfinishedStubbingException}.
     */
    private final PanacheQuery<com.intermarche.pos.domain.payment.TenderDefinition> noTender =
            tenderQuery(null);

    /**
     * Builds the query resolving to one administered tender, or to none.
     *
     * @param tender the administered row, or null when no row administers the key
     * @return the stubbed query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<com.intermarche.pos.domain.payment.TenderDefinition> tenderQuery(
            com.intermarche.pos.domain.payment.TenderDefinition tender) {
        PanacheQuery<com.intermarche.pos.domain.payment.TenderDefinition> query =
                mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(tender);
        return query;
    }

    /**
     * Stubs the TENDER referential to administer ONE row, every other key
     * resolving to none (BO-03-02).
     *
     * @param mocked the active Panache static mock
     * @param tender the administered row
     * @param query the query resolving to that row, built before the static mock
     */
    private void stubAdministeredTender(MockedStatic<PanacheEntityBase> mocked,
            com.intermarche.pos.domain.payment.TenderDefinition tender,
            PanacheQuery<com.intermarche.pos.domain.payment.TenderDefinition> query) {
        stubNoAdministeredTenders(mocked);
        mocked.when(() -> com.intermarche.pos.domain.payment.TenderDefinition
                .find("code", tender.code)).thenReturn(query);
    }

    /**
     * Builds an administered tender carrying its settlement key and nothing
     * else, ready for the case to set the one flag it is about.
     *
     * @param code the settlement key
     * @return the administered row
     */
    private com.intermarche.pos.domain.payment.TenderDefinition tender(String code) {
        com.intermarche.pos.domain.payment.TenderDefinition tender =
                new com.intermarche.pos.domain.payment.TenderDefinition();
        tender.code = code;
        tender.functionalId = "0" + code.length();
        tender.label = code;
        tender.active = true;
        return tender;
    }

    /**
     * Builds a cash movement of the given type and amount (a plain instance,
     * never persisted).
     *
     * @param type the movement type
     * @param amount the movement amount, or null
     * @return the movement
     */
    private CashMovement movement(CashMovement.MovementType type, String amount) {
        CashMovement movement = new CashMovement();
        movement.type = type;
        movement.amount = amount != null ? new BigDecimal(amount) : null;
        return movement;
    }

    /**
     * Creates a mocked payment with the given method key and amount.
     *
     * @param key the method key returned by {@code getMethodKey}
     * @param amount the applied amount
     * @return the configured mocked payment
     */
    private TicketPayment payment(String key, String amount) {
        TicketPayment payment = mock(TicketPayment.class);
        when(payment.getMethodKey()).thenReturn(key);
        payment.amount = new BigDecimal(amount);
        return payment;
    }

    /**
     * Verifies that {@code getOpenSession} returns the session resolved by the
     * terminal-scoped finder.
     */
    @Test
    void getOpenSessionReturnsFinderResult() {
        CashSession session = mock(CashSession.class);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(mocked, session);
            assertSame(session, newService().getOpenSession());
        }
    }

    /**
     * Covers the guard arm of {@code openSession}: a session is already open, so
     * the opening is refused and nothing is created, logged or enqueued.
     */
    @Test
    void openSessionRefusedWhenAlreadyOpen() {
        CashSessionService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashSession> created = mockConstruction(CashSession.class)) {
            stubOpenSession(mocked, mock(CashSession.class));
            assertNull(service.openSession(7L, new BigDecimal("50.00")));
            assertEquals(0, created.constructed().size());
            verifyNoInteractions(service.technicalEventService);
            verifyNoInteractions(service.syncOutboxService);
        }
    }

    /**
     * Covers the null arm of the cashier-id ternary of {@code openSession}: a
     * null id resolves to a null cashier and the opening is refused.
     */
    @Test
    void openSessionRefusedWhenCashierIdNull() {
        CashSessionService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashSession> created = mockConstruction(CashSession.class)) {
            stubOpenSession(mocked, null);
            assertNull(service.openSession(null, new BigDecimal("50.00")));
            assertEquals(0, created.constructed().size());
            verifyNoInteractions(service.syncOutboxService);
        }
    }

    /**
     * Covers the non-null cashier-id arm with an unknown cashier: the finder
     * returns null and the opening is refused.
     */
    @Test
    void openSessionRefusedWhenCashierNotFound() {
        CashSessionService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashSession> created = mockConstruction(CashSession.class)) {
            stubOpenSession(mocked, null);
            mocked.when(() -> Employee.findById(7L)).thenReturn(null);
            assertNull(service.openSession(7L, new BigDecimal("50.00")));
            assertEquals(0, created.constructed().size());
            verifyNoInteractions(service.syncOutboxService);
        }
    }

    /**
     * Covers the nominal opening with a provided float (non-null float arm):
     * the session is created, seeded, persisted, journaled and enqueued.
     */
    @Test
    void openSessionSucceedsWithProvidedFloat() {
        CashSessionService service = newService();
        Employee cashier = mock(Employee.class);
        when(service.ticketNumberService.nextSessionNumber()).thenReturn("C04-S00012");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashSession> created = mockConstruction(CashSession.class)) {
            stubOpenSession(mocked, null);
            mocked.when(() -> Employee.findById(7L)).thenReturn(cashier);
            CashSession result = service.openSession(7L, new BigDecimal("50.00"));
            assertEquals(1, created.constructed().size());
            assertSame(created.constructed().get(0), result);
            assertEquals("C04-S00012", result.sessionNumber);
            assertEquals(TERMINAL, result.terminalId);
            assertEquals(CashSession.SessionStatus.OPEN, result.status);
            assertSame(cashier, result.openingCashier);
            assertEquals(0, new BigDecimal("50.00").compareTo(result.openingFloat));
            verify(result, times(1)).persist();
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.SESSION_OPENED, "C04-S00012 fond 50.00");
            verify(service.syncOutboxService).enqueue(SyncOutbox.EntityType.SESSION, result.id);
        }
    }

    /**
     * Covers the null arm of the float ternary of {@code openSession}: a null
     * float is stored as zero.
     */
    @Test
    void openSessionDefaultsFloatToZeroWhenNull() {
        CashSessionService service = newService();
        Employee cashier = mock(Employee.class);
        when(service.ticketNumberService.nextSessionNumber()).thenReturn("C04-S00012");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashSession> created = mockConstruction(CashSession.class)) {
            stubOpenSession(mocked, null);
            mocked.when(() -> Employee.findById(7L)).thenReturn(cashier);
            CashSession result = service.openSession(7L, null);
            assertEquals(0, BigDecimal.ZERO.compareTo(result.openingFloat));
        }
    }

    /**
     * Covers the empty-session report: no closed tickets and no refunds, so the
     * theoretical cash equals the opening float and the totals stay empty.
     */
    @Test
    void buildReportEmptySession() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of());
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertSame(session, report.session);
            assertEquals(0, report.ticketCount);
            assertEquals(0, BigDecimal.ZERO.compareTo(report.totalIncludingTax));
            assertTrue(report.totalsByMethod.isEmpty());
            assertEquals(0, BigDecimal.ZERO.compareTo(report.totalRefunds));
            assertEquals(0, new BigDecimal("50.00").compareTo(report.theoreticalCash));
            assertFalse(report.closing);
        }
    }

    /**
     * Covers the populated report: both arms of the {@code "CASH".equals(key)}
     * payment test and both arms of the cash-vs-non-cash refund test, so the
     * theoretical cash reflects only the cash payment minus the cash refund.
     */
    @Test
    void buildReportAggregatesPaymentsAndRefunds() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("100.00");
        Ticket ticket = new Ticket();
        ticket.totalIncludingTax = new BigDecimal("30.00");
        ticket.payments = List.of(payment("CASH", "20.00"), payment("CARD", "10.00"));
        Refund cashRefund = new Refund();
        cashRefund.totalAmount = new BigDecimal("5.00");
        cashRefund.refundMethod = Refund.RefundMethod.CASH;
        Refund cardRefund = new Refund();
        cardRefund.totalAmount = new BigDecimal("3.00");
        cardRefund.refundMethod = Refund.RefundMethod.CARD;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of(ticket));
            mocked.when(() -> Refund.list("session", session))
                    .thenReturn(List.of(cashRefund, cardRefund));
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of());
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(1, report.ticketCount);
            assertEquals(0, BigDecimal.ZERO.compareTo(report.netCashMovements));
            assertEquals(0, new BigDecimal("30.00").compareTo(report.totalIncludingTax));
            assertEquals(0, new BigDecimal("20.00").compareTo(report.totalsByMethod.get("CASH")));
            assertEquals(0, new BigDecimal("10.00").compareTo(report.totalsByMethod.get("CARD")));
            assertEquals(0, new BigDecimal("8.00").compareTo(report.totalRefunds));
            assertEquals(0, new BigDecimal("115.00").compareTo(report.theoreticalCash));
        }
    }

    /**
     * A withdrawal and a deposit of the same amount compensate exactly: the net
     * cash movement is zero and the theoretical cash equals the movement-free
     * baseline (opening float, here with no tickets and no refunds). This is the
     * invariance the lot must preserve.
     */
    @Test
    void buildReportWithdrawalAndDepositCompensate() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of(
                    movement(CashMovement.MovementType.WITHDRAWAL, "40.00"),
                    movement(CashMovement.MovementType.DEPOSIT, "40.00")));
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, BigDecimal.ZERO.compareTo(report.netCashMovements));
            assertEquals(0, new BigDecimal("50.00").compareTo(report.theoreticalCash));
        }
    }

    /**
     * Covers every arm of the movement-sign switch and both arms of the
     * null-amount guard: a deposit and a customer down-payment add cash, a
     * withdrawal and an expense remove it, a declaration and a null-amount
     * movement move nothing. Net = +10 +5 −4 −3 = 8, so the theoretical is the
     * opening float plus 8.
     */
    @Test
    void buildReportMovementSignsPerType() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of(
                    movement(CashMovement.MovementType.DEPOSIT, "10.00"),
                    movement(CashMovement.MovementType.CUSTOMER_DEPOSIT, "5.00"),
                    movement(CashMovement.MovementType.WITHDRAWAL, "4.00"),
                    movement(CashMovement.MovementType.EXPENSE, "3.00"),
                    movement(CashMovement.MovementType.DECLARATION, "100.00"),
                    movement(CashMovement.MovementType.DEPOSIT, null)));
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, new BigDecimal("8.00").compareTo(report.netCashMovements));
            assertEquals(0, new BigDecimal("58.00").compareTo(report.theoreticalCash));
        }
    }

    /**
     * Creates a movement that names the tenders it concerns.
     *
     * @param type the kind of movement
     * @param amount the amount, or null
     * @param paymentMethod the tender the movement takes out, or null for cash
     * @param transferTo the tender a transfer lands on, or null when not a transfer
     * @return the movement fixture
     */
    private CashMovement tenderMovement(CashMovement.MovementType type, String amount,
                                        String paymentMethod, String transferTo) {
        CashMovement movement = movement(type, amount);
        movement.paymentMethod = paymentMethod;
        movement.transferTo = transferTo;
        return movement;
    }

    /**
     * A withdrawal of a NON-cash tender leaves the cash alone ({@code LC-12-03-02}):
     * handing over the cheques takes nothing out of the till.
     */
    @Test
    void buildReportWithdrawalOfANonCashTenderLeavesTheCashAlone() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of(
                    tenderMovement(CashMovement.MovementType.WITHDRAWAL, "40.00", "CHEQUE", null)));
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, BigDecimal.ZERO.compareTo(report.netCashMovements));
            assertEquals(0, new BigDecimal("50.00").compareTo(report.theoreticalCash));
            assertEquals(0, new BigDecimal("-40.00").compareTo(report.totalsByMethod.get("CHEQUE")));
        }
    }

    /**
     * A withdrawal that names the cash explicitly removes it, exactly as one that names
     * no tender at all does: the two spellings of the same gesture must not disagree.
     */
    @Test
    void buildReportWithdrawalOfCashRemovesItWhicheverWayItIsNamed() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of(
                    tenderMovement(CashMovement.MovementType.WITHDRAWAL, "10.00", "CASH", null),
                    tenderMovement(CashMovement.MovementType.WITHDRAWAL, "5.00", null, null),
                    tenderMovement(CashMovement.MovementType.WITHDRAWAL, "1.00", "  ", null)));
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, new BigDecimal("-16.00").compareTo(report.netCashMovements));
            assertEquals(0, new BigDecimal("-16.00").compareTo(report.totalsByMethod.get("CASH")));
        }
    }

    /**
     * A transfer OUT of the cash removes it, and a transfer INTO the cash adds it: the
     * two arms of the transfer's effect on the till ({@code LC-12-10-04}).
     */
    @Test
    void buildReportTransferMovesTheCashInBothDirections() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of(
                    tenderMovement(CashMovement.MovementType.TRANSFER, "30.00", "CASH", "CHEQUE"),
                    tenderMovement(CashMovement.MovementType.TRANSFER, "10.00", "TR", "CASH")));
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, new BigDecimal("-20.00").compareTo(report.netCashMovements));
            assertEquals(0, new BigDecimal("-20.00").compareTo(report.totalsByMethod.get("CASH")));
            assertEquals(0, new BigDecimal("30.00").compareTo(report.totalsByMethod.get("CHEQUE")));
            assertEquals(0, new BigDecimal("-10.00").compareTo(report.totalsByMethod.get("TR")));
        }
    }

    /**
     * A transfer between two NON-cash tenders leaves the cash untouched while moving
     * both theoreticals — the arm where neither end is the till.
     */
    @Test
    void buildReportTransferBetweenTwoNonCashTendersLeavesTheCashAlone() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of(
                    tenderMovement(CashMovement.MovementType.TRANSFER, "12.00", "TR", "CHEQUE")));
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, BigDecimal.ZERO.compareTo(report.netCashMovements));
            assertEquals(0, new BigDecimal("-12.00").compareTo(report.totalsByMethod.get("TR")));
            assertEquals(0, new BigDecimal("12.00").compareTo(report.totalsByMethod.get("CHEQUE")));
        }
    }

    /**
     * A transfer whose two ends are both the cash moves nothing at all — the arm where
     * the source and the destination agree.
     */
    @Test
    void buildReportTransferFromCashToCashMovesNothing() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of(
                    tenderMovement(CashMovement.MovementType.TRANSFER, "12.00", "CASH", "CASH")));
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, BigDecimal.ZERO.compareTo(report.netCashMovements));
            assertEquals(0, new BigDecimal("50.00").compareTo(report.theoreticalCash));
        }
    }

    /**
     * A transfer that names no destination moves no theoretical: the movement is
     * incomplete, and guessing where the amount landed would invent a tender total.
     */
    @Test
    void buildReportTransferWithoutADestinationMovesNoTheoretical() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of(
                    tenderMovement(CashMovement.MovementType.TRANSFER, "12.00", "CHEQUE", null)));
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertTrue(report.totalsByMethod.isEmpty());
        }
    }

    /**
     * A movement that moves no tender leaves the per-tender totals alone: a deposit, an
     * expense, a customer down-payment and a declaration are cash gestures the cash
     * theoretical already carries, and a null-amount movement states nothing.
     */
    @Test
    void buildReportLeavesTheTenderTotalsAloneForTheOtherMovements() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of(
                    tenderMovement(CashMovement.MovementType.DEPOSIT, "10.00", "CASH", null),
                    tenderMovement(CashMovement.MovementType.EXPENSE, "3.00", "CASH", null),
                    tenderMovement(CashMovement.MovementType.CUSTOMER_DEPOSIT, "5.00", "CASH", null),
                    tenderMovement(CashMovement.MovementType.DECLARATION, "99.00", "CASH", null),
                    tenderMovement(CashMovement.MovementType.WITHDRAWAL, null, "CASH", null)));
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertTrue(report.totalsByMethod.isEmpty());
        }
    }

    /**
     * Covers the guard arm of {@code closeSession}: no session is open, so the
     * closing is refused and nothing is journaled or enqueued.
     */
    @Test
    void closeSessionRefusedWhenNoOpenSession() {
        CashSessionService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(mocked, null);
            assertNull(service.closeSession(9L, new BigDecimal("120.00"),
                    new BigDecimal("40.00"), "{}"));
            verifyNoInteractions(service.technicalEventService);
            verifyNoInteractions(service.syncOutboxService);
        }
    }

    /**
     * Covers the full closing with parked tickets present and a resolvable
     * closing cashier and non-null amounts: the parked tickets are cancelled
     * and enqueued, the cancellation is journaled, and the session records the
     * counted, theoretical, variance and withdrawal values before being closed
     * and pushed again.
     */
    @Test
    void closeSessionCancelsParkedAndRecordsCounts() {
        CashSessionService service = newService();
        Employee cashier = mock(Employee.class);
        CashSession session = mock(CashSession.class);
        session.sessionNumber = "C04-S00012";
        session.terminalId = TERMINAL;
        session.openingFloat = new BigDecimal("100.00");
        Ticket parked = mock(Ticket.class);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(mocked, session);
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of());
            mocked.when(() -> Ticket.list("terminalId = ?1 and status = ?2",
                    TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(List.of(parked));
            mocked.when(() -> Employee.findById(9L)).thenReturn(cashier);
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.closeSession(9L,
                    new BigDecimal("120.00"), new BigDecimal("40.00"), "{}");
            assertTrue(report.closing);
            assertEquals(Ticket.TicketStatus.CANCELLED, parked.status);
            verify(parked, times(1)).persist();
            verify(service.syncOutboxService).enqueue(SyncOutbox.EntityType.TICKET, parked.id);
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.TICKET_CANCELLED,
                    "1 ticket(s) en attente annulé(s) à la clôture");
            assertSame(cashier, session.closingCashier);
            assertEquals(0, new BigDecimal("120.00").compareTo(session.countedAmount));
            assertEquals(0, new BigDecimal("100.00").compareTo(session.theoreticalAmount));
            assertEquals(0, new BigDecimal("20.00").compareTo(session.variance));
            assertEquals(0, new BigDecimal("40.00").compareTo(session.withdrawnAmount));
            assertEquals("{}", session.countDetail);
            assertEquals(CashSession.SessionStatus.CLOSED, session.status);
            verify(session, times(1)).persist();
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.SESSION_CLOSED, "C04-S00012 écart 20.00");
            verify(service.syncOutboxService).enqueue(SyncOutbox.EntityType.SESSION, session.id);
        }
    }

    /**
     * Covers the closing with no parked ticket, a null cashier id and null
     * amounts: the no-parked branch skips the cancellation journal, the closing
     * cashier stays null and the counted and withdrawal amounts default to zero.
     */
    @Test
    void closeSessionWithoutParkedAndNullInputs() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.sessionNumber = "C04-S00012";
        session.terminalId = TERMINAL;
        session.openingFloat = new BigDecimal("0.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(mocked, session);
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of());
            mocked.when(() -> Ticket.list("terminalId = ?1 and status = ?2",
                    TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(List.of());
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.closeSession(null, null, null, null);
            assertTrue(report.closing);
            assertNull(session.closingCashier);
            assertEquals(0, BigDecimal.ZERO.compareTo(session.countedAmount));
            assertEquals(0, BigDecimal.ZERO.compareTo(session.withdrawnAmount));
            assertNull(session.countDetail);
            assertEquals(CashSession.SessionStatus.CLOSED, session.status);
            verify(service.technicalEventService, never()).log(
                    org.mockito.ArgumentMatchers.eq(TechnicalEvent.EventType.TICKET_CANCELLED),
                    org.mockito.ArgumentMatchers.anyString());
            verify(service.technicalEventService).log(
                    org.mockito.ArgumentMatchers.eq(TechnicalEvent.EventType.SESSION_CLOSED),
                    org.mockito.ArgumentMatchers.anyString());
        }
    }

    /**
     * Covers the non-null arm of the {@code money} ternary through every
     * amount formatter of {@link CashSessionService.SessionReport}: a present
     * value is scaled to two decimals with HALF_UP rounding and rendered with a
     * French decimal comma.
     */
    @Test
    void sessionReportFormattersRenderPresentAmounts() {
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        report.totalIncludingTax = new BigDecimal("12.5");
        report.theoreticalCash = new BigDecimal("100.005");
        report.totalRefunds = new BigDecimal("7.899");
        report.netCashMovements = new BigDecimal("-3.20");
        assertEquals("12,50", report.getTotalIncludingTaxFormatted());
        assertEquals("100,01", report.getTheoreticalCashFormatted());
        assertEquals("7,90", report.getTotalRefundsFormatted());
        assertEquals("-3,20", report.getNetCashMovementsFormatted());
    }

    /**
     * Covers the null arm of the {@code money} ternary: a null amount is
     * tolerated and rendered as the zero baseline instead of throwing.
     */
    @Test
    void sessionReportFormatterToleratesNullAmount() {
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        report.totalIncludingTax = null;
        assertEquals("0,00", report.getTotalIncludingTaxFormatted());
    }

    /**
     * Covers the loop-not-entered arm of {@code getMethodRows}: an empty
     * per-method map yields an empty, non-null settlement-row list.
     */
    @Test
    void sessionReportMethodRowsEmptyWhenNoMethod() {
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        assertTrue(report.getMethodRows().isEmpty());
    }

    /**
     * Covers the loop-entered arm of {@code getMethodRows}: the settlement rows
     * are produced in first-seen (insertion) order, each carrying the method key
     * and its French-formatted total.
     */
    @Test
    void sessionReportMethodRowsPreserveOrderAndFormat() {
        CashSessionService.SessionReport report = new CashSessionService.SessionReport();
        report.totalsByMethod.put("CASH", new BigDecimal("20.5"));
        report.totalsByMethod.put("CARD", new BigDecimal("10"));
        List<CashSessionService.SessionReport.MethodRow> rows = report.getMethodRows();
        assertEquals(2, rows.size());
        assertEquals("CASH", rows.get(0).method());
        assertEquals("20,50", rows.get(0).amountFormatted());
        assertEquals("CARD", rows.get(1).method());
        assertEquals("10,00", rows.get(1).amountFormatted());
    }

    // --------------------------------------------------
    // Administered tender reporting rules (BO-03-02-17/18/21/22/25/30)
    // --------------------------------------------------

    /**
     * Builds a one-ticket session paid on the two given tenders, so a case can
     * be about the administered rules and nothing else.
     *
     * @param mocked the active Panache static mock
     * @param session the reported session
     */
    private void stubTwoTenderSession(MockedStatic<PanacheEntityBase> mocked,
            CashSession session, Ticket ticket) {
        mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of(ticket));
        mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
        mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of());
    }

    /**
     * Builds the one-ticket sale the reporting cases run on: two hundred euros
     * in cash and one hundred by card.
     *
     * <p>Built BEFORE the static mock is opened, like every other instance mock
     * of this class — stubbing an instance mock while a static mock is open is
     * what raises {@code UnfinishedStubbingException}.
     *
     * @return the closed ticket
     */
    private Ticket twoTenderTicket() {
        Ticket ticket = mock(Ticket.class);
        ticket.totalIncludingTax = new BigDecimal("300.00");
        ticket.payments = List.of(payment("CASH", "200.00"), payment("CARD", "100.00"));
        return ticket;
    }

    /**
     * An empty referential details EVERY tender and lumps nothing, which is what
     * the register did before the referential existed (BO-03-02-25).
     */
    @Test
    void anEmptyReferentialDetailsEveryTender() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        Ticket ticket = twoTenderTicket();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubTwoTenderSession(mocked, session, ticket);
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(2, report.detailedMethods.size());
            assertEquals(2, report.getDetailedMethodRows().size());
            assertFalse(report.isCarryingUndetailedMethods());
            assertEquals(0, BigDecimal.ZERO.compareTo(report.otherMethodsTotal));
        }
    }

    /**
     * A tender administered NOT to be detailed leaves the line and joins the
     * lump total, the report still balancing (BO-03-02-25).
     */
    @Test
    void anUndetailedTenderJoinsTheLumpTotal() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        com.intermarche.pos.domain.payment.TenderDefinition card = tender("CARD");
        card.withdrawalReportDetail = false;
        Ticket ticket = twoTenderTicket();
        PanacheQuery<com.intermarche.pos.domain.payment.TenderDefinition> cardQuery = tenderQuery(card);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubTwoTenderSession(mocked, session, ticket);
            stubAdministeredTender(mocked, card, cardQuery);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertTrue(report.detailedMethods.contains("CASH"));
            assertFalse(report.detailedMethods.contains("CARD"));
            assertTrue(report.isCarryingUndetailedMethods());
            assertEquals(0, new BigDecimal("100.00").compareTo(report.otherMethodsTotal));
            assertEquals("100,00", report.getOtherMethodsTotalFormatted());
            assertEquals(1, report.getDetailedMethodRows().size());
        }
    }

    /**
     * Only the tenders administered for the bank are summed into the deposit,
     * and an empty referential deposits nothing (BO-03-02-21).
     */
    @Test
    void onlyTheAdministeredTendersAreDeposited() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        com.intermarche.pos.domain.payment.TenderDefinition card = tender("CARD");
        card.bankDeposit = true;
        Ticket ticket = twoTenderTicket();
        PanacheQuery<com.intermarche.pos.domain.payment.TenderDefinition> cardQuery = tenderQuery(card);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubTwoTenderSession(mocked, session, ticket);
            stubAdministeredTender(mocked, card, cardQuery);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, new BigDecimal("100.00").compareTo(report.bankDepositTotal));
            assertEquals("100,00", report.getBankDepositTotalFormatted());
        }

        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubTwoTenderSession(mocked, session, ticket);
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, BigDecimal.ZERO.compareTo(report.bankDepositTotal));
        }
    }

    /**
     * Only the tenders administered as reported to fidelity are summed into the
     * fidelity total (BO-03-02-30).
     */
    @Test
    void onlyTheAdministeredTendersAreReportedToFidelity() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        com.intermarche.pos.domain.payment.TenderDefinition cash = tender("CASH");
        cash.fidelityReported = true;
        Ticket ticket = twoTenderTicket();
        PanacheQuery<com.intermarche.pos.domain.payment.TenderDefinition> cashQuery = tenderQuery(cash);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubTwoTenderSession(mocked, session, ticket);
            stubAdministeredTender(mocked, cash, cashQuery);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, new BigDecimal("200.00").compareTo(report.fidelityReportedTotal));
            assertEquals("200,00", report.getFidelityReportedTotalFormatted());
        }

        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubTwoTenderSession(mocked, session, ticket);
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, BigDecimal.ZERO.compareTo(report.fidelityReportedTotal));
        }
    }

    /**
     * A cash tender administered NOT to make up the float drops the float out of
     * the theoretical, and an empty referential keeps it in (BO-03-02-22).
     */
    @Test
    void theFloatCountsOnlyWhenTheCashMayMakeItUp() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        com.intermarche.pos.domain.payment.TenderDefinition cash = tender("CASH");
        cash.floatAllowed = false;
        Ticket ticket = twoTenderTicket();
        PanacheQuery<com.intermarche.pos.domain.payment.TenderDefinition> cashQuery = tenderQuery(cash);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubTwoTenderSession(mocked, session, ticket);
            stubAdministeredTender(mocked, cash, cashQuery);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, new BigDecimal("200.00").compareTo(report.theoreticalCash));
        }

        com.intermarche.pos.domain.payment.TenderDefinition allowed = tender("CASH");
        allowed.floatAllowed = true;
        PanacheQuery<com.intermarche.pos.domain.payment.TenderDefinition> allowedQuery = tenderQuery(allowed);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubTwoTenderSession(mocked, session, ticket);
            stubAdministeredTender(mocked, allowed, allowedQuery);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, new BigDecimal("250.00").compareTo(report.theoreticalCash));
        }
    }

    /**
     * A cash tender in automatic declaration is declared BY THE REGISTER: the
     * counted amount is the theoretical whatever the cashier typed, and the
     * variance is therefore nil (BO-03-02-17).
     */
    @Test
    void anAutomaticallyDeclaredCashIsDeclaredByTheRegister() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.sessionNumber = "C04-S00012";
        session.terminalId = TERMINAL;
        session.openingFloat = new BigDecimal("50.00");
        com.intermarche.pos.domain.payment.TenderDefinition cash = tender("CASH");
        cash.cashierDeclaration = true;
        // The row is about THIS flag: the float stays allowed, so the
        // theoretical is the one every other case of this class computes.
        cash.floatAllowed = true;
        Ticket ticket = twoTenderTicket();
        PanacheQuery<com.intermarche.pos.domain.payment.TenderDefinition> cashQuery = tenderQuery(cash);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(mocked, session);
            stubTwoTenderSession(mocked, session, ticket);
            mocked.when(() -> Ticket.list("terminalId = ?1 and status = ?2",
                    TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(List.of());
            stubAdministeredTender(mocked, cash, cashQuery);
            CashSessionService.SessionReport report =
                    service.closeSession(null, new BigDecimal("999.00"), null, null);
            assertTrue(report.cashDeclaredAutomatically);
            assertEquals(0, new BigDecimal("250.00").compareTo(session.countedAmount));
            assertEquals(0, BigDecimal.ZERO.compareTo(session.variance));
        }
    }

    /**
     * A cash tender in automatic withdrawal has its closing withdrawal COMPUTED:
     * everything above the float leaves the drawer, and a drawer below its float
     * withdraws nothing — the two arms of the floor (BO-03-02-18).
     */
    @Test
    void anAutomaticWithdrawalTakesEverythingAboveTheFloat() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.sessionNumber = "C04-S00012";
        session.terminalId = TERMINAL;
        session.openingFloat = new BigDecimal("50.00");
        com.intermarche.pos.domain.payment.TenderDefinition cash = tender("CASH");
        cash.automaticWithdrawal = true;
        // The row is about THIS flag: the float stays allowed, so the
        // theoretical is the one every other case of this class computes.
        cash.floatAllowed = true;
        Ticket ticket = twoTenderTicket();
        PanacheQuery<com.intermarche.pos.domain.payment.TenderDefinition> cashQuery = tenderQuery(cash);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(mocked, session);
            stubTwoTenderSession(mocked, session, ticket);
            mocked.when(() -> Ticket.list("terminalId = ?1 and status = ?2",
                    TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(List.of());
            stubAdministeredTender(mocked, cash, cashQuery);
            CashSessionService.SessionReport report =
                    service.closeSession(null, null, new BigDecimal("7.00"), null);
            assertTrue(report.cashWithdrawnAutomatically);
            assertEquals(0, new BigDecimal("200.00").compareTo(session.withdrawnAmount));
        }

        // A session that sold nothing and refunded a hundred euros in cash: the
        // drawer is BELOW its float, so the automatic withdrawal takes nothing
        // rather than going negative. Below and not merely equal, deliberately:
        // a drawer exactly at its float would not tell the floor apart from a
        // plain subtraction.
        CashSession poor = mock(CashSession.class);
        poor.sessionNumber = "C04-S00013";
        poor.terminalId = TERMINAL;
        poor.openingFloat = new BigDecimal("500.00");
        Refund cashRefund = mock(Refund.class);
        cashRefund.totalAmount = new BigDecimal("100.00");
        cashRefund.refundMethod = Refund.RefundMethod.CASH;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(mocked, poor);
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    poor, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", poor)).thenReturn(List.of(cashRefund));
            mocked.when(() -> CashMovement.list("session = ?1", poor)).thenReturn(List.of());
            mocked.when(() -> Ticket.list("terminalId = ?1 and status = ?2",
                    TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(List.of());
            stubAdministeredTender(mocked, cash, cashQuery);
            service.closeSession(null, null, new BigDecimal("7.00"), null);
            assertEquals(0, BigDecimal.ZERO.compareTo(poor.withdrawnAmount));
        }
    }
}
