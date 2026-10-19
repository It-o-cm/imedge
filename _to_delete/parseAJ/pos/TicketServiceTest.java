package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.ui.PriceModType;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.VatRate;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.valuation.ValuationService;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.scanner.ScanContext;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.ui.TestMessages;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketService}.
 * <p>
 * Plain JUnit 5 + Mockito: every collaborator is mocked while the real
 * {@link PosState}/{@link TicketState} graph is exercised so cart mutations
 * are observed on live state. Panache static finders resolve to
 * {@link PanacheEntityBase} under plain {@code mvn test}: {@code Product.find}
 * is intercepted with {@link org.mockito.Mockito#mockStatic} on
 * {@link PanacheEntityBase}, while the entity-declared statics
 * {@code Product.findActiveByPlu} and {@code Price.findCurrentPrice} are
 * intercepted on {@link Product}/{@link Price} respectively. Every branch of
 * the target class is enumerated and covered, including the unreachable-by-API
 * {@code displayItem(null)} arm reached through reflection.
 */
class TicketServiceTest {

    /** Service under test with hand-wired collaborators. */
    private TicketService service;

    /** Journal of the age-check decisions (confirmed / refused). */
    private com.intermarche.pos.service.TechnicalEventService technicalEventService;

    /** Mocked register identity — whose last sale to recover. */
    private com.intermarche.pos.service.TicketNumberService ticketNumberService;

    /** Real POS state graph shared as both the injected and the parameter state. */
    private PosState state;

    /** Mocked hardware boundary (display and scale). */
    private HardwareService hardwareService;

    /** Mocked draft persistence collaborator. */
    private TicketPersistenceService ticketPersistenceService;

    /** Mocked valuation engine collaborator. */
    private ValuationService valuationService;

    /** Mocked cash session collaborator. */
    private CashSessionService cashSessionService;

    /** Mocked ticket printer collaborator. */
    private TicketPrinterService ticketPrinterService;

    /** Mocked CDI handler instance feeding the scan chain. */
    @SuppressWarnings("unchecked")
    private Instance<ScanContext.ScanHandler> scanHandlers = mock(Instance.class);

    /**
     * Builds a fresh service, an unlocked-agnostic real state and all mocks.
     */
    @BeforeEach
    void setUp() {
        service = new TicketService();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        service.i18n = TestMessages.french();
        // Back-office parameters: caps at their catalog defaults, so every
        // historical assertion (the 100 % ceiling included) is unchanged.
        service.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        // The administered lists (BO-04-02-07), consulted for the return type.
        service.posListService = mock(com.intermarche.pos.service.PosListService.class);
        when(service.posSettingsService.lineMaxDiscountPercent()).thenReturn(100);
        // BO-03-07-11 and -12 at their catalogue defaults — the cumulation is
        // allowed — so every historical discount assertion below is unchanged.
        when(service.posSettingsService.discountCumulativeOnDiscounted()).thenReturn(true);
        when(service.posSettingsService.discountGlobalOnDiscounted()).thenReturn(true);
        when(service.posSettingsService.globalMaxDiscountPercent()).thenReturn(100);
        // Euro ceilings at their catalog default of zero, i.e. disabled, so every
        // historical assertion on remises is unchanged (BO-03-07-04).
        when(service.posSettingsService.lineMaxDiscountAmount()).thenReturn(BigDecimal.ZERO);
        when(service.posSettingsService.globalMaxDiscountAmount()).thenReturn(BigDecimal.ZERO);
        // Original price shown at its catalog default (BO-10-07-12), so the
        // historical "Prix initial" assertions on forcePrice hold.
        when(service.posSettingsService.priceShowOriginalOnForce()).thenReturn(true);
        state = new PosState();
        hardwareService = mock(HardwareService.class);
        ticketPersistenceService = mock(TicketPersistenceService.class);
        valuationService = mock(ValuationService.class);
        cashSessionService = mock(CashSessionService.class);
        ticketPrinterService = mock(TicketPrinterService.class);
        service.hardwareService = hardwareService;
        // LC-14-01: the tare resolver is a stand-in returning no tare by
        // default, so every weighed-line assertion below is unchanged; the
        // tare-specific cases stub a positive tare explicitly.
        service.tareService = mock(com.intermarche.pos.ui.balance.TareService.class);
        when(service.tareService.resolve(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(BigDecimal.ZERO);
        service.ticketPersistenceService = ticketPersistenceService;
        service.valuationService = valuationService;
        service.cashSessionService = cashSessionService;
        service.ticketPrinterService = ticketPrinterService;
        // LC-04-04-12: cancelling a draft asks the abandon rules whether a ticket is
        // printed. The stand-in answers no, which is the shop that prints none — the
        // default of every case below.
        service.ticketAbandonService = mock(TicketAbandonService.class);
        service.state = state;
        technicalEventService = mock(com.intermarche.pos.service.TechnicalEventService.class);
        service.technicalEventService = technicalEventService;
        ticketNumberService = mock(com.intermarche.pos.service.TicketNumberService.class);
        service.ticketNumberService = ticketNumberService;
        service.scanHandlers = scanHandlers;
        // The sale-screen service, reached only by the cancel-by-scan interception
        // (EXP-36); a mock keeps the ordinary scan cases unchanged.
        service.homeService = mock(com.intermarche.pos.ui.home.HomeService.class);
        // The return flow, reached only by the return-screen interception
        // (LC-05-02-11); a mock keeps the ordinary scan cases unchanged.
        service.refundService = mock(com.intermarche.pos.ui.returnprocess.RefundService.class);
        service.defaultVatRate = new BigDecimal("0.20");
    }

    /**
     * Builds a standalone ticket line whose original unit price equals its
     * unit price (constructor default).
     *
     * @param label the display label
     * @param unitPrice the unit price including tax
     * @param quantity the quantity
     * @return the wired line
     */
    private TicketState.TicketItem line(String label, BigDecimal unitPrice, BigDecimal quantity) {
        return new TicketState.TicketItem(null, null, label, unitPrice, quantity, BigDecimal.ZERO);
    }

    /**
     * Builds an active product with an id and name, not forbidden to sale.
     *
     * @param name the product name
     * @param ean the EAN code, or null
     * @param plu the PLU code, or null
     * @return the wired product
     */
    private Product product(String name, String ean, String plu) {
        Product p = new Product();
        p.id = 42L;
        p.name = name;
        p.ean = ean;
        p.plu = plu;
        p.active = true;
        p.forbiddenToSale = false;
        return p;
    }

    /**
     * Builds a current price row.
     *
     * @param incTax the price including tax
     * @param vat the VAT rate
     * @return the wired price
     */
    private Price price(String incTax, String vat) {
        Price pr = new Price();
        pr.priceIncludingTax = new BigDecimal(incTax);
        pr.vat = new VatRate(1, new BigDecimal(vat), "Taux normal");
        return pr;
    }

    /**
     * Stubs an open cash session on the register.
     */
    private void openSession() {
        when(cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
    }

    // --- processScan ---

    /**
     * {@code processScan} while locked bypasses the session gate (first
     * operand false), runs the whole handler chain (exercising every
     * {@code getPriority} branch), clears a pending transient error and skips
     * the trailing sync (locked arm of the sync guard).
     */
    @Test
    void processScanLockedRunsHandlersClearsErrorAndSkipsSync() {
        state.auth.isLocked = true;
        state.ticket.transientError = "old";
        ScanContext.ScanHandler jdkProxy = (ScanContext.ScanHandler) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ScanContext.ScanHandler.class},
                new InvocationHandler() {
                    /**
                     * No-op invocation handler for the JDK proxy handler.
                     *
                     * @param proxy the proxy instance
                     * @param method the invoked method
                     * @param args the arguments
                     * @return always null
                     */
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        return null;
                    }
                });
        List<ScanContext.ScanHandler> handlers = List.of(
                new PriorityHandler(), new NoPriorityHandler(), new Marker_ClientProxy(), jdkProxy);
        when(scanHandlers.spliterator()).thenReturn(handlers.spliterator());
        service.processScan("123");
        assertNull(state.ticket.transientError);
        assertEquals(-1, state.selectedTicketIndex);
        verify(ticketPersistenceService, never()).syncDraft(state);
        verify(valuationService, never()).revalue(state);
    }

    /**
     * {@code processScan} unlocked with no open session refuses the scan: the
     * session gate returns (both gate operands true), an error is shown and
     * the handler chain never runs. A null transient error takes the
     * skip arm of the error reset.
     */
    @Test
    void processScanUnlockedNoSessionRefusesBeforeHandlers() {
        state.auth.isLocked = false;
        state.trainingMode = false;
        state.ticket.transientError = null;
        when(cashSessionService.getOpenSession()).thenReturn(null);
        service.processScan("123");
        assertEquals("AUCUNE SESSION OUVERTE - MENU CAISSE", state.ticket.transientError);
        verify(scanHandlers, never()).spliterator();
        verify(ticketPersistenceService, never()).syncDraft(state);
    }

    /**
     * {@code processScan} unlocked with an open session, empty cart and no
     * draft id runs the chain but skips the sync (both sync sub-conditions
     * false).
     */
    @Test
    void processScanUnlockedSessionEmptyCartSkipsSync() {
        state.auth.isLocked = false;
        openSession();
        state.payment.ticketDbId = null;
        when(scanHandlers.spliterator()).thenReturn(List.<ScanContext.ScanHandler>of().spliterator());
        service.processScan("123");
        verify(ticketPersistenceService, never()).syncDraft(state);
        verify(valuationService, never()).revalue(state);
    }

    /**
     * {@code processScan} in cancel-by-scan mode (EXP-36) routes the code to the
     * cancel gesture and SKIPS the add-handler chain entirely.
     */
    @Test
    void processScanCancelByScanInterceptsBeforeTheChain() {
        state.auth.isLocked = false;
        openSession();
        state.cancelByScanArmed = true;
        service.processScan("3560070123456");
        verify(service.homeService).cancelByScan("3560070123456");
        verify(scanHandlers, never()).spliterator();
    }

    /**
     * {@code processScan} with a ticket selected on the RETURN screen routes the
     * code to the return and SKIPS the add-handler chain entirely (LC-05-02-11):
     * the scanned article designates the origin line to take back, never a sale.
     */
    @Test
    void processScanOnTheReturnScreenInterceptsBeforeTheChain() {
        state.auth.isLocked = false;
        openSession();
        state.refund.selectedTicket = new com.intermarche.pos.domain.sale.Ticket();
        service.processScan("3560070123456");
        verify(service.refundService).enterArticleByCode(state, "3560070123456");
        verify(scanHandlers, never()).spliterator();
    }

    /**
     * {@code processScan} on the RETURN screen WITH a manager endorsement pending
     * does NOT swallow the code as an origin-line scan (the added arm of the
     * return interception, BO-10-02): a refund awaiting a manager's aval shows the
     * endorsement modal over the return screen, and the manager's badge must reach
     * the chain's badge handler to prefill it — so the chain runs and the refund
     * article entry is left untouched.
     */
    @Test
    void processScanOnTheReturnScreenYieldsToAPendingEndorsement() {
        state.auth.isLocked = false;
        openSession();
        state.refund.selectedTicket = new com.intermarche.pos.domain.sale.Ticket();
        state.endorsement.active = true;
        when(scanHandlers.spliterator()).thenReturn(List.<ScanContext.ScanHandler>of().spliterator());
        service.processScan("11111111");
        verify(service.refundService, never()).enterArticleByCode(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(scanHandlers).spliterator();
    }

    /**
     * Without a ticket selected on the return screen the code goes down the
     * ordinary chain (the other arm of the same interception): a return left
     * behind must not swallow the next sale scan.
     */
    @Test
    void processScanWithoutAReturnSelectionRunsTheChain() {
        state.auth.isLocked = false;
        openSession();
        state.refund.selectedTicket = null;
        when(scanHandlers.spliterator()).thenReturn(List.<ScanContext.ScanHandler>of().spliterator());
        service.processScan("3560070123456");
        verify(service.refundService, never()).enterArticleByCode(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(scanHandlers).spliterator();
    }

    /**
     * {@code processScan} unlocked with an open session and a non-empty cart
     * runs the chain and syncs (first sync sub-condition true).
     */
    @Test
    void processScanUnlockedSessionNonEmptyCartSyncs() {
        state.auth.isLocked = false;
        openSession();
        state.ticket.items.add(line("X", new BigDecimal("1.00"), BigDecimal.ONE));
        when(scanHandlers.spliterator()).thenReturn(List.<ScanContext.ScanHandler>of().spliterator());
        service.processScan("123");
        verify(ticketPersistenceService, times(1)).syncDraft(state);
        verify(valuationService, times(1)).revalue(state);
    }

    /**
     * {@code processScan} unlocked with an open session, empty cart but a
     * present draft id syncs (second sync sub-condition true).
     */
    @Test
    void processScanUnlockedSessionEmptyCartWithDraftIdSyncs() {
        state.auth.isLocked = false;
        openSession();
        state.payment.ticketDbId = 7L;
        when(scanHandlers.spliterator()).thenReturn(List.<ScanContext.ScanHandler>of().spliterator());
        service.processScan("123");
        verify(ticketPersistenceService, times(1)).syncDraft(state);
        verify(valuationService, times(1)).revalue(state);
    }

    // --- processWeight ---

    /**
     * {@code processWeight} does nothing while the register is locked.
     */
    @Test
    void processWeightLockedIsNoOp() {
        state.auth.isLocked = true;
        state.ticket.currentWeight = 5.0;
        service.processWeight("1,5");
        assertEquals(5.0, state.ticket.currentWeight);
    }

    /**
     * {@code processWeight} parses a comma-separated weight and records it.
     */
    @Test
    void processWeightValidRecordsWeight() {
        state.auth.isLocked = false;
        service.processWeight("2,5");
        assertEquals(2.5, state.ticket.currentWeight);
    }

    /**
     * {@code processWeight} swallows an unparsable weight (catch arm) and
     * leaves the recorded weight untouched.
     */
    @Test
    void processWeightInvalidIsSwallowed() {
        state.auth.isLocked = false;
        state.ticket.currentWeight = 3.0;
        service.processWeight("abc");
        assertEquals(3.0, state.ticket.currentWeight);
    }

    // --- requireOpenSession training arm (via addDeposit) ---

    /**
     * In training mode a mutation proceeds without any open session
     * (training arm of the session guard).
     */
    @Test
    void addDepositInTrainingModeProceedsWithoutSession() {
        state.trainingMode = true;
        when(cashSessionService.getOpenSession()).thenReturn(null);
        service.addDeposit(state);
        assertEquals(1, state.ticket.items.size());
        assertEquals("DECONSIGNATION", state.ticket.items.get(0).label);
        verify(hardwareService).displayMessage(anyString());
        verify(ticketPersistenceService).syncDraft(state);
    }

    /**
     * {@code addDeposit} refuses the line when no session is open outside
     * training (session-null non-training arm).
     */
    @Test
    void addDepositWithoutSessionRefuses() {
        state.trainingMode = false;
        when(cashSessionService.getOpenSession()).thenReturn(null);
        service.addDeposit(state);
        assertTrue(state.ticket.items.isEmpty());
        assertEquals("AUCUNE SESSION OUVERTE - MENU CAISSE", state.ticket.transientError);
        verify(ticketPersistenceService, never()).syncDraft(state);
    }

    // --- loadRecalledDocument (EXP-90/91/92/85) ---

    /**
     * {@code loadRecalledDocument} loads a recalled document's lines into the sale
     * through the add funnel (EXP-90): each line becomes a ticket item.
     */
    @Test
    void loadRecalledDocumentLoadsLines() {
        openSession();
        com.intermarche.pos.ui.recall.RecalledDocument doc =
                new com.intermarche.pos.ui.recall.RecalledDocument(
                        com.intermarche.pos.ui.recall.RecalledDocument.Kind.COMMANDE, "CMD1",
                        java.util.List.of(new com.intermarche.pos.ui.recall.RecalledDocument.Line(
                                "300", "PAIN", BigDecimal.ONE, new BigDecimal("2.00"), new BigDecimal("0.055"))),
                        BigDecimal.ZERO);
        service.loadRecalledDocument(state, doc);
        assertEquals(1, state.ticket.items.size());
        assertEquals("PAIN", state.ticket.items.get(0).label);
        assertEquals(0, state.payment.paidAmount.compareTo(BigDecimal.ZERO));
    }

    /**
     * {@code loadRecalledDocument} deducts the order's already-paid down-payment
     * by registering it as a payment (EXP-85, "repris sur commande").
     */
    @Test
    void loadRecalledDocumentDeductsAcompteOnOrder() {
        openSession();
        com.intermarche.pos.ui.recall.RecalledDocument doc =
                new com.intermarche.pos.ui.recall.RecalledDocument(
                        com.intermarche.pos.ui.recall.RecalledDocument.Kind.COMMANDE, "CMD2",
                        java.util.List.of(new com.intermarche.pos.ui.recall.RecalledDocument.Line(
                                "300", "PAIN", BigDecimal.ONE, new BigDecimal("10.00"), new BigDecimal("0.055"))),
                        new BigDecimal("3.00"));
        service.loadRecalledDocument(state, doc);
        assertEquals(0, state.payment.paidAmount.compareTo(new BigDecimal("3.00")));
    }

    /**
     * {@code loadRecalledDocument} refuses a null document (unknown code) with
     * DOCUMENT INTROUVABLE and loads nothing.
     */
    @Test
    void loadRecalledDocumentNullRefuses() {
        openSession();
        service.loadRecalledDocument(state, null);
        assertTrue(state.ticket.items.isEmpty());
        assertEquals("DOCUMENT INTROUVABLE", state.ticket.transientError);
    }

    /**
     * {@code loadRecalledDocument} refuses a document with no line with DOCUMENT
     * VIDE and loads nothing.
     */
    @Test
    void loadRecalledDocumentEmptyRefuses() {
        openSession();
        com.intermarche.pos.ui.recall.RecalledDocument doc =
                new com.intermarche.pos.ui.recall.RecalledDocument(
                        com.intermarche.pos.ui.recall.RecalledDocument.Kind.DEVIS, "D1",
                        java.util.List.of(), BigDecimal.ZERO);
        service.loadRecalledDocument(state, doc);
        assertTrue(state.ticket.items.isEmpty());
        assertEquals("DOCUMENT VIDE", state.ticket.transientError);
    }

    /**
     * {@code loadRecalledDocument} refuses when no session is open (guard arm):
     * nothing is loaded.
     */
    @Test
    void loadRecalledDocumentWithoutSessionRefuses() {
        state.trainingMode = false;
        when(cashSessionService.getOpenSession()).thenReturn(null);
        com.intermarche.pos.ui.recall.RecalledDocument doc =
                new com.intermarche.pos.ui.recall.RecalledDocument(
                        com.intermarche.pos.ui.recall.RecalledDocument.Kind.BON_VENTE, "B1",
                        java.util.List.of(new com.intermarche.pos.ui.recall.RecalledDocument.Line(
                                "300", "PAIN", BigDecimal.ONE, new BigDecimal("2.00"), new BigDecimal("0.055"))),
                        BigDecimal.ZERO);
        service.loadRecalledDocument(state, doc);
        assertTrue(state.ticket.items.isEmpty());
    }

    // --- applyRemise ---

    /**
     * {@code applyRemise} returns on a null line (first guard operand).
     */
    @Test
    void applyRemiseNullItemReturns() {
        service.applyRemise(null, new BigDecimal("1.00"));
        verifyNoInteractions(hardwareService);
    }

    /**
     * {@code applyRemise} returns on a null amount (second guard operand).
     */
    @Test
    void applyRemiseNullAmountReturns() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        service.applyRemise(item, null);
        assertNull(item.modifierType);
        verifyNoInteractions(hardwareService);
    }

    /**
     * {@code applyRemise} returns on a non-positive amount (third guard operand).
     */
    @Test
    void applyRemiseNonPositiveAmountReturns() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        service.applyRemise(item, BigDecimal.ZERO);
        assertNull(item.modifierType);
        verifyNoInteractions(hardwareService);
    }

    /**
     * BO-02-03-09: {@code applyRemise} refuses a discount-forbidden line — the
     * line is untouched, exactly as for a money product.
     */
    @Test
    void applyRemiseRefusesDiscountForbiddenLine() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        item.discountForbidden = true;
        service.applyRemise(item, new BigDecimal("1.00"));
        assertNull(item.modifierType);
        assertEquals(new BigDecimal("10"), item.unitPrice);
        verifyNoInteractions(hardwareService);
    }

    /**
     * BO-02-03-09: {@code applyDiscount} refuses a discount-forbidden line — the
     * unit price is untouched.
     */
    @Test
    void applyDiscountRefusesDiscountForbiddenLine() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        item.discountForbidden = true;
        service.applyDiscount(item, new BigDecimal("20"));
        assertNull(item.modifierType);
        assertEquals(new BigDecimal("10"), item.unitPrice);
        verifyNoInteractions(hardwareService);
    }

    /**
     * BO-03-07-11: where the store forbids it, a second reduction on a line
     * that already carries one is REFUSED — both gestures, and both ways a line
     * can already be reduced (a manual modifier, and an engine-valued total
     * under the local price).
     */
    @Test
    void aSecondReductionIsRefusedWhereTheStoreForbidsTheCumulation() {
        when(service.posSettingsService.discountCumulativeOnDiscounted()).thenReturn(false);
        TicketState.TicketItem manual = line("X", new BigDecimal("10"), BigDecimal.ONE);
        manual.modifierType = com.intermarche.pos.ui.PriceModType.REMISE;
        service.applyDiscount(manual, new BigDecimal("20"));
        assertEquals(com.intermarche.pos.ui.PriceModType.REMISE, manual.modifierType);
        assertEquals(new BigDecimal("10"), manual.unitPrice);

        TicketState.TicketItem promoted = line("Y", new BigDecimal("10"), BigDecimal.ONE);
        promoted.valuedTotal = new BigDecimal("8.00");
        service.applyRemise(promoted, new BigDecimal("2"));
        assertNull(promoted.modifierType);
        assertEquals(new BigDecimal("10"), promoted.unitPrice);
    }

    /**
     * And where the store allows it, the same two gestures land — plus the two
     * legs that are NOT reductions: a line the engine valued at or above its
     * local price, and one it never valued at all.
     */
    @Test
    void aSecondReductionLandsWhereTheStoreAllowsTheCumulation() {
        TicketState.TicketItem manual = line("X", new BigDecimal("10"), BigDecimal.ONE);
        manual.modifierType = com.intermarche.pos.ui.PriceModType.REMISE;
        service.applyDiscount(manual, new BigDecimal("20"));
        assertEquals(com.intermarche.pos.ui.PriceModType.DISCOUNT, manual.modifierType);

        when(service.posSettingsService.discountCumulativeOnDiscounted()).thenReturn(false);
        TicketState.TicketItem valuedUp = line("Y", new BigDecimal("10"), BigDecimal.ONE);
        valuedUp.valuedTotal = new BigDecimal("10.00");
        service.applyDiscount(valuedUp, new BigDecimal("10"));
        assertEquals(com.intermarche.pos.ui.PriceModType.DISCOUNT, valuedUp.modifierType);

        TicketState.TicketItem unvalued = line("Z", new BigDecimal("10"), BigDecimal.ONE);
        service.applyDiscount(unvalued, new BigDecimal("10"));
        assertEquals(com.intermarche.pos.ui.PriceModType.DISCOUNT, unvalued.modifierType);
    }

    /**
     * BO-03-07-03 and -06: a ready-made key applies the administered figure
     * through the ordinary gestures — a PERCENT key discounts, an AMOUNT key
     * rebates — so a key and a keyed figure are capped and journaled the same.
     */
    @Test
    void aReadyMadeKeyAppliesTheAdministeredFigure() {
        when(service.posSettingsService.discountPreset("-20%")).thenReturn(
                new com.intermarche.pos.service.PosSettingsService.DiscountPreset(
                        "-20%", true, new BigDecimal("20"), false));
        when(service.posSettingsService.discountPreset("2 euros")).thenReturn(
                new com.intermarche.pos.service.PosSettingsService.DiscountPreset(
                        "2 euros", false, new BigDecimal("2"), false));
        TicketState.TicketItem percent = line("X", new BigDecimal("10"), BigDecimal.ONE);
        assertTrue(service.applyPreset(percent, "-20%"));
        assertEquals(com.intermarche.pos.ui.PriceModType.DISCOUNT, percent.modifierType);
        assertEquals(0, new BigDecimal("8").compareTo(percent.unitPrice));

        TicketState.TicketItem amount = line("Y", new BigDecimal("10"), BigDecimal.ONE);
        assertTrue(service.applyPreset(amount, "2 euros"));
        assertEquals(com.intermarche.pos.ui.PriceModType.REMISE, amount.modifierType);
        assertEquals(0, new BigDecimal("8").compareTo(amount.unitPrice));
    }

    /**
     * A key nobody administered, and a null line, apply NOTHING — the two arms
     * of the first guard.
     */
    @Test
    void aReadyMadeKeyNobodyAdministeredAppliesNothing() {
        when(service.posSettingsService.discountPreset("-30%")).thenReturn(null);
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        assertFalse(service.applyPreset(item, "-30%"));
        assertNull(item.modifierType);
        assertFalse(service.applyPreset(null, "-30%"));
    }

    /**
     * BO-03-07-11 on a ready-made key: a key carrying CUMUL lands on an
     * already-reduced line EVEN where the default forbids it, and a key that
     * does not carry it is refused there EVEN where the default allows it. The
     * key's rule is the key's, not the register's.
     */
    @Test
    void theCumulationRuleOfAKeyOverridesTheDefaultBothWays() {
        when(service.posSettingsService.discountPreset("cumulable")).thenReturn(
                new com.intermarche.pos.service.PosSettingsService.DiscountPreset(
                        "cumulable", true, new BigDecimal("10"), true));
        when(service.posSettingsService.discountPreset("exclusive")).thenReturn(
                new com.intermarche.pos.service.PosSettingsService.DiscountPreset(
                        "exclusive", true, new BigDecimal("10"), false));

        when(service.posSettingsService.discountCumulativeOnDiscounted()).thenReturn(false);
        TicketState.TicketItem reduced = line("X", new BigDecimal("10"), BigDecimal.ONE);
        reduced.modifierType = com.intermarche.pos.ui.PriceModType.REMISE;
        assertTrue(service.applyPreset(reduced, "cumulable"));
        assertEquals(com.intermarche.pos.ui.PriceModType.DISCOUNT, reduced.modifierType);

        when(service.posSettingsService.discountCumulativeOnDiscounted()).thenReturn(true);
        TicketState.TicketItem alsoReduced = line("Y", new BigDecimal("10"), BigDecimal.ONE);
        alsoReduced.modifierType = com.intermarche.pos.ui.PriceModType.REMISE;
        assertFalse(service.applyPreset(alsoReduced, "exclusive"));
        assertEquals(com.intermarche.pos.ui.PriceModType.REMISE, alsoReduced.modifierType);
        assertEquals(new BigDecimal("10"), alsoReduced.unitPrice);
    }

    /**
     * {@code applyRemise} with a zero original price (first orig operand),
     * a non-negative resulting total and a non-zero quantity divides the new
     * total back to a unit price. P8: the 5 € amount is a UNIT remise, so the
     * line loses 5 € × 2 = 10 € (20 € → 10 €) and each unit falls to 5 €.
     */
    @Test
    void applyRemiseZeroOriginalPositiveTotalDivides() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), new BigDecimal("2"));
        item.originalUnitPrice = BigDecimal.ZERO;
        service.applyRemise(item, new BigDecimal("5"));
        assertEquals(0, new BigDecimal("10").compareTo(item.originalUnitPrice));
        assertEquals(0, new BigDecimal("5").compareTo(item.unitPrice));
        assertEquals(PriceModType.REMISE, item.modifierType);
        assertEquals(0, new BigDecimal("5").compareTo(item.modifierValue));
        assertEquals("Remise -5,00€", item.modifierLabel);
        verify(hardwareService).displayMessage(anyString());
    }

    /**
     * P8, the expert's own case: 10 articles at 10 € (line 100 €) with a 2 €
     * remise is a UNIT remise, so the line loses 2 € × 10 = 20 € and settles at
     * 80 €, not 98 €. The unit price falls to 8 € and the recorded modifier value
     * stays the 2 € the operator entered.
     */
    @Test
    void applyRemiseTreatsTheAmountAsAUnitRemiseTimesQuantity() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), new BigDecimal("10"));
        service.applyRemise(item, new BigDecimal("2"));
        assertEquals(0, new BigDecimal("8").compareTo(item.unitPrice));
        assertEquals(0, new BigDecimal("2").compareTo(item.modifierValue));
        assertEquals(PriceModType.REMISE, item.modifierType);
    }

    /**
     * P8: the administered EURO ceiling caps the LINE discount, which is the
     * unit remise times the quantity. A 6 € unit remise on 2 articles is a 12 €
     * line discount and is refused by a 10 € ceiling, even though 6 € alone is
     * under it.
     */
    @Test
    void applyRemiseCapsTheUnitAmountTimesQuantity() {
        when(service.posSettingsService.lineMaxDiscountAmount()).thenReturn(new BigDecimal("10.00"));
        TicketState.TicketItem item = line("X", new BigDecimal("20.00"), new BigDecimal("2"));
        service.applyRemise(item, new BigDecimal("6.00"));
        assertEquals(0, new BigDecimal("20.00").compareTo(item.unitPrice));
        assertNull(item.modifierType);
    }

    /**
     * P8, cap boundary: a 5 € unit remise on 2 articles is exactly the 10 €
     * ceiling and passes — the line drops from 40 € to 30 €.
     */
    @Test
    void applyRemiseAcceptsAUnitAmountTimesQuantityAtTheCap() {
        when(service.posSettingsService.lineMaxDiscountAmount()).thenReturn(new BigDecimal("10.00"));
        TicketState.TicketItem item = line("X", new BigDecimal("20.00"), new BigDecimal("2"));
        service.applyRemise(item, new BigDecimal("5.00"));
        assertEquals(0, new BigDecimal("15.00").compareTo(item.unitPrice));
        assertEquals(PriceModType.REMISE, item.modifierType);
    }

    /**
     * {@code applyRemise} with an untouched original (second orig operand)
     * and an over-large amount floors the total to zero (negative arm).
     */
    @Test
    void applyRemiseUntouchedOriginalNegativeTotalFloorsToZero() {
        TicketState.TicketItem item = line("X", new BigDecimal("2"), BigDecimal.ONE);
        service.applyRemise(item, new BigDecimal("5"));
        assertEquals(0, BigDecimal.ZERO.compareTo(item.unitPrice));
        assertEquals(PriceModType.REMISE, item.modifierType);
    }

    /**
     * {@code applyRemise} on an already-modified line (both orig operands
     * false) with a zero quantity assigns the floored total directly as the
     * unit price (zero-quantity arm).
     */
    @Test
    void applyRemiseModifiedLineZeroQuantityAssignsTotal() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ZERO);
        item.originalUnitPrice = new BigDecimal("99");
        service.applyRemise(item, new BigDecimal("5"));
        assertEquals(0, new BigDecimal("99").compareTo(item.originalUnitPrice));
        assertEquals(0, BigDecimal.ZERO.compareTo(item.unitPrice));
    }

    // --- applyDiscount ---

    /**
     * {@code applyDiscount} returns on a null line (first guard operand).
     */
    @Test
    void applyDiscountNullItemReturns() {
        service.applyDiscount(null, new BigDecimal("10"));
        verifyNoInteractions(hardwareService);
    }

    /**
     * {@code applyDiscount} returns on a null percent (second guard operand).
     */
    @Test
    void applyDiscountNullPercentReturns() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        service.applyDiscount(item, null);
        assertNull(item.modifierType);
    }

    /**
     * {@code applyDiscount} returns on a non-positive percent (third guard operand).
     */
    @Test
    void applyDiscountNonPositivePercentReturns() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        service.applyDiscount(item, BigDecimal.ZERO);
        assertNull(item.modifierType);
    }

    /**
     * {@code applyDiscount} returns on a percent above 100 (fourth guard operand).
     */
    @Test
    void applyDiscountPercentAbove100Returns() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        service.applyDiscount(item, new BigDecimal("150"));
        assertNull(item.modifierType);
    }

    /**
     * BO-03-07-02: the line ceiling is the one the back office administers, not
     * the absolute 100. With {@code discount.line-max-percent} at 20, a 30 %
     * discount is refused — a percentage the absolute ceiling would have let
     * through. Replacing the administered read by a literal 100 makes this test
     * fail, which is the whole point of it.
     */
    @Test
    void applyDiscountRefusesPercentAboveTheAdministeredLineCap() {
        when(service.posSettingsService.lineMaxDiscountPercent()).thenReturn(20);
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        service.applyDiscount(item, new BigDecimal("30"));
        assertNull(item.modifierType);
        assertEquals(0, new BigDecimal("10").compareTo(item.unitPrice));
    }

    /**
     * BO-03-07-02: the administered ceiling is INCLUSIVE — a discount exactly at
     * the configured percentage applies. Paired with the case above, the two
     * bracket the boundary the back office actually sets.
     */
    @Test
    void applyDiscountAcceptsPercentExactlyAtTheAdministeredLineCap() {
        when(service.posSettingsService.lineMaxDiscountPercent()).thenReturn(20);
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        service.applyDiscount(item, new BigDecimal("20"));
        assertEquals(PriceModType.DISCOUNT, item.modifierType);
        assertEquals(0, new BigDecimal("8").compareTo(item.unitPrice));
    }

    /**
     * {@code applyDiscount} with a zero original price (first orig operand)
     * reduces the unit price by the percentage.
     */
    @Test
    void applyDiscountZeroOriginalReducesUnitPrice() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        item.originalUnitPrice = BigDecimal.ZERO;
        service.applyDiscount(item, new BigDecimal("10"));
        assertEquals(0, new BigDecimal("10").compareTo(item.originalUnitPrice));
        assertEquals(0, new BigDecimal("9").compareTo(item.unitPrice));
        assertEquals(PriceModType.DISCOUNT, item.modifierType);
        assertEquals(0, new BigDecimal("10").compareTo(item.modifierValue));
        assertEquals("Discount -10,00%", item.modifierLabel);
        verify(hardwareService).displayMessage(anyString());
    }

    /**
     * {@code applyDiscount} with an untouched original (second orig operand)
     * reduces the unit price.
     */
    @Test
    void applyDiscountUntouchedOriginalReducesUnitPrice() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        service.applyDiscount(item, new BigDecimal("50"));
        assertEquals(0, new BigDecimal("5").compareTo(item.unitPrice));
    }

    /**
     * {@code applyDiscount} on an already-modified line (both orig operands
     * false) keeps the recorded original.
     */
    @Test
    void applyDiscountModifiedLineKeepsOriginal() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        item.originalUnitPrice = new BigDecimal("99");
        service.applyDiscount(item, new BigDecimal("10"));
        assertEquals(0, new BigDecimal("99").compareTo(item.originalUnitPrice));
        assertEquals(0, new BigDecimal("9").compareTo(item.unitPrice));
    }

    // --- forcePrice ---

    /**
     * {@code forcePrice} returns on a null line (first guard operand).
     */
    @Test
    void forcePriceNullItemReturns() {
        service.forcePrice(null, new BigDecimal("10"));
        verifyNoInteractions(hardwareService);
    }

    /**
     * {@code forcePrice} returns on a null new total (second guard operand).
     */
    @Test
    void forcePriceNullTotalReturns() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        service.forcePrice(item, null);
        assertNull(item.modifierType);
    }

    /**
     * {@code forcePrice} returns on a negative new total (third guard operand).
     */
    @Test
    void forcePriceNegativeTotalReturns() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        service.forcePrice(item, new BigDecimal("-1"));
        assertNull(item.modifierType);
    }

    /**
     * {@code forcePrice} with a zero original price (first orig operand) and a
     * non-zero quantity divides the forced total into a unit price and records
     * the old line total.
     */
    @Test
    void forcePriceZeroOriginalNonZeroQuantityDivides() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), new BigDecimal("2"));
        item.originalUnitPrice = BigDecimal.ZERO;
        service.forcePrice(item, new BigDecimal("30"));
        assertEquals(0, new BigDecimal("10").compareTo(item.originalUnitPrice));
        assertEquals(0, new BigDecimal("15").compareTo(item.unitPrice));
        assertEquals(PriceModType.FORCE_PRICE, item.modifierType);
        assertEquals(0, new BigDecimal("30").compareTo(item.modifierValue));
        assertEquals("Prix initial: 20,00€", item.modifierLabel);
        verify(hardwareService).displayMessage(anyString());
    }

    /**
     * {@code forcePrice} with an untouched original (second orig operand), a
     * zero quantity and a zero new total assigns the total directly
     * (zero-quantity arm, non-negative total).
     */
    @Test
    void forcePriceUntouchedOriginalZeroQuantityAssignsTotal() {
        TicketState.TicketItem item = line("X", new BigDecimal("5"), BigDecimal.ZERO);
        service.forcePrice(item, BigDecimal.ZERO);
        assertEquals(0, BigDecimal.ZERO.compareTo(item.unitPrice));
        assertEquals("Prix initial: 0,00€", item.modifierLabel);
    }

    /**
     * {@code forcePrice} masks the original price when the back office
     * deactivated its display (BO-10-07-12, false arm of the ternary): the line
     * carries no "Prix initial" label, yet it still forces the price and is
     * marked as a FORCE_PRICE modification.
     */
    @Test
    void forcePriceMasksOriginalWhenAdministeredHidden() {
        when(service.posSettingsService.priceShowOriginalOnForce()).thenReturn(false);
        TicketState.TicketItem item = line("X", new BigDecimal("10"), new BigDecimal("2"));
        service.forcePrice(item, new BigDecimal("30"));
        assertNull(item.modifierLabel);
        assertEquals(PriceModType.FORCE_PRICE, item.modifierType);
        assertEquals(0, new BigDecimal("15").compareTo(item.unitPrice));
    }

    /**
     * {@code forcePrice} on an already-modified line (both orig operands
     * false) computes the old total from the recorded original.
     */
    @Test
    void forcePriceModifiedLineUsesRecordedOriginal() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), new BigDecimal("2"));
        item.originalUnitPrice = new BigDecimal("99");
        service.forcePrice(item, new BigDecimal("30"));
        assertEquals("Prix initial: 198,00€", item.modifierLabel);
        assertEquals(0, new BigDecimal("15").compareTo(item.unitPrice));
    }

    // --- recalculateTotal ---

    /**
     * {@code recalculateTotal} recomputes the ticket total, bumps the version
     * and syncs the draft.
     */
    @Test
    void recalculateTotalRecomputesAndSyncs() {
        state.ticket.items.add(line("A", new BigDecimal("2.00"), new BigDecimal("3")));
        long before = state.version;
        service.recalculateTotal(state);
        assertEquals(0, new BigDecimal("6.00").compareTo(state.ticket.totalAmount));
        assertTrue(state.version > before);
        verify(ticketPersistenceService).syncDraft(state);
        verify(valuationService).revalue(state);
    }

    // --- addItemByEan ---

    /**
     * {@code addItemByEan} returns on a null EAN (first guard operand).
     */
    @Test
    void addItemByEanNullEanReturns() {
        service.addItemByEan(state, null, BigDecimal.ONE);
        assertTrue(state.ticket.items.isEmpty());
        verifyNoInteractions(cashSessionService);
    }

    /**
     * {@code addItemByEan} returns on an empty EAN (second guard operand).
     */
    @Test
    void addItemByEanEmptyEanReturns() {
        service.addItemByEan(state, "", BigDecimal.ONE);
        assertTrue(state.ticket.items.isEmpty());
        verifyNoInteractions(cashSessionService);
    }

    /**
     * {@code addItemByEan} refuses when no session is open (session-guard
     * false arm).
     */
    @Test
    void addItemByEanNoSessionRefuses() {
        state.trainingMode = false;
        when(cashSessionService.getOpenSession()).thenReturn(null);
        service.addItemByEan(state, "123", BigDecimal.ONE);
        assertEquals("AUCUNE SESSION OUVERTE - MENU CAISSE", state.ticket.transientError);
        assertTrue(state.ticket.items.isEmpty());
    }

    /**
     * {@code addItemByEan} shows an error when the product is not found and
     * clears a pending transient error (non-null reset arm).
     */
    @Test
    void addItemByEanProductNotFound() {
        openSession();
        state.ticket.transientError = "old";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            // BO-02-03-04: the EAN miss falls back to the internal-code lookup,
            // which also misses here — both empty yields PRODUIT INTROUVABLE.
            panache.when(() -> Product.find("internalCode = ?1 and active = true", "123")).thenReturn(query);
            service.addItemByEan(state, "123", BigDecimal.ONE);
        }
        assertEquals("PRODUIT INTROUVABLE", state.ticket.transientError);
        assertTrue(state.ticket.items.isEmpty());
    }

    /**
     * {@code addItemByEan} refuses a product forbidden to sale (forbidden arm).
     */
    @Test
    void addItemByEanForbiddenProduct() {
        openSession();
        Product p = product("BEER", "123", null);
        p.forbiddenToSale = true;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            service.addItemByEan(state, "123", BigDecimal.ONE);
        }
        assertEquals("PRODUIT INTERDIT À LA VENTE", state.ticket.transientError);
        assertTrue(state.ticket.items.isEmpty());
    }

    /**
     * {@code addItemByEan} with a current price adds a line at that price and
     * VAT rate (price non-null arm), takes the null transient-error skip arm
     * and syncs.
     */
    @Test
    void addItemByEanWithPriceAddsLine() {
        openSession();
        state.ticket.transientError = null;
        Product p = product("MILK", "123", null);
        Price pr = price("1.50", "0.055");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(pr);
            service.addItemByEan(state, "123", new BigDecimal("2"));
        }
        assertEquals(1, state.ticket.items.size());
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals("MILK", added.label);
        assertEquals(0, new BigDecimal("1.50").compareTo(added.unitPrice));
        assertEquals(0, new BigDecimal("0.055").compareTo(added.vatRate));
        verify(hardwareService).displayMessage(anyString());
        verify(ticketPersistenceService).syncDraft(state);
        verify(valuationService).revalue(state);
    }

    /**
     * {@code addItemByEan} without a current price falls back to a zero price
     * and the default VAT rate (price null arm).
     */
    @Test
    void addItemByEanWithoutPriceUsesDefaults() {
        openSession();
        Product p = product("MILK", "123", null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
            service.addItemByEan(state, "123", BigDecimal.ONE);
        }
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(added.unitPrice));
        assertEquals(0, new BigDecimal("0.20").compareTo(added.vatRate));
    }

    /**
     * BO-02-03-04: {@code addItemByEan} falls back to the internal-code lookup
     * when the EAN misses, and rings the line under the product's own EAN.
     */
    @Test
    void addItemByEanResolvesByInternalCodeWhenEanMisses() {
        openSession();
        Product p = product("MILK", "3760001", null);
        p.internalCode = "INT-42";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> miss = mock(PanacheQuery.class);
            when(miss.firstResult()).thenReturn(null);
            panache.when(() -> Product.find("ean = ?1 and active = true", "INT-42")).thenReturn(miss);
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> hit = mock(PanacheQuery.class);
            when(hit.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("internalCode = ?1 and active = true", "INT-42")).thenReturn(hit);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
            service.addItemByEan(state, "INT-42", BigDecimal.ONE);
        }
        assertEquals(1, state.ticket.items.size());
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals("MILK", added.label);
        assertEquals("3760001", added.ean);
    }

    /**
     * BO-02-03-26/27: {@code addItemByEan} on a VAT-exempt article rings the
     * line at VAT rate 0 while keeping the catalog price (amount due unchanged).
     */
    @Test
    void addItemByEanVatExemptVentilatesAtZero() {
        openSession();
        Product p = product("MILK", "123", null);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.VAT_EXEMPT, "true");
        Price pr = price("1.50", "0.055");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(pr);
            service.addItemByEan(state, "123", BigDecimal.ONE);
        }
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals(0, new BigDecimal("1.50").compareTo(added.unitPrice));
        assertEquals(0, BigDecimal.ZERO.compareTo(added.vatRate));
    }

    /**
     * BO-02-03-11: {@code addItemByEan} refuses a recalled article with the
     * recall error and adds no line.
     */
    @Test
    void addItemByEanRefusesRecalledArticle() {
        openSession();
        Product p = product("MILK", "123", null);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.RECALL, "true");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            service.addItemByEan(state, "123", BigDecimal.ONE);
        }
        assertTrue(state.ticket.items.isEmpty());
        assertEquals("ARTICLE EN RETRAIT/RAPPEL", state.ticket.transientError);
    }

    /**
     * BO-02-03-09: {@code addItemByEan} snapshots the discount ban onto the
     * added line so later price gestures refuse it.
     */
    @Test
    void addItemByEanSnapshotsDiscountForbidden() {
        openSession();
        Product p = product("MILK", "123", null);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.DISCOUNT_FORBIDDEN, "true");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
            service.addItemByEan(state, "123", BigDecimal.ONE);
        }
        assertTrue(state.ticket.items.get(0).discountForbidden);
    }

    /**
     * BO-02-03-06: {@code addItemByEan} snapshots the meal-voucher eligibility
     * onto the added line, so a register the engine never answered still knows
     * what a meal ticket may settle.
     */
    @Test
    void addItemByEanSnapshotsMealVoucherEligibility() {
        openSession();
        Product p = product("SANDWICH", "124", null);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE, "true");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "124")).thenReturn(query);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
            service.addItemByEan(state, "124", BigDecimal.ONE);
        }
        assertTrue(state.ticket.items.get(0).mealVoucherEligible);
    }

    /**
     * An article the referential does NOT declare eligible lands on a line that
     * says so — the second value, which is what proves the attribute is read
     * rather than a literal posed.
     */
    @Test
    void addItemByEanSnapshotsAnIneligibleArticleAsSuch() {
        openSession();
        Product p = product("VIN", "125", null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "125")).thenReturn(query);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
            service.addItemByEan(state, "125", BigDecimal.ONE);
        }
        assertFalse(state.ticket.items.get(0).mealVoucherEligible);
    }

    // --- addItemByPlu ---

    /**
     * {@code addItemByPlu} refuses when no session is open (session-guard
     * false arm).
     */
    @Test
    void addItemByPluNoSessionRefuses() {
        state.trainingMode = false;
        when(cashSessionService.getOpenSession()).thenReturn(null);
        service.addItemByPlu(state, "1234");
        assertEquals("AUCUNE SESSION OUVERTE - MENU CAISSE", state.ticket.transientError);
    }

    /**
     * {@code addItemByPlu} shows an error when the PLU resolves to nothing
     * (product-null arm) and clears a pending transient error.
     */
    @Test
    void addItemByPluNotFound() {
        openSession();
        state.ticket.transientError = "old";
        try (MockedStatic<Product> productStatic = mockStatic(Product.class)) {
            productStatic.when(() -> Product.findActiveByPlu("1234")).thenReturn(null);
            service.addItemByPlu(state, "1234");
        }
        assertEquals("PLU INTROUVABLE", state.ticket.transientError);
    }

    /**
     * {@code addItemByPlu} refuses a product forbidden to sale (forbidden arm).
     */
    @Test
    void addItemByPluForbiddenProduct() {
        openSession();
        Product p = product("APPLE", "111", "1234");
        p.forbiddenToSale = true;
        try (MockedStatic<Product> productStatic = mockStatic(Product.class)) {
            productStatic.when(() -> Product.findActiveByPlu("1234")).thenReturn(p);
            service.addItemByPlu(state, "1234");
        }
        assertEquals("PRODUIT INTERDIT À LA VENTE", state.ticket.transientError);
    }

    /**
     * {@code addItemByPlu} refuses a non-positive weighing (weight-guard arm).
     */
    @Test
    void addItemByPluInvalidWeight() {
        openSession();
        Product p = product("APPLE", "111", "1234");
        when(hardwareService.awaitWeighing(org.mockito.ArgumentMatchers.any())).thenReturn(0.0);
        try (MockedStatic<Product> productStatic = mockStatic(Product.class)) {
            productStatic.when(() -> Product.findActiveByPlu("1234")).thenReturn(p);
            service.addItemByPlu(state, "1234");
        }
        assertEquals("POIDS INVALIDE", state.ticket.transientError);
    }

    /**
     * {@code addItemByPlu} rejects a weighing identical to the last recorded
     * one (not-NaN and equal arm).
     */
    @Test
    void addItemByPluDuplicateWeight() {
        openSession();
        Product p = product("APPLE", "111", "1234");
        state.ticket.lastRecordedWeight = 1.5;
        when(hardwareService.awaitWeighing(org.mockito.ArgumentMatchers.any())).thenReturn(1.5);
        try (MockedStatic<Product> productStatic = mockStatic(Product.class)) {
            productStatic.when(() -> Product.findActiveByPlu("1234")).thenReturn(p);
            service.addItemByPlu(state, "1234");
        }
        assertEquals("ERREUR POIDS IDENTIQUE", state.ticket.transientError);
    }

    /**
     * {@code addItemByPlu} adds a weighed line with a current price when the
     * last weight differs (not-NaN and not-equal arm, price non-null arm),
     * clears a null transient error and syncs.
     */
    @Test
    void addItemByPluAddsWeighedLineWithPrice() {
        openSession();
        state.ticket.transientError = null;
        state.ticket.lastRecordedWeight = 2.0;
        Product p = product("APPLE", "111", "1234");
        Price pr = price("3.00", "0.055");
        when(hardwareService.awaitWeighing(org.mockito.ArgumentMatchers.any())).thenReturn(1.5);
        try (MockedStatic<Product> productStatic = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            productStatic.when(() -> Product.findActiveByPlu("1234")).thenReturn(p);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(pr);
            service.addItemByPlu(state, "1234");
        }
        assertEquals(1, state.ticket.items.size());
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals("APPLE", added.label);
        assertEquals("1234", added.plu);
        assertEquals(0, new BigDecimal("1.500").compareTo(added.quantity));
        assertEquals(0, new BigDecimal("3.00").compareTo(added.unitPrice));
        assertEquals(1.5, state.ticket.lastRecordedWeight);
        verify(ticketPersistenceService).syncDraft(state);
    }

    /**
     * LC-14-01-03/06/07: {@code addItemByPlu} resolves the software tare, SENDS
     * it to the scale and records the NET weight the scale returns AS-IS —
     * never re-deducting (non-cumulation) — then consumes the punctual
     * container and snapshots the article tare on the line for a later manual
     * re-entry.
     */
    @Test
    void addItemByPluSendsTareRecordsNetAndSnapshots() {
        openSession();
        Product p = product("APPLE", "111", "1234");
        p.attributes.put("TARE", "0.010");
        Price pr = price("3.00", "0.055");
        when(service.tareService.resolve(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(new BigDecimal("0.010"));
        when(hardwareService.awaitWeighing(new BigDecimal("0.010"))).thenReturn(0.290);
        try (MockedStatic<Product> productStatic = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            productStatic.when(() -> Product.findActiveByPlu("1234")).thenReturn(p);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(pr);
            service.addItemByPlu(state, "1234");
        }
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals(0, new BigDecimal("0.290").compareTo(added.quantity));
        assertEquals(0, new BigDecimal("0.010").compareTo(added.tareKg));
        verify(hardwareService).awaitWeighing(new BigDecimal("0.010"));
        verify(service.tareService).consume(state);
    }

    /**
     * {@code addItemByPlu} with the default NaN last weight (NaN short-circuit
     * arm) and no current price falls back to zero price and the default VAT
     * rate (price null arm).
     */
    @Test
    void addItemByPluFirstWeighingWithoutPriceUsesDefaults() {
        openSession();
        Product p = product("APPLE", "111", "1234");
        when(hardwareService.awaitWeighing(org.mockito.ArgumentMatchers.any())).thenReturn(1.5);
        try (MockedStatic<Product> productStatic = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            productStatic.when(() -> Product.findActiveByPlu("1234")).thenReturn(p);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
            service.addItemByPlu(state, "1234");
        }
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(added.unitPrice));
        assertEquals(0, new BigDecimal("0.20").compareTo(added.vatRate));
    }

    /**
     * BO-02-03-11: {@code addItemByPlu} refuses a recalled article with the
     * recall error (true arm of the PLU recall guard) and — the check sitting
     * BEFORE the scale read — never weighs and adds no line.
     */
    @Test
    void addItemByPluRefusesRecalledArticle() {
        openSession();
        Product p = product("APPLE", "111", "1234");
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.RECALL, "true");
        try (MockedStatic<Product> productStatic = mockStatic(Product.class)) {
            productStatic.when(() -> Product.findActiveByPlu("1234")).thenReturn(p);
            service.addItemByPlu(state, "1234");
        }
        assertTrue(state.ticket.items.isEmpty());
        assertEquals("ARTICLE EN RETRAIT/RAPPEL", state.ticket.transientError);
        verify(hardwareService, never()).awaitWeighing(org.mockito.ArgumentMatchers.any());
    }

    /**
     * BO-02-03-26/27: {@code addItemByPlu} on a VAT-exempt weighed article rings
     * the line at VAT rate 0 (true arm of the PLU vat-exempt guard) while keeping
     * the catalog price.
     */
    @Test
    void addItemByPluVatExemptVentilatesAtZero() {
        openSession();
        Product p = product("APPLE", "111", "1234");
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.VAT_EXEMPT, "true");
        when(hardwareService.awaitWeighing(org.mockito.ArgumentMatchers.any())).thenReturn(1.5);
        try (MockedStatic<Product> productStatic = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            productStatic.when(() -> Product.findActiveByPlu("1234")).thenReturn(p);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(price("3.00", "0.055"));
            service.addItemByPlu(state, "1234");
        }
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals(0, new BigDecimal("3.00").compareTo(added.unitPrice));
        assertEquals(0, BigDecimal.ZERO.compareTo(added.vatRate));
    }

    /**
     * BO-02-03-09: {@code addItemByPlu} snapshots the discount ban onto the
     * added weighed line (true arm of the PLU discount-forbidden guard) so later
     * price gestures refuse it.
     */
    @Test
    void addItemByPluSnapshotsDiscountForbidden() {
        openSession();
        Product p = product("APPLE", "111", "1234");
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.DISCOUNT_FORBIDDEN, "true");
        when(hardwareService.awaitWeighing(org.mockito.ArgumentMatchers.any())).thenReturn(1.5);
        try (MockedStatic<Product> productStatic = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            productStatic.when(() -> Product.findActiveByPlu("1234")).thenReturn(p);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
            service.addItemByPlu(state, "1234");
        }
        assertTrue(state.ticket.items.get(0).discountForbidden);
    }

    // --- addUnknownItem ---

    /**
     * {@code addUnknownItem} refuses when no session is open (session-guard
     * false arm).
     */
    @Test
    void addUnknownItemNoSessionRefuses() {
        state.trainingMode = false;
        when(cashSessionService.getOpenSession()).thenReturn(null);
        service.addUnknownItem(state, "LABEL", "1.00");
        assertEquals("AUCUNE SESSION OUVERTE - MENU CAISSE", state.ticket.transientError);
    }

    /**
     * {@code addUnknownItem} adds a line when price, label and non-empty label
     * all hold (all-true arm), clearing a pending transient error.
     */
    @Test
    void addUnknownItemAddsLine() {
        openSession();
        state.ticket.transientError = "old";
        service.addUnknownItem(state, "candy", "1,50");
        assertEquals(1, state.ticket.items.size());
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals("CANDY", added.label);
        assertEquals(0, new BigDecimal("1.50").compareTo(added.unitPrice));
        assertNull(state.ticket.transientError);
        verify(ticketPersistenceService).syncDraft(state);
    }

    /**
     * {@code addUnknownItem} adds nothing on a negative price (first inner
     * operand false) without raising an error.
     */
    @Test
    void addUnknownItemNegativePriceAddsNothing() {
        openSession();
        service.addUnknownItem(state, "candy", "-1.00");
        assertTrue(state.ticket.items.isEmpty());
        assertNull(state.ticket.transientError);
    }

    /**
     * {@code addUnknownItem} adds nothing on a null label (second inner
     * operand false).
     */
    @Test
    void addUnknownItemNullLabelAddsNothing() {
        openSession();
        service.addUnknownItem(state, null, "1.00");
        assertTrue(state.ticket.items.isEmpty());
        assertNull(state.ticket.transientError);
    }

    /**
     * {@code addUnknownItem} adds nothing on an empty label (third inner
     * operand false).
     */
    @Test
    void addUnknownItemEmptyLabelAddsNothing() {
        openSession();
        service.addUnknownItem(state, "", "1.00");
        assertTrue(state.ticket.items.isEmpty());
        assertNull(state.ticket.transientError);
    }

    /**
     * {@code addUnknownItem} shows an error on an unparsable price (catch arm).
     */
    @Test
    void addUnknownItemInvalidPrice() {
        openSession();
        service.addUnknownItem(state, "candy", "abc");
        assertEquals("ERREUR PRIX SAISI", state.ticket.transientError);
        assertTrue(state.ticket.items.isEmpty());
    }

    // --- applyGlobalDiscount ---

    /**
     * A payment in progress REFUSES the whole-ticket gesture: the totals are
     * already committed to the payment screen, so moving them under the
     * cashier's feet is forbidden (first guard true).
     */
    @Test
    void applyGlobalDiscountRefusedDuringPayment() {
        state.payment.paymentInProgress = true;
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_DISCOUNT, new BigDecimal("10"));
        assertEquals("TERMINEZ OU ANNULEZ LE TICKET D'ABORD", state.ticket.transientError);
        assertNull(state.ticket.globalDiscountType);
    }

    /**
     * A NULL value is invalid (second guard, first leg).
     */
    @Test
    void applyGlobalDiscountRejectsNullValue() {
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_REMISE, null);
        assertEquals("VALEUR INVALIDE", state.ticket.transientError);
        assertNull(state.ticket.globalDiscountType);
    }

    /**
     * A NEGATIVE value is invalid (second guard, second leg): a gesture may
     * lower a ticket, never inflate it.
     */
    @Test
    void applyGlobalDiscountRejectsNegativeValue() {
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_REMISE, new BigDecimal("-1"));
        assertEquals("VALEUR INVALIDE", state.ticket.transientError);
        assertNull(state.ticket.globalDiscountType);
    }

    /**
     * A percentage ABOVE 100 is invalid (second guard, third leg): the guard
     * is percent-only — the same figure in euros is a legitimate request that
     * the allocation will cap at the base.
     */
    @Test
    void applyGlobalDiscountRejectsPercentAbove100() {
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_DISCOUNT, new BigDecimal("101"));
        assertEquals("VALEUR INVALIDE", state.ticket.transientError);
        assertNull(state.ticket.globalDiscountType);
    }

    /**
     * Exactly 100 % is ACCEPTED (third leg false at the boundary): giving the
     * whole ticket away is a legitimate, endorsed gesture.
     */
    @Test
    void applyGlobalDiscountAcceptsExactly100Percent() {
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_DISCOUNT, new BigDecimal("100"));
        assertEquals("PERCENT", state.ticket.globalDiscountType);
        assertEquals(0, new BigDecimal("100").compareTo(state.ticket.globalDiscountValue));
    }

    /**
     * BO-03-07-08: the whole-ticket ceiling is the one the back office
     * administers. With {@code discount.global-max-percent} at 30, a 50 %
     * ticket discount is refused — a percentage the absolute 100 would have
     * accepted, so this case fails the moment the administered read is replaced
     * by a literal.
     */
    @Test
    void applyGlobalDiscountRefusesPercentAboveTheAdministeredCap() {
        when(service.posSettingsService.globalMaxDiscountPercent()).thenReturn(30);
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_DISCOUNT, new BigDecimal("50"));
        assertEquals("VALEUR INVALIDE", state.ticket.transientError);
        assertNull(state.ticket.globalDiscountType);
    }

    /**
     * BO-03-07-08: the administered ceiling is INCLUSIVE — a ticket discount
     * exactly at the configured percentage applies.
     */
    @Test
    void applyGlobalDiscountAcceptsPercentExactlyAtTheAdministeredCap() {
        when(service.posSettingsService.globalMaxDiscountPercent()).thenReturn(30);
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_DISCOUNT, new BigDecimal("30"));
        assertEquals("PERCENT", state.ticket.globalDiscountType);
        assertEquals(0, new BigDecimal("30").compareTo(state.ticket.globalDiscountValue));
    }

    /**
     * BO-03-07-08: the administered ceiling governs PERCENT only. The same
     * figure asked in euros goes through untouched — a 50 € reduction on a
     * ticket is not a 50 % one, and the allocation caps it at the base.
     */
    @Test
    void applyGlobalDiscountAdministeredCapDoesNotGovernEuros() {
        when(service.posSettingsService.globalMaxDiscountPercent()).thenReturn(30);
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_REMISE, new BigDecimal("50"));
        assertEquals("AMOUNT", state.ticket.globalDiscountType);
        assertEquals(0, new BigDecimal("50").compareTo(state.ticket.globalDiscountValue));
    }

    /**
     * A euro amount ABOVE 100 is accepted (the percent-only leg does not fire
     * on GLOBAL_REMISE): the allocation caps it at the ticket base.
     */
    @Test
    void applyGlobalDiscountAcceptsEurosAbove100() {
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_REMISE, new BigDecimal("150"));
        assertEquals("AMOUNT", state.ticket.globalDiscountType);
        assertEquals(0, new BigDecimal("150").compareTo(state.ticket.globalDiscountValue));
    }

    /**
     * ZERO is accepted and ERASES the request through the state setter (the
     * guard is {@code < 0}, not {@code <= 0}): typing 0 is how a cashier
     * cancels the gesture.
     */
    @Test
    void applyGlobalDiscountZeroErasesRequest() {
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_DISCOUNT, new BigDecimal("10"));
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_DISCOUNT, BigDecimal.ZERO);
        assertNull(state.ticket.globalDiscountType);
        assertNull(state.ticket.globalDiscountValue);
    }

    /**
     * The type is mapped to the STATE's vocabulary — GLOBAL_DISCOUNT becomes
     * PERCENT, anything else becomes AMOUNT — and the totals are recomputed
     * so the allocation happens immediately.
     */
    @Test
    void applyGlobalDiscountMapsTypeAndRecomputes() {
        state.ticket.items.add(line("MILK", new BigDecimal("10.00"), BigDecimal.ONE));
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_REMISE, new BigDecimal("2.00"));
        assertEquals("AMOUNT", state.ticket.globalDiscountType);
        assertEquals(0, new BigDecimal("2.00").compareTo(state.ticket.globalDiscountApplied));
        assertEquals(0, new BigDecimal("8.00").compareTo(state.ticket.totalAmount));
    }

    // --- suspendForAgeCheck ---

    /**
     * An UNRESTRICTED product never parks the scan (first leg true).
     */
    @Test
    void suspendForAgeCheckPassesUnrestrictedProduct() {
        Product p = product("MILK", "123", null);
        p.ageRestriction = null;
        assertFalse(service.suspendForAgeCheck(state, p, "SCAN", "123", null));
        assertFalse(state.ageCheck.active);
        verifyNoInteractions(hardwareService);
    }

    /**
     * A restriction ALREADY covered by this ticket's verified threshold lets
     * the scan through (second leg true, {@code <} case): the ID is checked
     * once per ticket, not once per bottle.
     */
    @Test
    void suspendForAgeCheckPassesWhenThresholdAlreadyCleared() {
        Product p = product("WINE", "123", null);
        p.ageRestriction = 18;
        state.ticket.ageVerifiedThreshold = 21;
        assertFalse(service.suspendForAgeCheck(state, p, "SCAN", "123", null));
        assertFalse(state.ageCheck.active);
    }

    /**
     * An EQUAL threshold also lets the scan through (second leg true,
     * {@code ==} boundary): the guard is {@code <=}.
     */
    @Test
    void suspendForAgeCheckPassesOnEqualThreshold() {
        Product p = product("WINE", "123", null);
        p.ageRestriction = 18;
        state.ticket.ageVerifiedThreshold = 18;
        assertFalse(service.suspendForAgeCheck(state, p, "SCAN", "123", null));
        assertFalse(state.ageCheck.active);
    }

    /**
     * A HIGHER restriction parks the gesture with everything needed to replay
     * it (both legs false): kind, code, quantity, label and threshold — the
     * customer display is warned and the polling woken.
     */
    @Test
    void suspendForAgeCheckParksTheGesture() {
        Product p = product("vin rouge", "123", null);
        p.ageRestriction = 18;
        state.ticket.ageVerifiedThreshold = 0;
        long version = state.version;
        assertTrue(service.suspendForAgeCheck(state, p, "EAN_QTY", "123", new BigDecimal("2")));
        assertTrue(state.ageCheck.active);
        assertEquals("VIN ROUGE", state.ageCheck.productLabel);
        assertEquals(18, state.ageCheck.threshold);
        assertEquals("EAN_QTY", state.ageCheck.kind);
        assertEquals("123", state.ageCheck.code);
        assertEquals(0, new BigDecimal("2").compareTo(state.ageCheck.quantity));
        verify(hardwareService).displayMessage("CONTROLE D'AGE EN COURS");
        assertTrue(state.version > version);
    }

    // --- confirmAgeCheck / refuseAgeCheck ---

    /**
     * Confirming with NO pending prompt is a silent no-op (guard true).
     */
    @Test
    void confirmAgeCheckWithoutPendingIsNoOp() {
        service.confirmAgeCheck(state);
        verifyNoInteractions(technicalEventService);
        assertTrue(state.ticket.items.isEmpty());
    }

    /**
     * Confirming RAISES the ticket's verified threshold, journals the check
     * and REPLAYS the parked gesture by kind — here the quantity add, whose
     * EAN and quantity come back verbatim from the parked state.
     */
    @Test
    void confirmAgeCheckRaisesThresholdJournalsAndReplaysEanQty() {
        openSession();
        state.auth.operatorName = "Marie";
        state.ticket.ageVerifiedThreshold = 0;
        state.ageCheck.active = true;
        state.ageCheck.productLabel = "VIN ROUGE";
        state.ageCheck.threshold = 18;
        state.ageCheck.kind = "EAN_QTY";
        state.ageCheck.code = "123";
        state.ageCheck.quantity = new BigDecimal("2");
        Product p = product("VIN ROUGE", "123", null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(price("5.00", "0.20"));
            service.confirmAgeCheck(state);
        }
        assertEquals(18, state.ticket.ageVerifiedThreshold);
        assertFalse(state.ageCheck.active);
        assertNull(state.ageCheck.code);
        assertEquals(1, state.ticket.items.size());
        assertEquals(0, new BigDecimal("2").compareTo(state.ticket.items.get(0).quantity));
        verify(technicalEventService).log(
                eq(TechnicalEvent.EventType.AGE_CHECK_CONFIRMED), anyString());
    }

    /**
     * The verified threshold only ever GROWS: confirming an 18+ check on a
     * ticket already cleared for 21 keeps 21 ({@code Math.max}).
     */
    @Test
    void confirmAgeCheckNeverLowersTheVerifiedThreshold() {
        openSession();
        state.auth.operatorName = "Marie";
        state.ticket.ageVerifiedThreshold = 21;
        state.ageCheck.active = true;
        state.ageCheck.productLabel = "VIN ROUGE";
        state.ageCheck.threshold = 18;
        state.ageCheck.kind = "PLU";
        state.ageCheck.code = "99";
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByPlu("99")).thenReturn(null);
            service.confirmAgeCheck(state);
        }
        assertEquals(21, state.ticket.ageVerifiedThreshold);
    }

    /**
     * Refusing with NO pending prompt is a silent no-op (guard true).
     */
    @Test
    void refuseAgeCheckWithoutPendingIsNoOp() {
        service.refuseAgeCheck(state);
        verifyNoInteractions(technicalEventService);
        assertNull(state.ticket.transientError);
    }

    /**
     * Refusing JOURNALS the refusal, clears the parked gesture and shows the
     * cashier message — and it does NOT raise the verified threshold, so the
     * next restricted scan will ask again.
     */
    @Test
    void refuseAgeCheckJournalsClearsAndWarns() {
        state.auth.operatorName = "Marie";
        state.ageCheck.active = true;
        state.ageCheck.productLabel = "VIN ROUGE";
        state.ageCheck.threshold = 18;
        state.ageCheck.kind = "SCAN";
        state.ageCheck.code = "123";
        service.refuseAgeCheck(state);
        verify(technicalEventService).log(
                eq(TechnicalEvent.EventType.AGE_CHECK_REFUSED), anyString());
        assertFalse(state.ageCheck.active);
        assertNull(state.ageCheck.code);
        assertEquals(0, state.ticket.ageVerifiedThreshold);
        assertEquals("VENTE REFUSÉE - CONTRÔLE D'ÂGE", state.ticket.transientError);
        assertTrue(state.ticket.items.isEmpty());
    }

    // --- money product flag ---

    /**
     * A GIFT CARD added by EAN is flagged as a money product: the line
     * carries VALUE, not goods — the flag is what later excludes it from
     * discounts, gestures and valuation, and forbids its refund.
     */
    @Test
    void addItemByEanFlagsGiftCardAsMoneyProduct() {
        openSession();
        Product p = product("CARTE CADEAU 25", "3400025000001", null);
        p.giftCardAmount = new BigDecimal("25.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "3400025000001"))
                    .thenReturn(query);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong()))
                    .thenReturn(price("25.00", "0.00"));
            service.addItemByEan(state, "3400025000001", BigDecimal.ONE);
        }
        assertEquals(1, state.ticket.items.size());
        assertTrue(state.ticket.items.get(0).moneyProduct);
    }

    /**
     * An ordinary product is NOT flagged (null giftCardAmount arm).
     */
    @Test
    void addItemByEanLeavesOrdinaryProductUnflagged() {
        openSession();
        Product p = product("MILK", "123", null);
        p.giftCardAmount = null;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(price("1.50", "0.055"));
            service.addItemByEan(state, "123", BigDecimal.ONE);
        }
        assertFalse(state.ticket.items.get(0).moneyProduct);
    }

    /**
     * Confirming a SCAN-kind gesture replays it through the recognition
     * CHAIN ({@code default} arm of the replay switch), not through a direct
     * add: the confirmed code re-enters where it came from, so every handler
     * priority applies again exactly as on the first pass.
     */
    @Test
    void confirmAgeCheckReplaysScanThroughTheChain() {
        state.auth.operatorName = "Marie";
        state.ageCheck.active = true;
        state.ageCheck.productLabel = "VIN ROUGE";
        state.ageCheck.threshold = 18;
        state.ageCheck.kind = "SCAN";
        state.ageCheck.code = "3400018000001";
        RecordingHandler handler = new RecordingHandler();
        when(scanHandlers.spliterator())
                .thenReturn(List.<ScanContext.ScanHandler>of(handler).spliterator());
        service.confirmAgeCheck(state);
        assertEquals("3400018000001", handler.seenCode);
        assertEquals(18, state.ticket.ageVerifiedThreshold);
        assertFalse(state.ageCheck.active);
    }

    /**
     * {@code addItemByEan} on an age-restricted product PARKS the gesture and
     * adds nothing (true arm of the suspend call site): the line only appears
     * after the ID check is confirmed.
     */
    @Test
    void addItemByEanParksRestrictedProduct() {
        openSession();
        Product p = product("VIN ROUGE", "3400018000001", null);
        p.ageRestriction = 18;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "3400018000001"))
                    .thenReturn(query);
            service.addItemByEan(state, "3400018000001", new BigDecimal("2"));
        }
        assertTrue(state.ticket.items.isEmpty());
        assertTrue(state.ageCheck.active);
        assertEquals("EAN_QTY", state.ageCheck.kind);
        assertEquals(0, new BigDecimal("2").compareTo(state.ageCheck.quantity));
        verify(ticketPersistenceService, never()).syncDraft(state);
    }

    /**
     * {@code addItemByPlu} on an age-restricted product PARKS the gesture and
     * — the point of placing the gate BEFORE the scale read — NEVER WEIGHS:
     * the balance is consumed on the replay only, so a confirmed check
     * weighs exactly once.
     */
    @Test
    void addItemByPluParksRestrictedProductWithoutWeighing() {
        openSession();
        Product p = product("VIN ROUGE", null, "99");
        p.ageRestriction = 18;
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByPlu("99")).thenReturn(p);
            service.addItemByPlu(state, "99");
        }
        assertTrue(state.ageCheck.active);
        assertEquals("PLU", state.ageCheck.kind);
        assertEquals("99", state.ageCheck.code);
        assertNull(state.ageCheck.quantity);
        assertTrue(state.ticket.items.isEmpty());
        verify(hardwareService, never()).awaitWeighing(org.mockito.ArgumentMatchers.any());
    }

    // --- money products are never discounted ---

    /**
     * {@code applyRemise} REFUSES a money product: a gift card is value, not
     * goods — discounting it would mint money (money-product guard, true arm).
     */
    @Test
    void applyRemiseRefusesMoneyProduct() {
        TicketState.TicketItem card = line("CARTE CADEAU 25", new BigDecimal("25.00"), BigDecimal.ONE);
        card.moneyProduct = true;
        service.applyRemise(card, new BigDecimal("5.00"));
        assertEquals(0, new BigDecimal("25.00").compareTo(card.unitPrice));
        assertNull(card.modifierLabel);
    }

    /**
     * {@code applyDiscount} REFUSES a money product (same guard, percentage
     * flavour).
     */
    @Test
    void applyDiscountRefusesMoneyProduct() {
        TicketState.TicketItem card = line("CARTE CADEAU 25", new BigDecimal("25.00"), BigDecimal.ONE);
        card.moneyProduct = true;
        service.applyDiscount(card, new BigDecimal("10"));
        assertEquals(0, new BigDecimal("25.00").compareTo(card.unitPrice));
        assertNull(card.modifierLabel);
    }

    /**
     * {@code forcePrice} REFUSES a money product: forcing the price of an
     * instrument would break the equality between its face value and the
     * amount loaded on it.
     */
    @Test
    void forcePriceRefusesMoneyProduct() {
        TicketState.TicketItem card = line("CARTE CADEAU 25", new BigDecimal("25.00"), BigDecimal.ONE);
        card.moneyProduct = true;
        service.forcePrice(card, new BigDecimal("1.00"));
        assertEquals(0, new BigDecimal("25.00").compareTo(card.unitPrice));
        assertNull(card.modifierLabel);
    }

    // --- cancelItemById ---

    /**
     * {@code cancelItemById} removes a line, leaving the remaining last line
     * on the customer display (non-empty display arm).
     */
    @Test
    void cancelItemByIdShowsRemainingLastItem() {
        TicketState.TicketItem first = line("A", new BigDecimal("1.00"), BigDecimal.ONE);
        TicketState.TicketItem second = line("B", new BigDecimal("2.00"), BigDecimal.ONE);
        state.ticket.items.add(first);
        state.ticket.items.add(second);
        service.cancelItemById(state, second.uid);
        assertEquals(1, state.ticket.items.size());
        assertEquals(-1, state.selectedTicketIndex);
        verify(hardwareService).displayMessage(anyString());
        verify(ticketPersistenceService).syncDraft(state);
    }

    /**
     * {@code cancelItemById} removing the last remaining line shows the
     * welcome message (empty display arm).
     */
    @Test
    void cancelItemByIdEmptyShowsWelcome() {
        TicketState.TicketItem only = line("A", new BigDecimal("1.00"), BigDecimal.ONE);
        state.ticket.items.add(only);
        service.cancelItemById(state, only.uid);
        assertTrue(state.ticket.items.isEmpty());
        verify(hardwareService).displayMessage("INTERMARCHE");
    }

    /**
     * {@code cancelItemById} conserves the cancelled article (lot C4,
     * BO-04-01-16): with a persisted draft (non-null id arm), it marks the draft
     * line with the operator badge BEFORE removing it from the cart, so the
     * reconciliation keeps it as a witness.
     */
    @Test
    void cancelItemByIdMarksTheDraftLineWhenDraftExists() {
        TicketState.TicketItem only = line("A", new BigDecimal("1.00"), BigDecimal.ONE);
        state.ticket.items.add(only);
        state.payment.ticketDbId = 5L;
        state.auth.operatorBadgeId = "12341234";
        service.cancelItemById(state, only.uid);
        verify(ticketPersistenceService).markLineCancelled(5L, only.uid, "12341234");
        assertTrue(state.ticket.items.isEmpty());
    }

    /**
     * {@code cancelItemById} PARKS the line for the screen instead of erasing
     * it: it leaves the cart and the total, and stays available struck through
     * at the rank it held, so the operator and the customer can still point at
     * what was cancelled.
     */
    @Test
    void cancelItemByIdParksTheLineForTheScreen() {
        TicketState.TicketItem first = line("A", new BigDecimal("1.00"), BigDecimal.ONE);
        TicketState.TicketItem second = line("B", new BigDecimal("2.00"), BigDecimal.ONE);
        state.ticket.items.add(first);
        state.ticket.items.add(second);
        service.cancelItemById(state, first.uid);
        assertEquals(1, state.ticket.items.size());
        assertEquals(1, state.ticket.cancelledItems.size());
        assertTrue(state.ticket.cancelledItems.get(0).cancelled);
        assertEquals(2, state.ticket.displayItems().size());
    }

    /**
     * {@code cancelItemById} skips the marking when there is no draft yet
     * (null id arm, e.g. training or pre-first-article), and never touches the
     * persistence witness.
     */
    @Test
    void cancelItemByIdSkipsMarkingWithoutDraft() {
        TicketState.TicketItem only = line("A", new BigDecimal("1.00"), BigDecimal.ONE);
        state.ticket.items.add(only);
        state.payment.ticketDbId = null;
        service.cancelItemById(state, only.uid);
        verify(ticketPersistenceService, never()).markLineCancelled(anyLong(), anyString(), anyString());
    }

    // --- cancelTicket ---

    /**
     * {@code cancelTicket} cancels the draft when a draft id is present
     * (non-null arm) then clears the ticket.
     */
    @Test
    void cancelTicketWithDraftCancelsDraft() {
        state.payment.ticketDbId = 9L;
        state.ticket.items.add(line("A", new BigDecimal("1.00"), BigDecimal.ONE));
        service.cancelTicket(state);
        verify(ticketPersistenceService).cancelDraft(9L);
        assertTrue(state.ticket.items.isEmpty());
        verify(hardwareService).displayMessage("INTERMARCHE");
    }

    /**
     * {@code cancelTicket} without a draft id skips the draft cancellation
     * (null arm) and still clears the ticket.
     */
    @Test
    void cancelTicketWithoutDraftSkipsCancel() {
        state.payment.ticketDbId = null;
        state.ticket.items.add(line("A", new BigDecimal("1.00"), BigDecimal.ONE));
        service.cancelTicket(state);
        verify(ticketPersistenceService, never()).cancelDraft(anyLong());
        assertTrue(state.ticket.items.isEmpty());
        verify(hardwareService).displayMessage("INTERMARCHE");
    }

    // --- reprintTicket ---

    /**
     * {@code reprintTicket} prints when the id is present (non-null arm).
     */
    @Test
    void reprintTicketWithIdPrints() {
        service.reprintTicket(5L);
        verify(ticketPrinterService).printTicket(5L);
    }

    /**
     * {@code reprintTicket} does nothing on a null id (null arm).
     */
    @Test
    void reprintTicketNullIdDoesNothing() {
        service.reprintTicket(null);
        verifyNoInteractions(ticketPrinterService);
    }

    // --- printTicketIdentityBarcode (LC-08-01-04) ---

    /**
     * {@code printTicketIdentityBarcode} prints when the id is present (non-null arm).
     */
    @Test
    void printTicketIdentityBarcodeWithIdPrints() {
        service.printTicketIdentityBarcode(5L);
        verify(ticketPrinterService).printTicketIdentityBarcode(5L);
    }

    /**
     * {@code printTicketIdentityBarcode} does nothing on a null id (null arm).
     */
    @Test
    void printTicketIdentityBarcodeNullIdDoesNothing() {
        service.printTicketIdentityBarcode(null);
        verifyNoInteractions(ticketPrinterService);
    }

    // --- resolveLastClosedTicketId: the DERNIER keys survive a restart ---

    /**
     * The id already in memory is returned untouched and the database is never
     * asked (in-memory arm).
     */
    @Test
    void resolveLastClosedTicketIdKeepsTheOneInMemory() {
        PosState state = new PosState();
        state.lastClosedTicketId = 42L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertEquals(42L, service.resolveLastClosedTicketId(state));
            panache.verifyNoInteractions();
        }
    }

    /**
     * With nothing in memory — a register that restarted — the last CLOSED
     * ticket of this terminal is recovered from the database and remembered
     * (recovery arm).
     */
    @Test
    void resolveLastClosedTicketIdRecoversFromTheDatabase() {
        PosState state = new PosState();
        state.lastClosedTicketId = null;
        when(ticketNumberService.getTerminalId()).thenReturn("C04");
        Ticket closed = new Ticket();
        closed.id = 77L;
        try (MockedStatic<Ticket> tickets = mockStatic(Ticket.class)) {
            tickets.when(() -> Ticket.findLastClosedByTerminal("C04")).thenReturn(closed);
            assertEquals(77L, service.resolveLastClosedTicketId(state));
        }
        assertEquals(77L, state.lastClosedTicketId);
    }

    /**
     * A register that has closed no sale at all still answers null, and stores
     * nothing — the guard then says AUCUN TICKET, which is the truth
     * (nothing-found arm).
     */
    @Test
    void resolveLastClosedTicketIdStaysNullWhenNothingWasEverClosed() {
        PosState state = new PosState();
        state.lastClosedTicketId = null;
        when(ticketNumberService.getTerminalId()).thenReturn("C04");
        try (MockedStatic<Ticket> tickets = mockStatic(Ticket.class)) {
            tickets.when(() -> Ticket.findLastClosedByTerminal("C04")).thenReturn(null);
            assertNull(service.resolveLastClosedTicketId(state));
        }
        assertNull(state.lastClosedTicketId);
    }

    // --- printCardReceiptDuplicate (LC-08-05-09) ---

    /**
     * {@code printCardReceiptDuplicate} prints the slip with the DUPLICATA mention
     * when the id is present (non-null arm).
     */
    @Test
    void printCardReceiptDuplicateWithIdPrints() {
        when(ticketPrinterService.printCardReceipt(5L, false, "DUPLICATA")).thenReturn(1);
        assertEquals(1, service.printCardReceiptDuplicate(5L));
        verify(ticketPrinterService).printCardReceipt(5L, false, "DUPLICATA");
    }

    /**
     * {@code printCardReceiptDuplicate} passes the printer's count back, zero
     * included: a sale settled without a card prints nothing, and the caller is
     * what turns that into a message (printed-zero arm).
     */
    @Test
    void printCardReceiptDuplicateReportsNothingPrinted() {
        when(ticketPrinterService.printCardReceipt(5L, false, "DUPLICATA")).thenReturn(0);
        assertEquals(0, service.printCardReceiptDuplicate(5L));
    }

    /**
     * {@code printCardReceiptDuplicate} does nothing on a null id and reports
     * zero (null arm).
     */
    @Test
    void printCardReceiptDuplicateNullIdDoesNothing() {
        assertEquals(0, service.printCardReceiptDuplicate(null));
        verifyNoInteractions(ticketPrinterService);
    }

    // --- displayItem null arm (unreachable through the public API) ---

    /**
     * {@code displayItem(null)} shows the welcome message — the null arm is
     * unreachable through the public API and is covered reflectively.
     *
     * @throws Exception if the reflective invocation fails
     */
    @Test
    void displayItemNullShowsWelcome() throws Exception {
        Method m = TicketService.class.getDeclaredMethod("displayItem", TicketState.TicketItem.class);
        m.setAccessible(true);
        m.invoke(service, new Object[]{null});
        verify(hardwareService).displayMessage("INTERMARCHE");
    }

    // --- test scan handlers exercising getPriority ---

    /**
     * A chain handler that records the code it was handed, to prove a
     * confirmed SCAN gesture really re-enters the recognition chain.
     */
    static class RecordingHandler implements ScanContext.ScanHandler {

        /** The code seen by the chain, or null if never called. */
        String seenCode;

        /**
         * Records the scanned code and lets the chain continue.
         *
         * @param context the scan context
         */
        @Override
        public void handle(ScanContext context) {
            seenCode = context.code;
        }
    }

    /**
     * A scan handler carrying an explicit {@link Priority} annotation.
     */
    @Priority(5)
    static class PriorityHandler implements ScanContext.ScanHandler {
        /**
         * No-op handler.
         *
         * @param context the scan context
         */
        @Override
        public void handle(ScanContext context) {
        }
    }

    /**
     * A scan handler without any {@link Priority} annotation (default 100).
     */
    static class NoPriorityHandler implements ScanContext.ScanHandler {
        /**
         * No-op handler.
         *
         * @param context the scan context
         */
        @Override
        public void handle(ScanContext context) {
        }
    }

    /**
     * A scan handler whose class name contains {@code _ClientProxy} so the
     * CDI-proxy unwrap branch (second operand) is taken.
     */
    static class Marker_ClientProxy implements ScanContext.ScanHandler {
        /**
         * No-op handler.
         *
         * @param context the scan context
         */
        @Override
        public void handle(ScanContext context) {
        }
    }

    // ------------------------------------------------- Entry prompt (LC-02-03)

    /**
     * An article the referential prices and measures needs no prompt: the add goes
     * straight through — the arm where both attributes are absent.
     */
    @Test
    void suspendForEntryLetsAnOrdinaryArticleThrough() {
        Product p = product("MILK", "123", null);
        assertFalse(service.suspendForEntry(state, p, null));
        assertFalse(state.entryPrompt.active);
    }

    /**
     * An article whose quantity must be keyed parks the add on the QUANTITY prompt,
     * carrying its unit of measure and its price per unit ({@code LC-02-03-01/02}).
     */
    @Test
    void suspendForEntryAsksForADecimalQuantity() {
        Product p = product("CABLE", "123", null);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog
                .QUANTITY_TO_ENTER, "true");
        p.unitName = "m";
        try (MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(price("4.99", "0.20"));
            assertTrue(service.suspendForEntry(state, p, null));
        }
        assertTrue(state.entryPrompt.active);
        assertEquals(PosState.EntryPromptState.QUANTITY, state.entryPrompt.kind);
        assertEquals("123", state.entryPrompt.ean);
        assertEquals("CABLE", state.entryPrompt.label);
        assertEquals("m", state.entryPrompt.unitName);
        assertEquals("4,99", state.entryPrompt.unitPriceFormatted);
        assertEquals(0, BigDecimal.ONE.compareTo(state.entryPrompt.quantity));
    }

    /**
     * An article whose PRICE must be keyed parks on the PRICE prompt straight away —
     * the arm where the quantity is known and only the price is not
     * ({@code LC-02-03-03}).
     */
    @Test
    void suspendForEntryAsksForAPriceWhenOnlyThePriceIsMissing() {
        Product p = product("FLEURS", "123", null);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog
                .PRICE_TO_ENTER, "true");
        try (MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
            assertTrue(service.suspendForEntry(state, p, null));
        }
        assertEquals(PosState.EntryPromptState.PRICE, state.entryPrompt.kind);
        assertEquals("", state.entryPrompt.unitPriceFormatted);
        assertEquals("", state.entryPrompt.unitName);
    }

    /**
     * BO-03-13-10: in RETURN mode, an administered return type may ask for the
     * price of each returned line to be confirmed — the same PRICE prompt, on an
     * article the catalog prices perfectly well, with that price shown beside
     * the field so the cashier confirms or changes it.
     */
    @Test
    void aReturnTypeMayAskToConfirmThePriceGivenBack() {
        state.returnMode = true;
        state.returnTypeLabel = "Sans ticket";
        when(service.posListService.returnType("Sans ticket")).thenReturn(
                new com.intermarche.pos.service.PosSettingsService.ReturnType(
                        "Sans ticket", false, false, true, true));
        Product p = product("MILK", "123", null);
        try (MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            priceStatic.when(() -> Price.findCurrentPrice(anyLong()))
                    .thenReturn(price("1.15", "0.055"));
            assertTrue(service.suspendForEntry(state, p, null));
        }
        assertTrue(state.entryPrompt.active);
        assertEquals(PosState.EntryPromptState.PRICE, state.entryPrompt.kind);
        assertEquals("1,15", state.entryPrompt.unitPriceFormatted);
    }

    /**
     * The three legs that ask nothing: a SALE (whatever the type says), a return
     * whose type does not ask, and a return whose type the back office does not
     * name at all.
     */
    @Test
    void thePriceOfAReturnedLineIsNotAlwaysConfirmed() {
        Product p = product("MILK", "123", null);
        com.intermarche.pos.service.PosSettingsService.ReturnType asking =
                new com.intermarche.pos.service.PosSettingsService.ReturnType(
                        "Sans ticket", false, false, true, true);

        // A sale: the mode is off, so the type is never even consulted.
        state.returnMode = false;
        state.returnTypeLabel = "Sans ticket";
        when(service.posListService.returnType("Sans ticket")).thenReturn(asking);
        assertFalse(service.suspendForEntry(state, p, null));

        // A return whose type asks for nothing.
        state.returnMode = true;
        state.returnTypeLabel = "Contenant";
        when(service.posListService.returnType("Contenant")).thenReturn(
                new com.intermarche.pos.service.PosSettingsService.ReturnType("Contenant", false));
        assertFalse(service.suspendForEntry(state, p, null));

        // A return with no administered type behind the label.
        state.returnTypeLabel = "Inconnu";
        when(service.posListService.returnType("Inconnu")).thenReturn(null);
        assertFalse(service.suspendForEntry(state, p, null));
    }

    /**
     * A quantity already keyed or armed is carried INTO the prompt, so an article that
     * also wants its price is rung at the quantity the operator gave and not at one
     * ({@code LC-02-13-08}).
     */
    @Test
    void suspendForEntryCarriesTheQuantityTheOperatorAlreadyGave() {
        Product p = product("FLEURS", "123", null);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog
                .PRICE_TO_ENTER, "true");
        try (MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
            assertTrue(service.suspendForEntry(state, p, new BigDecimal("3")));
        }
        assertEquals(0, new BigDecimal("3").compareTo(state.entryPrompt.quantity));
    }

    /**
     * {@code confirmEntry} on a closed prompt does nothing: a stale page posting a
     * figure must not ring a line nobody asked for.
     */
    @Test
    void confirmEntryDoesNothingWhenNoPromptIsOpen() {
        service.confirmEntry(state, new BigDecimal("2"));
        assertTrue(state.ticket.items.isEmpty());
        assertNull(state.ticket.transientError);
    }

    /**
     * A figure that is not a figure is refused and the prompt stays open, on both arms
     * that can produce one: nothing keyed, and zero or less.
     */
    @Test
    void confirmEntryRefusesAFigureThatIsNotOne() {
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.QUANTITY;
        service.confirmEntry(state, null);
        assertEquals("VALEUR INVALIDE", state.ticket.transientError);
        assertTrue(state.entryPrompt.active);
        service.confirmEntry(state, BigDecimal.ZERO);
        assertTrue(state.entryPrompt.active);
        service.confirmEntry(state, new BigDecimal("-1"));
        assertTrue(state.entryPrompt.active);
        assertTrue(state.ticket.items.isEmpty());
    }

    /**
     * BO-02-03-06: a line rung through the entry prompt carries the meal-voucher
     * eligibility too, and an article the referential does not declare eligible
     * lands as ineligible — the two values, on the third snapshot site.
     */
    @Test
    void confirmEntrySnapshotsMealVoucherEligibility() {
        openSession();
        Product eligible = product("SANDWICH", "126", null);
        eligible.attributes.put(
                com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE,
                "true");
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.QUANTITY;
        state.entryPrompt.ean = "126";
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan("126")).thenReturn(eligible);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(price("4.99", "0.20"));
            service.confirmEntry(state, new BigDecimal("2"));
        }
        assertTrue(state.ticket.items.get(0).mealVoucherEligible);

        Product ordinary = product("VIN", "127", null);
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.QUANTITY;
        state.entryPrompt.ean = "127";
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan("127")).thenReturn(ordinary);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(price("4.99", "0.20"));
            service.confirmEntry(state, new BigDecimal("2"));
        }
        assertFalse(state.ticket.items.get(1).mealVoucherEligible);
    }

    /**
     * An article that vanished between the prompt and the answer closes the prompt and
     * says so, rather than ringing a line for nothing.
     */
    @Test
    void confirmEntryClosesThePromptWhenTheArticleIsGone() {
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.QUANTITY;
        state.entryPrompt.ean = "123";
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByEan("123")).thenReturn(null);
            service.confirmEntry(state, new BigDecimal("2"));
        }
        assertFalse(state.entryPrompt.active);
        assertEquals("PRODUIT INTROUVABLE", state.ticket.transientError);
        assertTrue(state.ticket.items.isEmpty());
    }

    /**
     * A keyed decimal quantity rings the line at the catalog price, carrying the unit
     * of measure onto it ({@code LC-02-03-01/02}).
     */
    @Test
    void confirmEntryRingsTheLineAtTheKeyedQuantity() {
        openSession();
        Product p = product("CABLE", "123", null);
        p.unitName = "m";
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.QUANTITY;
        state.entryPrompt.ean = "123";
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan("123")).thenReturn(p);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(price("4.99", "0.20"));
            service.confirmEntry(state, new BigDecimal("2.36"));
        }
        assertFalse(state.entryPrompt.active);
        assertEquals(1, state.ticket.items.size());
        TicketState.TicketItem line = state.ticket.items.get(0);
        assertEquals(0, new BigDecimal("2.36").compareTo(line.quantity));
        assertEquals(0, new BigDecimal("4.99").compareTo(line.unitPrice));
        assertEquals("m", line.unitName);
        verify(ticketPersistenceService).syncDraft(state);
        verify(valuationService).revalue(state);
    }

    /**
     * An article that wants BOTH figures asks the second one after the first, and rings
     * nothing until it has them ({@code LC-02-13-08}).
     */
    @Test
    void confirmEntryChainsTheQuantityIntoThePrice() {
        openSession();
        Product p = product("FLEURS", "123", null);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog
                .PRICE_TO_ENTER, "true");
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.QUANTITY;
        state.entryPrompt.ean = "123";
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByEan("123")).thenReturn(p);
            service.confirmEntry(state, new BigDecimal("3"));
            assertTrue(state.entryPrompt.active);
            assertEquals(PosState.EntryPromptState.PRICE, state.entryPrompt.kind);
            assertTrue(state.ticket.items.isEmpty());
            try (MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
                priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
                service.confirmEntry(state, new BigDecimal("12.50"));
            }
        }
        assertFalse(state.entryPrompt.active);
        TicketState.TicketItem line = state.ticket.items.get(0);
        assertEquals(0, new BigDecimal("3").compareTo(line.quantity));
        assertEquals(0, new BigDecimal("12.50").compareTo(line.unitPrice));
    }

    /**
     * A keyed price wins over the catalog's, which is the whole point of asking for it
     * ({@code LC-02-03-03}).
     */
    @Test
    void confirmEntryPrefersTheKeyedPriceToTheCatalogOne() {
        openSession();
        Product p = product("FLEURS", "123", null);
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.PRICE;
        state.entryPrompt.ean = "123";
        state.entryPrompt.quantity = new BigDecimal("2");
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan("123")).thenReturn(p);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(price("4.99", "0.055"));
            service.confirmEntry(state, new BigDecimal("12.50"));
        }
        TicketState.TicketItem line = state.ticket.items.get(0);
        assertEquals(0, new BigDecimal("12.50").compareTo(line.unitPrice));
        assertEquals(0, new BigDecimal("0.055").compareTo(line.vatRate));
    }

    /**
     * {@code cancelEntry} closes the prompt, registers nothing and says the add was
     * given up.
     */
    @Test
    void cancelEntryRegistersNothing() {
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.QUANTITY;
        state.entryPrompt.ean = "123";
        long version = state.version;
        service.cancelEntry(state);
        assertFalse(state.entryPrompt.active);
        assertNull(state.entryPrompt.ean);
        assertEquals("SAISIE ABANDONNÉE", state.ticket.transientError);
        assertTrue(state.ticket.items.isEmpty());
        assertTrue(state.version > version);
    }

    /**
     * The administered EURO ceiling refuses a line remise beyond it
     * (BO-03-07-04): the line keeps its price and its modifier.
     */
    @Test
    void applyRemiseRefusesAnAmountAboveTheAdministeredCap() {
        when(service.posSettingsService.lineMaxDiscountAmount()).thenReturn(new BigDecimal("10.00"));
        TicketState.TicketItem item = line("Pommes", new BigDecimal("20.00"), BigDecimal.ONE);
        service.applyRemise(item, new BigDecimal("10.01"));
        assertEquals(0, new BigDecimal("20.0000").compareTo(item.unitPrice));
        assertNull(item.modifierType);
    }

    /**
     * The administered EURO ceiling accepts a line remise exactly AT it
     * (BO-03-07-04, boundary): the minimum promised is honoured.
     */
    @Test
    void applyRemiseAcceptsAnAmountExactlyAtTheAdministeredCap() {
        when(service.posSettingsService.lineMaxDiscountAmount()).thenReturn(new BigDecimal("10.00"));
        TicketState.TicketItem item = line("Pommes", new BigDecimal("20.00"), BigDecimal.ONE);
        service.applyRemise(item, new BigDecimal("10.00"));
        assertEquals(0, new BigDecimal("10.0000").compareTo(item.unitPrice));
        assertEquals(PriceModType.REMISE, item.modifierType);
    }

    /**
     * A SECOND administered ceiling moves the refusal: what 10 € forbade, 20 €
     * allows. Two distinct administered values, so no literal can satisfy both.
     */
    @Test
    void applyRemiseFollowsTheAdministeredCapWhateverItIs() {
        when(service.posSettingsService.lineMaxDiscountAmount()).thenReturn(new BigDecimal("20.00"));
        TicketState.TicketItem item = line("Pommes", new BigDecimal("30.00"), BigDecimal.ONE);
        service.applyRemise(item, new BigDecimal("15.00"));
        assertEquals(PriceModType.REMISE, item.modifierType);
    }

    /**
     * A zero ceiling is no ceiling: the historical behaviour, kept as the
     * catalog default so an unadministered register refuses nothing new.
     */
    @Test
    void applyRemiseIsUncappedWhenTheAdministeredCapIsZero() {
        when(service.posSettingsService.lineMaxDiscountAmount()).thenReturn(BigDecimal.ZERO);
        TicketState.TicketItem item = line("Pommes", new BigDecimal("20.00"), BigDecimal.ONE);
        service.applyRemise(item, new BigDecimal("19.00"));
        assertEquals(PriceModType.REMISE, item.modifierType);
    }

    /**
     * The administered EURO ceiling refuses a TICKET remise beyond it
     * (BO-03-07-04).
     */
    @Test
    void applyGlobalDiscountRefusesEurosAboveTheAdministeredAmountCap() {
        when(service.posSettingsService.globalMaxDiscountAmount()).thenReturn(new BigDecimal("30.00"));
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_REMISE, new BigDecimal("30.01"));
        assertNull(state.ticket.globalDiscountType);
        assertEquals("REMISE SUPERIEURE AU PLAFOND", state.ticket.transientError);
    }

    /**
     * The administered EURO ceiling accepts a TICKET remise exactly AT it
     * (BO-03-07-04, boundary).
     */
    @Test
    void applyGlobalDiscountAcceptsEurosExactlyAtTheAdministeredAmountCap() {
        when(service.posSettingsService.globalMaxDiscountAmount()).thenReturn(new BigDecimal("30.00"));
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_REMISE, new BigDecimal("30.00"));
        assertEquals("AMOUNT", state.ticket.globalDiscountType);
    }

    /**
     * A SECOND administered ceiling moves the refusal on the ticket gesture too.
     */
    @Test
    void applyGlobalDiscountFollowsTheAdministeredAmountCapWhateverItIs() {
        when(service.posSettingsService.globalMaxDiscountAmount()).thenReturn(new BigDecimal("60.00"));
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_REMISE, new BigDecimal("50.00"));
        assertEquals("AMOUNT", state.ticket.globalDiscountType);
    }

    /**
     * The euro ceiling does NOT govern the percentage gesture, which keeps its
     * own cap: the two guards are independent.
     */
    @Test
    void theAmountCapDoesNotGovernThePercentageGesture() {
        when(service.posSettingsService.globalMaxDiscountAmount()).thenReturn(new BigDecimal("5.00"));
        when(service.posSettingsService.globalMaxDiscountPercent()).thenReturn(50);
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_DISCOUNT, new BigDecimal("40"));
        assertEquals("PERCENT", state.ticket.globalDiscountType);
    }

    // --- enrichment: remaining reachable branches ---

    /**
     * Builds a current price row that carries no VAT regime, so
     * {@link Price#vatRate()} answers null.
     *
     * @param incTax the price including tax
     * @return the wired price with a null VAT
     */
    private Price priceNoVat(String incTax) {
        Price pr = new Price();
        pr.priceIncludingTax = new BigDecimal(incTax);
        pr.vat = null;
        return pr;
    }

    /**
     * {@code suspendForEntry} formats an empty unit price when the catalog row
     * exists but its tax-included price is null — the
     * {@code price.priceIncludingTax == null} true arm of the format ternary.
     */
    @Test
    void suspendForEntryFormatsEmptyWhenPriceIncludingTaxIsNull() {
        Product p = product("CABLE", "123", null);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog
                .QUANTITY_TO_ENTER, "true");
        try (MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            Price pr = new Price();
            pr.priceIncludingTax = null;
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(pr);
            assertTrue(service.suspendForEntry(state, p, null));
        }
        assertEquals("", state.entryPrompt.unitPriceFormatted);
    }

    /**
     * {@code confirmEntry} on a quantity-only article with no catalog price
     * rings the line at a zero unit price and the default VAT rate — the
     * {@code keyedPrice == null} then {@code price == null} arms of
     * {@code addEnteredLine}.
     */
    @Test
    void confirmEntryWithoutKeyedNorCatalogPriceUsesZeroAndDefaultVat() {
        openSession();
        Product p = product("CABLE", "123", null);
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.QUANTITY;
        state.entryPrompt.ean = "123";
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan("123")).thenReturn(p);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
            service.confirmEntry(state, new BigDecimal("2"));
        }
        assertEquals(1, state.ticket.items.size());
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(added.unitPrice));
        assertEquals(0, new BigDecimal("0.20").compareTo(added.vatRate));
    }

    /**
     * {@code confirmEntry} on an article whose catalog price carries no VAT
     * regime falls back to the default VAT rate while keeping the catalog price
     * — the {@code price.vatRate() != null} false arm of {@code addEnteredLine},
     * price non-null.
     */
    @Test
    void confirmEntryPriceWithNullVatUsesDefault() {
        openSession();
        Product p = product("CABLE", "123", null);
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.QUANTITY;
        state.entryPrompt.ean = "123";
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan("123")).thenReturn(p);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(priceNoVat("4.99"));
            service.confirmEntry(state, new BigDecimal("2"));
        }
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals(0, new BigDecimal("4.99").compareTo(added.unitPrice));
        assertEquals(0, new BigDecimal("0.20").compareTo(added.vatRate));
    }

    /**
     * {@code confirmEntry} on a VAT-exempt article ventilates the entered line
     * at rate 0 — the {@code vatExempt} true arm of {@code addEnteredLine}.
     */
    @Test
    void confirmEntryVatExemptVentilatesAtZero() {
        openSession();
        Product p = product("CABLE", "123", null);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.VAT_EXEMPT, "true");
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.QUANTITY;
        state.entryPrompt.ean = "123";
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan("123")).thenReturn(p);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(price("4.99", "0.20"));
            service.confirmEntry(state, new BigDecimal("2"));
        }
        assertEquals(0, BigDecimal.ZERO.compareTo(state.ticket.items.get(0).vatRate));
    }

    /**
     * {@code confirmEntry} flags a gift-card article rung through the prompt as
     * a money product — the {@code giftCardAmount != null} true arm of
     * {@code addEnteredLine}.
     */
    @Test
    void confirmEntryFlagsGiftCardAsMoneyProduct() {
        openSession();
        Product p = product("CARTE CADEAU", "123", null);
        p.giftCardAmount = new BigDecimal("25.00");
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.QUANTITY;
        state.entryPrompt.ean = "123";
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan("123")).thenReturn(p);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(price("25.00", "0.20"));
            service.confirmEntry(state, new BigDecimal("1"));
        }
        assertTrue(state.ticket.items.get(0).moneyProduct);
    }

    /**
     * {@code confirmEntry} snapshots a discount ban onto the entered line — the
     * {@code discountForbidden} true arm of {@code addEnteredLine}.
     */
    @Test
    void confirmEntrySnapshotsDiscountForbidden() {
        openSession();
        Product p = product("CABLE", "123", null);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.DISCOUNT_FORBIDDEN, "true");
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.QUANTITY;
        state.entryPrompt.ean = "123";
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan("123")).thenReturn(p);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(price("4.99", "0.20"));
            service.confirmEntry(state, new BigDecimal("2"));
        }
        assertTrue(state.ticket.items.get(0).discountForbidden);
    }

    /**
     * {@code addItemByEan} on an article whose price must be entered suspends
     * the add behind the entry prompt and rings nothing — the true arm of the
     * {@code suspendForEntry} call site.
     */
    @Test
    void addItemByEanSuspendsForEntry() {
        openSession();
        Product p = product("CABLE", "123", null);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.PRICE_TO_ENTER, "true");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
            service.addItemByEan(state, "123", BigDecimal.ONE);
        }
        assertTrue(state.entryPrompt.active);
        assertTrue(state.ticket.items.isEmpty());
        verify(ticketPersistenceService, never()).syncDraft(state);
    }

    /**
     * {@code addItemByEan} with a current price carrying no VAT regime falls
     * back to the default VAT rate — the {@code price.vatRate() != null} false
     * arm of the VAT ternary, price non-null.
     */
    @Test
    void addItemByEanPriceWithNullVatUsesDefault() {
        openSession();
        Product p = product("MILK", "123", null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(priceNoVat("1.50"));
            service.addItemByEan(state, "123", BigDecimal.ONE);
        }
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals(0, new BigDecimal("1.50").compareTo(added.unitPrice));
        assertEquals(0, new BigDecimal("0.20").compareTo(added.vatRate));
    }

    /**
     * {@code addItemByEan} lists the recalled lots of a registered article once
     * the line is rung — the {@code message != null} true arm of
     * {@code noticeRecalledLots}.
     */
    @Test
    void addItemByEanNoticesRecalledLots() {
        openSession();
        Product p = product("MILK", "123", null);
        p.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.RECALL_LOTS, "LOT1,LOT2");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
            service.addItemByEan(state, "123", BigDecimal.ONE);
        }
        assertEquals(1, state.ticket.items.size());
        assertEquals("PRODUIT EN RAPPEL - LOTS : LOT1, LOT2", state.ticket.transientError);
        assertTrue(state.ticket.transientOk);
    }

    /**
     * {@code addItemByPlu} with a weighed price carrying no VAT regime falls
     * back to the default VAT rate — the {@code price.vatRate() != null} false
     * arm of the PLU VAT ternary, price non-null.
     */
    @Test
    void addItemByPluPriceWithNullVatUsesDefault() {
        openSession();
        Product p = product("APPLE", "111", "1234");
        when(hardwareService.awaitWeighing(org.mockito.ArgumentMatchers.any())).thenReturn(1.5);
        try (MockedStatic<Product> productStatic = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            productStatic.when(() -> Product.findActiveByPlu("1234")).thenReturn(p);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(priceNoVat("3.00"));
            service.addItemByPlu(state, "1234");
        }
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals(0, new BigDecimal("3.00").compareTo(added.unitPrice));
        assertEquals(0, new BigDecimal("0.20").compareTo(added.vatRate));
    }

    /**
     * {@code cancelTicket} prints the abandon ticket BEFORE cancelling the draft
     * when the abandon rules ask for it — the {@code printsTicket()} true arm.
     */
    @Test
    void cancelTicketPrintsAbandonTicketWhenConfigured() {
        state.payment.ticketDbId = 9L;
        state.auth.operatorName = "Marie";
        state.abandonReason = "Client parti";
        when(service.ticketAbandonService.printsTicket()).thenReturn(true);
        when(service.posSettingsService.abandonPrintDetail()).thenReturn(true);
        service.cancelTicket(state);
        verify(ticketPrinterService).printAbandonTicket(9L, "Client parti", true, "Marie");
        verify(ticketPersistenceService).cancelDraft(9L);
    }

    /**
     * {@code cancelTicket} journals a plain abandon when the reason is null —
     * the {@code abandonReason == null} true arm (the state default is the
     * empty string, so a genuine null is set explicitly here).
     */
    @Test
    void cancelTicketJournalsPlainAbandonWhenReasonNull() {
        state.payment.ticketDbId = null;
        state.abandonReason = null;
        service.cancelTicket(state);
        verify(technicalEventService).log(
                TechnicalEvent.EventType.TICKET_CANCELLED, "Abandon ticket");
    }

    /**
     * {@code cancelTicket} journals a plain abandon when the reason is present
     * but blank — the {@code abandonReason == null} false arm followed by the
     * {@code isBlank()} true arm.
     */
    @Test
    void cancelTicketJournalsPlainAbandonWhenReasonBlank() {
        state.payment.ticketDbId = null;
        state.abandonReason = "   ";
        service.cancelTicket(state);
        verify(technicalEventService).log(
                TechnicalEvent.EventType.TICKET_CANCELLED, "Abandon ticket");
    }

    /**
     * {@code cancelTicket} journals the reason when it is present and not blank
     * — the {@code abandonReason == null} false arm followed by the
     * {@code isBlank()} false arm.
     */
    @Test
    void cancelTicketJournalsReasonWhenPresent() {
        state.payment.ticketDbId = null;
        state.abandonReason = "Erreur caisse";
        service.cancelTicket(state);
        verify(technicalEventService).log(
                TechnicalEvent.EventType.TICKET_CANCELLED, "Abandon ticket : Erreur caisse");
    }


    // --------------------------------------------------
    // Restored warn-on-expensive-entry suite (main feature)
    // --------------------------------------------------

    /**
     * Drives the post-mutation funnel the way an entry does: a line is added
     * through the {@code addItem} funnel (which raises the entry flag on the
     * state), then a scan with an empty handler chain runs the chain and the
     * trailing sync.
     *
     * @param label the line label
     * @param unitPrice the unit price of the line
     */
    private void enterLine(String label, String unitPrice) {
        enterLine(label, unitPrice, null);
    }

    /**
     * Drives the same funnel with an administered ARTICLE MESSAGE answered by
     * the catalog (BO-03-08-09).
     *
     * <p>The lookup is a Panache static, so it is intercepted here: the entry
     * funnel ALWAYS reaches it — the trailing sync asks the catalog for the
     * message of the line just entered — and a plain unit test has no
     * persistence context to answer with.
     *
     * @param label the line label
     * @param unitPrice the unit price of the line
     * @param infoMessage the message the catalog carries for that EAN, or null
     */
    private void enterLine(String label, String unitPrice, String infoMessage) {
        state.auth.isLocked = false;
        when(cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(scanHandlers.spliterator())
                .thenReturn(List.<ScanContext.ScanHandler>of().spliterator());
        com.intermarche.pos.domain.catalog.Product product = null;
        if (infoMessage != null) {
            product = new com.intermarche.pos.domain.catalog.Product();
            product.ean = "3000000000021";
            product.infoMessage = infoMessage;
        }
        @SuppressWarnings("unchecked")
        io.quarkus.hibernate.orm.panache.PanacheQuery<
                com.intermarche.pos.domain.catalog.Product> found =
                        mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        final com.intermarche.pos.domain.catalog.Product answered = product;
        when(found.firstResult()).thenReturn(answered);
        state.ticket.addItem("3000000000021", null, label,
                new BigDecimal(unitPrice), BigDecimal.ONE, new BigDecimal("0.20"));
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            panache.when(() -> com.intermarche.pos.domain.catalog.Product
                    .find("ean", "3000000000021")).thenReturn(found);
            service.processScan("3000000000021");
        }
    }

    /**
     * An article dearer than the threshold warns: the message names the line
     * and its price, and the zone is drawn as a WARNING — neither a refusal
     * nor a confirmation.
     */
    @Test
    void anArticleAboveTheThresholdWarns() {
        when(service.posSettingsService.saleLineWarningThreshold()).thenReturn(new BigDecimal("10.00"));
        enterLine("WHISKY 70CL", "24.90");
        assertEquals("PRIX ÉLEVÉ : WHISKY 70CL 24,90 €", state.ticket.transientError);
        assertTrue(state.ticket.transientWarn);
        assertFalse(state.ticket.transientOk);
    }

    /**
     * An article exactly AT the threshold does not warn: the comparison is
     * strictly above, like every other administered ceiling of this register.
     */
    @Test
    void anArticleAtTheThresholdDoesNotWarn() {
        when(service.posSettingsService.saleLineWarningThreshold()).thenReturn(new BigDecimal("10.00"));
        enterLine("LIVRE", "10.00");
        assertNull(state.ticket.transientError);
    }

    /**
     * An article below the threshold does not warn.
     */
    @Test
    void anArticleBelowTheThresholdDoesNotWarn() {
        when(service.posSettingsService.saleLineWarningThreshold()).thenReturn(new BigDecimal("10.00"));
        enterLine("PAIN", "1.20");
        assertNull(state.ticket.transientError);
    }

    /**
     * BO-03-08-09: the message administered on the article is shown when the
     * line is entered, trimmed, and drawn as a WARNING.
     */
    @Test
    void anArticleCarryingAMessageShowsIt() {
        enterLine("CHARBON 3KG", "4.90", "  VENTE INTERDITE AUX MINEURS  ");
        assertEquals("VENTE INTERDITE AUX MINEURS", state.ticket.transientError);
        assertTrue(state.ticket.transientWarn);
    }

    /**
     * BO-03-10-13: an island that blocks the article pop-ups silences BOTH
     * informative messages — the article's own and the high-price alert.
     */
    @Test
    void anIslandBlockingArticlePopupsSilencesThem() {
        when(service.posSettingsService.islandBlockArticlePopups()).thenReturn(true);
        when(service.posSettingsService.saleLineWarningThreshold()).thenReturn(new BigDecimal("10.00"));
        enterLine("WHISKY 70CL", "24.90", "VENTE INTERDITE AUX MINEURS");
        assertNull(state.ticket.transientError);
        assertFalse(state.ticket.transientWarn);
    }

    /**
     * A threshold at zero switches the warning off — the disabled arm.
     */
    @Test
    void aZeroThresholdWarnsAboutNothing() {
        when(service.posSettingsService.saleLineWarningThreshold()).thenReturn(BigDecimal.ZERO);
        enterLine("WHISKY 70CL", "24.90");
        assertNull(state.ticket.transientError);
    }

    /**
     * A negative threshold is treated as disabled too — the other leg of the
     * {@code signum() <= 0} guard, which a zero alone leaves uncovered.
     */
    @Test
    void aNegativeThresholdWarnsAboutNothing() {
        when(service.posSettingsService.saleLineWarningThreshold()).thenReturn(new BigDecimal("-1.00"));
        enterLine("WHISKY 70CL", "24.90");
        assertNull(state.ticket.transientError);
    }

    /**
     * An unreadable setting (null) disables the warning rather than faulting
     * the entry — the null leg of the same guard.
     */
    @Test
    void aNullThresholdWarnsAboutNothing() {
        when(service.posSettingsService.saleLineWarningThreshold()).thenReturn(null);
        enterLine("WHISKY 70CL", "24.90");
        assertNull(state.ticket.transientError);
    }

    /**
     * A refusal already in the zone is never spoken over: the message that
     * stopped a gesture outranks the one that comments on it.
     */
    @Test
    void aWarningNeverSpeaksOverARefusal() {
        when(service.posSettingsService.saleLineWarningThreshold()).thenReturn(new BigDecimal("10.00"));
        state.auth.isLocked = false;
        when(cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(scanHandlers.spliterator()).thenReturn(List.<ScanContext.ScanHandler>of(
                new ErrorHandler()).spliterator());
        state.ticket.addItem("3000000000021", null, "WHISKY 70CL",
                new BigDecimal("24.90"), BigDecimal.ONE, new BigDecimal("0.20"));
        service.processScan("3000000000021");
        assertEquals("REFUS", state.ticket.transientError);
        assertFalse(state.ticket.transientWarn);
    }

    /**
     * The entry flag is CONSUMED even when nothing is warned about, so a
     * later gesture on the same cart — a discount, a quantity change — does
     * not replay the warning of an entry already commented on.
     */
    @Test
    void theEntryFlagIsConsumedSoAFollowingGestureDoesNotReplayIt() {
        when(service.posSettingsService.saleLineWarningThreshold()).thenReturn(new BigDecimal("10.00"));
        enterLine("WHISKY 70CL", "24.90");
        state.ticket.transientError = null;
        state.ticket.transientWarn = false;
        service.processScan("3000000000021");
        assertNull(state.ticket.transientError);
        assertFalse(state.lineJustEntered);
    }

    /**
     * Entering the same dear article twice warns twice: the second entry
     * merges into the line already there, and a merge is an entry — the arm
     * a uid comparison would have missed.
     */
    @Test
    void enteringTheSameDearArticleTwiceWarnsTwice() {
        when(service.posSettingsService.saleLineWarningThreshold()).thenReturn(new BigDecimal("10.00"));
        enterLine("WHISKY 70CL", "24.90");
        assertTrue(state.ticket.transientWarn);
        state.ticket.transientError = null;
        state.ticket.transientWarn = false;
        state.ticket.addItem("3000000000021", null, "WHISKY 70CL",
                new BigDecimal("24.90"), BigDecimal.ONE, new BigDecimal("0.20"));
        service.processScan("3000000000021");
        assertEquals(1, state.ticket.items.size());
        assertTrue(state.ticket.transientWarn);
    }

    /**
     * An entered line that is no longer in the ticket warns about nothing —
     * the null arm of the lookup, reached when the line was removed between
     * the entry and the sync.
     */
    @Test
    void anEnteredLineNoLongerPresentWarnsAboutNothing() {
        when(service.posSettingsService.saleLineWarningThreshold()).thenReturn(new BigDecimal("10.00"));
        state.auth.isLocked = false;
        when(cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(scanHandlers.spliterator())
                .thenReturn(List.<ScanContext.ScanHandler>of().spliterator());
        state.ticket.addItem("3000000000021", null, "WHISKY 70CL",
                new BigDecimal("24.90"), BigDecimal.ONE, new BigDecimal("0.20"));
        String uid = state.lastEnteredItemId;
        state.ticket.addItem("3000000000038", null, "PAIN",
                new BigDecimal("1.20"), BigDecimal.ONE, new BigDecimal("0.055"));
        state.ticket.removeItemById(uid);
        state.lastEnteredItemId = uid;
        service.processScan("3000000000021");
        assertNull(state.ticket.transientError);
    }

    /**
     * No recorded entry at all — a sync driven by something other than an
     * entry — warns about nothing, and the setting is not even consulted.
     */
    @Test
    void aSyncWithoutAnEntryWarnsAboutNothing() {
        when(service.posSettingsService.saleLineWarningThreshold()).thenReturn(new BigDecimal("10.00"));
        state.auth.isLocked = false;
        when(cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(scanHandlers.spliterator())
                .thenReturn(List.<ScanContext.ScanHandler>of().spliterator());
        state.ticket.addItem("3000000000038", null, "PAIN",
                new BigDecimal("1.20"), BigDecimal.ONE, new BigDecimal("0.055"));
        state.lineJustEntered = false;
        state.lastEnteredItemId = null;
        service.processScan("3000000000038");
        assertNull(state.ticket.transientError);
    }

    /** A handler that refuses the scan, to occupy the message zone. */
    private static class ErrorHandler implements ScanContext.ScanHandler {
        /**
         * Stamps a refusal on the ticket.
         *
         * @param ctx the scan context
         */
        @Override
        public void handle(ScanContext ctx) {
            ctx.state.ticket.setError("REFUS");
            ctx.handled = true;
        }
    }

    // --- addFamilyPricedItem (EXP-24) ---

    /**
     * Builds a product family with a code and description (EXP-24).
     *
     * @param code the family code
     * @param description the family label
     * @return the wired family
     */
    private com.intermarche.pos.domain.catalog.ProductFamily family(String code, String description) {
        com.intermarche.pos.domain.catalog.ProductFamily f =
                new com.intermarche.pos.domain.catalog.ProductFamily();
        f.code = code;
        f.description = description;
        return f;
    }

    /**
     * {@code addFamilyPricedItem} rings a free-price line under a family code
     * (EXP-24): the family label is upper-cased onto the line, the keyed price is
     * applied and the register default VAT is used (family found + price >= 0).
     */
    @Test
    void addFamilyPricedItemRingsLabelAndPrice() {
        openSession();
        try (MockedStatic<com.intermarche.pos.domain.catalog.ProductFamily> families =
                mockStatic(com.intermarche.pos.domain.catalog.ProductFamily.class)) {
            families.when(() -> com.intermarche.pos.domain.catalog.ProductFamily.findByCode("80"))
                    .thenReturn(family("80", "Epicerie"));
            service.addFamilyPricedItem(state, "80", "2,35");
            assertEquals(1, state.ticket.items.size());
            TicketState.TicketItem added = state.ticket.items.get(0);
            assertEquals("EPICERIE", added.label);
            assertEquals(0, new BigDecimal("2.35").compareTo(added.unitPrice));
            assertEquals(0, new BigDecimal("0.20").compareTo(added.vatRate));
        }
    }

    /**
     * {@code addFamilyPricedItem} refuses an unknown family code (EXP-24): the
     * family-null arm sets FAMILLE INCONNUE and adds no line.
     */
    @Test
    void addFamilyPricedItemRefusesUnknownFamily() {
        openSession();
        try (MockedStatic<com.intermarche.pos.domain.catalog.ProductFamily> families =
                mockStatic(com.intermarche.pos.domain.catalog.ProductFamily.class)) {
            families.when(() -> com.intermarche.pos.domain.catalog.ProductFamily.findByCode("99"))
                    .thenReturn(null);
            service.addFamilyPricedItem(state, "99", "2,35");
            assertTrue(state.ticket.items.isEmpty());
            assertEquals("FAMILLE INCONNUE", state.ticket.transientError);
        }
    }

    /**
     * {@code addFamilyPricedItem} treats a blank family code as unknown (EXP-24):
     * the {@code isBlank()} guard arm short-circuits the lookup to null.
     */
    @Test
    void addFamilyPricedItemRefusesBlankFamilyCode() {
        openSession();
        service.addFamilyPricedItem(state, "  ", "2,35");
        assertTrue(state.ticket.items.isEmpty());
        assertEquals("FAMILLE INCONNUE", state.ticket.transientError);
    }

    /**
     * {@code addFamilyPricedItem} refuses a negative price (EXP-24): the
     * {@code signum() >= 0} false arm sets ERREUR PRIX SAISI, no line.
     */
    @Test
    void addFamilyPricedItemRefusesNegativePrice() {
        openSession();
        try (MockedStatic<com.intermarche.pos.domain.catalog.ProductFamily> families =
                mockStatic(com.intermarche.pos.domain.catalog.ProductFamily.class)) {
            families.when(() -> com.intermarche.pos.domain.catalog.ProductFamily.findByCode("80"))
                    .thenReturn(family("80", "Epicerie"));
            service.addFamilyPricedItem(state, "80", "-1,00");
            assertTrue(state.ticket.items.isEmpty());
            assertEquals("ERREUR PRIX SAISI", state.ticket.transientError);
        }
    }

    /**
     * {@code addFamilyPricedItem} refuses an unparsable price (EXP-24): the catch
     * arm sets ERREUR PRIX SAISI, no line.
     */
    @Test
    void addFamilyPricedItemRefusesUnparsablePrice() {
        openSession();
        try (MockedStatic<com.intermarche.pos.domain.catalog.ProductFamily> families =
                mockStatic(com.intermarche.pos.domain.catalog.ProductFamily.class)) {
            families.when(() -> com.intermarche.pos.domain.catalog.ProductFamily.findByCode("80"))
                    .thenReturn(family("80", "Epicerie"));
            service.addFamilyPricedItem(state, "80", "abc");
            assertTrue(state.ticket.items.isEmpty());
            assertEquals("ERREUR PRIX SAISI", state.ticket.transientError);
        }
    }

    /**
     * {@code addFamilyPricedItem} does nothing without an open session (EXP-24):
     * the session gate returns before any family lookup.
     */
    @Test
    void addFamilyPricedItemNeedsOpenSession() {
        when(cashSessionService.getOpenSession()).thenReturn(null);
        service.addFamilyPricedItem(state, "80", "2,35");
        assertTrue(state.ticket.items.isEmpty());
    }

    // --------------------------------------------------
    // Campaign 2 — retours/warning added branches
    // --------------------------------------------------

    /**
     * The cancel-by-scan interception revalues when the ticket still carries
     * lines (the {@code !items.isEmpty()} true leg of the sync guard), and the
     * expensive-entry warning finds no entered line when
     * {@code lastEnteredItemId} is null — the state a SOUS-TOTAL marker leaves
     * behind (the {@code lastEnteredItemId == null} true leg). No warning is
     * raised.
     */
    @Test
    void cancelByScanRevaluesWithLinesAndTheWarningFindsNoEnteredLine() {
        state.auth.isLocked = false;
        openSession();
        when(service.posSettingsService.saleLineWarningThreshold()).thenReturn(new BigDecimal("10.00"));
        state.cancelByScanArmed = true;
        state.ticket.addItem("3000000000038", null, "PAIN",
                new BigDecimal("2.00"), BigDecimal.ONE, new BigDecimal("0.055"));
        state.lastEnteredItemId = null;
        service.processScan("3000000000038");
        verify(service.homeService).cancelByScan("3000000000038");
        verify(valuationService).revalue(state);
        assertNull(state.ticket.transientError);
    }

    /**
     * The cancel-by-scan interception revalues an emptied but persisted ticket
     * (the {@code !items.isEmpty()} false leg and the {@code ticketDbId != null}
     * true leg of the sync guard): the draft id outlives the last line, so the
     * cancellation must still reach the server.
     */
    @Test
    void cancelByScanRevaluesAnEmptiedPersistedTicket() {
        state.auth.isLocked = false;
        openSession();
        state.cancelByScanArmed = true;
        state.payment.ticketDbId = 5L;
        service.processScan("3000000000038");
        verify(service.homeService).cancelByScan("3000000000038");
        verify(valuationService).revalue(state);
    }

    /**
     * The expensive-entry warning says nothing when the entered line carries no
     * unit price (the {@code entered.unitPrice == null} true leg): the price is a
     * public, unguarded field, so a line can reach the warning with none, and the
     * guard spares the later {@code setScale} a null dereference.
     */
    @Test
    void theWarningSaysNothingWhenTheEnteredLineHasNoUnitPrice() {
        state.auth.isLocked = false;
        openSession();
        when(service.posSettingsService.saleLineWarningThreshold()).thenReturn(new BigDecimal("10.00"));
        state.cancelByScanArmed = true;
        state.ticket.addItem("3000000000038", null, "PAIN",
                new BigDecimal("2.00"), BigDecimal.ONE, new BigDecimal("0.055"));
        TicketState.TicketItem entered =
                state.ticket.items.get(state.ticket.items.size() - 1);
        entered.unitPrice = null;
        state.lastEnteredItemId = entered.uid;
        service.processScan("3000000000038");
        verify(valuationService).revalue(state);
        assertNull(state.ticket.transientError);
    }

    /**
     * {@code addFamilyPricedItem} clears a pending transient error before ringing
     * (the {@code transientError != null} true leg): a free-price entry starts
     * from a clean zone, so the family line is not shadowed by a stale message.
     */
    @Test
    void addFamilyPricedItemClearsAPendingTransientError() {
        openSession();
        state.ticket.transientError = "old";
        try (MockedStatic<com.intermarche.pos.domain.catalog.ProductFamily> families =
                mockStatic(com.intermarche.pos.domain.catalog.ProductFamily.class)) {
            families.when(() -> com.intermarche.pos.domain.catalog.ProductFamily.findByCode("80"))
                    .thenReturn(family("80", "Epicerie"));
            service.addFamilyPricedItem(state, "80", "2,35");
            assertEquals(1, state.ticket.items.size());
            assertNull(state.ticket.transientError);
        }
    }

    /**
     * {@code addFamilyPricedItem} treats a null family code as unknown (the
     * {@code familyCode == null} true leg of the guard): the lookup is
     * short-circuited to null and FAMILLE INCONNUE is set, no line added.
     */
    @Test
    void addFamilyPricedItemRefusesNullFamilyCode() {
        openSession();
        service.addFamilyPricedItem(state, null, "2,35");
        assertTrue(state.ticket.items.isEmpty());
        assertEquals("FAMILLE INCONNUE", state.ticket.transientError);
    }

    /**
     * {@code addFamilyPricedItem} falls back to the family code as the line label
     * when the family has no description (the {@code description == null} true arm
     * of the label ternary).
     */
    @Test
    void addFamilyPricedItemLabelsWithTheCodeWhenNoDescription() {
        openSession();
        try (MockedStatic<com.intermarche.pos.domain.catalog.ProductFamily> families =
                mockStatic(com.intermarche.pos.domain.catalog.ProductFamily.class)) {
            families.when(() -> com.intermarche.pos.domain.catalog.ProductFamily.findByCode("80"))
                    .thenReturn(family("80", null));
            service.addFamilyPricedItem(state, "80", "2,35");
            assertEquals(1, state.ticket.items.size());
            assertEquals("80", state.ticket.items.get(0).label);
        }
    }

    /**
     * {@code loadRecalledDocument} refuses a document whose line list is null (the
     * {@code doc.lines == null} true leg of the emptiness guard) with DOCUMENT
     * VIDE, loading nothing. The null is set on the public field through the
     * no-arg constructor, the way the back-office client populates a document
     * after a fetch — the 4-arg constructor would coerce it to an empty list.
     */
    @Test
    void loadRecalledDocumentRefusesNullLines() {
        openSession();
        com.intermarche.pos.ui.recall.RecalledDocument doc =
                new com.intermarche.pos.ui.recall.RecalledDocument();
        doc.kind = com.intermarche.pos.ui.recall.RecalledDocument.Kind.DEVIS;
        doc.code = "D1";
        doc.lines = null;
        service.loadRecalledDocument(state, doc);
        assertTrue(state.ticket.items.isEmpty());
        assertEquals("DOCUMENT VIDE", state.ticket.transientError);
    }

    /**
     * {@code loadRecalledDocument} loads a document carrying no code, resolving
     * the {@code doc.code == null ? "" :} ternary to its empty-string arm so the
     * notice reads the kind and CHARGÉ without a code.
     */
    @Test
    void loadRecalledDocumentLoadsADocumentWithoutACode() {
        openSession();
        com.intermarche.pos.ui.recall.RecalledDocument doc =
                new com.intermarche.pos.ui.recall.RecalledDocument(
                        com.intermarche.pos.ui.recall.RecalledDocument.Kind.COMMANDE, null,
                        java.util.List.of(new com.intermarche.pos.ui.recall.RecalledDocument.Line(
                                "300", "PAIN", BigDecimal.ONE, new BigDecimal("2.00"), new BigDecimal("0.055"))),
                        BigDecimal.ZERO);
        service.loadRecalledDocument(state, doc);
        assertEquals(1, state.ticket.items.size());
        assertTrue(state.ticket.transientError.contains("CHARGÉ"));
    }

    /**
     * BO-10-02-15: an article rung while a settlement is under way is refused
     * and said, unless the shop allows the gesture.
     */
    @Test
    void saleRefusedDuringPaymentFollowsTheAdministeredRule() {
        PosState state = mock(PosState.class);
        state.ticket = mock(com.intermarche.pos.ui.ticket.TicketState.class);
        state.payment = mock(com.intermarche.pos.ui.payment.PaymentState.class);
        // Nothing under way: nothing refused, whatever the rule says.
        state.payment.paymentInProgress = false;
        assertFalse(service.saleRefusedDuringPayment(state));
        // Under way and forbidden — the mock's false IS the catalogue default.
        state.payment.paymentInProgress = true;
        assertTrue(service.saleRefusedDuringPayment(state));
        verify(state.ticket).setError("VENTE INTERDITE PENDANT LE REGLEMENT");
        // Under way and allowed: the article goes through, nothing said.
        when(service.posSettingsService.saleAllowedDuringPayment()).thenReturn(true);
        assertFalse(service.saleRefusedDuringPayment(state));
        verify(state.ticket, times(1)).setError("VENTE INTERDITE PENDANT LE REGLEMENT");
    }
}
