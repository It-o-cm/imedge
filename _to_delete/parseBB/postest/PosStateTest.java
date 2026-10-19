package com.intermarche.pos.ui;

import com.intermarche.pos.ui.auth.AuthState;
import com.intermarche.pos.ui.fidelity.FidelityState;
import com.intermarche.pos.ui.payment.PaymentState;
import com.intermarche.pos.ui.ticket.TicketState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PosState}.
 * <p>
 * {@code PosState} is the in-memory composition root of the terminal. Its own
 * logic is limited to a version heartbeat, an end-of-sale reset that delegates
 * to the sub-states, a handful of BigDecimal money computations and the ticket
 * pagination arithmetic. Every collaborator ({@link TicketState},
 * {@link PaymentState}, {@link FidelityState}, {@link AuthState} and
 * {@link PriceModState}) is a pure in-memory state holder, so each is replaced
 * by a Mockito mock: {@code PosState} only ever reads their public fields or
 * invokes their void mutators, both of which a mock serves without booting any
 * Quarkus context. Tests assert absolute expected values and verify delegation.
 */
class PosStateTest {

    /**
     * Builds a {@link PosState} whose collaborators are all fresh mocks, wired
     * onto the public fields so tests can both drive and verify them.
     *
     * @return a POS state with mocked sub-states
     */
    private PosState newState() {
        PosState state = new PosState();
        state.ticket = mock(TicketState.class);
        state.payment = mock(PaymentState.class);
        state.fidelity = mock(FidelityState.class);
        state.auth = mock(AuthState.class);
        state.priceModState = mock(PriceModState.class);
        // The two refusals this state posts itself read the French bundle, so
        // the assertions keep the literal sentence the register shows (LC-13-06).
        state.i18n = TestMessages.french();
        return state;
    }

    /**
     * Builds a list holding the requested number of distinct ticket lines, used
     * to populate the mocked ticket's public {@code items} field.
     *
     * @param count the number of lines to create
     * @return a mutable list of that many ticket items
     */
    private List<TicketState.TicketItem> items(int count) {
        List<TicketState.TicketItem> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new TicketState.TicketItem());
        }
        return list;
    }

    // --------------------------------------------------
    // AgeCheckState.clear
    // --------------------------------------------------

    /**
     * {@code AgeCheckState.clear()} wipes the SIX fields of a parked age
     * prompt at once. All six matter: leaving {@code active} would keep the
     * modal on screen, and leaving {@code kind}/{@code code}/{@code quantity}
     * would let a later confirmation REPLAY a gesture that belongs to a scan
     * already resolved.
     */
    @Test
    void ageCheckClearWipesEveryParkedField() {
        PosState.AgeCheckState ageCheck = new PosState.AgeCheckState();
        ageCheck.active = true;
        ageCheck.productLabel = "VIN ROUGE";
        ageCheck.threshold = 18;
        ageCheck.kind = "EAN_QTY";
        ageCheck.code = "3400018000001";
        ageCheck.quantity = new BigDecimal("2");

        ageCheck.clear();

        assertFalse(ageCheck.active);
        assertNull(ageCheck.productLabel);
        assertEquals(0, ageCheck.threshold);
        assertNull(ageCheck.kind);
        assertNull(ageCheck.code);
        assertNull(ageCheck.quantity);
    }

    /**
     * {@code clear()} is IDEMPOTENT: called on an already-pristine prompt it
     * leaves the same pristine values, so a double confirmation (or a clear
     * on a sale that never armed the gate) is harmless.
     */
    @Test
    void ageCheckClearIsIdempotentOnPristineState() {
        PosState.AgeCheckState ageCheck = new PosState.AgeCheckState();

        ageCheck.clear();

        assertFalse(ageCheck.active);
        assertNull(ageCheck.productLabel);
        assertEquals(0, ageCheck.threshold);
        assertNull(ageCheck.kind);
        assertNull(ageCheck.code);
        assertNull(ageCheck.quantity);
    }

    /**
     * A fresh prompt starts pristine — the state a sale must begin from: no
     * modal, no threshold, nothing parked.
     */
    @Test
    void ageCheckStartsPristine() {
        PosState.AgeCheckState ageCheck = new PosState.AgeCheckState();
        assertFalse(ageCheck.active);
        assertNull(ageCheck.productLabel);
        assertEquals(0, ageCheck.threshold);
        assertNull(ageCheck.kind);
        assertNull(ageCheck.code);
        assertNull(ageCheck.quantity);
    }

    /**
     * {@code touch()} bumps the polling version counter by exactly one.
     */
    @Test
    void touchIncrementsVersion() {
        PosState state = newState();
        state.version = 41;
        state.touch();
        assertEquals(42, state.version);
    }

    /**
     * {@code clearTicket()} delegates to every transactional sub-state and
     * resets the cross-cutting per-sale fields to their pristine values.
     */
    @Test
    void clearTicketResetsAllTransactionalState() {
        PosState state = newState();
        state.selectedTicketIndex = 3;
        state.lastEnteredItemId = "abc";
        state.donationLineUid = "don";
        state.ticketCurrentPage = 5;
        state.clearTicket();
        verify(state.ticket).clear();
        verify(state.fidelity).clear();
        verify(state.payment).reset();
        verify(state.priceModState).clear();
        assertEquals(-1, state.selectedTicketIndex);
        assertNull(state.lastEnteredItemId);
        assertNull(state.donationLineUid);
        assertEquals(0, state.ticketCurrentPage);
    }

    /**
     * {@code clearTicket()} also disarms the quantity key and closes the entry
     * prompt: a figure armed for an article that is never rung, or a prompt left
     * open on a sale that has ended, would land on the NEXT customer's first
     * article ({@code LC-02-13-04}, {@code LC-02-03-01/03}).
     */
    @Test
    void clearTicketDisarmsTheQuantityAndClosesTheEntryPrompt() {
        PosState state = newState();
        state.armedQuantity = new java.math.BigDecimal("3");
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.PRICE;
        state.entryPrompt.ean = "123";
        state.clearTicket();
        assertNull(state.armedQuantity);
        assertFalse(state.entryPrompt.active);
        assertNull(state.entryPrompt.kind);
        assertNull(state.entryPrompt.ean);
    }

    /**
     * {@code clearTicket()} brings the button menu back to ARTICLES: the sale that
     * just ended left the register on the settlement — or the ticket — menu, and the
     * next customer's first gesture is a scan.
     */
    @Test
    void clearTicketBringsTheMenuBackToArticles() {
        PosState state = newState();
        state.menu = PosMenu.REGLEMENT;
        state.clearTicket();
        assertEquals(PosMenu.ARTICLES, state.menu);
    }

    /**
     * A menu the cashier chose DURING a sale survives until that sale ends: nothing
     * but {@code clearTicket()} moves the menu, so selecting the drawer menu and
     * scanning an article leaves the drawer menu up.
     */
    @Test
    void theChosenMenuSurvivesUntilTheTicketEnds() {
        PosState state = newState();
        state.menu = PosMenu.TIROIR;
        state.touch();
        assertEquals(PosMenu.TIROIR, state.menu);
    }

    /**
     * {@code clearTicket()} closes any open supervisor session and disarms the
     * cancel-by-scan mode (EXP-33/36): a supervisor authorization or an armed
     * cancel must never leak into the next customer's sale.
     */
    @Test
    void clearTicketClosesSupervisorSessionAndDisarmsCancelByScan() {
        PosState state = newState();
        state.supervisorSessionBadge = "M1";
        state.cancelByScanArmed = true;
        state.clearTicket();
        assertNull(state.supervisorSessionBadge);
        assertFalse(state.cancelByScanArmed);
    }

    /**
     * The entry prompt clears back to the state a fresh one starts in, so a second
     * suspended add never inherits the first one's figures.
     */
    @Test
    void theEntryPromptClearsBackToItsPristineState() {
        PosState.EntryPromptState prompt = new PosState.EntryPromptState();
        prompt.active = true;
        prompt.kind = PosState.EntryPromptState.QUANTITY;
        prompt.ean = "123";
        prompt.label = "CABLE";
        prompt.unitName = "m";
        prompt.unitPriceFormatted = "4,99";
        prompt.quantity = new java.math.BigDecimal("2.36");
        prompt.price = new java.math.BigDecimal("11.78");
        prompt.clear();
        assertFalse(prompt.active);
        assertNull(prompt.kind);
        assertNull(prompt.ean);
        assertNull(prompt.label);
        assertEquals("", prompt.unitName);
        assertEquals("", prompt.unitPriceFormatted);
        assertEquals(0, java.math.BigDecimal.ONE.compareTo(prompt.quantity));
        assertNull(prompt.price);
    }

    /**
     * The prompt says which figure it is asking for, on both its kinds and on the
     * kind it has before anything is asked.
     */
    @Test
    void theEntryPromptNamesTheFigureItAsksFor() {
        PosState.EntryPromptState prompt = new PosState.EntryPromptState();
        prompt.kind = PosState.EntryPromptState.PRICE;
        assertTrue(prompt.isPriceKind());
        assertEquals("op.prixSaisir", prompt.getTitleKey());
        prompt.kind = PosState.EntryPromptState.QUANTITY;
        assertFalse(prompt.isPriceKind());
        assertEquals("op.quantiteSaisir", prompt.getTitleKey());
        prompt.kind = null;
        assertFalse(prompt.isPriceKind());
        assertEquals("op.quantiteSaisir", prompt.getTitleKey());
    }

    /**
     * {@code clearPayments()} clears only the payments and bumps the version.
     */
    @Test
    void clearPaymentsDelegatesAndTouches() {
        PosState state = newState();
        state.version = 7;
        state.clearPayments();
        verify(state.payment).clearPayments();
        assertEquals(8, state.version);
    }

    /**
     * {@code getRemaining()} subtracts the paid amount from the ticket total and
     * rounds HALF_UP to two decimals.
     */
    @Test
    void getRemainingSubtractsAndRoundsHalfUp() {
        PosState state = newState();
        state.ticket.totalAmount = new BigDecimal("10.005");
        state.payment.paidAmount = new BigDecimal("3.00");
        assertEquals(new BigDecimal("7.01"), state.getRemaining());
    }

    /**
     * {@code isNetRefund()} is true when the signed ticket total is strictly
     * negative — the returns outweigh the purchases (EXP-38, negative arm).
     */
    @Test
    void isNetRefundTrueWhenTotalNegative() {
        PosState state = newState();
        state.ticket.totalAmount = new BigDecimal("-5.00");
        assertTrue(state.isNetRefund());
    }

    /**
     * {@code isNetRefund()} is false when the signed total is zero or positive —
     * an ordinary sale to collect (EXP-38, non-negative arm).
     */
    @Test
    void isNetRefundFalseWhenTotalZeroOrPositive() {
        PosState zero = newState();
        zero.ticket.totalAmount = new BigDecimal("0.00");
        assertFalse(zero.isNetRefund());
        PosState positive = newState();
        positive.ticket.totalAmount = new BigDecimal("5.00");
        assertFalse(positive.isNetRefund());
    }

    /**
     * {@code getRefundDueFormatted()} renders the absolute value of the negative
     * remaining with a French comma (EXP-38).
     */
    @Test
    void getRefundDueFormattedRendersAbsoluteRemaining() {
        PosState state = newState();
        state.ticket.totalAmount = new BigDecimal("-10.00");
        state.payment.paidAmount = new BigDecimal("0.00");
        assertEquals("10,00", state.getRefundDueFormatted());
    }

    /**
     * {@code clearTicket()} does NOT turn off return mode (EXP-40): it is a DAY
     * mode that survives the end of a ticket, until the operator toggles it off.
     */
    @Test
    void clearTicketKeepsReturnMode() {
        PosState state = newState();
        state.returnMode = true;
        state.clearTicket();
        assertTrue(state.returnMode);
    }

    /**
     * {@code getRemainingFormatted()} renders the remaining due with a French
     * comma decimal separator.
     */
    @Test
    void getRemainingFormattedUsesComma() {
        PosState state = newState();
        state.ticket.totalAmount = new BigDecimal("7.01");
        state.payment.paidAmount = BigDecimal.ZERO;
        assertEquals("7,01", state.getRemainingFormatted());
    }

    /**
     * {@code getRemainingNumpad()} formats the remaining due through
     * {@code String.format("%.2f", ...)} with no explicit locale, so the decimal
     * separator follows the JVM default locale (a comma under fr-FR here).
     */
    @Test
    void getRemainingNumpadFormatsWithDefaultLocale() {
        PosState state = newState();
        state.ticket.totalAmount = new BigDecimal("7.01");
        state.payment.paidAmount = BigDecimal.ZERO;
        assertEquals(String.format("%.2f", new BigDecimal("7.01")), state.getRemainingNumpad());
    }

    /**
     * {@code getCashRoundedRemaining()} rounds the remaining due to the
     * administered step: with a five-cent step, 7,02 settles at 7,00.
     */
    @Test
    void getCashRoundedRemainingRoundsToStep() {
        PosState state = newState();
        state.ticket.totalAmount = new BigDecimal("7.02");
        state.payment.paidAmount = BigDecimal.ZERO;
        state.cashRoundingStepCents = 5;
        assertEquals(new BigDecimal("7.00"), state.getCashRoundedRemaining());
    }

    /**
     * {@code getCashRoundedRemainingFormatted()} renders the rounded remaining
     * due with a French comma decimal separator.
     */
    @Test
    void getCashRoundedRemainingFormattedUsesComma() {
        PosState state = newState();
        state.ticket.totalAmount = new BigDecimal("7.02");
        state.payment.paidAmount = BigDecimal.ZERO;
        state.cashRoundingStepCents = 5;
        assertEquals("7,00", state.getCashRoundedRemainingFormatted());
    }

    /**
     * {@code getCashRoundedRemainingNumpad()} formats the rounded remaining due
     * through {@code String.format("%.2f", ...)} with no explicit locale.
     */
    @Test
    void getCashRoundedRemainingNumpadFormatsWithDefaultLocale() {
        PosState state = newState();
        state.ticket.totalAmount = new BigDecimal("7.02");
        state.payment.paidAmount = BigDecimal.ZERO;
        state.cashRoundingStepCents = 5;
        assertEquals(String.format("%.2f", new BigDecimal("7.00")), state.getCashRoundedRemainingNumpad());
    }

    /**
     * {@code isCashRoundingVisible()} is false when the shop does not round
     * (first condition {@code cashRoundingStepCents > 1} false), short-circuiting
     * before the amount comparison — this is the France default.
     */
    @Test
    void isCashRoundingVisibleFalseWhenStepNotRounding() {
        PosState state = newState();
        state.ticket.totalAmount = new BigDecimal("7.02");
        state.payment.paidAmount = BigDecimal.ZERO;
        state.cashRoundingStepCents = 1;
        assertFalse(state.isCashRoundingVisible());
    }

    /**
     * {@code isCashRoundingVisible()} is false when the shop rounds (first
     * condition true) but the rounded amount equals the real one (second
     * condition {@code compareTo != 0} false), so no second figure is worth
     * showing.
     */
    @Test
    void isCashRoundingVisibleFalseWhenRoundedEqualsRemaining() {
        PosState state = newState();
        state.ticket.totalAmount = new BigDecimal("7.00");
        state.payment.paidAmount = BigDecimal.ZERO;
        state.cashRoundingStepCents = 5;
        assertFalse(state.isCashRoundingVisible());
    }

    /**
     * {@code isCashRoundingVisible()} is true when the shop rounds (first
     * condition true) and the rounded amount differs from the real one (second
     * condition true): 7,02 rounds to 7,00 on a five-cent step.
     */
    @Test
    void isCashRoundingVisibleTrueWhenRoundedDiffersFromRemaining() {
        PosState state = newState();
        state.ticket.totalAmount = new BigDecimal("7.02");
        state.payment.paidAmount = BigDecimal.ZERO;
        state.cashRoundingStepCents = 5;
        assertTrue(state.isCashRoundingVisible());
    }

    /**
     * {@code isMoneticsDegradedForced()} is false when no forcing was ever set
     * (null guard true arm), and leaves the timestamp null.
     */
    @Test
    void isMoneticsDegradedForcedFalseWhenNeverSet() {
        PosState state = newState();
        state.moneticsDegradedUntil = null;
        assertFalse(state.isMoneticsDegradedForced());
        assertNull(state.moneticsDegradedUntil);
    }

    /**
     * {@code isMoneticsDegradedForced()} EXPIRES a forcing whose end is in the
     * past (null guard false, {@code isBefore(now)} true arm): it clears the
     * timestamp and reports the mode off.
     */
    @Test
    void isMoneticsDegradedForcedExpiresPastForcing() {
        PosState state = newState();
        state.moneticsDegradedUntil = java.time.LocalDateTime.now().minusHours(1);
        assertFalse(state.isMoneticsDegradedForced());
        assertNull(state.moneticsDegradedUntil);
    }

    /**
     * {@code isMoneticsDegradedForced()} reports the mode on and keeps the
     * timestamp while the forcing's end is still in the future (null guard
     * false, {@code isBefore(now)} false arm).
     */
    @Test
    void isMoneticsDegradedForcedTrueWhileStillActive() {
        PosState state = newState();
        java.time.LocalDateTime future = java.time.LocalDateTime.now().plusHours(1);
        state.moneticsDegradedUntil = future;
        assertTrue(state.isMoneticsDegradedForced());
        assertSame(future, state.moneticsDegradedUntil);
    }

    /**
     * {@code getMenus()} returns the five bottom-bar menus in tab order.
     */
    @Test
    void getMenusReturnsAllMenus() {
        PosState state = newState();
        assertSame(PosMenu.ALL, state.getMenus());
    }

    /**
     * {@code isLocked()} reflects a locked authentication state.
     */
    @Test
    void isLockedTrueWhenAuthLocked() {
        PosState state = newState();
        state.auth.isLocked = true;
        assertTrue(state.isLocked());
    }

    /**
     * {@code isLocked()} reflects an unlocked authentication state.
     */
    @Test
    void isLockedFalseWhenAuthUnlocked() {
        PosState state = newState();
        state.auth.isLocked = false;
        assertFalse(state.isLocked());
    }

    /**
     * {@code getOperatorName()} returns the authenticated operator's name.
     */
    @Test
    void getOperatorNameReturnsAuthName() {
        PosState state = newState();
        state.auth.operatorName = "Alice";
        assertEquals("Alice", state.getOperatorName());
    }

    /**
     * {@code getTargetItem()} returns the explicitly selected line when the
     * selection index is within bounds (both {@code >=0} and {@code <size} true).
     */
    @Test
    void getTargetItemReturnsSelectedWhenInBounds() {
        PosState state = newState();
        List<TicketState.TicketItem> list = items(2);
        state.ticket.items = list;
        state.selectedTicketIndex = 0;
        assertSame(list.get(0), state.getTargetItem());
    }

    /**
     * {@code getTargetItem()} falls back to the last line when nothing is
     * selected (index {@code >=0} false) but the ticket is not empty.
     */
    @Test
    void getTargetItemReturnsLastWhenNoSelection() {
        PosState state = newState();
        List<TicketState.TicketItem> list = items(2);
        state.ticket.items = list;
        state.selectedTicketIndex = -1;
        assertSame(list.get(1), state.getTargetItem());
    }

    /**
     * {@code getTargetItem()} falls back to the last line when the selection
     * index is out of range (index {@code >=0} true but {@code <size} false).
     */
    @Test
    void getTargetItemReturnsLastWhenSelectionOutOfRange() {
        PosState state = newState();
        List<TicketState.TicketItem> list = items(2);
        state.ticket.items = list;
        state.selectedTicketIndex = 5;
        assertSame(list.get(1), state.getTargetItem());
    }

    /**
     * {@code getTargetItem()} returns null when the ticket has no lines.
     */
    @Test
    void getTargetItemReturnsNullWhenEmpty() {
        PosState state = newState();
        state.ticket.items = items(0);
        state.selectedTicketIndex = -1;
        assertNull(state.getTargetItem());
    }

    /**
     * {@code getSelectedItem()} returns the selected line when the index is in
     * bounds (both conditions true).
     */
    @Test
    void getSelectedItemReturnsSelectedWhenInBounds() {
        PosState state = newState();
        List<TicketState.TicketItem> list = items(1);
        state.ticket.items = list;
        state.selectedTicketIndex = 0;
        assertSame(list.get(0), state.getSelectedItem());
    }

    /**
     * {@code getSelectedItem()} returns null when nothing is selected (index
     * {@code >=0} false).
     */
    @Test
    void getSelectedItemReturnsNullWhenNoSelection() {
        PosState state = newState();
        state.ticket.items = items(1);
        state.selectedTicketIndex = -1;
        assertNull(state.getSelectedItem());
    }

    /**
     * {@code getSelectedItem()} returns null when the selection index is out of
     * range (index {@code >=0} true but {@code <size} false).
     */
    @Test
    void getSelectedItemReturnsNullWhenOutOfRange() {
        PosState state = newState();
        state.ticket.items = items(1);
        state.selectedTicketIndex = 5;
        assertNull(state.getSelectedItem());
    }

    /**
     * {@code getVisibleItems()} returns an empty list when the ticket is empty.
     */
    @Test
    void getVisibleItemsEmptyWhenNoLines() {
        PosState state = newState();
        state.ticket.items = items(0);
        // The DISPLAY sequence is what the page reads. A real TicketState with no
        // cancelled line answers its own item list; the mock has to say so.
        when(state.ticket.displayItems()).thenReturn(state.ticket.items);
        assertTrue(state.getVisibleItems().isEmpty());
    }

    /**
     * {@code getVisibleItems()} returns the current page slice when the page
     * index is within range (no clamping needed).
     */
    @Test
    void getVisibleItemsReturnsPageSlice() {
        PosState state = newState();
        List<TicketState.TicketItem> list = items(3);
        state.ticket.items = list;
        when(state.ticket.displayItems()).thenReturn(list);
        state.ticketCurrentPage = 0;
        List<TicketState.TicketItem> visible = state.getVisibleItems();
        assertEquals(3, visible.size());
        assertSame(list.get(0), visible.get(0));
    }

    /**
     * {@code getVisibleItems()} clamps an over-large page index down to the last
     * page before slicing.
     */
    @Test
    void getVisibleItemsClampsPageBeyondMax() {
        PosState state = newState();
        List<TicketState.TicketItem> list = items(3);
        state.ticket.items = list;
        when(state.ticket.displayItems()).thenReturn(list);
        state.ticketCurrentPage = 5;
        List<TicketState.TicketItem> visible = state.getVisibleItems();
        assertEquals(0, state.ticketCurrentPage);
        assertEquals(3, visible.size());
    }

    /**
     * {@code showLinePage} brings the view onto the page holding the line with the
     * given uid — a line on a later page moves the display there (P1, multi-page arm).
     */
    @Test
    void showLinePageJumpsToTheLinePage() {
        PosState state = newState();
        List<TicketState.TicketItem> list = items(13);
        list.get(12).uid = "L13";
        state.ticket.items = list;
        state.ticketCurrentPage = 0;
        state.showLinePage("L13");
        assertEquals(2, state.ticketCurrentPage);
    }

    /**
     * {@code showLinePage} on a line of the first page leaves the display on page 0
     * (P1, single-page arm).
     */
    @Test
    void showLinePageStaysOnFirstPageForAFirstPageLine() {
        PosState state = newState();
        List<TicketState.TicketItem> list = items(13);
        list.get(2).uid = "L3";
        state.ticket.items = list;
        state.ticketCurrentPage = 2;
        state.showLinePage("L3");
        assertEquals(0, state.ticketCurrentPage);
    }

    /**
     * {@code showLinePage} is a no-op for an unknown uid: the display stays put
     * (P1, uid-absent arm).
     */
    @Test
    void showLinePageIgnoresUnknownUid() {
        PosState state = newState();
        state.ticket.items = items(13);
        state.ticketCurrentPage = 1;
        state.showLinePage("NOPE");
        assertEquals(1, state.ticketCurrentPage);
    }

    /**
     * {@code showLinePage} is a no-op for a null uid (P1, null arm).
     */
    @Test
    void showLinePageIgnoresNullUid() {
        PosState state = newState();
        state.ticket.items = items(13);
        state.ticketCurrentPage = 1;
        state.showLinePage(null);
        assertEquals(1, state.ticketCurrentPage);
    }

    /**
     * {@code isHasPreviousPage()} is false on the first page.
     */
    @Test
    void hasPreviousPageFalseOnFirstPage() {
        PosState state = newState();
        state.ticketCurrentPage = 0;
        assertFalse(state.isHasPreviousPage());
    }

    /**
     * {@code isHasPreviousPage()} is true past the first page.
     */
    @Test
    void hasPreviousPageTrueBeyondFirstPage() {
        PosState state = newState();
        state.ticketCurrentPage = 1;
        assertTrue(state.isHasPreviousPage());
    }

    /**
     * {@code isHasNextPage()} is false when the current page shows the last line.
     */
    @Test
    void hasNextPageFalseWhenLastPage() {
        PosState state = newState();
        state.ticket.items = items(3);
        state.ticketCurrentPage = 0;
        assertFalse(state.isHasNextPage());
    }

    /**
     * {@code isHasNextPage()} is true when further lines follow the current page.
     */
    @Test
    void hasNextPageTrueWhenMoreLines() {
        PosState state = newState();
        state.ticket.items = items(10);
        state.ticketCurrentPage = 0;
        assertTrue(state.isHasNextPage());
    }

    /**
     * {@code getTicketCurrentPageDisplay()} returns the 1-based page number.
     */
    @Test
    void currentPageDisplayIsOneBased() {
        PosState state = newState();
        state.ticketCurrentPage = 2;
        assertEquals(3, state.getTicketCurrentPageDisplay());
    }

    /**
     * {@code getTicketTotalPages()} returns one page for an empty ticket.
     */
    @Test
    void totalPagesIsOneWhenEmpty() {
        PosState state = newState();
        state.ticket.items = items(0);
        assertEquals(1, state.getTicketTotalPages());
    }

    /**
     * {@code getTicketTotalPages()} rounds the line count up to a whole number
     * of pages.
     */
    @Test
    void totalPagesCeilsLineCount() {
        PosState state = newState();
        state.ticket.items = items(7);
        assertEquals(2, state.getTicketTotalPages());
    }

    /**
     * {@code nextPage()} advances the page and bumps the version when a next
     * page exists.
     */
    @Test
    void nextPageAdvancesWhenAvailable() {
        PosState state = newState();
        state.ticket.items = items(10);
        state.ticketCurrentPage = 0;
        state.version = 0;
        state.nextPage();
        assertEquals(1, state.ticketCurrentPage);
        assertEquals(1, state.version);
    }

    /**
     * {@code nextPage()} is a no-op with no version bump when no next page
     * exists.
     */
    @Test
    void nextPageNoopWhenLastPage() {
        PosState state = newState();
        state.ticket.items = items(3);
        state.ticketCurrentPage = 0;
        state.version = 0;
        state.nextPage();
        assertEquals(0, state.ticketCurrentPage);
        assertEquals(0, state.version);
    }

    /**
     * {@code prevPage()} steps back and bumps the version when a previous page
     * exists.
     */
    @Test
    void prevPageStepsBackWhenAvailable() {
        PosState state = newState();
        state.ticketCurrentPage = 1;
        state.version = 0;
        state.prevPage();
        assertEquals(0, state.ticketCurrentPage);
        assertEquals(1, state.version);
    }

    /**
     * {@code prevPage()} is a no-op with no version bump on the first page.
     */
    @Test
    void prevPageNoopOnFirstPage() {
        PosState state = newState();
        state.ticketCurrentPage = 0;
        state.version = 0;
        state.prevPage();
        assertEquals(0, state.ticketCurrentPage);
        assertEquals(0, state.version);
    }

    // --- requireLastClosedTicket: the one gate of the last-ticket functions ---

    /**
     * {@code requireLastClosedTicket()} refuses in training mode and says so,
     * whatever the last closed ticket is (first arm true).
     */
    @Test
    void requireLastClosedTicketRefusesInTraining() {
        PosState state = newState();
        state.trainingMode = true;
        state.lastClosedTicketId = 42L;
        assertFalse(state.requireLastClosedTicket());
        verify(state.ticket).setError("FONCTION INDISPONIBLE EN FORMATION");
        assertEquals(1, state.version);
    }

    /**
     * {@code requireLastClosedTicket()} refuses when the register has closed no
     * sale yet, and says so rather than staying silent (first arm false, second
     * arm true).
     */
    @Test
    void requireLastClosedTicketRefusesWithoutClosedTicket() {
        PosState state = newState();
        state.trainingMode = false;
        state.lastClosedTicketId = null;
        assertFalse(state.requireLastClosedTicket());
        verify(state.ticket).setError("AUCUN TICKET");
        assertEquals(1, state.version);
    }

    /**
     * {@code requireLastClosedTicket()} allows the function and posts nothing
     * when the register is live and holds a closed ticket (both arms false).
     */
    @Test
    void requireLastClosedTicketAllowsOnClosedTicket() {
        PosState state = newState();
        state.trainingMode = false;
        state.lastClosedTicketId = 42L;
        assertTrue(state.requireLastClosedTicket());
        verifyNoInteractions(state.ticket);
        assertEquals(0, state.version);
    }

    /**
     * The freshly constructed state exposes its pristine defaults and touches no
     * collaborator before any action is taken.
     */
    @Test
    void constructorLeavesPristineDefaults() {
        PosState state = newState();
        assertEquals(-1, state.selectedTicketIndex);
        assertEquals(0, state.version);
        assertFalse(state.trainingMode);
        verifyNoInteractions(state.payment);
    }

    /**
     * The back-office message channel (EXP-20/89) starts empty: no down message
     * and no pending survey, and a fresh {@link PosState.BackOfficeSurvey} carries
     * a null question and an empty option list.
     */
    @Test
    void backOfficeMessageChannelStartsEmpty() {
        PosState state = newState();
        assertEquals("", state.backOfficeMessage);
        assertNull(state.backOfficeSurvey);
        PosState.BackOfficeSurvey survey = new PosState.BackOfficeSurvey();
        assertNull(survey.question);
        assertTrue(survey.options.isEmpty());
    }

    /**
     * The back-office message and survey SURVIVE {@code clearTicket()} (EXP-20):
     * a message from the store node is not a per-sale field and must not be wiped
     * between two customers.
     */
    @Test
    void clearTicketKeepsTheBackOfficeMessage() {
        PosState state = newState();
        state.backOfficeMessage = "Info magasin";
        state.backOfficeSurvey = new PosState.BackOfficeSurvey();
        state.clearTicket();
        assertEquals("Info magasin", state.backOfficeMessage);
        assertNotNull(state.backOfficeSurvey);
    }

    // --- PendingClose.getVarianceFormatted ---

    /**
     * {@code PendingClose.getVarianceFormatted()} (EXP-82) renders the variance
     * the French way: two decimals, a comma, HALF_UP rounding — and ZERO for an
     * absent variance rather than a crash on the arbitration screen.
     *
     * <p>Four behaviours in one line, which is why "it is only a getter" is not a
     * reason: null folding, scale, rounding and the decimal separator.
     */
    @Test
    void pendingCloseFormatsTheVarianceTheFrenchWay() {
        assertEquals("0,00", new PosState.PendingClose(
                null, null, null, null, null).getVarianceFormatted());
        assertEquals("12,34", new PosState.PendingClose(
                null, null, null, null, new java.math.BigDecimal("12.34")).getVarianceFormatted());
        // Scale: a bare integer is still printed with its two decimals.
        assertEquals("5,00", new PosState.PendingClose(
                null, null, null, null, new java.math.BigDecimal("5")).getVarianceFormatted());
        // HALF_UP, and not the banker's rounding of a plain format.
        assertEquals("2,35", new PosState.PendingClose(
                null, null, null, null, new java.math.BigDecimal("2.345")).getVarianceFormatted());
        // A negative variance keeps its sign: a drawer short is not a drawer over.
        assertEquals("-3,20", new PosState.PendingClose(
                null, null, null, null, new java.math.BigDecimal("-3.20")).getVarianceFormatted());
    }

    /**
     * {@code clearTicket()} forgets the order the ticket was loaded from by a
     * recall (LC-06-03-07): the settle push of one order must never fire on the
     * next customer's sale.
     */
    @Test
    void clearTicketForgetsTheRecalledOrder() {
        PosState state = new PosState();
        state.recalledOrderCode = "C04-CMD000001";
        state.clearTicket();
        assertNull(state.recalledOrderCode);
    }

    /**
     * The service request, its reason and the message-arrived indicator are
     * NODE-PUSHED and a whole-lane concern (LC-01-07-03/-04, LC-01-08-04):
     * {@code clearTicket()} deliberately leaves them standing — a request for
     * help outlives the customer whose basket interrupted the reading of its
     * answer.
     */
    @Test
    void clearTicketKeepsTheSupervisionIndicators() {
        PosState state = new PosState();
        state.serviceRequest = PosState.ServiceRequestStatus.PENDING;
        state.serviceRequestReason = "Sécurité";
        state.boMessageArrived = true;
        state.clearTicket();
        assertEquals(PosState.ServiceRequestStatus.PENDING, state.serviceRequest);
        assertEquals("Sécurité", state.serviceRequestReason);
        assertTrue(state.boMessageArrived);
    }

    /**
     * The request state starts at NONE and the reason and the arrived flag
     * at nothing: a lane that has asked nothing shows nothing.
     */
    @Test
    void supervisionIndicatorsStartAtNothing() {
        PosState state = new PosState();
        assertEquals(PosState.ServiceRequestStatus.NONE, state.serviceRequest);
        assertNull(state.serviceRequestReason);
        assertFalse(state.boMessageArrived);
    }
}
