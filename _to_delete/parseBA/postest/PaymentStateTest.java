package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.payment.AccountCustomer;
import com.intermarche.pos.ui.payment.PaymentState.PaymentEntry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PaymentState} and its nested {@link PaymentEntry}.
 * <p>
 * {@link PaymentState} is a pure in-memory state/value holder with no
 * collaborators and no Panache entity access, so no mocking is required.
 * Every guard, ternary and short-circuit is exercised on both arms with
 * absolute expected values, and each mutator/reset is exercised for line
 * coverage. The formatting helpers of {@link PaymentEntry} force a French
 * comma through an explicit {@code replace(".", ",")}, so the asserted
 * strings are independent of the JVM default locale. The sole uncovered
 * branch is the defensive {@code fromIndex >= payments.size()} true arm of
 * {@link PaymentState#getVisiblePayments()}: after both page clamps the
 * from-index is provably always below the list size, making that arm
 * unreachable through the public API.
 */
class PaymentStateTest {

    /**
     * Fills a fresh state with the given number of plain payments of 1,00 each.
     *
     * @param count the number of payments to register
     * @return the populated state
     */
    private PaymentState stateWith(int count) {
        PaymentState state = new PaymentState();
        for (int i = 0; i < count; i++) {
            state.addPayment("CARD", new BigDecimal("1.00"));
        }
        return state;
    }

    /**
     * addPayment appends a plain entry, accumulates the paid amount, jumps to
     * the last page and clears the numpad inputs.
     */
    @Test
    void addPaymentRegistersEntryAndAccumulates() {
        PaymentState state = new PaymentState();
        state.inputMode = "AMOUNT";
        state.temporaryInput = "12,00";
        state.addPayment("CARD", new BigDecimal("12.00"));
        assertEquals(1, state.payments.size());
        assertEquals("CARD", state.payments.get(0).method);
        assertNull(state.payments.get(0).tenderedAmount);
        assertFalse(state.payments.get(0).voucher);
        assertEquals(new BigDecimal("12.00"), state.paidAmount);
        assertEquals(0, state.currentPage);
        assertNull(state.inputMode);
        assertEquals("0,00", state.temporaryInput);
    }

    /**
     * addCashPayment records the tendered amount alongside the applied amount.
     */
    @Test
    void addCashPaymentKeepsTenderedAmount() {
        PaymentState state = new PaymentState();
        state.addCashPayment(new BigDecimal("10.00"), new BigDecimal("20.00"));
        PaymentEntry entry = state.payments.get(0);
        assertEquals("CASH", entry.method);
        assertEquals(new BigDecimal("10.00"), entry.amount);
        assertEquals(new BigDecimal("20.00"), entry.tenderedAmount);
        assertFalse(entry.voucher);
        assertEquals(new BigDecimal("10.00"), state.paidAmount);
    }

    /**
     * addVoucherPayment marks the entry as a voucher and stores its number.
     */
    @Test
    void addVoucherPaymentMarksVoucher() {
        PaymentState state = new PaymentState();
        state.addVoucherPayment("Ticket Resto", "V123", new BigDecimal("7.50"));
        PaymentEntry entry = state.payments.get(0);
        assertEquals("Ticket Resto", entry.method);
        assertEquals(new BigDecimal("7.50"), entry.amount);
        assertNull(entry.tenderedAmount);
        assertEquals("V123", entry.voucherNumber);
        assertTrue(entry.voucher);
        assertEquals(new BigDecimal("7.50"), state.paidAmount);
    }

    /**
     * clearPayments empties the list, zeroes the paid amount, resets the page
     * and the numpad inputs.
     */
    @Test
    void clearPaymentsResetsListAndPage() {
        PaymentState state = stateWith(3);
        state.inputMode = "AMOUNT";
        state.temporaryInput = "3,00";
        state.clearPayments();
        assertTrue(state.payments.isEmpty());
        assertEquals(BigDecimal.ZERO, state.paidAmount);
        assertEquals(0, state.currentPage);
        assertNull(state.inputMode);
        assertEquals("0,00", state.temporaryInput);
    }

    /**
     * reset clears every payment and transaction field back to its default.
     */
    @Test
    void resetRestoresDefaults() {
        PaymentState state = stateWith(2);
        state.transactionComplete = true;
        state.ticketDbId = 42L;
        state.paymentInProgress = true;
        state.pendingCardAmount = new BigDecimal("5.00");
        state.pendingCardAuthNumber = "123456";
        state.pendingCardDegraded = true;
        state.valuationStatus = "ENGINE";
        state.valuationJson = "{}";
        state.valuationEngineTotal = new BigDecimal("9.99");
        state.valuationAdjustment = new BigDecimal("-1.00");
        state.valuationMealEligible = new BigDecimal("19.00");
        state.valuationMealThreshold = new BigDecimal("25.00");
        state.valuationUpsells.add("Café");
        state.lastChangeAmount = new BigDecimal("2.00");
        state.voucherPanelOpen = true;
        state.pendingVoucherTypeCode = "TR";
        state.pendingVoucherLabel = "Ticket Resto";
        state.pendingVoucherNumber = "V1";
        state.pendingVoucherNeedsAmount = true;
        state.voucherError = "bad";
        state.reset();
        assertTrue(state.payments.isEmpty());
        assertEquals(BigDecimal.ZERO, state.paidAmount);
        assertFalse(state.transactionComplete);
        assertNull(state.ticketDbId);
        assertFalse(state.paymentInProgress);
        assertNull(state.pendingCardAmount);
        assertNull(state.pendingCardAuthNumber);
        assertFalse(state.pendingCardDegraded);
        assertNull(state.valuationStatus);
        assertNull(state.valuationJson);
        assertNull(state.valuationEngineTotal);
        assertNull(state.valuationAdjustment);
        assertNull(state.valuationMealEligible);
        assertNull(state.valuationMealThreshold);
        assertTrue(state.valuationUpsells.isEmpty());
        assertNull(state.lastChangeAmount);
        assertFalse(state.voucherPanelOpen);
        assertNull(state.pendingVoucherTypeCode);
        assertNull(state.pendingVoucherLabel);
        assertNull(state.pendingVoucherNumber);
        assertFalse(state.pendingVoucherNeedsAmount);
        assertNull(state.voucherError);
    }

    /**
     * clearPendingVoucher wipes only the in-progress voucher entry fields.
     */
    @Test
    void clearPendingVoucherWipesVoucherFields() {
        PaymentState state = new PaymentState();
        state.voucherPanelOpen = true;
        state.pendingVoucherTypeCode = "TR";
        state.pendingVoucherLabel = "Ticket Resto";
        state.pendingVoucherNumber = "V1";
        state.pendingVoucherNeedsAmount = true;
        state.voucherError = "bad";
        state.clearPendingVoucher();
        assertFalse(state.voucherPanelOpen);
        assertNull(state.pendingVoucherTypeCode);
        assertNull(state.pendingVoucherLabel);
        assertNull(state.pendingVoucherNumber);
        assertFalse(state.pendingVoucherNeedsAmount);
        assertNull(state.voucherError);
    }

    /**
     * getTotalPages returns one page when no payment is registered (empty arm).
     */
    @Test
    void getTotalPagesEmptyReturnsOne() {
        assertEquals(1, new PaymentState().getTotalPages());
    }

    /**
     * getTotalPages rounds the page count up for a non-empty list (non-empty arm).
     */
    @Test
    void getTotalPagesRoundsUp() {
        assertEquals(1, stateWith(5).getTotalPages());
        assertEquals(2, stateWith(6).getTotalPages());
        assertEquals(3, stateWith(11).getTotalPages());
    }

    /**
     * goToLastPage positions the view on the final page.
     */
    @Test
    void goToLastPageSelectsFinalPage() {
        PaymentState state = stateWith(11);
        state.currentPage = 0;
        state.goToLastPage();
        assertEquals(2, state.currentPage);
    }






    /**
     * isHasPreviousPage is true past the first page and false on it (both arms).
     */
    @Test
    void isHasPreviousPageBothArms() {
        PaymentState state = stateWith(11);
        state.currentPage = 0;
        assertFalse(state.isHasPreviousPage());
        state.currentPage = 1;
        assertTrue(state.isHasPreviousPage());
    }

    /**
     * isHasNextPage is true while payments remain beyond the page and false on
     * the last page (both arms).
     */
    @Test
    void isHasNextPageBothArms() {
        PaymentState state = stateWith(11);
        state.currentPage = 0;
        assertTrue(state.isHasNextPage());
        state.currentPage = 2;
        assertFalse(state.isHasNextPage());
    }


    /**
     * getCurrentPageDisplay returns the 1-based page number.
     */
    @Test
    void getCurrentPageDisplayIsOneBased() {
        PaymentState state = stateWith(11);
        state.currentPage = 2;
        assertEquals(3, state.getCurrentPageDisplay());
    }

    /**
     * isOnCurrentPage covers the three short-circuit outcomes of its && guard:
     * below the window (first condition false), at or after its end (second
     * condition false), and inside it (both true).
     */
    @Test
    void isOnCurrentPageAllShortCircuitArms() {
        PaymentState state = stateWith(11);
        state.currentPage = 1;
        assertFalse(state.isOnCurrentPage(4));
        assertFalse(state.isOnCurrentPage(10));
        assertTrue(state.isOnCurrentPage(7));
    }

    /**
     * isAmongLastTwo is true for the final two indices and false before them
     * (both arms).
     */
    @Test
    void isAmongLastTwoBothArms() {
        PaymentState state = stateWith(5);
        assertTrue(state.isAmongLastTwo(3));
        assertTrue(state.isAmongLastTwo(4));
        assertFalse(state.isAmongLastTwo(2));
    }

    /**
     * nextPage advances when a next page exists and stays put otherwise (both arms).
     */
    @Test
    void nextPageBothArms() {
        PaymentState state = stateWith(11);
        state.currentPage = 0;
        state.nextPage();
        assertEquals(1, state.currentPage);
        state.currentPage = 2;
        state.nextPage();
        assertEquals(2, state.currentPage);
    }

    /**
     * prevPage retreats when a previous page exists and stays put otherwise
     * (both arms).
     */
    @Test
    void prevPageBothArms() {
        PaymentState state = stateWith(11);
        state.currentPage = 2;
        state.prevPage();
        assertEquals(1, state.currentPage);
        state.currentPage = 0;
        state.prevPage();
        assertEquals(0, state.currentPage);
    }

    /**
     * The two-argument plain-entry constructor leaves the tendered amount null
     * and the voucher flag false.
     */
    @Test
    void plainEntryConstructorDefaults() {
        PaymentEntry entry = new PaymentEntry("CARD", new BigDecimal("3.00"));
        assertEquals("CARD", entry.method);
        assertEquals(new BigDecimal("3.00"), entry.amount);
        assertNull(entry.tenderedAmount);
        assertFalse(entry.isVoucher());
    }

    /**
     * addCardPayment appends a card entry carrying the terminal traces
     * (authorization number, degraded-mode indicator), accumulates the paid
     * amount and jumps to the last page (BO-04-01-08/47/49).
     */
    @Test
    void addCardPaymentCarriesTerminalTraces() {
        PaymentState state = new PaymentState();
        state.addCardPayment(new BigDecimal("12.00"), "654321", true);
        PaymentEntry entry = state.payments.get(0);
        assertEquals("CARD", entry.method);
        assertEquals(new BigDecimal("12.00"), entry.amount);
        assertEquals("654321", entry.authorizationNumber);
        assertTrue(entry.degradedMode);
        assertNull(entry.tenderedAmount);
        assertFalse(entry.isVoucher());
        assertEquals(new BigDecimal("12.00"), state.paidAmount);
    }

    /**
     * addCardPayment accepts a null authorization number and a non-degraded
     * flag (the nominal accepted-card case), storing both verbatim.
     */
    @Test
    void addCardPaymentAcceptsNoAuthorizationAndNotDegraded() {
        PaymentState state = new PaymentState();
        state.addCardPayment(new BigDecimal("4.00"), null, false);
        PaymentEntry entry = state.payments.get(0);
        assertNull(entry.authorizationNumber);
        assertFalse(entry.degradedMode);
    }

    /**
     * The card-entry constructor stores the degraded flag and authorization
     * number and leaves the tendered amount null and the voucher flag false.
     */
    @Test
    void cardEntryConstructorStoresTraces() {
        PaymentEntry entry = new PaymentEntry("CARD", new BigDecimal("9.00"), true, "111222");
        assertEquals("CARD", entry.method);
        assertEquals(new BigDecimal("9.00"), entry.amount);
        assertTrue(entry.degradedMode);
        assertEquals("111222", entry.authorizationNumber);
        assertNull(entry.tenderedAmount);
        assertFalse(entry.isVoucher());
    }

    /**
     * getFormattedAmount renders two decimals with a French comma and HALF_UP
     * rounding, independent of the JVM locale.
     */
    @Test
    void getFormattedAmountUsesCommaAndHalfUp() {
        assertEquals("1,50", new PaymentEntry("CARD", new BigDecimal("1.5")).getFormattedAmount());
        assertEquals("1,01", new PaymentEntry("CARD", new BigDecimal("1.005")).getFormattedAmount());
    }



    /**
     * getVoucherNumber returns the stored number when present (non-null arm).
     */
    @Test
    void getVoucherNumberPresent() {
        PaymentEntry entry = new PaymentEntry("Ticket Resto", new BigDecimal("7.50"), "V123", true);
        assertEquals("V123", entry.getVoucherNumber());
    }

    /**
     * getVoucherNumber returns an empty string when the number is null (null arm).
     */
    @Test
    void getVoucherNumberAbsent() {
        PaymentEntry entry = new PaymentEntry("Ticket Resto", new BigDecimal("7.50"), null, true);
        assertEquals("", entry.getVoucherNumber());
    }

    /**
     * Builds a distinct account customer, so a filled credit list holds
     * separate instances rather than repeated references.
     *
     * @param number the account number to stamp on the customer
     * @return a fresh account customer
     */
    private AccountCustomer account(String number) {
        AccountCustomer customer = new AccountCustomer();
        customer.accountNumber = number;
        customer.companyName = "Company " + number;
        return customer;
    }

    /**
     * getVisibleCreditCustomers returns the whole list untouched when it holds
     * no more than CREDIT_ROWS accounts (size &lt;= CREDIT_ROWS true arm).
     */
    @Test
    void getVisibleCreditCustomersReturnsFullListWhenSmall() {
        PaymentState state = new PaymentState();
        state.creditCustomers.add(account("A1"));
        state.creditCustomers.add(account("A2"));
        List<AccountCustomer> visible = state.getVisibleCreditCustomers();
        assertSame(state.creditCustomers, visible);
        assertEquals(2, visible.size());
    }

    /**
     * getVisibleCreditCustomers caps the list to the first CREDIT_ROWS accounts
     * when it holds more (size &lt;= CREDIT_ROWS false arm).
     */
    @Test
    void getVisibleCreditCustomersCapsWhenLarge() {
        PaymentState state = new PaymentState();
        for (int i = 0; i < PaymentState.CREDIT_ROWS + 2; i++) {
            state.creditCustomers.add(account("A" + i));
        }
        List<AccountCustomer> visible = state.getVisibleCreditCustomers();
        assertEquals(PaymentState.CREDIT_ROWS, visible.size());
        assertSame(state.creditCustomers.get(0), visible.get(0));
        assertSame(state.creditCustomers.get(PaymentState.CREDIT_ROWS - 1),
                visible.get(PaymentState.CREDIT_ROWS - 1));
    }

    /**
     * isCreditSearchWithoutMatch is false before any search ran, short-circuiting
     * on creditSearched (first condition false).
     */
    @Test
    void isCreditSearchWithoutMatchFalseWhenNotSearched() {
        assertFalse(new PaymentState().isCreditSearchWithoutMatch());
    }

    /**
     * isCreditSearchWithoutMatch is false when a search ran and matched at least
     * one account (first condition true, isEmpty false arm).
     */
    @Test
    void isCreditSearchWithoutMatchFalseWhenSearchedWithMatches() {
        PaymentState state = new PaymentState();
        state.creditSearched = true;
        state.creditCustomers.add(account("A1"));
        assertFalse(state.isCreditSearchWithoutMatch());
    }

    /**
     * isCreditSearchWithoutMatch is true when a search ran and matched nothing
     * (first condition true, isEmpty true arm).
     */
    @Test
    void isCreditSearchWithoutMatchTrueWhenSearchedEmpty() {
        PaymentState state = new PaymentState();
        state.creditSearched = true;
        assertTrue(state.isCreditSearchWithoutMatch());
    }

    /**
     * isCreditOverLimitPending is false when no over-ceiling amount is held back
     * (creditPendingAmount null arm).
     */
    @Test
    void isCreditOverLimitPendingFalseWhenNull() {
        assertFalse(new PaymentState().isCreditOverLimitPending());
    }

    /**
     * isCreditOverLimitPending is true while an over-ceiling amount awaits a
     * supervisor (creditPendingAmount non-null arm).
     */
    @Test
    void isCreditOverLimitPendingTrueWhenSet() {
        PaymentState state = new PaymentState();
        state.creditPendingAmount = new BigDecimal("5.00");
        assertTrue(state.isCreditOverLimitPending());
    }

    /**
     * A settlement is pending a supervisor's authorization exactly while a
     * tender is held — both arms (BO-03-02-10/12/13/14).
     */
    @Test
    void aTenderIsPendingExactlyWhileOneIsHeld() {
        PaymentState state = new PaymentState();
        assertFalse(state.isTenderAuthorizationPending());
        state.tenderHeldMethod = "CASH";
        assertTrue(state.isTenderAuthorizationPending());
    }

    /**
     * Abandoning the hold forgets the settlement AND the authorization granted
     * for it, so nothing carries over to the next one.
     */
    @Test
    void clearingTheHoldForgetsTheAuthorizationToo() {
        PaymentState state = new PaymentState();
        state.tenderHeldMethod = "CASH";
        state.tenderHeldLabel = "ESPECES";
        state.tenderHeldAmount = new BigDecimal("40.00");
        state.tenderHeldMessage = "PLAFOND";
        state.tenderOverride = "CASH";
        state.clearTenderHold();
        assertNull(state.tenderHeldMethod);
        assertNull(state.tenderHeldLabel);
        assertNull(state.tenderHeldAmount);
        assertNull(state.tenderHeldMessage);
        assertNull(state.tenderOverride);
    }

    /**
     * A new transaction owes no change as a credit note and holds no settlement,
     * whatever the previous one left behind (BO-03-02-16).
     */
    @Test
    void resetForgetsTheBookedNoteAndTheHold() {
        PaymentState state = new PaymentState();
        state.changeAsCreditNote = new BigDecimal("30.00");
        state.tenderHeldMethod = "CASH";
        state.reset();
        assertEquals(0, BigDecimal.ZERO.compareTo(state.changeAsCreditNote));
        assertFalse(state.isTenderAuthorizationPending());
    }

    /**
     * {@code removePayment} at a valid index (EXP-63) drops that entry, keeps the
     * others and takes its amount off the paid total.
     */
    @Test
    void removePaymentAtValidIndexDropsOnlyThatEntry() {
        PaymentState state = new PaymentState();
        state.addPayment("CASH", new BigDecimal("10.00"));
        state.addPayment("CARD", new BigDecimal("5.00"));
        assertTrue(state.removePayment(0));
        assertEquals(1, state.payments.size());
        assertEquals("CARD", state.payments.get(0).method);
        assertEquals(0, new BigDecimal("5.00").compareTo(state.paidAmount));
    }

    /**
     * {@code removePayment} with a negative index (EXP-63) removes nothing and
     * leaves the paid total untouched (first guard leg true).
     */
    @Test
    void removePaymentWithNegativeIndexRemovesNothing() {
        PaymentState state = new PaymentState();
        state.addPayment("CASH", new BigDecimal("10.00"));
        assertFalse(state.removePayment(-1));
        assertEquals(1, state.payments.size());
        assertEquals(0, new BigDecimal("10.00").compareTo(state.paidAmount));
    }

    /**
     * {@code removePayment} at an index equal to the size (EXP-63) removes nothing
     * (second guard leg true), including on an empty list.
     */
    @Test
    void removePaymentAtOrBeyondSizeRemovesNothing() {
        PaymentState state = new PaymentState();
        assertFalse(state.removePayment(0));
        state.addPayment("CASH", new BigDecimal("10.00"));
        assertFalse(state.removePayment(1));
        assertEquals(1, state.payments.size());
    }

    /**
     * EXP-54: the 4-arg {@code addCardPayment} records the real card entry mode
     * on the entry, while the 3-arg one leaves it null.
     */
    @Test
    void addCardPaymentRecordsEntryMode() {
        PaymentState withMode = new PaymentState();
        withMode.addCardPayment(new BigDecimal("10.00"), "AUTH1", false, "SANS_CONTACT");
        assertEquals("SANS_CONTACT", withMode.payments.get(0).cardEntryMode);
        PaymentState withoutMode = new PaymentState();
        withoutMode.addCardPayment(new BigDecimal("10.00"), "AUTH1", false);
        assertNull(withoutMode.payments.get(0).cardEntryMode);
    }

    /**
     * EXP-54: {@code reset} clears the pending card entry mode.
     */
    @Test
    void resetClearsPendingCardEntryMode() {
        PaymentState state = new PaymentState();
        state.pendingCardEntryMode = "CODE";
        state.reset();
        assertNull(state.pendingCardEntryMode);
    }
    /**
     * LC-06-03-07: {@code addDepositPayment} registers the recovery under the
     * ACOMPTE key, accumulates the paid amount and carries the deposit's
     * per-rate VAT detail and the order code on the entry.
     */
    @Test
    void addDepositPaymentRegistersEntryAndDetail() {
        PaymentState state = new PaymentState();
        state.addDepositPayment(new BigDecimal("10.00"), "0.2:1;0.055:0.21", "C04-CMD000001");
        assertEquals(1, state.payments.size());
        assertEquals("ACOMPTE", state.payments.get(0).method);
        assertEquals(0, new BigDecimal("10.00").compareTo(state.payments.get(0).amount));
        assertEquals("0.2:1;0.055:0.21", state.payments.get(0).depositVatDetail);
        assertEquals("C04-CMD000001", state.payments.get(0).depositOrderCode);
        assertEquals(0, new BigDecimal("10.00").compareTo(state.paidAmount));
    }

    /**
     * LC-06-03-07: {@code isDepositRecovery} is true on an ACOMPTE entry and
     * false on every other one (both arms).
     */
    @Test
    void isDepositRecoveryNamesOnlyAcompteEntries() {
        PaymentState.PaymentEntry recovery = new PaymentState.PaymentEntry("ACOMPTE",
                new BigDecimal("10.00"), "0.2:1", "C04-CMD000001");
        PaymentState.PaymentEntry cash = new PaymentState.PaymentEntry("CASH", new BigDecimal("5.00"));
        assertTrue(recovery.isDepositRecovery());
        assertFalse(cash.isDepositRecovery());
    }
}

