package com.intermarche.pos.service;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductFamily;
import com.intermarche.pos.domain.payment.CashPayment;
import com.intermarche.pos.domain.payment.StoredValue;
import com.intermarche.pos.domain.payment.TicketPayment;
import com.intermarche.pos.domain.payment.VoucherPayment;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketFidelityLine;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.domain.session.TicketCounter;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.PaymentState;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TicketPersistenceService}.
 * <p>
 * The service persists the in-memory ticket through Panache active-record
 * static access ({@code Ticket.findById}, {@code Store.findAll},
 * {@code Employee.findById}, {@code Product.find}), all intercepted with
 * {@link org.mockito.Mockito#mockStatic} on {@link PanacheEntityBase}. The
 * draft entity itself is either a Mockito mock (reconcile / payment /
 * finalization paths, whose {@code persist()} is a no-op and whose public
 * fields are read and written directly) or, on the creation path, a
 * construction mock installed with {@link org.mockito.Mockito#mockConstruction}
 * so {@code new Ticket()} yields a neutralized instance. No database and no
 * Quarkus context is booted. The injected {@link PosState} is a real instance
 * so the in-memory ticket and payment behave exactly as in production, while
 * the four collaborators ({@link TicketNumberService}, {@link CashSessionService},
 * {@link TechnicalEventService}, {@link SyncOutboxService}) and the payment
 * factories are Mockito mocks assigned to the package-private injection fields.
 * <p>
 * Every branch of the seven public methods and their private helpers is
 * covered: {@code syncDraft} (training mode, empty cart with and without a
 * draft, creation, resync on missing / non-OPEN draft, nominal reconcile),
 * {@code createDraft} (store missing, cashier missing, no session, success
 * exercising the three {@code mapLine} product-lookup arms and the negative
 * line), {@code reconcileLines} (orphan removal of null-uid and vanished
 * lines, in-place update of both modifier arms, new line), the fidelity
 * ternary of {@code applyHeaderAndTotals}, {@code addPaymentToTicket} (missing
 * ticket, plain method, voucher, unknown method), {@code removePaymentsFromTicket}
 * (missing ticket, present payments, empty payments), {@code validateTicket}
 * (missing ticket, chained and GENESIS previous signature) and
 * {@code cancelDraft} (OPEN draft, null ticket, non-OPEN draft). JaCoCo branch
 * count: 70/70 branches covered (100%). The only two uncovered lines are the
 * JVM-mandated {@code catch (NoSuchAlgorithmException)} of {@code sha256Hex},
 * which is unreachable and carries no branch.
 */
class TicketPersistenceServiceTest {

    /** The terminal identifier used across the tests. */
    private static final String TERMINAL = "C04";

    /**
     * Builds a service instance with the four collaborators mocked and the
     * terminal id resolved through the ticket number service.
     *
     * @return a ready-to-use service with mocked collaborators
     */
    private TicketPersistenceService newService() {
        TicketPersistenceService service = new TicketPersistenceService();
        service.ticketNumberService = mock(TicketNumberService.class);
        service.cashSessionService = mock(CashSessionService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        service.syncOutboxService = mock(SyncOutboxService.class);
        service.consumptionService = mock(com.intermarche.pos.service.sync.ConsumptionService.class);
        when(service.ticketNumberService.getTerminalId()).thenReturn(TERMINAL);
        return service;
    }

    /**
     * Appends an in-memory ticket item to the given state with full control on
     * its identity, price and modifier, bypassing the merge rules of
     * {@code addItem}.
     *
     * @param state the POS state whose cart must be filled
     * @param uid the stable line uid
     * @param ean the EAN code, or null
     * @param plu the PLU code, or null
     * @param unitPrice the unit price including tax
     * @param quantity the quantity
     * @param modifierLabel the price-modification label, or null
     * @return the appended item, for further tuning
     */
    private TicketState.TicketItem addItem(PosState state, String uid, String ean, String plu,
            String unitPrice, String quantity, String modifierLabel) {
        TicketState.TicketItem item = new TicketState.TicketItem(
                ean, plu, "LABEL", new BigDecimal(unitPrice), new BigDecimal(quantity), new BigDecimal("0.2000"));
        item.uid = uid;
        item.modifierLabel = modifierLabel;
        state.ticket.items.add(item);
        return item;
    }

    /**
     * Creates a mocked draft with the given status, exposing an empty mutable
     * line list, an empty payment list and a fixed id and number.
     *
     * @param status the lifecycle status of the draft
     * @return the configured mocked draft
     */
    private Ticket draft(Ticket.TicketStatus status) {
        Ticket ticket = mock(Ticket.class);
        ticket.id = 5L;
        ticket.status = status;
        ticket.ticketNumber = "C04-00000001";
        ticket.terminalId = TERMINAL;
        ticket.lines = new ArrayList<>();
        ticket.payments = new ArrayList<>();
        return ticket;
    }

    /**
     * Creates a persisted ticket line carrying the given uid.
     *
     * @param uid the line uid, possibly null
     * @return the configured line
     */
    private TicketLine line(String uid) {
        TicketLine line = new TicketLine();
        line.lineUid = uid;
        line.quantity = BigDecimal.ONE;
        line.unitPrice = BigDecimal.ONE;
        line.totalPrice = BigDecimal.ONE;
        line.vatRate = new BigDecimal("0.2000");
        return line;
    }

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
     * Builds a Panache query whose {@code list} resolves to the given values,
     * as the direct-family lookup reads it.
     *
     * @param results the values the query must list
     * @param <T> the queried type
     * @return the configured mocked query
     */
    @SafeVarargs
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> queryListing(T... results) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(java.util.List.of(results));
        return query;
    }

    // --------------------------------------------------
    // syncDraft
    // --------------------------------------------------

    /**
     * Covers the training-mode guard of {@code syncDraft}: nothing fiscal
     * reaches the database and the method answers null.
     */
    @Test
    void syncDraftTrainingModeReturnsNull() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.trainingMode = true;
        assertNull(service.syncDraft(state));
        verifyNoInteractions(service.technicalEventService);
        verifyNoInteractions(service.syncOutboxService);
    }

    /**
     * Covers the empty-cart arm of {@code syncDraft} with an existing draft:
     * the draft is cancelled, journaled, enqueued and the pivot id is cleared.
     */
    @Test
    void syncDraftEmptyCartCancelsExistingDraft() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.payment.ticketDbId = 5L;
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            assertNull(service.syncDraft(state));
            assertEquals(Ticket.TicketStatus.CANCELLED, ticket.status);
            assertNull(state.payment.ticketDbId);
            verify(ticket, times(1)).persist();
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.TICKET_CANCELLED, "C04-00000001");
            verify(service.syncOutboxService).enqueue(SyncOutbox.EntityType.TICKET, 5L);
        }
    }

    /**
     * Covers the empty-cart arm of {@code syncDraft} with no draft: the method
     * answers null and nothing is cancelled.
     */
    @Test
    void syncDraftEmptyCartNoDraftReturnsNull() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.payment.ticketDbId = null;
        assertNull(service.syncDraft(state));
        verifyNoInteractions(service.technicalEventService);
        verifyNoInteractions(service.syncOutboxService);
    }

    /**
     * Covers the creation arm of {@code syncDraft}: no draft yet and a
     * non-empty cart, so a draft is created and its id stored as the pivot.
     * The three product-lookup arms of {@code mapLine} (PLU, EAN, neither) and
     * the negative-line arm are exercised in the same pass, and the fidelity
     * ternary takes its null branch.
     */
    @Test
    void syncDraftCreatesDraftFromCart() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.auth.operatorId = 99L;
        state.fidelity.active = false;
        addItem(state, "U1", null, "500", "3.00", "2", null);
        addItem(state, "U2", "3000", null, "1.50", "1", null);
        addItem(state, "U3", null, null, "-2.00", "1", null);
        Store store = mock(Store.class);
        Employee cashier = mock(Employee.class);
        CashSession session = mock(CashSession.class);
        Product product = mock(Product.class);
        when(service.ticketNumberService.nextTicketNumber()).thenReturn("C04-00000001");
        when(service.ticketNumberService.getTerminalId()).thenReturn(TERMINAL);
        when(service.cashSessionService.getOpenSession()).thenReturn(session);
        PanacheQuery<Store> storeQuery = queryReturning(store);
        PanacheQuery<Product> pluQuery = queryReturning(product);
        PanacheQuery<Product> eanQuery = queryReturning(product);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> created = mockConstruction(Ticket.class, (mock, ctx) -> {
                    mock.id = 100L;
                    mock.lines = new ArrayList<>();
                })) {
            mocked.when(Store::findAll).thenReturn(storeQuery);
            mocked.when(() -> Employee.findById(99L)).thenReturn(cashier);
            mocked.when(() -> Product.find("plu", "500")).thenReturn(pluQuery);
            mocked.when(() -> Product.find("ean", "3000")).thenReturn(eanQuery);
            Long id = service.syncDraft(state);
            assertEquals(100L, id);
            assertEquals(100L, state.payment.ticketDbId);
            Ticket ticket = created.constructed().get(0);
            assertEquals("C04-00000001", ticket.ticketNumber);
            assertEquals(TERMINAL, ticket.terminalId);
            assertSame(store, ticket.store);
            assertSame(cashier, ticket.cashier);
            assertSame(session, ticket.session);
            assertNull(ticket.fidelityCard);
            assertEquals(3, ticket.itemCount);
            verify(ticket, times(1)).persist();
        }
    }

    /**
     * The price-embedded flag is carried from each in-memory line onto its
     * persisted {@code TicketLine} (mapped both true and false), so the sticker
     * total survives a restart and a re-valuation.
     */
    @Test
    void syncDraftMapsThePriceEmbeddedFlagOntoEachLine() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.auth.operatorId = 99L;
        state.fidelity.active = false;
        TicketState.TicketItem sticker = addItem(state, "S1", null, null, "3.00", "1", null);
        sticker.priceEmbedded = true;
        // EXP-26/LC-02-05-04: the deduced weight + price/kg travel to the line too.
        sticker.embeddedWeight = new BigDecimal("0.750");
        sticker.embeddedUnitPrice = new BigDecimal("4.00");
        TicketState.TicketItem plain = addItem(state, "P1", null, null, "2.00", "1", null);
        plain.priceEmbedded = false;
        Store store = mock(Store.class);
        Employee cashier = mock(Employee.class);
        CashSession session = mock(CashSession.class);
        when(service.ticketNumberService.nextTicketNumber()).thenReturn("C04-00000001");
        when(service.ticketNumberService.getTerminalId()).thenReturn(TERMINAL);
        when(service.cashSessionService.getOpenSession()).thenReturn(session);
        PanacheQuery<Store> storeQuery = queryReturning(store);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> created = mockConstruction(Ticket.class, (mock, ctx) -> {
                    mock.id = 100L;
                    mock.lines = new ArrayList<>();
                })) {
            mocked.when(Store::findAll).thenReturn(storeQuery);
            mocked.when(() -> Employee.findById(99L)).thenReturn(cashier);
            service.syncDraft(state);
            Ticket ticket = created.constructed().get(0);
            ArgumentCaptor<TicketLine> lineCaptor = ArgumentCaptor.forClass(TicketLine.class);
            verify(ticket, times(2)).addLine(lineCaptor.capture());
            assertTrue(lineCaptor.getAllValues().get(0).priceEmbedded);
            assertFalse(lineCaptor.getAllValues().get(1).priceEmbedded);
            assertEquals(0, new BigDecimal("0.750").compareTo(lineCaptor.getAllValues().get(0).embeddedWeight));
            assertEquals(0, new BigDecimal("4.00").compareTo(lineCaptor.getAllValues().get(0).embeddedUnitPrice));
            assertNull(lineCaptor.getAllValues().get(1).embeddedWeight);
            assertNull(lineCaptor.getAllValues().get(1).embeddedUnitPrice);
        }
    }

    /**
     * The activation code of a dematerialised article and the provider that
     * owes it (BO-03-06-44) are carried from the in-memory line onto its
     * persisted line, both arms: a customer coming back with a gift card that
     * never worked is answered by the SOLD line, not by a cart that is gone.
     */
    @Test
    void syncDraftMapsTheActivationCodeOntoEachLine() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.auth.operatorId = 99L;
        state.fidelity.active = false;
        TicketState.TicketItem demat = addItem(state, "D1", null, null, "50.00", "1", null);
        demat.activationCode = "ACT-123";
        demat.activationProvider = "TRANSACTIS";
        addItem(state, "P1", null, null, "2.00", "1", null);
        Store store = mock(Store.class);
        Employee cashier = mock(Employee.class);
        CashSession session = mock(CashSession.class);
        when(service.ticketNumberService.nextTicketNumber()).thenReturn("C04-00000001");
        when(service.ticketNumberService.getTerminalId()).thenReturn(TERMINAL);
        when(service.cashSessionService.getOpenSession()).thenReturn(session);
        PanacheQuery<Store> storeQuery = queryReturning(store);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> created = mockConstruction(Ticket.class, (mock, ctx) -> {
                    mock.id = 100L;
                    mock.lines = new ArrayList<>();
                })) {
            mocked.when(Store::findAll).thenReturn(storeQuery);
            mocked.when(() -> Employee.findById(99L)).thenReturn(cashier);
            service.syncDraft(state);
            Ticket ticket = created.constructed().get(0);
            ArgumentCaptor<TicketLine> lineCaptor = ArgumentCaptor.forClass(TicketLine.class);
            verify(ticket, times(2)).addLine(lineCaptor.capture());
            assertEquals("ACT-123", lineCaptor.getAllValues().get(0).activationCode);
            assertEquals("TRANSACTIS", lineCaptor.getAllValues().get(0).activationProvider);
            assertNull(lineCaptor.getAllValues().get(1).activationCode);
            assertNull(lineCaptor.getAllValues().get(1).activationProvider);
        }
    }

    /**
     * The reduction ban (BO-02-03-09) is carried from each in-memory line onto
     * its persisted line, both arms, so the ban survives a restart instead of
     * being lost with the in-memory cart.
     */
    @Test
    void syncDraftMapsTheReductionBanOntoEachLine() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.auth.operatorId = 99L;
        state.fidelity.active = false;
        TicketState.TicketItem banned = addItem(state, "B1", null, null, "3.00", "1", null);
        banned.discountForbidden = true;
        TicketState.TicketItem plain = addItem(state, "P1", null, null, "2.00", "1", null);
        plain.discountForbidden = false;
        Store store = mock(Store.class);
        Employee cashier = mock(Employee.class);
        CashSession session = mock(CashSession.class);
        when(service.ticketNumberService.nextTicketNumber()).thenReturn("C04-00000001");
        when(service.ticketNumberService.getTerminalId()).thenReturn(TERMINAL);
        when(service.cashSessionService.getOpenSession()).thenReturn(session);
        PanacheQuery<Store> storeQuery = queryReturning(store);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> created = mockConstruction(Ticket.class, (mock, ctx) -> {
                    mock.id = 100L;
                    mock.lines = new ArrayList<>();
                })) {
            mocked.when(Store::findAll).thenReturn(storeQuery);
            mocked.when(() -> Employee.findById(99L)).thenReturn(cashier);
            service.syncDraft(state);
            Ticket ticket = created.constructed().get(0);
            ArgumentCaptor<TicketLine> lineCaptor = ArgumentCaptor.forClass(TicketLine.class);
            verify(ticket, times(2)).addLine(lineCaptor.capture());
            assertTrue(lineCaptor.getAllValues().get(0).discountForbidden);
            assertFalse(lineCaptor.getAllValues().get(1).discountForbidden);
        }
    }

    /**
     * The nomenclature snapshot (BO-04-01-11) is captured on each persisted
     * line at creation: a product with a direct family carries its code and
     * label, a product attached to no family (family query resolves null) and
     * a line with no catalog product both leave the snapshot blank. The three
     * arms of the {@code line.product != null} / {@code family != null} guards
     * are exercised in one pass.
     */
    @Test
    void syncDraftSnapshotsTheNomenclatureOntoEachLine() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.auth.operatorId = 99L;
        state.fidelity.active = false;
        addItem(state, "F1", "3000", null, "2.00", "1", null);
        addItem(state, "F2", "4000", null, "1.00", "1", null);
        addItem(state, "F3", null, null, "-1.00", "1", null);
        Store store = mock(Store.class);
        Employee cashier = mock(Employee.class);
        CashSession session = mock(CashSession.class);
        Product withFamily = mock(Product.class);
        withFamily.id = 7L;
        Product noFamily = mock(Product.class);
        noFamily.id = 8L;
        ProductFamily family = new ProductFamily();
        family.code = "FRUITS";
        family.description = "Rayon Fruits";
        when(service.ticketNumberService.nextTicketNumber()).thenReturn("C04-00000001");
        when(service.cashSessionService.getOpenSession()).thenReturn(session);
        PanacheQuery<Store> storeQuery = queryReturning(store);
        PanacheQuery<Product> eanWith = queryReturning(withFamily);
        PanacheQuery<Product> eanNo = queryReturning(noFamily);
        PanacheQuery<ProductFamily> familyQuery = queryListing(family);
        PanacheQuery<ProductFamily> emptyFamilyQuery = queryListing();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> created = mockConstruction(Ticket.class, (mock, ctx) -> {
                    mock.id = 100L;
                    mock.lines = new ArrayList<>();
                })) {
            mocked.when(Store::findAll).thenReturn(storeQuery);
            mocked.when(() -> Employee.findById(99L)).thenReturn(cashier);
            mocked.when(() -> Product.find("ean", "3000")).thenReturn(eanWith);
            mocked.when(() -> Product.find("ean", "4000")).thenReturn(eanNo);
            mocked.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 7L))
                    .thenReturn(familyQuery);
            mocked.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 8L))
                    .thenReturn(emptyFamilyQuery);
            service.syncDraft(state);
            Ticket ticket = created.constructed().get(0);
            ArgumentCaptor<TicketLine> lineCaptor = ArgumentCaptor.forClass(TicketLine.class);
            verify(ticket, times(3)).addLine(lineCaptor.capture());
            TicketLine withFam = lineCaptor.getAllValues().get(0);
            TicketLine noFam = lineCaptor.getAllValues().get(1);
            TicketLine noProd = lineCaptor.getAllValues().get(2);
            assertEquals("FRUITS", withFam.familyCode);
            assertEquals("Rayon Fruits", withFam.familyLabel);
            assertNull(noFam.familyCode);
            assertNull(noFam.familyLabel);
            assertNull(noProd.familyCode);
            assertNull(noProd.familyLabel);
        }
    }

    /**
     * Totals invariance (campaign rule): the same cart yields byte-identical
     * HT, TTC and VAT totals whether or not the sold product carries a
     * nomenclature — the snapshot decorates the line, it never moves a centime.
     */
    @Test
    void syncDraftKeepsTicketTotalsInvariantUnderTheNomenclatureSnapshot() {
        BigDecimal[] withFamily = totalsOfSingleLineDraft(true);
        BigDecimal[] withoutFamily = totalsOfSingleLineDraft(false);
        assertEquals(withoutFamily[0], withFamily[0]);
        assertEquals(withoutFamily[1], withFamily[1]);
        assertEquals(withoutFamily[2], withFamily[2]);
    }

    /**
     * Creates a one-line draft (2 units at 2,50 €, 20% VAT) and returns its
     * persisted [HT, TTC, VAT] totals, resolving a family on the product only
     * when requested — the two runs differ solely by the snapshot.
     *
     * @param withFamily whether the product resolves a direct family
     * @return the three ticket totals of the created draft
     */
    private BigDecimal[] totalsOfSingleLineDraft(boolean withFamily) {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.auth.operatorId = 99L;
        state.fidelity.active = false;
        addItem(state, "L1", "3000", null, "2.50", "2", null);
        Store store = mock(Store.class);
        Employee cashier = mock(Employee.class);
        CashSession session = mock(CashSession.class);
        Product product = mock(Product.class);
        product.id = 7L;
        when(service.ticketNumberService.nextTicketNumber()).thenReturn("C04-00000001");
        when(service.cashSessionService.getOpenSession()).thenReturn(session);
        PanacheQuery<Store> storeQuery = queryReturning(store);
        PanacheQuery<Product> eanQuery = queryReturning(product);
        ProductFamily family = new ProductFamily();
        family.code = "FRUITS";
        family.description = "Rayon Fruits";
        PanacheQuery<ProductFamily> familyQuery = withFamily ? queryListing(family) : queryListing();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> created = mockConstruction(Ticket.class, (mock, ctx) -> {
                    mock.id = 100L;
                    mock.lines = new ArrayList<>();
                })) {
            mocked.when(Store::findAll).thenReturn(storeQuery);
            mocked.when(() -> Employee.findById(99L)).thenReturn(cashier);
            mocked.when(() -> Product.find("ean", "3000")).thenReturn(eanQuery);
            mocked.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 7L))
                    .thenReturn(familyQuery);
            service.syncDraft(state);
            Ticket ticket = created.constructed().get(0);
            return new BigDecimal[]{ticket.totalExcludingTax, ticket.totalIncludingTax, ticket.totalVat};
        }
    }

    /**
     * Covers the store-missing arm of {@code createDraft}: no store is found,
     * so the creation returns null and the pivot stays null.
     */
    @Test
    void syncDraftCreateReturnsNullWhenStoreMissing() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.auth.operatorId = 99L;
        addItem(state, "U1", "3000", null, "1.50", "1", null);
        PanacheQuery<Store> storeQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(Store::findAll).thenReturn(storeQuery);
            mocked.when(() -> Employee.findById(99L)).thenReturn(mock(Employee.class));
            assertNull(service.syncDraft(state));
            assertNull(state.payment.ticketDbId);
        }
    }

    /**
     * Covers the cashier-missing arm of {@code createDraft}: the operator id is
     * null (the ternary takes its null branch), so no cashier is resolved and
     * the creation returns null.
     */
    @Test
    void syncDraftCreateReturnsNullWhenCashierMissing() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.auth.operatorId = null;
        addItem(state, "U1", "3000", null, "1.50", "1", null);
        PanacheQuery<Store> storeQuery = queryReturning(mock(Store.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(Store::findAll).thenReturn(storeQuery);
            assertNull(service.syncDraft(state));
            assertNull(state.payment.ticketDbId);
        }
    }

    /**
     * Covers the no-session arm of {@code createDraft}: store and cashier are
     * resolved but no cash session is open, so the creation returns null.
     */
    @Test
    void syncDraftCreateReturnsNullWhenNoSession() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.auth.operatorId = 99L;
        addItem(state, "U1", "3000", null, "1.50", "1", null);
        when(service.cashSessionService.getOpenSession()).thenReturn(null);
        PanacheQuery<Store> storeQuery = queryReturning(mock(Store.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(Store::findAll).thenReturn(storeQuery);
            mocked.when(() -> Employee.findById(99L)).thenReturn(mock(Employee.class));
            assertNull(service.syncDraft(state));
            assertNull(state.payment.ticketDbId);
        }
    }

    /**
     * Covers the missing-draft resync arm of {@code syncDraft}: the pivot id
     * points to a vanished ticket, so a fresh draft is created.
     */
    @Test
    void syncDraftResyncsWhenDraftMissing() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.auth.operatorId = 99L;
        state.payment.ticketDbId = 5L;
        addItem(state, "U1", null, null, "1.50", "1", null);
        when(service.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(service.ticketNumberService.nextTicketNumber()).thenReturn("C04-00000002");
        PanacheQuery<Store> storeQuery = queryReturning(mock(Store.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> created = mockConstruction(Ticket.class, (mock, ctx) -> {
                    mock.id = 200L;
                    mock.lines = new ArrayList<>();
                })) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(null);
            mocked.when(Store::findAll).thenReturn(storeQuery);
            mocked.when(() -> Employee.findById(99L)).thenReturn(mock(Employee.class));
            assertEquals(200L, service.syncDraft(state));
            assertEquals(200L, state.payment.ticketDbId);
        }
    }

    /**
     * Covers the non-OPEN resync arm of {@code syncDraft}: the pivot id points
     * to a ticket that is no longer OPEN, so a fresh draft is created.
     */
    @Test
    void syncDraftResyncsWhenDraftNotOpen() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.auth.operatorId = 99L;
        state.payment.ticketDbId = 5L;
        addItem(state, "U1", null, null, "1.50", "1", null);
        Ticket closed = draft(Ticket.TicketStatus.CLOSED);
        when(service.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(service.ticketNumberService.nextTicketNumber()).thenReturn("C04-00000002");
        PanacheQuery<Store> storeQuery = queryReturning(mock(Store.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> created = mockConstruction(Ticket.class, (mock, ctx) -> {
                    mock.id = 200L;
                    mock.lines = new ArrayList<>();
                })) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(closed);
            mocked.when(Store::findAll).thenReturn(storeQuery);
            mocked.when(() -> Employee.findById(99L)).thenReturn(mock(Employee.class));
            assertEquals(200L, service.syncDraft(state));
            assertEquals(200L, state.payment.ticketDbId);
        }
    }

    /**
     * Covers the nominal reconcile arm of {@code syncDraft}: the OPEN draft is
     * reconciled line by line (null-uid and vanished lines orphan-removed, both
     * modifier arms updated in place, a new line appended), the totals and the
     * fidelity card (active branch) are refreshed, and the draft is persisted.
     */
    @Test
    void syncDraftReconcilesOpenDraft() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.fidelity.active = true;
        state.fidelity.label = "CARD-123";
        addItem(state, "U1", "3000", null, "2.00", "1", "REMISE -10%");
        addItem(state, "U2", "4000", null, "1.00", "1", null);
        addItem(state, "U3", null, null, "3.00", "1", null);
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.lines = new ArrayList<>(Arrays.asList(
                line("U1"), line("U2"), line(null), line("GONE")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            state.payment.ticketDbId = 5L;
            assertEquals(5L, service.syncDraft(state));
            assertEquals(2, ticket.lines.size());
            assertEquals("CARD-123", ticket.fidelityCard);
            assertEquals(3, ticket.itemCount);
            verify(ticket, times(1)).addLine(any(TicketLine.class));
            verify(ticket, times(1)).persist();
        }
    }

    // --------------------------------------------------
    // markLineCancelled / article-cancellation conservation (lot C4, BO-04-01-16)
    // --------------------------------------------------

    /**
     * {@code markLineCancelled} stamps the matching line with the flag, a
     * timestamp and the operator badge, persists once, and leaves the other
     * lines untouched.
     */
    @Test
    void markLineCancelledMarksTheMatchingLine() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        TicketLine a = line("U1");
        TicketLine b = line("U2");
        ticket.lines.add(a);
        ticket.lines.add(b);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.markLineCancelled(5L, "U1", "12341234", null, null);
            assertTrue(a.cancelled);
            assertNotNull(a.cancellationDate);
            assertEquals("12341234", a.cancelledBy);
            assertFalse(b.cancelled);
            verify(ticket, times(1)).persist();
        }
    }

    /**
     * BO-06-06-04: the justification travels with the witness — its code and
     * its label are frozen on the line beside the author and the timestamp.
     */
    @Test
    void markLineCancelledStoresTheJustification() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        TicketLine a = line("U1");
        ticket.lines.add(a);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.markLineCancelled(5L, "U1", "12341234", "ERREUR", "Erreur de saisie");
            assertEquals("ERREUR", a.cancellationReasonCode);
            assertEquals("Erreur de saisie", a.cancellationReasonLabel);
        }
    }

    /**
     * BO-06-06-04: no justification administered, no justification stored —
     * the line carries null rather than an invented code.
     */
    @Test
    void markLineCancelledStoresNoJustificationWhenNoneIsGiven() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        TicketLine a = line("U1");
        ticket.lines.add(a);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.markLineCancelled(5L, "U1", "12341234", null, null);
            assertNull(a.cancellationReasonCode);
            assertNull(a.cancellationReasonLabel);
        }
    }

    /**
     * {@code markLineCancelled} flags {@code cancelledLast} when the cancelled
     * line carries the ticket's greatest line number at the moment of the
     * gesture (R10): a last-line cancellation, the mis-scan corrected on the
     * spot, not the fraud indicator.
     */
    @Test
    void markLineCancelledFlagsTheLastLine() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        TicketLine a = line("U1");
        a.lineNumber = 2;
        TicketLine b = line("U2");
        b.lineNumber = 1;
        ticket.lines.add(a);
        ticket.lines.add(b);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.markLineCancelled(5L, "U1", "12341234", null, null);
            assertTrue(a.cancelledLast);
        }
    }

    /**
     * {@code markLineCancelled} leaves {@code cancelledLast} false when the
     * cancelled line is NOT the ticket's last (R10): the endorsed cancellation
     * of a mid-ticket line, which the financial state counts as the fraud
     * indicator.
     */
    @Test
    void markLineCancelledClearsTheLastFlagForAMidLine() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        TicketLine a = line("U1");
        a.lineNumber = 1;
        TicketLine b = line("U2");
        b.lineNumber = 2;
        ticket.lines.add(a);
        ticket.lines.add(b);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.markLineCancelled(5L, "U1", "12341234", null, null);
            assertFalse(a.cancelledLast);
        }
    }

    /**
     * {@code markLineCancelled} is a no-op when the draft is not found (the
     * cancellation still empties the cart in memory; the witness is
     * best-effort).
     */
    @Test
    void markLineCancelledNoOpWhenTicketMissing() {
        TicketPersistenceService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(null);
            service.markLineCancelled(5L, "U1", "12341234", null, null);
        }
    }

    /**
     * {@code markLineCancelled} marks nothing and never persists when no line
     * carries the requested uid (the loop falls through).
     */
    @Test
    void markLineCancelledNoOpWhenUidNotFound() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        TicketLine b = line("U2");
        ticket.lines.add(b);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.markLineCancelled(5L, "U1", "12341234", null, null);
            assertFalse(b.cancelled);
            verify(ticket, never()).persist();
        }
    }

    /**
     * {@code markLineCancelled} skips a line that is ALREADY cancelled (the
     * {@code !line.cancelled} guard), preserving its original author and never
     * re-persisting.
     */
    @Test
    void markLineCancelledSkipsAnAlreadyCancelledLine() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        TicketLine a = line("U1");
        a.cancelled = true;
        a.cancelledBy = "OLD";
        ticket.lines.add(a);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.markLineCancelled(5L, "U1", "12341234", null, null);
            assertEquals("OLD", a.cancelledBy);
            verify(ticket, never()).persist();
        }
    }

    /**
     * {@code markLineCancelled} returns before any database access on either leg
     * of the null guard: a null ticket id (first leg) and a null line uid
     * (second leg) both short-circuit, so no Panache finder is ever reached.
     */
    @Test
    void markLineCancelledNoOpWhenIdsNull() {
        TicketPersistenceService service = newService();
        service.markLineCancelled(null, "U1", "12341234", null, null);
        service.markLineCancelled(5L, null, "12341234", null, null);
    }

    /**
     * The reconciliation keeps a line marked cancelled even though it left the
     * in-memory cart (lot C4, BO-04-01-16), while a plain vanished line is
     * still orphan-removed: the cancelled article is conserved, the totals
     * count the live cart only.
     */
    @Test
    void syncDraftKeepsACancelledLineAndStillOrphanRemovesAVanishedOne() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        addItem(state, "U2", "4000", null, "1.00", "1", null);
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        TicketLine cancelled = line("U1");
        cancelled.cancelled = true;
        ticket.lines = new ArrayList<>(Arrays.asList(cancelled, line("U2"), line("GONE")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            state.payment.ticketDbId = 5L;
            service.syncDraft(state);
            assertEquals(2, ticket.lines.size());
            assertTrue(ticket.lines.stream().anyMatch(l -> "U1".equals(l.lineUid) && l.cancelled));
            assertTrue(ticket.lines.stream().anyMatch(l -> "U2".equals(l.lineUid)));
            assertFalse(ticket.lines.stream().anyMatch(l -> "GONE".equals(l.lineUid)));
            assertEquals(1, ticket.itemCount);
        }
    }

    /**
     * The conserved witnesses are numbered AFTER the sold lines, so no two
     * lines of one ticket ever share a number: the live lines are renumbered
     * from the cart at every synchronization, and a witness that kept its
     * original number would collide with whichever line took its place.
     */
    @Test
    void syncDraftNumbersCancelledWitnessesAfterTheSoldLines() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        addItem(state, "U2", "4000", null, "1.00", "1", null);
        addItem(state, "U3", "5000", null, "2.00", "1", null);
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        TicketLine cancelled = line("U1");
        cancelled.cancelled = true;
        cancelled.lineNumber = 1;
        TicketLine second = line("U2");
        second.lineNumber = 2;
        TicketLine third = line("U3");
        third.lineNumber = 3;
        ticket.lines = new ArrayList<>(Arrays.asList(cancelled, second, third));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            state.payment.ticketDbId = 5L;
            service.syncDraft(state);
            assertEquals(1, second.lineNumber);
            assertEquals(2, third.lineNumber);
            assertEquals(3, cancelled.lineNumber);
        }
    }

    /**
     * Totals invariance (campaign rule): a cancelled line kept on the draft
     * moves no centime. The same live cart yields byte-identical HT, TTC and
     * VAT totals whether or not a cancelled ghost line sits in the persisted
     * collection — the totals are recomputed from the cart, never from the
     * conserved witness.
     */
    @Test
    void aCancelledLineMovesNoCentimeOfTheTotals() {
        BigDecimal[] withGhost = totalsWithOptionalCancelledGhost(true);
        BigDecimal[] without = totalsWithOptionalCancelledGhost(false);
        assertEquals(without[0], withGhost[0]);
        assertEquals(without[1], withGhost[1]);
        assertEquals(without[2], withGhost[2]);
    }

    /**
     * Reconciles a one-item live cart (2 units at 2,50 €, 20% VAT) against a
     * draft that optionally already holds a cancelled ghost line of a different
     * price and rate, and returns the persisted [HT, TTC, VAT] totals. The two
     * runs differ solely by the presence of the conserved witness.
     *
     * @param withGhost whether a cancelled ghost line sits in the collection
     * @return the three ticket totals of the reconciled draft
     */
    private BigDecimal[] totalsWithOptionalCancelledGhost(boolean withGhost) {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        addItem(state, "U2", "4000", null, "2.50", "2", null);
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        List<TicketLine> lines = new ArrayList<>();
        if (withGhost) {
            TicketLine ghost = line("U1");
            ghost.cancelled = true;
            ghost.totalPrice = new BigDecimal("9.99");
            ghost.vatRate = new BigDecimal("0.0550");
            lines.add(ghost);
        }
        lines.add(line("U2"));
        ticket.lines = lines;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            state.payment.ticketDbId = 5L;
            service.syncDraft(state);
            return new BigDecimal[]{ticket.totalExcludingTax, ticket.totalIncludingTax, ticket.totalVat};
        }
    }

    // --------------------------------------------------
    // addPaymentToTicket
    // --------------------------------------------------

    /**
     * Builds the service with two payment factories (CARD and VOUCHER) indexed
     * by {@code init}, ready for the payment tests.
     *
     * @param cardFactory the plain-method factory
     * @param voucherFactory the voucher factory
     * @return the service with the factories indexed
     */
    @SuppressWarnings("unchecked")
    private TicketPersistenceService serviceWithFactories(
            TicketPayment.Factory cardFactory, TicketPayment.Factory voucherFactory) {
        TicketPersistenceService service = newService();
        when(cardFactory.getKey()).thenReturn("CARD");
        when(voucherFactory.getKey()).thenReturn("VOUCHER");
        Instance<TicketPayment.Factory> instance = mock(Instance.class);
        when(instance.iterator()).thenReturn(Arrays.asList(cardFactory, voucherFactory).iterator());
        service.factoryInstances = instance;
        service.init();
        return service;
    }

    /**
     * Covers the missing-ticket guard of {@code addPaymentToTicket}: the finder
     * resolves to null, so the method throws.
     */
    @Test
    void addPaymentThrowsWhenTicketMissing() {
        TicketPersistenceService service = newService();
        PaymentState.PaymentEntry entry = new PaymentState.PaymentEntry("CARD", new BigDecimal("10.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(null);
            assertThrows(IllegalArgumentException.class, () -> service.addPaymentToTicket(5L, entry));
        }
    }

    /**
     * Covers the plain-method arm of {@code addPaymentToTicket}: a non-voucher
     * entry resolves its factory by method key, the created payment is indexed
     * and added, and it is not a {@link VoucherPayment}.
     */
    @Test
    void addPaymentAddsPlainPayment() {
        TicketPayment.Factory cardFactory = mock(TicketPayment.Factory.class);
        TicketPayment.Factory voucherFactory = mock(TicketPayment.Factory.class);
        TicketPersistenceService service = serviceWithFactories(cardFactory, voucherFactory);
        TicketPayment payment = mock(TicketPayment.class);
        when(cardFactory.create(any(BigDecimal.class), any())).thenReturn(payment);
        PaymentState.PaymentEntry entry = new PaymentState.PaymentEntry("CARD", new BigDecimal("10.00"));
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.addPaymentToTicket(5L, entry);
            assertEquals(1, payment.paymentIndex);
            verify(ticket).addPayment(payment);
            verify(ticket, times(1)).persist();
        }
    }

    /**
     * Covers the card arm of {@code addPaymentToTicket}: a card entry resolves
     * the CARD factory and the created {@link com.intermarche.pos.domain.payment.CardPayment}
     * is enriched with the entry's monetique traces (authorization number and
     * degraded-mode indicator) before being added (BO-04-01-08/47/49).
     */
    @Test
    void addPaymentEnrichesCardPaymentTraces() {
        TicketPayment.Factory cardFactory = mock(TicketPayment.Factory.class);
        TicketPayment.Factory voucherFactory = mock(TicketPayment.Factory.class);
        TicketPersistenceService service = serviceWithFactories(cardFactory, voucherFactory);
        com.intermarche.pos.domain.payment.CardPayment payment =
                mock(com.intermarche.pos.domain.payment.CardPayment.class);
        when(cardFactory.create(any(BigDecimal.class), any())).thenReturn(payment);
        PaymentState.PaymentEntry entry =
                new PaymentState.PaymentEntry("CARD", new BigDecimal("10.00"), true, "654321");
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.addPaymentToTicket(5L, entry);
            assertEquals("654321", payment.authorizationNumber);
            assertTrue(payment.degradedMode);
            assertEquals(1, payment.paymentIndex);
            verify(ticket).addPayment(payment);
            verify(ticket, times(1)).persist();
        }
    }

    /**
     * Covers the voucher arm of {@code addPaymentToTicket}: a voucher entry
     * resolves the VOUCHER factory, and the created {@link VoucherPayment} is
     * enriched with the entry label and number before being added.
     */
    @Test
    void addPaymentAddsVoucherPayment() {
        TicketPayment.Factory cardFactory = mock(TicketPayment.Factory.class);
        TicketPayment.Factory voucherFactory = mock(TicketPayment.Factory.class);
        TicketPersistenceService service = serviceWithFactories(cardFactory, voucherFactory);
        VoucherPayment payment = mock(VoucherPayment.class);
        when(voucherFactory.create(any(BigDecimal.class), any())).thenReturn(payment);
        PaymentState.PaymentEntry entry = new PaymentState.PaymentEntry(
                "Chèque cadeau", new BigDecimal("5.00"), "V123", true);
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.addPaymentToTicket(5L, entry);
            assertEquals("Chèque cadeau", payment.voucherLabel);
            assertEquals("V123", payment.voucherNumber);
            assertEquals(1, payment.paymentIndex);
            verify(ticket).addPayment(payment);
        }
    }

    /**
     * Covers the unknown-method guard of {@code addPaymentToTicket}: no factory
     * matches the entry method, so the method throws.
     */
    @Test
    void addPaymentThrowsWhenMethodUnknown() {
        TicketPayment.Factory cardFactory = mock(TicketPayment.Factory.class);
        TicketPayment.Factory voucherFactory = mock(TicketPayment.Factory.class);
        TicketPersistenceService service = serviceWithFactories(cardFactory, voucherFactory);
        PaymentState.PaymentEntry entry = new PaymentState.PaymentEntry("BITCOIN", new BigDecimal("10.00"));
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            assertThrows(IllegalArgumentException.class, () -> service.addPaymentToTicket(5L, entry));
        }
    }

    /**
     * Builds the service with a SINGLE payment factory indexed under the given
     * key by {@code init}, producing the supplied payment for every create call.
     *
     * @param key the method key the factory answers to
     * @param payment the payment the factory produces
     * @return the service with the one factory indexed
     */
    @SuppressWarnings("unchecked")
    private TicketPersistenceService serviceWithSingleFactory(String key, TicketPayment payment) {
        TicketPersistenceService service = newService();
        TicketPayment.Factory factory = mock(TicketPayment.Factory.class);
        when(factory.getKey()).thenReturn(key);
        when(factory.create(any(BigDecimal.class), any())).thenReturn(payment);
        Instance<TicketPayment.Factory> instance = mock(Instance.class);
        when(instance.iterator()).thenReturn(java.util.List.of(factory).iterator());
        service.factoryInstances = instance;
        service.init();
        return service;
    }

    /**
     * Covers the cheque arm of {@code addPaymentToTicket} (L319, true leg): a
     * cheque entry resolves the CHEQUE factory and the created
     * {@link com.intermarche.pos.domain.payment.ChequePayment} is enriched with
     * the entry's magnetic line before being added.
     */
    @Test
    void addPaymentEnrichesChequePaymentMagneticLine() {
        com.intermarche.pos.domain.payment.ChequePayment payment =
                mock(com.intermarche.pos.domain.payment.ChequePayment.class);
        TicketPersistenceService service = serviceWithSingleFactory("CHEQUE", payment);
        PaymentState.PaymentEntry entry = new PaymentState.PaymentEntry("CHEQUE", new BigDecimal("30.00"));
        entry.magneticLine = "CMC7-0123456789";
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.addPaymentToTicket(5L, entry);
            assertEquals("CMC7-0123456789", payment.magneticLine);
            assertEquals(1, payment.paymentIndex);
            verify(ticket).addPayment(payment);
            verify(ticket, times(1)).persist();
        }
    }

    /**
     * Covers the backup arm of {@code addPaymentToTicket} (L322, true leg): a
     * backup entry resolves the SECOURS factory and the created
     * {@link com.intermarche.pos.domain.payment.BackupPayment} is enriched with
     * the entry's method label, transaction number and manual indicator before
     * being added.
     */
    @Test
    void addPaymentEnrichesBackupPaymentTraces() {
        com.intermarche.pos.domain.payment.BackupPayment payment =
                mock(com.intermarche.pos.domain.payment.BackupPayment.class);
        TicketPersistenceService service = serviceWithSingleFactory("SECOURS", payment);
        PaymentState.PaymentEntry entry = new PaymentState.PaymentEntry("SECOURS", new BigDecimal("40.00"));
        entry.backupMethodLabel = "CB (secours)";
        entry.backupTransaction = "TX-778899";
        entry.backupManual = true;
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.addPaymentToTicket(5L, entry);
            assertEquals("CB (secours)", payment.methodLabel);
            assertEquals("TX-778899", payment.transactionNumber);
            assertTrue(payment.manual);
            assertEquals(1, payment.paymentIndex);
            verify(ticket).addPayment(payment);
            verify(ticket, times(1)).persist();
        }
    }

    /**
     * Covers the foreign-currency arm of {@code addPaymentToTicket} (L327, true
     * leg): a currency entry resolves the DEVISE factory and the created
     * {@link com.intermarche.pos.domain.payment.ForeignCurrencyPayment} is
     * enriched with the entry's currency code, foreign amount and exchange rate
     * before being added.
     */
    @Test
    void addPaymentEnrichesForeignCurrencyPaymentTraces() {
        com.intermarche.pos.domain.payment.ForeignCurrencyPayment payment =
                mock(com.intermarche.pos.domain.payment.ForeignCurrencyPayment.class);
        TicketPersistenceService service = serviceWithSingleFactory("DEVISE", payment);
        PaymentState.PaymentEntry entry = new PaymentState.PaymentEntry("DEVISE", new BigDecimal("10.00"));
        entry.currencyCode = "USD";
        entry.currencyAmount = new BigDecimal("11.50");
        entry.currencyRate = new BigDecimal("1.15");
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.addPaymentToTicket(5L, entry);
            assertEquals("USD", payment.currencyCode);
            assertEquals(0, new BigDecimal("11.50").compareTo(payment.foreignAmount));
            assertEquals(0, new BigDecimal("1.15").compareTo(payment.exchangeRate));
            assertEquals(1, payment.paymentIndex);
            verify(ticket).addPayment(payment);
            verify(ticket, times(1)).persist();
        }
    }

    /**
     * Covers the credit arm of {@code addPaymentToTicket} (L332, true leg): a
     * credit entry resolves the CREDIT factory and the created
     * {@link com.intermarche.pos.domain.payment.CreditPayment} is enriched with
     * the entry's account number, account name and over-limit indicator before
     * being added.
     */
    @Test
    void addPaymentEnrichesCreditPaymentTraces() {
        com.intermarche.pos.domain.payment.CreditPayment payment =
                mock(com.intermarche.pos.domain.payment.CreditPayment.class);
        TicketPersistenceService service = serviceWithSingleFactory("CREDIT", payment);
        PaymentState.PaymentEntry entry = new PaymentState.PaymentEntry("CREDIT", new BigDecimal("15.00"));
        entry.creditAccountNumber = "CPT-4242";
        entry.creditAccountName = "Famille Martin";
        entry.creditOverLimit = true;
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.addPaymentToTicket(5L, entry);
            assertEquals("CPT-4242", payment.accountNumber);
            assertEquals("Famille Martin", payment.accountName);
            assertTrue(payment.overLimit);
            assertEquals(1, payment.paymentIndex);
            verify(ticket).addPayment(payment);
            verify(ticket, times(1)).persist();
        }
    }

    // --------------------------------------------------
    // storeFormattedContent
    // --------------------------------------------------

    /**
     * Covers the null-id leg of the guard of {@code storeFormattedContent}
     * (L409, first condition true): a null ticket id short-circuits before any
     * Panache access, so nothing is looked up.
     */
    @Test
    void storeFormattedContentIgnoresNullTicketId() {
        TicketPersistenceService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            service.storeFormattedContent(null, "Ticket printed");
            mocked.verify(() -> Ticket.findById(any()), never());
        }
    }

    /**
     * Covers the null-content leg of the guard of {@code storeFormattedContent}
     * (L409, first condition false, second true): a null content short-circuits
     * before any Panache access.
     */
    @Test
    void storeFormattedContentIgnoresNullContent() {
        TicketPersistenceService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            service.storeFormattedContent(5L, null);
            mocked.verify(() -> Ticket.findById(any()), never());
        }
    }

    /**
     * Covers the blank-content leg of the guard of {@code storeFormattedContent}
     * (L409, first two conditions false, third true): a blank content
     * short-circuits before any Panache access.
     */
    @Test
    void storeFormattedContentIgnoresBlankContent() {
        TicketPersistenceService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            service.storeFormattedContent(5L, "   ");
            mocked.verify(() -> Ticket.findById(any()), never());
        }
    }

    /**
     * Covers the missing-ticket arm of {@code storeFormattedContent} (L409 all
     * false, so the guard is passed, then L413 true): a well-formed request
     * whose ticket vanished stores nothing.
     */
    @Test
    void storeFormattedContentIgnoresMissingTicket() {
        TicketPersistenceService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(null);
            service.storeFormattedContent(5L, "Ticket printed");
        }
    }

    /**
     * Covers the nominal arm of {@code storeFormattedContent} (L409 all false,
     * L413 false): a well-formed request on an existing ticket freezes its
     * printed form and persists it.
     */
    @Test
    void storeFormattedContentStoresAndPersists() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.CLOSED);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.storeFormattedContent(5L, "Ticket printed");
            assertEquals("Ticket printed", ticket.formattedContent);
            verify(ticket, times(1)).persist();
        }
    }

    // --------------------------------------------------
    // storeFidelity (BO-03-03-25 / -29 / -30 / -31)
    // --------------------------------------------------

    /**
     * A null ticket id short-circuits before any Panache access — first leg of
     * that guard.
     */
    @Test
    void storeFidelityIgnoresAnullTicketId() {
        TicketPersistenceService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            service.storeFidelity(null, BigDecimal.ONE, BigDecimal.TEN, false, List.of(), null, null, false);
            mocked.verify(() -> Ticket.findById(any()), never());
        }
    }

    /**
     * A ticket that vanished between the closing and this write freezes nothing
     * — second leg.
     */
    @Test
    void storeFidelityIgnoresAmissingTicket() {
        TicketPersistenceService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(null);
            service.storeFidelity(5L, BigDecimal.ONE, BigDecimal.TEN, false, List.of(), null, null, false);
        }
    }

    /**
     * The nominal arm: the displayed earn, the balance read at attachment, the
     * availability flag and the advantage lines are all written on the row, and
     * the row is persisted.
     */
    @Test
    void storeFidelityFreezesTheZoneAndPersists() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.CLOSED);
        ticket.fidelityLines = new java.util.ArrayList<>();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.storeFidelity(5L, new BigDecimal("1.03"), new BigDecimal("42.30"), true,
                    List.of(new TicketFidelityLine("SOCLE", "Cagnotte socle",
                            new BigDecimal("0.28"))), new BigDecimal("46.20"),
                    new BigDecimal("30.30"), true);
            assertEquals(new BigDecimal("1.03"), ticket.fidelityEarnTotal);
            assertEquals(new BigDecimal("42.30"), ticket.fidelityAvailableBalance);
            assertEquals(new BigDecimal("46.20"), ticket.fidelityProjectedAfterEarn);
            assertEquals(new BigDecimal("30.30"), ticket.fidelityBalanceAfter);
            assertTrue(ticket.fidelityAnonymousProjection);
            assertTrue(ticket.fidelityUnavailable);
            assertEquals(1, ticket.fidelityLines.size());
            assertEquals("Cagnotte socle", ticket.fidelityLines.get(0).label);
            verify(ticket, times(1)).persist();
        }
    }

    /**
     * A row whose collection was never initialised — a freshly constructed
     * entity, or one ingested by a node that predates the column — gets one
     * rather than failing: the null arm of that guard.
     */
    @Test
    void storeFidelityInitialisesAnabsentCollection() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.CLOSED);
        ticket.fidelityLines = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.storeFidelity(5L, null, null, false,
                    List.of(new TicketFidelityLine("SOCLE", "Cagnotte socle",
                            new BigDecimal("0.28"))), null, null, false);
            assertEquals(1, ticket.fidelityLines.size());
        }
    }

    /**
     * A second write REPLACES the first: a sale re-frozen must not accumulate
     * the advantages of the sale before it.
     */
    @Test
    void storeFidelityReplacesWhatWasThereBefore() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.CLOSED);
        ticket.fidelityLines = new java.util.ArrayList<>();
        ticket.fidelityLines.add(new TicketFidelityLine("VIEUX", "Ancienne", BigDecimal.ONE));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.storeFidelity(5L, null, null, false, List.of(), null, null, false);
            assertTrue(ticket.fidelityLines.isEmpty());
        }
    }

    // --------------------------------------------------
    // storeEngineMessages (GB-01-07-05/06)
    // --------------------------------------------------

    /**
     * {@code storeEngineMessages} ignores a null ticket id: the finder is never
     * called (the null-id arm).
     */
    @Test
    void storeEngineMessagesIgnoresANullTicketId() {
        TicketPersistenceService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            service.storeEngineMessages(null, List.of(
                    new com.intermarche.pos.domain.sale.TicketMessage("C", "T", "FOOTER")));
            mocked.verify(() -> Ticket.findById(any()), never());
        }
    }

    /**
     * {@code storeEngineMessages} stores nothing for an empty list: a
     * LOCAL/DEGRADED sale, or an engine that emitted none, leaves the ticket
     * untouched (the empty-list arm).
     */
    @Test
    void storeEngineMessagesStoresNothingForAnEmptyList() {
        TicketPersistenceService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            service.storeEngineMessages(5L, List.of());
            mocked.verify(() -> Ticket.findById(any()), never());
        }
    }

    /**
     * {@code storeEngineMessages} stores nothing for a null list (the
     * null-messages arm).
     */
    @Test
    void storeEngineMessagesStoresNothingForANullList() {
        TicketPersistenceService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            service.storeEngineMessages(5L, null);
            mocked.verify(() -> Ticket.findById(any()), never());
        }
    }

    /**
     * {@code storeEngineMessages} ignores a missing ticket (the null-ticket arm).
     */
    @Test
    void storeEngineMessagesIgnoresAmissingTicket() {
        TicketPersistenceService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(null);
            service.storeEngineMessages(5L, List.of(
                    new com.intermarche.pos.domain.sale.TicketMessage("C", "T", "FOOTER")));
        }
    }

    /**
     * {@code storeEngineMessages} freezes the messages on the ticket and
     * persists (the happy path).
     */
    @Test
    void storeEngineMessagesFreezesTheMessagesAndPersists() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.CLOSED);
        ticket.messages = new java.util.ArrayList<>();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.storeEngineMessages(5L, List.of(
                    new com.intermarche.pos.domain.sale.TicketMessage(
                            "BELOW_TRIGGER", "Plus que 5 €", "FOOTER")));
            assertEquals(1, ticket.messages.size());
            assertEquals("Plus que 5 €", ticket.messages.get(0).text);
            verify(ticket, times(1)).persist();
        }
    }

    /**
     * {@code storeEngineMessages} initialises an absent collection before
     * adding (the null-collection arm).
     */
    @Test
    void storeEngineMessagesInitialisesAnAbsentCollection() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.CLOSED);
        ticket.messages = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.storeEngineMessages(5L, List.of(
                    new com.intermarche.pos.domain.sale.TicketMessage("C", "T", null)));
            assertEquals(1, ticket.messages.size());
        }
    }

    // --------------------------------------------------
    // removePaymentsFromTicket
    // --------------------------------------------------

    /**
     * Covers the missing-ticket arm of {@code removePaymentsFromTicket}: the
     * finder resolves to null, so the method returns without journaling.
     */
    @Test
    void removePaymentsReturnsWhenTicketMissing() {
        TicketPersistenceService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(null);
            service.removePaymentsFromTicket(5L);
            verifyNoInteractions(service.technicalEventService);
        }
    }

    /**
     * Covers the payments-present arm of {@code removePaymentsFromTicket}: the
     * payments are cleared, the draft persisted and the clearing journaled with
     * the removed count.
     */
    @Test
    void removePaymentsClearsAndLogsWhenPresent() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.payments = new ArrayList<>(Arrays.asList(
                mock(TicketPayment.class), mock(TicketPayment.class)));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.removePaymentsFromTicket(5L);
            assertTrue(ticket.payments.isEmpty());
            verify(ticket, times(1)).persist();
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.PAYMENTS_CLEARED, "C04-00000001 (2)");
        }
    }

    /**
     * Covers the no-payments arm of {@code removePaymentsFromTicket}: nothing
     * was registered, so the draft is persisted but no clearing is journaled.
     */
    @Test
    void removePaymentsDoesNotLogWhenEmpty() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.removePaymentsFromTicket(5L);
            verify(ticket, times(1)).persist();
            verifyNoInteractions(service.technicalEventService);
        }
    }

    // --------------------------------------------------
    // validateTicket
    // --------------------------------------------------

    /**
     * Covers the missing-ticket guard of {@code validateTicket}: the finder
     * resolves to null, so the method returns without locking the counter.
     */
    @Test
    void validateTicketReturnsWhenTicketMissing() {
        TicketPersistenceService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(null);
            service.validateTicket(5L);
            verify(service.ticketNumberService, never()).lockCounter(any());
            verifyNoInteractions(service.syncOutboxService);
        }
    }

    /**
     * Covers the chained arm of {@code validateTicket}: the counter carries a
     * previous signature, so the ticket chains to it, the grand total advances
     * and the closure is journaled and enqueued.
     */
    @Test
    void validateTicketChainsToPreviousSignature() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        TicketCounter counter = new TicketCounter();
        counter.lastSignature = "PREVSIG";
        counter.grandTotal = new BigDecimal("100.00");
        when(service.ticketNumberService.lockCounter(TERMINAL)).thenReturn(counter);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.validateTicket(5L);
            assertEquals(Ticket.TicketStatus.CLOSED, ticket.status);
            assertEquals("PREVSIG", ticket.previousSignature);
            assertEquals(64, ticket.signature.length());
            assertEquals(0, new BigDecimal("112.00").compareTo(counter.grandTotal));
            assertEquals(0, counter.grandTotal.compareTo(ticket.grandTotal));
            assertEquals(ticket.signature, counter.lastSignature);
            verify(ticket, times(1)).persist();
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.TICKET_CLOSED, "C04-00000001");
            verify(service.syncOutboxService).enqueue(SyncOutbox.EntityType.TICKET, 5L);
        }
    }

    /**
     * Covers the genesis arm of {@code validateTicket}: the counter has no
     * previous signature, so the ticket chains to the "GENESIS" anchor.
     */
    @Test
    void validateTicketUsesGenesisWhenNoPreviousSignature() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        TicketCounter counter = new TicketCounter();
        counter.lastSignature = null;
        when(service.ticketNumberService.lockCounter(TERMINAL)).thenReturn(counter);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.validateTicket(5L);
            assertEquals("GENESIS", ticket.previousSignature);
            assertEquals(0, new BigDecimal("12.00").compareTo(counter.grandTotal));
        }
    }

    // --------------------------------------------------
    // validateTicket — stored-value registry
    // --------------------------------------------------

    /**
     * Stubs the registry lookup at the level Panache actually intercepts:
     * {@code findByNumber} is declared on StoredValue, so it is NOT
     * intercepted by the PanacheEntityBase static mock — its body runs and
     * calls {@code find("number", …)}, which IS. Stubbing that query is what
     * makes the lookup return the wanted instrument.
     *
     * @param mocked the open Panache static mock
     * @param number the instrument number
     * @param instrument the instrument to return, possibly null
     */
    private void stubRegistryLookup(MockedStatic<PanacheEntityBase> mocked, String number,
                                    StoredValue instrument) {
        @SuppressWarnings("unchecked")
        PanacheQuery<StoredValue> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(instrument);
        mocked.when(() -> StoredValue.find("number", number)).thenReturn(query);
    }

    /**
     * Builds a registry instrument.
     *
     * @param number the instrument number
     * @param balance the current balance
     * @return the instrument
     */
    private StoredValue instrument(String number, String balance) {
        StoredValue instrument = new StoredValue();
        instrument.number = number;
        instrument.balance = new BigDecimal(balance);
        instrument.status = StoredValue.Status.ACTIVE;
        return instrument;
    }

    /**
     * Prepares a counter so that {@code validateTicket} runs to completion.
     *
     * @param service the service under test
     */
    private void stubCounter(TicketPersistenceService service) {
        TicketCounter counter = new TicketCounter();
        counter.lastSignature = null;
        when(service.ticketNumberService.lockCounter(TERMINAL)).thenReturn(counter);
    }

    /**
     * THE FISCAL DEBIT: a registry voucher used on the closed ticket has its
     * balance lowered by the amount paid, and the ticket is stamped on the
     * instrument. The debit happens HERE and nowhere else — a payment that
     * never reaches the fiscal moment never spends the instrument.
     */
    @Test
    void validateTicketDebitsTheRegistryInstrument() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        ticket.payments.add(new VoucherPayment(
                new BigDecimal("4.00"), "Avoir", "297000000000001"));
        StoredValue note = instrument("297000000000001", "10.00");
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            stubRegistryLookup(mocked, "297000000000001", note);
            service.validateTicket(5L);
        }
        assertEquals(0, new BigDecimal("6.00").compareTo(note.balance));
        assertEquals(5L, note.lastRedeemedTicketId);
        assertEquals(StoredValue.Status.ACTIVE, note.status);
        assertNull(note.exhaustedAt);
    }

    /**
     * A FULLY SPENT instrument is closed: the balance reaches zero, the
     * status flips to EXHAUSTED and the moment is stamped — this is what
     * makes a second scan of the same paper refuse later.
     */
    @Test
    void validateTicketExhaustsAFullySpentInstrument() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        ticket.payments.add(new VoucherPayment(
                new BigDecimal("10.00"), "Avoir", "297000000000001"));
        StoredValue note = instrument("297000000000001", "10.00");
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            stubRegistryLookup(mocked, "297000000000001", note);
            service.validateTicket(5L);
        }
        assertEquals(0, BigDecimal.ZERO.compareTo(note.balance));
        assertEquals(StoredValue.Status.EXHAUSTED, note.status);
        assertNotNull(note.exhaustedAt);
    }

    /**
     * The balance is FLOORED at zero: even if the paid amount somehow exceeds
     * the balance, the instrument never goes negative — a negative stored
     * value would be money created out of a rounding accident.
     */
    @Test
    void validateTicketFloorsTheBalanceAtZero() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        ticket.payments.add(new VoucherPayment(
                new BigDecimal("25.00"), "Carte cadeau", "296000000000001"));
        StoredValue card = instrument("296000000000001", "10.00");
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            stubRegistryLookup(mocked, "296000000000001", card);
            service.validateTicket(5L);
        }
        assertEquals(0, BigDecimal.ZERO.compareTo(card.balance));
        assertEquals(StoredValue.Status.EXHAUSTED, card.status);
    }

    /**
     * An instrument that VANISHED from the registry is skipped rather than
     * crashing the fiscal close: the sale is already legally complete, and a
     * missing row must never hold a closing hostage.
     */
    @Test
    void validateTicketSkipsAVanishedInstrument() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        ticket.payments.add(new VoucherPayment(
                new BigDecimal("4.00"), "Avoir", "297000000000001"));
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            stubRegistryLookup(mocked, "297000000000001", null);
            service.validateTicket(5L);
            assertEquals(Ticket.TicketStatus.CLOSED, ticket.status);
        }
    }

    /**
     * A NON-REGISTRY voucher (an old encoded coupon) is left alone: its value
     * lives on the paper, not in the registry, and there is nothing to debit.
     */
    @Test
    void validateTicketIgnoresANonRegistryVoucher() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        ticket.payments.add(new VoucherPayment(
                new BigDecimal("4.00"), "Chèque cadeau", "500123"));
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.validateTicket(5L);
            mocked.verify(() -> StoredValue.find(eq("number"), any(Object[].class)), never());
        }
    }

    /**
     * A NON-VOUCHER payment never reaches the registry lookup (the
     * {@code instanceof} leg): cash spends no instrument.
     */
    @Test
    void validateTicketIgnoresNonVoucherPayments() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        ticket.payments.add(new CashPayment(new BigDecimal("12.00"), new BigDecimal("20.00")));
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.validateTicket(5L);
            mocked.verify(() -> StoredValue.find(eq("number"), any(Object[].class)), never());
        }
    }

    // --------------------------------------------------
    // validateTicket — gift-card issuance
    // --------------------------------------------------

    /**
     * Builds a gift-card product.
     *
     * @param faceValue the loaded value, or null for an ordinary product
     * @return the product
     */
    private Product giftCardProduct(String faceValue) {
        Product product = new Product();
        product.giftCardAmount = faceValue == null ? null : new BigDecimal(faceValue);
        return product;
    }

    /**
     * Stubs the catalog lookup of a sold line.
     *
     * @param mocked the open Panache static mock
     * @param ean the scanned EAN
     * @param product the product to return, possibly null
     */
    private void stubProduct(MockedStatic<PanacheEntityBase> mocked, String ean, Product product) {
        @SuppressWarnings("unchecked")
        PanacheQuery<Product> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(product);
        mocked.when(() -> Product.find("ean", ean)).thenReturn(query);
    }

    /**
     * Selling a gift card ISSUES the instrument at the fiscal moment: kind,
     * face value and balance are loaded, the issuing ticket is stamped, and
     * the number is derived from the generated id AFTER persisting — the
     * identity comes from the database, never from the register.
     */
    @Test
    void validateTicketIssuesTheSoldGiftCard() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("25.00");
        ticket.totalVat = BigDecimal.ZERO;
        TicketLine sold = new TicketLine();
        sold.ean = "3400025000001";
        sold.quantity = BigDecimal.ONE;
        ticket.lines.add(sold);
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<StoredValue> issued = mockConstruction(StoredValue.class,
                     (card, ctx) -> card.id = 7L)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            stubProduct(mocked, "3400025000001", giftCardProduct("25.00"));
            service.validateTicket(5L);
            assertEquals(1, issued.constructed().size());
            StoredValue card = issued.constructed().get(0);
            assertEquals(StoredValue.Kind.GIFT_CARD, card.kind);
            assertEquals(0, new BigDecimal("25.00").compareTo(card.initialAmount));
            assertEquals(0, new BigDecimal("25.00").compareTo(card.balance));
            assertEquals(5L, card.issuingTicketId);
            assertNotNull(card.issuedAt);
            assertEquals("296000000000007", card.number);
            verify(card).persistAndFlush();
        }
    }

    /**
     * A line carrying SEVERAL cards issues one instrument PER UNIT: two cards
     * bought together are two distinct instruments with two numbers, never a
     * single one loaded twice.
     */
    @Test
    void validateTicketIssuesOneInstrumentPerUnit() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("50.00");
        ticket.totalVat = BigDecimal.ZERO;
        TicketLine sold = new TicketLine();
        sold.ean = "3400025000001";
        sold.quantity = new BigDecimal("2");
        ticket.lines.add(sold);
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<StoredValue> issued = mockConstruction(StoredValue.class,
                     (card, ctx) -> card.id = 7L)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            stubProduct(mocked, "3400025000001", giftCardProduct("25.00"));
            service.validateTicket(5L);
            assertEquals(2, issued.constructed().size());
        }
    }

    /**
     * A line WITHOUT an EAN issues nothing (first guard): a weighed PLU line
     * can never be an instrument.
     */
    @Test
    void validateTicketIssuesNothingForALineWithoutEan() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        TicketLine sold = new TicketLine();
        sold.ean = null;
        sold.quantity = BigDecimal.ONE;
        ticket.lines.add(sold);
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<StoredValue> issued = mockConstruction(StoredValue.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.validateTicket(5L);
            assertTrue(issued.constructed().isEmpty());
        }
    }

    /**
     * An EAN UNKNOWN to the catalog issues nothing (second guard, first leg).
     */
    @Test
    void validateTicketIssuesNothingForAnUnknownProduct() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        TicketLine sold = new TicketLine();
        sold.ean = "123";
        sold.quantity = BigDecimal.ONE;
        ticket.lines.add(sold);
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<StoredValue> issued = mockConstruction(StoredValue.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            stubProduct(mocked, "123", null);
            service.validateTicket(5L);
            assertTrue(issued.constructed().isEmpty());
        }
    }

    /**
     * An ORDINARY product issues nothing (second guard, second leg): only a
     * loaded face value makes an instrument.
     */
    @Test
    void validateTicketIssuesNothingForAnOrdinaryProduct() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        TicketLine sold = new TicketLine();
        sold.ean = "123";
        sold.quantity = BigDecimal.ONE;
        ticket.lines.add(sold);
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<StoredValue> issued = mockConstruction(StoredValue.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            stubProduct(mocked, "123", giftCardProduct(null));
            service.validateTicket(5L);
            assertTrue(issued.constructed().isEmpty());
        }
    }

    /**
     * A ZERO-quantity line issues nothing: the per-unit loop never runs.
     */
    @Test
    void validateTicketIssuesNothingForAZeroQuantityLine() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        TicketLine sold = new TicketLine();
        sold.ean = "3400025000001";
        sold.quantity = BigDecimal.ZERO;
        ticket.lines.add(sold);
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<StoredValue> issued = mockConstruction(StoredValue.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            stubProduct(mocked, "3400025000001", giftCardProduct("25.00"));
            service.validateTicket(5L);
            assertTrue(issued.constructed().isEmpty());
        }
    }

    /**
     * A CANCELLED gift-card line issues nothing at the fiscal close (lot C4):
     * the {@code line.cancelled} guard skips it before the EAN and product
     * lookups, exactly as it contributes nothing to the totals — a rung-then-
     * cancelled card never mints value.
     */
    @Test
    void validateTicketIssuesNothingForACancelledGiftCardLine() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("25.00");
        ticket.totalVat = BigDecimal.ZERO;
        TicketLine sold = new TicketLine();
        sold.ean = "3400025000001";
        sold.quantity = BigDecimal.ONE;
        sold.cancelled = true;
        ticket.lines.add(sold);
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<StoredValue> issued = mockConstruction(StoredValue.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.validateTicket(5L);
            assertTrue(issued.constructed().isEmpty());
        }
    }

    /**
     * The unreachable arm of the signature: if the JVM ever failed to supply
     * SHA-256 — which its own specification forbids — the fiscal close would
     * fail LOUDLY rather than sign with something weaker. Reaching it takes a
     * static mock of the JCA itself, which is the point: the catch exists so
     * that an impossible platform cannot silently degrade a fiscal signature.
     */
    @Test
    void validateTicketFailsLoudlyWhenSha256IsUnavailable() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedStatic<java.security.MessageDigest> jca =
                     mockStatic(java.security.MessageDigest.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            jca.when(() -> java.security.MessageDigest.getInstance("SHA-256"))
                    .thenThrow(new java.security.NoSuchAlgorithmException("absent"));

            IllegalStateException failure =
                    assertThrows(IllegalStateException.class, () -> service.validateTicket(5L));

            assertEquals("SHA-256 indisponible", failure.getMessage());
            assertNotNull(failure.getCause());
            assertInstanceOf(NoSuchAlgorithmException.class, failure.getCause());
        }
    }

    // --------------------------------------------------
    // cancelDraft
    // --------------------------------------------------

    /**
     * Covers the OPEN arm of {@code cancelDraft}: the draft is flipped to
     * CANCELLED, persisted, journaled and enqueued.
     */
    @Test
    void cancelDraftFlipsOpenDraft() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.cancelDraft(5L);
            assertEquals(Ticket.TicketStatus.CANCELLED, ticket.status);
            verify(ticket, times(1)).persist();
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.TICKET_CANCELLED, "C04-00000001");
            verify(service.syncOutboxService).enqueue(SyncOutbox.EntityType.TICKET, 5L);
            verify(service.consumptionService).enqueueCancellation(
                    org.mockito.ArgumentMatchers.eq("C04-00000001"),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.any());
        }
    }

    /**
     * A cancelled draft carrying a store declares its consumption reversal with
     * that store's code (the store-non-null arm of the reversal hook).
     */
    @Test
    void cancelDraftDeclaresCancellationWithStoreCode() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        Store store = new Store();
        store.code = "C04";
        ticket.store = store;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.cancelDraft(5L);
            verify(service.consumptionService).enqueueCancellation(
                    org.mockito.ArgumentMatchers.eq("C04-00000001"),
                    org.mockito.ArgumentMatchers.eq("C04"),
                    org.mockito.ArgumentMatchers.any());
        }
    }

    /**
     * Covers the null-ticket arm of {@code cancelDraft}: the finder resolves to
     * null, so nothing is flipped or journaled.
     */
    @Test
    void cancelDraftIgnoresMissingTicket() {
        TicketPersistenceService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(null);
            service.cancelDraft(5L);
            verifyNoInteractions(service.technicalEventService);
            verifyNoInteractions(service.syncOutboxService);
        }
    }

    /**
     * Covers the non-OPEN arm of {@code cancelDraft}: the draft is already in a
     * terminal state, so it is left untouched.
     */
    @Test
    void cancelDraftIgnoresNonOpenDraft() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.CLOSED);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.cancelDraft(5L);
            assertEquals(Ticket.TicketStatus.CLOSED, ticket.status);
            verify(ticket, never()).persist();
            verifyNoInteractions(service.technicalEventService);
        }
    }

    // --------------------------------------------------
    // Change given as a credit note (BO-03-02-16)
    // --------------------------------------------------

    /**
     * {@code issueChangeCreditNote} creates an ACTIVE credit note carrying the
     * change, numbered from its own registry row, and answers that number.
     */
    @Test
    void issueChangeCreditNoteNumbersTheNoteFromItsRow() {
        TicketPersistenceService service = newService();
        try (MockedConstruction<StoredValue> created =
                mockConstruction(StoredValue.class, (mock, ctx) -> mock.id = 42L)) {
            String number = service.issueChangeCreditNote(9L, new java.math.BigDecimal("30.00"));
            assertEquals("297000000000042", number);
            StoredValue note = created.constructed().get(0);
            assertEquals(StoredValue.Kind.CREDIT_NOTE, note.kind);
            assertEquals(new java.math.BigDecimal("30.00"), note.initialAmount);
            assertEquals(new java.math.BigDecimal("30.00"), note.balance);
            assertEquals(Long.valueOf(9L), note.issuingTicketId);
            assertNotNull(note.issuedAt);
            verify(note, times(1)).persistAndFlush();
        }
    }

    /**
     * Nothing is created when there is nothing to hand over: no ticket, no
     * amount, or an amount of zero — the three legs of the guard.
     */
    @Test
    void issueChangeCreditNoteCreatesNothingWhenNothingIsOwed() {
        TicketPersistenceService service = newService();
        try (MockedConstruction<StoredValue> created = mockConstruction(StoredValue.class)) {
            assertNull(service.issueChangeCreditNote(null, new java.math.BigDecimal("30.00")));
            assertNull(service.issueChangeCreditNote(9L, null));
            assertNull(service.issueChangeCreditNote(9L, java.math.BigDecimal.ZERO));
            assertTrue(created.constructed().isEmpty());
        }
    }
}
