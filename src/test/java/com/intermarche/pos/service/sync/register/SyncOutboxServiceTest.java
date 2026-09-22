package com.intermarche.pos.service.sync.register;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.service.sync.SyncEndpoints;
import com.intermarche.pos.domain.payment.CardPayment;
import com.intermarche.pos.domain.payment.CashPayment;
import com.intermarche.pos.domain.sale.Refund;
import com.intermarche.pos.domain.sale.RefundLine;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.payment.VoucherPayment;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.intermarche.pos.service.sync.SyncPayloads;

/**
 * Unit tests for {@link SyncOutboxService}.
 * <p>
 * The service is a Panache active-record producer/reader: it enqueues rows,
 * drains them by static finder, serializes an entity graph to JSON through an
 * injected {@link ObjectMapper} and deletes/annotates rows by id. All static
 * access ({@code X.findById}, {@code SyncOutbox.find}, {@code deleteById})
 * resolves to {@link PanacheEntityBase} under plain {@code mvn test} and is
 * intercepted with {@link org.mockito.Mockito#mockStatic}; the single
 * {@code new SyncOutbox()} on the enqueue path is neutralized with
 * {@link org.mockito.Mockito#mockConstruction} so its {@code persist()} is
 * inert. Loaded entities are real POJO instances (never persisted here, only
 * read), and a real {@link ObjectMapper} performs and reverses the
 * serialization so the private {@code toDto}/{@code iso} mappings are asserted
 * on absolute round-tripped values; the failure branch swaps in a mapper mock
 * that throws. Since {@code findById} of every entity resolves to the same
 * {@code PanacheEntityBase.findById}, ids are kept distinct across types within
 * a test so stubs never collide. No database and no Quarkus context is booted.
 * <p>
 * Branch enumeration — every decision point, both arms: {@code isEnabled}
 * (present/absent, blank/non-blank); {@code getStoreUrl} (disabled arm,
 * trailing-slash ternary both arms); {@code enqueue} (disabled short-circuit,
 * null-id arm, persist arm); {@code prepare} (row-null arm, the four switch
 * cases each with entity-present/entity-gone arms, and the serialization
 * catch); {@code markFailure} (row-null arm, error-null and error-non-null
 * arms of the {@code &&}, and the length &gt; 255 ternary both arms);
 * {@code markSuccess}/{@code markGone}/{@code nextBatchIds} (no decision);
 * and the private mappers reached through {@code prepare} — {@code toDto}
 * cashier/store/session/refund-method/original-ticket/original-line ternaries
 * both arms, line and payment loops empty and non-empty, the
 * {@code instanceof CashPayment}/{@code VoucherPayment} arms, and {@code iso}
 * null and non-null.
 */
class SyncOutboxServiceTest {

    /**
     * Builds a service enabled with the given store URL and a real
     * {@link ObjectMapper} for serialization round-trips.
     *
     * @param url the configured store URL
     * @return the enabled service
     */
    private SyncOutboxService enabledService(String url) {
        SyncOutboxService service = new SyncOutboxService();
        service.endpoints = SyncEndpoints.of(url);
        service.objectMapper = new ObjectMapper();
        // BO-10-04-14/-19: a document payload asks the parameters what it may
        // carry, so the collaborator belongs to the fixture. Both extractions are
        // ON here; the tests that are about the gates say otherwise themselves.
        service.posSettingsService =
                org.mockito.Mockito.mock(com.intermarche.pos.service.PosSettingsService.class);
        org.mockito.Mockito.when(service.posSettingsService.invoiceExportDueDate())
                .thenReturn(true);
        org.mockito.Mockito.when(service.posSettingsService.invoiceExportEcoTax())
                .thenReturn(true);
        return service;
    }

    // --------------------------------------------------
    // isEnabled / getStoreUrl
    // --------------------------------------------------

    /**
     * The address itself is now decided by {@link SyncEndpoints} and tested
     * there; what remains the outbox's own responsibility is that it asks that
     * object rather than answering on its own — so these two cover the
     * delegation, and nothing else.
     */
    @Test
    void isEnabledAsksTheEndpoints() {
        SyncOutboxService enabled = new SyncOutboxService();
        enabled.endpoints = SyncEndpoints.of("http://store");
        SyncOutboxService disabled = new SyncOutboxService();
        disabled.endpoints = SyncEndpoints.of(null);
        assertTrue(enabled.isEnabled());
        assertFalse(disabled.isEnabled());
    }

    /**
     * The outbox returns the address the endpoints object computed, trailing
     * slash already stripped — it does no normalization of its own.
     */
    @Test
    void getStoreUrlAsksTheEndpoints() {
        SyncOutboxService service = new SyncOutboxService();
        service.endpoints = SyncEndpoints.of(" http://store/ ");
        assertEquals("http://store", service.getStoreUrl());
    }

    // --------------------------------------------------
    // enqueue
    // --------------------------------------------------

    /**
     * Covers the disabled short-circuit arm of {@code enqueue}: nothing is
     * constructed when synchronization is off.
     */
    @Test
    void enqueueDoesNothingWhenDisabled() {
        SyncOutboxService service = new SyncOutboxService();
        service.endpoints = SyncEndpoints.of(null);
        try (MockedConstruction<SyncOutbox> created = mockConstruction(SyncOutbox.class)) {
            service.enqueue(SyncOutbox.EntityType.TICKET, 5L);
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * Covers the null-id arm of {@code enqueue}: an enabled service enqueues
     * nothing when the entity id is null.
     */
    @Test
    void enqueueDoesNothingWhenEntityIdNull() {
        SyncOutboxService service = enabledService("http://store");
        try (MockedConstruction<SyncOutbox> created = mockConstruction(SyncOutbox.class)) {
            service.enqueue(SyncOutbox.EntityType.TICKET, null);
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * Covers the persist arm of {@code enqueue}: an enabled service with a
     * non-null id constructs, stamps and persists a row.
     */
    @Test
    void enqueuePersistsRow() {
        SyncOutboxService service = enabledService("http://store");
        try (MockedConstruction<SyncOutbox> created = mockConstruction(SyncOutbox.class)) {
            service.enqueue(SyncOutbox.EntityType.SESSION, 42L);
            assertEquals(1, created.constructed().size());
            SyncOutbox row = created.constructed().get(0);
            assertEquals(SyncOutbox.EntityType.SESSION, row.entityType);
            assertEquals(42L, row.entityId);
            assertTrue(row.createdAt != null);
            verify(row, times(1)).persist();
        }
    }

    // --------------------------------------------------
    // nextBatchIds
    // --------------------------------------------------

    /**
     * Covers {@code nextBatchIds}: the paged query result is mapped to its row
     * ids in order.
     */
    @Test
    @SuppressWarnings("unchecked")
    void nextBatchIdsMapsRowsToIds() {
        SyncOutboxService service = new SyncOutboxService();
        SyncOutbox first = new SyncOutbox();
        first.id = 3L;
        SyncOutbox second = new SyncOutbox();
        second.id = 7L;
        PanacheQuery<SyncOutbox> query = mock(PanacheQuery.class);
        when(query.page(0, 10)).thenReturn(query);
        when(query.list()).thenReturn(List.of(first, second));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.find("order by entityType, id")).thenReturn(query);
            List<Long> ids = service.nextBatchIds(10);
            assertEquals(List.of(3L, 7L), ids);
        }
    }

    // --------------------------------------------------
    // prepare
    // --------------------------------------------------

    /**
     * Covers the row-null arm of {@code prepare}: a vanished outbox row yields
     * null.
     */
    @Test
    void prepareReturnsNullWhenRowMissing() {
        SyncOutboxService service = enabledService("http://store");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(null);
            assertNull(service.prepare(1L));
        }
    }

    /**
     * Covers the SESSION case of {@code prepare} with a fully populated session:
     * both cashier ternary true arms and the {@code iso} non-null arm for both
     * dates; the payload is serialized and round-trips to the expected values.
     */
    @Test
    void prepareSessionSerializesFullGraph() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.SESSION;
        row.entityId = 100L;
        CashSession session = new CashSession();
        session.sessionNumber = "S1";
        session.terminalId = "T1";
        session.status = CashSession.SessionStatus.CLOSED;
        session.openingDate = LocalDateTime.of(2026, 1, 1, 8, 0, 0);
        session.closingDate = LocalDateTime.of(2026, 1, 1, 18, 0, 0);
        Employee opening = new Employee();
        opening.loginName = "alice";
        Employee closing = new Employee();
        closing.loginName = "bob";
        session.openingCashier = opening;
        session.closingCashier = closing;
        session.openingFloat = new BigDecimal("50.00");
        session.countDetail = "detail";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> CashSession.findById(100L)).thenReturn(session);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            assertEquals("session", item.pathSuffix);
            SyncPayloads.SessionDto out = new ObjectMapper().readValue(item.json, SyncPayloads.SessionDto.class);
            assertEquals("S1", out.sessionNumber);
            assertEquals("CLOSED", out.status);
            assertEquals("2026-01-01T08:00:00", out.openingDate);
            assertEquals("2026-01-01T18:00:00", out.closingDate);
            assertEquals("alice", out.openingCashierLogin);
            assertEquals("bob", out.closingCashierLogin);
        }
    }

    /**
     * Covers the SESSION case of {@code prepare} with null cashiers and null
     * dates: both cashier ternary false arms and the {@code iso} null arm.
     */
    @Test
    void prepareSessionSerializesWithNulls() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.SESSION;
        row.entityId = 100L;
        CashSession session = new CashSession();
        session.sessionNumber = "S2";
        session.terminalId = "T2";
        session.status = CashSession.SessionStatus.OPEN;
        session.openingDate = null;
        session.closingDate = null;
        session.openingCashier = null;
        session.closingCashier = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> CashSession.findById(100L)).thenReturn(session);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            SyncPayloads.SessionDto out = new ObjectMapper().readValue(item.json, SyncPayloads.SessionDto.class);
            assertEquals("S2", out.sessionNumber);
            assertNull(out.openingDate);
            assertNull(out.closingDate);
            assertNull(out.openingCashierLogin);
            assertNull(out.closingCashierLogin);
        }
    }

    /**
     * Covers the entity-gone arm of the SESSION case: the outbox row exists but
     * its session vanished, so {@code prepare} yields null.
     */
    @Test
    void prepareSessionReturnsNullWhenSessionGone() {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.SESSION;
        row.entityId = 100L;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> CashSession.findById(100L)).thenReturn(null);
            assertNull(service.prepare(1L));
        }
    }

    /**
     * Covers the TICKET case of {@code prepare} with a full graph: store,
     * cashier and session ternary true arms, one line, a cash payment
     * ({@code instanceof CashPayment} true, {@code instanceof VoucherPayment}
     * false) and a voucher payment (the mirrored arms), and both {@code iso}
     * date arms; the payload round-trips to the expected values.
     */
    @Test
    void prepareTicketSerializesFullGraph() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.TICKET;
        row.entityId = 100L;
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "K1";
        ticket.terminalId = "T1";
        ticket.status = Ticket.TicketStatus.CLOSED;
        ticket.valuationStatus = Ticket.ValuationStatus.VALUATED;
        ticket.creationDate = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
        ticket.closingDate = LocalDateTime.of(2026, 1, 1, 10, 5, 0);
        Store store = new Store();
        store.code = "ST1";
        ticket.store = store;
        Employee cashier = new Employee();
        cashier.loginName = "alice";
        ticket.cashier = cashier;
        CashSession session = new CashSession();
        session.sessionNumber = "S1";
        ticket.session = session;
        ticket.itemCount = 2;
        ticket.totalExcludingTax = new BigDecimal("10.00");
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        // BO-03-03-25/-29/-31: the loyalty zone the register froze at closing
        // travels with the sale, so the node can reissue the same paper.
        ticket.fidelityCard = "2990000000019";
        ticket.fidelityEarnTotal = new BigDecimal("1.03");
        ticket.fidelityAvailableBalance = new BigDecimal("42.30");
        ticket.fidelityUnavailable = true;
        ticket.fidelityLines.add(new com.intermarche.pos.domain.sale.TicketFidelityLine(
                "SOCLE", "Cagnotte socle", new BigDecimal("0.28")));
        TicketLine line = new TicketLine();
        line.lineNumber = 1;
        line.lineUid = "U1";
        line.ean = "E1";
        line.productLabel = "Pomme";
        line.quantity = new BigDecimal("1");
        line.unitPrice = new BigDecimal("12.00");
        line.totalPrice = new BigDecimal("12.00");
        line.familyCode = "FRUITS";
        line.familyLabel = "Rayon Fruits";
        line.cancelled = true;
        line.cancellationDate = java.time.LocalDateTime.of(2026, 9, 1, 15, 42);
        line.cancelledBy = "12341234";
        ticket.lines.add(line);
        CashPayment cash = mock(CashPayment.class);
        when(cash.getMethodKey()).thenReturn("CASH");
        cash.paymentIndex = 1;
        cash.amount = new BigDecimal("7.00");
        cash.tenderedAmount = new BigDecimal("10.00");
        VoucherPayment voucher = mock(VoucherPayment.class);
        when(voucher.getMethodKey()).thenReturn("VOUCHER");
        voucher.paymentIndex = 2;
        voucher.amount = new BigDecimal("5.00");
        voucher.voucherLabel = "Bon";
        voucher.voucherNumber = "V9";
        ticket.payments.add(cash);
        ticket.payments.add(voucher);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> Ticket.findById(100L)).thenReturn(ticket);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            assertEquals("ticket", item.pathSuffix);
            SyncPayloads.TicketDto out = new ObjectMapper().readValue(item.json, SyncPayloads.TicketDto.class);
            assertEquals("K1", out.ticketNumber);
            assertEquals("VALUATED", out.valuationStatus);
            assertEquals("2026-01-01T10:00:00", out.creationDate);
            assertEquals("2026-01-01T10:05:00", out.closingDate);
            assertEquals("ST1", out.storeCode);
            assertEquals("alice", out.cashierLogin);
            assertEquals("S1", out.sessionNumber);
            assertEquals("2990000000019", out.fidelityCard);
            assertEquals(new BigDecimal("1.03"), out.fidelityEarnTotal);
            assertEquals(new BigDecimal("42.30"), out.fidelityAvailableBalance);
            assertTrue(out.fidelityUnavailable);
            assertEquals(1, out.fidelityLines.size());
            assertEquals("SOCLE", out.fidelityLines.get(0).ruleCode);
            assertEquals("Cagnotte socle", out.fidelityLines.get(0).label);
            assertEquals(new BigDecimal("0.28"), out.fidelityLines.get(0).amount);
            assertEquals(1, out.lines.size());
            assertEquals("U1", out.lines.get(0).lineUid);
            assertEquals("FRUITS", out.lines.get(0).familyCode);
            assertEquals("Rayon Fruits", out.lines.get(0).familyLabel);
            // Article-cancellation witness survives the JSON round-trip
            // (lot C4, BO-04-01-16): timestamp carried as an ISO string.
            assertTrue(out.lines.get(0).cancelled);
            assertEquals("2026-09-01T15:42:00", out.lines.get(0).cancellationDate);
            assertEquals("12341234", out.lines.get(0).cancelledBy);
            assertEquals(2, out.payments.size());
            assertEquals("CASH", out.payments.get(0).methodKey);
            assertEquals(new BigDecimal("10.00"), out.payments.get(0).tenderedAmount);
            assertNull(out.payments.get(0).voucherLabel);
            assertEquals("VOUCHER", out.payments.get(1).methodKey);
            assertEquals("Bon", out.payments.get(1).voucherLabel);
            assertEquals("V9", out.payments.get(1).voucherNumber);
            assertNull(out.payments.get(1).tenderedAmount);
        }
    }

    /**
     * Covers the {@code instanceof CardPayment} true arm of the TICKET payment
     * mapping: a card payment carries its authorization number and degraded-mode
     * indicator into the payload, which round-trips to the expected values
     * (BO-04-01-08/47/49).
     */
    @Test
    void prepareTicketSerializesCardPaymentTraces() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.TICKET;
        row.entityId = 100L;
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "K3";
        ticket.terminalId = "T3";
        ticket.status = Ticket.TicketStatus.CLOSED;
        ticket.valuationStatus = Ticket.ValuationStatus.VALUATED;
        ticket.creationDate = LocalDateTime.of(2026, 3, 3, 10, 0, 0);
        ticket.itemCount = 1;
        ticket.totalExcludingTax = new BigDecimal("10.00");
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        CardPayment card = mock(CardPayment.class);
        when(card.getMethodKey()).thenReturn("CARD");
        card.paymentIndex = 1;
        card.amount = new BigDecimal("12.00");
        card.authorizationNumber = "654321";
        card.degradedMode = true;
        ticket.payments.add(card);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> Ticket.findById(100L)).thenReturn(ticket);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            SyncPayloads.TicketDto out = new ObjectMapper().readValue(item.json, SyncPayloads.TicketDto.class);
            assertEquals(1, out.payments.size());
            assertEquals("CARD", out.payments.get(0).methodKey);
            assertEquals("654321", out.payments.get(0).authorizationNumber);
            assertTrue(out.payments.get(0).degradedMode);
            assertNull(out.payments.get(0).tenderedAmount);
            assertNull(out.payments.get(0).voucherLabel);
        }
    }

    /**
     * Covers the four remaining payment-subtype {@code instanceof} true arms of
     * the TICKET payment mapping in one graph: a cheque ({@code ChequePayment}
     * true) carries its magnetic line, a backup ({@code BackupPayment} true)
     * carries its method label, transaction number and manual flag, a foreign
     * currency ({@code ForeignCurrencyPayment} true) carries its currency code,
     * amount and rate, and a credit ({@code CreditPayment} true) carries its
     * account number, account name and over-limit flag; the payload round-trips
     * to the expected values.
     */
    @Test
    void prepareTicketSerializesRemainingPaymentTraces() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.TICKET;
        row.entityId = 100L;
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "K4";
        ticket.terminalId = "T4";
        ticket.status = Ticket.TicketStatus.CLOSED;
        ticket.valuationStatus = Ticket.ValuationStatus.VALUATED;
        ticket.creationDate = LocalDateTime.of(2026, 4, 4, 10, 0, 0);
        ticket.itemCount = 1;
        ticket.totalExcludingTax = new BigDecimal("10.00");
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        com.intermarche.pos.domain.payment.ChequePayment cheque =
                mock(com.intermarche.pos.domain.payment.ChequePayment.class);
        when(cheque.getMethodKey()).thenReturn("CHEQUE");
        cheque.paymentIndex = 1;
        cheque.amount = new BigDecimal("3.00");
        cheque.magneticLine = "CMC7-LINE";
        com.intermarche.pos.domain.payment.BackupPayment secours =
                mock(com.intermarche.pos.domain.payment.BackupPayment.class);
        when(secours.getMethodKey()).thenReturn("BACKUP");
        secours.paymentIndex = 2;
        secours.amount = new BigDecimal("3.00");
        secours.methodLabel = "Secours CB";
        secours.transactionNumber = "TX42";
        secours.manual = true;
        com.intermarche.pos.domain.payment.ForeignCurrencyPayment devise =
                mock(com.intermarche.pos.domain.payment.ForeignCurrencyPayment.class);
        when(devise.getMethodKey()).thenReturn("DEVISE");
        devise.paymentIndex = 3;
        devise.amount = new BigDecimal("3.00");
        devise.currencyCode = "USD";
        devise.foreignAmount = new BigDecimal("3.30");
        devise.exchangeRate = new BigDecimal("1.10");
        com.intermarche.pos.domain.payment.CreditPayment credit =
                mock(com.intermarche.pos.domain.payment.CreditPayment.class);
        when(credit.getMethodKey()).thenReturn("CREDIT");
        credit.paymentIndex = 4;
        credit.amount = new BigDecimal("3.00");
        credit.accountNumber = "ACC-1";
        credit.accountName = "ACME SARL";
        credit.overLimit = true;
        ticket.payments.add(cheque);
        ticket.payments.add(secours);
        ticket.payments.add(devise);
        ticket.payments.add(credit);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> Ticket.findById(100L)).thenReturn(ticket);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            SyncPayloads.TicketDto out = new ObjectMapper().readValue(item.json, SyncPayloads.TicketDto.class);
            assertEquals(4, out.payments.size());
            assertEquals("CHEQUE", out.payments.get(0).methodKey);
            assertEquals("CMC7-LINE", out.payments.get(0).magneticLine);
            assertEquals("BACKUP", out.payments.get(1).methodKey);
            assertEquals("Secours CB", out.payments.get(1).backupMethodLabel);
            assertEquals("TX42", out.payments.get(1).backupTransaction);
            assertTrue(out.payments.get(1).backupManual);
            assertEquals("DEVISE", out.payments.get(2).methodKey);
            assertEquals("USD", out.payments.get(2).currencyCode);
            assertEquals(new BigDecimal("3.30"), out.payments.get(2).currencyAmount);
            assertEquals(new BigDecimal("1.10"), out.payments.get(2).currencyRate);
            assertEquals("CREDIT", out.payments.get(3).methodKey);
            assertEquals("ACC-1", out.payments.get(3).creditAccountNumber);
            assertEquals("ACME SARL", out.payments.get(3).creditAccountName);
            assertTrue(out.payments.get(3).creditOverLimit);
        }
    }

    /**
     * Covers the TICKET case of {@code prepare} with a lean graph: store,
     * cashier and session ternary false arms, empty line and payment loops, and
     * the {@code iso} null arm for the closing date.
     */
    @Test
    void prepareTicketSerializesLeanGraph() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.TICKET;
        row.entityId = 100L;
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "K2";
        ticket.terminalId = "T2";
        ticket.status = Ticket.TicketStatus.CANCELLED;
        ticket.valuationStatus = Ticket.ValuationStatus.NOT_VALUATED;
        ticket.creationDate = LocalDateTime.of(2026, 2, 2, 11, 0, 0);
        ticket.closingDate = null;
        ticket.store = null;
        ticket.cashier = null;
        ticket.session = null;
        ticket.itemCount = 0;
        ticket.totalExcludingTax = new BigDecimal("0.00");
        ticket.totalIncludingTax = new BigDecimal("0.00");
        ticket.totalVat = new BigDecimal("0.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> Ticket.findById(100L)).thenReturn(ticket);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            SyncPayloads.TicketDto out = new ObjectMapper().readValue(item.json, SyncPayloads.TicketDto.class);
            assertEquals("K2", out.ticketNumber);
            assertNull(out.storeCode);
            assertNull(out.cashierLogin);
            assertNull(out.sessionNumber);
            assertNull(out.closingDate);
            assertTrue(out.lines.isEmpty());
            assertTrue(out.payments.isEmpty());
        }
    }

    /**
     * Covers the entity-gone arm of the TICKET case: a vanished ticket yields
     * null.
     */
    @Test
    void prepareTicketReturnsNullWhenTicketGone() {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.TICKET;
        row.entityId = 100L;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> Ticket.findById(100L)).thenReturn(null);
            assertNull(service.prepare(1L));
        }
    }

    /**
     * Covers the REFUND case of {@code prepare} with a full graph: refund-method
     * ternary true arm, resolved original ticket (original ternary true arm),
     * session ternary true arm, and one line whose original line resolves
     * (original-line ternary true arm); the payload round-trips as expected.
     */
    @Test
    void prepareRefundSerializesFullGraph() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.REFUND;
        row.entityId = 200L;
        Refund refund = new Refund();
        refund.refundNumber = "R1";
        refund.terminalId = "T1";
        refund.status = Refund.RefundStatus.CLOSED;
        refund.refundMethod = Refund.RefundMethod.CASH;
        refund.originalTicketId = 201L;
        refund.creationDate = LocalDateTime.of(2026, 1, 1, 14, 0, 0);
        refund.totalAmount = new BigDecimal("5.00");
        refund.totalExcludingTax = new BigDecimal("4.00");
        refund.totalVat = new BigDecimal("1.00");
        CashSession session = new CashSession();
        session.sessionNumber = "S1";
        refund.session = session;
        RefundLine refundLine = new RefundLine();
        refundLine.originalLineId = 202L;
        refundLine.productLabel = "Pomme";
        refundLine.quantity = new BigDecimal("1");
        refundLine.price = new BigDecimal("5.00");
        refundLine.vatRate = new BigDecimal("0.2000");
        refund.lines.add(refundLine);
        Ticket original = new Ticket();
        original.ticketNumber = "K1";
        TicketLine originalLine = new TicketLine();
        originalLine.lineUid = "U1";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> Refund.findById(200L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(201L)).thenReturn(original);
            mocked.when(() -> TicketLine.findById(202L)).thenReturn(originalLine);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            assertEquals("refund", item.pathSuffix);
            SyncPayloads.RefundDto out = new ObjectMapper().readValue(item.json, SyncPayloads.RefundDto.class);
            assertEquals("R1", out.refundNumber);
            assertEquals("CASH", out.refundMethod);
            assertEquals("K1", out.originalTicketNumber);
            assertEquals("S1", out.sessionNumber);
            assertEquals("2026-01-01T14:00:00", out.creationDate);
            assertEquals(1, out.lines.size());
            assertEquals("U1", out.lines.get(0).originalLineUid);
        }
    }

    /**
     * Covers the REFUND case of {@code prepare} with nulls: refund-method
     * ternary false arm, vanished original ticket (original ternary false arm),
     * null session (session ternary false arm) and a line whose original line
     * vanished (original-line ternary false arm).
     */
    @Test
    void prepareRefundSerializesWithNulls() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.REFUND;
        row.entityId = 200L;
        Refund refund = new Refund();
        refund.refundNumber = "R2";
        refund.terminalId = "T2";
        refund.status = Refund.RefundStatus.CLOSED;
        refund.refundMethod = null;
        refund.originalTicketId = 201L;
        refund.creationDate = LocalDateTime.of(2026, 2, 2, 15, 0, 0);
        refund.totalAmount = new BigDecimal("3.00");
        refund.session = null;
        RefundLine refundLine = new RefundLine();
        refundLine.originalLineId = 202L;
        refundLine.productLabel = "Poire";
        refundLine.quantity = new BigDecimal("1");
        refundLine.price = new BigDecimal("3.00");
        refund.lines.add(refundLine);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> Refund.findById(200L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(201L)).thenReturn(null);
            mocked.when(() -> TicketLine.findById(202L)).thenReturn(null);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            SyncPayloads.RefundDto out = new ObjectMapper().readValue(item.json, SyncPayloads.RefundDto.class);
            assertEquals("R2", out.refundNumber);
            assertNull(out.refundMethod);
            assertNull(out.originalTicketNumber);
            assertNull(out.sessionNumber);
            assertEquals(1, out.lines.size());
            assertNull(out.lines.get(0).originalLineUid);
        }
    }

    /**
     * Covers the entity-gone arm of the REFUND case: a vanished refund yields
     * null.
     */
    @Test
    void prepareRefundReturnsNullWhenRefundGone() {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.REFUND;
        row.entityId = 200L;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> Refund.findById(200L)).thenReturn(null);
            assertNull(service.prepare(1L));
        }
    }

    /**
     * Covers the EVENT case of {@code prepare}: a present event with a non-null
     * date is serialized ({@code iso} non-null arm) and round-trips.
     */
    @Test
    void prepareEventSerializes() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.EVENT;
        row.entityId = 100L;
        TechnicalEvent event = new TechnicalEvent();
        event.eventUid = "EV1";
        event.terminalId = "T1";
        event.eventType = TechnicalEvent.EventType.TICKET_CLOSED;
        event.detail = "detail";
        event.operatorBadgeId = "12341234";
        event.eventDate = LocalDateTime.of(2026, 1, 1, 17, 0, 0);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> TechnicalEvent.findById(100L)).thenReturn(event);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            assertEquals("event", item.pathSuffix);
            SyncPayloads.EventDto out = new ObjectMapper().readValue(item.json, SyncPayloads.EventDto.class);
            assertEquals("EV1", out.eventUid);
            assertEquals("TICKET_CLOSED", out.type);
            assertEquals("detail", out.detail);
            assertEquals("12341234", out.operatorBadgeId);
            assertEquals("2026-01-01T17:00:00", out.eventDate);
        }
    }

    /**
     * Covers the entity-gone arm of the EVENT case: a vanished event yields
     * null.
     */
    @Test
    void prepareEventReturnsNullWhenEventGone() {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.EVENT;
        row.entityId = 100L;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> TechnicalEvent.findById(100L)).thenReturn(null);
            assertNull(service.prepare(1L));
        }
    }

    /**
     * Covers the CUSTOMER case of {@code prepare} with a full graph
     * (LC-08-04-09): the switch CUSTOMER arm, the customer-present ternary true
     * arm and the address-present null-guard true arm; the payload is serialized
     * to the {@code customer} path and round-trips to the expected values,
     * including the address fields.
     */
    @Test
    void prepareCustomerSerializesFullGraph() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.CUSTOMER;
        row.entityId = 400L;
        com.intermarche.pos.domain.payment.AccountCustomer customer =
                new com.intermarche.pos.domain.payment.AccountCustomer();
        customer.accountNumber = "AC1";
        customer.companyName = "ACME SARL";
        customer.lastName = "Doe";
        customer.firstName = "John";
        com.intermarche.pos.domain.store.Address address = new com.intermarche.pos.domain.store.Address();
        address.streetLine1 = "1 rue de la Paix";
        address.postalCode = "75002";
        address.city = "Paris";
        customer.address = address;
        customer.siret = "12345678900011";
        customer.vatNumber = "FR12345678900";
        customer.phone = "0102030405";
        customer.email = "contact@acme.example";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> com.intermarche.pos.domain.payment.AccountCustomer.findById(400L)).thenReturn(customer);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            assertEquals("customer", item.pathSuffix);
            SyncPayloads.CustomerDto out =
                    new ObjectMapper().readValue(item.json, SyncPayloads.CustomerDto.class);
            assertEquals("AC1", out.accountNumber);
            assertEquals("ACME SARL", out.companyName);
            assertEquals("Doe", out.lastName);
            assertEquals("John", out.firstName);
            assertEquals("1 rue de la Paix", out.street);
            assertEquals("75002", out.postalCode);
            assertEquals("Paris", out.city);
            assertEquals("12345678900011", out.siret);
            assertEquals("FR12345678900", out.vatNumber);
            assertEquals("0102030405", out.phone);
            assertEquals("contact@acme.example", out.email);
        }
    }

    /**
     * Covers the CUSTOMER case of {@code prepare} with a null address: the
     * address-present null-guard false arm leaves the street, postal code and
     * city null in the payload.
     */
    @Test
    void prepareCustomerSerializesWithoutAddress() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.CUSTOMER;
        row.entityId = 400L;
        com.intermarche.pos.domain.payment.AccountCustomer customer =
                new com.intermarche.pos.domain.payment.AccountCustomer();
        customer.accountNumber = "AC2";
        customer.companyName = "SOLO SARL";
        customer.address = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> com.intermarche.pos.domain.payment.AccountCustomer.findById(400L)).thenReturn(customer);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            SyncPayloads.CustomerDto out =
                    new ObjectMapper().readValue(item.json, SyncPayloads.CustomerDto.class);
            assertEquals("AC2", out.accountNumber);
            assertEquals("SOLO SARL", out.companyName);
            assertNull(out.street);
            assertNull(out.postalCode);
            assertNull(out.city);
        }
    }

    /**
     * Covers the entity-gone arm of the CUSTOMER case (the customer-present
     * ternary false arm): a vanished customer yields null.
     */
    @Test
    void prepareCustomerReturnsNullWhenCustomerGone() {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.CUSTOMER;
        row.entityId = 400L;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> com.intermarche.pos.domain.payment.AccountCustomer.findById(400L)).thenReturn(null);
            assertNull(service.prepare(1L));
        }
    }

    /**
     * Covers the serialization catch arm of {@code prepare}: a mapper that
     * throws makes preparation return null rather than propagate.
     */
    @Test
    void prepareReturnsNullWhenSerializationFails() throws Exception {
        SyncOutboxService service = new SyncOutboxService();
        service.endpoints = SyncEndpoints.of("http://store");
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
        service.objectMapper = mapper;
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.SESSION;
        row.entityId = 100L;
        CashSession session = new CashSession();
        session.sessionNumber = "S1";
        session.status = CashSession.SessionStatus.OPEN;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> CashSession.findById(100L)).thenReturn(session);
            assertNull(service.prepare(1L));
        }
    }

    // --------------------------------------------------
    // prepare — MOVEMENT (lot C5a, BO-04-01-12/33/35/36/37/40/44)
    // --------------------------------------------------

    /**
     * Covers the MOVEMENT case of {@code prepare} with a full graph: session
     * and cashier ternary true arms and the {@code iso} non-null arm; the
     * payload is serialized to the {@code movement} path and round-trips to the
     * expected natural keys and values.
     */
    @Test
    void prepareMovementSerializesFullGraph() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.MOVEMENT;
        row.entityId = 300L;
        CashMovement movement = new CashMovement();
        movement.movementUid = "M1";
        movement.terminalId = "C04";
        movement.type = CashMovement.MovementType.WITHDRAWAL;
        movement.amount = new BigDecimal("30.00");
        movement.reason = "coffre";
        movement.movementDate = LocalDateTime.of(2026, 9, 1, 15, 42, 0);
        movement.endorsedBy = "11111111";
        CashSession session = new CashSession();
        session.sessionNumber = "C04-S00001";
        movement.session = session;
        Employee cashier = new Employee();
        cashier.loginName = "jdupont";
        movement.cashier = cashier;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> CashMovement.findById(300L)).thenReturn(movement);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            assertEquals("movement", item.pathSuffix);
            SyncPayloads.MovementDto out =
                    new ObjectMapper().readValue(item.json, SyncPayloads.MovementDto.class);
            assertEquals("M1", out.movementUid);
            assertEquals("C04", out.terminalId);
            assertEquals("C04-S00001", out.sessionNumber);
            assertEquals("jdupont", out.cashierLogin);
            assertEquals("WITHDRAWAL", out.type);
            assertEquals(new BigDecimal("30.00"), out.amount);
            assertEquals("coffre", out.reason);
            assertEquals("2026-09-01T15:42:00", out.movementDate);
            assertEquals("11111111", out.endorsedBy);
        }
    }

    /**
     * Covers the MOVEMENT case of {@code prepare} with null session, null
     * cashier and null date: both natural-key ternary false arms and the
     * {@code iso} null arm.
     */
    @Test
    void prepareMovementSerializesWithNulls() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.MOVEMENT;
        row.entityId = 300L;
        CashMovement movement = new CashMovement();
        movement.movementUid = "M2";
        movement.terminalId = "C04";
        movement.type = CashMovement.MovementType.DECLARATION;
        movement.amount = new BigDecimal("0.00");
        movement.reason = null;
        movement.movementDate = null;
        movement.session = null;
        movement.cashier = null;
        movement.endorsedBy = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> CashMovement.findById(300L)).thenReturn(movement);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            SyncPayloads.MovementDto out =
                    new ObjectMapper().readValue(item.json, SyncPayloads.MovementDto.class);
            assertEquals("M2", out.movementUid);
            assertEquals("DECLARATION", out.type);
            assertNull(out.sessionNumber);
            assertNull(out.cashierLogin);
            assertNull(out.reason);
            assertNull(out.movementDate);
            assertNull(out.endorsedBy);
        }
    }

    /**
     * Covers the entity-gone arm of the MOVEMENT case: a vanished movement
     * yields null.
     */
    @Test
    void prepareMovementReturnsNullWhenMovementGone() {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.MOVEMENT;
        row.entityId = 300L;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> CashMovement.findById(300L)).thenReturn(null);
            assertNull(service.prepare(1L));
        }
    }

    // --------------------------------------------------
    // markSuccess / markGone
    // --------------------------------------------------

    /**
     * Covers {@code markSuccess}: the row is deleted by id.
     */
    @Test
    void markSuccessDeletesRow() {
        SyncOutboxService service = new SyncOutboxService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.deleteById(5L)).thenReturn(true);
            service.markSuccess(5L);
            mocked.verify(() -> SyncOutbox.deleteById(5L));
        }
    }

    /**
     * Covers {@code markGone}: the row of a vanished entity is deleted by id.
     */
    @Test
    void markGoneDeletesRow() {
        SyncOutboxService service = new SyncOutboxService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.deleteById(6L)).thenReturn(true);
            service.markGone(6L);
            mocked.verify(() -> SyncOutbox.deleteById(6L));
        }
    }

    // --------------------------------------------------
    // markFailure
    // --------------------------------------------------

    /**
     * Covers the row-null arm of {@code markFailure}: a vanished row is a no-op.
     */
    @Test
    void markFailureDoesNothingWhenRowMissing() {
        SyncOutboxService service = new SyncOutboxService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(9L)).thenReturn(null);
            service.markFailure(9L, "error");
            mocked.verify(() -> SyncOutbox.findById(9L));
        }
    }

    /**
     * Covers the short-error arm of {@code markFailure}: a non-null error under
     * 256 chars is stored verbatim (error-non-null true arm, length false arm)
     * and the attempt counter is bumped.
     */
    @Test
    void markFailureRecordsShortError() {
        SyncOutboxService service = new SyncOutboxService();
        SyncOutbox row = mock(SyncOutbox.class);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(10L)).thenReturn(row);
            service.markFailure(10L, "boom");
            assertEquals(1, row.attempts);
            assertEquals("boom", row.lastError);
            verify(row, times(1)).persist();
        }
    }

    /**
     * Covers the null-error arm of {@code markFailure}: a null error short-
     * circuits the {@code &&} (error-non-null false arm) and is stored as null.
     */
    @Test
    void markFailureAcceptsNullError() {
        SyncOutboxService service = new SyncOutboxService();
        SyncOutbox row = mock(SyncOutbox.class);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(11L)).thenReturn(row);
            service.markFailure(11L, null);
            assertEquals(1, row.attempts);
            assertNull(row.lastError);
            verify(row, times(1)).persist();
        }
    }

    /**
     * Covers the long-error arm of {@code markFailure}: an error over 255 chars
     * is truncated to 255 (length true arm).
     */
    @Test
    void markFailureTruncatesLongError() {
        SyncOutboxService service = new SyncOutboxService();
        SyncOutbox row = mock(SyncOutbox.class);
        String longError = "x".repeat(300);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(12L)).thenReturn(row);
            service.markFailure(12L, longError);
            assertEquals(1, row.attempts);
            assertEquals(255, row.lastError.length());
            assertEquals(longError.substring(0, 255), row.lastError);
            verify(row, times(1)).persist();
        }
    }

    /**
     * Builds a real outbox POJO with the given attempt count and error, for the
     * backlog aggregation (no persistence, only field reads).
     *
     * @param attempts the failed-attempt count
     * @param lastError the last error, or null
     * @return the populated row
     */
    private SyncOutbox row(int attempts, String lastError) {
        SyncOutbox row = new SyncOutbox();
        row.attempts = attempts;
        row.lastError = lastError;
        return row;
    }

    /**
     * Covers {@code backlog}: an empty kind is skipped (isEmpty true arm); a
     * populated kind is aggregated (isEmpty false arm) with the worst attempt
     * count — the {@code attempts > maxAttempts} true arm (5 &gt; 0) and false
     * arm (2 is not &gt; 5) — and the latest non-null error, the
     * {@code lastError != null} true arm (an error present) and false arm (a
     * clean row leaves the accumulator null). Rows come back in drain (enum)
     * order.
     */
    @Test
    void backlogAggregatesPerKindInDrainOrder() {
        SyncOutboxService service = new SyncOutboxService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.list("entityType", SyncOutbox.EntityType.SESSION))
                    .thenReturn(List.of());
            mocked.when(() -> SyncOutbox.list("entityType", SyncOutbox.EntityType.MOVEMENT))
                    .thenReturn(List.of(row(0, null)));
            mocked.when(() -> SyncOutbox.list("entityType", SyncOutbox.EntityType.TICKET))
                    .thenReturn(List.of(row(5, "e1"), row(2, "e2")));
            mocked.when(() -> SyncOutbox.list("entityType", SyncOutbox.EntityType.REFUND))
                    .thenReturn(List.of());
            mocked.when(() -> SyncOutbox.list("entityType", SyncOutbox.EntityType.EVENT))
                    .thenReturn(List.of());
            mocked.when(() -> SyncOutbox.list("entityType", SyncOutbox.EntityType.CUSTOMER))
                    .thenReturn(List.of());
            List<SyncOutboxService.BacklogRow> backlog = service.backlog();
            assertEquals(2, backlog.size());
            SyncOutboxService.BacklogRow movement = backlog.get(0);
            assertEquals("MOVEMENT", movement.type());
            assertEquals(1L, movement.count());
            assertEquals(0, movement.maxAttempts());
            assertNull(movement.lastError());
            SyncOutboxService.BacklogRow ticket = backlog.get(1);
            assertEquals("TICKET", ticket.type());
            assertEquals(2L, ticket.count());
            assertEquals(5, ticket.maxAttempts());
            assertEquals("e2", ticket.lastError());
        }
    }

    /**
     * Builds the outbox row and the document a DOCUMENT payload is drawn from.
     *
     * @return the document, its row already numbered 1
     */
    private com.intermarche.pos.domain.sale.Invoice billedDocument() {
        com.intermarche.pos.domain.sale.Invoice document =
                new com.intermarche.pos.domain.sale.Invoice();
        document.documentNumber = "C04-F000123";
        document.documentType = com.intermarche.pos.domain.sale.DocumentType.FACTURE;
        document.terminalId = "C04";
        document.issueDate = LocalDateTime.of(2026, 9, 10, 18, 42);
        document.ticketNumber = "C04-T000045";
        document.customerAccountNumber = "ACC-1";
        document.customerName = "ACME";
        document.customerTaxId = "PT1";
        document.customerDueDate = java.time.LocalDate.of(2026, 10, 31);
        document.totalExcludingTax = new BigDecimal("100.00");
        document.totalIncludingTax = new BigDecimal("120.00");
        document.totalVat = new BigDecimal("20.00");
        document.totalEcoTax = new BigDecimal("1.50");
        document.printCount = 1;
        return document;
    }

    /**
     * A shop that extracts both carries the due date and the eco-tax on the
     * payload ({@code BO-10-04-19} and {@code BO-10-04-14}, true arms).
     */
    @Test
    void prepareDocumentCarriesTheDueDateAndTheEcoTaxWhenTheShopExtractsThem()
            throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.DOCUMENT;
        row.entityId = 100L;
        com.intermarche.pos.domain.sale.Invoice document = billedDocument();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> com.intermarche.pos.domain.sale.Invoice.findById(100L))
                    .thenReturn(document);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            assertEquals("document", item.pathSuffix);
            SyncPayloads.DocumentDto out =
                    new ObjectMapper().readValue(item.json, SyncPayloads.DocumentDto.class);
            assertEquals("2026-10-31", out.customerDueDate);
            assertEquals(new BigDecimal("1.50"), out.totalEcoTax);
            assertEquals("PT1", out.customerTaxId);
        }
    }

    /**
     * A shop that extracts neither sends neither, although the document carries
     * both ({@code BO-10-04-19} and {@code BO-10-04-14}, false arms).
     */
    @Test
    void prepareDocumentWithholdsThemWhenTheShopDoesNotExtractThem() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        org.mockito.Mockito.when(service.posSettingsService.invoiceExportDueDate())
                .thenReturn(false);
        org.mockito.Mockito.when(service.posSettingsService.invoiceExportEcoTax())
                .thenReturn(false);
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.DOCUMENT;
        row.entityId = 100L;
        com.intermarche.pos.domain.sale.Invoice document = billedDocument();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> com.intermarche.pos.domain.sale.Invoice.findById(100L))
                    .thenReturn(document);
            SyncPayloads.DocumentDto out = new ObjectMapper()
                    .readValue(service.prepare(1L).json, SyncPayloads.DocumentDto.class);
            assertNull(out.customerDueDate);
            assertNull(out.totalEcoTax);
        }
    }

    /**
     * A document carrying NO due date sends none even when the shop extracts them
     * — the null arm of the first leg, which the parameter alone would not reach.
     */
    @Test
    void prepareDocumentSendsNoDueDateWhenTheDocumentCarriesNone() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.DOCUMENT;
        row.entityId = 100L;
        com.intermarche.pos.domain.sale.Invoice document = billedDocument();
        document.customerDueDate = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> com.intermarche.pos.domain.sale.Invoice.findById(100L))
                    .thenReturn(document);
            SyncPayloads.DocumentDto out = new ObjectMapper()
                    .readValue(service.prepare(1L).json, SyncPayloads.DocumentDto.class);
            assertNull(out.customerDueDate);
            assertEquals(new BigDecimal("1.50"), out.totalEcoTax);
        }
    }
}
