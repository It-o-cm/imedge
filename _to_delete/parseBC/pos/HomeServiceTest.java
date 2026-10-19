package com.intermarche.pos.ui.home;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.PriceModState;
import com.intermarche.pos.ui.PriceModType;
import com.intermarche.pos.ui.endorsement.ControlLevel;
import com.intermarche.pos.ui.endorsement.ControlPoint;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.ui.payment.PaymentState;
import com.intermarche.pos.ui.ticket.TicketService;
import com.intermarche.pos.ui.ticket.TicketState;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HomeService}.
 * <p>
 * Every collaborator is a Mockito mock. {@link PosState} carries plain public
 * fields ({@code selectedTicketIndex}, {@code lastEnteredItemId},
 * {@code trainingMode}, {@code lastClosedTicketId}) that the tests set directly
 * on the mock, plus decision methods ({@code getTargetItem()},
 * {@code getOperatorName()}) that are stubbed; its {@code ticket},
 * {@code priceModState} and {@code payment} sub-states are mocked so no direct
 * field access hits a null, and {@code ticket.items} is backed by a real list
 * carrying real {@link TicketState.TicketItem} lines. The private static final
 * {@code SUPERVISOR_CLIENT} is swapped with a mock {@link HttpClient} through
 * {@code sun.misc.Unsafe} for the real-time supervisor-call branches, then
 * restored after each test so the class stays isolated. Tests assert absolute
 * expected values and verify delegation, covering both arms of every guard,
 * ternary, compound condition and {@code null}/empty short-circuit.
 */
class HomeServiceTest {

    /** The service under test, rebuilt fresh for each test. */
    private HomeService service;

    /** The original supervisor HTTP client, restored after each test. */
    private HttpClient originalClient;

    /**
     * Builds a {@link HomeService} whose collaborators are fresh mocks wired
     * onto its package-private fields, including the {@link PosState} sub-state
     * holders and a real {@code ticket.items} list. Captures the original
     * static supervisor client for later restoration.
     *
     * @throws Exception if the static client field cannot be read
     */
    @BeforeEach
    void setUp() throws Exception {
        service = new HomeService();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        service.i18n = TestMessages.french();
        service.state = mock(PosState.class);
        service.state.ticket = mock(TicketState.class);
        service.state.ticket.items = new ArrayList<>();
        service.state.priceModState = mock(PriceModState.class);
        service.state.payment = mock(PaymentState.class);
        service.ticketService = mock(TicketService.class);
        // The tare resolver is a stand-in and nothing here stubs it: the quantity
        // key no longer types weights, so no case of this class consults it.
        service.tareService = mock(com.intermarche.pos.ui.balance.TareService.class);
        service.ticketPrinterService = mock(com.intermarche.pos.ui.hardware.TicketPrinterService.class);
        service.endorsementService = mock(EndorsementService.class);
        // The control points of BO-10-02 answer as they do with nothing
        // administered — no control — which is the behaviour every case of this
        // class was written against. A case exercising a control states its own.
        when(service.endorsementService.check(any(), any()))
                .thenReturn(com.intermarche.pos.ui.endorsement.ControlLevel.NONE);
        // The payment writer, for the direct (unendorsed) ticket abandon (EXP-32).
        service.paymentService = mock(com.intermarche.pos.ui.payment.PaymentService.class);
        // Back-office parameters at their catalog defaults: the endorsement
        // ceremony applies, so the historical routing assertions hold.
        service.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        // The administered lists (BO-04-02-07): a mock answers an empty list for
        // every kind, which is what a register with no list distributed sees.
        service.posListService = mock(com.intermarche.pos.service.PosListService.class);
        when(service.posSettingsService.gestureEndorsementRequired()).thenReturn(true);
        // LC-05-01-01/-03 and LC-05-02-09/14: the return-creation parameters at
        // their catalog defaults — an article may be taken back inside a sale and
        // without its origin ticket, and the register is not dedicated to returns.
        when(service.posSettingsService.returnInSaleAllowed()).thenReturn(true);
        when(service.posSettingsService.returnWithoutOriginAllowed()).thenReturn(true);
        when(service.posSettingsService.returnDedicatedRegister()).thenReturn(false);
        // Discounts and rebates active at their catalog default (BO-03-07-01),
        // so the historical remise/discount routing assertions hold.
        when(service.posSettingsService.discountEnabled()).thenReturn(true);
        service.technicalEventService = mock(TechnicalEventService.class);
        service.ticketNumberService = mock(TicketNumberService.class);
        // The drawer-cap alert (EXP-17) reads the cash session; a mock returns no
        // excess by default, so the historical assertions are unchanged.
        service.cashSessionService = mock(com.intermarche.pos.service.CashSessionService.class);
        service.syncOutboxService = mock(SyncOutboxService.class);
        service.objectMapper = mock(ObjectMapper.class);
        service.supervisorToken = Optional.empty();
        originalClient = (HttpClient) readStaticField("SUPERVISOR_CLIENT");
    }

    /**
     * Restores the original supervisor client and clears any interrupt flag so
     * the supervisor-call tests leave no residual static or thread state.
     *
     * @throws Exception if the static client field cannot be restored
     */
    @AfterEach
    void tearDown() throws Exception {
        writeStaticFinal("SUPERVISOR_CLIENT", originalClient);
        Thread.interrupted();
    }

    /**
     * Reads a static field of {@link HomeService} by reflection.
     *
     * @param name the field name
     * @return the current field value
     * @throws Exception if the field cannot be accessed
     */
    private Object readStaticField(String name) throws Exception {
        Field field = HomeService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    /**
     * Overwrites a private static final field of {@link HomeService} using
     * {@code sun.misc.Unsafe}, the only way to replace a final reference field
     * on JDK 21.
     *
     * @param name the field name
     * @param value the value to store
     * @throws Exception if the field or Unsafe cannot be accessed
     */
    private void writeStaticFinal(String name, Object value) throws Exception {
        Field field = HomeService.class.getDeclaredField(name);
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        Object base = unsafe.staticFieldBase(field);
        long offset = unsafe.staticFieldOffset(field);
        unsafe.putObject(base, offset, value);
    }

    /**
     * Builds a real ticket line with a controlled uid.
     *
     * @param uid the line uid
     * @param ean the EAN code, or null
     * @param plu the PLU code, or null
     * @param price the unit price including tax
     * @param qty the quantity
     * @return the ticket line
     */
    private TicketState.TicketItem item(String uid, String ean, String plu, BigDecimal price, BigDecimal qty) {
        TicketState.TicketItem it = new TicketState.TicketItem(ean, plu, "L", price, qty, BigDecimal.ZERO);
        it.uid = uid;
        return it;
    }

    // --- Navigation ---

    /**
     * {@code selectMenu()} shows the asked menu and bumps the state version so the
     * screen redraws.
     */
    @Test
    void selectMenuShowsTheAskedMenu() {
        service.selectMenu(com.intermarche.pos.ui.PosMenu.TICKET);
        assertEquals(com.intermarche.pos.ui.PosMenu.TICKET, service.state.menu);
        verify(service.state).touch();
    }

    /**
     * Adds {@code count} lines to the ticket, giving the one at {@code markIndex}
     * the uid {@code markUid} so a test can select it by identity.
     *
     * @param count how many lines to add
     * @param markIndex the index to tag
     * @param markUid the uid to set at that index
     */
    private void fillTicket(int count, int markIndex, String markUid) {
        for (int i = 0; i < count; i++) {
            TicketState.TicketItem it = new TicketState.TicketItem(
                    "E" + i, null, "L" + i, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO);
            if (i == markIndex) {
                it.uid = markUid;
            }
            service.state.ticket.items.add(it);
        }
    }

    /**
     * {@code selectLine()} clears the selection when the tapped line is already
     * selected (same-line arm true).
     */
    @Test
    void selectLineDeselectsWhenAlreadySelected() {
        fillTicket(3, 2, "U2");
        service.state.selectedTicketIndex = 2;
        service.selectLine("U2");
        assertEquals(-1, service.state.selectedTicketIndex);
        verify(service.state).touch();
    }

    /**
     * {@code selectLine()} selects the tapped line when a different line was
     * selected (same-line arm false).
     */
    @Test
    void selectLineSelectsWhenDifferent() {
        fillTicket(3, 2, "U2");
        service.state.selectedTicketIndex = 0;
        service.selectLine("U2");
        assertEquals(2, service.state.selectedTicketIndex);
        verify(service.state).touch();
    }

    /**
     * P2 regression — selecting the SECOND line of the THIRD page retains that
     * whole-ticket line (index 13), never the second line of the ticket: the uid
     * resolves to the right global index whatever the page (the offset bug).
     */
    @Test
    void selectLinePicksTheRightGlobalLineFromALaterPage() {
        fillTicket(18, 13, "P3L2");
        service.state.selectedTicketIndex = -1;
        service.selectLine("P3L2");
        assertEquals(13, service.state.selectedTicketIndex);
        verify(service.state).touch();
    }

    /**
     * {@code selectLine()} is a no-op selection for an unknown uid: nothing is
     * selected, but the state is still touched (uid-absent arm).
     */
    @Test
    void selectLineIgnoresUnknownUid() {
        fillTicket(3, 0, "U0");
        service.state.selectedTicketIndex = -1;
        service.selectLine("NOPE");
        assertEquals(-1, service.state.selectedTicketIndex);
        verify(service.state).touch();
    }

    // --- Armed quantity and repetition ---

    /**
     * {@code armQuantity()} keeps the figure for the NEXT article and says so, then
     * closes the quantity keypad ({@code LC-02-13-04/05/06/07}).
     */
    @Test
    void armQuantityKeepsTheFigureForTheNextArticle() {
        service.armQuantity(new BigDecimal("3"));
        assertEquals(new BigDecimal("3"), service.state.armedQuantity);
        verify(service.state.priceModState).clear();
        verify(service.state.ticket).setNotice("QUANTITÉ 3 — SAISISSEZ L'ARTICLE");
        verify(service.state).touch();
    }

    /**
     * {@code armQuantity()} refuses a figure that is not a quantity and arms nothing,
     * on both arms that can produce one: no figure at all, and zero or less.
     */
    @Test
    void armQuantityRefusesWhatIsNotAQuantity() {
        service.armQuantity(null);
        assertNull(service.state.armedQuantity);
        verify(service.state.ticket).setError("QUANTITÉ INVALIDE");
        service.armQuantity(BigDecimal.ZERO);
        assertNull(service.state.armedQuantity);
        service.armQuantity(new BigDecimal("-2"));
        assertNull(service.state.armedQuantity);
        verify(service.state.ticket, times(3)).setError("QUANTITÉ INVALIDE");
        verify(service.state.priceModState, times(3)).clear();
    }

    /**
     * {@code armManualWeight()} keeps the GROSS weight for the NEXT article and
     * says so, with the French comma a weight is read with
     * ({@code LC-02-02-03}).
     */
    @Test
    void armManualWeightKeepsTheGrossWeightForTheNextArticle() {
        service.armManualWeight(new BigDecimal("1.250"));
        assertEquals(new BigDecimal("1.250"), service.state.pendingManualWeightKg);
        verify(service.state.ticket).setNotice("POIDS BRUT 1,25 KG — SAISISSEZ L'ARTICLE");
        verify(service.state).touch();
    }

    /**
     * {@code armManualWeight()} refuses what is not a weight and arms nothing,
     * on the three arms that can produce one: no figure at all, zero, and a
     * negative figure.
     */
    @Test
    void armManualWeightRefusesWhatIsNotAWeight() {
        service.armManualWeight(null);
        assertNull(service.state.pendingManualWeightKg);
        verify(service.state.ticket).setError("POIDS INVALIDE");
        service.armManualWeight(BigDecimal.ZERO);
        assertNull(service.state.pendingManualWeightKg);
        service.armManualWeight(new BigDecimal("-1.5"));
        assertNull(service.state.pendingManualWeightKg);
        verify(service.state.ticket, times(3)).setError("POIDS INVALIDE");
    }

    /**
     * {@code armManualWeight()} refusing a weight also DISARMS the one already
     * kept: a mistyped correction must not leave the previous figure waiting
     * for the next article.
     */
    @Test
    void armManualWeightRefusalDisarmsWhatWasKept() {
        service.armManualWeight(new BigDecimal("2"));
        assertEquals(new BigDecimal("2"), service.state.pendingManualWeightKg);
        service.armManualWeight(BigDecimal.ZERO);
        assertNull(service.state.pendingManualWeightKg);
    }

    /**
     * {@code clearManualWeight()} gives the armed weight up, so leaving the
     * weighing screen never lays it over the next article.
     */
    @Test
    void clearManualWeightGivesTheArmedWeightUp() {
        service.state.pendingManualWeightKg = new BigDecimal("1.250");
        service.clearManualWeight();
        assertNull(service.state.pendingManualWeightKg);
        verify(service.state).touch();
    }

    /**
     * {@code repeatLastItem()} adds ONE to the last line rather than ringing a second
     * one, and asks for the total to be recomputed ({@code LC-02-13-12/13}).
     */
    @Test
    void repeatLastItemAddsOneToTheLastLine() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("2.00"), BigDecimal.ONE);
        service.state.ticket.items.add(it);
        service.state.lastEnteredItemId = "A";
        service.repeatLastItem();
        assertEquals(0, new BigDecimal("2").compareTo(it.quantity));
        verify(service.ticketService).recalculateTotal(service.state);
        verify(service.state.ticket).setNotice("ARTICLE RÉPÉTÉ : L");
        verify(service.state).touch();
    }

    /**
     * {@code repeatLastItem()} refuses when there is nothing to repeat, on both arms:
     * no registration at all, and a registration whose line has since been cancelled.
     */
    @Test
    void repeatLastItemRefusesWhenThereIsNothingToRepeat() {
        service.state.lastEnteredItemId = null;
        service.repeatLastItem();
        service.state.lastEnteredItemId = "GONE";
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.repeatLastItem();
        verify(service.state.ticket, times(2)).setError("AUCUN ARTICLE À RÉPÉTER");
        verifyNoInteractions(service.ticketService);
    }

    /**
     * A weighed line is a measurement and cannot be repeated ({@code LC-02-13-15}):
     * the refusal is said out loud, and nothing is recomputed.
     */
    @Test
    void repeatLastItemRefusesAWeighedLine() {
        TicketState.TicketItem it = item("A", "123", "0042", new BigDecimal("2.00"),
                new BigDecimal("0.500"));
        service.state.ticket.items.add(it);
        service.state.lastEnteredItemId = "A";
        service.repeatLastItem();
        assertEquals(0, new BigDecimal("0.500").compareTo(it.quantity));
        verify(service.state.ticket).setError("RÉPÉTITION IMPOSSIBLE : ARTICLE EN PESÉE");
        verifyNoInteractions(service.ticketService);
    }

    /**
     * Repetition has NO CEILING: three presses on the same line give three more
     * units. The register used to refuse the second press, reading the quantity its
     * own first press had raised as a quantity the cashier had keyed, which capped
     * the function at two.
     */
    @Test
    void repeatLastItemHasNoCeiling() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("2.00"), BigDecimal.ONE);
        service.state.ticket.items.add(it);
        service.state.lastEnteredItemId = "A";
        service.repeatLastItem();
        service.repeatLastItem();
        service.repeatLastItem();
        assertEquals(0, new BigDecimal("4").compareTo(it.quantity));
        verify(service.state.ticket, times(3)).setNotice("ARTICLE RÉPÉTÉ : L");
        verify(service.ticketService, times(3)).recalculateTotal(service.state);
    }

    /**
     * A line whose quantity the cashier KEYED is repeated like any other: the
     * refusal that used to protect that figure is gone, and the keyed quantity
     * simply takes one more unit.
     */
    @Test
    void repeatLastItemAddsOneToAKeyedQuantity() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("2.00"),
                new BigDecimal("3"));
        service.state.ticket.items.add(it);
        service.state.lastEnteredItemId = "A";
        service.repeatLastItem();
        assertEquals(0, new BigDecimal("4").compareTo(it.quantity));
        verify(service.state.ticket).setNotice("ARTICLE RÉPÉTÉ : L");
        verify(service.ticketService).recalculateTotal(service.state);
    }

    /**
     * A price-embedded label names ONE physical object and cannot be repeated
     * ({@code LC-02-13-16}).
     */
    @Test
    void repeatLastItemRefusesAPriceEmbeddedLine() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("4.20"), BigDecimal.ONE);
        it.priceEmbedded = true;
        service.state.ticket.items.add(it);
        service.state.lastEnteredItemId = "A";
        service.repeatLastItem();
        assertEquals(0, BigDecimal.ONE.compareTo(it.quantity));
        verify(service.state.ticket).setError("RÉPÉTITION IMPOSSIBLE : ARTICLE PRIX EMBARQUÉ");
        verifyNoInteractions(service.ticketService);
    }

    /**
     * An empty PLU is not a weighed line: the line was rung by its EAN, so the
     * repetition goes through — the leg a null-only test would leave unproven.
     */
    @Test
    void repeatLastItemAcceptsALineWhosePluIsEmpty() {
        TicketState.TicketItem it = item("A", "123", "", new BigDecimal("2.00"), BigDecimal.ONE);
        service.state.ticket.items.add(it);
        service.state.lastEnteredItemId = "A";
        service.repeatLastItem();
        assertEquals(0, new BigDecimal("2").compareTo(it.quantity));
        verify(service.ticketService).recalculateTotal(service.state);
    }

    // --- Cancel line ---

    /**
     * {@code cancelLine()} cancels the selected line directly when it is the
     * last entered line (index guard both true, uid equals last entered true).
     */
    @Test
    void cancelLineCancelsSelectedLastEnteredDirectly() {
        TicketState.TicketItem it = item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE);
        service.state.ticket.items.add(it);
        service.state.ticket.items.add(item("B", "456", null, BigDecimal.ONE, BigDecimal.ONE));
        service.state.selectedTicketIndex = 0;
        service.state.lastEnteredItemId = "A";
        when(service.endorsementService.gestureAuthorized(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        service.cancelLine();
        verify(service.ticketService).cancelItemById(service.state, "A");
        assertNull(service.state.lastEnteredItemId);
        assertEquals(-1, service.state.selectedTicketIndex);
        verify(service.state).touch();
        verify(service.endorsementService, never()).requestAuthorization(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * {@code cancelLine()} requests a manager endorsement when the selected line
     * is not the last entered one (uid equals last entered false).
     */
    @Test
    void cancelLineRequestsEndorsementWhenNotLastEntered() {
        TicketState.TicketItem it = item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE);
        service.state.ticket.items.add(it);
        service.state.ticket.items.add(item("B", "456", null, BigDecimal.ONE, BigDecimal.ONE));
        service.state.selectedTicketIndex = 0;
        service.state.lastEnteredItemId = "OTHER";
        service.cancelLine();
        verify(service.endorsementService).requestAuthorization(service.state, "CANCEL_LINE_A");
        assertEquals(-1, service.state.selectedTicketIndex);
        verify(service.state).touch();
        verifyNoInteractions(service.ticketService);
    }

    /**
     * {@code cancelLine()} targets the last line when nothing is selected
     * (index guard first arm false, non-empty second arm true).
     */
    @Test
    void cancelLineTargetsLastLineWhenNoSelection() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.state.ticket.items.add(item("B", "456", null, BigDecimal.ONE, BigDecimal.ONE));
        service.state.selectedTicketIndex = -1;
        service.state.lastEnteredItemId = null;
        service.cancelLine();
        verify(service.endorsementService).requestAuthorization(service.state, "CANCEL_LINE_B");
    }

    /**
     * {@code cancelLine()} targets the last line when the selected index is out
     * of range (index guard first arm true, second arm false).
     */
    @Test
    void cancelLineTargetsLastLineWhenIndexOutOfRange() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.state.ticket.items.add(item("B", "456", null, BigDecimal.ONE, BigDecimal.ONE));
        service.state.selectedTicketIndex = 5;
        service.state.lastEnteredItemId = "B";
        when(service.endorsementService.gestureAuthorized(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        service.cancelLine();
        verify(service.ticketService).cancelItemById(service.state, "B");
    }

    /**
     * {@code cancelLine()} does nothing when the ticket is empty and no line is
     * selected (target uid stays null, early return).
     */
    @Test
    void cancelLineDoesNothingWhenEmpty() {
        service.state.selectedTicketIndex = -1;
        service.cancelLine();
        verifyNoInteractions(service.ticketService);
        verifyNoInteractions(service.endorsementService);
        verify(service.state, never()).touch();
    }

    /**
     * {@code cancelTicket()} requests a manager endorsement for the whole
     * ticket.
     */
    @Test
    void cancelTicketRequestsEndorsement() {
        service.cancelTicket();
        verify(service.endorsementService).requestAuthorization(service.state, "CANCEL_TICKET");
    }

    // --- Print last ticket ---

    /**
     * {@code printLastTicket()} prints nothing when the guard refuses.
     *
     * <p>The guard's own legs — training mode, no closed ticket — are covered
     * once in {@code PosStateTest}; what belongs here is that the service asks
     * it and obeys it.
     */
    @Test
    void printLastTicketPrintsNothingWhenGuardRefuses() {
        when(service.state.requireLastClosedTicket()).thenReturn(false);
        service.printLastTicket();
        // The RECOVERY still happens, and must: the DERNIER keys work on the last
        // sale this REGISTER closed, not the last one this PROCESS closed, so the
        // service looks the ticket up BEFORE asking the guard. What the refusal
        // forbids is the printing.
        verify(service.ticketService).resolveLastClosedTicketId(service.state);
        verifyNoMoreInteractions(service.ticketService);
    }

    /**
     * {@code printLastTicket()} reprints the last closed ticket when the guard
     * allows it.
     */
    @Test
    void printLastTicketReprintsWhenGuardAllows() {
        when(service.state.requireLastClosedTicket()).thenReturn(true);
        service.state.lastClosedTicketId = 42L;
        service.printLastTicket();
        verify(service.ticketService).reprintTicket(42L);
        verify(service.state.ticket).setNotice("TICKET RÉIMPRIMÉ");
    }

    // --- Print last ticket identity barcode (LC-08-01-04) ---

    /**
     * {@code printLastTicketBarcode()} prints nothing when the guard refuses.
     */
    @Test
    void printLastTicketBarcodePrintsNothingWhenGuardRefuses() {
        when(service.state.requireLastClosedTicket()).thenReturn(false);
        service.printLastTicketBarcode();
        verify(service.ticketService).resolveLastClosedTicketId(service.state);
        verifyNoMoreInteractions(service.ticketService);
    }

    /**
     * {@code printLastTicketBarcode()} prints the identity of the last closed
     * ticket when the guard allows it.
     */
    @Test
    void printLastTicketBarcodePrintsWhenGuardAllows() {
        when(service.state.requireLastClosedTicket()).thenReturn(true);
        service.state.lastClosedTicketId = 42L;
        service.printLastTicketBarcode();
        verify(service.ticketService).printTicketIdentityBarcode(42L);
        verify(service.state.ticket).setNotice("CODE-BARRES IMPRIMÉ");
    }

    // --- Duplicata du dernier ticket carte bancaire (LC-08-05-09) ---

    /**
     * {@code printLastCardReceiptDuplicate()} prints nothing when the guard
     * refuses.
     */
    @Test
    void printLastCardReceiptDuplicatePrintsNothingWhenGuardRefuses() {
        when(service.state.requireLastClosedTicket()).thenReturn(false);
        service.printLastCardReceiptDuplicate();
        verify(service.ticketService).resolveLastClosedTicketId(service.state);
        verifyNoMoreInteractions(service.ticketService);
    }

    /**
     * {@code printLastCardReceiptDuplicate()} prints the slip of the last closed
     * ticket when the guard allows it.
     */
    @Test
    void printLastCardReceiptDuplicatePrintsWhenGuardAllows() {
        when(service.state.requireLastClosedTicket()).thenReturn(true);
        service.state.lastClosedTicketId = 42L;
        when(service.ticketService.printCardReceiptDuplicate(42L)).thenReturn(1);
        service.printLastCardReceiptDuplicate();
        verify(service.ticketService).printCardReceiptDuplicate(42L);
        verify(service.state.ticket).setNotice("DUPLICATA CB IMPRIMÉ");
    }

    /**
     * A sale settled without a card prints no slip: the key answers, and it
     * answers a refusal rather than a confirmation (printed == 0 arm).
     */
    @Test
    void printLastCardReceiptDuplicateTellsWhenTheSaleCarriedNoCard() {
        when(service.state.requireLastClosedTicket()).thenReturn(true);
        service.state.lastClosedTicketId = 42L;
        when(service.ticketService.printCardReceiptDuplicate(42L)).thenReturn(0);
        service.printLastCardReceiptDuplicate();
        verify(service.state.ticket).setError("AUCUN PAIEMENT CARTE SUR CE TICKET");
        verify(service.state.ticket, never()).setNotice(org.mockito.ArgumentMatchers.anyString());
    }

    // --- Open / cancel price modification ---

    /**
     * {@code openPriceMod()} sets an error when no line is targeted
     * (target null).
     */
    @Test
    void openPriceModErrorsWhenNoTarget() {
        when(service.state.getTargetItem()).thenReturn(null);
        service.openPriceMod("remise");
        verify(service.state.ticket).setError("AUCUNE LIGNE SÉLECTIONNÉE");
        verify(service.state).touch();
        verifyNoInteractions(service.state.priceModState);
    }

    /**
     * {@code openPriceMod()} on QUANTITY with NO line selected opens the modal on
     * no line at all instead of refusing the gesture: the figure about to be keyed
     * is for the article the cashier is about to ring ({@code LC-02-13-04/05/06}).
     */
    @Test
    void openPriceModQuantityWithoutSelectionTargetsTheNextArticle() {
        when(service.state.getSelectedItem()).thenReturn(null);
        service.openPriceMod("quantity");
        verify(service.state.priceModState).set(PriceModType.QUANTITY, null, "PROCHAIN ARTICLE");
        verify(service.state.ticket, never())
                .setError(org.mockito.ArgumentMatchers.anyString());
        verify(service.state).touch();
    }

    /**
     * THE ARMING FIRES EVEN WITH ARTICLES ALREADY ON THE TICKET, and this is the
     * case that was broken: the key read {@code getTargetItem()}, which answers
     * the LAST line when nothing is selected, so a quantity keyed on a running
     * sale landed on the article just rung instead of arming for the next one.
     * The key reads the explicit selection now, and the fallback is left to the
     * price gestures it was written for.
     */
    @Test
    void openPriceModQuantityArmsEvenWhenTheTicketCarriesLines() {
        TicketState.TicketItem last = item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE);
        when(service.state.getTargetItem()).thenReturn(last);
        when(service.state.getSelectedItem()).thenReturn(null);
        service.openPriceMod("quantity");
        verify(service.state.priceModState).set(PriceModType.QUANTITY, null, "PROCHAIN ARTICLE");
        verify(service.state.ticket, never())
                .setError(org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * {@code openPriceMod()} on QUANTITY WITH a line selected still targets that
     * line — the other arm of the same guard, and the gesture that existed first.
     */
    @Test
    void openPriceModQuantityWithASelectionStillTargetsThatLine() {
        TicketState.TicketItem it = item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE);
        when(service.state.getSelectedItem()).thenReturn(it);
        service.openPriceMod("quantity");
        verify(service.state.priceModState).set(PriceModType.QUANTITY, "A", "L", it.getHtml(),
                it.getPriceFormatted(), it.getModifierLabel());
        verify(service.state).touch();
    }

    /**
     * {@code openPriceMod()} opens the modal on the targeted line, upper-casing
     * the type (target non-null).
     */
    @Test
    void openPriceModOpensModalWhenTargeted() {
        TicketState.TicketItem it = item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE);
        when(service.state.getTargetItem()).thenReturn(it);
        service.openPriceMod("remise");
        verify(service.state.priceModState).set(PriceModType.REMISE, "A", "L", it.getHtml(),
                it.getPriceFormatted(), it.getModifierLabel());
        verify(service.state).touch();
    }

    /**
     * A GLOBAL_ gesture targets the WHOLE ticket: the modal opens with a null
     * line uid and the "TICKET COMPLET" label, and — the point of the branch
     * — NO line selection is required. Selecting a line first would be
     * meaningless for a sale-level discount, and demanding one would block
     * the gesture on an empty selection.
     */
    @Test
    void openPriceModGlobalTargetsTheWholeTicket() {
        service.openPriceMod("global_remise");
        verify(service.state.priceModState).set(PriceModType.GLOBAL_REMISE, null, "TICKET COMPLET");
        verify(service.state).touch();
        verify(service.state, never()).getTargetItem();
        verifyNoInteractions(service.state.ticket);
    }

    /**
     * The percentage flavour takes the same branch — the prefix is what
     * routes, not the full type.
     */
    @Test
    void openPriceModGlobalDiscountTargetsTheWholeTicketToo() {
        service.openPriceMod("global_discount");
        verify(service.state.priceModState).set(PriceModType.GLOBAL_DISCOUNT, null, "TICKET COMPLET");
        verify(service.state).touch();
    }

    /**
     * A GLOBAL_ gesture opens even when a line IS selected: the selection is
     * simply irrelevant here, and the modal must not silently retarget the
     * gesture onto that line.
     */
    @Test
    void openPriceModGlobalIgnoresAnySelectedLine() {
        TicketState.TicketItem selected = item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE);
        when(service.state.getTargetItem()).thenReturn(selected);
        service.openPriceMod("GLOBAL_REMISE");
        verify(service.state.priceModState).set(PriceModType.GLOBAL_REMISE, null, "TICKET COMPLET");
        verify(service.state.priceModState, never()).set(PriceModType.GLOBAL_REMISE, "A", "L");
    }

    /**
     * A GLOBAL_ gesture never raises the no-selection error — the guard that
     * governs per-line gestures is short-circuited by the early return.
     */
    @Test
    void openPriceModGlobalNeverComplainsAboutSelection() {
        when(service.state.getTargetItem()).thenReturn(null);
        service.openPriceMod("global_remise");
        verify(service.state.ticket, never()).setError(any());
    }

    /**
     * The prefix match is on the UPPER-CASED type, so the lower-case form
     * used by the screen links routes exactly like the canonical one.
     */
    @Test
    void openPriceModGlobalPrefixMatchesOnTheUpperCasedType() {
        service.openPriceMod("Global_Remise");
        verify(service.state.priceModState).set(PriceModType.GLOBAL_REMISE, null, "TICKET COMPLET");
    }

    /**
     * A word that names no mode opens NOTHING: a stale page or a hand-typed
     * address must not raise a modal whose title and buttons nobody can decide,
     * even when a line is selected and would otherwise be a valid target
     * (unknown-mode arm).
     */
    @Test
    void openPriceModUnknownModeOpensNothing() {
        TicketState.TicketItem it = item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE);
        when(service.state.getTargetItem()).thenReturn(it);
        service.openPriceMod("remise_global");
        verify(service.state.ticket).setError("MODIFICATION INCONNUE");
        verify(service.state.priceModState, never()).set(any(), any(), any());
        verify(service.state.priceModState, never()).set(any(), any(), any(), any(), any(), any());
    }

    /**
     * A null mode name is refused exactly like an unrecognized one: the guard
     * folds the two into the same answer (null arm of the upper-casing).
     */
    @Test
    void openPriceModNullModeOpensNothing() {
        service.openPriceMod(null);
        verify(service.state.ticket).setError("MODIFICATION INCONNUE");
        verify(service.state.priceModState, never()).set(any(), any(), any());
    }

    /**
     * A blank mode name is refused too — an empty path segment is not a mode
     * (blank arm of the parser).
     */
    @Test
    void openPriceModBlankModeOpensNothing() {
        service.openPriceMod("   ");
        verify(service.state.ticket).setError("MODIFICATION INCONNUE");
        verify(service.state.priceModState, never()).set(any(), any(), any());
    }

    /**
     * {@code cancelPriceMod()} closes the modal and touches the state.
     */
    @Test
    void cancelPriceModClearsModal() {
        service.cancelPriceMod();
        verify(service.state.priceModState).clear();
        verify(service.state).touch();
    }

    // --- Training toggle request ---

    /**
     * {@code requestTrainingToggle()} refuses over a non-empty cart
     * (first arm true).
     */
    @Test
    void requestTrainingToggleRefusedWhenCartNotEmpty() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.requestTrainingToggle();
        verify(service.state.ticket).setError("TERMINEZ OU ANNULEZ LE TICKET D'ABORD");
        verify(service.state).touch();
        verifyNoInteractions(service.endorsementService);
    }

    /**
     * {@code requestTrainingToggle()} refuses during an active payment
     * (first arm false, second arm true).
     */
    @Test
    void requestTrainingToggleRefusedWhenPaymentInProgress() {
        service.state.payment.paymentInProgress = true;
        service.requestTrainingToggle();
        verify(service.state.ticket).setError("TERMINEZ OU ANNULEZ LE TICKET D'ABORD");
        verify(service.state).touch();
        verifyNoInteractions(service.endorsementService);
    }

    /**
     * {@code requestTrainingToggle()} requests the endorsement on an empty cart
     * with no payment (both arms false).
     */
    @Test
    void requestTrainingToggleTogglesDirectlyWhenSelfAuthorized() {
        service.state.payment.paymentInProgress = false;
        when(service.endorsementService.selfAuthorized(service.state)).thenReturn(true);
        service.requestTrainingToggle();
        assertTrue(service.state.trainingMode);
        verify(service.endorsementService, never()).requestAuthorization(any(), anyString());
    }

    @Test
    void requestTrainingToggleRequestsEndorsementWhenIdle() {
        service.state.payment.paymentInProgress = false;
        service.requestTrainingToggle();
        verify(service.endorsementService).requestAuthorization(service.state, "TRAINING_TOGGLE");
        verify(service.state).touch();
        verify(service.state.ticket, never()).setError(any());
    }

    // --- Training toggle perform ---

    /**
     * {@code performTrainingToggle()} refuses over a non-empty cart
     * (first arm true).
     */
    @Test
    void performTrainingToggleRefusedWhenCartNotEmpty() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.performTrainingToggle();
        verify(service.state.ticket).setError("TERMINEZ OU ANNULEZ LE TICKET D'ABORD");
        verifyNoInteractions(service.technicalEventService);
    }

    /**
     * {@code performTrainingToggle()} refuses during an active payment
     * (first arm false, second arm true).
     */
    @Test
    void performTrainingToggleRefusedWhenPaymentInProgress() {
        service.state.payment.paymentInProgress = true;
        service.performTrainingToggle();
        verify(service.state.ticket).setError("TERMINEZ OU ANNULEZ LE TICKET D'ABORD");
        verifyNoInteractions(service.technicalEventService);
    }

    /**
     * {@code performTrainingToggle()} enters training mode when it was off
     * (ternary true arm).
     */
    @Test
    void performTrainingToggleEntersTraining() {
        service.state.trainingMode = false;
        service.performTrainingToggle();
        assertTrue(service.state.trainingMode);
        verify(service.technicalEventService).log(TechnicalEvent.EventType.TRAINING_STARTED, null);
        verify(service.state.ticket).setError("MODE FORMATION ACTIVÉ");
    }

    /**
     * {@code performTrainingToggle()} leaves training mode when it was on
     * (ternary false arm).
     */
    @Test
    void performTrainingToggleLeavesTraining() {
        service.state.trainingMode = true;
        service.performTrainingToggle();
        assertFalse(service.state.trainingMode);
        verify(service.technicalEventService).log(TechnicalEvent.EventType.TRAINING_ENDED, null);
        verify(service.state.ticket).setError("MODE FORMATION TERMINÉ");
    }

    // --- Supervisor call ---

    /**
     * {@code callSupervisor()} journals the call and reports the missing
     * configuration when the outbox is disabled (enabled false).
     */
    @Test
    void callSupervisorReportsNotConfigured() {
        when(service.syncOutboxService.isEnabled()).thenReturn(false);
        service.callSupervisor("no-change");
        verify(service.technicalEventService).log(TechnicalEvent.EventType.SUPERVISOR_CALLED, "no-change");
        verify(service.state.ticket).setError("SUPERVISION NON CONFIGURÉE SUR CETTE CAISSE");
        verify(service.state).touch();
    }

    /**
     * EXP-19: a free message is appended to the journalled detail (reason — message)
     * when it is non-blank; the outbox-disabled path is used to avoid a real push.
     */
    @Test
    void callSupervisorWithMessageJournalsBoth() {
        when(service.syncOutboxService.isEnabled()).thenReturn(false);
        service.callSupervisor("Prix", "étiquette manquante rayon 4");
        verify(service.technicalEventService).log(TechnicalEvent.EventType.SUPERVISOR_CALLED,
                "Prix — étiquette manquante rayon 4");
    }

    /**
     * EXP-19: a blank free message degrades to the plain reason in the journal.
     */
    @Test
    void callSupervisorWithBlankMessageJournalsReasonOnly() {
        when(service.syncOutboxService.isEnabled()).thenReturn(false);
        service.callSupervisor("Autre", "   ");
        verify(service.technicalEventService).log(TechnicalEvent.EventType.SUPERVISOR_CALLED, "Autre");
    }

    /**
     * EXP-84 / BO-04-02-07: the supervisor screen hands over the administered
     * service-request types as the list service resolves them — referential
     * first, semicolon setting as the fallback — and adds nothing of its own.
     */
    @Test
    void supervisorServiceReasonsHandsOverTheAdministeredTypes() {
        java.util.List<com.intermarche.pos.service.PosListService.ServiceRequestType> types =
                java.util.List.of(
                        new com.intermarche.pos.service.PosListService.ServiceRequestType(
                                "ANNUL", "Annulation", 2),
                        new com.intermarche.pos.service.PosListService.ServiceRequestType(
                                "PRIX", "Prix / étiquette", 1));
        when(service.posListService.serviceRequestTypes()).thenReturn(types);
        assertEquals(types, service.supervisorServiceReasons());
    }

    /**
     * EXP-84: nothing administered yields no key on the supervisor screen.
     */
    @Test
    void supervisorServiceReasonsEmptyWhenNothingAdministered() {
        when(service.posListService.serviceRequestTypes()).thenReturn(java.util.List.of());
        assertTrue(service.supervisorServiceReasons().isEmpty());
    }

    /**
     * EXP-89: answering a pending survey pushes the answer up (journalled) and
     * clears the survey and the message.
     */
    @Test
    void answerSurveyPushesAndClears() {
        when(service.syncOutboxService.isEnabled()).thenReturn(false);
        PosState.BackOfficeSurvey survey = new PosState.BackOfficeSurvey();
        survey.question = "Rayon propre ?";
        service.state.backOfficeSurvey = survey;
        service.state.backOfficeMessage = "Rayon propre ?";
        service.answerSurvey("Oui");
        verify(service.technicalEventService).log(TechnicalEvent.EventType.SUPERVISOR_CALLED,
                "SONDAGE — Rayon propre ? : Oui");
        assertNull(service.state.backOfficeSurvey);
        assertEquals("", service.state.backOfficeMessage);
    }

    /**
     * EXP-89: answering with no survey pending is a no-op (nothing journalled).
     */
    @Test
    void answerSurveyNoOpWhenNoSurvey() {
        service.state.backOfficeSurvey = null;
        service.answerSurvey("Oui");
        verify(service.technicalEventService, org.mockito.Mockito.never())
                .log(org.mockito.ArgumentMatchers.eq(TechnicalEvent.EventType.SUPERVISOR_CALLED),
                        org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * EXP-89: a blank answer to a pending survey is a no-op.
     */
    @Test
    void answerSurveyNoOpWhenBlankAnswer() {
        PosState.BackOfficeSurvey survey = new PosState.BackOfficeSurvey();
        survey.question = "Q";
        service.state.backOfficeSurvey = survey;
        service.answerSurvey("  ");
        verify(service.technicalEventService, org.mockito.Mockito.never())
                .log(org.mockito.ArgumentMatchers.eq(TechnicalEvent.EventType.SUPERVISOR_CALLED),
                        org.mockito.ArgumentMatchers.anyString());
        assertNotNull(service.state.backOfficeSurvey);
    }

    /**
     * EXP-20: receiving a plain back-office message stores it with no survey and
     * touches the state.
     */
    @Test
    void receiveBackOfficeMessagePlain() {
        service.receiveBackOfficeMessage("Réunion à 14h", null);
        assertEquals("Réunion à 14h", service.state.backOfficeMessage);
        assertNull(service.state.backOfficeSurvey);
        verify(service.state).touch();
    }

    /**
     * EXP-89: receiving a message with a survey stores both; a null text is
     * normalised to empty.
     */
    @Test
    void receiveBackOfficeMessageWithSurveyNormalisesNullText() {
        PosState.BackOfficeSurvey survey = new PosState.BackOfficeSurvey();
        survey.question = "Q";
        service.receiveBackOfficeMessage(null, survey);
        assertEquals("", service.state.backOfficeMessage);
        assertSame(survey, service.state.backOfficeSurvey);
    }

    /**
     * {@code callSupervisor()} reports success on a 2xx response with a
     * non-blank shared token (token added, status both arms true).
     *
     * @throws Exception if the HTTP send stub cannot be wired
     */
    @Test
    @SuppressWarnings("unchecked")
    void callSupervisorReportsSuccessWithToken() throws Exception {
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        when(service.syncOutboxService.getStoreUrl()).thenReturn("http://localhost:9999");
        when(service.objectMapper.writeValueAsString(any())).thenReturn("{}");
        service.supervisorToken = Optional.of("secret");
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(client.send(any(), any())).thenReturn(response);
        writeStaticFinal("SUPERVISOR_CLIENT", client);
        service.callSupervisor("theft");
        verify(service.state.ticket).setNotice("SUPERVISEUR PRÉVENU");
        verify(service.state).touch();
    }

    /**
     * {@code callSupervisor()} reports a refusal on a 500 response with a blank
     * token (token skipped, status first arm true / second arm false).
     *
     * @throws Exception if the HTTP send stub cannot be wired
     */
    @Test
    @SuppressWarnings("unchecked")
    void callSupervisorReportsRefusedWhenServerError() throws Exception {
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        when(service.syncOutboxService.getStoreUrl()).thenReturn("http://localhost:9999");
        when(service.objectMapper.writeValueAsString(any())).thenReturn("{}");
        service.supervisorToken = Optional.of("");
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(client.send(any(), any())).thenReturn(response);
        writeStaticFinal("SUPERVISOR_CLIENT", client);
        service.callSupervisor("theft");
        verify(service.state.ticket).setError("APPEL SUPERVISEUR REFUSÉ (500)");
    }

    /**
     * {@code callSupervisor()} reports a refusal on a sub-200 response
     * (status first arm false).
     *
     * @throws Exception if the HTTP send stub cannot be wired
     */
    @Test
    @SuppressWarnings("unchecked")
    void callSupervisorReportsRefusedWhenInformational() throws Exception {
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        when(service.syncOutboxService.getStoreUrl()).thenReturn("http://localhost:9999");
        when(service.objectMapper.writeValueAsString(any())).thenReturn("{}");
        service.supervisorToken = Optional.empty();
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(100);
        when(client.send(any(), any())).thenReturn(response);
        writeStaticFinal("SUPERVISOR_CLIENT", client);
        service.callSupervisor("theft");
        verify(service.state.ticket).setError("APPEL SUPERVISEUR REFUSÉ (100)");
    }

    /**
     * {@code callSupervisor()} reports an interruption and re-raises the thread
     * interrupt flag when the send is interrupted (InterruptedException catch).
     *
     * @throws Exception if the HTTP send stub cannot be wired
     */
    @Test
    void callSupervisorReportsInterrupted() throws Exception {
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        when(service.syncOutboxService.getStoreUrl()).thenReturn("http://localhost:9999");
        when(service.objectMapper.writeValueAsString(any())).thenReturn("{}");
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(), any())).thenThrow(new InterruptedException());
        writeStaticFinal("SUPERVISOR_CLIENT", client);
        service.callSupervisor("theft");
        verify(service.state.ticket).setError("APPEL SUPERVISEUR INTERROMPU");
        assertTrue(Thread.currentThread().isInterrupted());
        verify(service.state).touch();
    }

    /**
     * {@code callSupervisor()} reports a generic failure when the send throws an
     * I/O error (generic Exception catch).
     *
     * @throws Exception if the HTTP send stub cannot be wired
     */
    @Test
    void callSupervisorReportsImpossibleOnIoError() throws Exception {
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        when(service.syncOutboxService.getStoreUrl()).thenReturn("http://localhost:9999");
        when(service.objectMapper.writeValueAsString(any())).thenReturn("{}");
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(), any())).thenThrow(new java.io.IOException("down"));
        writeStaticFinal("SUPERVISOR_CLIENT", client);
        service.callSupervisor("theft");
        verify(service.state.ticket).setError("APPEL SUPERVISEUR IMPOSSIBLE");
        verify(service.state).touch();
    }

    /**
     * LC-01-07-03: a supervisor call the back office took puts the lane's
     * request IN PROGRESS — the indicator the sale screen shows until the
     * request is cancelled, refused, acknowledged or validated.
     *
     * @throws Exception if the HTTP send stub cannot be wired
     */
    @Test
    @SuppressWarnings("unchecked")
    void callSupervisorMarksTheRequestInProgress() throws Exception {
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        when(service.syncOutboxService.getStoreUrl()).thenReturn("http://localhost:9999");
        when(service.objectMapper.writeValueAsString(any())).thenReturn("{}");
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(client.send(any(), any())).thenReturn(response);
        writeStaticFinal("SUPERVISOR_CLIENT", client);
        service.state.serviceRequest = PosState.ServiceRequestStatus.NONE;
        service.callSupervisor("Sécurité");
        assertEquals(PosState.ServiceRequestStatus.PENDING, service.state.serviceRequest);
        assertEquals("Sécurité", service.state.serviceRequestReason);
    }

    /**
     * LC-01-07-03: a call the back office did NOT take (refusal, network)
     * leaves the lane's request state alone — nothing is in progress that
     * nobody received.
     *
     * @throws Exception if the HTTP send stub cannot be wired
     */
    @Test
    @SuppressWarnings("unchecked")
    void callSupervisorRefusedLeavesNoRequestInProgress() throws Exception {
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        when(service.syncOutboxService.getStoreUrl()).thenReturn("http://localhost:9999");
        when(service.objectMapper.writeValueAsString(any())).thenReturn("{}");
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(client.send(any(), any())).thenReturn(response);
        writeStaticFinal("SUPERVISOR_CLIENT", client);
        service.state.serviceRequest = PosState.ServiceRequestStatus.NONE;
        service.callSupervisor("Sécurité");
        assertEquals(PosState.ServiceRequestStatus.NONE, service.state.serviceRequest);
    }

    /**
     * LC-01-07-05: cancelling with no pending request is a no-op notice —
     * there is nothing to withdraw.
     */
    @Test
    void cancelServiceRequestWithoutPendingRequestIsANoOp() {
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        service.state.serviceRequest = PosState.ServiceRequestStatus.NONE;
        service.cancelServiceRequest();
        verify(service.state.ticket).setNotice("AUCUNE DEMANDE EN COURS");
        verify(service.state).touch();
    }

    /**
     * LC-01-07-05: cancelling with no configured back office reports the
     * missing supervision and withdraws nothing.
     */
    @Test
    void cancelServiceRequestWithoutConfigurationReportsIt() {
        when(service.syncOutboxService.isEnabled()).thenReturn(false);
        service.state.serviceRequest = PosState.ServiceRequestStatus.PENDING;
        service.cancelServiceRequest();
        verify(service.state.ticket).setError("SUPERVISION NON CONFIGURÉE SUR CETTE CAISSE");
        assertEquals(PosState.ServiceRequestStatus.PENDING, service.state.serviceRequest);
    }

    /**
     * LC-01-07-05: a cancellation the node takes lowers the indicator — the
     * request is gone from the back office and from the lane in one gesture.
     *
     * @throws Exception if the HTTP send stub cannot be wired
     */
    @Test
    @SuppressWarnings("unchecked")
    void cancelServiceRequestSucceedingClearsTheIndicator() throws Exception {
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        when(service.syncOutboxService.getStoreUrl()).thenReturn("http://localhost:9999");
        when(service.objectMapper.writeValueAsString(any())).thenReturn("{}");
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(client.send(any(), any())).thenReturn(response);
        writeStaticFinal("SUPERVISOR_CLIENT", client);
        service.state.serviceRequest = PosState.ServiceRequestStatus.PENDING;
        service.state.serviceRequestReason = "Sécurité";
        service.cancelServiceRequest();
        assertEquals(PosState.ServiceRequestStatus.NONE, service.state.serviceRequest);
        assertNull(service.state.serviceRequestReason);
        verify(service.state.ticket).setNotice("DEMANDE ANNULÉE");
    }

    /**
     * LC-01-07-05: a cancellation the node refuses keeps the indicator, so
     * the operator can try again — the banner stays up there.
     *
     * @throws Exception if the HTTP send stub cannot be wired
     */
    @Test
    @SuppressWarnings("unchecked")
    void cancelServiceRequestRefusedKeepsTheIndicator() throws Exception {
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        when(service.syncOutboxService.getStoreUrl()).thenReturn("http://localhost:9999");
        when(service.objectMapper.writeValueAsString(any())).thenReturn("{}");
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        when(client.send(any(), any())).thenReturn(response);
        writeStaticFinal("SUPERVISOR_CLIENT", client);
        service.state.serviceRequest = PosState.ServiceRequestStatus.PENDING;
        service.cancelServiceRequest();
        assertEquals(PosState.ServiceRequestStatus.PENDING, service.state.serviceRequest);
        verify(service.state.ticket).setError("APPEL SUPERVISEUR REFUSÉ (503)");
    }

    /**
     * LC-01-07-05: a node that cannot be reached keeps the indicator too
     * (generic Exception catch).
     *
     * @throws Exception if the HTTP send stub cannot be wired
     */
    @Test
    void cancelServiceRequestUnreachableKeepsTheIndicator() throws Exception {
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        when(service.syncOutboxService.getStoreUrl()).thenReturn("http://localhost:9999");
        when(service.objectMapper.writeValueAsString(any())).thenReturn("{}");
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(), any())).thenThrow(new java.io.IOException("down"));
        writeStaticFinal("SUPERVISOR_CLIENT", client);
        service.state.serviceRequest = PosState.ServiceRequestStatus.PENDING;
        service.cancelServiceRequest();
        assertEquals(PosState.ServiceRequestStatus.PENDING, service.state.serviceRequest);
        verify(service.state.ticket).setError("APPEL SUPERVISEUR IMPOSSIBLE");
    }

    /**
     * LC-01-07-06: validating a request the back office has acknowledged
     * clears the indicator — the handling is closed on the lane.
     */
    @Test
    void validateServiceRequestClearsAnAcknowledgedRequest() {
        service.state.serviceRequest = PosState.ServiceRequestStatus.ACKED;
        service.state.serviceRequestReason = "Sécurité";
        service.validateServiceRequest();
        assertEquals(PosState.ServiceRequestStatus.NONE, service.state.serviceRequest);
        assertNull(service.state.serviceRequestReason);
        verify(service.state.ticket).setNotice("DEMANDE TRAITÉE");
        verify(service.state).touch();
    }

    /**
     * LC-01-07-06: validating while the request is still pending — or with
     * nothing asked at all — is a no-op notice on both arms of the guard.
     */
    @Test
    void validateServiceRequestRefusesWhatIsNotAcknowledged() {
        service.state.serviceRequest = PosState.ServiceRequestStatus.PENDING;
        service.validateServiceRequest();
        verify(service.state.ticket).setNotice("LA DEMANDE N'EST PAS ENCORE ACQUITTÉE");
        assertEquals(PosState.ServiceRequestStatus.PENDING, service.state.serviceRequest);
        service.state.serviceRequest = PosState.ServiceRequestStatus.NONE;
        service.validateServiceRequest();
        verify(service.state.ticket, org.mockito.Mockito.times(2))
                .setNotice("LA DEMANDE N'EST PAS ENCORE ACQUITTÉE");
    }

    /**
     * LC-01-08-04: a received message raises the arrived indicator — the
     * consult key and the chip stay until the operator acknowledges.
     */
    @Test
    void receivedMessageRaisesTheArrivedIndicator() {
        service.receiveBackOfficeMessage("Consigne du responsable", null);
        assertTrue(service.state.boMessageArrived);
        verify(service.state).touch();
    }

    /**
     * LC-01-08-05: the blank answer that takes a notice down lowers the
     * indicator again — there is nothing left to consult.
     */
    @Test
    void takenDownMessageLowersTheArrivedIndicator() {
        service.state.boMessageArrived = true;
        service.receiveBackOfficeMessage("", null);
        assertFalse(service.state.boMessageArrived);
    }

    /**
     * LC-01-08-04: a survey raises the indicator too — it is a message.
     */
    @Test
    void receivedSurveyRaisesTheArrivedIndicator() {
        PosState.BackOfficeSurvey survey = new PosState.BackOfficeSurvey();
        survey.question = "Rayon propre ?";
        service.receiveBackOfficeMessage(null, survey);
        assertTrue(service.state.boMessageArrived);
    }

    /**
     * LC-01-08-05: acknowledging with nothing to acknowledge is a no-op
     * notice.
     */
    @Test
    void ackMessageWithNothingToShowIsANoOp() {
        service.state.backOfficeMessage = "";
        service.state.backOfficeSurvey = null;
        service.ackBackOfficeMessage();
        verify(service.state.ticket).setNotice("AUCUN MESSAGE");
        verify(service.state).touch();
    }

    /**
     * LC-01-08-05: an acknowledgement the node takes clears the banner, the
     * survey, the indicator — and posts who read the notice.
     *
     * @throws Exception if the HTTP send stub cannot be wired
     */
    @Test
    @SuppressWarnings("unchecked")
    void ackMessageSucceedingClearsEverything() throws Exception {
        service.state.auth = new com.intermarche.pos.ui.auth.AuthState();
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        when(service.syncOutboxService.getStoreUrl()).thenReturn("http://localhost:9999");
        when(service.objectMapper.writeValueAsString(any())).thenReturn("{}");
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(client.send(any(), any())).thenReturn(response);
        writeStaticFinal("SUPERVISOR_CLIENT", client);
        service.state.backOfficeMessage = "Consigne du responsable";
        service.state.boMessageArrived = true;
        service.ackBackOfficeMessage();
        assertEquals("", service.state.backOfficeMessage);
        assertNull(service.state.backOfficeSurvey);
        assertFalse(service.state.boMessageArrived);
        verify(service.state.ticket).setNotice("MESSAGE ACQUITTÉ");
    }

    /**
     * LC-01-08-05: an acknowledgement the node refuses keeps the message,
     * for the same reason as the cancellation — the lane can try again.
     *
     * @throws Exception if the HTTP send stub cannot be wired
     */
    @Test
    @SuppressWarnings("unchecked")
    void ackMessageRefusedKeepsTheMessage() throws Exception {
        service.state.auth = new com.intermarche.pos.ui.auth.AuthState();
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        when(service.syncOutboxService.getStoreUrl()).thenReturn("http://localhost:9999");
        when(service.objectMapper.writeValueAsString(any())).thenReturn("{}");
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        when(client.send(any(), any())).thenReturn(response);
        writeStaticFinal("SUPERVISOR_CLIENT", client);
        service.state.backOfficeMessage = "Consigne du responsable";
        service.state.boMessageArrived = true;
        service.ackBackOfficeMessage();
        assertEquals("Consigne du responsable", service.state.backOfficeMessage);
        assertTrue(service.state.boMessageArrived);
        verify(service.state.ticket).setError("APPEL SUPERVISEUR REFUSÉ (503)");
    }

    // --- Submit price modification ---

    /**
     * PRE-ENTRY: a figure typed before the key is applied to the line the opening
     * captured, and the modal closes without ever having been seen.
     *
     * <p>AND IT IS READ IN CENTS, because a remise in euros is an amount: a bare
     * {@code 3} is three cents, exactly as it would be on the amount pad. The
     * register has one spelling for an amount and the pre-entry pad is no
     * exception.
     */
    @Test
    void applyPreEntryAppliesTheTypedFigure() {
        service.state.priceModState.active = true;
        service.state.priceModState.type = PriceModType.REMISE;
        service.state.priceModState.targetUid = "A";
        service.applyPreEntry("3");
        verify(service.endorsementService).requestPriceModification(service.state,
                PriceModType.REMISE, "A", new BigDecimal("0.03"));
        verify(service.state.priceModState).clear();
        verify(service.state).touch();
    }

    /**
     * A COMMA keyed on an amount is DROPPED, not honoured: {@code 2,50} on a
     * remise means the same two euros fifty as {@code 250}, because the figure
     * is read in cents either way. The pad still carries a comma for the
     * quantity gestures, so the separator must parse rather than refuse.
     */
    @Test
    void applyPreEntryReadsTheFrenchComma() {
        service.state.priceModState.active = true;
        service.state.priceModState.type = PriceModType.REMISE;
        service.state.priceModState.targetUid = "A";
        service.applyPreEntry("2,50");
        verify(service.endorsementService).requestPriceModification(service.state,
                PriceModType.REMISE, "A", new BigDecimal("2.50"));
    }

    /**
     * A modal that did NOT open — unknown mode, or no line selected — leaves the
     * typed figure unused: it would otherwise land on a line nobody chose.
     */
    @Test
    void applyPreEntryDoesNothingWhenTheModalDidNotOpen() {
        service.state.priceModState.active = false;
        service.applyPreEntry("3");
        verify(service.state, never()).touch();
        verifyNoInteractions(service.endorsementService);
    }

    /**
     * An unparseable figure is refused by name and applies nothing — the same
     * refusal the modal's own validation makes.
     */
    @Test
    void applyPreEntryRefusesAnUnparseableFigure() {
        service.state.priceModState.active = true;
        service.state.priceModState.type = PriceModType.REMISE;
        service.state.priceModState.targetUid = "A";
        service.applyPreEntry("1.2.3");
        verify(service.state.ticket).setError("VALEUR INVALIDE");
        verify(service.state.priceModState).clear();
        verify(service.state).touch();
        verifyNoInteractions(service.endorsementService);
    }

    /**
     * {@code submitPriceMod()} routes a non-quantity type through the manager
     * endorsement (type not QUANTITY).
     */
    @Test
    void submitPriceModRoutesNonQuantityToEndorsement() {
        BigDecimal value = new BigDecimal("1.5");
        service.submitPriceMod(PriceModType.REMISE, "A", value);
        verify(service.endorsementService).requestPriceModification(service.state, PriceModType.REMISE, "A", value);
        verify(service.state.priceModState).clear();
        verify(service.state).touch();
    }

    /**
     * {@code submitPriceMod()} refuses every discount and rebate gesture when
     * the back office deactivated them (BO-03-07-01): each of the four remise /
     * discount types — line and global (the four operands of
     * {@code isDiscountGesture}) — sets the deactivation error and never reaches
     * the endorsement or the ticket service.
     */
    @Test
    void submitPriceModRefusesDiscountGesturesWhenDeactivated() {
        when(service.posSettingsService.discountEnabled()).thenReturn(false);
        BigDecimal value = new BigDecimal("5");
        service.submitPriceMod(PriceModType.REMISE, "A", value);
        service.submitPriceMod(PriceModType.DISCOUNT, "A", value);
        service.submitPriceMod(PriceModType.GLOBAL_REMISE, null, value);
        service.submitPriceMod(PriceModType.GLOBAL_DISCOUNT, null, value);
        verify(service.state.ticket, times(4)).setError("REMISES DÉSACTIVÉES");
        verifyNoInteractions(service.endorsementService);
        verifyNoInteractions(service.ticketService);
        verify(service.state.priceModState, times(4)).clear();
    }

    /**
     * {@code submitPriceMod()} still routes a price forcing normally when
     * discounts are deactivated (BO-03-07-01): FORCE_PRICE is not a discount
     * gesture (the false arm of {@code isDiscountGesture}), so it reaches the
     * endorsement and no deactivation error is raised.
     */
    @Test
    void submitPriceModAllowsForcePriceWhenDiscountsDeactivated() {
        when(service.posSettingsService.discountEnabled()).thenReturn(false);
        BigDecimal value = new BigDecimal("9");
        service.submitPriceMod(PriceModType.FORCE_PRICE, "A", value);
        verify(service.endorsementService).requestPriceModification(service.state, PriceModType.FORCE_PRICE, "A", value);
        verify(service.state.ticket, never()).setError("REMISES DÉSACTIVÉES");
    }

    /**
     * BO-10-02-16, no ceiling administered: the control is never consulted and
     * the price forcing follows its normal route. This is the register's
     * historical silence and the catalogue's default.
     */
    @Test
    void forcePriceIsUncontrolledWhenNoCeilingIsAdministered() {
        when(service.posSettingsService.controlItemAmountMax()).thenReturn(null);
        BigDecimal value = new BigDecimal("900");
        service.submitPriceMod(PriceModType.FORCE_PRICE, "A", value);
        verify(service.endorsementService, never()).check(any(), any());
        verify(service.endorsementService).requestPriceModification(service.state, PriceModType.FORCE_PRICE, "A", value);
    }

    /**
     * BO-10-02-16, a ceiling and no figure: a null value reaches no comparison
     * and the control stays silent — the null arm of the guard.
     */
    @Test
    void forcePriceIsUncontrolledWhenNoFigureWasKeyed() {
        when(service.posSettingsService.controlItemAmountMax()).thenReturn(new BigDecimal("100"));
        service.submitPriceMod(PriceModType.FORCE_PRICE, "A", null);
        verify(service.endorsementService, never()).check(any(), any());
    }

    /**
     * BO-10-02-16, the figure at the ceiling: the comparison is "beyond", so
     * the ceiling itself passes — the boundary arm.
     */
    @Test
    void forcePriceAtTheCeilingIsNotControlled() {
        when(service.posSettingsService.controlItemAmountMax()).thenReturn(new BigDecimal("100.00"));
        service.submitPriceMod(PriceModType.FORCE_PRICE, "A", new BigDecimal("100.00"));
        verify(service.endorsementService, never()).check(any(), any());
    }

    /**
     * BO-10-02-16, beyond the ceiling with no control administered (NONE): the
     * point is reached and lets the gesture through, saying nothing.
     */
    @Test
    void forcePriceBeyondTheCeilingPassesAtLevelNone() {
        when(service.posSettingsService.controlItemAmountMax()).thenReturn(new BigDecimal("100"));
        when(service.endorsementService.check(service.state, ControlPoint.ITEM_AMOUNT))
                .thenReturn(ControlLevel.NONE);
        BigDecimal value = new BigDecimal("150");
        service.submitPriceMod(PriceModType.FORCE_PRICE, "A", value);
        verify(service.state.ticket, never()).setWarning(any());
        verify(service.state.ticket, never()).setError(any());
        verify(service.endorsementService).requestPriceModification(service.state, PriceModType.FORCE_PRICE, "A", value);
    }

    /**
     * BO-10-02-16, WARN: the operator is told and the gesture goes on — the
     * non-blocking alert the questionnaire asks for.
     */
    @Test
    void forcePriceBeyondTheCeilingWarnsAndGoesOn() {
        when(service.posSettingsService.controlItemAmountMax()).thenReturn(new BigDecimal("100"));
        when(service.endorsementService.check(service.state, ControlPoint.ITEM_AMOUNT))
                .thenReturn(ControlLevel.WARN);
        when(service.endorsementService.controlMessage(ControlPoint.ITEM_AMOUNT, ControlLevel.WARN))
                .thenReturn("MONTANT D'UN ARTICLE — GESTE SIGNALÉ");
        BigDecimal value = new BigDecimal("150");
        service.submitPriceMod(PriceModType.FORCE_PRICE, "A", value);
        verify(service.state.ticket).setWarning("MONTANT D'UN ARTICLE — GESTE SIGNALÉ");
        verify(service.endorsementService).requestPriceModification(service.state, PriceModType.FORCE_PRICE, "A", value);
    }

    /**
     * BO-10-02-16, BLOCK: the gesture is refused, nothing is applied and no
     * endorsement is offered — no credential at the register lifts it.
     */
    @Test
    void forcePriceBeyondTheCeilingIsRefusedAtLevelBlock() {
        when(service.posSettingsService.controlItemAmountMax()).thenReturn(new BigDecimal("100"));
        when(service.endorsementService.check(service.state, ControlPoint.ITEM_AMOUNT))
                .thenReturn(ControlLevel.BLOCK);
        when(service.endorsementService.controlMessage(ControlPoint.ITEM_AMOUNT, ControlLevel.BLOCK))
                .thenReturn("MONTANT D'UN ARTICLE — GESTE INTERDIT PAR LE PARAMÉTRAGE");
        service.submitPriceMod(PriceModType.FORCE_PRICE, "A", new BigDecimal("150"));
        verify(service.state.ticket).setError("MONTANT D'UN ARTICLE — GESTE INTERDIT PAR LE PARAMÉTRAGE");
        verify(service.endorsementService, never()).requestPriceModification(any(), any(), any(), any());
        verify(service.endorsementService, never()).gestureAuthorized(any(), anyString());
        verify(service.state.priceModState).clear();
        verify(service.state).touch();
    }

    /**
     * BO-10-02-16, ENDORSE: the gesture is parked for a manager by the CONTROL
     * itself, without the register ever asking whether the price gesture was
     * otherwise free — which is what distinguishes this route from the normal
     * one.
     */
    @Test
    void forcePriceBeyondTheCeilingIsParkedAtLevelEndorse() {
        when(service.posSettingsService.controlItemAmountMax()).thenReturn(new BigDecimal("100"));
        when(service.endorsementService.check(service.state, ControlPoint.ITEM_AMOUNT))
                .thenReturn(ControlLevel.ENDORSE);
        BigDecimal value = new BigDecimal("150");
        service.submitPriceMod(PriceModType.FORCE_PRICE, "A", value);
        verify(service.endorsementService).requestPriceModification(service.state, PriceModType.FORCE_PRICE, "A", value);
        verify(service.endorsementService, never()).gestureAuthorized(any(), anyString());
        verify(service.state.ticket, never()).setError(any());
    }

    /**
     * BO-10-02-16, A RETURN IS PLAFONNED LIKE A SALE: a negative figure beyond
     * the ceiling in absolute value reaches the control, which is what
     * "aussi bien aux ventes qu'aux retours" means.
     */
    @Test
    void aNegativeForcedPriceIsComparedInAbsoluteValue() {
        when(service.posSettingsService.controlItemAmountMax()).thenReturn(new BigDecimal("100"));
        when(service.endorsementService.check(service.state, ControlPoint.ITEM_AMOUNT))
                .thenReturn(ControlLevel.BLOCK);
        when(service.endorsementService.controlMessage(ControlPoint.ITEM_AMOUNT, ControlLevel.BLOCK))
                .thenReturn("REFUS");
        service.submitPriceMod(PriceModType.FORCE_PRICE, "A", new BigDecimal("-150"));
        verify(service.state.ticket).setError("REFUS");
    }

    /**
     * BO-10-02-16, the control watches the FORCED PRICE only: a remise beyond
     * the same ceiling is not its business, and the ceiling is never read.
     */
    @Test
    void theItemAmountControlLeavesOtherPriceGesturesAlone() {
        when(service.posSettingsService.controlItemAmountMax()).thenReturn(new BigDecimal("1"));
        service.submitPriceMod(PriceModType.REMISE, "A", new BigDecimal("150"));
        verify(service.endorsementService, never()).check(any(), any());
        verify(service.endorsementService).requestPriceModification(
                service.state, PriceModType.REMISE, "A", new BigDecimal("150"));
    }

    /**
     * {@code submitPriceMod()} applies a valid quantity directly on a unit line
     * (type QUANTITY: unit line, non-negative, whole value in range).
     */
    @Test
    void submitPriceModAppliesValidQuantity() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("2.00"), BigDecimal.ONE);
        service.state.ticket.items.add(it);
        service.submitPriceMod(PriceModType.QUANTITY, "A", new BigDecimal("3"));
        assertEquals(0, BigDecimal.valueOf(3).compareTo(it.quantity));
        verify(service.ticketService).recalculateTotal(service.state);
        verify(service.state.priceModState).clear();
        verify(service.state).touch();
    }

    /**
     * {@code submitPriceMod()} on QUANTITY without a line arms the figure for the
     * NEXT article, on both arms that mean "no line": no uid at all, and the empty
     * uid the modal posts when it opened on none ({@code LC-02-13-04/05/06}).
     */
    @Test
    void submitPriceModQuantityWithoutLineArmsTheNextArticle() {
        service.submitPriceMod(PriceModType.QUANTITY, null, new BigDecimal("3"));
        assertEquals(new BigDecimal("3"), service.state.armedQuantity);
        verify(service.state.ticket).setNotice("QUANTITÉ 3 — SAISISSEZ L'ARTICLE");
        service.state.armedQuantity = null;
        service.submitPriceMod(PriceModType.QUANTITY, "   ", new BigDecimal("4"));
        assertEquals(new BigDecimal("4"), service.state.armedQuantity);
        verify(service.state.ticket).setNotice("QUANTITÉ 4 — SAISISSEZ L'ARTICLE");
        verify(service.state.ticket, never()).setError("LIGNE INTROUVABLE");
        verify(service.ticketService, never()).recalculateTotal(any());
    }

    /**
     * {@code submitPriceMod()} reports an unknown line when the uid matches no
     * item (item null).
     */
    @Test
    void submitPriceModQuantityReportsLineNotFound() {
        service.submitPriceMod(PriceModType.QUANTITY, "MISSING", new BigDecimal("3"));
        verify(service.state.ticket).setError("LIGNE INTROUVABLE");
        verify(service.ticketService, never()).recalculateTotal(any());
        verify(service.state.priceModState).clear();
    }

    /**
     * {@code submitPriceMod()} REFUSES a quantity on a weighed line
     * ({@code LC-02-13-15}: plu carried, so the quantity is a weight the scale
     * measured and no key may rewrite it — the weighed leg of the guard). The
     * weight stays what was weighed and the tare resolver is never consulted:
     * typing a weight by hand is another gesture on another key
     * ({@code LC-02-02-03}).
     */
    @Test
    void submitPriceModQuantityRefusedOnWeighedLine() {
        TicketState.TicketItem it = item("A", "123", "1000", BigDecimal.ONE, new BigDecimal("1.234"));
        service.state.ticket.items.add(it);
        service.submitPriceMod(PriceModType.QUANTITY, "A", new BigDecimal("3"));
        verify(service.state.ticket).setError("QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE");
        assertEquals(0, new BigDecimal("1.234").compareTo(it.quantity));
        verify(service.ticketService, never()).recalculateTotal(any());
        verify(service.tareService, never()).consume(any());
    }

    /**
     * {@code submitPriceMod()} refuses a DECIMAL weight on a weighed line too:
     * the refusal is about the line and not about the figure, so 0,850 keyed on
     * 1,234 kg of bananas changes nothing either.
     */
    @Test
    void submitPriceModQuantityRefusedOnWeighedLineWhateverTheFigure() {
        TicketState.TicketItem it = item("A", "123", "1000", BigDecimal.ONE, new BigDecimal("1.234"));
        service.state.ticket.items.add(it);
        service.submitPriceMod(PriceModType.QUANTITY, "A", new BigDecimal("0.850"));
        verify(service.state.ticket).setError("QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE");
        assertEquals(0, new BigDecimal("1.234").compareTo(it.quantity));
        verify(service.ticketService, never()).recalculateTotal(any());
    }

    /**
     * {@code submitPriceMod()} applies a DECIMAL quantity on a DECIMAL-QUANTITY
     * line ({@code LC-02-13-02}: a hose sold by the metre, a tablecloth by the
     * square metre). The figure is stored exactly as keyed — no rounding to the
     * unit — and the total is recomputed.
     */
    @Test
    void submitPriceModQuantityAppliesDecimalOnDecimalQuantityLine() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("4.99"), BigDecimal.ONE);
        it.decimalQuantity = true;
        service.state.ticket.items.add(it);
        service.submitPriceMod(PriceModType.QUANTITY, "A", new BigDecimal("2.360"));
        assertEquals(0, new BigDecimal("2.360").compareTo(it.quantity));
        verify(service.ticketService).recalculateTotal(service.state);
    }

    /**
     * A whole figure on a DECIMAL-QUANTITY line is applied as keyed: the decimal
     * family accepts the integers too (the no-decimal case of the same branch).
     */
    @Test
    void submitPriceModQuantityAppliesWholeFigureOnDecimalQuantityLine() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("4.99"), BigDecimal.ONE);
        it.decimalQuantity = true;
        service.state.ticket.items.add(it);
        service.submitPriceMod(PriceModType.QUANTITY, "A", new BigDecimal("3"));
        assertEquals(0, BigDecimal.valueOf(3).compareTo(it.quantity));
        verify(service.ticketService).recalculateTotal(service.state);
    }

    /**
     * The upper bound is the same 999 as the unit family, and it is INCLUSIVE on
     * a DECIMAL-QUANTITY line (the above-999 leg false at its boundary).
     */
    @Test
    void submitPriceModQuantityAcceptsTheCeilingOnDecimalQuantityLine() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("4.99"), BigDecimal.ONE);
        it.decimalQuantity = true;
        service.state.ticket.items.add(it);
        service.submitPriceMod(PriceModType.QUANTITY, "A", new BigDecimal("999.000"));
        assertEquals(0, new BigDecimal("999").compareTo(it.quantity));
        verify(service.ticketService).recalculateTotal(service.state);
    }

    /**
     * {@code submitPriceMod()} rejects a null figure on a DECIMAL-QUANTITY line
     * (value-null leg) with the plain invalid-quantity message, the range
     * message of the unit family naming a range that is not this one.
     */
    @Test
    void submitPriceModQuantityRejectsNullOnDecimalQuantityLine() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("4.99"), BigDecimal.ONE);
        it.decimalQuantity = true;
        service.state.ticket.items.add(it);
        service.submitPriceMod(PriceModType.QUANTITY, "A", null);
        verify(service.state.ticket).setError("QUANTITÉ INVALIDE");
        verify(service.ticketService, never()).recalculateTotal(any());
    }

    /**
     * {@code submitPriceMod()} rejects a ZERO figure on a DECIMAL-QUANTITY line
     * (signum leg, zero arm): nothing is sold by the zero metre.
     */
    @Test
    void submitPriceModQuantityRejectsZeroOnDecimalQuantityLine() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("4.99"), BigDecimal.ONE);
        it.decimalQuantity = true;
        service.state.ticket.items.add(it);
        service.submitPriceMod(PriceModType.QUANTITY, "A", BigDecimal.ZERO);
        verify(service.state.ticket).setError("QUANTITÉ INVALIDE");
        assertEquals(0, BigDecimal.ONE.compareTo(it.quantity));
    }

    /**
     * {@code submitPriceMod()} rejects a NEGATIVE figure on a DECIMAL-QUANTITY
     * line (signum leg, negative arm).
     */
    @Test
    void submitPriceModQuantityRejectsNegativeOnDecimalQuantityLine() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("4.99"), BigDecimal.ONE);
        it.decimalQuantity = true;
        service.state.ticket.items.add(it);
        service.submitPriceMod(PriceModType.QUANTITY, "A", new BigDecimal("-2.5"));
        verify(service.state.ticket).setError("QUANTITÉ INVALIDE");
        assertEquals(0, BigDecimal.ONE.compareTo(it.quantity));
    }

    /**
     * {@code submitPriceMod()} rejects a figure finer than three decimals on a
     * DECIMAL-QUANTITY line (scale leg): the register states quantities to the
     * thousandth and keeps no digit it would not print.
     */
    @Test
    void submitPriceModQuantityRejectsTooManyDecimalsOnDecimalQuantityLine() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("4.99"), BigDecimal.ONE);
        it.decimalQuantity = true;
        service.state.ticket.items.add(it);
        service.submitPriceMod(PriceModType.QUANTITY, "A", new BigDecimal("2.3605"));
        verify(service.state.ticket).setError("QUANTITÉ INVALIDE");
        assertEquals(0, BigDecimal.ONE.compareTo(it.quantity));
    }

    /**
     * {@code submitPriceMod()} rejects a figure above 999 on a DECIMAL-QUANTITY
     * line (above-999 leg).
     */
    @Test
    void submitPriceModQuantityRejectsAboveMaxOnDecimalQuantityLine() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("4.99"), BigDecimal.ONE);
        it.decimalQuantity = true;
        service.state.ticket.items.add(it);
        service.submitPriceMod(PriceModType.QUANTITY, "A", new BigDecimal("999.001"));
        verify(service.state.ticket).setError("QUANTITÉ INVALIDE");
        assertEquals(0, BigDecimal.ONE.compareTo(it.quantity));
    }

    /**
     * {@code submitPriceMod()} refuses a quantity on a PRICE-EMBEDDED sticker
     * line: one physical sticker is one object at its printed total, never a
     * multipliable line.
     */
    @Test
    void submitPriceModQuantityRefusedOnPriceEmbeddedLine() {
        TicketState.TicketItem it = item("A", "123", "1000", BigDecimal.ONE, BigDecimal.ONE);
        it.priceEmbedded = true;
        service.state.ticket.items.add(it);
        service.submitPriceMod(PriceModType.QUANTITY, "A", new BigDecimal("3"));
        verify(service.state.ticket).setError("QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE");
        verify(service.ticketService, never()).recalculateTotal(any());
    }

    /**
     * {@code submitPriceMod()} refuses a quantity on a MONEY-PRODUCT line
     * (gift card): money is not multiplied by a gesture.
     */
    @Test
    void submitPriceModQuantityRefusedOnMoneyProductLine() {
        TicketState.TicketItem it = item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE);
        it.moneyProduct = true;
        service.state.ticket.items.add(it);
        service.submitPriceMod(PriceModType.QUANTITY, "A", new BigDecimal("3"));
        verify(service.state.ticket).setError("QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE");
        verify(service.ticketService, never()).recalculateTotal(any());
    }

    /**
     * {@code submitPriceMod()} refuses a quantity on a line with no EAN
     * (empty plu, ean null: first paren true, ean-present arm false).
     */
    @Test
    void submitPriceModQuantityRefusedWhenEanNull() {
        service.state.ticket.items.add(item("A", null, "", BigDecimal.ONE, BigDecimal.ONE));
        service.submitPriceMod(PriceModType.QUANTITY, "A", new BigDecimal("3"));
        verify(service.state.ticket).setError("QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE");
    }

    /**
     * {@code submitPriceMod()} refuses a quantity on a line with an empty EAN
     * (empty plu, empty ean: ean-present true, ean-non-empty arm false).
     */
    @Test
    void submitPriceModQuantityRefusedWhenEanEmpty() {
        service.state.ticket.items.add(item("A", "", "", BigDecimal.ONE, BigDecimal.ONE));
        service.submitPriceMod(PriceModType.QUANTITY, "A", new BigDecimal("3"));
        verify(service.state.ticket).setError("QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE");
    }

    /**
     * {@code submitPriceMod()} refuses a quantity on a line carrying a PLU but
     * NO EAN: the hasEan leg is false AND the weighed leg is true, so the line is
     * refused twice over — it is a weighing whose article the catalog no longer
     * names.
     */
    @Test
    void submitPriceModQuantityRefusedWhenPluPresentButEanNull() {
        service.state.ticket.items.add(item("A", null, "1000", BigDecimal.ONE, BigDecimal.ONE));
        service.submitPriceMod(PriceModType.QUANTITY, "A", new BigDecimal("3"));
        verify(service.state.ticket).setError("QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE");
        verify(service.ticketService, never()).recalculateTotal(any());
    }

    /**
     * {@code submitPriceMod()} refuses a quantity on a negative unit line
     * (unit line true, negative total: signum arm true).
     */
    @Test
    void submitPriceModQuantityRefusedWhenNegativeTotal() {
        service.state.ticket.items.add(item("A", "123", null, new BigDecimal("-1.00"), BigDecimal.ONE));
        service.submitPriceMod(PriceModType.QUANTITY, "A", new BigDecimal("3"));
        verify(service.state.ticket).setError("QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE");
    }

    /**
     * {@code submitPriceMod()} rejects a null quantity value
     * (value null arm true).
     */
    @Test
    void submitPriceModQuantityRejectsNullValue() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.submitPriceMod(PriceModType.QUANTITY, "A", null);
        verify(service.state.ticket).setError("QUANTITÉ INVALIDE (1-999)");
        verify(service.ticketService, never()).recalculateTotal(any());
    }

    /**
     * {@code submitPriceMod()} rejects a fractional quantity
     * (scale arm true).
     */
    @Test
    void submitPriceModQuantityRejectsFractionalValue() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.submitPriceMod(PriceModType.QUANTITY, "A", new BigDecimal("1.5"));
        verify(service.state.ticket).setError("QUANTITÉ INVALIDE (1-999)");
    }

    /**
     * {@code submitPriceMod()} rejects a quantity below one
     * (below-one arm true).
     */
    @Test
    void submitPriceModQuantityRejectsBelowOne() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.submitPriceMod(PriceModType.QUANTITY, "A", new BigDecimal("0"));
        verify(service.state.ticket).setError("QUANTITÉ INVALIDE (1-999)");
    }

    /**
     * {@code submitPriceMod()} rejects a quantity above 999
     * (above-999 arm true).
     */
    @Test
    void submitPriceModQuantityRejectsAboveMax() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.submitPriceMod(PriceModType.QUANTITY, "A", new BigDecimal("1000"));
        verify(service.state.ticket).setError("QUANTITÉ INVALIDE (1-999)");
    }

    // --- submitPriceMod without endorsement (applyGestureDirectly) ---

    /**
     * Adds a ticket line with the given uid to the real items list.
     *
     * @param uid the uid to assign
     * @return the created item
     */
    private TicketState.TicketItem addLine(String uid) {
        TicketState.TicketItem item = new TicketState.TicketItem(
                "3000", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, new BigDecimal("0.2000"));
        item.uid = uid;
        service.state.ticket.items.add(item);
        return item;
    }

    /**
     * With the endorsement ceremony disabled, a global gesture is applied
     * directly (disabled-routing arm, GLOBAL_ arm) without a manager request.
     */
    @Test
    void submitPriceModDisabledAppliesGlobalDirectly() {
        when(service.endorsementService.gestureAuthorized(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        BigDecimal value = new BigDecimal("10");
        service.submitPriceMod(PriceModType.GLOBAL_DISCOUNT, "A", value);
        verify(service.ticketService).applyGlobalDiscount(service.state, PriceModType.GLOBAL_DISCOUNT, value);
        verify(service.endorsementService, never()).requestPriceModification(any(), any(), any(), any());
        verify(service.state.priceModState).clear();
        verify(service.state).touch();
    }

    /**
     * The direct gesture reports an unknown line when the uid matches nothing
     * (type-null arm of the global guard, item-null arm).
     */
    @Test
    void gestureDirectlyReportsMissingLine() {
        when(service.endorsementService.gestureAuthorized(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        service.submitPriceMod(null, "MISSING", new BigDecimal("1"));
        verify(service.state.ticket).setError("LIGNE INTROUVABLE");
        verify(service.ticketService, never()).recalculateTotal(any());
    }

    /**
     * The direct gesture applies a REMISE on the target line (REMISE arm) and
     * recomputes.
     */
    @Test
    void gestureDirectlyAppliesRemise() {
        when(service.endorsementService.gestureAuthorized(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        TicketState.TicketItem item = addLine("A");
        BigDecimal value = new BigDecimal("2");
        service.submitPriceMod(PriceModType.REMISE, "A", value);
        verify(service.ticketService).applyRemise(item, value);
        verify(service.ticketService).recalculateTotal(service.state);
    }

    /**
     * The direct gesture applies a DISCOUNT on the target line (DISCOUNT arm).
     */
    @Test
    void gestureDirectlyAppliesDiscount() {
        when(service.endorsementService.gestureAuthorized(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        TicketState.TicketItem item = addLine("A");
        BigDecimal value = new BigDecimal("15");
        service.submitPriceMod(PriceModType.DISCOUNT, "A", value);
        verify(service.ticketService).applyDiscount(item, value);
        verify(service.ticketService).recalculateTotal(service.state);
    }

    /**
     * The direct gesture forces a price on the target line (FORCE_PRICE arm).
     */
    @Test
    void gestureDirectlyForcesPrice() {
        when(service.endorsementService.gestureAuthorized(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        TicketState.TicketItem item = addLine("A");
        BigDecimal value = new BigDecimal("5");
        service.submitPriceMod(PriceModType.FORCE_PRICE, "A", value);
        verify(service.ticketService).forcePrice(item, value);
        verify(service.ticketService).recalculateTotal(service.state);
    }

    /**
     * The direct gesture on a mode the line dispatch has no arm for applies
     * nothing yet still recomputes (none-of-the-three arm, null type).
     */
    @Test
    void gestureDirectlyUnknownTypeStillRecalculates() {
        when(service.endorsementService.gestureAuthorized(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        TicketState.TicketItem item = addLine("A");
        service.submitPriceMod(null, "A", new BigDecimal("1"));
        verify(service.ticketService, never()).applyRemise(any(), any());
        verify(service.ticketService, never()).applyDiscount(any(), any());
        verify(service.ticketService, never()).forcePrice(any(), any());
        verify(service.ticketService).recalculateTotal(service.state);
    }

    // --- printOperatorBadge ---

    /**
     * Builds a Panache query whose {@code firstResult} resolves to the value.
     *
     * @param employee the employee the query returns, or null
     * @return the mocked query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<Employee> employeeQuery(Employee employee) {
        PanacheQuery<Employee> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(employee);
        return query;
    }

    /**
     * Builds an employee with the given activity.
     *
     * @param active whether the employee is active
     * @return the employee mock
     */
    private Employee activeEmployee(boolean active) {
        Employee employee = mock(Employee.class);
        employee.active = active;
        return employee;
    }

    /**
     * {@code printOperatorBadge} resolves an operator by badge id, prints the
     * badge and confirms (badge-found arm, active arm).
     */
    @Test
    void printOperatorBadgeByBadgeId() {
        Employee employee = activeEmployee(true);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Employee> byBadge = employeeQuery(employee);
            ms.when(() -> Employee.find("badgeId", "5")).thenReturn(byBadge);
            service.printOperatorBadge("5");
        }
        verify(service.ticketPrinterService).printOperatorBadge(employee);
        verify(service.state.ticket).setError("BADGE OPÉRATEUR IMPRIMÉ");
    }

    /**
     * {@code printOperatorBadge} falls back to the login name when the badge
     * id matches nothing (badge-null arm, login-found arm).
     */
    @Test
    void printOperatorBadgeByLoginName() {
        Employee employee = activeEmployee(true);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Employee> byBadge = employeeQuery(null);
            PanacheQuery<Employee> byLogin = employeeQuery(employee);
            ms.when(() -> Employee.find("badgeId", "jdupont")).thenReturn(byBadge);
            ms.when(() -> Employee.find("loginName", "jdupont")).thenReturn(byLogin);
            service.printOperatorBadge("jdupont");
        }
        verify(service.ticketPrinterService).printOperatorBadge(employee);
        verify(service.state.ticket).setError("BADGE OPÉRATEUR IMPRIMÉ");
    }

    /**
     * {@code printOperatorBadge} reports an unknown operator when neither
     * lookup matches (both-null arm), printing nothing.
     */
    @Test
    void printOperatorBadgeNotFound() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Employee> byBadge = employeeQuery(null);
            PanacheQuery<Employee> byLogin = employeeQuery(null);
            ms.when(() -> Employee.find("badgeId", "x")).thenReturn(byBadge);
            ms.when(() -> Employee.find("loginName", "x")).thenReturn(byLogin);
            service.printOperatorBadge("x");
        }
        verify(service.state.ticket).setError("OPÉRATEUR INTROUVABLE (x)");
        verify(service.ticketPrinterService, never()).printOperatorBadge(any());
    }

    /**
     * {@code printOperatorBadge} reports an unknown operator for a deactivated
     * one (inactive arm), printing nothing.
     */
    @Test
    void printOperatorBadgeInactive() {
        Employee employee = activeEmployee(false);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Employee> byBadge = employeeQuery(employee);
            ms.when(() -> Employee.find("badgeId", "5")).thenReturn(byBadge);
            service.printOperatorBadge("5");
        }
        verify(service.state.ticket).setError("OPÉRATEUR INTROUVABLE (5)");
        verify(service.ticketPrinterService, never()).printOperatorBadge(any());
    }

    // --------------------------------------------------
    // The "à enlever" key (LC-02-08-01/02/03)
    // --------------------------------------------------

    /**
     * With a line EXPLICITLY selected, the key marks that line, clears the selection
     * and says so ({@code LC-02-08-03}).
     */
    @Test
    void collectKeyMarksTheSelectedLine() {
        TicketState.TicketItem line = new TicketState.TicketItem(
                "3017620422003", null, "PATE", new BigDecimal("2.00"),
                BigDecimal.ONE, new BigDecimal("0.20"));
        when(service.state.getSelectedItem()).thenReturn(line);
        service.toggleCollect();
        assertTrue(line.toCollect);
        assertEquals(-1, service.state.selectedTicketIndex);
        assertFalse(service.state.collectArmed);
        verify(service.state.ticket).setNotice("ARTICLE MARQUÉ À ENLEVER");
    }

    /**
     * Pressed again on a line already marked, it unmarks it — the other arm of the
     * toggle, and the way a cashier corrects themselves.
     */
    @Test
    void collectKeyUnmarksAnAlreadyMarkedLine() {
        TicketState.TicketItem line = new TicketState.TicketItem(
                "3017620422003", null, "PATE", new BigDecimal("2.00"),
                BigDecimal.ONE, new BigDecimal("0.20"));
        line.toCollect = true;
        when(service.state.getSelectedItem()).thenReturn(line);
        service.toggleCollect();
        assertFalse(line.toCollect);
        verify(service.state.ticket).setNotice("MARQUAGE À ENLEVER RETIRÉ");
    }

    /**
     * With NOTHING selected, the key arms the next article instead
     * ({@code LC-02-08-02}) — the implicit "last line" fallback the other line
     * gestures use is deliberately not applied here.
     */
    @Test
    void collectKeyArmsTheNextArticleWhenNothingIsSelected() {
        when(service.state.getSelectedItem()).thenReturn(null);
        service.toggleCollect();
        assertTrue(service.state.collectArmed);
        verify(service.state.ticket).setNotice("PROCHAIN ARTICLE À ENLEVER");
    }

    /**
     * Pressed again while armed, it disarms — the other arm, and the way a cashier
     * who armed it by mistake gets out.
     */
    @Test
    void collectKeyDisarmsWhenPressedAgain() {
        when(service.state.getSelectedItem()).thenReturn(null);
        service.state.collectArmed = true;
        service.toggleCollect();
        assertFalse(service.state.collectArmed);
        verify(service.state.ticket).setNotice("MARQUAGE À ENLEVER ANNULÉ");
    }

    // --------------------------------------------------
    // The restricted-tender bases (LC-09-01-12/14/16/18)
    // --------------------------------------------------

    /**
     * Adds a line carrying the given restricted-tender snapshot.
     *
     * @param total    the line total, tax included
     * @param snapshot the eligibilities the article carried, or null
     */
    private void lineWithTenders(String total, String snapshot) {
        TicketState.TicketItem line = new TicketState.TicketItem(
                "3017620422003", null, "PATE", new BigDecimal(total),
                BigDecimal.ONE, new BigDecimal("0.20"));
        line.restrictedTenders = snapshot;
        service.state.ticket.items.add(line);
    }

    /**
     * Each tender totals only the lines it covers, and the amounts are formatted the
     * French way.
     */
    @Test
    void eachTenderTotalsOnlyItsOwnLines() {
        when(service.posSettingsService.restrictedTenders()).thenReturn("");
        lineWithTenders("10.00",
                com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE);
        lineWithTenders("2.50",
                com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE
                        + "," + com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.ECO_VOUCHER_ELIGIBLE);
        lineWithTenders("7.00", null);
        java.util.List<HomeService.RestrictedTenderRow> rows = service.restrictedTenderRows();
        assertEquals(2, rows.size());
        assertEquals("Titre-restaurant", rows.get(0).label());
        assertEquals("12,50", rows.get(0).amount());
        assertEquals("Éco-chèque", rows.get(1).label());
        assertEquals("2,50", rows.get(1).amount());
    }

    /**
     * A tender no line is eligible for is DROPPED rather than shown at zero: three
     * permanent zeroes beside the total teach a cashier to stop reading the zone.
     */
    @Test
    void aTenderWithoutEligibleLinesIsDropped() {
        when(service.posSettingsService.restrictedTenders()).thenReturn("");
        lineWithTenders("7.00", null);
        assertTrue(service.restrictedTenderRows().isEmpty());
    }

    /**
     * An empty basket shows nothing at all — the empty leg of the loop.
     */
    @Test
    void anEmptyBasketShowsNoBase() {
        when(service.posSettingsService.restrictedTenders()).thenReturn("");
        assertTrue(service.restrictedTenderRows().isEmpty());
    }

    /**
     * The shop's own list is honoured, tenders it did not administer included nowhere
     * ({@code LC-09-01-17/18}).
     */
    @Test
    void theAdministeredListIsHonoured() {
        when(service.posSettingsService.restrictedTenders()).thenReturn(
                "ECO:" + com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.ECO_VOUCHER_ELIGIBLE
                        + ":Éco-chèque");
        lineWithTenders("10.00",
                com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE);
        lineWithTenders("4.00",
                com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.ECO_VOUCHER_ELIGIBLE);
        java.util.List<HomeService.RestrictedTenderRow> rows = service.restrictedTenderRows();
        assertEquals(1, rows.size());
        assertEquals("4,00", rows.get(0).amount());
    }

    /**
     * {@code waitingLabel} (EXP-03) delegates to the administered waiting label.
     */
    @Test
    void waitingLabelDelegatesToSettings() {
        when(service.posSettingsService.waitingLabel()).thenReturn("CLIENT SUIVANT");
        assertEquals("CLIENT SUIVANT", service.waitingLabel());
    }

    /**
     * {@code drawerCapAlert} (EXP-17) is null when the drawer cash is at or below
     * the cap (the null-excess arm).
     */
    @Test
    void drawerCapAlertNullWhenNotOverCap() {
        when(service.cashSessionService.drawerCapExcess()).thenReturn(null);
        assertNull(service.drawerCapAlert());
    }

    /**
     * {@code drawerCapAlert} (EXP-17) warns with the drawer cash when it exceeds
     * the cap (the non-null-excess arm).
     */
    @Test
    void drawerCapAlertWarnsWhenOverCap() {
        when(service.cashSessionService.drawerCapExcess())
                .thenReturn(new java.math.BigDecimal("1200.00"));
        assertEquals("PRÉLÈVEMENT BILLETS — CONTENU TIROIR 1200,00 €", service.drawerCapAlert());
    }

    /**
     * {@code cancelLine()} refuses to cancel the only article (EXP-35): the
     * refusal names the abandon exit, and neither a cancel nor an endorsement runs.
     */
    @Test
    void cancelLineRefusesTheOnlyArticle() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.state.selectedTicketIndex = 0;
        service.state.lastEnteredItemId = "A";
        service.cancelLine();
        verify(service.state.ticket).setError("DERNIER ARTICLE — UTILISEZ L'ABANDON DE TICKET");
        verify(service.ticketService, never()).cancelItemById(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(service.endorsementService, never()).requestAuthorization(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * {@code cancelLine()} DOES cancel the only line when that line is negative —
     * a deposit return, a reprise: nothing was sold, so there is no sale to zero.
     *
     * <p>The other arm of the exemption: a cashier who rang a déconsignation by
     * mistake had no way out but the ticket abandon, because a second press raises
     * the quantity of the same line instead of adding one, and the quantity key
     * refuses a negative line.
     */
    @Test
    void cancelLineCancelsTheOnlyLineWhenNothingWasSold() {
        service.state.ticket.items.add(
                item("D", "3570590000000", null, new BigDecimal("-1.00"), BigDecimal.ONE));
        service.state.selectedTicketIndex = 0;
        service.state.lastEnteredItemId = "D";
        when(service.endorsementService.gestureAuthorized(service.state, "cancel-last"))
                .thenReturn(true);
        service.cancelLine();
        verify(service.state.ticket, never())
                .setError("DERNIER ARTICLE — UTILISEZ L'ABANDON DE TICKET");
        verify(service.ticketService).cancelItemById(service.state, "D");
    }

    /**
     * {@code armCancelByScan()} arms the mode, clears the modal and prompts the
     * cashier to scan (EXP-36).
     */
    @Test
    void armCancelByScanArmsTheMode() {
        service.armCancelByScan();
        assertTrue(service.state.cancelByScanArmed);
        verify(service.state.ticket).setNotice("SCANNEZ L'ARTICLE À ANNULER");
        verify(service.state).touch();
    }

    /**
     * {@code toggleReturnMode()} turns return mode ON from off (EXP-40): the day's
     * articles ring as returns, announced on the message zone. The back office
     * allows the gesture without an endorsement here (LC-05-01-01).
     */
    @Test
    void toggleReturnModeTurnsItOn() {
        service.state.returnMode = false;
        when(service.endorsementService.gestureAuthorized(service.state, "return")).thenReturn(true);
        service.toggleReturnMode();
        assertTrue(service.state.returnMode);
        verify(service.state.ticket).setNotice("MODE RETOUR ACTIVÉ — LES ARTICLES SONT REPRIS");
        verify(service.state).touch();
    }

    /**
     * On a register the back office dedicated to returns (LC-05-01-03), the key
     * forces the mode ON and says so instead of toggling — first guard, mode
     * previously off.
     */
    @Test
    void toggleReturnModeForcesTheModeOnADedicatedRegister() {
        service.state.returnMode = false;
        when(service.posSettingsService.returnDedicatedRegister()).thenReturn(true);
        service.toggleReturnMode();
        assertTrue(service.state.returnMode);
        verify(service.state.ticket).setError("CAISSE DÉDIÉE AUX RETOURS");
        verify(service.state.ticket, never()).setNotice(org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * On a dedicated register the mode cannot be left either (LC-05-01-03, same
     * guard reached with the mode already on).
     */
    @Test
    void toggleReturnModeCannotLeaveADedicatedRegister() {
        service.state.returnMode = true;
        when(service.posSettingsService.returnDedicatedRegister()).thenReturn(true);
        service.toggleReturnMode();
        assertTrue(service.state.returnMode);
        verify(service.state.ticket).setError("CAISSE DÉDIÉE AUX RETOURS");
    }

    /**
     * With mixing forbidden, leaving the mode over a ticket that already carries
     * a returned line is refused (LC-05-01-02): both legs of the compound guard
     * true.
     */
    @Test
    void toggleReturnModeRefusesToLeaveOverAReturnedLine() {
        service.state.returnMode = true;
        when(service.posSettingsService.returnInSaleAllowed()).thenReturn(false);
        service.state.ticket.items.add(
                item("A", "123", null, new BigDecimal("2.00"), new BigDecimal("-1")));
        service.toggleReturnMode();
        assertTrue(service.state.returnMode);
        verify(service.state.ticket).setError("TICKET DE RETOUR — VENTE INTERDITE SUR CE TICKET");
    }

    /**
     * With mixing forbidden but no returned line yet, the mode is left normally
     * (second leg of the compound guard false: nothing has been taken back).
     */
    @Test
    void toggleReturnModeLeavesWhenNothingWasReturnedYet() {
        service.state.returnMode = true;
        when(service.posSettingsService.returnInSaleAllowed()).thenReturn(false);
        service.state.ticket.items.add(
                item("A", "123", null, new BigDecimal("2.00"), BigDecimal.ONE));
        service.toggleReturnMode();
        assertFalse(service.state.returnMode);
        verify(service.state.ticket).setNotice("MODE RETOUR TERMINÉ");
    }

    /**
     * With mixing forbidden and an EMPTY ticket, the mode is left normally
     * (second leg false through the empty line list, no stream match).
     */
    @Test
    void toggleReturnModeLeavesOnAnEmptyTicketWhenMixingForbidden() {
        service.state.returnMode = true;
        when(service.posSettingsService.returnInSaleAllowed()).thenReturn(false);
        service.toggleReturnMode();
        assertFalse(service.state.returnMode);
        verify(service.state.ticket).setNotice("MODE RETOUR TERMINÉ");
    }

    /**
     * When the store ALLOWS mixing, a returned line does not hold the mode
     * (first leg of the compound guard false).
     */
    @Test
    void toggleReturnModeLeavesOverAReturnedLineWhenMixingAllowed() {
        service.state.returnMode = true;
        service.state.ticket.items.add(
                item("A", "123", null, new BigDecimal("2.00"), new BigDecimal("-1")));
        service.toggleReturnMode();
        assertFalse(service.state.returnMode);
        verify(service.state.ticket).setNotice("MODE RETOUR TERMINÉ");
    }

    /**
     * A store forbidding returns without the origin ticket refuses the mode
     * outright (LC-05-02-09/14).
     */
    @Test
    void toggleReturnModeRefusedWhenTheOriginTicketIsMandatory() {
        service.state.returnMode = false;
        when(service.posSettingsService.returnWithoutOriginAllowed()).thenReturn(false);
        service.toggleReturnMode();
        assertFalse(service.state.returnMode);
        verify(service.state.ticket).setError("RETOUR SANS TICKET D'ORIGINE INTERDIT");
        verify(service.endorsementService, never())
                .requestAuthorization(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * BO-03-13-06: where the store forbids the return without its origin ticket,
     * an administered return TYPE carrying that permission lifts the refusal —
     * and the gesture still goes through the supervisor endorsement.
     */
    @Test
    void aReturnTypeMayCarryTheNoOriginPermission() {
        service.state.returnMode = false;
        service.state.returnTypeLabel = "Ticket introuvable";
        when(service.posSettingsService.returnWithoutOriginAllowed()).thenReturn(false);
        when(service.posListService.returnType("Ticket introuvable")).thenReturn(
                new com.intermarche.pos.service.PosSettingsService.ReturnType(
                        "Ticket introuvable", false, false, true, false));
        when(service.endorsementService.gestureAuthorized(service.state, "return"))
                .thenReturn(true);
        service.toggleReturnMode();
        assertTrue(service.state.returnMode);
        verify(service.state.ticket, never()).setError("RETOUR SANS TICKET D'ORIGINE INTERDIT");
    }

    /**
     * The other leg: a type that does NOT carry the permission leaves the
     * store-wide refusal in place.
     */
    @Test
    void aReturnTypeWithoutThePermissionIsStillRefused() {
        service.state.returnMode = false;
        service.state.returnTypeLabel = "Contenant";
        when(service.posSettingsService.returnWithoutOriginAllowed()).thenReturn(false);
        when(service.posListService.returnType("Contenant")).thenReturn(
                new com.intermarche.pos.service.PosSettingsService.ReturnType("Contenant", false));
        service.toggleReturnMode();
        assertFalse(service.state.returnMode);
        verify(service.state.ticket).setError("RETOUR SANS TICKET D'ORIGINE INTERDIT");
    }

    /**
     * BO-03-13-08: a type declared "articles spécifiques" is dispensed from the
     * endorsement — the mode opens without a manager being called, where an
     * ordinary type would have asked for one.
     */
    @Test
    void anExemptReturnTypeNeedsNoEndorsement() {
        service.state.returnMode = false;
        service.state.returnTypeLabel = "Echange";
        when(service.posListService.returnType("Echange")).thenReturn(
                new com.intermarche.pos.service.PosSettingsService.ReturnType(
                        "Echange", false, true, false, false));
        when(service.endorsementService.gestureAuthorized(service.state, "return"))
                .thenReturn(false);
        service.toggleReturnMode();
        assertTrue(service.state.returnMode);
        verify(service.endorsementService, never())
                .requestAuthorization(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * And a type that is NOT exempt still asks: the exemption is the type's, not
     * the gesture's.
     */
    @Test
    void anOrdinaryReturnTypeStillAsksForTheEndorsement() {
        service.state.returnMode = false;
        service.state.returnTypeLabel = "Contenant";
        when(service.posListService.returnType("Contenant")).thenReturn(
                new com.intermarche.pos.service.PosSettingsService.ReturnType("Contenant", false));
        when(service.endorsementService.gestureAuthorized(service.state, "return"))
                .thenReturn(false);
        service.toggleReturnMode();
        assertFalse(service.state.returnMode);
        verify(service.endorsementService).requestAuthorization(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * With returns inside a sale forbidden, entering the mode over a ticket that
     * already carries articles is refused (LC-05-01-01): both legs of the
     * compound guard true.
     */
    @Test
    void toggleReturnModeRefusedInsideASaleTicket() {
        service.state.returnMode = false;
        when(service.posSettingsService.returnInSaleAllowed()).thenReturn(false);
        service.state.ticket.items.add(
                item("A", "123", null, new BigDecimal("2.00"), BigDecimal.ONE));
        service.toggleReturnMode();
        assertFalse(service.state.returnMode);
        verify(service.state.ticket).setError("RETOUR DANS UN TICKET DE VENTE INTERDIT");
    }

    /**
     * With returns inside a sale forbidden, the mode is still accepted on an
     * EMPTY ticket (second leg of the compound guard false).
     */
    @Test
    void toggleReturnModeAcceptedOnAnEmptyTicketWhenSaleMixingForbidden() {
        service.state.returnMode = false;
        when(service.posSettingsService.returnInSaleAllowed()).thenReturn(false);
        when(service.endorsementService.gestureAuthorized(service.state, "return")).thenReturn(true);
        service.toggleReturnMode();
        assertTrue(service.state.returnMode);
        verify(service.state.ticket).setNotice("MODE RETOUR ACTIVÉ — LES ARTICLES SONT REPRIS");
    }

    /**
     * When the store allows mixing, a ticket already carrying articles does not
     * block the mode (first leg of the compound guard false).
     */
    @Test
    void toggleReturnModeAcceptedInsideASaleWhenMixingAllowed() {
        service.state.returnMode = false;
        service.state.ticket.items.add(
                item("A", "123", null, new BigDecimal("2.00"), BigDecimal.ONE));
        when(service.endorsementService.gestureAuthorized(service.state, "return")).thenReturn(true);
        service.toggleReturnMode();
        assertTrue(service.state.returnMode);
        verify(service.state.ticket).setNotice("MODE RETOUR ACTIVÉ — LES ARTICLES SONT REPRIS");
    }

    /**
     * Without the gesture authorization the entry is PARKED for a supervisor
     * (LC-05-01-01): nothing changes until the endorsement is granted.
     */
    @Test
    void toggleReturnModeParksTheSupervisorEndorsement() {
        service.state.returnMode = false;
        when(service.endorsementService.gestureAuthorized(service.state, "return")).thenReturn(false);
        service.toggleReturnMode();
        assertFalse(service.state.returnMode);
        verify(service.endorsementService)
                .requestAuthorization(service.state, HomeService.RETURN_MODE_ACTION);
        verify(service.state.ticket, never()).setNotice(org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * A return TYPE the back office does not administer is refused rather than
     * defaulted (LC-05-04-01): a key that does something else than it says is
     * worse than a key that does nothing.
     */
    @Test
    void startReturnTypeRefusesAnUnknownLabel() {
        when(service.posListService.returnTypes()).thenReturn(java.util.List.of());
        assertFalse(service.startReturnType("Contenant"));
        assertNull(service.state.returnTypeLabel);
        assertFalse(service.state.returnMode);
        verify(service.state.ticket).setError("TYPE DE RETOUR INCONNU");
    }

    /**
     * A null label names no type either (nullity leg of the resolution).
     */
    @Test
    void startReturnTypeRefusesANullLabel() {
        when(service.posListService.returnTypes()).thenReturn(java.util.List.of(
                new com.intermarche.pos.service.PosSettingsService.ReturnType("Contenant", false)));
        assertFalse(service.startReturnType(null));
        assertNull(service.state.returnTypeLabel);
        verify(service.state.ticket).setError("TYPE DE RETOUR INCONNU");
    }

    /**
     * An IN-SALE type enters MODE RETOUR through the ordinary guarded entry,
     * names the type and journals it (LC-05-04-01).
     */
    @Test
    void startReturnTypeEntersReturnModeForAnInSaleType() {
        when(service.posListService.returnTypes()).thenReturn(java.util.List.of(
                new com.intermarche.pos.service.PosSettingsService.ReturnType("Contenant", false)));
        when(service.endorsementService.gestureAuthorized(service.state, "return")).thenReturn(true);
        assertFalse(service.startReturnType("contenant"));
        assertEquals("Contenant", service.state.returnTypeLabel);
        assertTrue(service.state.returnMode);
        verify(service.technicalEventService).log(
                TechnicalEvent.EventType.RETURN_TYPE_SELECTED, "Contenant");
    }

    /**
     * An IN-SALE type tapped while the mode is ALREADY on only re-labels the
     * gesture: toggling would end it (the already-on arm).
     */
    @Test
    void startReturnTypeKeepsTheModeOnWhenItIsAlreadyOn() {
        service.state.returnMode = true;
        when(service.posListService.returnTypes()).thenReturn(java.util.List.of(
                new com.intermarche.pos.service.PosSettingsService.ReturnType("Contenant", false)));
        assertFalse(service.startReturnType("Contenant"));
        assertTrue(service.state.returnMode);
        assertEquals("Contenant", service.state.returnTypeLabel);
    }

    /**
     * A DEDICATED-TICKET type names the type and sends the operator to the return
     * screen without touching the sale (LC-05-04-01, other arm).
     */
    @Test
    void startReturnTypeRoutesADedicatedTicketTypeToTheReturnScreen() {
        when(service.posListService.returnTypes()).thenReturn(java.util.List.of(
                new com.intermarche.pos.service.PosSettingsService.ReturnType("Defectueux", true)));
        assertTrue(service.startReturnType("Defectueux"));
        assertEquals("Defectueux", service.state.returnTypeLabel);
        assertFalse(service.state.returnMode);
        verify(service.technicalEventService).log(
                TechnicalEvent.EventType.RETURN_TYPE_SELECTED, "Defectueux");
    }

    /**
     * Leaving MODE RETOUR drops the chosen type (LC-05-04-01): left behind, it
     * would label the next return with the previous one's type.
     */
    @Test
    void performReturnModeToggleDropsTheChosenTypeOnTheWayOut() {
        service.state.returnMode = true;
        service.state.returnTypeLabel = "Contenant";
        service.performReturnModeToggle();
        assertFalse(service.state.returnMode);
        assertNull(service.state.returnTypeLabel);
    }

    /**
     * Entering MODE RETOUR keeps whatever type named it: the type is chosen
     * BEFORE the mode is entered (the other arm of the same guard).
     */
    @Test
    void performReturnModeToggleKeepsTheChosenTypeOnTheWayIn() {
        service.state.returnMode = false;
        service.state.returnTypeLabel = "Contenant";
        service.performReturnModeToggle();
        assertTrue(service.state.returnMode);
        assertEquals("Contenant", service.state.returnTypeLabel);
    }

    /**
     * {@code performReturnModeToggle()} flips the mode ON with no guard and no
     * endorsement — the arm the endorsement dispatch replays.
     */
    @Test
    void performReturnModeToggleFlipsTheModeOn() {
        service.state.returnMode = false;
        service.performReturnModeToggle();
        assertTrue(service.state.returnMode);
        verify(service.state.ticket).setNotice("MODE RETOUR ACTIVÉ — LES ARTICLES SONT REPRIS");
        verify(service.state).touch();
    }

    /**
     * {@code performReturnModeToggle()} flips the mode OFF (other arm of its
     * ternary).
     */
    @Test
    void performReturnModeToggleFlipsTheModeOff() {
        service.state.returnMode = true;
        service.performReturnModeToggle();
        assertFalse(service.state.returnMode);
        verify(service.state.ticket).setNotice("MODE RETOUR TERMINÉ");
        verify(service.state).touch();
    }

    /**
     * {@code toggleReturnMode()} turns return mode OFF from on (EXP-40, other arm).
     */
    @Test
    void toggleReturnModeTurnsItOff() {
        service.state.returnMode = true;
        service.toggleReturnMode();
        assertFalse(service.state.returnMode);
        verify(service.state.ticket).setNotice("MODE RETOUR TERMINÉ");
        verify(service.state).touch();
    }

    /**
     * {@code cancelByScan()} cancels the line whose EAN matches the scanned code
     * (EXP-36), disarms the mode, and runs through the shared cancel decision.
     */
    @Test
    void cancelByScanCancelsTheMatchingLine() {
        service.state.cancelByScanArmed = true;
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.state.ticket.items.add(item("B", "456", null, BigDecimal.ONE, BigDecimal.ONE));
        when(service.endorsementService.gestureAuthorized(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        service.cancelByScan("123");
        assertFalse(service.state.cancelByScanArmed);
        verify(service.ticketService).cancelItemById(service.state, "A");
    }

    /**
     * {@code cancelByScan()} refuses a code no line carries (EXP-36): the mode is
     * disarmed and nothing is cancelled.
     */
    @Test
    void cancelByScanRefusesAbsentArticle() {
        service.state.cancelByScanArmed = true;
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.cancelByScan("999");
        assertFalse(service.state.cancelByScanArmed);
        verify(service.state.ticket).setError("ARTICLE ABSENT DU TICKET");
        verify(service.ticketService, never()).cancelItemById(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * {@code cancelTicket()} cancels directly when authorized (EXP-32): the
     * settlement is undone and the ticket cancelled without a modal.
     */
    @Test
    void cancelTicketCancelsDirectlyWhenAuthorized() {
        when(service.endorsementService.gestureAuthorized(service.state, "abandon")).thenReturn(true);
        service.state.payment.payments = new ArrayList<>();
        service.cancelTicket();
        verify(service.ticketService).cancelTicket(service.state);
        verify(service.endorsementService, never()).requestAuthorization(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * {@code cancelTicket()} parks the endorsement when the abandon requires one
     * (EXP-32): the ticket is not cancelled until a manager answers.
     */
    @Test
    void cancelTicketRequestsEndorsementWhenNotAuthorized() {
        when(service.endorsementService.gestureAuthorized(service.state, "abandon")).thenReturn(false);
        service.cancelTicket();
        verify(service.endorsementService).requestAuthorization(service.state, "CANCEL_TICKET");
        verify(service.ticketService, never()).cancelTicket(org.mockito.ArgumentMatchers.any());
    }

    /**
     * {@code markSubtotal} confirms when a marker is placed (EXP-43, true arm).
     */
    @Test
    void markSubtotalConfirmsWhenPlaced() {
        when(service.state.ticket.markSubtotal()).thenReturn(true);
        service.markSubtotal();
        verify(service.state.ticket).setNotice("SOUS-TOTAL POSÉ");
    }

    /**
     * {@code markSubtotal} refuses on an empty ticket (EXP-43, false arm).
     */
    @Test
    void markSubtotalRefusesWhenEmpty() {
        when(service.state.ticket.markSubtotal()).thenReturn(false);
        service.markSubtotal();
        verify(service.state.ticket).setError("AUCUN ARTICLE POUR UN SOUS-TOTAL");
    }

    // --------------------------------------------------
    // Campaign 2 — retours/langue added branches
    // --------------------------------------------------

    /**
     * {@code cancelLineByUid} proceeds to cancel when the ticket holds a single
     * line that is NOT the target (the {@code targetUid.equals(first.uid)} false
     * arm): the last-article guard is for cancelling the only line, not for a
     * stray uid, so the cancel runs normally.
     */
    @Test
    void cancelLineByUidProceedsWhenTheSingleLineIsNotTheTarget() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        when(service.endorsementService.gestureAuthorized(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        service.cancelLineByUid("B");
        verify(service.ticketService).cancelItemById(service.state, "B");
    }

    /**
     * BO-06-06-04: a shop that administers justifications for the gesture is
     * asked for one BEFORE anything else — nothing is cancelled yet, and the
     * line is remembered.
     */
    @Test
    void cancelLineByUidAsksForAReasonWhenTheShopAdministersThem() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        when(service.posListService.justificationsOf(
                com.intermarche.pos.service.PosListService.FUNCTION_CANCEL_LINE))
                .thenReturn(java.util.List.of(
                        new com.intermarche.pos.service.PosListService.ListChoice(
                                "ERREUR", "Erreur de saisie")));
        service.cancelLineByUid("B");
        assertEquals("B", service.state.ticket.pendingCancelUid);
        verify(service.ticketService, never()).cancelItemById(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * BO-06-06-04: no list administered, no question — the gesture is the one
     * of before and the register remembers nothing.
     */
    @Test
    void cancelLineByUidAsksNothingWhenNoReasonIsAdministered() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        when(service.endorsementService.gestureAuthorized(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        service.cancelLineByUid("B");
        assertNull(service.state.ticket.pendingCancelUid);
        verify(service.ticketService).cancelItemById(service.state, "B");
    }

    /**
     * BO-06-06-04: the code the operator tapped is recorded and the
     * cancellation resumes, guards included.
     */
    @Test
    void cancelLineWithReasonRecordsTheCodeAndCancels() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.state.ticket.pendingCancelUid = "B";
        when(service.posListService.justificationOf(
                com.intermarche.pos.service.PosListService.FUNCTION_CANCEL_LINE, "ERREUR"))
                .thenReturn(new com.intermarche.pos.service.PosListService.ListChoice(
                        "ERREUR", "Erreur de saisie"));
        when(service.endorsementService.gestureAuthorized(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        service.cancelLineWithReason("ERREUR");
        assertEquals("ERREUR", service.state.ticket.pendingCancelReasonCode);
        assertEquals("Erreur de saisie", service.state.ticket.pendingCancelReasonLabel);
        assertNull(service.state.ticket.pendingCancelUid);
        verify(service.ticketService).cancelItemById(service.state, "B");
    }

    /**
     * BO-06-06-04: a code the administered list does not carry is refused
     * rather than recorded as itself, and the question stays on screen.
     */
    @Test
    void cancelLineWithReasonRefusesAnUnknownCode() {
        service.state.ticket.pendingCancelUid = "B";
        service.cancelLineWithReason("INCONNU");
        verify(service.state.ticket).setError("MOTIF D'ANNULATION INCONNU");
        assertEquals("B", service.state.ticket.pendingCancelUid);
        assertNull(service.state.ticket.pendingCancelReasonCode);
    }

    /**
     * BO-06-06-04: nothing pending, nothing done — a reason posted twice does
     * not cancel a second line.
     */
    @Test
    void cancelLineWithReasonDoesNothingWhenNothingIsPending() {
        service.cancelLineWithReason("ERREUR");
        verify(service.ticketService, never()).cancelItemById(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        assertNull(service.state.ticket.pendingCancelReasonCode);
    }

    /**
     * BO-06-06-04: abandoning the question leaves the line on the ticket and
     * forgets everything the gesture had started.
     */
    @Test
    void cancelLineReasonAbandonedLeavesTheLineOnTheTicket() {
        service.state.ticket.pendingCancelUid = "B";
        service.state.ticket.pendingCancelReasonCode = "ERREUR";
        service.state.ticket.pendingCancelReasonLabel = "Erreur de saisie";
        service.cancelLineReasonAbandoned();
        assertNull(service.state.ticket.pendingCancelUid);
        assertNull(service.state.ticket.pendingCancelReasonCode);
        assertNull(service.state.ticket.pendingCancelReasonLabel);
        verify(service.ticketService, never()).cancelItemById(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * BO-06-06-04: the chooser is served exactly what the referential carries
     * for that gesture.
     */
    @Test
    void cancelReasonsOfferWhatTheReferentialCarries() {
        com.intermarche.pos.service.PosListService.ListChoice offered =
                new com.intermarche.pos.service.PosListService.ListChoice("ERREUR", "Erreur de saisie");
        when(service.posListService.justificationsOf(
                com.intermarche.pos.service.PosListService.FUNCTION_CANCEL_LINE))
                .thenReturn(java.util.List.of(offered));
        assertEquals(java.util.List.of(offered), service.cancelReasons());
    }

    /**
     * {@code cancelTicket} undoes a settlement already taken before cancelling
     * (the {@code !payments.isEmpty()} true arm): a paid-then-abandoned ticket
     * must not leave the payment standing.
     */
    @Test
    void cancelTicketUndoesPaymentsWhenPresent() {
        when(service.endorsementService.gestureAuthorized(service.state, "abandon")).thenReturn(true);
        java.util.List<PaymentState.PaymentEntry> payments = new ArrayList<>();
        payments.add(new PaymentState.PaymentEntry("CASH", BigDecimal.ONE));
        service.state.payment.payments = payments;
        service.cancelTicket();
        verify(service.paymentService).cancelPayments(service.state);
        verify(service.ticketService).cancelTicket(service.state);
    }

    /**
     * {@code cancelByScan} with a null code (the {@code code != null} false arm)
     * matches no line and reports the article as absent.
     */
    @Test
    void cancelByScanWithNullCodeFindsNoLine() {
        service.state.cancelByScanArmed = true;
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.cancelByScan(null);
        assertFalse(service.state.cancelByScanArmed);
        verify(service.state.ticket).setError("ARTICLE ABSENT DU TICKET");
    }

    /**
     * {@code callSupervisor} forwards a non-blank free message in the payload when
     * the store is configured (both true arms of {@code message != null &&
     * !message.isBlank()} on the push path).
     *
     * @throws Exception if the HTTP send stub cannot be wired
     */
    @Test
    @SuppressWarnings("unchecked")
    void callSupervisorForwardsANonBlankMessageWhenConfigured() throws Exception {
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        when(service.syncOutboxService.getStoreUrl()).thenReturn("http://localhost:9999");
        when(service.objectMapper.writeValueAsString(any())).thenReturn("{}");
        service.supervisorToken = Optional.empty();
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(client.send(any(), any())).thenReturn(response);
        writeStaticFinal("SUPERVISOR_CLIENT", client);
        service.callSupervisor("Prix", "étiquette manquante rayon 4");
        verify(service.state.ticket).setNotice("SUPERVISEUR PRÉVENU");
    }

    /**
     * {@code callSupervisor} omits a blank free message from the payload when the
     * store is configured (the {@code message != null} true arm but the
     * {@code !message.isBlank()} false arm on the push path).
     *
     * @throws Exception if the HTTP send stub cannot be wired
     */
    @Test
    @SuppressWarnings("unchecked")
    void callSupervisorOmitsABlankMessageWhenConfigured() throws Exception {
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        when(service.syncOutboxService.getStoreUrl()).thenReturn("http://localhost:9999");
        when(service.objectMapper.writeValueAsString(any())).thenReturn("{}");
        service.supervisorToken = Optional.empty();
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(client.send(any(), any())).thenReturn(response);
        writeStaticFinal("SUPERVISOR_CLIENT", client);
        service.callSupervisor("Autre", "   ");
        verify(service.state.ticket).setNotice("SUPERVISEUR PRÉVENU");
    }

    /**
     * {@code answerSurvey} is a no-op for a null answer with a survey pending (the
     * {@code answer == null} true arm of the guard): nothing is journalled and the
     * survey stays.
     */
    @Test
    void answerSurveyNoOpWhenNullAnswer() {
        PosState.BackOfficeSurvey survey = new PosState.BackOfficeSurvey();
        survey.question = "Q";
        service.state.backOfficeSurvey = survey;
        service.answerSurvey(null);
        verify(service.technicalEventService, org.mockito.Mockito.never())
                .log(org.mockito.ArgumentMatchers.eq(TechnicalEvent.EventType.SUPERVISOR_CALLED),
                        org.mockito.ArgumentMatchers.anyString());
        assertSame(survey, service.state.backOfficeSurvey);
    }

    // --- ANNULER SCANNER : le decrement d'une unite ---

    /**
     * {@code decrementByScan} takes ONE unit off a unit line when nothing was
     * keyed, recomputes the total and says so — the gesture the field expert
     * asked for: a customer taking one of three yogurts back keeps the other
     * two.
     */
    @Test
    void decrementByScanTakesOneUnitWhenNothingWasKeyed() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("2.00"), new BigDecimal("3"));
        service.state.ticket.items.add(it);
        assertTrue(service.decrementByScan("A"));
        assertEquals(0, new BigDecimal("2").compareTo(it.quantity));
        verify(service.ticketService).recalculateTotal(service.state);
        verify(service.state.ticket).setNotice("1 UNITE(S) RETIREE(S) DE LA LIGNE");
    }

    /**
     * A quantity keyed before the gesture is HONOURED — that is how the operator
     * says "take two back" — and consumed, so it never leaks onto the next scan.
     */
    @Test
    void decrementByScanHonoursAndConsumesTheKeyedQuantity() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("2.00"), new BigDecimal("5"));
        service.state.ticket.items.add(it);
        service.state.armedQuantity = new BigDecimal("2");
        assertTrue(service.decrementByScan("A"));
        assertEquals(0, new BigDecimal("3").compareTo(it.quantity));
        assertNull(service.state.armedQuantity);
        verify(service.state.ticket).setNotice("2 UNITE(S) RETIREE(S) DE LA LIGNE");
    }

    /**
     * A keyed quantity at or above what the line carries empties it, so the
     * caller cancels the line outright — with its endorsement. The armed
     * quantity is consumed all the same (the {@code <=} leg).
     */
    @Test
    void decrementByScanHandsBackALineTheQuantityWouldEmpty() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("2.00"), new BigDecimal("2"));
        service.state.ticket.items.add(it);
        service.state.armedQuantity = new BigDecimal("2");
        assertFalse(service.decrementByScan("A"));
        assertEquals(0, new BigDecimal("2").compareTo(it.quantity));
        assertNull(service.state.armedQuantity);
        verify(service.ticketService, never()).recalculateTotal(any());
    }

    /**
     * A line carrying ONE unit is handed back too: one unit off one unit is an
     * empty line, which is a cancellation and not a decrement.
     */
    @Test
    void decrementByScanHandsBackASingleUnitLine() {
        service.state.ticket.items.add(item("A", "123", null, new BigDecimal("2.00"), BigDecimal.ONE));
        assertFalse(service.decrementByScan("A"));
    }

    /**
     * A ZERO armed quantity is read as one unit, not as a refusal — the first
     * leg of the signum guard on the armed figure.
     */
    @Test
    void decrementByScanReadsAZeroArmedQuantityAsOneUnit() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("2.00"), new BigDecimal("4"));
        service.state.ticket.items.add(it);
        service.state.armedQuantity = BigDecimal.ZERO;
        assertTrue(service.decrementByScan("A"));
        assertEquals(0, new BigDecimal("3").compareTo(it.quantity));
    }

    /**
     * A NEGATIVE armed quantity is read as one unit too — the second leg of that
     * guard. A figure that cannot be a count of articles does not become one.
     */
    @Test
    void decrementByScanReadsANegativeArmedQuantityAsOneUnit() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("2.00"), new BigDecimal("4"));
        service.state.ticket.items.add(it);
        service.state.armedQuantity = new BigDecimal("-3");
        assertTrue(service.decrementByScan("A"));
        assertEquals(0, new BigDecimal("3").compareTo(it.quantity));
    }

    /**
     * An unknown uid is handed back without touching anything — the not-found
     * leg, reachable only by calling the method directly.
     */
    @Test
    void decrementByScanHandsBackAnUnknownUid() {
        service.state.ticket.items.add(item("A", "123", null, new BigDecimal("2.00"), new BigDecimal("3")));
        assertFalse(service.decrementByScan("MISSING"));
        verify(service.ticketService, never()).recalculateTotal(any());
    }

    /**
     * A WEIGHED line is handed back: its quantity is a weight
     * ({@code LC-02-13-15}) and half a weighing means nothing — it is cancelled
     * whole, as before.
     */
    @Test
    void decrementByScanHandsBackAWeighedLine() {
        service.state.ticket.items.add(item("A", "123", "1000", new BigDecimal("2.00"), new BigDecimal("1.234")));
        assertFalse(service.decrementByScan("A"));
    }

    /**
     * A PRICE-EMBEDDED sticker line is handed back: one sticker is one object at
     * its printed total ({@code LC-02-13-16}).
     */
    @Test
    void decrementByScanHandsBackAPriceEmbeddedLine() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("2.00"), new BigDecimal("3"));
        it.priceEmbedded = true;
        service.state.ticket.items.add(it);
        assertFalse(service.decrementByScan("A"));
    }

    /**
     * A MONEY-PRODUCT line is handed back: money is not decremented by a scan.
     */
    @Test
    void decrementByScanHandsBackAMoneyProductLine() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("2.00"), new BigDecimal("3"));
        it.moneyProduct = true;
        service.state.ticket.items.add(it);
        assertFalse(service.decrementByScan("A"));
    }

    /**
     * A NEGATIVE line — a deposit return — is handed back: taking a unit off a
     * refund is not what a scan means.
     */
    @Test
    void decrementByScanHandsBackANegativeLine() {
        service.state.ticket.items.add(item("A", "123", null, new BigDecimal("-2.00"), new BigDecimal("3")));
        assertFalse(service.decrementByScan("A"));
    }

    /**
     * {@code cancelByScan} on a line carrying several units DECREMENTS it and
     * never reaches the cancellation path — no endorsement is requested for one
     * unit leaving the basket.
     */
    @Test
    void cancelByScanDecrementsInsteadOfCancelling() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("2.00"), new BigDecimal("3"));
        service.state.ticket.items.add(it);
        service.cancelByScan("123");
        assertEquals(0, new BigDecimal("2").compareTo(it.quantity));
        verify(service.endorsementService, never()).requestAuthorization(any(), anyString());
        verify(service.ticketService, never()).cancelItemById(any(), anyString());
    }

    /**
     * {@code cancelByScan} on a line carrying its LAST unit falls through to the
     * cancellation, which answers to the endorsement as before.
     */
    @Test
    void cancelByScanStillCancelsALineOnItsLastUnit() {
        service.state.ticket.items.add(item("A", "123", null, new BigDecimal("2.00"), BigDecimal.ONE));
        service.state.ticket.items.add(item("B", "456", null, new BigDecimal("1.00"), BigDecimal.ONE));
        when(service.endorsementService.gestureAuthorized(any(), anyString())).thenReturn(true);
        service.cancelByScan("123");
        verify(service.ticketService).cancelItemById(service.state, "A");
    }
}
