package com.intermarche.edge.service;

import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.session.CashMovement;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.domain.sync.SyncOutbox;
import com.intermarche.edge.service.sync.register.SyncOutboxService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
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
        service.revenueReportService = mock(RevenueReportService.class);
        service.ticketNumberService = mock(TicketNumberService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        service.syncOutboxService = mock(SyncOutboxService.class);
        service.posSettingsService = mock(com.intermarche.edge.service.PosSettingsService.class);
        service.parkedTicketArchiveService = mock(ParkedTicketArchiveService.class);
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
        for (String key : com.intermarche.edge.domain.payment.PaymentTypes.keys()) {
            mocked.when(() -> com.intermarche.edge.domain.payment.TenderDefinition
                    .find("code", key)).thenReturn(noTender);
        }
        mocked.when(() -> com.intermarche.edge.domain.payment.TenderDefinition
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
    private final PanacheQuery<com.intermarche.edge.domain.payment.TenderDefinition> noTender =
            tenderQuery(null);

    /**
     * Builds the query resolving to one administered tender, or to none.
     *
     * @param tender the administered row, or null when no row administers the key
     * @return the stubbed query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<com.intermarche.edge.domain.payment.TenderDefinition> tenderQuery(
            com.intermarche.edge.domain.payment.TenderDefinition tender) {
        PanacheQuery<com.intermarche.edge.domain.payment.TenderDefinition> query =
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
            com.intermarche.edge.domain.payment.TenderDefinition tender,
            PanacheQuery<com.intermarche.edge.domain.payment.TenderDefinition> query) {
        stubNoAdministeredTenders(mocked);
        mocked.when(() -> com.intermarche.edge.domain.payment.TenderDefinition
                .find("code", tender.code)).thenReturn(query);
    }

    /**
     * Builds an administered tender carrying its settlement key and nothing
     * else, ready for the case to set the one flag it is about.
     *
     * @param code the settlement key
     * @return the administered row
     */
    private com.intermarche.edge.domain.payment.TenderDefinition tender(String code) {
        com.intermarche.edge.domain.payment.TenderDefinition tender =
                new com.intermarche.edge.domain.payment.TenderDefinition();
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
     * The FLOAT REFERENCE is what the drawer must keep: the opening float plus the
     * net cash movements of the session. A coin supply received during the day raises
     * it — the store handed over change, not revenue — and a withdrawal already made
     * lowers it, so that counted minus the reference is the cash revenue and nothing
     * else.
     */
    @Test
    void buildReportStatesWhatTheDrawerMustKeep() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("100.00");
        CashMovement supply = new CashMovement();
        supply.type = CashMovement.MovementType.DEPOSIT;
        supply.amount = new BigDecimal("50.00");
        CashMovement taken = new CashMovement();
        taken.type = CashMovement.MovementType.WITHDRAWAL;
        taken.amount = new BigDecimal("20.00");
        taken.paymentMethod = CashMovement.CASH;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session))
                    .thenReturn(List.of(supply, taken));
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, new BigDecimal("130.00").compareTo(report.floatReference));
            assertEquals("130,00", report.getFloatReferenceFormatted());
            assertEquals("130.00", report.getFloatReferencePlain());
        }
    }

    /**
     * A report built on nothing keeps the float as the reference, and a null
     * reference still reads as zero on the plain accessor (the null arm), the
     * closing screen having to be given a number whatever happened.
     */
    @Test
    void theFloatReferenceFallsBackToZero() {
        CashSessionService.SessionReport bare = new CashSessionService.SessionReport();
        assertEquals("0.00", bare.getFloatReferencePlain());
        bare.floatReference = null;
        assertEquals("0.00", bare.getFloatReferencePlain());
        assertEquals("0,00", bare.getFloatReferenceFormatted());
    }

    /**
     * A session with NO declaration states none: the report's declared cash stays
     * null, the variance stays zero, and {@code isDeclared} is false — a line at
     * zero would state a count nobody made.
     */
    @Test
    void buildReportWithoutADeclarationStatesNone() {
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
            assertFalse(report.isDeclared());
            assertNull(report.declaredCash);
            assertEquals(0, BigDecimal.ZERO.compareTo(report.declaredCashVariance));
            assertEquals("—", report.getDeclaredCashFormatted());
            assertEquals("—", report.getDeclaredCashVarianceFormatted());
        }
    }

    /**
     * A cash DECLARATION is put beside the theoretical: declared 60 € against a 50 €
     * float with no sale gives a +10 € gap. The declaration itself moves nothing, so
     * the theoretical is unchanged by it.
     */
    @Test
    void buildReportStatesTheGapBetweenTheDeclarationAndTheTheoretical() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session))
                    .thenReturn(List.of(declaration(new BigDecimal("60.00"), null,
                            java.time.LocalDateTime.of(2026, 10, 5, 10, 0))));
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertTrue(report.isDeclared());
            assertEquals(0, new BigDecimal("60.00").compareTo(report.declaredCash));
            assertEquals(0, new BigDecimal("10.00").compareTo(report.declaredCashVariance));
            assertEquals(0, new BigDecimal("50.00").compareTo(report.theoreticalCash));
            assertEquals("60,00", report.getDeclaredCashFormatted());
            assertEquals("10,00", report.getDeclaredCashVarianceFormatted());
        }
    }

    /**
     * The LAST declaration wins: a cashier who declares twice has corrected
     * themselves. The later row is kept whichever order the query returns them in
     * (both arms of the date comparison), and a declaration naming ANOTHER TENDER is
     * not the cash and is ignored.
     */
    @Test
    void buildReportKeepsTheLastCashDeclaration() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session))
                    .thenReturn(List.of(
                            declaration(new BigDecimal("80.00"), null,
                                    java.time.LocalDateTime.of(2026, 10, 5, 11, 0)),
                            declaration(new BigDecimal("60.00"), null,
                                    java.time.LocalDateTime.of(2026, 10, 5, 10, 0)),
                            declaration(new BigDecimal("999.00"), "CHEQUE",
                                    java.time.LocalDateTime.of(2026, 10, 5, 12, 0))));
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, new BigDecimal("80.00").compareTo(report.declaredCash));
            assertEquals(0, new BigDecimal("30.00").compareTo(report.declaredCashVariance));
        }
    }

    /**
     * An UNDATED declaration is not the latest when a dated one exists (null arm of
     * the date comparison), and it is still kept when it is the only one (the
     * current-null arm): a row with no date cannot win over a dated row, but it is
     * not thrown away either.
     */
    @Test
    void buildReportWeighsAnUndatedDeclaration() {
        CashSessionService service = newService();
        CashSession dated = mock(CashSession.class);
        dated.openingFloat = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    dated, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", dated)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", dated))
                    .thenReturn(List.of(
                            declaration(new BigDecimal("70.00"), null,
                                    java.time.LocalDateTime.of(2026, 10, 5, 9, 0)),
                            declaration(new BigDecimal("999.00"), null, null)));
            stubNoAdministeredTenders(mocked);
            assertEquals(0, new BigDecimal("70.00")
                    .compareTo(service.buildReport(dated).declaredCash));
        }
        CashSession alone = mock(CashSession.class);
        alone.openingFloat = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    alone, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", alone)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", alone))
                    .thenReturn(List.of(declaration(new BigDecimal("65.00"), null, null)));
            stubNoAdministeredTenders(mocked);
            assertEquals(0, new BigDecimal("65.00")
                    .compareTo(service.buildReport(alone).declaredCash));
        }
    }

    /**
     * A declaration carrying NO amount is ignored rather than read as zero (the
     * amount-null arm): the drawer was never declared.
     */
    @Test
    void buildReportIgnoresADeclarationWithoutAnAmount() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session))
                    .thenReturn(List.of(declaration(null, null,
                            java.time.LocalDateTime.of(2026, 10, 5, 10, 0))));
            stubNoAdministeredTenders(mocked);
            assertFalse(service.buildReport(session).isDeclared());
        }
    }

    /**
     * Builds a cash-count declaration movement.
     *
     * @param amount the declared amount, possibly null
     * @param tender the tender it declares, null for the cash
     * @param date the moment it was recorded, possibly null
     * @return the movement
     */
    private CashMovement declaration(BigDecimal amount, String tender,
                                     java.time.LocalDateTime date) {
        CashMovement movement = new CashMovement();
        movement.type = CashMovement.MovementType.DECLARATION;
        movement.amount = amount;
        movement.paymentMethod = tender;
        movement.movementDate = date;
        return movement;
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
            assertEquals(0, new BigDecimal("115.00").compareTo(report.theoreticalByMethod.get("CASH")));
            assertEquals(0, new BigDecimal("7.00").compareTo(report.theoreticalByMethod.get("CARD")));
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
     * BO-10-01-27: a customer deposit taken by card never entered the drawer,
     * so only the one taken in cash moves the theoretical cash.
     */
    @Test
    void buildReportCountsOnlyTheCustomerDepositsTakenInCash() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of(
                    tenderMovement(CashMovement.MovementType.CUSTOMER_DEPOSIT, "5.00", "CASH", null),
                    tenderMovement(CashMovement.MovementType.CUSTOMER_DEPOSIT, "20.00", "CARD", null)));
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, new BigDecimal("5.00").compareTo(report.netCashMovements));
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
            assertEquals(0, new BigDecimal("50.00").compareTo(report.theoreticalByMethod.get("CASH")));
            assertEquals(0, new BigDecimal("-40.00").compareTo(report.theoreticalByMethod.get("CHEQUE")));
            assertTrue(report.totalsByMethod.isEmpty());
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
            assertEquals(0, new BigDecimal("34.00").compareTo(report.theoreticalByMethod.get("CASH")));
            assertTrue(report.totalsByMethod.isEmpty());
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
            assertEquals(0, new BigDecimal("30.00").compareTo(report.theoreticalByMethod.get("CASH")));
            assertEquals(0, new BigDecimal("30.00").compareTo(report.theoreticalByMethod.get("CHEQUE")));
            assertEquals(0, new BigDecimal("-10.00").compareTo(report.theoreticalByMethod.get("TR")));
            assertTrue(report.totalsByMethod.isEmpty());
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
            assertEquals(0, new BigDecimal("-12.00").compareTo(report.theoreticalByMethod.get("TR")));
            assertEquals(0, new BigDecimal("12.00").compareTo(report.theoreticalByMethod.get("CHEQUE")));
            assertEquals(0, new BigDecimal("50.00").compareTo(report.theoreticalByMethod.get("CASH")));
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
     * A transfer that names no destination moves no theoretical, the cash's
     * included: the movement is incomplete, and guessing where the amount landed
     * would invent a tender total — and make the cash and the per-tender
     * theoretical disagree.
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
            assertEquals(0, BigDecimal.ZERO.compareTo(report.netCashMovements));
            assertEquals(0, new BigDecimal("50.00").compareTo(report.theoreticalCash));
            assertEquals(1, report.theoreticalByMethod.size());
            assertEquals(0, new BigDecimal("50.00").compareTo(report.theoreticalByMethod.get("CASH")));
        }
    }

    /**
     * Recette RT (BO-05-02-08): a CASH cash-in, expense and down-payment move the
     * cash line of the per-tender theoretical exactly as they move the cash
     * theoretical — the line used to ignore them — while the settlements stay what
     * the tickets took in; a declaration and a null-amount movement move nothing.
     */
    @Test
    void buildReportCarriesTheCashMovementsOnTheCashLine() {
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
            assertEquals(0, new BigDecimal("62.00").compareTo(report.theoreticalCash));
            assertEquals(1, report.theoreticalByMethod.size());
            assertEquals(0, new BigDecimal("62.00").compareTo(report.theoreticalByMethod.get("CASH")));
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
            // LC-04-01-03: the purge leaves its signed trace.
            verify(service.parkedTicketArchiveService).record(parked,
                    com.intermarche.edge.domain.sale.ParkedTicketRecord.Event.PURGED, "CLOTURE Z");
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
                    org.mockito.ArgumentMatchers.eq(TechnicalEvent.EventType.SESSION_CLOSED),
                    org.mockito.ArgumentMatchers.eq("C04-S00012 écart 20.00"),
                    org.mockito.ArgumentMatchers.isNull());
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
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.isNull());
        }
    }

    /**
     * BO-03-10-10: an island automatic declaration closes with the counted
     * amount FORCED to the theoretical whatever the caller passes (here a 120,00
     * that must be ignored), a zero variance, and a SESSION_CLOSED journal line
     * that NAMES the automatic declaration so the zero variance is not mistaken
     * for a correct hand count.
     */
    @Test
    void closeSessionAutoDeclarationDeclaresTheoreticalAndMarksJournal() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.sessionNumber = "C04-S00012";
        session.terminalId = TERMINAL;
        session.openingFloat = new BigDecimal("100.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(mocked, session);
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of());
            mocked.when(() -> Ticket.list("terminalId = ?1 and status = ?2",
                    TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(List.of());
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.closeSession(null,
                    new BigDecimal("120.00"), null, null, true);
            assertEquals(0, session.countedAmount.compareTo(report.theoreticalCash));
            assertEquals(0, new BigDecimal("100.00").compareTo(session.countedAmount));
            assertEquals(0, BigDecimal.ZERO.compareTo(session.variance));
            verify(service.technicalEventService).log(
                    org.mockito.ArgumentMatchers.eq(TechnicalEvent.EventType.SESSION_CLOSED),
                    org.mockito.ArgumentMatchers.contains("déclaration automatique"),
                    org.mockito.ArgumentMatchers.isNull());
        }
    }

    /**
     * The 4-argument {@code closeSession} delegates with the automatic flag
     * FALSE: the counted amount is the caller's (120,00, not the theoretical)
     * and the SESSION_CLOSED journal carries NO automatic marker — the current
     * behaviour, unchanged.
     */
    @Test
    void closeSessionManualDelegationKeepsCountedAndPlainJournal() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.sessionNumber = "C04-S00012";
        session.terminalId = TERMINAL;
        session.openingFloat = new BigDecimal("100.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(mocked, session);
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of());
            mocked.when(() -> Ticket.list("terminalId = ?1 and status = ?2",
                    TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(List.of());
            stubNoAdministeredTenders(mocked);
            service.closeSession(null, new BigDecimal("120.00"), null, "{}");
            assertEquals(0, new BigDecimal("120.00").compareTo(session.countedAmount));
            verify(service.technicalEventService).log(
                    org.mockito.ArgumentMatchers.eq(TechnicalEvent.EventType.SESSION_CLOSED),
                    org.mockito.ArgumentMatchers.eq("C04-S00012 écart 20.00"),
                    org.mockito.ArgumentMatchers.isNull());
        }
    }

    /**
     * EXP-79/83: buildReport names the per-cashier revenue and lists the paper
     * tenders (cashierDeclaration) with their theoretical settlement count.
     */
    @Test
    void buildReportCarriesPerCashierAndPaperTenders() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("0.00");
        com.intermarche.edge.domain.people.Employee jean =
                mock(com.intermarche.edge.domain.people.Employee.class);
        when(jean.getFullName()).thenReturn("Jean Dupont");
        Ticket ticket = new Ticket();
        ticket.totalIncludingTax = new BigDecimal("30.00");
        ticket.cashier = jean;
        ticket.payments = List.of(payment("CHEQUE", "30.00"));
        com.intermarche.edge.domain.payment.TenderDefinition cheque = tender("CHEQUE");
        cheque.cashierDeclaration = true;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of(ticket));
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of());
            mocked.when(com.intermarche.edge.domain.payment.TenderDefinition::listAllOrdered)
                    .thenReturn(List.of(cheque));
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(1, report.cashierRows.size());
            assertEquals("Jean Dupont", report.cashierRows.get(0).cashier());
            assertEquals(1, report.cashierRows.get(0).ticketCount());
            assertEquals(1, report.getPaperTenders().size());
            assertEquals("CHEQUE", report.paperTenders.get(0).code());
            assertEquals(1, report.paperTenders.get(0).theoreticalQty());
        }
    }

    /**
     * EXP-95 on a FIXED float: EVERY evening is a full-count day, the cashier being
     * counted and reset daily. Jan 2024 starts on a Monday, so the 6th/7th are the
     * week-end, the 15th an ordinary Monday and the 31st the last day of the month —
     * all four count.
     */
    @Test
    void isFullCountDayIsEveryDayOnAFixedFloat() {
        CashSessionService service = newService();
        when(service.posSettingsService.floatMode()).thenReturn("FIXED");
        when(service.posSettingsService.floatingFloat()).thenReturn(false);
        assertTrue(service.isFullCountDay(java.time.LocalDate.of(2024, 1, 6)));
        assertTrue(service.isFullCountDay(java.time.LocalDate.of(2024, 1, 7)));
        assertTrue(service.isFullCountDay(java.time.LocalDate.of(2024, 1, 15)));
        assertTrue(service.isFullCountDay(java.time.LocalDate.of(2024, 1, 31)));
    }

    /**
     * EXP-95 on a float floating to the WEEK and the MONTH: the week-end (both
     * days) and the last day of the month count, an ordinary weekday does not.
     */
    @Test
    void isFullCountDayCoversWeekEndAndMonthEndOnAWeeklyFloatingFloat() {
        CashSessionService service = newService();
        when(service.posSettingsService.floatMode()).thenReturn("FLOATING_WEEK_MONTH");
        when(service.posSettingsService.floatingFloat()).thenReturn(true);
        assertTrue(service.isFullCountDay(java.time.LocalDate.of(2024, 1, 6)));
        assertTrue(service.isFullCountDay(java.time.LocalDate.of(2024, 1, 7)));
        assertTrue(service.isFullCountDay(java.time.LocalDate.of(2024, 1, 31)));
        assertFalse(service.isFullCountDay(java.time.LocalDate.of(2024, 1, 15)));
    }

    /**
     * The FORMER spelling {@code FLOATING} still names the week-and-month mode: a
     * shop administered before the third mode existed keeps its behaviour.
     */
    @Test
    void isFullCountDayAcceptsTheFormerFloatingSpelling() {
        CashSessionService service = newService();
        when(service.posSettingsService.floatMode()).thenReturn("FLOATING");
        when(service.posSettingsService.floatingFloat()).thenReturn(true);
        assertTrue(service.isFullCountDay(java.time.LocalDate.of(2024, 1, 6)));
        assertFalse(service.isFullCountDay(java.time.LocalDate.of(2024, 1, 15)));
    }

    /**
     * EXP-95 on a float floating to the MONTH ALONE (the rare case): only the last
     * day of the month counts — the week-end does not.
     */
    @Test
    void isFullCountDayCoversOnlyMonthEndOnAMonthlyFloatingFloat() {
        CashSessionService service = newService();
        when(service.posSettingsService.floatMode()).thenReturn("FLOATING_MONTH");
        when(service.posSettingsService.floatingFloat()).thenReturn(true);
        assertTrue(service.isFullCountDay(java.time.LocalDate.of(2024, 1, 31)));
        assertFalse(service.isFullCountDay(java.time.LocalDate.of(2024, 1, 6)));
        assertFalse(service.isFullCountDay(java.time.LocalDate.of(2024, 1, 7)));
        assertFalse(service.isFullCountDay(java.time.LocalDate.of(2024, 1, 15)));
    }

    /**
     * EXP-95: a FLOATING no-count close arbitrates NO variance — counted forced to
     * the theoretical, variance zero, the floatingNoCount flag raised, the
     * withdrawal the surplus above the initial float (zero here, no tickets).
     */
    @Test
    void closeSessionFloatingNoCountArbitratesNoVariance() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.sessionNumber = "C04-S00012";
        session.terminalId = TERMINAL;
        session.openingFloat = new BigDecimal("100.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(mocked, session);
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of());
            mocked.when(() -> Ticket.list("terminalId = ?1 and status = ?2",
                    TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(List.of());
            stubNoAdministeredTenders(mocked);
            service.closeSession(null, null, null, null, false, null, null, true);
            assertTrue(session.floatingNoCount);
            assertEquals(0, BigDecimal.ZERO.compareTo(session.variance));
            assertEquals(0, BigDecimal.ZERO.compareTo(session.withdrawnAmount));
            verify(service.technicalEventService).log(
                    org.mockito.ArgumentMatchers.eq(TechnicalEvent.EventType.SESSION_CLOSED),
                    org.mockito.ArgumentMatchers.contains("fond flottant sans comptage"),
                    org.mockito.ArgumentMatchers.isNull());
        }
    }

    /**
     * EXP-82: a supervisor-validated over-threshold discrepancy stores the
     * validating badge as the nominative trace and names it in the journal.
     */
    @Test
    void closeSessionStoresVarianceEndorsement() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.sessionNumber = "C04-S00012";
        session.terminalId = TERMINAL;
        session.openingFloat = new BigDecimal("100.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(mocked, session);
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of());
            mocked.when(() -> Ticket.list("terminalId = ?1 and status = ?2",
                    TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(List.of());
            stubNoAdministeredTenders(mocked);
            service.closeSession(null, new BigDecimal("90.00"), new BigDecimal("0"), "{}",
                    false, "22222222", null, false);
            assertEquals("22222222", session.varianceEndorsedBy);
            assertEquals(0, new BigDecimal("-10.00").compareTo(session.variance));
            verify(service.technicalEventService).log(
                    org.mockito.ArgumentMatchers.eq(TechnicalEvent.EventType.SESSION_CLOSED),
                    org.mockito.ArgumentMatchers.contains("écart validé par 22222222"),
                    org.mockito.ArgumentMatchers.eq("22222222"));
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
        com.intermarche.edge.domain.payment.TenderDefinition card = tender("CARD");
        card.withdrawalReportDetail = false;
        Ticket ticket = twoTenderTicket();
        PanacheQuery<com.intermarche.edge.domain.payment.TenderDefinition> cardQuery = tenderQuery(card);
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
        com.intermarche.edge.domain.payment.TenderDefinition card = tender("CARD");
        card.bankDeposit = true;
        Ticket ticket = twoTenderTicket();
        PanacheQuery<com.intermarche.edge.domain.payment.TenderDefinition> cardQuery = tenderQuery(card);
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
        com.intermarche.edge.domain.payment.TenderDefinition cash = tender("CASH");
        cash.fidelityReported = true;
        Ticket ticket = twoTenderTicket();
        PanacheQuery<com.intermarche.edge.domain.payment.TenderDefinition> cashQuery = tenderQuery(cash);
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
        com.intermarche.edge.domain.payment.TenderDefinition cash = tender("CASH");
        cash.floatAllowed = false;
        Ticket ticket = twoTenderTicket();
        PanacheQuery<com.intermarche.edge.domain.payment.TenderDefinition> cashQuery = tenderQuery(cash);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubTwoTenderSession(mocked, session, ticket);
            stubAdministeredTender(mocked, cash, cashQuery);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, new BigDecimal("200.00").compareTo(report.theoreticalCash));
        }

        com.intermarche.edge.domain.payment.TenderDefinition allowed = tender("CASH");
        allowed.floatAllowed = true;
        PanacheQuery<com.intermarche.edge.domain.payment.TenderDefinition> allowedQuery = tenderQuery(allowed);
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
        com.intermarche.edge.domain.payment.TenderDefinition cash = tender("CASH");
        cash.cashierDeclaration = true;
        // The row is about THIS flag: the float stays allowed, so the
        // theoretical is the one every other case of this class computes.
        cash.floatAllowed = true;
        Ticket ticket = twoTenderTicket();
        PanacheQuery<com.intermarche.edge.domain.payment.TenderDefinition> cashQuery = tenderQuery(cash);
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
        com.intermarche.edge.domain.payment.TenderDefinition cash = tender("CASH");
        cash.automaticWithdrawal = true;
        // The row is about THIS flag: the float stays allowed, so the
        // theoretical is the one every other case of this class computes.
        cash.floatAllowed = true;
        Ticket ticket = twoTenderTicket();
        PanacheQuery<com.intermarche.edge.domain.payment.TenderDefinition> cashQuery = tenderQuery(cash);
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

    /**
     * Stubs the last-closed-session finder of this register on the given static
     * mock (EXP-02).
     *
     * @param mocked the active Panache static mock
     * @param result the last closed session to return, possibly null
     */
    private void stubLastClosed(MockedStatic<PanacheEntityBase> mocked, CashSession result) {
        PanacheQuery<CashSession> query = queryReturning(result);
        mocked.when(() -> CashSession.find(
                "terminalId = ?1 and status = ?2 order by closingDate desc",
                TERMINAL, CashSession.SessionStatus.CLOSED)).thenReturn(query);
    }

    /**
     * {@code theoreticalOpeningFloat} returns null when this register was never
     * closed (no previous close to carry over) — the null arm.
     */
    @Test
    void theoreticalOpeningFloatNullWhenNeverClosed() {
        CashSessionService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLastClosed(mocked, null);
            assertNull(service.theoreticalOpeningFloat());
        }
    }

    /**
     * {@code theoreticalOpeningFloat} carries over the leftover of the previous
     * close (counted minus withdrawn) — the non-null arm.
     */
    @Test
    void theoreticalOpeningFloatCarriesOverLeftover() {
        CashSessionService service = newService();
        CashSession last = mock(CashSession.class);
        last.countedAmount = new BigDecimal("200.00");
        last.withdrawnAmount = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLastClosed(mocked, last);
            assertEquals(0, new BigDecimal("150.00").compareTo(service.theoreticalOpeningFloat()));
        }
    }

    /**
     * {@code theoreticalOpeningFloat} treats null counted and withdrawn amounts
     * as zero — both null-guard arms — yielding a zero leftover.
     */
    @Test
    void theoreticalOpeningFloatTreatsNullAmountsAsZero() {
        CashSessionService service = newService();
        CashSession last = mock(CashSession.class);
        last.countedAmount = null;
        last.withdrawnAmount = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLastClosed(mocked, last);
            assertEquals(0, new BigDecimal("0.00").compareTo(service.theoreticalOpeningFloat()));
        }
    }

    /**
     * {@code theoreticalOpeningFloat} clamps a negative leftover (withdrawn more
     * than counted) to zero — the negative-signum arm.
     */
    @Test
    void theoreticalOpeningFloatClampsNegativeToZero() {
        CashSessionService service = newService();
        CashSession last = mock(CashSession.class);
        last.countedAmount = new BigDecimal("10.00");
        last.withdrawnAmount = new BigDecimal("40.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubLastClosed(mocked, last);
            assertEquals(0, new BigDecimal("0.00").compareTo(service.theoreticalOpeningFloat()));
        }
    }

    /**
     * {@code drawerCapExcess} returns null when no cap is administered (the
     * cap-null / non-positive arm).
     */
    @Test
    void drawerCapExcessNullWhenNoCap() {
        CashSessionService service = newService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.drawerCap()).thenReturn(BigDecimal.ZERO);
        assertNull(service.drawerCapExcess());
    }

    /**
     * {@code drawerCapExcess} returns null when a cap is set but no session is
     * open (the no-session arm).
     */
    @Test
    void drawerCapExcessNullWhenNoOpenSession() {
        CashSessionService service = newService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.drawerCap()).thenReturn(new BigDecimal("100.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(mocked, null);
            assertNull(service.drawerCapExcess());
        }
    }

    /**
     * {@code drawerCapExcess} returns null when the drawer cash is at or below
     * the cap (the not-over arm).
     */
    @Test
    void drawerCapExcessNullWhenUnderCap() {
        CashSessionService service = newService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.drawerCap()).thenReturn(new BigDecimal("100.00"));
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(mocked, session);
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of());
            stubNoAdministeredTenders(mocked);
            assertNull(service.drawerCapExcess());
        }
    }

    /**
     * {@code drawerCapExcess} returns the drawer cash when it exceeds the cap
     * (the over-cap arm).
     */
    @Test
    void drawerCapExcessReturnsCashWhenOverCap() {
        CashSessionService service = newService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.drawerCap()).thenReturn(new BigDecimal("40.00"));
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(mocked, session);
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of());
            stubNoAdministeredTenders(mocked);
            assertEquals(0, new BigDecimal("50.00").compareTo(service.drawerCapExcess()));
        }
    }
    /** LC-12: movement ordering treats undated rows as earliest and compares every dated ordering leg. */
    @Test
    void movementOrderingCoversMissingAndEqualDates() throws Exception {
        CashSessionService service = newService();
        var method = CashSessionService.class.getDeclaredMethod("laterThan", java.time.LocalDateTime.class, java.time.LocalDateTime.class);
        method.setAccessible(true);
        var date = java.time.LocalDateTime.of(2026, 10, 20, 12, 0);
        assertEquals(false, method.invoke(service, null, date));
        assertEquals(true, method.invoke(service, date, null));
        assertEquals(false, method.invoke(service, date, date));
        assertEquals(false, method.invoke(service, date.minusMinutes(1), date));
        assertEquals(true, method.invoke(service, date.plusMinutes(1), date));
    }

    /** BO-10-02-08: without an open session there are no parked tickets at risk during closing. */
    @Test
    void parkedCountWithoutSessionIsZero() {
        CashSessionService service = newService();
        try (var base = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(base, null);
            assertEquals(0L, service.parkedTicketCount());
        }
    }

    /** BO-09-03-04: counter-reset failure does not undo a completed fiscal session close. */
    @Test
    void counterResetFailureNeverReopensAClosedSession() {
        CashSessionService service = newService();
        when(service.posSettingsService.resetTicketCounterAtPeriodEnd()).thenReturn(true);
        doThrow(new IllegalStateException("counter unavailable")).when(service.ticketNumberService).resetTicketNumbers();
        CashSession session = mock(CashSession.class);
        session.sessionNumber = "C04-S00012";
        session.terminalId = TERMINAL;
        session.openingFloat = BigDecimal.TEN;
        try (var base = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(base, session);
            stubNoAdministeredTenders(base);
            assertNotNull(service.closeSession(null, BigDecimal.TEN, BigDecimal.ZERO, null));
        }
        assertEquals(CashSession.SessionStatus.CLOSED, session.status);
        verify(service.ticketNumberService).resetTicketNumbers();
        verify(session).persist();
    }

    /** EXP-17/BO-10-02-08: zero caps are disabled and an open session reports its actual parked count. */
    @Test
    void zeroCapAndOpenSessionParkedCountKeepTheirDefaults() {
        CashSessionService service = newService();
        when(service.posSettingsService.drawerCap()).thenReturn(BigDecimal.ZERO);
        assertNull(service.drawerCapExcess());
        CashSession session = mock(CashSession.class);
        session.terminalId = TERMINAL;
        try (var base = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(base, session);
            base.when(() -> PanacheEntityBase.count("terminalId = ?1 and status = ?2", TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(3L);
            assertEquals(3L, service.parkedTicketCount());
        }
    }

    /** LC-12: an older drawer declaration cannot overwrite a newer one and non-paper tenders are not declared by count. */
    @Test
    void reportKeepsTheLatestDeclarationAndOnlyPaperTenders() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = BigDecimal.TEN;
        var now = java.time.LocalDateTime.of(2026, 10, 20, 12, 0);
        var paper = tender("CHEQUE");
        paper.cashierDeclaration = true;
        var electronic = tender("CARD");
        try (var base = mockStatic(PanacheEntityBase.class)) {
            stubNoAdministeredTenders(base);
            base.when(() -> PanacheEntityBase.list("order by displayOrder, code")).thenReturn(List.of(paper, electronic));
            base.when(() -> PanacheEntityBase.list("session = ?1", session)).thenReturn(List.of(declaration(new BigDecimal("12"), null, now), declaration(new BigDecimal("11"), null, now.minusMinutes(1)), declaration(new BigDecimal("13"), null, now.plusMinutes(1))));
            var report = service.buildReport(session);
            assertEquals(new BigDecimal("13.00"), report.declaredCash);
            assertEquals(1, report.paperTenders.size());
            assertEquals("CHEQUE", report.paperTenders.get(0).code());
        }
    }

    /**
     * Wires the given lifecycle hooks into the service, as CDI would through
     * its {@code Instance}; each iteration gets a fresh iterator.
     *
     * @param service the service under test
     * @param hooks the hooks to expose, in iteration order
     */
    @SuppressWarnings("unchecked")
    private void wireHooks(CashSessionService service, SessionLifecycleHook... hooks) {
        jakarta.enterprise.inject.Instance<SessionLifecycleHook> instance =
                mock(jakarta.enterprise.inject.Instance.class);
        when(instance.iterator()).thenAnswer(invocation -> List.of(hooks).iterator());
        service.sessionHooks = instance;
    }

    /** LC-01-02-09: a failing lifecycle hook cannot unwind an already persisted session transition, nor stop the next hook. */
    @Test
    void terminalHookFailureIsContained() {
        var service = newService();
        SessionLifecycleHook failing = mock(SessionLifecycleHook.class);
        SessionLifecycleHook next = mock(SessionLifecycleHook.class);
        doThrow(new IllegalStateException("terminal unavailable")).when(failing).onSessionOpened();
        wireHooks(service, failing, next);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.notifyHooks(true));
        verify(failing).onSessionOpened();
        verify(next).onSessionOpened();
        verify(next, never()).onSessionClosed();
    }

    /** A node that wires no hook at all (the store node, a plain unit test) notifies nothing and never fails. */
    @Test
    void noHookWiredNotifiesNothing() {
        var service = newService();
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.notifyHooks(false));
        wireHooks(service);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.notifyHooks(false));
    }

    /** BO-09-03-04, LC-01-02-09 and BO-10-08: successful post-close housekeeping notifies the hooks after persistence, then resets the counter, and the report exposes its cashier rows. */
    @Test
    void successfulCloseNotifiesTheHooksAndResetsTheCounter() {
        var service = newService();
        SessionLifecycleHook hook = mock(SessionLifecycleHook.class);
        wireHooks(service, hook);
        when(service.posSettingsService.resetTicketCounterAtPeriodEnd()).thenReturn(true);
        CashSession session = mock(CashSession.class);
        session.sessionNumber = "C04-S00012";
        session.terminalId = TERMINAL;
        session.openingFloat = BigDecimal.TEN;
        try (var base = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(base, session);
            stubNoAdministeredTenders(base);
            var report = service.closeSession(null, BigDecimal.TEN, BigDecimal.ZERO, null);
            assertNotNull(report);
            assertNotNull(report.getCashierRows());
        }
        assertEquals(CashSession.SessionStatus.CLOSED, session.status);
        var order = org.mockito.Mockito.inOrder(session, hook, service.ticketNumberService);
        order.verify(session).persist();
        order.verify(hook).onSessionClosed();
        order.verify(service.ticketNumberService).resetTicketNumbers();
    }


    /** LC-01-02-07: force-close persists CLOSED and syncs without inventing a count or variance. */
    @Test
    void forceClosePersistsUnknownCountAndSynchronizesTheClosedSession() {
        var service = newService();
        CashSession session = mock(CashSession.class);
        session.id = 12L;
        session.sessionNumber = "C04-S00012";
        session.terminalId = TERMINAL;
        session.openingFloat = new BigDecimal("100.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(mocked, session);
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2", session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of());
            mocked.when(() -> Ticket.list("terminalId = ?1 and status = ?2", TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(List.of());
            stubNoAdministeredTenders(mocked);
            assertNotNull(service.forceCloseSession(null));
            assertEquals(CashSession.SessionStatus.CLOSED, session.status);
            assertNotNull(session.closingDate);
            assertNull(session.countedAmount);
            assertNull(session.variance);
            assertNull(session.withdrawnAmount);
            verify(session).persist();
            verify(service.syncOutboxService).enqueue(com.intermarche.edge.domain.sync.SyncOutbox.EntityType.SESSION, 12L);
            verify(service.technicalEventService).log(
                    org.mockito.ArgumentMatchers.eq(TechnicalEvent.EventType.SESSION_CLOSED),
                    org.mockito.ArgumentMatchers.contains("fermeture forcée sans comptage"),
                    org.mockito.ArgumentMatchers.isNull());
        }
    }


    /** LC-12-07-01: the query selects this period, connected employee and closed status before summing tenders. */
    @Test
    void operatorRevenueUsesConnectedEmployeeAndCurrentPeriod() {
        var service = newService();
        var session = new CashSession();
        var ticket = new Ticket();
        ticket.totalIncludingTax = new BigDecimal("25.00");
        var cash = mock(TicketPayment.class);
        cash.amount = new BigDecimal("20.00");
        when(cash.getMethodKey()).thenReturn("CASH");
        var card = mock(TicketPayment.class);
        card.amount = new BigDecimal("5.00");
        when(card.getMethodKey()).thenReturn("CARD");
        ticket.payments = List.of(cash, card);
        try (var entities = mockStatic(PanacheEntityBase.class)) {
            entities.when(() -> Ticket.list("session = ?1 and status = ?2 and cashier.id = ?3",
                    session, Ticket.TicketStatus.CLOSED, 7L)).thenReturn(List.of(ticket));
            var report = service.buildOperatorReport(session, 7L);
            assertSame(session, report.session);
            assertEquals(1, report.ticketCount);
            assertEquals(new BigDecimal("25.00"), report.totalIncludingTax);
            assertEquals(new BigDecimal("20.00"), report.totalsByMethod.get("CASH"));
            assertEquals(new BigDecimal("5.00"), report.totalsByMethod.get("CARD"));
            var empty = service.buildOperatorReport(session, 8L);
            assertEquals(0, empty.ticketCount);
            assertEquals(BigDecimal.ZERO, empty.totalIncludingTax);
            assertTrue(empty.totalsByMethod.isEmpty());
        }
    }

    /**
     * BO-03-02-20: a float, an expense or a down-payment recorded in another
     * tender than cash moves THAT tender's theoretical and leaves the notes in
     * the drawer alone; in cash, the cash theoretical carries them and the cash
     * line of the per-tender theoretical with it. The settlements are not
     * touched, and a declaration moves nothing.
     */
    @Test
    void buildReportMovesTheTenderOfANonCashFloatOrExpense() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of(
                    tenderMovement(CashMovement.MovementType.EXPENSE, "15.00", "CHEQUE", null),
                    tenderMovement(CashMovement.MovementType.DEPOSIT, "40.00", "TR", null),
                    tenderMovement(CashMovement.MovementType.CUSTOMER_DEPOSIT, "25.00", "CARD", null),
                    tenderMovement(CashMovement.MovementType.EXPENSE, "5.00", "CASH", null),
                    tenderMovement(CashMovement.MovementType.DEPOSIT, "7.00", null, null),
                    tenderMovement(CashMovement.MovementType.DECLARATION, "9.00", "TR", null)));
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, new BigDecimal("2.00").compareTo(report.netCashMovements));
            assertEquals(0, new BigDecimal("-15.00").compareTo(report.theoreticalByMethod.get("CHEQUE")));
            assertEquals(0, new BigDecimal("40.00").compareTo(report.theoreticalByMethod.get("TR")));
            assertEquals(0, new BigDecimal("25.00").compareTo(report.theoreticalByMethod.get("CARD")));
            assertEquals(0, new BigDecimal("52.00").compareTo(report.theoreticalByMethod.get("CASH")));
            assertTrue(report.totalsByMethod.isEmpty());
        }
    }

    /**
     * Recette RT replay (BO-03-02-20, BO-05-02-08): a 100 euro float, a cash sale,
     * a cash-in of 20, a cash expense of 5, a withdrawal of 30, a card sale, a
     * cheque sale and a cheque expense. The settlements state the tickets alone;
     * the per-tender theoretical states the drawer, its cash line being the Z's
     * theoretical cash: 100 + 50 + 20 - 5 - 30 = 135.
     */
    @Test
    void theRecetteReplayStatesOneDrawerOnBothSides() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("100.00");
        Ticket cashSale = new Ticket();
        cashSale.totalIncludingTax = new BigDecimal("50.00");
        cashSale.payments = List.of(payment("CASH", "50.00"));
        Ticket otherSale = new Ticket();
        otherSale.totalIncludingTax = new BigDecimal("100.00");
        otherSale.payments = List.of(payment("CARD", "40.00"), payment("CHEQUE", "60.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of(cashSale, otherSale));
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of(
                    tenderMovement(CashMovement.MovementType.DEPOSIT, "20.00", "CASH", null),
                    tenderMovement(CashMovement.MovementType.EXPENSE, "5.00", "CASH", null),
                    tenderMovement(CashMovement.MovementType.WITHDRAWAL, "30.00", "CASH", null),
                    tenderMovement(CashMovement.MovementType.EXPENSE, "8.00", "CHEQUE", null)));
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, new BigDecimal("135.00").compareTo(report.theoreticalCash));
            assertEquals(0, new BigDecimal("135.00").compareTo(report.theoreticalByMethod.get("CASH")));
            assertEquals(0, new BigDecimal("40.00").compareTo(report.theoreticalByMethod.get("CARD")));
            assertEquals(0, new BigDecimal("52.00").compareTo(report.theoreticalByMethod.get("CHEQUE")));
            assertEquals(List.of("CASH", "CARD", "CHEQUE"),
                    List.copyOf(report.theoreticalByMethod.keySet()));
            assertEquals(0, new BigDecimal("50.00").compareTo(report.totalsByMethod.get("CASH")));
            assertEquals(0, new BigDecimal("40.00").compareTo(report.totalsByMethod.get("CARD")));
            assertEquals(0, new BigDecimal("60.00").compareTo(report.totalsByMethod.get("CHEQUE")));
        }
    }

    /**
     * Every movement type, in the cash and in a non-cash tender, moves its own
     * tender with its own sign (BO-03-02-20, LC-12-03-06, LC-12-10-04): a cash-in
     * and a down-payment add, an expense and a withdrawal remove, a transfer moves
     * the amount between its two ends and a declaration moves nothing. The cash
     * line always equals the cash theoretical.
     */
    @Test
    void everyMovementTypeMovesItsOwnTender() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("100.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of(
                    tenderMovement(CashMovement.MovementType.DEPOSIT, "1.00", "CASH", null),
                    tenderMovement(CashMovement.MovementType.DEPOSIT, "2.00", "TR", null),
                    tenderMovement(CashMovement.MovementType.CUSTOMER_DEPOSIT, "3.00", "CASH", null),
                    tenderMovement(CashMovement.MovementType.CUSTOMER_DEPOSIT, "4.00", "TR", null),
                    tenderMovement(CashMovement.MovementType.EXPENSE, "5.00", "CASH", null),
                    tenderMovement(CashMovement.MovementType.EXPENSE, "6.00", "TR", null),
                    tenderMovement(CashMovement.MovementType.WITHDRAWAL, "7.00", "CASH", null),
                    tenderMovement(CashMovement.MovementType.WITHDRAWAL, "8.00", "TR", null),
                    tenderMovement(CashMovement.MovementType.TRANSFER, "9.00", "CASH", "TR"),
                    tenderMovement(CashMovement.MovementType.TRANSFER, "10.00", "TR", "CASH"),
                    tenderMovement(CashMovement.MovementType.DECLARATION, "11.00", "CASH", null),
                    tenderMovement(CashMovement.MovementType.DECLARATION, "12.00", "TR", null)));
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, new BigDecimal("93.00").compareTo(report.theoreticalCash));
            assertEquals(0, new BigDecimal("93.00").compareTo(report.theoreticalByMethod.get("CASH")));
            assertEquals(0, new BigDecimal("-9.00").compareTo(report.theoreticalByMethod.get("TR")));
            assertTrue(report.totalsByMethod.isEmpty());
        }
    }

    /**
     * Refunds lower the theoretical of the tender they hand back (BO-05-02-08):
     * a cash refund the cash, a card refund the card takings, and a voucher or a
     * loyalty credit no tender at all — they hand nothing out of the drawer.
     */
    @Test
    void refundsLowerOnlyTheTenderTheyHandBack() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("100.00");
        Ticket ticket = new Ticket();
        ticket.totalIncludingTax = new BigDecimal("90.00");
        ticket.payments = List.of(payment("CASH", "50.00"), payment("CARD", "40.00"));
        Refund cash = new Refund();
        cash.totalAmount = new BigDecimal("4.00");
        cash.refundMethod = Refund.RefundMethod.CASH;
        Refund card = new Refund();
        card.totalAmount = new BigDecimal("6.00");
        card.refundMethod = Refund.RefundMethod.CARD;
        Refund voucher = new Refund();
        voucher.totalAmount = new BigDecimal("7.00");
        voucher.refundMethod = Refund.RefundMethod.VOUCHER;
        Refund loyalty = new Refund();
        loyalty.totalAmount = new BigDecimal("8.00");
        loyalty.refundMethod = Refund.RefundMethod.LOYALTY;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of(ticket));
            mocked.when(() -> Refund.list("session", session))
                    .thenReturn(List.of(cash, card, voucher, loyalty));
            mocked.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of());
            stubNoAdministeredTenders(mocked);
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(0, new BigDecimal("25.00").compareTo(report.totalRefunds));
            assertEquals(0, new BigDecimal("146.00").compareTo(report.theoreticalByMethod.get("CASH")));
            assertEquals(0, new BigDecimal("34.00").compareTo(report.theoreticalByMethod.get("CARD")));
            assertEquals(2, report.theoreticalByMethod.size());
            assertEquals(0, new BigDecimal("40.00").compareTo(report.totalsByMethod.get("CARD")));
        }
    }
}
