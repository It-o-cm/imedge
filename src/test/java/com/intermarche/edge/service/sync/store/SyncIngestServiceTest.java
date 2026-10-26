package com.intermarche.edge.service.sync.store;

import com.intermarche.edge.domain.barcode.BalanceTicket;
import com.intermarche.edge.domain.barcode.BalanceTicketLine;
import com.intermarche.edge.domain.session.CashMovement;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.store.Store;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.RefundLine;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.payment.VoucherPayment;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.intermarche.edge.service.sync.SyncPayloads;
import com.intermarche.edge.service.sync.TicketStreamService;

/**
 * Unit tests for {@link SyncIngestService}.
 * <p>
 * The service is a Panache active-record consumer: every ingest method
 * resolves natural keys through inherited static finders ({@code X.find(...)},
 * {@code Store.findAll()}, {@code Product.findByEan/findByPlu}) and persists
 * the upserted graph. All static access is intercepted with
 * {@link org.mockito.Mockito#mockStatic} on {@link PanacheEntityBase} (the
 * class the inherited {@code find}/{@code findAll} resolve to under plain
 * {@code mvn test}, and the one {@code Product.findByPlu}/{@code findByEan}
 * delegate to), and every {@code new X()} on the insert path is neutralized
 * with {@link org.mockito.Mockito#mockConstruction}: the constructed instance
 * is a mock whose {@code persist()} is inert and whose owned collections are
 * seeded to real lists so {@code clear()}/{@code add()} behave. Found entities
 * are plain Mockito mocks (fields read/written directly). Payment factories are
 * mocks registered through a mocked CDI {@link Instance}; no database and no
 * Quarkus context is booted.
 * <p>
 * Branch enumeration — 29 two-way decision points (58 branches), every arm
 * exercised: {@code init} loop; {@code ingestSession} (create/update,
 * closing-cashier ternary, created ternary); {@code ingestTicket}
 * (create/update, session ternary, lines loop, PLU/EAN product-resolution
 * arms, created ternary, payments loop, voucher-key ternary, unknown-factory
 * throw, {@code instanceof VoucherPayment}); {@code ingestRefund}
 * (missing-original throw, create/update, refund-method ternary, session
 * ternary, lines loop, original-line ternary both arms, missing-line throw,
 * created ternary); {@code ingestEvent} (create/update); the shared
 * {@code requireEmployee} (login ternary + not-found throw),
 * {@code requireStore} (code ternary, fallback arm, no-store throw) and
 * {@code parse} (null/non-null) — 100%.
 */
class SyncIngestServiceTest {

    /**
     * Builds a Panache query whose {@code firstResult} resolves to the given
     * value.
     *
     * @param result the value the query must return
     * @param <T> the queried type
     * @return the configured mocked query
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> queryReturning(T result) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * Builds a service whose payment factories are indexed from the supplied
     * mocks by invoking {@link SyncIngestService#init()}.
     *
     * @param factories the payment factories to register
     * @return the initialized service
     */
    @SuppressWarnings("unchecked")
    private SyncIngestService serviceWith(TicketPayment.Factory... factories) {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        Instance<TicketPayment.Factory> instance = mock(Instance.class);
        when(instance.iterator()).thenReturn(List.of(factories).iterator());
        service.factoryInstances = instance;
        // BO-05-02-16: every consolidated ticket moves the coin machines of its
        // register, so the collaborator belongs to the fixture. What it does with
        // the figure is proved on CoinMachineService itself.
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        service.safeService = mock(com.intermarche.edge.service.SafeService.class);
        // BO-11-02-02/03: every consolidated ticket is enqueued for the
        // at-the-flow subscribers, so the collaborator belongs to the fixture.
        // What it does with the ticket is proved on TicketStreamService itself.
        service.ticketStreamService = mock(TicketStreamService.class);
        service.init();
        return service;
    }

    // --------------------------------------------------
    // ingestSession
    // --------------------------------------------------

    /**
     * Covers the insert arm of {@code ingestSession} with a non-null closing
     * cashier (ternary true arm), a non-null opening date (parse true arm) and
     * a null closing date (parse false arm); the created row is stamped and
     * persisted and the "créée" log arm is taken.
     */
    @Test
    void ingestSessionCreatesWithClosingCashier() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        SyncPayloads.SessionDto dto = new SyncPayloads.SessionDto();
        dto.sessionNumber = "S1";
        dto.terminalId = "T1";
        dto.status = "OPEN";
        dto.openingDate = "2026-01-01T08:00:00";
        dto.closingDate = null;
        dto.openingCashierLogin = "alice";
        dto.closingCashierLogin = "bob";
        dto.openingFloat = new BigDecimal("50.00");
        dto.countedAmount = new BigDecimal("120.00");
        dto.theoreticalAmount = new BigDecimal("119.00");
        dto.variance = new BigDecimal("1.00");
        dto.withdrawnAmount = new BigDecimal("70.00");
        dto.countDetail = "detail";
        Employee opening = mock(Employee.class);
        Employee closing = mock(Employee.class);
        PanacheQuery<CashSession> sessionQuery = queryReturning(null);
        PanacheQuery<Employee> openingQuery = queryReturning(opening);
        PanacheQuery<Employee> closingQuery = queryReturning(closing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashSession> created = mockConstruction(CashSession.class)) {
            mocked.when(() -> CashSession.find("sessionNumber", "S1")).thenReturn(sessionQuery);
            mocked.when(() -> Employee.find("loginName", "alice")).thenReturn(openingQuery);
            mocked.when(() -> Employee.find("loginName", "bob")).thenReturn(closingQuery);
            service.ingestSession(dto);
            CashSession session = created.constructed().get(0);
            assertEquals("S1", session.sessionNumber);
            assertEquals("T1", session.terminalId);
            assertEquals(CashSession.SessionStatus.OPEN, session.status);
            assertEquals(LocalDateTime.of(2026, 1, 1, 8, 0, 0), session.openingDate);
            assertNull(session.closingDate);
            assertSame(opening, session.openingCashier);
            assertSame(closing, session.closingCashier);
            assertEquals(new BigDecimal("50.00"), session.openingFloat);
            assertEquals("detail", session.countDetail);
            verify(session, times(1)).persist();
        }
    }

    /**
     * Covers the update arm of {@code ingestSession} with a null closing
     * cashier (ternary false arm) and a non-null closing date (parse true arm);
     * the existing row is refreshed, persisted and the "mise à jour" log arm is
     * taken.
     */
    @Test
    void ingestSessionUpdatesWithoutClosingCashier() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        SyncPayloads.SessionDto dto = new SyncPayloads.SessionDto();
        dto.sessionNumber = "S2";
        dto.terminalId = "T2";
        dto.status = "CLOSED";
        dto.openingDate = "2026-02-02T09:00:00";
        dto.closingDate = "2026-02-02T18:00:00";
        dto.openingCashierLogin = "alice";
        dto.closingCashierLogin = null;
        dto.openingFloat = new BigDecimal("40.00");
        Employee opening = mock(Employee.class);
        CashSession existing = mock(CashSession.class);
        PanacheQuery<CashSession> sessionQuery = queryReturning(existing);
        PanacheQuery<Employee> openingQuery = queryReturning(opening);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CashSession.find("sessionNumber", "S2")).thenReturn(sessionQuery);
            mocked.when(() -> Employee.find("loginName", "alice")).thenReturn(openingQuery);
            service.ingestSession(dto);
            assertEquals("T2", existing.terminalId);
            assertEquals(CashSession.SessionStatus.CLOSED, existing.status);
            assertEquals(LocalDateTime.of(2026, 2, 2, 18, 0, 0), existing.closingDate);
            assertSame(opening, existing.openingCashier);
            assertNull(existing.closingCashier);
            verify(existing, times(1)).persist();
        }
    }

    /**
     * Covers the null-login arm of {@code requireEmployee} (ternary false arm)
     * and its unresolved-employee throw arm: a session whose opening cashier
     * login is null raises {@link IllegalStateException}.
     */
    @Test
    void ingestSessionThrowsOnUnknownEmployee() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        SyncPayloads.SessionDto dto = new SyncPayloads.SessionDto();
        dto.sessionNumber = "S3";
        dto.status = "OPEN";
        dto.openingDate = "2026-03-03T10:00:00";
        dto.openingCashierLogin = null;
        PanacheQuery<CashSession> sessionQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CashSession.find("sessionNumber", "S3")).thenReturn(sessionQuery);
            assertThrows(IllegalStateException.class, () -> service.ingestSession(dto));
        }
    }

    // --------------------------------------------------
    // ingestTicket
    // --------------------------------------------------

    /**
     * Covers the insert arm of {@code ingestTicket} end to end: a resolved
     * store (code ternary true arm, first store guard false), a resolved
     * cashier, a non-null session (session ternary true arm), the three
     * line product-resolution arms (PLU present, EAN-only, neither) and both
     * payment kinds (voucher — voucher-key ternary true arm and
     * {@code instanceof} true arm; card — both false arms). The "créé" log arm
     * is taken and the graph is persisted.
     */
    @Test
    void ingestTicketCreatesWithLinesPaymentsAndSession() {
        TicketPayment.Factory cardFactory = mock(TicketPayment.Factory.class);
        TicketPayment.Factory voucherFactory = mock(TicketPayment.Factory.class);
        when(cardFactory.getKey()).thenReturn("CB");
        when(voucherFactory.getKey()).thenReturn("VOUCHER");
        TicketPayment cardPayment = mock(TicketPayment.class);
        VoucherPayment voucherPayment = mock(VoucherPayment.class);
        when(cardFactory.create(any(), any())).thenReturn(cardPayment);
        when(voucherFactory.create(any(), any())).thenReturn(voucherPayment);
        SyncIngestService service = serviceWith(cardFactory, voucherFactory);
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        dto.ticketNumber = "K1";
        dto.terminalId = "T1";
        dto.status = "CLOSED";
        dto.creationDate = "2026-01-01T10:00:00";
        dto.closingDate = null;
        dto.storeCode = "ST1";
        dto.cashierLogin = "alice";
        dto.sessionNumber = "S1";
        dto.fidelityCard = "F1";
        dto.customerTaxId = "123456789";
        // BO-03-03-25/-29/-31: the loyalty zone the selling register froze.
        dto.fidelityEarnTotal = new BigDecimal("1.03");
        dto.fidelityAvailableBalance = new BigDecimal("42.30");
        dto.fidelityUnavailable = true;
        dto.fidelityPotBalances = "Cagnotte\t12.50";
        // BO-03-03-28/31: the projected/after balances and the anonymous flag an
        // up-to-date register pushes.
        dto.fidelityProjectedAfterEarn = new BigDecimal("43.33");
        dto.fidelityBalanceAfter = new BigDecimal("39.30");
        dto.fidelityAnonymousProjection = true;
        SyncPayloads.FidelityLineDto earnDto = new SyncPayloads.FidelityLineDto();
        earnDto.ruleCode = "SOCLE";
        earnDto.label = "Cagnotte socle";
        earnDto.amount = new BigDecimal("0.28");
        // BO-03-03-25: the advantage grouping an up-to-date register pushes.
        earnDto.advantageType = "TYPE_A";
        earnDto.advantageTypeLabel = "Avantages";
        earnDto.advantageTypeOrder = 1;
        earnDto.advantageCategory = "CAT";
        earnDto.advantageCategoryLabel = "Categorie";
        dto.fidelityLines.add(earnDto);
        // A line from an un-updated register: no advantage grouping — it must
        // persist with null grouping (backward compatibility on the wire).
        SyncPayloads.FidelityLineDto legacyEarn = new SyncPayloads.FidelityLineDto();
        legacyEarn.ruleCode = "LEGACY";
        legacyEarn.label = "Ancienne ligne";
        legacyEarn.amount = new BigDecimal("0.10");
        dto.fidelityLines.add(legacyEarn);
        dto.itemCount = 3;
        dto.totalExcludingTax = new BigDecimal("10.00");
        dto.totalIncludingTax = new BigDecimal("12.00");
        dto.totalVat = new BigDecimal("2.00");
        dto.grandTotal = new BigDecimal("100.00");
        dto.valuationStatus = "NOT_VALUATED";
        SyncPayloads.LineDto byPlu = new SyncPayloads.LineDto();
        byPlu.lineNumber = 1;
        byPlu.linkedParentUid = "BOTTLE";
        byPlu.linkedArticleEan = "DEPOSIT";
        byPlu.linkedRecordedQuantity = new BigDecimal("3");
        byPlu.plu = "100";
        byPlu.quantity = new BigDecimal("1");
        byPlu.familyCode = "FRUITS";
        byPlu.familyLabel = "Rayon Fruits";
        byPlu.cancelled = true;
        byPlu.cancellationDate = "2026-09-01T15:42:00";
        byPlu.cancelledBy = "12341234";
        byPlu.moneyProduct = true;
        byPlu.cancelledLast = true;
        byPlu.appliedTareKg = new java.math.BigDecimal("0.050");
        byPlu.returnLine = true;
        byPlu.service = true;
        SyncPayloads.LineDto byEan = new SyncPayloads.LineDto();
        byEan.lineNumber = 2;
        byEan.plu = null;
        byEan.ean = "E1";
        SyncPayloads.LineDto noRef = new SyncPayloads.LineDto();
        noRef.lineNumber = 3;
        noRef.plu = null;
        noRef.ean = null;
        dto.lines.add(byPlu);
        dto.lines.add(byEan);
        dto.lines.add(noRef);
        SyncPayloads.PaymentDto voucherDto = new SyncPayloads.PaymentDto();
        voucherDto.paymentIndex = 1;
        voucherDto.methodKey = "CB";
        voucherDto.amount = new BigDecimal("5.00");
        voucherDto.voucherLabel = "Bon";
        voucherDto.voucherNumber = "V9";
        SyncPayloads.PaymentDto cardDto = new SyncPayloads.PaymentDto();
        cardDto.paymentIndex = 2;
        cardDto.methodKey = "CB";
        cardDto.amount = new BigDecimal("7.00");
        cardDto.tenderedAmount = new BigDecimal("7.00");
        cardDto.voucherLabel = null;
        dto.payments.add(voucherDto);
        dto.payments.add(cardDto);
        Store store = mock(Store.class);
        Employee cashier = mock(Employee.class);
        CashSession session = mock(CashSession.class);
        Product plaster = mock(Product.class);
        Product apple = mock(Product.class);
        PanacheQuery<Ticket> ticketQuery = queryReturning(null);
        PanacheQuery<Store> storeQuery = queryReturning(store);
        PanacheQuery<Employee> cashierQuery = queryReturning(cashier);
        PanacheQuery<CashSession> sessionQuery = queryReturning(session);
        PanacheQuery<Product> pluQuery = queryReturning(plaster);
        PanacheQuery<Product> eanQuery = queryReturning(apple);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> createdTicket = mockConstruction(Ticket.class,
                        (m, c) -> { m.lines = new ArrayList<>(); m.payments = new ArrayList<>(); });
                MockedConstruction<TicketLine> createdLines = mockConstruction(TicketLine.class)) {
            mocked.when(() -> Ticket.find("ticketNumber", "K1")).thenReturn(ticketQuery);
            mocked.when(() -> Store.find("code", "ST1")).thenReturn(storeQuery);
            mocked.when(() -> Employee.find("loginName", "alice")).thenReturn(cashierQuery);
            mocked.when(() -> CashSession.find("sessionNumber", "S1")).thenReturn(sessionQuery);
            mocked.when(() -> Product.find("plu", "100")).thenReturn(pluQuery);
            mocked.when(() -> Product.find("ean", "E1")).thenReturn(eanQuery);
            service.ingestTicket(dto);
            Ticket ticket = createdTicket.constructed().get(0);
            assertEquals("K1", ticket.ticketNumber);
            assertEquals(Ticket.TicketStatus.CLOSED, ticket.status);
            assertEquals(LocalDateTime.of(2026, 1, 1, 10, 0, 0), ticket.creationDate);
            assertNull(ticket.closingDate);
            assertSame(store, ticket.store);
            assertSame(cashier, ticket.cashier);
            assertSame(session, ticket.session);
            assertEquals(Ticket.ValuationStatus.NOT_VALUATED, ticket.valuationStatus);
            assertEquals("F1", ticket.fidelityCard);
            assertEquals("123456789", ticket.customerTaxId);
            assertEquals(new BigDecimal("1.03"), ticket.fidelityEarnTotal);
            assertEquals(new BigDecimal("42.30"), ticket.fidelityAvailableBalance);
            assertTrue(ticket.fidelityUnavailable);
            assertEquals("Cagnotte\t12.50", ticket.fidelityPotBalances);
            assertEquals(new BigDecimal("43.33"), ticket.fidelityProjectedAfterEarn);
            assertEquals(new BigDecimal("39.30"), ticket.fidelityBalanceAfter);
            assertTrue(ticket.fidelityAnonymousProjection);
            assertEquals(2, ticket.fidelityLines.size());
            assertEquals("SOCLE", ticket.fidelityLines.get(0).ruleCode);
            assertEquals("Cagnotte socle", ticket.fidelityLines.get(0).label);
            assertEquals(new BigDecimal("0.28"), ticket.fidelityLines.get(0).amount);
            assertEquals("TYPE_A", ticket.fidelityLines.get(0).advantageType);
            assertEquals("Avantages", ticket.fidelityLines.get(0).advantageTypeLabel);
            assertEquals(Integer.valueOf(1), ticket.fidelityLines.get(0).advantageTypeOrder);
            assertEquals("CAT", ticket.fidelityLines.get(0).advantageCategory);
            assertEquals("Categorie", ticket.fidelityLines.get(0).advantageCategoryLabel);
            assertNull(ticket.fidelityLines.get(1).advantageType);
            assertNull(ticket.fidelityLines.get(1).advantageTypeOrder);
            assertEquals(3, createdLines.constructed().size());
            assertSame(plaster, createdLines.constructed().get(0).product);
            assertEquals("BOTTLE", createdLines.constructed().get(0).linkedParentUid);
            assertEquals("DEPOSIT", createdLines.constructed().get(0).linkedArticleEan);
            assertEquals(new BigDecimal("3"), createdLines.constructed().get(0).linkedRecordedQuantity);
            assertSame(apple, createdLines.constructed().get(1).product);
            assertNull(createdLines.constructed().get(2).product);
            // The nomenclature snapshot is stored verbatim from the payload,
            // authoritative over the store-side product link (BO-04-01-11):
            // a referential re-parenting never rewrites a consolidated line.
            assertEquals("FRUITS", createdLines.constructed().get(0).familyCode);
            assertEquals("Rayon Fruits", createdLines.constructed().get(0).familyLabel);
            assertNull(createdLines.constructed().get(1).familyCode);
            // Article-cancellation witness ingested verbatim, ISO string parsed
            // back to a LocalDateTime (lot C4, BO-04-01-16); a non-cancelled
            // line reads back false with a null timestamp.
            assertTrue(createdLines.constructed().get(0).cancelled);
            assertEquals(java.time.LocalDateTime.of(2026, 9, 1, 15, 42),
                    createdLines.constructed().get(0).cancellationDate);
            assertEquals("12341234", createdLines.constructed().get(0).cancelledBy);
            // Money-instrument (R12) and last-line-cancellation (R10) flags ingested
            // verbatim from the payload; an untouched line reads both back false.
            assertTrue(createdLines.constructed().get(0).moneyProduct);
            assertTrue(createdLines.constructed().get(0).cancelledLast);
            assertEquals(new java.math.BigDecimal("0.050"), createdLines.constructed().get(0).appliedTareKg);
            assertFalse(createdLines.constructed().get(1).cancelled);
            assertNull(createdLines.constructed().get(1).cancellationDate);
            assertFalse(createdLines.constructed().get(1).moneyProduct);
            assertFalse(createdLines.constructed().get(1).cancelledLast);
            // BO-03-13-11/-12: the return-line flag is stored verbatim.
            assertTrue(createdLines.constructed().get(0).returnLine);
            assertFalse(createdLines.constructed().get(1).returnLine);
            assertTrue(createdLines.constructed().get(0).service);
            assertFalse(createdLines.constructed().get(1).service);
            verify(ticket, times(3)).addLine(any());
            assertEquals("Bon", voucherPayment.voucherLabel);
            assertEquals("V9", voucherPayment.voucherNumber);
            assertEquals(1, voucherPayment.paymentIndex);
            assertEquals(2, cardPayment.paymentIndex);
            verify(ticket).addPayment(voucherPayment);
            verify(ticket).addPayment(cardPayment);
            verify(ticket, times(1)).persist();
        }
    }

    /**
     * Covers the {@code instanceof CardPayment} true arm of {@code ingestTicket}:
     * a rebuilt card payment receives the authorization number and degraded-mode
     * indicator carried by the payload (BO-04-01-08/47/49). A second push of the
     * same ticket number is an upsert (see the update test) — no row is
     * duplicated — so the traces are the store node's stable copy of the sale.
     */
    @Test
    void ingestTicketSetsCardPaymentTraces() {
        TicketPayment.Factory cardFactory = mock(TicketPayment.Factory.class);
        when(cardFactory.getKey()).thenReturn("CARD");
        com.intermarche.edge.domain.payment.CardPayment cardPayment =
                mock(com.intermarche.edge.domain.payment.CardPayment.class);
        when(cardFactory.create(any(), any())).thenReturn(cardPayment);
        SyncIngestService service = serviceWith(cardFactory);
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        dto.ticketNumber = "K5";
        dto.terminalId = "T5";
        dto.status = "CLOSED";
        dto.creationDate = "2026-05-05T10:00:00";
        dto.storeCode = "ST1";
        dto.cashierLogin = "alice";
        dto.sessionNumber = null;
        dto.itemCount = 1;
        dto.totalExcludingTax = new BigDecimal("10.00");
        dto.totalIncludingTax = new BigDecimal("12.00");
        dto.totalVat = new BigDecimal("2.00");
        dto.valuationStatus = "NOT_VALUATED";
        SyncPayloads.PaymentDto cardDto = new SyncPayloads.PaymentDto();
        cardDto.paymentIndex = 1;
        cardDto.methodKey = "CARD";
        cardDto.amount = new BigDecimal("12.00");
        cardDto.voucherLabel = null;
        cardDto.authorizationNumber = "654321";
        cardDto.moneticServer = "2";
        cardDto.degradedMode = true;
        dto.payments.add(cardDto);
        Store store = mock(Store.class);
        Employee cashier = mock(Employee.class);
        PanacheQuery<Ticket> ticketQuery = queryReturning(null);
        PanacheQuery<Store> storeQuery = queryReturning(store);
        PanacheQuery<Employee> cashierQuery = queryReturning(cashier);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> createdTicket = mockConstruction(Ticket.class,
                        (m, c) -> { m.lines = new ArrayList<>(); m.payments = new ArrayList<>(); })) {
            mocked.when(() -> Ticket.find("ticketNumber", "K5")).thenReturn(ticketQuery);
            mocked.when(() -> Store.find("code", "ST1")).thenReturn(storeQuery);
            mocked.when(() -> Employee.find("loginName", "alice")).thenReturn(cashierQuery);
            service.ingestTicket(dto);
            assertEquals("654321", cardPayment.authorizationNumber);
            assertEquals("2", cardPayment.moneticServer);
            assertTrue(cardPayment.degradedMode);
            assertEquals(1, cardPayment.paymentIndex);
            Ticket ticket = createdTicket.constructed().get(0);
            verify(ticket).addPayment(cardPayment);
            verify(ticket, times(1)).persist();
        }
    }

    /**
     * Covers the {@code instanceof DepositPayment} true arm of
     * {@code ingestTicket} (LC-06-03-08): the recovery of a deposit arrives
     * with its per-rate VAT detail and its order code, and the store node
     * holds both on the row — the witness the back office accounts the
     * recovery by rate against its order.
     */
    @Test
    void ingestTicketSetsDepositRecoveryDetails() {
        TicketPayment.Factory acompteFactory = mock(TicketPayment.Factory.class);
        when(acompteFactory.getKey()).thenReturn("ACOMPTE");
        com.intermarche.edge.domain.payment.DepositPayment acomptePayment =
                mock(com.intermarche.edge.domain.payment.DepositPayment.class);
        when(acompteFactory.create(any(), any())).thenReturn(acomptePayment);
        SyncIngestService service = serviceWith(acompteFactory);
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        dto.ticketNumber = "K6";
        dto.terminalId = "T6";
        dto.status = "CLOSED";
        dto.creationDate = "2026-05-06T10:00:00";
        dto.storeCode = "ST1";
        dto.cashierLogin = "alice";
        dto.sessionNumber = null;
        dto.itemCount = 1;
        dto.totalExcludingTax = new BigDecimal("10.00");
        dto.totalIncludingTax = new BigDecimal("12.00");
        dto.totalVat = new BigDecimal("2.00");
        dto.valuationStatus = "NOT_VALUATED";
        SyncPayloads.PaymentDto acompteDto = new SyncPayloads.PaymentDto();
        acompteDto.paymentIndex = 1;
        acompteDto.methodKey = "ACOMPTE";
        acompteDto.amount = new BigDecimal("10.00");
        acompteDto.voucherLabel = null;
        acompteDto.depositVatDetail = "0.2:1;0.055:0.21";
        acompteDto.depositOrderCode = "C04-CMD000001";
        dto.payments.add(acompteDto);
        Store store = mock(Store.class);
        Employee cashier = mock(Employee.class);
        PanacheQuery<Ticket> ticketQuery = queryReturning(null);
        PanacheQuery<Store> storeQuery = queryReturning(store);
        PanacheQuery<Employee> cashierQuery = queryReturning(cashier);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> createdTicket = mockConstruction(Ticket.class,
                        (m, c) -> { m.lines = new ArrayList<>(); m.payments = new ArrayList<>(); })) {
            mocked.when(() -> Ticket.find("ticketNumber", "K6")).thenReturn(ticketQuery);
            mocked.when(() -> Store.find("code", "ST1")).thenReturn(storeQuery);
            mocked.when(() -> Employee.find("loginName", "alice")).thenReturn(cashierQuery);
            service.ingestTicket(dto);
            assertEquals("0.2:1;0.055:0.21", acomptePayment.vatDetail);
            assertEquals("C04-CMD000001", acomptePayment.orderCode);
            assertEquals(1, acomptePayment.paymentIndex);
            Ticket ticket = createdTicket.constructed().get(0);
            verify(ticket).addPayment(acomptePayment);
            verify(ticket, times(1)).persist();
        }
    }

    /**
     * Covers the update arm of {@code ingestTicket} with a null session
     * (session ternary false arm) and a null store code exercising the
     * fallback to the single local store (code ternary false arm, first store
     * guard true, second guard false). The "mis à jour" log arm is taken and no
     * lines or payments are replayed (loop exit arms).
     */
    @Test
    void ingestTicketUpdatesWithFallbackStoreAndNullSession() {
        SyncIngestService service = serviceWith();
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        dto.ticketNumber = "K2";
        dto.terminalId = "T2";
        dto.status = "CANCELLED";
        dto.creationDate = "2026-02-02T11:00:00";
        dto.storeCode = null;
        dto.cashierLogin = "bob";
        dto.sessionNumber = null;
        dto.itemCount = 0;
        dto.totalExcludingTax = new BigDecimal("0.00");
        dto.totalIncludingTax = new BigDecimal("0.00");
        dto.totalVat = new BigDecimal("0.00");
        dto.valuationStatus = "DEGRADED";
        Ticket existing = mock(Ticket.class);
        existing.lines = new ArrayList<>();
        existing.payments = new ArrayList<>();
        Store store = mock(Store.class);
        Employee cashier = mock(Employee.class);
        PanacheQuery<Ticket> ticketQuery = queryReturning(existing);
        PanacheQuery<Store> storeQuery = queryReturning(store);
        PanacheQuery<Employee> cashierQuery = queryReturning(cashier);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.find("ticketNumber", "K2")).thenReturn(ticketQuery);
            mocked.when(Store::findAll).thenReturn(storeQuery);
            mocked.when(() -> Employee.find("loginName", "bob")).thenReturn(cashierQuery);
            service.ingestTicket(dto);
            assertEquals("T2", existing.terminalId);
            assertEquals(Ticket.TicketStatus.CANCELLED, existing.status);
            assertSame(store, existing.store);
            assertSame(cashier, existing.cashier);
            assertNull(existing.session);
            assertEquals(Ticket.ValuationStatus.DEGRADED, existing.valuationStatus);
            verify(existing, times(1)).persist();
        }
    }

    /**
     * A payload naming no valuation status reads as a ticket never valued,
     * rather than failing the ingestion.
     */
    @Test
    void ingestTicketWithoutValuationStatusReadsNotValued() {
        SyncIngestService service = serviceWith();
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        dto.ticketNumber = "K3";
        dto.terminalId = "T3";
        dto.status = "CLOSED";
        dto.creationDate = "2026-02-02T11:00:00";
        dto.cashierLogin = "bob";
        dto.totalIncludingTax = BigDecimal.ONE;
        Ticket existing = mock(Ticket.class);
        existing.lines = new ArrayList<>();
        existing.payments = new ArrayList<>();
        existing.valuationStatus = Ticket.ValuationStatus.DEGRADED;
        PanacheQuery<Ticket> ticketQuery = queryReturning(existing);
        PanacheQuery<Store> storeQuery = queryReturning(mock(Store.class));
        PanacheQuery<Employee> cashierQuery = queryReturning(mock(Employee.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.find("ticketNumber", "K3")).thenReturn(ticketQuery);
            mocked.when(Store::findAll).thenReturn(storeQuery);
            mocked.when(() -> Employee.find("loginName", "bob")).thenReturn(cashierQuery);
            service.ingestTicket(dto);
            assertEquals(Ticket.ValuationStatus.NOT_VALUATED, existing.valuationStatus);
        }
    }

    /**
     * Covers the unknown-factory throw arm of {@code ingestTicket}: a non-voucher
     * payment whose method key has no registered factory (voucher-key ternary
     * false arm, {@code factory == null} true arm) raises
     * {@link IllegalStateException}.
     */
    @Test
    void ingestTicketThrowsOnUnknownPaymentMethod() {
        SyncIngestService service = serviceWith();
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        dto.ticketNumber = "K3";
        dto.terminalId = "T3";
        dto.status = "CLOSED";
        dto.creationDate = "2026-03-03T12:00:00";
        dto.storeCode = "ST1";
        dto.cashierLogin = "alice";
        dto.sessionNumber = null;
        dto.totalExcludingTax = new BigDecimal("1.00");
        dto.totalIncludingTax = new BigDecimal("1.00");
        dto.totalVat = new BigDecimal("0.00");
        dto.valuationStatus = "NOT_VALUATED";
        SyncPayloads.PaymentDto payment = new SyncPayloads.PaymentDto();
        payment.methodKey = "UNKNOWN";
        payment.amount = new BigDecimal("1.00");
        payment.voucherLabel = null;
        dto.payments.add(payment);
        Store store = mock(Store.class);
        Employee cashier = mock(Employee.class);
        PanacheQuery<Ticket> ticketQuery = queryReturning(null);
        PanacheQuery<Store> storeQuery = queryReturning(store);
        PanacheQuery<Employee> cashierQuery = queryReturning(cashier);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> createdTicket = mockConstruction(Ticket.class,
                        (m, c) -> { m.lines = new ArrayList<>(); m.payments = new ArrayList<>(); })) {
            mocked.when(() -> Ticket.find("ticketNumber", "K3")).thenReturn(ticketQuery);
            mocked.when(() -> Store.find("code", "ST1")).thenReturn(storeQuery);
            mocked.when(() -> Employee.find("loginName", "alice")).thenReturn(cashierQuery);
            assertThrows(IllegalStateException.class, () -> service.ingestTicket(dto));
        }
    }

    /**
     * Covers the no-store throw arm of {@code requireStore}: a null store code
     * with an empty local store table (code ternary false arm, first and second
     * store guards both true) raises {@link IllegalStateException}.
     */
    @Test
    void ingestTicketThrowsWhenNoStoreExists() {
        SyncIngestService service = serviceWith();
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        dto.ticketNumber = "K4";
        dto.status = "CLOSED";
        dto.creationDate = "2026-04-04T13:00:00";
        dto.storeCode = null;
        dto.cashierLogin = "alice";
        dto.valuationStatus = "NOT_VALUATED";
        PanacheQuery<Ticket> ticketQuery = queryReturning(null);
        PanacheQuery<Store> storeQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> createdTicket = mockConstruction(Ticket.class,
                        (m, c) -> { m.lines = new ArrayList<>(); m.payments = new ArrayList<>(); })) {
            mocked.when(() -> Ticket.find("ticketNumber", "K4")).thenReturn(ticketQuery);
            mocked.when(Store::findAll).thenReturn(storeQuery);
            assertThrows(IllegalStateException.class, () -> service.ingestTicket(dto));
        }
    }

    /**
     * Covers the {@code fidelityLines == null} false arm of {@code ingestTicket}
     * (line 151): an existing ticket already holding a non-null loyalty-line list
     * has that very list reused — cleared in place, not reconstructed — and
     * refilled from the payload, which is the wholesale-replacement idempotency the
     * upsert relies on.
     */
    @Test
    void ingestTicketReusesExistingFidelityLineList() {
        SyncIngestService service = serviceWith();
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        dto.ticketNumber = "K9";
        dto.terminalId = "T9";
        dto.status = "CLOSED";
        dto.creationDate = "2026-09-09T10:00:00";
        dto.storeCode = "ST1";
        dto.cashierLogin = "alice";
        dto.sessionNumber = null;
        dto.itemCount = 0;
        dto.totalExcludingTax = new BigDecimal("1.00");
        dto.totalIncludingTax = new BigDecimal("1.00");
        dto.totalVat = new BigDecimal("0.00");
        dto.valuationStatus = "NOT_VALUATED";
        SyncPayloads.FidelityLineDto earnDto = new SyncPayloads.FidelityLineDto();
        earnDto.ruleCode = "SOCLE";
        earnDto.label = "Cagnotte socle";
        earnDto.amount = new BigDecimal("0.28");
        dto.fidelityLines.add(earnDto);
        Ticket existing = mock(Ticket.class);
        existing.lines = new ArrayList<>();
        existing.payments = new ArrayList<>();
        List<com.intermarche.edge.domain.sale.TicketFidelityLine> priorLines = new ArrayList<>();
        priorLines.add(new com.intermarche.edge.domain.sale.TicketFidelityLine("OLD", "Ancienne", BigDecimal.ONE));
        existing.fidelityLines = priorLines;
        Store store = mock(Store.class);
        Employee cashier = mock(Employee.class);
        PanacheQuery<Ticket> ticketQuery = queryReturning(existing);
        PanacheQuery<Store> storeQuery = queryReturning(store);
        PanacheQuery<Employee> cashierQuery = queryReturning(cashier);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.find("ticketNumber", "K9")).thenReturn(ticketQuery);
            mocked.when(() -> Store.find("code", "ST1")).thenReturn(storeQuery);
            mocked.when(() -> Employee.find("loginName", "alice")).thenReturn(cashierQuery);
            service.ingestTicket(dto);
            assertSame(priorLines, existing.fidelityLines);
            assertEquals(1, existing.fidelityLines.size());
            assertEquals("SOCLE", existing.fidelityLines.get(0).ruleCode);
            assertEquals("Cagnotte socle", existing.fidelityLines.get(0).label);
            assertEquals(new BigDecimal("0.28"), existing.fidelityLines.get(0).amount);
            verify(existing, times(1)).persist();
        }
    }

    // --------------------------------------------------
    // ingestRefund
    // --------------------------------------------------

    /**
     * Covers the insert arm of {@code ingestRefund} with a resolved original
     * ticket, a non-null refund method (ternary true arm), a non-null session
     * (ternary true arm) and one line whose original-line uid resolves against
     * the original ticket (original-line ternary true arm, missing-line guard
     * false arm). The "créé" log arm is taken and the graph is persisted.
     */
    @Test
    void ingestRefundCreatesWithResolvedLine() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        SyncPayloads.RefundDto dto = new SyncPayloads.RefundDto();
        dto.refundNumber = "R1";
        dto.terminalId = "T1";
        dto.status = "CLOSED";
        dto.refundMethod = "CASH";
        dto.originalTicketNumber = "K1";
        dto.sessionNumber = "S1";
        dto.creationDate = "2026-01-01T14:00:00";
        dto.totalAmount = new BigDecimal("5.00");
        dto.totalExcludingTax = new BigDecimal("4.00");
        dto.totalVat = new BigDecimal("1.00");
        SyncPayloads.RefundLineDto lineDto = new SyncPayloads.RefundLineDto();
        lineDto.originalLineUid = "U1";
        lineDto.productLabel = "Pomme";
        lineDto.quantity = new BigDecimal("1");
        lineDto.price = new BigDecimal("5.00");
        lineDto.vatRate = new BigDecimal("0.2000");
        dto.lines.add(lineDto);
        Ticket original = mock(Ticket.class);
        original.id = 7L;
        TicketLine originalLine = mock(TicketLine.class);
        originalLine.lineUid = "U1";
        originalLine.id = 42L;
        original.lines = new ArrayList<>(List.of(originalLine));
        CashSession session = mock(CashSession.class);
        PanacheQuery<Ticket> ticketQuery = queryReturning(original);
        PanacheQuery<Refund> refundQuery = queryReturning(null);
        PanacheQuery<CashSession> sessionQuery = queryReturning(session);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Refund> createdRefund = mockConstruction(Refund.class,
                        (m, c) -> m.lines = new ArrayList<>());
                MockedConstruction<RefundLine> createdLines = mockConstruction(RefundLine.class)) {
            mocked.when(() -> Ticket.find("ticketNumber", "K1")).thenReturn(ticketQuery);
            mocked.when(() -> Refund.find("refundNumber", "R1")).thenReturn(refundQuery);
            mocked.when(() -> CashSession.find("sessionNumber", "S1")).thenReturn(sessionQuery);
            service.ingestRefund(dto);
            Refund refund = createdRefund.constructed().get(0);
            assertEquals("R1", refund.refundNumber);
            assertEquals("T1", refund.terminalId);
            assertEquals(Refund.RefundStatus.CLOSED, refund.status);
            assertEquals(Refund.RefundMethod.CASH, refund.refundMethod);
            assertEquals(7L, refund.originalTicketId);
            assertSame(session, refund.session);
            assertEquals(LocalDateTime.of(2026, 1, 1, 14, 0, 0), refund.creationDate);
            assertEquals(1, refund.lines.size());
            RefundLine line = createdLines.constructed().get(0);
            assertSame(line, refund.lines.get(0));
            assertEquals(42L, line.originalLineId);
            assertEquals("Pomme", line.productLabel);
            assertEquals(new BigDecimal("5.00"), line.price);
            verify(refund, times(1)).persist();
        }
    }

    /**
     * Covers the update arm of {@code ingestRefund}: an existing refund is
     * refreshed and persisted (created ternary false arm), with no lines
     * replayed (loop exit arm).
     */
    @Test
    void ingestRefundUpdatesExisting() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        SyncPayloads.RefundDto dto = new SyncPayloads.RefundDto();
        dto.refundNumber = "R2";
        dto.terminalId = "T2";
        dto.status = "CLOSED";
        dto.refundMethod = "CARD";
        dto.moneticServer = "2";
        dto.originalTicketNumber = "K2";
        dto.sessionNumber = "S2";
        dto.creationDate = "2026-02-02T15:00:00";
        dto.totalAmount = new BigDecimal("9.00");
        Ticket original = mock(Ticket.class);
        original.id = 8L;
        Refund existing = mock(Refund.class);
        existing.lines = new ArrayList<>();
        CashSession session = mock(CashSession.class);
        PanacheQuery<Ticket> ticketQuery = queryReturning(original);
        PanacheQuery<Refund> refundQuery = queryReturning(existing);
        PanacheQuery<CashSession> sessionQuery = queryReturning(session);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.find("ticketNumber", "K2")).thenReturn(ticketQuery);
            mocked.when(() -> Refund.find("refundNumber", "R2")).thenReturn(refundQuery);
            mocked.when(() -> CashSession.find("sessionNumber", "S2")).thenReturn(sessionQuery);
            service.ingestRefund(dto);
            assertEquals("T2", existing.terminalId);
            assertEquals(Refund.RefundMethod.CARD, existing.refundMethod);
            assertEquals("2", existing.moneticServer);
            assertEquals(8L, existing.originalTicketId);
            assertSame(session, existing.session);
            verify(existing, times(1)).persist();
            verify(service.coinMachineService).refundCashOf(existing);
            verify(service.coinMachineService).collectRefund(existing, null);
        }
    }

    /**
     * Covers the missing-original throw arm of {@code ingestRefund}: an absent
     * original ticket raises {@link IllegalStateException}.
     */
    @Test
    void ingestRefundThrowsWhenOriginalTicketMissing() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        SyncPayloads.RefundDto dto = new SyncPayloads.RefundDto();
        dto.refundNumber = "R3";
        dto.originalTicketNumber = "K9";
        PanacheQuery<Ticket> ticketQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.find("ticketNumber", "K9")).thenReturn(ticketQuery);
            assertThrows(IllegalStateException.class, () -> service.ingestRefund(dto));
        }
    }

    /**
     * Covers the missing-line throw arm of {@code ingestRefund} through the
     * null-uid path: a null refund method (ternary false arm), a null session
     * (ternary false arm) and a refund line with a null original uid
     * (original-line ternary false arm, missing-line guard true arm) raise
     * {@link IllegalStateException}.
     */
    @Test
    void ingestRefundThrowsWhenOriginalLineMissing() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        SyncPayloads.RefundDto dto = new SyncPayloads.RefundDto();
        dto.refundNumber = "R4";
        dto.terminalId = "T4";
        dto.status = "CLOSED";
        dto.refundMethod = null;
        dto.originalTicketNumber = "K4";
        dto.sessionNumber = null;
        dto.creationDate = "2026-04-04T16:00:00";
        dto.totalAmount = new BigDecimal("3.00");
        SyncPayloads.RefundLineDto lineDto = new SyncPayloads.RefundLineDto();
        lineDto.originalLineUid = null;
        dto.lines.add(lineDto);
        Ticket original = mock(Ticket.class);
        original.id = 9L;
        original.lines = new ArrayList<>();
        PanacheQuery<Ticket> ticketQuery = queryReturning(original);
        PanacheQuery<Refund> refundQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Refund> createdRefund = mockConstruction(Refund.class,
                        (m, c) -> m.lines = new ArrayList<>());
                MockedConstruction<RefundLine> createdLines = mockConstruction(RefundLine.class)) {
            mocked.when(() -> Ticket.find("ticketNumber", "K4")).thenReturn(ticketQuery);
            mocked.when(() -> Refund.find("refundNumber", "R4")).thenReturn(refundQuery);
            assertThrows(IllegalStateException.class, () -> service.ingestRefund(dto));
        }
    }

    // --------------------------------------------------
    // ingestMovement (lot C5a, BO-04-01-12/33/35/36/37/40/44)
    // --------------------------------------------------

    /**
     * Covers the insert arm of {@code ingestMovement}: no movement exists for
     * the uid so one is constructed, its session resolves (session ternary true
     * arm), its cashier resolves through {@code requireEmployee} (cashier
     * ternary true arm), the row is stamped and persisted, and — being a created
     * SAFE-flagged withdrawal (EXP-87) — it feeds the safe.
     */
    @Test
    void ingestMovementCreatesWithResolvedSessionAndCashier() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        service.safeService = mock(com.intermarche.edge.service.SafeService.class);
        SyncPayloads.MovementDto dto = new SyncPayloads.MovementDto();
        dto.movementUid = "M1";
        dto.terminalId = "C04";
        dto.sessionNumber = "C04-S00001";
        dto.cashierLogin = "jdupont";
        dto.type = "WITHDRAWAL";
        dto.amount = new BigDecimal("30.00");
        dto.reason = "coffre";
        dto.movementDate = "2026-09-01T15:42:00";
        dto.endorsedBy = "11111111";
        dto.safeDeposit = true;
        dto.orderReference = "CMD-9";
        dto.paymentMethod = "CHEQUE";
        dto.transferTo = "CARD";
        dto.denominationDetail = "20x2";
        CashSession session = mock(CashSession.class);
        Employee cashier = mock(Employee.class);
        PanacheQuery<CashMovement> movementQuery = queryReturning(null);
        PanacheQuery<CashSession> sessionQuery = queryReturning(session);
        PanacheQuery<Employee> cashierQuery = queryReturning(cashier);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashMovement> created = mockConstruction(CashMovement.class)) {
            mocked.when(() -> CashMovement.find("movementUid", "M1")).thenReturn(movementQuery);
            mocked.when(() -> CashSession.find("sessionNumber", "C04-S00001")).thenReturn(sessionQuery);
            mocked.when(() -> Employee.find("loginName", "jdupont")).thenReturn(cashierQuery);
            service.ingestMovement(dto);
            CashMovement movement = created.constructed().get(0);
            assertEquals("M1", movement.movementUid);
            assertEquals("C04", movement.terminalId);
            assertSame(session, movement.session);
            assertSame(cashier, movement.cashier);
            assertEquals(CashMovement.MovementType.WITHDRAWAL, movement.type);
            assertEquals(new BigDecimal("30.00"), movement.amount);
            assertEquals("coffre", movement.reason);
            assertEquals(LocalDateTime.of(2026, 9, 1, 15, 42, 0), movement.movementDate);
            assertEquals("11111111", movement.endorsedBy);
            assertTrue(movement.safeDeposit);
            assertEquals("CMD-9", movement.orderReference);
            assertEquals("CHEQUE", movement.paymentMethod);
            assertEquals("CARD", movement.transferTo);
            assertEquals("20x2", movement.denominationDetail);
            verify(movement, times(1)).persist();
            verify(service.coinMachineService).collectMovement(movement, BigDecimal.ZERO);
            // EXP-87 raccord: a created SAFE-flagged movement feeds the safe as a POUCH credit.
            verify(service.safeService, times(1)).record(
                    org.mockito.ArgumentMatchers.eq(com.intermarche.edge.domain.safe.SafeMovementType.POUCH),
                    org.mockito.ArgumentMatchers.eq(new BigDecimal("30.00")),
                    org.mockito.ArgumentMatchers.eq("coffre"),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.same(cashier),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.eq("M1"),
                    org.mockito.ArgumentMatchers.isNull());
        }
    }

    /**
     * The safe raccord (EXP-87) fires only for a CREATED, SAFE-flagged movement:
     * a created movement whose {@code safeDeposit} flag is false does not feed the
     * safe (flag false arm), and neither does the update of an existing movement
     * even when its flag is true (not-created arm) — the two skip arms of
     * {@code feedSafe}.
     */
    @Test
    void ingestMovementFeedsTheSafeOnlyForACreatedSafeDeposit() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        service.safeService = mock(com.intermarche.edge.service.SafeService.class);
        SyncPayloads.MovementDto deposit = new SyncPayloads.MovementDto();
        deposit.movementUid = "M3";
        deposit.terminalId = "C04";
        deposit.type = "WITHDRAWAL";
        deposit.amount = new BigDecimal("50.00");
        deposit.movementDate = "2026-09-01T09:00:00";
        deposit.safeDeposit = false;
        SyncPayloads.MovementDto updated = new SyncPayloads.MovementDto();
        updated.movementUid = "M4";
        updated.terminalId = "C04";
        updated.type = "WITHDRAWAL";
        updated.amount = new BigDecimal("20.00");
        updated.movementDate = "2026-09-01T10:00:00";
        updated.safeDeposit = true;
        CashMovement existing = mock(CashMovement.class);
        PanacheQuery<CashMovement> absent = queryReturning(null);
        PanacheQuery<CashMovement> present = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashMovement> created = mockConstruction(CashMovement.class)) {
            mocked.when(() -> CashMovement.find("movementUid", "M3")).thenReturn(absent);
            mocked.when(() -> CashMovement.find("movementUid", "M4")).thenReturn(present);
            service.ingestMovement(deposit);
            service.ingestMovement(updated);
            org.mockito.Mockito.verifyNoInteractions(service.safeService);
        }
    }

    /**
     * Covers the update arm of {@code ingestMovement} with a null session
     * (session ternary false arm) and a null cashier (cashier ternary false
     * arm): a SECOND push of the same uid finds the existing row and refreshes
     * it — no new row is constructed — which is the upsert-by-uid idempotency
     * the store consolidation relies on.
     */
    @Test
    void ingestMovementUpdatesExistingWithNullSessionAndCashier() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        SyncPayloads.MovementDto dto = new SyncPayloads.MovementDto();
        dto.movementUid = "M2";
        dto.terminalId = "C04";
        dto.sessionNumber = null;
        dto.cashierLogin = null;
        dto.type = "DECLARATION";
        dto.amount = new BigDecimal("250.00");
        dto.reason = null;
        dto.movementDate = "2026-09-01T20:00:00";
        dto.endorsedBy = null;
        CashMovement existing = mock(CashMovement.class);
        existing.endorsedBy = "22222222";
        when(service.coinMachineService.impactOf(existing)).thenReturn(new BigDecimal("-5.00"));
        PanacheQuery<CashMovement> movementQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashMovement> created = mockConstruction(CashMovement.class)) {
            mocked.when(() -> CashMovement.find("movementUid", "M2")).thenReturn(movementQuery);
            service.ingestMovement(dto);
            assertTrue(created.constructed().isEmpty());
            assertEquals("C04", existing.terminalId);
            assertNull(existing.session);
            assertNull(existing.cashier);
            assertEquals(CashMovement.MovementType.DECLARATION, existing.type);
            assertEquals(new BigDecimal("250.00"), existing.amount);
            assertNull(existing.reason);
            assertEquals(LocalDateTime.of(2026, 9, 1, 20, 0, 0), existing.movementDate);
            assertEquals("22222222", existing.endorsedBy);
            verify(existing, times(1)).persist();
            verify(service.coinMachineService).collectMovement(existing, new BigDecimal("-5.00"));
        }
    }

    // --------------------------------------------------
    // ingestEvent
    // --------------------------------------------------

    /**
     * Covers the insert arm of {@code ingestEvent}: no event exists for the uid
     * so one is constructed, stamped and persisted.
     */
    @Test
    void ingestEventCreatesWhenAbsent() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        SyncPayloads.EventDto dto = new SyncPayloads.EventDto();
        dto.eventUid = "EV1";
        dto.terminalId = "T1";
        dto.type = "TICKET_CLOSED";
        dto.detail = "detail";
        dto.operatorBadgeId = "12341234";
        dto.eventDate = "2026-01-01T17:00:00";
        PanacheQuery<TechnicalEvent> eventQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<TechnicalEvent> created = mockConstruction(TechnicalEvent.class)) {
            mocked.when(() -> TechnicalEvent.find("eventUid", "EV1")).thenReturn(eventQuery);
            service.ingestEvent(dto);
            TechnicalEvent event = created.constructed().get(0);
            assertEquals("EV1", event.eventUid);
            assertEquals("T1", event.terminalId);
            assertEquals(TechnicalEvent.EventType.TICKET_CLOSED, event.eventType);
            assertEquals("detail", event.detail);
            assertEquals("12341234", event.operatorBadgeId);
            assertEquals(LocalDateTime.of(2026, 1, 1, 17, 0, 0), event.eventDate);
            verify(event, times(1)).persist();
        }
    }

    /**
     * Covers the update arm of {@code ingestEvent}: an event already exists for
     * the uid so it is refreshed and persisted.
     */
    @Test
    void ingestEventUpdatesWhenPresent() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        SyncPayloads.EventDto dto = new SyncPayloads.EventDto();
        dto.eventUid = "EV2";
        dto.terminalId = "T2";
        dto.type = "TICKET_CANCELLED";
        dto.detail = null;
        dto.operatorBadgeId = null;
        dto.eventDate = "2026-02-02T18:00:00";
        TechnicalEvent existing = mock(TechnicalEvent.class);
        PanacheQuery<TechnicalEvent> eventQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> TechnicalEvent.find("eventUid", "EV2")).thenReturn(eventQuery);
            service.ingestEvent(dto);
            assertEquals("T2", existing.terminalId);
            assertEquals(TechnicalEvent.EventType.TICKET_CANCELLED, existing.eventType);
            assertNull(existing.detail);
            assertNull(existing.operatorBadgeId);
            assertEquals(LocalDateTime.of(2026, 2, 2, 18, 0, 0), existing.eventDate);
            verify(existing, times(1)).persist();
        }
    }

    // --------------------------------------------------
    // ingestBalanceTicket / consumeBalanceTicket
    // --------------------------------------------------

    /**
     * Builds a one-line counter payload.
     *
     * @param reference the printed reference
     * @param emittedAt the emission timestamp, or null
     * @return the payload
     */
    private SyncPayloads.BalanceTicketDto balanceDto(String reference, String emittedAt) {
        SyncPayloads.BalanceTicketDto dto = new SyncPayloads.BalanceTicketDto();
        dto.reference = reference;
        dto.counterLabel = "BOUCHERIE";
        dto.emittedAt = emittedAt;
        SyncPayloads.BalanceTicketLineDto line = new SyncPayloads.BalanceTicketLineDto();
        line.ean = "3560070000000";
        line.label = "ROTI";
        line.quantity = new BigDecimal("0.752");
        line.totalIncludingTax = new BigDecimal("13.54");
        line.vatRate = new BigDecimal("0.055");
        dto.lines.add(line);
        return dto;
    }

    /**
     * Covers the insert arm of {@code ingestBalanceTicket} with an emission
     * timestamp present (parse true arm) and a non-null line list.
     */
    @Test
    void ingestBalanceTicketCreatesWhenAbsent() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        PanacheQuery<BalanceTicket> query = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<BalanceTicket> created = mockConstruction(BalanceTicket.class,
                        (fresh, context) -> fresh.lines = new ArrayList<>());
                MockedConstruction<BalanceTicketLine> lines =
                        mockConstruction(BalanceTicketLine.class)) {
            mocked.when(() -> BalanceTicket.find("reference", "B1")).thenReturn(query);
            service.ingestBalanceTicket(balanceDto("B1", "2026-09-10T10:00:00"));
            BalanceTicket ticket = created.constructed().get(0);
            assertEquals("B1", ticket.reference);
            assertEquals("BOUCHERIE", ticket.counterLabel);
            assertEquals(LocalDateTime.of(2026, 9, 10, 10, 0, 0), ticket.emittedAt);
            assertEquals(1, lines.constructed().size());
            BalanceTicketLine line = lines.constructed().get(0);
            assertSame(ticket, line.balanceTicket);
            assertEquals("3560070000000", line.ean);
            assertEquals("ROTI", line.label);
            assertEquals(new BigDecimal("13.54"), line.totalIncludingTax);
            verify(ticket, times(1)).persist();
        }
    }

    /**
     * Covers the null-emission arm: with no timestamp from the scale the shop
     * stamps the ticket itself rather than leaving it undated.
     */
    @Test
    void ingestBalanceTicketStampsWhenEmissionIsMissing() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        PanacheQuery<BalanceTicket> query = queryReturning(null);
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<BalanceTicket> created = mockConstruction(BalanceTicket.class,
                        (fresh, context) -> fresh.lines = new ArrayList<>());
                MockedConstruction<BalanceTicketLine> lines =
                        mockConstruction(BalanceTicketLine.class)) {
            mocked.when(() -> BalanceTicket.find("reference", "B2")).thenReturn(query);
            service.ingestBalanceTicket(balanceDto("B2", null));
            BalanceTicket ticket = created.constructed().get(0);
            assertTrue(ticket.emittedAt.isAfter(before));
        }
    }

    /**
     * Covers the null-lines arm: a payload carrying no line list is stored as an
     * empty ticket rather than throwing.
     */
    @Test
    void ingestBalanceTicketAcceptsAPayloadWithoutLines() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        SyncPayloads.BalanceTicketDto dto = balanceDto("B3", "2026-09-10T10:00:00");
        dto.lines = null;
        PanacheQuery<BalanceTicket> query = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<BalanceTicket> created = mockConstruction(BalanceTicket.class,
                        (fresh, context) -> fresh.lines = new ArrayList<>());
                MockedConstruction<BalanceTicketLine> lines =
                        mockConstruction(BalanceTicketLine.class)) {
            mocked.when(() -> BalanceTicket.find("reference", "B3")).thenReturn(query);
            service.ingestBalanceTicket(dto);
            assertEquals(0, lines.constructed().size());
            verify(created.constructed().get(0), times(1)).persist();
        }
    }

    /**
     * Covers the update arm: the shop still holds the reference and the scale
     * pushes a correction, which replaces the lines.
     */
    @Test
    void ingestBalanceTicketUpdatesWhenStillAvailable() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        BalanceTicket existing = mock(BalanceTicket.class);
        existing.lines = new ArrayList<>();
        existing.lines.add(new BalanceTicketLine());
        when(existing.isConsumed()).thenReturn(false);
        PanacheQuery<BalanceTicket> query = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<BalanceTicketLine> lines =
                        mockConstruction(BalanceTicketLine.class)) {
            mocked.when(() -> BalanceTicket.find("reference", "B4")).thenReturn(query);
            service.ingestBalanceTicket(balanceDto("B4", "2026-09-10T10:00:00"));
            assertEquals(1, existing.lines.size());
            assertSame(lines.constructed().get(0), existing.lines.get(0));
            verify(existing, times(1)).persist();
        }
    }

    /**
     * Covers the consumed arm: a ticket already sold is frozen, so a late push
     * from the scale is ignored rather than resurrecting it.
     */
    @Test
    void ingestBalanceTicketIgnoresAConsumedTicket() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        BalanceTicket existing = mock(BalanceTicket.class);
        existing.lines = new ArrayList<>();
        when(existing.isConsumed()).thenReturn(true);
        PanacheQuery<BalanceTicket> query = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> BalanceTicket.find("reference", "B5")).thenReturn(query);
            service.ingestBalanceTicket(balanceDto("B5", "2026-09-10T10:00:00"));
            assertEquals(0, existing.lines.size());
            verify(existing, times(0)).persist();
        }
    }

    /**
     * Covers the unknown-reference arm of {@code consumeBalanceTicket}: the shop
     * holds nothing, so it serves nothing.
     */
    @Test
    void consumeBalanceTicketRefusesAnUnknownReference() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        PanacheQuery<BalanceTicket> query = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> BalanceTicket.find("reference", "B6")).thenReturn(query);
            assertNull(service.consumeBalanceTicket("B6", "CAISSE-01"));
        }
    }

    /**
     * Covers the already-consumed arm: the shop served this paper once, and once
     * is the whole rule.
     */
    @Test
    void consumeBalanceTicketRefusesASecondPickUp() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        BalanceTicket existing = mock(BalanceTicket.class);
        when(existing.isConsumed()).thenReturn(true);
        PanacheQuery<BalanceTicket> query = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> BalanceTicket.find("reference", "B7")).thenReturn(query);
            assertNull(service.consumeBalanceTicket("B7", "CAISSE-01"));
            verify(existing, times(0)).persist();
        }
    }

    /**
     * Covers the serving arm: the detail comes back AND the row is stamped in the
     * same call — serving and marking are one act, which is what lets the shop
     * tell a first pick-up from a second.
     */
    @Test
    void consumeBalanceTicketServesAndStamps() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        BalanceTicket existing = mock(BalanceTicket.class);
        when(existing.isConsumed()).thenReturn(false);
        existing.reference = "B8";
        existing.counterLabel = "FROMAGE";
        existing.emittedAt = LocalDateTime.of(2026, 9, 10, 11, 0, 0);
        existing.lines = new ArrayList<>();
        BalanceTicketLine line = new BalanceTicketLine();
        line.ean = "3560070000000";
        line.label = "COMTE";
        line.quantity = new BigDecimal("0.310");
        line.totalIncludingTax = new BigDecimal("7.20");
        line.vatRate = new BigDecimal("0.055");
        existing.lines.add(line);
        PanacheQuery<BalanceTicket> query = queryReturning(existing);
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> BalanceTicket.find("reference", "B8")).thenReturn(query);
            SyncPayloads.BalanceTicketDto served = service.consumeBalanceTicket("B8", "CAISSE-02");
            assertEquals("B8", served.reference);
            assertEquals("FROMAGE", served.counterLabel);
            assertEquals(1, served.lines.size());
            assertEquals("COMTE", served.lines.get(0).label);
            assertEquals(new BigDecimal("7.20"), served.lines.get(0).totalIncludingTax);
            assertEquals("CAISSE-02", existing.consumedByTerminal);
            assertTrue(existing.consumedAt.isAfter(before));
            verify(existing, times(1)).persist();
        }
    }

    /**
     * Covers the null-emission false arm of the {@code emittedAt} ternary in
     * {@code consumeBalanceTicket} (line 441): a served ticket whose emission
     * timestamp is null yields a payload with a null {@code emittedAt} rather
     * than throwing, the row still being stamped and persisted.
     */
    @Test
    void consumeBalanceTicketServesATicketWithNoEmissionDate() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        BalanceTicket existing = mock(BalanceTicket.class);
        when(existing.isConsumed()).thenReturn(false);
        existing.reference = "B9";
        existing.counterLabel = "POISSONNERIE";
        existing.emittedAt = null;
        existing.lines = new ArrayList<>();
        PanacheQuery<BalanceTicket> query = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> BalanceTicket.find("reference", "B9")).thenReturn(query);
            SyncPayloads.BalanceTicketDto served = service.consumeBalanceTicket("B9", "CAISSE-03");
            assertEquals("B9", served.reference);
            assertNull(served.emittedAt);
            assertEquals(0, served.lines.size());
            assertEquals("CAISSE-03", existing.consumedByTerminal);
            verify(existing, times(1)).persist();
        }
    }

    // --------------------------------------------------
    // ingestTicket — formattedContent guard and remaining payment kinds
    // --------------------------------------------------

    /**
     * Covers the true arms of the {@code formattedContent} compound guard in
     * {@code ingestTicket} (line 141): a non-null, non-blank printed form is
     * copied onto the ticket (the {@code != null} true arm and the
     * {@code !isBlank()} true arm both taken, line 142 executed).
     */
    @Test
    void ingestTicketStoresNonBlankFormattedContent() {
        SyncIngestService service = serviceWith();
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        dto.ticketNumber = "K6";
        dto.terminalId = "T6";
        dto.status = "CLOSED";
        dto.creationDate = "2026-06-06T10:00:00";
        dto.storeCode = "ST1";
        dto.cashierLogin = "alice";
        dto.sessionNumber = null;
        dto.itemCount = 0;
        dto.totalExcludingTax = new BigDecimal("1.00");
        dto.totalIncludingTax = new BigDecimal("1.00");
        dto.totalVat = new BigDecimal("0.00");
        dto.valuationStatus = "NOT_VALUATED";
        dto.formattedContent = "*** TICKET ***";
        Store store = mock(Store.class);
        Employee cashier = mock(Employee.class);
        PanacheQuery<Ticket> ticketQuery = queryReturning(null);
        PanacheQuery<Store> storeQuery = queryReturning(store);
        PanacheQuery<Employee> cashierQuery = queryReturning(cashier);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> createdTicket = mockConstruction(Ticket.class,
                        (m, c) -> { m.lines = new ArrayList<>(); m.payments = new ArrayList<>(); })) {
            mocked.when(() -> Ticket.find("ticketNumber", "K6")).thenReturn(ticketQuery);
            mocked.when(() -> Store.find("code", "ST1")).thenReturn(storeQuery);
            mocked.when(() -> Employee.find("loginName", "alice")).thenReturn(cashierQuery);
            service.ingestTicket(dto);
            Ticket ticket = createdTicket.constructed().get(0);
            assertEquals("*** TICKET ***", ticket.formattedContent);
            verify(ticket, times(1)).persist();
        }
    }

    /**
     * Covers the {@code !isBlank()} false arm of the {@code formattedContent}
     * compound guard in {@code ingestTicket} (line 141): a non-null but blank
     * printed form is NOT copied ({@code != null} true, {@code isBlank()} true),
     * so line 142 is skipped and the field is left null.
     */
    @Test
    void ingestTicketIgnoresBlankFormattedContent() {
        SyncIngestService service = serviceWith();
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        dto.ticketNumber = "K7";
        dto.terminalId = "T7";
        dto.status = "CLOSED";
        dto.creationDate = "2026-07-07T10:00:00";
        dto.storeCode = "ST1";
        dto.cashierLogin = "alice";
        dto.sessionNumber = null;
        dto.itemCount = 0;
        dto.totalExcludingTax = new BigDecimal("1.00");
        dto.totalIncludingTax = new BigDecimal("1.00");
        dto.totalVat = new BigDecimal("0.00");
        dto.valuationStatus = "NOT_VALUATED";
        dto.formattedContent = "   ";
        Store store = mock(Store.class);
        Employee cashier = mock(Employee.class);
        PanacheQuery<Ticket> ticketQuery = queryReturning(null);
        PanacheQuery<Store> storeQuery = queryReturning(store);
        PanacheQuery<Employee> cashierQuery = queryReturning(cashier);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> createdTicket = mockConstruction(Ticket.class,
                        (m, c) -> { m.lines = new ArrayList<>(); m.payments = new ArrayList<>(); })) {
            mocked.when(() -> Ticket.find("ticketNumber", "K7")).thenReturn(ticketQuery);
            mocked.when(() -> Store.find("code", "ST1")).thenReturn(storeQuery);
            mocked.when(() -> Employee.find("loginName", "alice")).thenReturn(cashierQuery);
            service.ingestTicket(dto);
            Ticket ticket = createdTicket.constructed().get(0);
            assertNull(ticket.formattedContent);
            verify(ticket, times(1)).persist();
        }
    }

    /**
     * Covers the true arms of the four remaining payment {@code instanceof}
     * decorations in {@code ingestTicket} (lines 201, 204, 209, 214): a cheque
     * (magnetic line), a backup payment (method label, transaction, manual
     * flag), a foreign-currency payment (code, foreign amount, rate) and a
     * credit payment (account number, account name, over-limit flag) are each
     * rebuilt and decorated from the payload in one upsert.
     */
    @Test
    void ingestTicketDecoratesChequeBackupCurrencyAndCreditPayments() {
        TicketPayment.Factory chequeFactory = mock(TicketPayment.Factory.class);
        TicketPayment.Factory backupFactory = mock(TicketPayment.Factory.class);
        TicketPayment.Factory deviseFactory = mock(TicketPayment.Factory.class);
        TicketPayment.Factory creditFactory = mock(TicketPayment.Factory.class);
        when(chequeFactory.getKey()).thenReturn("CHEQUE");
        when(backupFactory.getKey()).thenReturn("SECOURS");
        when(deviseFactory.getKey()).thenReturn("DEVISE");
        when(creditFactory.getKey()).thenReturn("CREDIT");
        com.intermarche.edge.domain.payment.ChequePayment chequePayment =
                mock(com.intermarche.edge.domain.payment.ChequePayment.class);
        com.intermarche.edge.domain.payment.BackupPayment backupPayment =
                mock(com.intermarche.edge.domain.payment.BackupPayment.class);
        com.intermarche.edge.domain.payment.ForeignCurrencyPayment devisePayment =
                mock(com.intermarche.edge.domain.payment.ForeignCurrencyPayment.class);
        com.intermarche.edge.domain.payment.CreditPayment creditPayment =
                mock(com.intermarche.edge.domain.payment.CreditPayment.class);
        when(chequeFactory.create(any(), any())).thenReturn(chequePayment);
        when(backupFactory.create(any(), any())).thenReturn(backupPayment);
        when(deviseFactory.create(any(), any())).thenReturn(devisePayment);
        when(creditFactory.create(any(), any())).thenReturn(creditPayment);
        SyncIngestService service = serviceWith(chequeFactory, backupFactory, deviseFactory, creditFactory);
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        dto.ticketNumber = "K8";
        dto.terminalId = "T8";
        dto.status = "CLOSED";
        dto.creationDate = "2026-08-08T10:00:00";
        dto.storeCode = "ST1";
        dto.cashierLogin = "alice";
        dto.sessionNumber = null;
        dto.itemCount = 0;
        dto.totalExcludingTax = new BigDecimal("1.00");
        dto.totalIncludingTax = new BigDecimal("1.00");
        dto.totalVat = new BigDecimal("0.00");
        dto.valuationStatus = "NOT_VALUATED";
        SyncPayloads.PaymentDto chequeDto = new SyncPayloads.PaymentDto();
        chequeDto.paymentIndex = 1;
        chequeDto.methodKey = "CHEQUE";
        chequeDto.amount = new BigDecimal("10.00");
        chequeDto.magneticLine = "CMC7-LINE";
        SyncPayloads.PaymentDto backupDto = new SyncPayloads.PaymentDto();
        backupDto.paymentIndex = 2;
        backupDto.methodKey = "SECOURS";
        backupDto.amount = new BigDecimal("20.00");
        backupDto.backupMethodLabel = "CB SECOURS";
        backupDto.backupTransaction = "TX-42";
        backupDto.backupManual = true;
        SyncPayloads.PaymentDto deviseDto = new SyncPayloads.PaymentDto();
        deviseDto.paymentIndex = 3;
        deviseDto.methodKey = "DEVISE";
        deviseDto.amount = new BigDecimal("30.00");
        deviseDto.currencyCode = "USD";
        deviseDto.currencyAmount = new BigDecimal("33.00");
        deviseDto.currencyRate = new BigDecimal("1.10");
        SyncPayloads.PaymentDto creditDto = new SyncPayloads.PaymentDto();
        creditDto.paymentIndex = 4;
        creditDto.methodKey = "CREDIT";
        creditDto.amount = new BigDecimal("40.00");
        creditDto.creditAccountNumber = "ACC-1";
        creditDto.creditAccountName = "Durand";
        creditDto.creditOverLimit = true;
        dto.payments.add(chequeDto);
        dto.payments.add(backupDto);
        dto.payments.add(deviseDto);
        dto.payments.add(creditDto);
        Store store = mock(Store.class);
        Employee cashier = mock(Employee.class);
        PanacheQuery<Ticket> ticketQuery = queryReturning(null);
        PanacheQuery<Store> storeQuery = queryReturning(store);
        PanacheQuery<Employee> cashierQuery = queryReturning(cashier);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> createdTicket = mockConstruction(Ticket.class,
                        (m, c) -> { m.lines = new ArrayList<>(); m.payments = new ArrayList<>(); })) {
            mocked.when(() -> Ticket.find("ticketNumber", "K8")).thenReturn(ticketQuery);
            mocked.when(() -> Store.find("code", "ST1")).thenReturn(storeQuery);
            mocked.when(() -> Employee.find("loginName", "alice")).thenReturn(cashierQuery);
            service.ingestTicket(dto);
            assertEquals("CMC7-LINE", chequePayment.magneticLine);
            assertEquals("CB SECOURS", backupPayment.methodLabel);
            assertEquals("TX-42", backupPayment.transactionNumber);
            assertTrue(backupPayment.manual);
            assertEquals("USD", devisePayment.currencyCode);
            assertEquals(new BigDecimal("33.00"), devisePayment.foreignAmount);
            assertEquals(new BigDecimal("1.10"), devisePayment.exchangeRate);
            assertEquals("ACC-1", creditPayment.accountNumber);
            assertEquals("Durand", creditPayment.accountName);
            assertTrue(creditPayment.overLimit);
            Ticket ticket = createdTicket.constructed().get(0);
            verify(ticket).addPayment(chequePayment);
            verify(ticket).addPayment(backupPayment);
            verify(ticket).addPayment(devisePayment);
            verify(ticket).addPayment(creditPayment);
            verify(ticket, times(1)).persist();
        }
    }

    // --------------------------------------------------
    // renderTicketDuplicate (LC-08-05-05)
    // --------------------------------------------------

    /**
     * Covers the {@code == null} true arm of {@code renderTicketDuplicate}'s
     * guard (line 295): a null ticket number yields null without hitting the
     * database or the printer.
     */
    @Test
    void renderTicketDuplicateReturnsNullOnNullNumber() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        assertNull(service.renderTicketDuplicate(null));
    }

    /**
     * Covers the {@code isBlank()} true arm of {@code renderTicketDuplicate}'s
     * guard (line 295, {@code == null} false + {@code isBlank()} true): a blank
     * ticket number yields null.
     */
    @Test
    void renderTicketDuplicateReturnsNullOnBlankNumber() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        assertNull(service.renderTicketDuplicate("   "));
    }

    /**
     * Covers the not-found arm of {@code renderTicketDuplicate} (line 295 both
     * guard arms false, line 301 {@code ticket == null} true): a well-formed
     * number the shop does not hold yields null; the trimmed number is used as
     * the lookup key.
     */
    @Test
    void renderTicketDuplicateReturnsNullWhenTicketAbsent() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        PanacheQuery<Ticket> ticketQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.find("ticketNumber", "K1")).thenReturn(ticketQuery);
            assertNull(service.renderTicketDuplicate("  K1  "));
        }
    }

    /**
     * Covers the found arm of {@code renderTicketDuplicate} (line 301
     * {@code ticket == null} false): a held ticket is rendered through the
     * node's own renderer as a duplicate, with a print count floored at 1.
     */
    @Test
    void renderTicketDuplicateRendersHeldTicket() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        com.intermarche.edge.service.TicketRenderService renderer =
                mock(com.intermarche.edge.service.TicketRenderService.class);
        service.ticketRenderService = renderer;
        Ticket ticket = mock(Ticket.class);
        ticket.printCount = 0;
        when(renderer.renderTicket(ticket, true, 1)).thenReturn("DUPLICATA");
        PanacheQuery<Ticket> ticketQuery = queryReturning(ticket);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.find("ticketNumber", "K1")).thenReturn(ticketQuery);
            assertEquals("DUPLICATA", service.renderTicketDuplicate("K1"));
        }
    }

    // --------------------------------------------------
    // ingestCustomer (LC-08-04-09)
    // --------------------------------------------------

    /**
     * Covers the insert arm of {@code ingestCustomer} (line 323
     * {@code customer == null} true) with a freshly constructed account whose
     * address is null (line 331 true arm): a new row and a new address are
     * created, stamped and persisted, and the "créé" log arm (line 342) is
     * taken.
     */
    @Test
    void ingestCustomerCreatesWhenAbsent() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        SyncPayloads.CustomerDto dto = new SyncPayloads.CustomerDto();
        dto.accountNumber = "AC1";
        dto.companyName = "ACME";
        dto.lastName = "Durand";
        dto.firstName = "Paul";
        dto.street = "1 rue des Lilas";
        dto.postalCode = "75001";
        dto.city = "Paris";
        dto.siret = "12345678900011";
        dto.vatNumber = "FR00123456789";
        dto.phone = "0102030405";
        dto.email = "paul@acme.fr";
        dto.origin = "VOLATILE";
        PanacheQuery<com.intermarche.edge.domain.payment.AccountCustomer> query = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.edge.domain.payment.AccountCustomer> created =
                        mockConstruction(com.intermarche.edge.domain.payment.AccountCustomer.class);
                MockedConstruction<com.intermarche.edge.domain.store.Address> addresses =
                        mockConstruction(com.intermarche.edge.domain.store.Address.class)) {
            mocked.when(() -> com.intermarche.edge.domain.payment.AccountCustomer
                    .find("accountNumber", "AC1")).thenReturn(query);
            service.ingestCustomer(dto);
            com.intermarche.edge.domain.payment.AccountCustomer customer = created.constructed().get(0);
            assertEquals("AC1", customer.accountNumber);
            assertEquals("ACME", customer.companyName);
            assertEquals("Durand", customer.lastName);
            assertEquals("Paul", customer.firstName);
            assertEquals(1, addresses.constructed().size());
            com.intermarche.edge.domain.store.Address address = addresses.constructed().get(0);
            assertSame(address, customer.address);
            assertEquals("1 rue des Lilas", address.streetLine1);
            assertEquals("75001", address.postalCode);
            assertEquals("Paris", address.city);
            assertEquals("12345678900011", customer.siret);
            assertEquals("paul@acme.fr", customer.email);
            assertEquals(com.intermarche.edge.domain.payment.AccountCustomer.Origin.VOLATILE, customer.origin);
            verify(customer, times(1)).persist();
        }
    }

    /**
     * BO-06-03-04: the origin a register states is read by name; none, a blank
     * one or an unknown one keeps the origin held here.
     */
    @Test
    void customerOriginIsReadByName() {
        assertEquals(com.intermarche.edge.domain.payment.AccountCustomer.Origin.VOLATILE,
                SyncIngestService.customerOrigin(" VOLATILE "));
        assertNull(SyncIngestService.customerOrigin(null));
        assertNull(SyncIngestService.customerOrigin(" "));
        assertNull(SyncIngestService.customerOrigin("MARTIAN"));
    }

    /**
     * BO-02-04-13: a file consolidated from a register carries its NIF over and
     * is filed for the commercial management, tagged as coming from the
     * register.
     */
    @Test
    void ingestCustomerCarriesTheNifAndGoesUp() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        service.customerUplinkService = mock(com.intermarche.edge.service.sync.CustomerUplinkService.class);
        SyncPayloads.CustomerDto dto = new SyncPayloads.CustomerDto();
        dto.accountNumber = "AC3";
        dto.companyName = "INITECH";
        dto.taxId = "PT123456789";
        com.intermarche.edge.domain.payment.AccountCustomer existing =
                mock(com.intermarche.edge.domain.payment.AccountCustomer.class);
        existing.taxId = "PT000000000";
        existing.address = new com.intermarche.edge.domain.store.Address();
        PanacheQuery<com.intermarche.edge.domain.payment.AccountCustomer> query = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> com.intermarche.edge.domain.payment.AccountCustomer
                    .find("accountNumber", "AC3")).thenReturn(query);
            service.ingestCustomer(dto);
        }
        assertEquals("PT123456789", existing.taxId);
        verify(service.customerUplinkService).enqueue(existing,
                com.intermarche.edge.domain.sync.CustomerUplink.Source.REGISTER);
    }

    /**
     * A register of an older version states no NIF, which leaves the one held.
     */
    @Test
    void ingestCustomerKeepsTheNifANullOneDoesNotState() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        SyncPayloads.CustomerDto dto = new SyncPayloads.CustomerDto();
        dto.accountNumber = "AC4";
        com.intermarche.edge.domain.payment.AccountCustomer existing =
                mock(com.intermarche.edge.domain.payment.AccountCustomer.class);
        existing.taxId = "PT000000000";
        existing.address = new com.intermarche.edge.domain.store.Address();
        PanacheQuery<com.intermarche.edge.domain.payment.AccountCustomer> query = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> com.intermarche.edge.domain.payment.AccountCustomer
                    .find("accountNumber", "AC4")).thenReturn(query);
            service.ingestCustomer(dto);
        }
        assertEquals("PT000000000", existing.taxId);
    }

    /**
     * Covers the update arm of {@code ingestCustomer} (line 323
     * {@code customer == null} false) with an existing account already carrying
     * an address (line 331 false arm): the row is refreshed in place, its
     * existing address reused (no new one constructed), and the "mis à jour" log
     * arm (line 342) is taken.
     */
    @Test
    void ingestCustomerUpdatesExistingWithAddress() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        SyncPayloads.CustomerDto dto = new SyncPayloads.CustomerDto();
        dto.accountNumber = "AC2";
        dto.companyName = "GLOBEX";
        dto.lastName = "Martin";
        dto.firstName = "Marie";
        dto.street = "2 avenue du Parc";
        dto.postalCode = "69002";
        dto.city = "Lyon";
        dto.siret = "98765432100022";
        dto.email = "marie@globex.fr";
        com.intermarche.edge.domain.payment.AccountCustomer existing =
                mock(com.intermarche.edge.domain.payment.AccountCustomer.class);
        com.intermarche.edge.domain.store.Address address =
                mock(com.intermarche.edge.domain.store.Address.class);
        existing.address = address;
        PanacheQuery<com.intermarche.edge.domain.payment.AccountCustomer> query = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.edge.domain.store.Address> addresses =
                        mockConstruction(com.intermarche.edge.domain.store.Address.class)) {
            mocked.when(() -> com.intermarche.edge.domain.payment.AccountCustomer
                    .find("accountNumber", "AC2")).thenReturn(query);
            service.ingestCustomer(dto);
            assertTrue(addresses.constructed().isEmpty());
            assertSame(address, existing.address);
            assertEquals("GLOBEX", existing.companyName);
            assertEquals("Martin", existing.lastName);
            assertEquals("Marie", existing.firstName);
            assertEquals("2 avenue du Parc", address.streetLine1);
            assertEquals("69002", address.postalCode);
            assertEquals("Lyon", address.city);
            assertEquals("marie@globex.fr", existing.email);
            verify(existing, times(1)).persist();
        }
    }

    /**
     * Builds the payload of a document pushed by a register.
     *
     * @return the payload, its totals filled
     */
    private SyncPayloads.DocumentDto documentDto() {
        SyncPayloads.DocumentDto dto = new SyncPayloads.DocumentDto();
        dto.documentNumber = "C04-F000123";
        dto.documentType = "FACTURE";
        dto.terminalId = "C04";
        dto.issueDate = "2026-09-10T18:42:00";
        dto.ticketNumber = null;
        dto.customerAccountNumber = null;
        dto.customerName = "ACME";
        dto.customerTaxId = "PT1";
        dto.customerDueDate = "2026-10-31";
        dto.totalExcludingTax = new BigDecimal("100.00");
        dto.totalIncludingTax = new BigDecimal("120.00");
        dto.totalVat = new BigDecimal("20.00");
        dto.printCount = 1;
        return dto;
    }

    /**
     * A register that extracts the eco-tax has it written onto the stored document
     * ({@code BO-10-04-14}, non-null arm).
     */
    @Test
    void ingestDocumentStoresTheEcoTaxWhenTheRegisterSendsIt() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        SyncPayloads.DocumentDto dto = documentDto();
        dto.totalEcoTax = new BigDecimal("1.50");
        com.intermarche.edge.domain.sale.Invoice existing =
                mock(com.intermarche.edge.domain.sale.Invoice.class);
        PanacheQuery<com.intermarche.edge.domain.sale.Invoice> found = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> com.intermarche.edge.domain.sale.Invoice
                    .find("documentNumber", "C04-F000123")).thenReturn(found);
            service.ingestDocument(dto);
            assertEquals(new BigDecimal("1.50"), existing.totalEcoTax);
            assertEquals(java.time.LocalDate.of(2026, 10, 31), existing.customerDueDate);
            verify(existing, times(1)).persist();
        }
    }

    /**
     * A register that does NOT extract it sends null, which is stored as zero and
     * not as "unknown": the column of a document is mandatory
     * ({@code BO-10-04-14}, null arm).
     */
    @Test
    void ingestDocumentStoresZeroWhenTheRegisterWithholdsTheEcoTax() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        SyncPayloads.DocumentDto dto = documentDto();
        dto.totalEcoTax = null;
        dto.customerDueDate = null;
        com.intermarche.edge.domain.sale.Invoice existing =
                mock(com.intermarche.edge.domain.sale.Invoice.class);
        PanacheQuery<com.intermarche.edge.domain.sale.Invoice> found = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> com.intermarche.edge.domain.sale.Invoice
                    .find("documentNumber", "C04-F000123")).thenReturn(found);
            service.ingestDocument(dto);
            assertEquals(BigDecimal.ZERO, existing.totalEcoTax);
            assertNull(existing.customerDueDate);
        }
    }

    /**
     * Covers the insert arm of {@code ingestDocument} (line 413 {@code document ==
     * null} true) together with the branches only a full create-push reaches: a
     * null document type defaulting to {@code FACTURE} (line 418 true arm), a
     * non-null ticket number resolved to a held ticket (line 424 false arm), a
     * non-null customer account number resolved to a held account (line 427 false
     * arm), all three amount totals arriving null and stored as zero (lines 443,
     * 445 and 447 null arms), a null customer address building a fresh one (line
     * 437 true arm) and an unreadable due date swallowed by {@code parseDay}'s
     * catch (lines 474/475). A fresh row and a fresh address are constructed,
     * stamped and persisted.
     */
    @Test
    void ingestDocumentCreatesResolvingTicketAndCustomerWithZeroTotals() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        SyncPayloads.DocumentDto dto = new SyncPayloads.DocumentDto();
        dto.documentNumber = "C04-F000999";
        dto.documentType = null;
        dto.terminalId = "C04";
        dto.issueDate = "2026-09-10T18:42:00";
        dto.ticketNumber = "K1";
        dto.customerAccountNumber = "AC1";
        dto.customerName = "ACME";
        dto.customerStreet = "1 rue des Lilas";
        dto.customerPostalCode = "75001";
        dto.customerCity = "Paris";
        dto.customerDueDate = "pas-une-date";
        dto.totalExcludingTax = null;
        dto.totalIncludingTax = null;
        dto.totalVat = null;
        dto.totalEcoTax = null;
        dto.printCount = 2;
        Ticket ticket = mock(Ticket.class);
        com.intermarche.edge.domain.payment.AccountCustomer customer =
                mock(com.intermarche.edge.domain.payment.AccountCustomer.class);
        PanacheQuery<com.intermarche.edge.domain.sale.Invoice> found = queryReturning(null);
        PanacheQuery<Ticket> ticketQuery = queryReturning(ticket);
        PanacheQuery<com.intermarche.edge.domain.payment.AccountCustomer> customerQuery =
                queryReturning(customer);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.edge.domain.sale.Invoice> created =
                        mockConstruction(com.intermarche.edge.domain.sale.Invoice.class);
                MockedConstruction<com.intermarche.edge.domain.store.Address> addresses =
                        mockConstruction(com.intermarche.edge.domain.store.Address.class)) {
            mocked.when(() -> com.intermarche.edge.domain.sale.Invoice
                    .find("documentNumber", "C04-F000999")).thenReturn(found);
            mocked.when(() -> Ticket.find("ticketNumber", "K1")).thenReturn(ticketQuery);
            mocked.when(() -> com.intermarche.edge.domain.payment.AccountCustomer
                    .find("accountNumber", "AC1")).thenReturn(customerQuery);
            service.ingestDocument(dto);
            com.intermarche.edge.domain.sale.Invoice document = created.constructed().get(0);
            assertEquals("C04-F000999", document.documentNumber);
            assertEquals(com.intermarche.edge.domain.sale.DocumentType.FACTURE, document.documentType);
            assertSame(ticket, document.ticket);
            assertEquals("K1", document.ticketNumber);
            assertSame(customer, document.customer);
            assertEquals("AC1", document.customerAccountNumber);
            assertEquals(1, addresses.constructed().size());
            com.intermarche.edge.domain.store.Address address = addresses.constructed().get(0);
            assertSame(address, document.customerAddress);
            assertEquals("1 rue des Lilas", address.streetLine1);
            assertEquals("75001", address.postalCode);
            assertEquals("Paris", address.city);
            assertEquals(BigDecimal.ZERO, document.totalExcludingTax);
            assertEquals(BigDecimal.ZERO, document.totalIncludingTax);
            assertEquals(BigDecimal.ZERO, document.totalVat);
            assertEquals(BigDecimal.ZERO, document.totalEcoTax);
            assertNull(document.customerDueDate);
            assertEquals(2, document.printCount);
            verify(document, times(1)).persist();
        }
    }

    /**
     * Covers the address-reuse arm of {@code ingestDocument} (line 437
     * {@code customerAddress == null} false) and the blank-date arm of
     * {@code parseDay} (line 469, {@code text == null} false + {@code isBlank()}
     * true): an existing document already carrying a customer address keeps it in
     * place (no new address constructed), and a blank due date reads back as null,
     * the refreshed row being persisted.
     */
    @Test
    void ingestDocumentReusesAddressAndTreatsBlankDueDateAsNone() {
        SyncIngestService service = new SyncIngestService();
        service.ticketStreamService = mock(TicketStreamService.class);
        service.coinMachineService = mock(com.intermarche.edge.service.CoinMachineService.class);
        service.saftStreamService = mock(com.intermarche.edge.service.fiscal.SaftStreamService.class);
        SyncPayloads.DocumentDto dto = documentDto();
        dto.customerDueDate = "   ";
        dto.customerStreet = "9 quai neuf";
        dto.customerPostalCode = "33000";
        dto.customerCity = "Bordeaux";
        com.intermarche.edge.domain.sale.Invoice existing =
                mock(com.intermarche.edge.domain.sale.Invoice.class);
        com.intermarche.edge.domain.store.Address address =
                mock(com.intermarche.edge.domain.store.Address.class);
        existing.customerAddress = address;
        PanacheQuery<com.intermarche.edge.domain.sale.Invoice> found = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<com.intermarche.edge.domain.store.Address> addresses =
                        mockConstruction(com.intermarche.edge.domain.store.Address.class)) {
            mocked.when(() -> com.intermarche.edge.domain.sale.Invoice
                    .find("documentNumber", "C04-F000123")).thenReturn(found);
            service.ingestDocument(dto);
            assertTrue(addresses.constructed().isEmpty());
            assertSame(address, existing.customerAddress);
            assertEquals("9 quai neuf", address.streetLine1);
            assertEquals("33000", address.postalCode);
            assertEquals("Bordeaux", address.city);
            assertEquals("ACME", existing.customerName);
            assertNull(existing.customerDueDate);
            verify(existing, times(1)).persist();
        }
    }
    /** GB-01-07-05/06: a pushed ticket replaces its previous offer messages with the register's frozen wording. */
    @Test
    void pushedTicketReplacesItsOfferMessages() {
        SyncIngestService service = serviceWith();
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        dto.ticketNumber = "MESSAGES";
        dto.status = "CANCELLED";
        dto.valuationStatus = "NOT_VALUATED";
        dto.cashierLogin = "OP";
        dto.totalIncludingTax = BigDecimal.ZERO;
        dto.totalExcludingTax = BigDecimal.ZERO;
        dto.totalVat = BigDecimal.ZERO;
        var message = new SyncPayloads.MessageDto();
        message.code = "OFFER";
        message.text = "FROZEN MESSAGE";
        message.printMode = "FOOTER";
        dto.messages.add(message);
        Ticket existing = mock(Ticket.class);
        existing.lines = new ArrayList<>();
        existing.payments = new ArrayList<>();
        existing.messages = new ArrayList<>();
        existing.messages.add(new com.intermarche.edge.domain.sale.TicketMessage("OLD", "OLD MESSAGE", "LINE"));
        var ticketQuery = queryReturning(existing);
        var storeQuery = queryReturning(mock(Store.class));
        var cashierQuery = queryReturning(mock(Employee.class));
        try (var base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> PanacheEntityBase.find("ticketNumber", "MESSAGES")).thenReturn(ticketQuery);
            base.when(PanacheEntityBase::findAll).thenReturn(storeQuery);
            base.when(() -> PanacheEntityBase.find("loginName", "op")).thenReturn(cashierQuery);
            service.ingestTicket(dto);
        }
        assertEquals(1, existing.messages.size());
        assertEquals("OFFER", existing.messages.get(0).code);
        assertEquals("FROZEN MESSAGE", existing.messages.get(0).text);
        assertEquals("FOOTER", existing.messages.get(0).printMode);
    }

    /** BO-04-01: an undated technical event receives an arrival timestamp rather than a null persisted date. */
    @Test
    void undatedTechnicalEventIsStampedOnArrival() {
        SyncIngestService service = serviceWith();
        var dto = new SyncPayloads.EventDto();
        dto.eventUid = "UNDATED";
        dto.type = "SESSION_CLOSED";
        TechnicalEvent existing = mock(TechnicalEvent.class);
        var query = queryReturning(existing);
        var before = LocalDateTime.now();
        try (var base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> PanacheEntityBase.find("eventUid", "UNDATED")).thenReturn(query);
            service.ingestEvent(dto);
        }
        assertFalse(existing.eventDate.isBefore(before));
        assertFalse(existing.eventDate.isAfter(LocalDateTime.now()));
        verify(existing).persist();
    }

    /** EXP-87: blank withdrawal reasons use the till label and absent movement dates remain absent at the safe. */
    @Test
    void safeFeedHandlesBlankReasonAndMissingDate() throws Exception {
        SyncIngestService service = serviceWith();
        CashMovement movement = new CashMovement();
        movement.safeDeposit = true;
        movement.reason = " ";
        movement.terminalId = "C04";
        movement.amount = BigDecimal.TEN;
        movement.movementUid = "M1";
        movement.movementDate = null;
        var method = SyncIngestService.class.getDeclaredMethod("feedSafe", boolean.class, CashMovement.class);
        method.setAccessible(true);
        method.invoke(service, true, movement);
        movement.reason = null;
        method.invoke(service, true, movement);
        verify(service.safeService, times(2)).record(com.intermarche.edge.domain.safe.SafeMovementType.POUCH, BigDecimal.TEN, "Prélèvement caisse C04", null, null, null, "M1", null);
    }


    /**
     * LC-08-02-02: a printed ticket is stored once per register row — created on
     * the first push with its audience, refreshed by a repeated push — and one
     * pushed without audience reads as the customer's.
     */
    @Test
    void ingestPrintoutUpsertsByRegisterRow() {
        SyncIngestService service = new SyncIngestService();
        SyncPayloads.PrintoutDto dto = new SyncPayloads.PrintoutDto();
        dto.sourceId = 7L;
        dto.terminalId = "C04";
        dto.source = "TicketPrinterService.printSessionReport";
        dto.audience = "STORE";
        dto.issuedAt = "2026-10-24T09:00:00";
        dto.content = "RAPPORT Z";
        com.intermarche.edge.domain.sale.PrintedTicket existing =
                mock(com.intermarche.edge.domain.sale.PrintedTicket.class);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             org.mockito.MockedConstruction<com.intermarche.edge.domain.sale.PrintedTicket> created =
                     org.mockito.Mockito.mockConstruction(com.intermarche.edge.domain.sale.PrintedTicket.class)) {
            PanacheQuery<com.intermarche.edge.domain.sale.PrintedTicket> none = queryReturning(null);
            mocked.when(() -> com.intermarche.edge.domain.sale.PrintedTicket.find(
                    "terminalId = ?1 and sourceId = ?2", "C04", 7L)).thenReturn(none);
            service.ingestPrintout(dto);
            com.intermarche.edge.domain.sale.PrintedTicket row = created.constructed().get(0);
            assertEquals("C04", row.terminalId);
            assertEquals(7L, row.sourceId);
            assertEquals(com.intermarche.edge.domain.sale.PrintedTicket.Audience.STORE, row.audience);
            assertEquals("RAPPORT Z", row.content);
            assertEquals(java.time.LocalDateTime.of(2026, 10, 24, 9, 0), row.issuedAt);
            verify(row).persist();
            PanacheQuery<com.intermarche.edge.domain.sale.PrintedTicket> found = queryReturning(existing);
            mocked.when(() -> com.intermarche.edge.domain.sale.PrintedTicket.find(
                    "terminalId = ?1 and sourceId = ?2", "C04", 7L)).thenReturn(found);
            dto.audience = null;
            service.ingestPrintout(dto);
            assertEquals(com.intermarche.edge.domain.sale.PrintedTicket.Audience.CUSTOMER, existing.audience);
            verify(existing).persist();
            assertEquals(1, created.constructed().size());
        }
    }

    /**
     * LC-13-05-06: a training sale is stored once per register row in the
     * training table — created on the first push, refreshed by a repeated push.
     */
    @Test
    void ingestTrainingTicketUpsertsByRegisterRow() {
        SyncIngestService service = new SyncIngestService();
        SyncPayloads.TrainingTicketDto dto = new SyncPayloads.TrainingTicketDto();
        dto.sourceId = 8L;
        dto.terminalId = "C04";
        dto.cashierLogin = "jdupont";
        dto.issuedAt = "2026-10-24T09:30:00";
        dto.itemCount = 2;
        dto.totalIncludingTax = new BigDecimal("3.72");
        dto.content = "MODE FORMATION";
        com.intermarche.edge.domain.sale.TrainingTicket existing =
                mock(com.intermarche.edge.domain.sale.TrainingTicket.class);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             org.mockito.MockedConstruction<com.intermarche.edge.domain.sale.TrainingTicket> created =
                     org.mockito.Mockito.mockConstruction(com.intermarche.edge.domain.sale.TrainingTicket.class)) {
            PanacheQuery<com.intermarche.edge.domain.sale.TrainingTicket> none = queryReturning(null);
            mocked.when(() -> com.intermarche.edge.domain.sale.TrainingTicket.find(
                    "terminalId = ?1 and sourceId = ?2", "C04", 8L)).thenReturn(none);
            service.ingestTrainingTicket(dto);
            com.intermarche.edge.domain.sale.TrainingTicket row = created.constructed().get(0);
            assertEquals("C04", row.terminalId);
            assertEquals(8L, row.sourceId);
            assertEquals("jdupont", row.cashierLogin);
            assertEquals(2, row.itemCount);
            assertEquals(new BigDecimal("3.72"), row.totalIncludingTax);
            assertEquals("MODE FORMATION", row.content);
            verify(row).persist();
            PanacheQuery<com.intermarche.edge.domain.sale.TrainingTicket> found = queryReturning(existing);
            mocked.when(() -> com.intermarche.edge.domain.sale.TrainingTicket.find(
                    "terminalId = ?1 and sourceId = ?2", "C04", 8L)).thenReturn(found);
            service.ingestTrainingTicket(dto);
            verify(existing).persist();
            assertEquals(1, created.constructed().size());
        }
    }

    /**
     * LC-04-01-03: the signed trace of a ticket on hold is kept once per register
     * row exactly as signed — created on the first push, refreshed by a repeated
     * push; a trace without event travels without it.
     */
    @Test
    void ingestParkedTicketUpsertsByRegisterRow() {
        SyncIngestService service = new SyncIngestService();
        SyncPayloads.ParkedTicketDto dto = new SyncPayloads.ParkedTicketDto();
        dto.sourceId = 9L;
        dto.terminalId = "C04";
        dto.ticketNumber = "C04-00000004";
        dto.event = "RESUMED";
        dto.eventDate = "2026-10-24T09:30:15";
        dto.operator = "12341234";
        dto.itemCount = 2;
        dto.totalAmount = new BigDecimal("5.30");
        dto.content = "1 x LAIT @ 1.15 = 1.15\n";
        dto.previousSignature = "GENESIS";
        dto.signature = "abc";
        com.intermarche.edge.domain.sale.ParkedTicketRecord existing =
                mock(com.intermarche.edge.domain.sale.ParkedTicketRecord.class);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             org.mockito.MockedConstruction<com.intermarche.edge.domain.sale.ParkedTicketRecord> created =
                     org.mockito.Mockito.mockConstruction(com.intermarche.edge.domain.sale.ParkedTicketRecord.class)) {
            PanacheQuery<com.intermarche.edge.domain.sale.ParkedTicketRecord> none = queryReturning(null);
            mocked.when(() -> com.intermarche.edge.domain.sale.ParkedTicketRecord.find(
                    "terminalId = ?1 and sourceId = ?2", "C04", 9L)).thenReturn(none);
            service.ingestParkedTicket(dto);
            com.intermarche.edge.domain.sale.ParkedTicketRecord row = created.constructed().get(0);
            assertEquals("C04", row.terminalId);
            assertEquals(9L, row.sourceId);
            assertEquals("C04-00000004", row.ticketNumber);
            assertEquals(com.intermarche.edge.domain.sale.ParkedTicketRecord.Event.RESUMED, row.event);
            assertEquals(java.time.LocalDateTime.of(2026, 10, 24, 9, 30, 15), row.eventDate);
            assertEquals("12341234", row.operator);
            assertEquals(2, row.itemCount);
            assertEquals(new BigDecimal("5.30"), row.totalAmount);
            assertEquals("1 x LAIT @ 1.15 = 1.15\n", row.content);
            assertEquals("GENESIS", row.previousSignature);
            assertEquals("abc", row.signature);
            verify(row).persist();
            PanacheQuery<com.intermarche.edge.domain.sale.ParkedTicketRecord> found = queryReturning(existing);
            mocked.when(() -> com.intermarche.edge.domain.sale.ParkedTicketRecord.find(
                    "terminalId = ?1 and sourceId = ?2", "C04", 9L)).thenReturn(found);
            dto.event = null;
            service.ingestParkedTicket(dto);
            verify(existing).persist();
            org.junit.jupiter.api.Assertions.assertNull(existing.event);
            assertEquals(1, created.constructed().size());
        }
    }

    /**
     * BO-03-06-62: an instrument is upserted by the register that printed it
     * and its number — created on the first report, updated on the next, the
     * reporting register and the date of the report kept; a report without
     * dates leaves them empty.
     */
    @Test
    void ingestStoredValueUpsertsByIssuerAndNumber() {
        SyncIngestService service = new SyncIngestService();
        service.centralInstrumentRelay = mock(CentralInstrumentRelay.class);
        service.nodeEchelonService = mock(com.intermarche.edge.service.NodeEchelonService.class);
        when(service.nodeEchelonService.currentPdv()).thenReturn("1234");
        SyncPayloads.StoredValueDto dto = new SyncPayloads.StoredValueDto();
        dto.issuingTerminalId = "C02";
        dto.terminalId = "C04";
        dto.number = "297000000000400";
        dto.kind = "CREDIT_NOTE";
        dto.initialAmount = new BigDecimal("12.40");
        dto.balance = new BigDecimal("2.40");
        dto.status = "ACTIVE";
        dto.issuedAt = "2026-10-24T09:30:15";
        dto.validUntil = "2027-10-24T00:00";
        dto.exhaustedAt = "2026-10-25T08:00";
        dto.issuingDocumentNumber = "C02-00000009";
        com.intermarche.edge.domain.barcode.StoredValueRecord existing =
                mock(com.intermarche.edge.domain.barcode.StoredValueRecord.class);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             org.mockito.MockedConstruction<com.intermarche.edge.domain.barcode.StoredValueRecord> created =
                     org.mockito.Mockito.mockConstruction(com.intermarche.edge.domain.barcode.StoredValueRecord.class)) {
            PanacheQuery<com.intermarche.edge.domain.barcode.StoredValueRecord> none = queryReturning(null);
            mocked.when(() -> com.intermarche.edge.domain.barcode.StoredValueRecord.find(
                    "issuingTerminalId = ?1 and number = ?2", "C02", "297000000000400")).thenReturn(none);
            service.ingestStoredValue(dto);
            com.intermarche.edge.domain.barcode.StoredValueRecord row = created.constructed().get(0);
            assertEquals("C02", row.issuingTerminalId);
            assertEquals("297000000000400", row.number);
            assertEquals("CREDIT_NOTE", row.kind);
            assertEquals(new BigDecimal("12.40"), row.initialAmount);
            assertEquals(new BigDecimal("2.40"), row.balance);
            assertEquals("ACTIVE", row.status);
            assertEquals(java.time.LocalDateTime.of(2026, 10, 24, 9, 30, 15), row.issuedAt);
            assertEquals(java.time.LocalDateTime.of(2027, 10, 24, 0, 0), row.validUntil);
            assertEquals(java.time.LocalDateTime.of(2026, 10, 25, 8, 0), row.exhaustedAt);
            assertEquals("C02-00000009", row.issuingDocumentNumber);
            assertEquals("C04", row.reportedBy);
            assertEquals("1234", row.pdvNumber);
            org.junit.jupiter.api.Assertions.assertNotNull(row.updatedAt);
            verify(row).persist();
            // BO-03-06-48: passed on to the central node under this shop.
            assertEquals("1234", dto.pdvNumber);
            verify(service.centralInstrumentRelay).forward(dto);
            dto.pdvNumber = null;
            PanacheQuery<com.intermarche.edge.domain.barcode.StoredValueRecord> found = queryReturning(existing);
            mocked.when(() -> com.intermarche.edge.domain.barcode.StoredValueRecord.find(
                    "issuingTerminalId = ?1 and number = ?2", "C02", "297000000000400")).thenReturn(found);
            dto.issuedAt = null;
            dto.validUntil = null;
            dto.exhaustedAt = null;
            service.ingestStoredValue(dto);
            verify(existing).persist();
            org.junit.jupiter.api.Assertions.assertNull(existing.issuedAt);
            assertEquals(1, created.constructed().size());
        }
    }

    /**
     * BO-03-06-48: on the central node an instrument a shop passed on is keyed
     * by that shop too — created under it the first time, updated after — and
     * is never passed on again.
     */
    @Test
    void ingestStoredValueKeysAShopsInstrumentByItsShop() {
        SyncIngestService service = new SyncIngestService();
        service.centralInstrumentRelay = mock(CentralInstrumentRelay.class);
        service.nodeEchelonService = mock(com.intermarche.edge.service.NodeEchelonService.class);
        SyncPayloads.StoredValueDto dto = new SyncPayloads.StoredValueDto();
        dto.pdvNumber = "5678";
        dto.issuingTerminalId = "POS01";
        dto.terminalId = "POS02";
        dto.number = "297000000000401";
        com.intermarche.edge.domain.barcode.StoredValueRecord existing =
                mock(com.intermarche.edge.domain.barcode.StoredValueRecord.class);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             org.mockito.MockedConstruction<com.intermarche.edge.domain.barcode.StoredValueRecord> created =
                     org.mockito.Mockito.mockConstruction(com.intermarche.edge.domain.barcode.StoredValueRecord.class)) {
            PanacheQuery<com.intermarche.edge.domain.barcode.StoredValueRecord> none = queryReturning(null);
            mocked.when(() -> com.intermarche.edge.domain.barcode.StoredValueRecord.find(
                    "pdvNumber = ?1 and issuingTerminalId = ?2 and number = ?3", "5678", "POS01",
                    "297000000000401")).thenReturn(none);
            service.ingestStoredValue(dto);
            assertEquals("5678", created.constructed().get(0).pdvNumber);
            PanacheQuery<com.intermarche.edge.domain.barcode.StoredValueRecord> found = queryReturning(existing);
            mocked.when(() -> com.intermarche.edge.domain.barcode.StoredValueRecord.find(
                    "pdvNumber = ?1 and issuingTerminalId = ?2 and number = ?3", "5678", "POS01",
                    "297000000000401")).thenReturn(found);
            service.ingestStoredValue(dto);
            verify(existing).persist();
            assertEquals(1, created.constructed().size());
        }
        org.mockito.Mockito.verifyNoInteractions(service.centralInstrumentRelay, service.nodeEchelonService);
    }

    /**
     * BO-03-06-48: a number this shop never printed is asked of the central
     * node; an instrument found there is kept here under the shop that
     * printed it and answered; one the central node does not know either
     * answers nothing.
     */
    @Test
    void findStoredValueAsksTheCentralBaseForAnUnknownNumber() {
        SyncIngestService service = new SyncIngestService();
        service.centralInstrumentRelay = mock(CentralInstrumentRelay.class);
        SyncPayloads.StoredValueDto central = new SyncPayloads.StoredValueDto();
        central.pdvNumber = "5678";
        central.issuingTerminalId = "POS01";
        central.terminalId = "POS01";
        central.number = "297000000000402";
        central.kind = "CREDIT_NOTE";
        central.initialAmount = new BigDecimal("8.00");
        central.balance = new BigDecimal("8.00");
        central.status = "ACTIVE";
        central.issuedAt = "2026-10-20T10:00";
        central.issuingDocumentNumber = "POS01-00000003";
        when(service.centralInstrumentRelay.lookup("297000000000402")).thenReturn(central);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             org.mockito.MockedConstruction<com.intermarche.edge.domain.barcode.StoredValueRecord> created =
                     org.mockito.Mockito.mockConstruction(com.intermarche.edge.domain.barcode.StoredValueRecord.class)) {
            PanacheQuery<com.intermarche.edge.domain.barcode.StoredValueRecord> none = queryReturning(null);
            mocked.when(() -> com.intermarche.edge.domain.barcode.StoredValueRecord.find(
                    org.mockito.ArgumentMatchers.eq("number = ?1 order by updatedAt desc, id desc"),
                    org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(none);
            org.junit.jupiter.api.Assertions.assertSame(central, service.findStoredValue("297000000000402"));
            com.intermarche.edge.domain.barcode.StoredValueRecord kept = created.constructed().get(0);
            assertEquals("5678", kept.pdvNumber);
            assertEquals("POS01", kept.issuingTerminalId);
            assertEquals("297000000000402", kept.number);
            assertEquals("CREDIT_NOTE", kept.kind);
            assertEquals(new BigDecimal("8.00"), kept.balance);
            assertEquals(java.time.LocalDateTime.of(2026, 10, 20, 10, 0), kept.issuedAt);
            assertEquals("POS01-00000003", kept.issuingDocumentNumber);
            verify(kept).persist();
            org.junit.jupiter.api.Assertions.assertNull(service.findStoredValue("297000000000499"));
            assertEquals(1, created.constructed().size());
        }
    }

    /**
     * BO-03-06-63: a payment barcode reading is upserted by its register and
     * the register's row.
     */
    @Test
    void ingestScannedBarcodeUpsertsByRegisterRow() {
        SyncIngestService service = new SyncIngestService();
        SyncPayloads.ScannedBarcodeDto dto = new SyncPayloads.ScannedBarcodeDto();
        dto.sourceId = 5L;
        dto.terminalId = "C04";
        dto.code = "297000000000400";
        dto.typeCode = "AVOIR";
        dto.typeLabel = "Avoir magasin";
        dto.ticketNumber = "C04-00000010";
        dto.amount = new BigDecimal("2.40");
        dto.validUntil = "2027-10-24T00:00";
        dto.scannedAt = "2026-10-26T11:00:05";
        dto.found = true;
        dto.gs1 = true;
        com.intermarche.edge.domain.barcode.ScannedBarcode existing =
                mock(com.intermarche.edge.domain.barcode.ScannedBarcode.class);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             org.mockito.MockedConstruction<com.intermarche.edge.domain.barcode.ScannedBarcode> created =
                     org.mockito.Mockito.mockConstruction(com.intermarche.edge.domain.barcode.ScannedBarcode.class)) {
            PanacheQuery<com.intermarche.edge.domain.barcode.ScannedBarcode> none = queryReturning(null);
            mocked.when(() -> com.intermarche.edge.domain.barcode.ScannedBarcode.find(
                    "terminalId = ?1 and sourceId = ?2", "C04", 5L)).thenReturn(none);
            service.ingestScannedBarcode(dto);
            com.intermarche.edge.domain.barcode.ScannedBarcode row = created.constructed().get(0);
            assertEquals("C04", row.terminalId);
            assertEquals(5L, row.sourceId);
            assertEquals("297000000000400", row.code);
            assertEquals("AVOIR", row.typeCode);
            assertEquals("Avoir magasin", row.typeLabel);
            assertEquals("C04-00000010", row.ticketNumber);
            assertEquals(new BigDecimal("2.40"), row.amount);
            assertEquals(java.time.LocalDateTime.of(2027, 10, 24, 0, 0), row.validUntil);
            assertEquals(java.time.LocalDateTime.of(2026, 10, 26, 11, 0, 5), row.scannedAt);
            org.junit.jupiter.api.Assertions.assertTrue(row.found);
            org.junit.jupiter.api.Assertions.assertTrue(row.gs1);
            verify(row).persist();
            PanacheQuery<com.intermarche.edge.domain.barcode.ScannedBarcode> found = queryReturning(existing);
            mocked.when(() -> com.intermarche.edge.domain.barcode.ScannedBarcode.find(
                    "terminalId = ?1 and sourceId = ?2", "C04", 5L)).thenReturn(found);
            service.ingestScannedBarcode(dto);
            verify(existing).persist();
            assertEquals(1, created.constructed().size());
        }
    }

    /**
     * BO-03-06-48: an instrument a register of the shop printed is answered
     * with its last reported state; one without dates answers none; an
     * unknown number answers nothing.
     */
    @Test
    void findStoredValueAnswersTheLastReport() {
        SyncIngestService service = new SyncIngestService();
        service.centralInstrumentRelay = mock(CentralInstrumentRelay.class);
        com.intermarche.edge.domain.barcode.StoredValueRecord record =
                new com.intermarche.edge.domain.barcode.StoredValueRecord();
        record.issuingTerminalId = "C02";
        record.reportedBy = "C04";
        record.number = "297000000000400";
        record.kind = "GIFT_CARD";
        record.initialAmount = new BigDecimal("30.00");
        record.balance = new BigDecimal("12.00");
        record.status = "ACTIVE";
        record.issuedAt = java.time.LocalDateTime.of(2026, 10, 24, 9, 30, 15);
        record.validUntil = java.time.LocalDateTime.of(2027, 10, 24, 0, 0);
        record.exhaustedAt = java.time.LocalDateTime.of(2026, 10, 25, 8, 0);
        record.issuingDocumentNumber = "C02-00000009";
        record.pdvNumber = "1234";
        com.intermarche.edge.domain.barcode.StoredValueRecord bare =
                new com.intermarche.edge.domain.barcode.StoredValueRecord();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<com.intermarche.edge.domain.barcode.StoredValueRecord> one = queryReturning(record);
            mocked.when(() -> com.intermarche.edge.domain.barcode.StoredValueRecord.find(
                    "number = ?1 order by updatedAt desc, id desc", "297000000000400")).thenReturn(one);
            PanacheQuery<com.intermarche.edge.domain.barcode.StoredValueRecord> other = queryReturning(bare);
            mocked.when(() -> com.intermarche.edge.domain.barcode.StoredValueRecord.find(
                    "number = ?1 order by updatedAt desc, id desc", "B")).thenReturn(other);
            PanacheQuery<com.intermarche.edge.domain.barcode.StoredValueRecord> none = queryReturning(null);
            mocked.when(() -> com.intermarche.edge.domain.barcode.StoredValueRecord.find(
                    "number = ?1 order by updatedAt desc, id desc", "X")).thenReturn(none);
            SyncPayloads.StoredValueDto dto = service.findStoredValue("297000000000400");
            assertEquals("C02", dto.issuingTerminalId);
            assertEquals("C04", dto.terminalId);
            assertEquals("297000000000400", dto.number);
            assertEquals("GIFT_CARD", dto.kind);
            assertEquals(new BigDecimal("30.00"), dto.initialAmount);
            assertEquals(new BigDecimal("12.00"), dto.balance);
            assertEquals("ACTIVE", dto.status);
            assertEquals("2026-10-24T09:30:15", dto.issuedAt);
            assertEquals("2027-10-24T00:00", dto.validUntil);
            assertEquals("2026-10-25T08:00", dto.exhaustedAt);
            assertEquals("C02-00000009", dto.issuingDocumentNumber);
            assertEquals("1234", dto.pdvNumber);
            SyncPayloads.StoredValueDto empty = service.findStoredValue("B");
            org.junit.jupiter.api.Assertions.assertNull(empty.issuedAt);
            org.junit.jupiter.api.Assertions.assertNull(empty.validUntil);
            org.junit.jupiter.api.Assertions.assertNull(empty.exhaustedAt);
            org.junit.jupiter.api.Assertions.assertNull(service.findStoredValue("X"));
        }
    }

    /**
     * Builds a pushed fiscal document.
     *
     * @param number the document number
     * @return the payload
     */
    private SyncPayloads.FiscalDocumentDto fiscalDto(String number) {
        SyncPayloads.FiscalDocumentDto dto = new SyncPayloads.FiscalDocumentDto();
        dto.documentType = "FS";
        dto.series = "C04/2026";
        dto.rank = 1;
        dto.number = number;
        dto.atcud = "AAA-1";
        dto.saftDocumentId = 1;
        dto.terminalId = "C04";
        dto.issuedAt = "2026-10-24T10:30:00";
        dto.part = 2;
        dto.lineUids = "U1";
        dto.sourceNumber = "SRC";
        dto.customerTaxId = "123456789";
        dto.customerName = "ACME";
        dto.customerAddress = "Rua 1";
        dto.grossTotal = new BigDecimal("12.30");
        dto.taxTotal = new BigDecimal("2.30");
        dto.hash = "HASH";
        dto.previousHash = "PREV";
        dto.qrPayload = "QR";
        dto.cancelled = true;
        return dto;
    }

    /**
     * BO-10-06-03: a pushed fiscal document is created when the node holds
     * none of that number and rewritten when it does, every field copied and
     * attached to the node's sale or return.
     */
    @Test
    @SuppressWarnings("unchecked")
    void storeFiscalDocumentsUpsertsByNumber() {
        SyncIngestService service = serviceWith();
        com.intermarche.edge.domain.sale.FiscalDocument existing =
                mock(com.intermarche.edge.domain.sale.FiscalDocument.class);
        PanacheQuery<Object> known = mock(PanacheQuery.class);
        when(known.firstResult()).thenReturn(existing);
        PanacheQuery<Object> unknown = mock(PanacheQuery.class);
        when(unknown.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<com.intermarche.edge.domain.sale.FiscalDocument> created =
                     mockConstruction(com.intermarche.edge.domain.sale.FiscalDocument.class)) {
            panache.when(() -> PanacheEntityBase.find("number", "FS-OLD")).thenReturn(known);
            panache.when(() -> PanacheEntityBase.find("number", "FS-NEW")).thenReturn(unknown);
            service.storeFiscalDocuments(List.of(fiscalDto("FS-OLD"), fiscalDto("FS-NEW")), 5L, null);
            service.storeFiscalDocuments(List.of(), null, 6L);
            assertEquals(1, created.constructed().size());
            com.intermarche.edge.domain.sale.FiscalDocument fresh = created.constructed().get(0);
            assertEquals("FS-NEW", fresh.number);
            verify(service.saftStreamService).onDocument(fresh);
            verify(service.saftStreamService).onDocument(existing);
            assertEquals(com.intermarche.edge.domain.sale.FiscalDocument.Type.FS, fresh.documentType);
            assertEquals("C04/2026", fresh.series);
            assertEquals(1, fresh.rank);
            assertEquals("AAA-1", fresh.atcud);
            assertEquals(1, fresh.saftDocumentId);
            assertEquals("C04", fresh.terminalId);
            assertEquals(LocalDateTime.of(2026, 10, 24, 10, 30), fresh.issuedAt);
            assertEquals(5L, fresh.ticketId);
            assertNull(fresh.refundId);
            assertEquals(2, fresh.part);
            assertEquals("U1", fresh.lineUids);
            assertEquals("SRC", fresh.sourceNumber);
            assertEquals("123456789", fresh.customerTaxId);
            assertEquals("ACME", fresh.customerName);
            assertEquals("Rua 1", fresh.customerAddress);
            assertEquals(new BigDecimal("12.30"), fresh.grossTotal);
            assertEquals(new BigDecimal("2.30"), fresh.taxTotal);
            assertEquals("HASH", fresh.hash);
            assertEquals("PREV", fresh.previousHash);
            assertEquals("QR", fresh.qrPayload);
            assertTrue(fresh.cancelled);
            verify(fresh).persist();
            assertEquals("AAA-1", existing.atcud);
            verify(existing).persist();
        }
    }

    /**
     * BO-06-08-02: the valuation trace pushed with a ticket replaces the one
     * kept, a trace without line skipped, missing totals read zero; nothing is
     * touched for an older register pushing none, nor for an unsaved ticket.
     */
    @Test
    void theValuationTraceReplacesTheKeptOne() {
        SyncIngestService service = new SyncIngestService();
        Ticket ticket = new Ticket();
        ticket.id = 9L;
        SyncPayloads.ValuationDto full = new SyncPayloads.ValuationDto();
        full.lineUid = "L1";
        full.localTotal = new BigDecimal("2.40");
        full.valuedTotal = new BigDecimal("2.00");
        full.offerLabel = "OFF1";
        full.advantageLabel = "Lot de 3";
        full.advantageAmount = new BigDecimal("-0.40");
        full.advantagePrintMode = "LINE";
        full.correctionLabel = "Arrondi";
        full.correctionAmount = new BigDecimal("0.01");
        SyncPayloads.ValuationDto bare = new SyncPayloads.ValuationDto();
        bare.lineUid = "L2";
        SyncPayloads.ValuationDto lineless = new SyncPayloads.ValuationDto();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<com.intermarche.edge.domain.sale.TicketLineValuation> built =
                     mockConstruction(com.intermarche.edge.domain.sale.TicketLineValuation.class)) {
            service.storeValuations(null, ticket);
            service.storeValuations(List.of(full), new Ticket());
            panache.verifyNoInteractions();
            service.storeValuations(List.of(full, bare, lineless), ticket);
            panache.verify(() -> PanacheEntityBase.delete("ticket.id", 9L));
            assertEquals(2, built.constructed().size());
            com.intermarche.edge.domain.sale.TicketLineValuation first = built.constructed().get(0);
            assertSame(ticket, first.ticket);
            assertEquals("L1", first.lineUid);
            assertEquals(new BigDecimal("2.40"), first.localTotal);
            assertEquals(new BigDecimal("2.00"), first.valuedTotal);
            assertEquals("OFF1", first.offerLabel);
            assertEquals("Lot de 3", first.advantageLabel);
            assertEquals(new BigDecimal("-0.40"), first.advantageAmount);
            assertEquals("LINE", first.advantagePrintMode);
            assertEquals("Arrondi", first.correctionLabel);
            assertEquals(new BigDecimal("0.01"), first.correctionAmount);
            verify(first).persist();
            com.intermarche.edge.domain.sale.TicketLineValuation second = built.constructed().get(1);
            assertEquals(BigDecimal.ZERO, second.localTotal);
            assertEquals(BigDecimal.ZERO, second.valuedTotal);
        }
    }
}
