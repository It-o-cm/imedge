package com.intermarche.pos.ui.payment;

import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.valuation.ValuationReconciler;
import com.intermarche.pos.ui.valuation.ValuationService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.service.sync.register.FidEventOutboxService;
import com.intermarche.pos.ui.fidelity.FidelityService;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.hardware.terminal.AutoAcceptTerminalClient;
import com.intermarche.pos.ui.hardware.terminal.PaymentTerminalClient;
import com.intermarche.pos.ui.hardware.terminal.TerminalOutcome;
import com.intermarche.pos.ui.hardware.terminal.TerminalTransactionCallback;
import com.intermarche.pos.ui.TestMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PaymentService}.
 * <p>
 * {@link PaymentService} is a pure orchestration class: it manipulates a
 * passed-in {@link PosState} (with its real {@link PaymentState} and
 * {@code TicketState} sub-states, kept real here since they are value/state
 * holders whose arithmetic — {@code getRemaining()}, {@code paidAmount}
 * accumulation, completion — the branches depend on) and drives four injected
 * service collaborators, all Mockito mocks: {@link HardwareService},
 * {@link TicketPersistenceService}, {@link ValuationService} and
 * {@link ValuationReconciler}. The class touches no Panache entity, so no
 * static mocking is needed. Every guard and ternary is exercised on both arms
 * with absolute expected values; the sole unreachable branch — the defensive
 * {@code tendered == null || signum <= 0} guard of the private
 * {@code handlePaymentWithChange}, which every public caller pre-guards — is
 * covered by reflection. Customer-display strings use the service's
 * locale-fixed French {@code DecimalFormat}, so the comma decimals asserted
 * here are independent of the JVM default locale.
 */
class PaymentServiceTest {

    /** The service under test with mocked collaborators wired in. */
    private PaymentService service;

    /** Mocked hardware boundary (customer display and cash drawer). */
    private HardwareService hardwareService;

    /** Mocked draft/ticket persistence collaborator. */
    private TicketPersistenceService ticketPersistenceService;

    /** Mocked valuation collaborator (engine revaluation). */
    private ValuationService valuationService;

    /** Mocked valuation reconciler collaborator (advantage revert). */
    private ValuationReconciler valuationReconciler;

    /** The printer, which issues the gift-card vouchers at the fiscal moment. */
    private com.intermarche.pos.ui.hardware.TicketPrinterService ticketPrinterService;

    /** The loyalty lease lifecycle: reserve, renew, release, confirm. */
    private FidelityService fidelityService;

    /** The loyalty fiscal-event outbox, fed at the fiscal moment. */
    private FidEventOutboxService fidEventOutboxService;

    /** The mocked payment-terminal port (decision legs driven by hand). */
    private PaymentTerminalClient terminal;

    /** The back-office parameters mock (drawer-open-on-payment rule). */
    private com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** The cheque reader mock, whose two callbacks the tests fire by hand. */
    private com.intermarche.pos.ui.hardware.ChequeReadingService chequeReadingService;

    /** A real POS state carrying real payment and ticket sub-states. */
    private PosState state;

    /** The conditional-printing rule (LC-08-03), mocked. */
    private com.intermarche.pos.ui.hardware.PrintPolicy printPolicy;

    /**
     * Builds a fresh service with fresh mocks and a fresh real state before
     * each test; the terminal port is a mock whose callback legs the tests
     * fire by hand.
     */
    /** The invoice service asked for an automatic document at closing time. */
    private com.intermarche.pos.ui.invoice.InvoiceService invoiceService;

    /** The administered tender rules opposed to every settlement (BO-03-02). */
    private TenderRulesService tenderRulesService;

    /** The supervisor credential asked for when an administered bound is passed. */
    private com.intermarche.pos.ui.endorsement.EndorsementService endorsementService;

    @BeforeEach
    void setUp() {
        service = new PaymentService();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        service.i18n = TestMessages.french();
        hardwareService = mock(HardwareService.class);
        ticketPersistenceService = mock(TicketPersistenceService.class);
        valuationService = mock(ValuationService.class);
        valuationReconciler = mock(ValuationReconciler.class);
        service.hardwareService = hardwareService;
        service.ticketPersistenceService = ticketPersistenceService;
        service.consumptionService = mock(com.intermarche.pos.service.sync.ConsumptionService.class);
        ticketPrinterService = mock(com.intermarche.pos.ui.hardware.TicketPrinterService.class);
        service.ticketPrinterService = ticketPrinterService;
        service.valuationService = valuationService;
        service.valuationReconciler = valuationReconciler;
        fidelityService = mock(FidelityService.class);
        fidEventOutboxService = mock(FidEventOutboxService.class);
        service.fidelityService = fidelityService;
        service.fidEventOutboxService = fidEventOutboxService;
        terminal = mock(PaymentTerminalClient.class);
        service.terminal = terminal;
        // LC-08-04-16: closing a sale asks the invoice service whether the settlement
        // calls for an automatic document. The stand-in answers nothing, which is the
        // shop that administered no automatic emission — the default of every case
        // below.
        invoiceService = mock(com.intermarche.pos.ui.invoice.InvoiceService.class);
        service.invoiceService = invoiceService;
        // The drawer-open-on-payment rule defaults to ON, the pre-existing
        // behavior every physical-tender case here relies on (BO-10-02-12).
        posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        when(posSettingsService.drawerOpenOnPayment()).thenReturn(true);
        service.posSettingsService = posSettingsService;
        // The administered tender rules (BO-03-02): the stand-in answers as a
        // shop that administered NO tender — no bound is opposed, change is
        // given, and the drawer follows the register's own rule. That is the
        // behaviour every case below was written against.
        tenderRulesService = mock(TenderRulesService.class);
        when(tenderRulesService.check(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(TenderRulesService.Verdict.silent());
        when(tenderRulesService.checkChange(any(), any()))
                .thenReturn(TenderRulesService.Verdict.silent());
        when(tenderRulesService.changeAllowed(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(call -> call.getArgument(1));
        when(tenderRulesService.opensDrawer(any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(call -> call.getArgument(3));
        service.tenderRulesService = tenderRulesService;
        // The supervisor credential (BO-03-02-10): the stand-in refuses by
        // default, so a case that wants an authorization grants it explicitly.
        endorsementService = mock(com.intermarche.pos.ui.endorsement.EndorsementService.class);
        // The control points of BO-10-02 answer as they do with nothing
        // administered — no control — which is the behaviour every case of this
        // class was written against. A case exercising a control states its own.
        when(endorsementService.check(any(), any()))
                .thenReturn(com.intermarche.pos.ui.endorsement.ControlLevel.NONE);
        service.endorsementService = endorsementService;
        chequeReadingService =
                mock(com.intermarche.pos.ui.hardware.ChequeReadingService.class);
        service.chequeReadingService = chequeReadingService;
        // The register's own identity (BO-03-02-43 tokens, BO-03-02-27/-29
        // rules): the stand-in names no terminal, so a case needing one states
        // it, and every pre-existing case keeps a register the referential
        // cannot place — which opposes nothing.
        service.ticketNumberService = mock(com.intermarche.pos.service.TicketNumberService.class);
        // The conditional-printing rule (LC-08-03) answers as it does with the
        // option OFF — every document named, nothing forced —, which is the
        // behaviour every case here was written against.
        printPolicy = mock(com.intermarche.pos.ui.hardware.PrintPolicy.class);
        when(printPolicy.decide(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new com.intermarche.pos.ui.hardware.PrintPolicy.Decision(true, true, true));
        service.printPolicy = printPolicy;
        // Step-6 tender seams (EXP-66/67/58): a supplier gift-card issuer, the
        // Resto Flash mobile service and the SOGEC compensation circuit. The
        // stand-ins let each new tender's arms be driven explicitly per test.
        restoFlashClient = mock(RestoFlashClient.class);
        service.restoFlashClient = restoFlashClient;
        giftCardIssuerClient = mock(GiftCardIssuerClient.class);
        service.giftCardIssuerClient = giftCardIssuerClient;
        sogecCompensationService = mock(SogecCompensationService.class);
        service.sogecCompensationService = sogecCompensationService;
        state = new PosState();
    }

    /** The Resto Flash mobile-voucher seam (EXP-67). */
    private RestoFlashClient restoFlashClient;

    /** The supplier gift-card issuer registry seam (EXP-66). */
    private GiftCardIssuerClient giftCardIssuerClient;

    /** The SOGEC compensation circuit (EXP-58). */
    private SogecCompensationService sogecCompensationService;

    /**
     * Makes the cheque reader answer with a magnetic line as soon as a reading is
     * started, on the calling thread — the real service answers from its own follower
     * thread, which nothing here depends on.
     *
     * @param line the magnetic line the reader reports
     */
    private void chequeReaderAnswers(String line) {
        // The callback only DEPOSITS the reading, as the follower thread does in
        // production; the scenarios settle it with settlePendingChequeRead, which
        // is what the next request carries.
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onRead =
                    (java.util.function.Consumer<String>) invocation.getArgument(1);
            onRead.accept(line);
            return null;
        }).when(chequeReadingService).read(any(), any(), any());
    }

    /**
     * Makes the cheque reader refuse as soon as a reading is started.
     *
     * @param message the operator-facing message the reader reports
     */
    private void chequeReaderRefuses(String message) {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onError =
                    (java.util.function.Consumer<String>) invocation.getArgument(2);
            onError.accept(message);
            return null;
        }).when(chequeReadingService).read(any(), any(), any());
    }

    // --------------------------------------------------
    // initPayment
    // --------------------------------------------------

    /**
     * {@code initPayment} on a normal sale (draft synced, not training): no
     * error, engine revaluation, total displayed at entry and after revaluation
     * ({@code ticketId != null} short-circuits the guard, {@code !trainingMode}
     * true in the revaluation branch).
     */
    @Test
    void initPaymentSyncedNotTraining() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        when(ticketPersistenceService.syncDraft(state)).thenReturn(5L);
        service.initPayment(state);
        assertTrue(state.payment.paymentInProgress);
        verify(hardwareService, times(2)).displayMessage("TOTAL   20,00 E");
        verify(valuationService).revalueForPayment(state);
        assertNull(state.payment.valuationStatus);
    }

    /**
     * {@code initPayment} still revalues but logs the failure when the draft
     * cannot be synced outside training ({@code ticketId == null} true,
     * {@code !trainingMode} true — both arms of the {@code &&} taken).
     */
    @Test
    void initPaymentSyncFailsNotTraining() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        when(ticketPersistenceService.syncDraft(state)).thenReturn(null);
        service.initPayment(state);
        verify(valuationService).revalueForPayment(state);
        assertNull(state.payment.valuationStatus);
    }

    /**
     * {@code initPayment} in training mode does not log the sync failure and
     * keeps the valuation local ({@code ticketId == null} true but
     * {@code !trainingMode} false, and the revaluation branch false).
     */
    @Test
    void initPaymentTrainingKeepsLocal() {
        state.trainingMode = true;
        state.ticket.totalAmount = new BigDecimal("20.00");
        when(ticketPersistenceService.syncDraft(state)).thenReturn(null);
        service.initPayment(state);
        assertEquals("LOCAL", state.payment.valuationStatus);
        verify(hardwareService, times(1)).displayMessage("TOTAL   20,00 E");
        verify(valuationService, never()).revalueForPayment(any());
    }

    // --------------------------------------------------
    // toggleDonationRoundup
    // --------------------------------------------------

    /**
     * {@code toggleDonationRoundup} does nothing on a completed transaction
     * ({@code transactionComplete} true arm).
     */
    @Test
    void toggleDonationOnCompletedTransactionReturns() {
        state.payment.transactionComplete = true;
        service.toggleDonationRoundup(state);
        verify(ticketPersistenceService, never()).syncDraft(any());
        verifyNoInteractions(hardwareService);
    }

    /**
     * {@code toggleDonationRoundup} removes the existing donation line and
     * clears its uid ({@code donationLineUid != null} true arm).
     */
    @Test
    void toggleDonationRemovesExistingLine() {
        state.payment.transactionComplete = false;
        state.donationLineUid = "donation-uid";
        service.toggleDonationRoundup(state);
        assertNull(state.donationLineUid);
        verify(ticketPersistenceService).syncDraft(state);
        verify(hardwareService).displayMessage("TOTAL   0,00 E");
    }

    /**
     * {@code toggleDonationRoundup} adds no line when the total is already whole
     * ({@code donationLineUid == null}, {@code difference.signum() <= 0} true).
     */
    @Test
    void toggleDonationWholeTotalAddsNothing() {
        state.payment.transactionComplete = false;
        state.donationLineUid = null;
        state.ticket.totalAmount = new BigDecimal("5.00");
        service.toggleDonationRoundup(state);
        assertNull(state.donationLineUid);
        verify(ticketPersistenceService, never()).syncDraft(any());
        verifyNoInteractions(hardwareService);
    }

    /**
     * {@code toggleDonationRoundup} adds a round-up donation line when the total
     * is not whole ({@code donationLineUid == null}, {@code difference.signum()
     * <= 0} false), tagging its uid.
     */
    @Test
    void toggleDonationAddsRoundupLine() {
        state.payment.transactionComplete = false;
        state.donationLineUid = null;
        state.ticket.totalAmount = new BigDecimal("4.30");
        service.toggleDonationRoundup(state);
        assertEquals(1, state.ticket.items.size());
        assertEquals(state.lastEnteredItemId, state.donationLineUid);
        assertEquals(0, new BigDecimal("0.70").compareTo(state.ticket.items.get(0).unitPrice));
        verify(ticketPersistenceService).syncDraft(state);
    }

    // --------------------------------------------------
    // cancelPayments
    // --------------------------------------------------

    /**
     * {@code cancelPayments} reverts the valuation when an adjustment exists and
     * removes the persisted payments when a draft id exists
     * ({@code valuationAdjustment != null} short-circuits the {@code ||},
     * {@code ticketId != null} true).
     */
    @Test
    void cancelPaymentsWithAdjustmentAndDraft() {
        state.payment.ticketDbId = 5L;
        state.payment.valuationAdjustment = new BigDecimal("-1.00");
        state.payment.paymentInProgress = true;
        service.cancelPayments(state);
        verify(valuationReconciler).revert(state.ticket, 5L);
        verify(valuationService).revalue(state);
        verify(ticketPersistenceService).removePaymentsFromTicket(5L);
        assertFalse(state.payment.paymentInProgress);
        assertNull(state.payment.valuationStatus);
        assertNull(state.payment.valuationAdjustment);
    }

    /**
     * {@code cancelPayments} reverts the valuation through the ENGINE status
     * when no adjustment is set ({@code valuationAdjustment != null} false,
     * {@code "ENGINE".equals(valuationStatus)} true; {@code ticketId != null}
     * true).
     */
    @Test
    void cancelPaymentsEngineStatusReverts() {
        state.payment.ticketDbId = 5L;
        state.payment.valuationAdjustment = null;
        state.payment.valuationStatus = "ENGINE";
        service.cancelPayments(state);
        verify(valuationReconciler).revert(state.ticket, 5L);
        verify(ticketPersistenceService).removePaymentsFromTicket(5L);
    }

    /**
     * {@code cancelPayments} skips the revert and the payment removal when there
     * is neither adjustment nor ENGINE status nor draft id
     * ({@code valuationAdjustment != null} false,
     * {@code "ENGINE".equals(valuationStatus)} false, {@code ticketId != null}
     * false).
     */
    @Test
    void cancelPaymentsNoValuationNoDraft() {
        state.payment.ticketDbId = null;
        state.payment.valuationAdjustment = null;
        state.payment.valuationStatus = "LOCAL";
        service.cancelPayments(state);
        verifyNoInteractions(valuationReconciler);
        verify(valuationService).revalue(state);
        verify(ticketPersistenceService, never()).removePaymentsFromTicket(any());
    }

    /**
     * {@code cancelOnePayment} at a valid index (EXP-63) drops only that entry and,
     * with a draft id, re-persists the survivors (removeAll + replay), leaving the
     * payment in progress — unlike the global cancel.
     */
    @Test
    void cancelOnePaymentValidRepersistsSurvivors() {
        state.payment.ticketDbId = 5L;
        state.payment.addPayment("CASH", new BigDecimal("10.00"));
        state.payment.addPayment("CARD", new BigDecimal("5.00"));
        assertTrue(service.cancelOnePayment(state, 0));
        assertEquals(1, state.payment.payments.size());
        assertEquals("CARD", state.payment.payments.get(0).method);
        verify(ticketPersistenceService).removePaymentsFromTicket(5L);
        verify(ticketPersistenceService).addPaymentToTicket(eq(5L), any());
    }

    /**
     * BO-03-02-35: cancelling a SINGLE settlement leaves its slip, naming the
     * settlement key when the referential administers no label for it. A
     * refused index prints nothing — the slip follows the removal, it does not
     * announce it.
     */
    @Test
    void cancelOnePaymentPrintsTheCancellationSlip() {
        state.payment.ticketDbId = null;
        state.payment.addPayment("CHEQUE", new BigDecimal("42.50"));
        assertTrue(service.cancelOnePayment(state, 0));
        verify(ticketPrinterService).printPaymentCancellation(
                org.mockito.ArgumentMatchers.isNull(), eq("CHEQUE"),
                eq(new BigDecimal("42.50")));

        state.payment.addPayment("CASH", new BigDecimal("5.00"));
        assertFalse(service.cancelOnePayment(state, 7));
        verify(ticketPrinterService, never()).printPaymentCancellation(
                any(), eq("CASH"), any());
    }

    /**
     * BO-03-02-35: cancelling the whole payment leaves ONE slip per settlement
     * the customer actually handed over, and none at all on a payment where
     * nothing was registered.
     */
    @Test
    void cancelPaymentsPrintsOneSlipPerSettlement() {
        state.payment.ticketDbId = null;
        state.payment.addPayment("CHEQUE", new BigDecimal("42.50"));
        state.payment.addPayment("CARD", new BigDecimal("7.50"));
        service.cancelPayments(state);
        verify(ticketPrinterService).printPaymentCancellation(
                org.mockito.ArgumentMatchers.isNull(), eq("CHEQUE"),
                eq(new BigDecimal("42.50")));
        verify(ticketPrinterService).printPaymentCancellation(
                org.mockito.ArgumentMatchers.isNull(), eq("CARD"),
                eq(new BigDecimal("7.50")));

        service.cancelPayments(state);
        verify(ticketPrinterService, org.mockito.Mockito.times(2))
                .printPaymentCancellation(any(), any(), any());
    }

    /**
     * BO-03-02-27: a tender the store forbade on a SELF-SERVICE lane is refused
     * there and says so, and nothing is registered. On a manned till the same
     * tender settles as before — the rule is read off the register's own kind.
     */
    @Test
    void aTenderForbiddenOnSelfServiceIsRefusedThere() {
        com.intermarche.pos.domain.payment.TenderDefinition tender =
                new com.intermarche.pos.domain.payment.TenderDefinition();
        tender.code = "CHEQUE";
        tender.selfServiceAllowed = false;
        com.intermarche.pos.domain.store.CheckoutPoint lane =
                new com.intermarche.pos.domain.store.CheckoutPoint();
        lane.terminalId = "C04";
        lane.tpvType = com.intermarche.pos.domain.store.TpvType.SELF_CHECKOUT_TGCS;
        when(service.ticketNumberService.getTerminalId()).thenReturn("C04");
        state.ticket.totalAmount = new BigDecimal("20.00");
        try (MockedStatic<com.intermarche.pos.domain.store.CheckoutPoint> points =
                     mockStatic(com.intermarche.pos.domain.store.CheckoutPoint.class);
             MockedStatic<com.intermarche.pos.domain.payment.TenderDefinition> tenders =
                     mockStatic(com.intermarche.pos.domain.payment.TenderDefinition.class)) {
            points.when(() -> com.intermarche.pos.domain.store.CheckoutPoint
                    .findByTerminal("C04")).thenReturn(lane);
            tenders.when(() -> com.intermarche.pos.domain.payment.TenderDefinition
                    .findByCode("CHEQUE")).thenReturn(tender);
            chequeReaderAnswers("a0007639 a800000000909r 000000000000i");
            service.processCheque(state, new BigDecimal("20.00"));
            service.settlePendingChequeRead(state);
        }
        assertEquals("REGLEMENT INDISPONIBLE EN LIBRE-SERVICE", state.ticket.transientError);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * BO-03-02-29: a tender the store WATCHES on a self-service lane raises the
     * supervisor hold every administered tender rule raises, and registers
     * nothing until the hostess allows it.
     */
    @Test
    void aWatchedTenderOnSelfServiceCallsTheHostess() {
        com.intermarche.pos.domain.payment.TenderDefinition tender =
                new com.intermarche.pos.domain.payment.TenderDefinition();
        tender.code = "CHEQUE";
        tender.selfServiceAllowed = true;
        tender.selfServiceSupervision = true;
        com.intermarche.pos.domain.store.CheckoutPoint lane =
                new com.intermarche.pos.domain.store.CheckoutPoint();
        lane.terminalId = "C04";
        lane.tpvType = com.intermarche.pos.domain.store.TpvType.SELF_CHECKOUT_DN;
        when(service.ticketNumberService.getTerminalId()).thenReturn("C04");
        state.ticket.totalAmount = new BigDecimal("20.00");
        try (MockedStatic<com.intermarche.pos.domain.store.CheckoutPoint> points =
                     mockStatic(com.intermarche.pos.domain.store.CheckoutPoint.class);
             MockedStatic<com.intermarche.pos.domain.payment.TenderDefinition> tenders =
                     mockStatic(com.intermarche.pos.domain.payment.TenderDefinition.class)) {
            points.when(() -> com.intermarche.pos.domain.store.CheckoutPoint
                    .findByTerminal("C04")).thenReturn(lane);
            tenders.when(() -> com.intermarche.pos.domain.payment.TenderDefinition
                    .findByCode("CHEQUE")).thenReturn(tender);
            chequeReaderAnswers("a0007639 a800000000909r 000000000000i");
            service.processCheque(state, new BigDecimal("20.00"));
            service.settlePendingChequeRead(state);
        }
        assertEquals("CHEQUE", state.payment.tenderHeldMethod);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * On a MANNED till neither rule is opposed, and a self-service lane the
     * referential does not describe is treated as manned: a lane missing from
     * the referential is a referential problem, not a reason to refuse a
     * customer at the one moment they are paying (both null arms).
     */
    @Test
    void aMannedTillAndAnUndescribedLaneOpposeNothing() {
        com.intermarche.pos.domain.payment.TenderDefinition tender =
                new com.intermarche.pos.domain.payment.TenderDefinition();
        tender.code = "CHEQUE";
        tender.selfServiceAllowed = false;
        com.intermarche.pos.domain.store.CheckoutPoint manned =
                new com.intermarche.pos.domain.store.CheckoutPoint();
        manned.terminalId = "C04";
        manned.tpvType = com.intermarche.pos.domain.store.TpvType.CASH_REGISTER;
        when(service.ticketNumberService.getTerminalId()).thenReturn("C04");
        state.ticket.totalAmount = new BigDecimal("20.00");
        try (MockedStatic<com.intermarche.pos.domain.store.CheckoutPoint> points =
                     mockStatic(com.intermarche.pos.domain.store.CheckoutPoint.class)) {
            points.when(() -> com.intermarche.pos.domain.store.CheckoutPoint
                    .findByTerminal("C04")).thenReturn(manned);
            chequeReaderAnswers("a0007639 a800000000909r 000000000000i");
            service.processCheque(state, new BigDecimal("20.00"));
            service.settlePendingChequeRead(state);
        }
        assertEquals(1, state.payment.payments.size());

        // The lane the referential does not describe at all.
        state.clearPayments();
        state.ticket.transientError = null;
        try (MockedStatic<com.intermarche.pos.domain.store.CheckoutPoint> points =
                     mockStatic(com.intermarche.pos.domain.store.CheckoutPoint.class)) {
            points.when(() -> com.intermarche.pos.domain.store.CheckoutPoint
                    .findByTerminal("C04")).thenReturn(null);
            chequeReaderAnswers("a0007639 a800000000909r 000000000000i");
            service.processCheque(state, new BigDecimal("20.00"));
            service.settlePendingChequeRead(state);
        }
        assertEquals(1, state.payment.payments.size());
    }

    /**
     * BO-03-03-11: a settlement paid by gift card leaves its slip, carrying the
     * balance the card STILL holds — the issuer's answer minus what was just
     * spent. A card whose balance covers more than the due is settled at the
     * due, and the remainder is what the customer walks away with.
     */
    @Test
    void aGiftCardPaymentPrintsItsSlipWithTheRemainingBalance() {
        when(giftCardIssuerClient.query("9999000011114321")).thenReturn(
                new com.intermarche.pos.ui.payment.GiftCardIssuerClient.IssuerVerdict(
                        true, new BigDecimal("50.00")));
        state.ticket.totalAmount = new BigDecimal("30.00");
        service.processSupplierGiftCard(state, "9999000011114321", new BigDecimal("30.00"));
        verify(ticketPrinterService).printGiftCardPaymentReceipt(
                org.mockito.ArgumentMatchers.isNull(), eq("9999000011114321"),
                eq(new BigDecimal("30.00")), eq(new BigDecimal("20.00")));
    }

    /**
     * A card whose balance is SMALLER than the amount asked settles the balance
     * and leaves nothing on the card: the remaining balance is zero and never
     * negative (BO-03-03-11, the capping arm).
     */
    @Test
    void aCappedGiftCardPaymentLeavesNoBalance() {
        when(giftCardIssuerClient.query("4321")).thenReturn(
                new com.intermarche.pos.ui.payment.GiftCardIssuerClient.IssuerVerdict(
                        true, new BigDecimal("12.00")));
        state.ticket.totalAmount = new BigDecimal("30.00");
        service.processSupplierGiftCard(state, "4321", new BigDecimal("30.00"));
        verify(ticketPrinterService).printGiftCardPaymentReceipt(
                org.mockito.ArgumentMatchers.isNull(), eq("4321"),
                eq(new BigDecimal("12.00")), eq(BigDecimal.ZERO));
    }

    /**
     * A card the issuer REFUSES settles nothing and prints nothing: there is no
     * settlement to witness (BO-03-03-11).
     */
    @Test
    void aRefusedGiftCardPrintsNoSlip() {
        when(giftCardIssuerClient.query("0000")).thenReturn(
                new com.intermarche.pos.ui.payment.GiftCardIssuerClient.IssuerVerdict(
                        false, new BigDecimal("50.00")));
        state.ticket.totalAmount = new BigDecimal("30.00");
        service.processSupplierGiftCard(state, "0000", new BigDecimal("30.00"));
        verify(ticketPrinterService, never()).printGiftCardPaymentReceipt(
                any(), any(), any(), any());
    }

    /**
     * BO-03-02-43: with nothing administered, the cheque back carries the date
     * and the amount, exactly as before the key existed.
     */
    @Test
    void theChequeEndorsementFallsBackOnTheDateAndTheAmount() {
        chequeReaderAnswers("a0007639 a800000000909r 000000000000i");
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processCheque(state, new BigDecimal("20.00"));
        org.mockito.ArgumentCaptor<String> captor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(chequeReadingService).read(captor.capture(), any(), any());
        assertTrue(captor.getValue().contains("20,00 EUR"), captor.getValue());
        assertEquals(2, captor.getValue().split("\n").length, captor.getValue());
    }

    /**
     * BO-03-02-43: an ADMINISTERED endorsement is printed line by line with its
     * tokens filled; an empty administered line prints nothing rather than a
     * blank band, and a token the register cannot answer renders empty rather
     * than leaving the token in ink.
     */
    @Test
    void theAdministeredChequeEndorsementIsPrintedLineByLine() {
        when(posSettingsService.chequeEndorsementLines())
                .thenReturn("{montant} EUR;;LE {date} A {heure};CAISSE {caisse} PDV {pdv}");
        chequeReaderAnswers("a0007639 a800000000909r 000000000000i");
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processCheque(state, new BigDecimal("20.00"));
        org.mockito.ArgumentCaptor<String> captor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(chequeReadingService).read(captor.capture(), any(), any());
        String[] lines = captor.getValue().split("\n");
        // The empty administered line is dropped: three lines, not four.
        assertEquals(3, lines.length, captor.getValue());
        assertEquals("20,00 EUR", lines[0]);
        assertTrue(lines[1].startsWith("LE "), lines[1]);
        assertTrue(lines[1].contains(" A "), lines[1]);
        // No till service and no PDV number on this fixture: both render empty,
        // and the token never reaches the paper.
        assertFalse(captor.getValue().contains("{"), captor.getValue());
    }

    /**
     * {@code cancelOnePayment} on an out-of-range index (EXP-63) removes nothing
     * and touches no draft.
     */
    @Test
    void cancelOnePaymentOutOfRangeIsNoOp() {
        state.payment.ticketDbId = 5L;
        state.payment.addPayment("CASH", new BigDecimal("10.00"));
        assertFalse(service.cancelOnePayment(state, 3));
        assertEquals(1, state.payment.payments.size());
        verify(ticketPersistenceService, never()).removePaymentsFromTicket(any());
    }

    /**
     * {@code cancelOnePayment} with no draft id (EXP-63) still drops the entry but
     * re-persists nothing (the ticketId-null arm).
     */
    @Test
    void cancelOnePaymentWithoutDraftDropsEntryOnly() {
        state.payment.ticketDbId = null;
        state.payment.addPayment("CASH", new BigDecimal("10.00"));
        assertTrue(service.cancelOnePayment(state, 0));
        assertEquals(0, state.payment.payments.size());
        verify(ticketPersistenceService, never()).removePaymentsFromTicket(any());
    }

    // --------------------------------------------------
    // processCash
    // --------------------------------------------------

    /**
     * {@code processCash} rejects a null amount ({@code tendered == null} true
     * arm of the guard).
     */
    @Test
    void processCashNullReturns() {
        service.processCash(state, null);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processCash} rejects a non-positive amount ({@code tendered ==
     * null} false, {@code tendered.signum() <= 0} true).
     */
    @Test
    void processCashZeroReturns() {
        service.processCash(state, BigDecimal.ZERO);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processCash} for an exact payment persists it, shows the method
     * line (no change), opens the drawer and completes the transaction
     * ({@code "CASH".equals} true, change ternary/if false arm, savePayment
     * persists, drawer opens, completion reached).
     */
    @Test
    void processCashExactCompletesAndOpensDrawer() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCash(state, new BigDecimal("20"));
        assertEquals(0, new BigDecimal("20").compareTo(state.payment.paidAmount));
        assertEquals(0, BigDecimal.ZERO.compareTo(state.payment.lastChangeAmount));
        verify(hardwareService).displayMessage("ESPECES   20,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
        verify(hardwareService).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    // --------------------------------------------------
    // processCash — legal cash rounding (LC-07-03)
    // --------------------------------------------------

    /**
     * With rounding OFF (step zero, the French default), an amount off any step is
     * accepted exactly as before and nothing is booked: the rule must not leak into
     * a shop that has not enabled it.
     */
    @Test
    void processCashWithoutRoundingAcceptsAnyAmount() {
        state.cashRoundingStepCents = 0;
        state.ticket.totalAmount = new BigDecimal("23.42");
        service.processCash(state, new BigDecimal("23.42"));
        assertEquals(1, state.payment.payments.size());
        assertEquals("CASH", state.payment.payments.get(0).method);
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * A step of one cent is not a rounding either and takes the same leg.
     */
    @Test
    void processCashWithOneCentStepAcceptsAnyAmount() {
        state.cashRoundingStepCents = 1;
        state.ticket.totalAmount = new BigDecimal("23.42");
        service.processCash(state, new BigDecimal("23.42"));
        assertEquals(1, state.payment.payments.size());
    }

    /**
     * Where the shop rounds, an amount off the step is REFUSED and nothing is
     * registered: the coins to make it up no longer circulate
     * ({@code LC-07-03-05}).
     */
    @Test
    void processCashRefusesAnAmountOffTheStep() {
        state.cashRoundingStepCents = 5;
        state.ticket.totalAmount = new BigDecimal("23.42");
        service.processCash(state, new BigDecimal("23.42"));
        assertTrue(state.payment.payments.isEmpty());
        assertEquals("MONTANT NON MULTIPLE DE 0,05 E", state.ticket.transientError);
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * Rounding DOWN: the sale owes 24,62, the customer hands over 24,60, and the
     * two cents are booked on ARRONDI so the settlements still sum to the total
     * ({@code LC-07-03-01/06}). The rounding is registered BEFORE the cash, so the
     * cash line settles a remainder already on the step.
     */
    @Test
    void processCashRoundsDownAndBooksThePositiveDifference() {
        state.cashRoundingStepCents = 5;
        state.ticket.totalAmount = new BigDecimal("24.62");
        state.payment.ticketDbId = 3L;
        service.processCash(state, new BigDecimal("24.60"));
        assertEquals(2, state.payment.payments.size());
        assertEquals("ARRONDI", state.payment.payments.get(0).method);
        assertEquals(0, new BigDecimal("0.02").compareTo(state.payment.payments.get(0).amount));
        assertEquals("CASH", state.payment.payments.get(1).method);
        assertEquals(0, new BigDecimal("24.60").compareTo(state.payment.payments.get(1).amount));
        assertEquals(0, new BigDecimal("24.62").compareTo(state.payment.paidAmount));
        assertEquals(0, BigDecimal.ZERO.compareTo(state.payment.lastChangeAmount));
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * Rounding UP: the sale owes 23,43, the customer hands over 23,45, and the
     * difference booked is NEGATIVE — the other side of the same rule.
     */
    @Test
    void processCashRoundsUpAndBooksTheNegativeDifference() {
        state.cashRoundingStepCents = 5;
        state.ticket.totalAmount = new BigDecimal("23.43");
        state.payment.ticketDbId = 3L;
        service.processCash(state, new BigDecimal("23.45"));
        assertEquals(2, state.payment.payments.size());
        assertEquals(0, new BigDecimal("-0.02").compareTo(state.payment.payments.get(0).amount));
        assertEquals(0, new BigDecimal("23.45").compareTo(state.payment.payments.get(1).amount));
        assertEquals(0, new BigDecimal("23.43").compareTo(state.payment.paidAmount));
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * A remainder already on the step books no rounding at all — the difference is
     * zero and the guard returns before registering anything.
     */
    @Test
    void processCashOnTheStepBooksNoRounding() {
        state.cashRoundingStepCents = 5;
        state.ticket.totalAmount = new BigDecimal("23.45");
        state.payment.ticketDbId = 3L;
        service.processCash(state, new BigDecimal("23.45"));
        assertEquals(1, state.payment.payments.size());
        assertEquals("CASH", state.payment.payments.get(0).method);
    }

    /**
     * A PART payment in cash books no rounding: the sale goes on, its remainder is
     * rounded when it is finally settled, and rounding a part payment would round
     * the same sale twice.
     */
    @Test
    void processCashPartPaymentBooksNoRounding() {
        state.cashRoundingStepCents = 5;
        state.ticket.totalAmount = new BigDecimal("24.62");
        state.payment.ticketDbId = 3L;
        service.processCash(state, new BigDecimal("10.00"));
        assertEquals(1, state.payment.payments.size());
        assertEquals("CASH", state.payment.payments.get(0).method);
        assertFalse(state.payment.transactionComplete);
    }

    /**
     * The rounding applies to what is SETTLED IN CASH and not to the ticket total:
     * a sale part-paid by card rounds the REMAINDER the customer hands over.
     */
    @Test
    void processCashRoundsTheRemainderNotTheTotal() {
        state.cashRoundingStepCents = 5;
        state.ticket.totalAmount = new BigDecimal("24.62");
        state.payment.ticketDbId = 3L;
        state.payment.addPayment("CARD", new BigDecimal("10.00"));
        service.processCash(state, new BigDecimal("14.60"));
        assertEquals(3, state.payment.payments.size());
        assertEquals("ARRONDI", state.payment.payments.get(1).method);
        assertEquals(0, new BigDecimal("0.02").compareTo(state.payment.payments.get(1).amount));
        assertEquals(0, new BigDecimal("24.62").compareTo(state.payment.paidAmount));
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * Overpaying with a note still rounds the sale and gives change on the ROUNDED
     * remainder: handing over 50 on a 24,62 sale returns 25,40, not 25,38.
     */
    @Test
    void processCashOverpaymentGivesChangeOnTheRoundedRemainder() {
        state.cashRoundingStepCents = 5;
        state.ticket.totalAmount = new BigDecimal("24.62");
        state.payment.ticketDbId = 3L;
        service.processCash(state, new BigDecimal("50.00"));
        assertEquals(0, new BigDecimal("25.40").compareTo(state.payment.lastChangeAmount));
        assertEquals(0, new BigDecimal("24.62").compareTo(state.payment.paidAmount));
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * {@code processCash} with an overpayment records the change and shows the
     * change line, but in training neither opens the drawer nor persists
     * (change ternary/if true arm, {@code !trainingMode} false, savePayment
     * training return).
     */
    @Test
    void processCashOverpaymentTrainingShowsChangeNoDrawer() {
        state.trainingMode = true;
        state.ticket.totalAmount = new BigDecimal("10.00");
        service.processCash(state, new BigDecimal("20"));
        assertEquals(0, new BigDecimal("10.00").compareTo(state.payment.lastChangeAmount));
        verify(hardwareService).displayMessage("DONNE 20,00 RENDU 10,00");
        verify(hardwareService, never()).openDrawer();
        verifyNoInteractions(ticketPersistenceService);
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * {@code processCash} outside training logs but does not persist when there
     * is no draft id, and leaves the transaction incomplete (savePayment
     * {@code ticketDbId == null} branch, completion not reached).
     */
    @Test
    void processCashNoDraftDoesNotPersistNorComplete() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = null;
        service.processCash(state, new BigDecimal("5"));
        verify(hardwareService).displayMessage("ESPECES   5,00 E");
        verify(ticketPersistenceService, never()).addPaymentToTicket(any(), any());
        verify(hardwareService).openDrawer();
        assertFalse(state.payment.transactionComplete);
    }

    /**
     * BO-10-02-12: with the drawer-open-on-payment rule DISABLED, an exact
     * cash payment completes and persists but leaves the drawer shut (the
     * {@code drawerOpenOnPayment()} false arm at the cash pulse).
     */
    @Test
    void processCashDrawerRuleDisabledKeepsDrawerShut() {
        when(posSettingsService.drawerOpenOnPayment()).thenReturn(false);
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCash(state, new BigDecimal("20"));
        verify(hardwareService).displayMessage("ESPECES   20,00 E");
        verify(hardwareService, never()).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    // --------------------------------------------------
    // processCard
    // --------------------------------------------------

    /**
     * {@code processCard} with a null amount defaults to the remaining due,
     * parks it as a pending request and hands the debit to the terminal port
     * ({@code amount == null} true, defaulted amount positive,
     * {@code pendingCardAmount == null} true).
     */
    @Test
    void processCardNullAmountParksAndRequestsDebit() {
        state.ticket.totalAmount = new BigDecimal("15.00");
        service.processCard(state, null);
        assertEquals(0, new BigDecimal("15.00").compareTo(state.payment.pendingCardAmount));
        verify(hardwareService).displayMessage("CARTE   15,00 E");
        verify(terminal).requestDebit(eq(new BigDecimal("15.00")), any());
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processCard} returns when the defaulted amount is non-positive
     * ({@code amount == null} false, {@code amount.signum() <= 0} true in the
     * default guard, then the second guard true).
     */
    @Test
    void processCardZeroRemainingReturns() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processCard(state, BigDecimal.ZERO);
        verifyNoInteractions(hardwareService);
        verifyNoInteractions(terminal);
        assertNull(state.payment.pendingCardAmount);
    }

    /**
     * {@code processCard} ignores a second request while one is pending
     * ({@code amount == null} false, positive,
     * {@code pendingCardAmount != null} true).
     */
    @Test
    void processCardPendingIgnored() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.pendingCardAmount = new BigDecimal("5.00");
        service.processCard(state, new BigDecimal("10"));
        assertEquals(0, new BigDecimal("5.00").compareTo(state.payment.pendingCardAmount));
        verifyNoInteractions(hardwareService);
        verifyNoInteractions(terminal);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processCard} behind the auto-accept terminal registers the
     * payment synchronously without opening the drawer (accept leg fired
     * before returning, {@code "CASH".equals} false so a plain payment is
     * added).
     */
    @Test
    void processCardAutoAcceptRegistersNoDrawer() {
        service.terminal = new AutoAcceptTerminalClient();
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCard(state, new BigDecimal("10"));
        assertNull(state.payment.pendingCardAmount);
        assertEquals(0, new BigDecimal("10").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("CARTE     10,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
        verify(hardwareService, never()).openDrawer();
    }

    // --------------------------------------------------
    // terminal callback legs / cancelPendingCard
    // --------------------------------------------------

    /**
     * Captures the callback handed to the terminal port by a processCard
     * call parking the given amount.
     *
     * @param amount the amount to request
     * @return the captured transaction callback
     */
    private TerminalTransactionCallback captureCallback(String amount) {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCard(state, new BigDecimal(amount));
        ArgumentCaptor<TerminalTransactionCallback> captor =
                ArgumentCaptor.forClass(TerminalTransactionCallback.class);
        verify(terminal).requestDebit(any(), captor.capture());
        return captor.getValue();
    }

    /**
     * The accept leg registers the parked amount and clears it
     * ({@code amount == null} false arm of the registration).
     */
    @Test
    void acceptLegRegistersPendingCard() {
        TerminalTransactionCallback cb = captureCallback("15.00");
        cb.onAccepted(TerminalOutcome.ofAmount(new BigDecimal("15.00")));
        assertNull(state.payment.pendingCardAmount);
        assertEquals(0, new BigDecimal("15.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("CARTE     15,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
    }

    /**
     * The accept leg carries the terminal traces (authorization number and
     * degraded-mode indicator) from the outcome onto the registered card entry,
     * which is then persisted (BO-04-01-08/47/49).
     */
    @Test
    void acceptLegCarriesTerminalTraces() {
        TerminalTransactionCallback cb = captureCallback("15.00");
        TerminalOutcome outcome = TerminalOutcome.ofAmount(new BigDecimal("15.00"));
        outcome.authorizationNumber = "654321";
        outcome.degradedMode = true;
        cb.onAccepted(outcome);
        PaymentState.PaymentEntry entry = state.payment.payments.get(0);
        assertEquals("654321", entry.authorizationNumber);
        org.junit.jupiter.api.Assertions.assertTrue(entry.degradedMode);
        assertNull(state.payment.pendingCardAuthNumber);
        assertFalse(state.payment.pendingCardDegraded);
        verify(ticketPersistenceService).addPaymentToTicket(3L, entry);
    }

    /**
     * The accept leg does nothing when the pending amount was cleared
     * meanwhile ({@code amount == null} true arm — ticket cancelled between
     * the request and the terminal decision).
     */
    @Test
    void acceptLegNoneReturns() {
        TerminalTransactionCallback cb = captureCallback("15.00");
        state.payment.pendingCardAmount = null;
        org.mockito.Mockito.reset(hardwareService);
        cb.onAccepted(TerminalOutcome.ofAmount(new BigDecimal("15.00")));
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * The refuse leg drops the pending amount, flags the ticket error and
     * displays the refusal ({@code pendingCardAmount == null} false arm).
     */
    @Test
    void refuseLegClearsAndFlags() {
        TerminalTransactionCallback cb = captureCallback("15.00");
        cb.onRefused(TerminalOutcome.ofAmount(new BigDecimal("15.00")));
        assertNull(state.payment.pendingCardAmount);
        assertEquals("PAIEMENT REFUSÉ PAR LE TPE", state.ticket.transientError);
        verify(hardwareService).displayMessage("PAIEMENT REFUSE");
    }

    /**
     * The refuse leg does nothing when the pending amount was cleared
     * meanwhile ({@code pendingCardAmount == null} true arm).
     */
    @Test
    void refuseLegNoneReturns() {
        TerminalTransactionCallback cb = captureCallback("15.00");
        state.payment.pendingCardAmount = null;
        org.mockito.Mockito.reset(hardwareService);
        cb.onRefused(TerminalOutcome.ofAmount(new BigDecimal("15.00")));
        verifyNoInteractions(hardwareService);
        assertNull(state.ticket.transientError);
    }

    /**
     * The error leg drops the pending amount with the terminal's own
     * message (terminal unreachable, protocol failure).
     */
    @Test
    void errorLegClearsWithTerminalMessage() {
        TerminalTransactionCallback cb = captureCallback("15.00");
        cb.onError("CLIENT MONETIQUE INJOIGNABLE");
        assertNull(state.payment.pendingCardAmount);
        assertEquals("CLIENT MONETIQUE INJOIGNABLE", state.ticket.transientError);
        verify(hardwareService).displayMessage("PAIEMENT REFUSE");
    }

    /**
     * {@code cancelPendingCard} does nothing when no card request is pending
     * ({@code pendingCardAmount == null} true arm).
     */
    @Test
    void cancelPendingCardNoneReturns() {
        state.payment.pendingCardAmount = null;
        service.cancelPendingCard(state);
        verifyNoInteractions(hardwareService);
        verifyNoInteractions(terminal);
    }

    /**
     * {@code cancelPendingCard} drops the pending amount, tells the terminal
     * to abandon and redisplays the total ({@code pendingCardAmount == null}
     * false arm).
     */
    @Test
    void cancelPendingCardClearsAbortsAndShowsTotal() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.pendingCardAmount = new BigDecimal("15.00");
        service.cancelPendingCard(state);
        assertNull(state.payment.pendingCardAmount);
        verify(terminal).abort();
        verify(hardwareService).displayMessage("TOTAL   20,00 E");
    }

    // --------------------------------------------------
    // processTicketResto
    // --------------------------------------------------

    /**
     * {@code processTicketResto} returns when the defaulted amount is
     * non-positive ({@code amount == null} true, second guard true).
     */
    @Test
    void processTicketRestoZeroReturns() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processTicketResto(state, null);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processTicketResto} with no engine cap defaults a blank amount to
     * the remaining due, completes and opens the drawer ({@code amount == null}
     * false with {@code signum() <= 0} true, {@code "ENGINE".equals} false,
     * {@code !trainingMode} true, completion reached).
     */
    @Test
    void processTicketRestoLocalDefaultsCompletesOpensDrawer() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "LOCAL";
        service.processTicketResto(state, BigDecimal.ZERO);
        assertEquals(0, new BigDecimal("20.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("TICKET    20,00 E");
        verify(hardwareService).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * {@code processTicketResto} under an engine cap with a threshold allows
     * up to {@code min(base, threshold)} and registers a fitting request as-is,
     * leaving the engine base untouched — it is a basket hint, not a counter
     * ({@code ENGINE} true, {@code mealEligible != null} true,
     * {@code mealThreshold != null} true, {@code allowed.signum() <= 0} false,
     * {@code amount > allowed} false).
     */
    @Test
    void processTicketRestoEngineWithinThreshold() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("10");
        state.payment.valuationMealThreshold = new BigDecimal("8");
        service.processTicketResto(state, new BigDecimal("5"));
        assertEquals(0, new BigDecimal("10").compareTo(state.payment.valuationMealEligible));
        verify(hardwareService).displayMessage("TICKET    5,00 E");
        verify(hardwareService).openDrawer();
    }

    /**
     * The meal-voucher cap applies to the BASKET: a second meal ticket is
     * capped at the base minus the one already registered, and a third is
     * refused once the allowance is spent — even though the engine base field
     * itself is rewritten by every revaluation and never decremented. This is
     * the regression test for the double 2,67 € payment observed on demo.
     */
    @Test
    void processTicketRestoBasketCapSpansPayments() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("8");
        state.payment.valuationMealThreshold = null;
        service.processTicketResto(state, new BigDecimal("5"));
        service.processTicketResto(state, new BigDecimal("5"));
        verify(hardwareService).displayMessage("TR PLAFONNE  3,00 E");
        assertEquals(0, new BigDecimal("8").compareTo(state.payment.paidAmount));
        service.processTicketResto(state, new BigDecimal("5"));
        verify(hardwareService).displayMessage("TR: AUCUN ARTICLE ELIGIBLE");
        assertEquals(0, new BigDecimal("8").compareTo(state.payment.paidAmount));
        assertEquals(2, state.payment.payments.size());
    }

    /**
     * {@code processTicketResto} caps a request above the allowed base and
     * announces the ceiling, leaving the engine base field untouched
     * ({@code mealThreshold != null} false so the base itself is the ceiling,
     * {@code amount > allowed} true).
     */
    @Test
    void processTicketRestoEngineCapsAboveBase() {
        state.ticket.totalAmount = new BigDecimal("30.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("8");
        state.payment.valuationMealThreshold = null;
        service.processTicketResto(state, new BigDecimal("20"));
        verify(hardwareService).displayMessage("TR PLAFONNE  8,00 E");
        verify(hardwareService).displayMessage("TICKET    8,00 E");
        assertEquals(0, new BigDecimal("8").compareTo(state.payment.valuationMealEligible));
    }

    /**
     * {@code processTicketResto} refuses the payment when the eligible base is
     * exhausted ({@code allowed.signum() <= 0} true), displaying the rejection.
     */
    @Test
    void processTicketRestoEngineBaseExhausted() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = BigDecimal.ZERO;
        state.payment.valuationMealThreshold = null;
        service.processTicketResto(state, new BigDecimal("5"));
        verify(hardwareService).displayMessage("TR: AUCUN ARTICLE ELIGIBLE");
        verify(hardwareService, never()).openDrawer();
        assertTrue(state.payment.payments.isEmpty());
    }

    // --------------------------------------------------
    // processTrdCard (EXP-60)
    // --------------------------------------------------

    /**
     * {@code processTrdCard} returns on a non-positive defaulted amount
     * (amount null → remaining, second guard true).
     */
    @Test
    void processTrdCardZeroReturns() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processTrdCard(state, null);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processTrdCard} refuses when the meal-voucher base is exhausted
     * (allowance signum &lt;= 0 arm).
     */
    @Test
    void processTrdCardBaseExhausted() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = BigDecimal.ZERO;
        state.payment.valuationMealThreshold = null;
        service.processTrdCard(state, new BigDecimal("5"));
        verify(hardwareService).displayMessage("TRD: AUCUN ARTICLE ELIGIBLE");
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processTrdCard} caps a request above the eligible base and settles
     * the ceiling (amount &gt; allowance arm; no administered cap).
     */
    @Test
    void processTrdCardCappedByBase() {
        state.ticket.totalAmount = new BigDecimal("30.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("8");
        state.payment.valuationMealThreshold = null;
        when(posSettingsService.trdCardCap()).thenReturn(BigDecimal.ZERO);
        service.processTrdCard(state, new BigDecimal("20"));
        verify(hardwareService).displayMessage("TRD PLAFONNE  8,00 E");
        assertEquals(0, new BigDecimal("8").compareTo(state.payment.paidAmount));
    }

    /**
     * {@code processTrdCard} caps at the administered plafond when it bites below
     * the eligible base (cap &gt; 0 and amount &gt; cap arm).
     */
    @Test
    void processTrdCardCappedByCap() {
        state.ticket.totalAmount = new BigDecimal("30.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("1000");
        state.payment.valuationMealThreshold = null;
        when(posSettingsService.trdCardCap()).thenReturn(new BigDecimal("10"));
        service.processTrdCard(state, new BigDecimal("20"));
        verify(hardwareService).displayMessage("TRD PLAFONNE  10,00 E");
        assertEquals(0, new BigDecimal("10").compareTo(state.payment.paidAmount));
    }

    /**
     * {@code processTrdCard} settles a fitting amount within the base with no
     * plafond (the handlePaymentWithChange settle path).
     */
    @Test
    void processTrdCardCompletesWithinBaseAndCap() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("1000");
        state.payment.valuationMealThreshold = null;
        when(posSettingsService.trdCardCap()).thenReturn(BigDecimal.ZERO);
        service.processTrdCard(state, new BigDecimal("5"));
        assertEquals(0, new BigDecimal("5").compareTo(state.payment.paidAmount));
    }

    // --------------------------------------------------
    // processRestoFlash (EXP-67)
    // --------------------------------------------------

    /**
     * {@code processRestoFlash} returns on a non-positive defaulted amount.
     */
    @Test
    void processRestoFlashZeroReturns() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processRestoFlash(state, "TOK", null);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processRestoFlash} refuses when the meal-voucher base is exhausted.
     */
    @Test
    void processRestoFlashBaseExhausted() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = BigDecimal.ZERO;
        state.payment.valuationMealThreshold = null;
        service.processRestoFlash(state, "TOK", new BigDecimal("5"));
        verify(hardwareService).displayMessage("RESTO FLASH: AUCUN ARTICLE ELIGIBLE");
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processRestoFlash} settles nothing when the service refuses the
     * token (confirm false arm).
     */
    @Test
    void processRestoFlashRefusedByService() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("1000");
        state.payment.valuationMealThreshold = null;
        when(restoFlashClient.confirm(eq("TOK"), any())).thenReturn(false);
        service.processRestoFlash(state, "TOK", new BigDecimal("5"));
        verify(hardwareService).displayMessage("RESTO FLASH REFUSE");
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processRestoFlash} settles when the service confirms the token
     * (confirm true arm, the settle path).
     */
    @Test
    void processRestoFlashConfirmedSettles() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("1000");
        state.payment.valuationMealThreshold = null;
        when(restoFlashClient.confirm(eq("TOK"), any())).thenReturn(true);
        service.processRestoFlash(state, "TOK", new BigDecimal("5"));
        assertEquals(0, new BigDecimal("5").compareTo(state.payment.paidAmount));
    }

    // --------------------------------------------------
    // processSupplierGiftCard (EXP-66)
    // --------------------------------------------------

    /**
     * {@code processSupplierGiftCard} refuses an invalid card (verdict.valid
     * false arm).
     */
    @Test
    void processSupplierGiftCardRefusedWhenInvalid() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        when(giftCardIssuerClient.query("GC1"))
                .thenReturn(new GiftCardIssuerClient.IssuerVerdict(false, BigDecimal.ZERO));
        service.processSupplierGiftCard(state, "GC1", new BigDecimal("5"));
        verify(hardwareService).displayMessage("CARTE CADEAU REFUSEE");
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processSupplierGiftCard} refuses a valid card with a nil balance
     * (balance null/&lt;=0 arm).
     */
    @Test
    void processSupplierGiftCardRefusedWhenBalanceNil() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        when(giftCardIssuerClient.query("GC1"))
                .thenReturn(new GiftCardIssuerClient.IssuerVerdict(true, BigDecimal.ZERO));
        service.processSupplierGiftCard(state, "GC1", new BigDecimal("5"));
        verify(hardwareService).displayMessage("CARTE CADEAU: SOLDE NUL");
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processSupplierGiftCard} caps the payment at the issuer balance
     * (amount &gt; balance arm) and settles it.
     */
    @Test
    void processSupplierGiftCardCappedAtBalance() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(giftCardIssuerClient.query("GC1"))
                .thenReturn(new GiftCardIssuerClient.IssuerVerdict(true, new BigDecimal("5")));
        service.processSupplierGiftCard(state, "GC1", new BigDecimal("20"));
        verify(hardwareService).displayMessage("CARTE CADEAU  5,00 E");
        assertEquals(0, new BigDecimal("5").compareTo(state.payment.paidAmount));
    }

    /**
     * {@code processSupplierGiftCard} settles a fitting amount below the balance
     * (amount &lt;= balance arm, the settle path).
     */
    @Test
    void processSupplierGiftCardSettlesWithinBalance() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(giftCardIssuerClient.query("GC1"))
                .thenReturn(new GiftCardIssuerClient.IssuerVerdict(true, new BigDecimal("100")));
        service.processSupplierGiftCard(state, "GC1", new BigDecimal("5"));
        assertEquals(0, new BigDecimal("5").compareTo(state.payment.paidAmount));
    }

    // --------------------------------------------------
    // processSogec (EXP-58)
    // --------------------------------------------------

    /**
     * {@code processSogec} with NO value at all — nothing decoded from the bon
     * (null arm of the face-value fallback) and nothing keyed (null arm of the
     * refusal guard) — settles nothing and says so. This is the regression the
     * recette found: the register used to read "no amount" as "the whole ticket".
     */
    @Test
    void processSogecWithoutAnyValueSettlesNothing() {
        state.ticket.totalAmount = new BigDecimal("56.50");
        state.payment.ticketDbId = 3L;
        service.processSogec(state, "SG1", null);
        verify(hardwareService).displayMessage("BON SOGEC: VALEUR REQUISE");
        verify(sogecCompensationService, never()).recordCompensation(any(), any());
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processSogec} with a keyed value of zero (non-positive arm of the
     * refusal guard, the null arm being covered above) also settles nothing: a bon
     * is worth what is printed on it, and zero is not a value.
     */
    @Test
    void processSogecWithAZeroValueSettlesNothing() {
        state.ticket.totalAmount = new BigDecimal("56.50");
        state.payment.ticketDbId = 3L;
        service.processSogec(state, "SG1", BigDecimal.ZERO);
        verify(hardwareService).displayMessage("BON SOGEC: VALEUR REQUISE");
        verify(sogecCompensationService, never()).recordCompensation(any(), any());
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processSogec} settles the KEYED value when the bon's barcode carries
     * none (null arm of the decoded value) and records the compensation trace for
     * the supplier reimbursement.
     */
    @Test
    void processSogecSettlesAndRecordsCompensation() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processSogec(state, "SG1", new BigDecimal("5"));
        assertEquals(0, new BigDecimal("5").compareTo(state.payment.paidAmount));
        verify(sogecCompensationService).recordCompensation("SG1", new BigDecimal("5"));
    }

    /**
     * A bon whose own barcode carries its face value (non-null arm of the decoded
     * value) settles THAT value, whatever the cashier keyed: the paper wins.
     */
    @Test
    void processSogecPrefersTheValueEncodedOnTheBon() {
        state.ticket.totalAmount = new BigDecimal("56.50");
        state.payment.ticketDbId = 3L;
        when(sogecCompensationService.faceValueOf("SG1")).thenReturn(new BigDecimal("10.00"));
        service.processSogec(state, "SG1", new BigDecimal("5"));
        assertEquals(0, new BigDecimal("10.00").compareTo(state.payment.paidAmount));
        verify(sogecCompensationService).recordCompensation("SG1", new BigDecimal("10.00"));
    }

    /**
     * A 20 € bon on a 56,50 € ticket settles TWENTY EUROS and leaves 36,50 € to
     * pay — the exact case the recette reported coming out paid in full by SOGEC.
     * The cap arm is not taken, so nothing is announced.
     */
    @Test
    void processSogecSettlesOnlyItsFaceValueOnALargerTicket() {
        state.ticket.totalAmount = new BigDecimal("56.50");
        state.payment.ticketDbId = 3L;
        service.processSogec(state, "SG1", new BigDecimal("20.00"));
        assertEquals(0, new BigDecimal("20.00").compareTo(state.payment.paidAmount));
        assertEquals(0, new BigDecimal("36.50").compareTo(state.getRemaining()));
        verify(hardwareService, never()).displayMessage(startsWith("BON SOGEC PLAFONNE"));
    }

    /**
     * A bon worth MORE than what is left to pay is capped at the remainder (cap arm
     * true), the cap is announced, and the compensation trace carries the capped
     * amount: the register hands no change back against a manufacturer's coupon.
     */
    @Test
    void processSogecCapsABonLargerThanTheRemainder() {
        state.ticket.totalAmount = new BigDecimal("8.00");
        state.payment.ticketDbId = 3L;
        service.processSogec(state, "SG1", new BigDecimal("20.00"));
        assertEquals(0, new BigDecimal("8.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("BON SOGEC PLAFONNE  8,00 E");
        verify(sogecCompensationService).recordCompensation("SG1", new BigDecimal("8.00"));
    }

    /**
     * A bon presented on an already settled ticket (non-positive remainder) settles
     * nothing and records nothing — and says nothing either, the cashier having made
     * no mistake worth a message.
     */
    @Test
    void processSogecOnASettledTicketDoesNothing() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processSogec(state, "SG1", new BigDecimal("20.00"));
        verifyNoInteractions(hardwareService);
        verify(sogecCompensationService, never()).recordCompensation(any(), any());
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processTicketResto} applies no cap when the engine emitted no
     * eligible base, taking a positive explicit amount as-is ({@code ENGINE}
     * true but {@code mealEligible != null} false; default guard fully false).
     */
    @Test
    void processTicketRestoEngineNoEligibleBase() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = null;
        service.processTicketResto(state, new BigDecimal("5"));
        assertEquals(0, new BigDecimal("5").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("TICKET    5,00 E");
        verify(hardwareService).openDrawer();
    }

    /**
     * The basket total behind the engine cap counts ONLY meal tickets: a
     * non-meal payment already on the ticket (here a card part-payment) is
     * skipped, so the full eligible base is still available to this meal ticket
     * ({@code "TR".equals(entry.method)} false arm of the summation loop).
     */
    @Test
    void processTicketRestoEngineBasketTotalSkipsNonMealPayments() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("8");
        state.payment.valuationMealThreshold = null;
        state.payment.addPayment("CARD", new BigDecimal("10.00"));
        service.processTicketResto(state, new BigDecimal("5"));
        verify(hardwareService).displayMessage("TICKET    5,00 E");
        assertEquals(0, new BigDecimal("15.00").compareTo(state.payment.paidAmount));
    }

    /**
     * {@code processTicketResto} in training does not open the drawer
     * ({@code !trainingMode} false arm).
     */
    @Test
    void processTicketRestoTrainingNoDrawer() {
        state.trainingMode = true;
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processTicketResto(state, new BigDecimal("5"));
        verify(hardwareService).displayMessage("TICKET    5,00 E");
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * BO-10-02-12: with the drawer-open-on-payment rule DISABLED, a meal
     * voucher registers but the drawer stays shut (false arm at the TR pulse).
     */
    @Test
    void processTicketRestoDrawerRuleDisabledKeepsDrawerShut() {
        when(posSettingsService.drawerOpenOnPayment()).thenReturn(false);
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "LOCAL";
        service.processTicketResto(state, BigDecimal.ZERO);
        verify(hardwareService).displayMessage("TICKET    20,00 E");
        verify(hardwareService, never()).openDrawer();
    }

    // --------------------------------------------------
    // processCheque
    // --------------------------------------------------

    /**
     * {@code processCheque} returns when the defaulted amount is non-positive
     * ({@code amount == null} true, second guard true).
     */
    @Test
    void processChequeZeroReturns() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processCheque(state, null);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processCheque} defaults a non-positive amount to the remaining due,
     * completes, persists and opens the drawer ({@code amount == null} false
     * with {@code signum() <= 0} true, {@code !trainingMode} true).
     */
    @Test
    void processChequeDefaultsCompletesOpensDrawer() {
        chequeReaderAnswers("a0007639 a800000000909r 000000000000i");
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCheque(state, BigDecimal.ZERO);
        service.settlePendingChequeRead(state);
        assertEquals(0, new BigDecimal("20.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("CHEQUE    20,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
        verify(hardwareService).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * {@code processCheque} for a partial explicit amount in training neither
     * opens the drawer nor completes ({@code amount == null} false with
     * {@code signum() <= 0} false, {@code !trainingMode} false).
     */
    @Test
    void processChequePartialTrainingNoDrawer() {
        state.trainingMode = true;
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processCheque(state, new BigDecimal("5"));
        verify(hardwareService).displayMessage("CHEQUE    5,00 E");
        verify(hardwareService, never()).openDrawer();
        assertFalse(state.payment.transactionComplete);
    }

    /**
     * BO-10-02-12: with the drawer-open-on-payment rule DISABLED, a cheque
     * registers but the drawer stays shut (false arm at the cheque pulse).
     */
    @Test
    void processChequeDrawerRuleDisabledKeepsDrawerShut() {
        chequeReaderAnswers("a0007639 a800000000909r 000000000000i");
        when(posSettingsService.drawerOpenOnPayment()).thenReturn(false);
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCheque(state, BigDecimal.ZERO);
        service.settlePendingChequeRead(state);
        verify(hardwareService).displayMessage("CHEQUE    20,00 E");
        verify(hardwareService, never()).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * The endorsement leaves WITH the reading: the reader prints on the cheque while
     * it still holds it, so the text cannot be sent afterwards. Its two lines are the
     * date and the amount the register knows and the paper does not.
     */
    @Test
    void processChequeSendsTheEndorsementWithTheReading() {
        chequeReaderAnswers("a0007639 a800000000909r 000000000000i");
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        ArgumentCaptor<String> endorsement = ArgumentCaptor.forClass(String.class);
        service.processCheque(state, BigDecimal.ZERO);
        verify(chequeReadingService).read(endorsement.capture(), any(), any());
        String[] lines = endorsement.getValue().split("\n");
        assertEquals(2, lines.length);
        assertEquals("20,00 EUR", lines[1]);
    }

    /**
     * A cheque the reader refuses settles NOTHING: the pending amount is dropped, the
     * cashier is told why and the ticket keeps its due.
     */
    @Test
    void processChequeRefusedSettlesNothing() {
        chequeReaderRefuses("AUCUNE DONNEE MAGNETIQUE");
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCheque(state, BigDecimal.ZERO);
        service.settlePendingChequeRead(state);
        assertTrue(state.payment.payments.isEmpty());
        assertNull(state.payment.pendingChequeAmount);
        assertFalse(state.payment.transactionComplete);
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * A reading that lands AFTER the cashier cancelled is ignored ({@code
     * pendingChequeAmount == null} true): the cheque was given up on, and a payment
     * appearing by itself on a ticket the cashier moved on from is worse than a
     * cheque re-presented.
     */
    @Test
    void processChequeReadAfterCancellationIsIgnored() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onRead =
                    (java.util.function.Consumer<String>) invocation.getArgument(1);
            service.cancelPendingCheque(state);
            onRead.accept("a0007639 a800000000909r 000000000000i");
            return null;
        }).when(chequeReadingService).read(any(), any(), any());
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCheque(state, BigDecimal.ZERO);
        service.settlePendingChequeRead(state);
        assertTrue(state.payment.payments.isEmpty());
        assertFalse(state.payment.transactionComplete);
    }

    /**
     * A refusal that lands after the cashier cancelled changes nothing either
     * ({@code pendingChequeAmount == null} true in the drop path).
     */
    @Test
    void processChequeRefusedAfterCancellationIsIgnored() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onError =
                    (java.util.function.Consumer<String>) invocation.getArgument(2);
            service.cancelPendingCheque(state);
            onError.accept("BOURRAGE");
            return null;
        }).when(chequeReadingService).read(any(), any(), any());
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processCheque(state, BigDecimal.ZERO);
        service.settlePendingChequeRead(state);
        assertTrue(state.payment.payments.isEmpty());
        assertNull(state.payment.pendingChequeAmount);
    }

    /**
     * {@code cancelPendingCheque} on a register expecting no cheque is a no-op
     * ({@code pendingChequeAmount == null} true, the guard's other arm).
     */
    @Test
    void cancelPendingChequeWithoutAPendingChequeDoesNothing() {
        long version = state.version;
        service.cancelPendingCheque(state);
        assertEquals(version, state.version);
    }

    // --------------------------------------------------
    // processFidelity
    // --------------------------------------------------

    /**
     * {@code processFidelity} returns on a settled ticket: nothing is due, so
     * no lease is even requested (first guard true).
     */
    @Test
    void processFidelityZeroReturns() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processFidelity(state, null);
        verifyNoInteractions(hardwareService);
        verifyNoInteractions(fidelityService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * A REFUSED lease registers no payment and shows the refusal message
     * verbatim — imfid's closed nomenclature reaches the cashier untouched
     * (refusal arm of the reservation protocol, spec §5.1).
     */
    @Test
    void processFidelityRefusedShowsMessageAndPaysNothing() {
        state.ticket.totalAmount = new BigDecimal("10.00");
        when(fidelityService.reserveLease(eq(state), any(), any()))
                .thenReturn(refusal("SOLDE CAGNOTTE INSUFFISANT"));
        service.processFidelity(state, new BigDecimal("4"));
        assertEquals("SOLDE CAGNOTTE INSUFFISANT", state.ticket.transientError);
        assertTrue(state.payment.payments.isEmpty());
        verifyNoInteractions(hardwareService);
    }

    /**
     * Builds a refusal verdict carrying the given display message.
     *
     * @param message the exact message the cashier must see
     * @return the refusal verdict
     */
    private FidelityService.BurnVerdict refusal(String message) {
        FidelityService.BurnVerdict verdict = new FidelityService.BurnVerdict();
        verdict.refusalMessage = message;
        return verdict;
    }

    /**
     * Builds a granted verdict for the given amount.
     *
     * @param amount the amount the lease grants
     * @return the granted verdict
     */
    private FidelityService.BurnVerdict grant(String amount) {
        FidelityService.BurnVerdict verdict = new FidelityService.BurnVerdict();
        verdict.grantedAmount = new BigDecimal(amount);
        return verdict;
    }

    /**
     * {@code processFidelity} defaults a non-positive amount to the remaining
     * due, persists it and completes without a drawer opening ({@code amount ==
     * null} false with {@code signum() <= 0} true, second guard false).
     */
    @Test
    void processFidelityDefaultsCompletesNoDrawer() {
        state.ticket.totalAmount = new BigDecimal("10.00");
        state.payment.ticketDbId = 3L;
        // The GRANTED amount is imfid's, not the caller's: the lease caps it
        // to min(asked, due, burnable base, available balance).
        when(fidelityService.reserveLease(eq(state), any(), any()))
                .thenReturn(grant("10.00"));
        service.processFidelity(state, BigDecimal.ZERO);
        assertEquals(0, new BigDecimal("10.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("FIDELITE  10,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
        verify(hardwareService, never()).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * {@code processFidelity} caps a positive explicit amount at the remaining
     * due and stays incomplete ({@code amount == null} false with
     * {@code signum() <= 0} false — the default guard fully false).
     */
    @Test
    void processFidelityPositivePartial() {
        state.ticket.totalAmount = new BigDecimal("10.00");
        state.payment.ticketDbId = 3L;
        when(fidelityService.reserveLease(eq(state), any(), any()))
                .thenReturn(grant("4"));
        service.processFidelity(state, new BigDecimal("4"));
        assertEquals(0, new BigDecimal("4").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("FIDELITE  4,00 E");
        assertFalse(state.payment.transactionComplete);
    }

    // --------------------------------------------------
    // processVoucher
    // --------------------------------------------------

    /**
     * {@code processVoucher} rejects a null amount ({@code amount == null} true
     * arm).
     */
    @Test
    void processVoucherNullReturns() {
        service.processVoucher(state, "BON", "N1", null);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processVoucher} rejects a non-positive amount ({@code amount ==
     * null} false, {@code amount.signum() <= 0} true).
     */
    @Test
    void processVoucherZeroReturns() {
        service.processVoucher(state, "BON", "N1", BigDecimal.ZERO);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processVoucher} registers the voucher, persists it and completes
     * ({@code amount == null} false, positive; saveVoucherPayment persists with
     * a draft id, completion reached).
     */
    @Test
    void processVoucherRegistersAndCompletes() {
        state.ticket.totalAmount = new BigDecimal("12.00");
        state.payment.ticketDbId = 3L;
        service.processVoucher(state, "BON", "N1", new BigDecimal("12.00"));
        assertEquals(0, new BigDecimal("12.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("BON       12,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * {@code processVoucher} in training registers in memory but persists
     * nothing (saveVoucherPayment {@code trainingMode} true arm).
     */
    @Test
    void processVoucherTrainingNoPersist() {
        state.trainingMode = true;
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processVoucher(state, "BON", "N1", new BigDecimal("5.00"));
        assertEquals(0, new BigDecimal("5.00").compareTo(state.payment.paidAmount));
        verifyNoInteractions(ticketPersistenceService);
        assertFalse(state.payment.transactionComplete);
    }

    /**
     * {@code processVoucher} outside training does not persist when there is no
     * draft id (saveVoucherPayment {@code ticketDbId == null} arm).
     */
    @Test
    void processVoucherNoDraftNoPersist() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = null;
        service.processVoucher(state, "BON", "N1", new BigDecimal("5.00"));
        verify(ticketPersistenceService, never()).addPaymentToTicket(any(), any());
        verify(hardwareService).displayMessage("BON       5,00 E");
    }

    // --------------------------------------------------
    // applyPrintChoice (LC-08-03)
    // --------------------------------------------------

    /**
     * Makes the conditional-printing rule answer with the given decision.
     *
     * @param saleTicket whether the sale ticket is printed
     * @param cardReceipt whether the card receipt is printed
     * @param voucher whether the vouchers are printed
     */
    private void stubDecision(boolean saleTicket, boolean cardReceipt, boolean voucher) {
        when(printPolicy.decide(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new com.intermarche.pos.ui.hardware.PrintPolicy.Decision(
                        saleTicket, cardReceipt, voucher));
    }

    /**
     * Applies a printing choice with the Panache statics neutralized — the rule
     * reads the ticket back to look for a GLC article.
     *
     * @param choice the cashier's choice
     */
    private void applyChoice(com.intermarche.pos.ui.hardware.PrintChoice choice) {
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            service.applyPrintChoice(state, choice);
        }
    }

    /**
     * A decision naming the sale ticket prints it — and only it
     * (LC-08-03-03).
     */
    @Test
    void applyPrintChoicePrintsTheSaleTicket() {
        state.payment.ticketDbId = 9L;
        stubDecision(true, false, false);
        applyChoice(com.intermarche.pos.ui.hardware.PrintChoice.SALE_TICKET);
        verify(ticketPrinterService).printTicket(9L);
        verify(ticketPrinterService, never()).printCardReceipt(any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
        assertTrue(state.payment.printApplied);
    }

    /**
     * BO-03-03-36: where the store administers the exit ticket, it REPLACES the
     * sale receipt — the printer answers one, and the receipt is not printed.
     */
    @Test
    void applyPrintChoicePrintsTheExitTicketInsteadOfTheReceipt() {
        state.payment.ticketDbId = 9L;
        stubDecision(true, false, false);
        org.mockito.Mockito.when(ticketPrinterService.printExitTicket(9L)).thenReturn(1);
        applyChoice(com.intermarche.pos.ui.hardware.PrintChoice.SALE_TICKET);
        verify(ticketPrinterService).printExitTicket(9L);
        verify(ticketPrinterService, never()).printTicket(any());
        assertTrue(state.payment.printApplied);
    }

    /**
     * And where it is not administered the printer answers zero, so the receipt
     * prints exactly as before — the other leg of the substitution.
     */
    @Test
    void applyPrintChoiceFallsBackToTheReceiptWithoutAnExitTicket() {
        state.payment.ticketDbId = 9L;
        stubDecision(true, false, false);
        org.mockito.Mockito.when(ticketPrinterService.printExitTicket(9L)).thenReturn(0);
        applyChoice(com.intermarche.pos.ui.hardware.PrintChoice.SALE_TICKET);
        verify(ticketPrinterService).printExitTicket(9L);
        verify(ticketPrinterService).printTicket(9L);
    }

    /**
     * A decision naming the card receipt prints it — and only it
     * (LC-08-03-04).
     */
    @Test
    void applyPrintChoicePrintsTheCardReceipt() {
        state.payment.ticketDbId = 9L;
        state.payment.cardSignatureRequired = true;
        stubDecision(false, true, false);
        applyChoice(com.intermarche.pos.ui.hardware.PrintChoice.CARD_RECEIPT);
        verify(ticketPrinterService).printCardReceipt(9L, true, null);
        verify(ticketPrinterService, never()).printTicket(any());
    }

    /**
     * A decision naming nothing prints nothing, and still records the choice as
     * applied so the closing step adds none (LC-08-03-06).
     */
    @Test
    void applyPrintChoicePrintsNothingButRecordsTheChoice() {
        state.payment.ticketDbId = 9L;
        stubDecision(false, false, false);
        applyChoice(com.intermarche.pos.ui.hardware.PrintChoice.NONE);
        verify(ticketPrinterService, never()).printTicket(any());
        verify(ticketPrinterService, never()).printCardReceipt(any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
        assertTrue(state.payment.printApplied);
        assertEquals(com.intermarche.pos.ui.hardware.PrintChoice.NONE, state.payment.printChoice);
    }

    /**
     * A second application prints nothing: a reloaded modal never produces a
     * second original ({@code printApplied} true arm).
     */
    @Test
    void applyPrintChoiceIgnoresASecondCall() {
        state.payment.ticketDbId = 9L;
        state.payment.printApplied = true;
        stubDecision(true, true, true);
        applyChoice(com.intermarche.pos.ui.hardware.PrintChoice.ALL);
        verify(ticketPrinterService, never()).printTicket(any());
    }

    /**
     * Without a draft there is nothing to print ({@code ticketDbId == null}
     * arm).
     */
    @Test
    void applyPrintChoiceWithoutDraftPrintsNothing() {
        state.payment.ticketDbId = null;
        stubDecision(true, true, true);
        applyChoice(com.intermarche.pos.ui.hardware.PrintChoice.ALL);
        verify(ticketPrinterService, never()).printTicket(any());
        assertFalse(state.payment.printApplied);
    }

    /**
     * The refuse leg prints the not-completed-transaction slip when the back
     * office forces it, with the terminal's own frame (LC-08-03-12).
     */
    @Test
    void refuseLegPrintsTheTnaReceiptWhenForced() {
        when(printPolicy.isTnaReceiptForced()).thenReturn(true);
        TerminalTransactionCallback cb = captureCallback("15.00");
        TerminalOutcome outcome = TerminalOutcome.ofAmount(new BigDecimal("15.00"));
        outcome.tnaFrame = "TRAME TNA";
        cb.onRefused(outcome);
        verify(ticketPrinterService).printCardTnaReceipt(new BigDecimal("15.00"), "TRAME TNA");
    }

    /**
     * The refuse leg prints no slip when the rule is off — the historical
     * behaviour.
     */
    @Test
    void refuseLegPrintsNoTnaReceiptWhenNotForced() {
        when(printPolicy.isTnaReceiptForced()).thenReturn(false);
        TerminalTransactionCallback cb = captureCallback("15.00");
        cb.onRefused(TerminalOutcome.ofAmount(new BigDecimal("15.00")));
        verify(ticketPrinterService, never()).printCardTnaReceipt(any(), any());
    }

    /**
     * The refuse leg prints no not-completed slip when the terminal supplied NO
     * outcome, even with the rule forced: there is no frame to print
     * ({@code outcome != null} false arm short-circuits the {@code &&}). The
     * pending amount is still dropped with the standard refusal message.
     */
    @Test
    void refuseLegWithNullOutcomePrintsNoTnaReceipt() {
        when(printPolicy.isTnaReceiptForced()).thenReturn(true);
        TerminalTransactionCallback cb = captureCallback("15.00");
        cb.onRefused(null);
        verify(ticketPrinterService, never()).printCardTnaReceipt(any(), any());
        assertNull(state.payment.pendingCardAmount);
        assertEquals("PAIEMENT REFUSÉ PAR LE TPE", state.ticket.transientError);
    }

    /**
     * The accept leg raises the signature flag the printing rule reads
     * (LC-08-03-11).
     */
    @Test
    void acceptLegRecordsTheSignatureRequirement() {
        TerminalTransactionCallback cb = captureCallback("15.00");
        TerminalOutcome outcome = TerminalOutcome.ofAmount(new BigDecimal("15.00"));
        outcome.signatureRequired = true;
        cb.onAccepted(outcome);
        assertTrue(state.payment.cardSignatureRequired);
    }

    /**
     * An accepted transaction asking for no signature leaves the flag down.
     */
    @Test
    void acceptLegLeavesTheSignatureFlagDown() {
        TerminalTransactionCallback cb = captureCallback("15.00");
        cb.onAccepted(TerminalOutcome.ofAmount(new BigDecimal("15.00")));
        assertFalse(state.payment.cardSignatureRequired);
    }

    // --------------------------------------------------
    // finalizeTransaction
    // --------------------------------------------------

    /**
     * {@code finalizeTransaction} validates the draft, remembers it as the last
     * closed ticket and clears the state ({@code ticketId != null} true arm).
     */
    @Test
    void finalizeTransactionValidatesDraft() {
        state.payment.ticketDbId = 9L;
        // The fiscal moment looks up the gift cards ISSUED by this sale to
        // print their vouchers: a Panache call, neutralized here (plain
        // mvn test leaves entities un-enhanced). No card issued = no voucher.
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(ticketPersistenceService).validateTicket(9L);
        assertEquals(9L, state.lastClosedTicketId);
        verify(hardwareService).displayMessage("MERCI A BIENTOT");
        assertNull(state.payment.ticketDbId);
    }

    /**
     * An engine-valued sale declares its campaign consumption at the fiscal
     * moment, derived from the retained valuation, with the ticket's number,
     * store code (null here) and closing date ({@code "ENGINE".equals} true,
     * {@code valued != null} true).
     */
    @Test
    void finalizeTransactionEnqueuesConsumptionForEngineValuation() {
        state.payment.ticketDbId = 9L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationJson = "{\"advantages\":[]}";
        com.intermarche.pos.domain.sale.Ticket valued =
                org.mockito.Mockito.mock(com.intermarche.pos.domain.sale.Ticket.class);
        valued.ticketNumber = "C04-000003";
        valued.closingDate = java.time.LocalDateTime.of(2026, 8, 2, 10, 30);
        valued.store = null;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            panache.when(() -> com.intermarche.pos.domain.sale.Ticket.findById(9L)).thenReturn(valued);
            service.finalizeTransaction(state);
        }
        verify(service.consumptionService).enqueueSale("C04-000003", null,
                java.time.LocalDateTime.of(2026, 8, 2, 10, 30), "{\"advantages\":[]}");
    }

    /**
     * The retained engine offer messages are frozen on the ticket at the fiscal
     * moment (spec §3): each {@code EngineMessage} on the payment state is mapped
     * to a {@code TicketMessage} and handed to the persistence service.
     */
    @Test
    void finalizeTransactionFreezesEngineMessagesOnTheTicket() {
        state.payment.ticketDbId = 9L;
        state.payment.valuationMessages.add(
                new com.intermarche.pos.ui.valuation.ValuationService.EngineMessage(
                        "BELOW_TRIGGER", "Plus que 5 €", "FOOTER"));
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<com.intermarche.pos.domain.sale.TicketMessage>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(ticketPersistenceService).storeEngineMessages(
                org.mockito.ArgumentMatchers.eq(9L), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("Plus que 5 €", captor.getValue().get(0).text);
        assertEquals("BELOW_TRIGGER", captor.getValue().get(0).code);
        assertEquals("FOOTER", captor.getValue().get(0).printMode);
    }

    /**
     * An engine-valued sale whose ticket cannot be reloaded declares nothing
     * ({@code "ENGINE".equals} true, {@code valued != null} false).
     */
    @Test
    void finalizeTransactionDeclaresNoConsumptionWhenTicketVanished() {
        state.payment.ticketDbId = 9L;
        state.payment.valuationStatus = "ENGINE";
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(service.consumptionService, never()).enqueueSale(any(), any(), any(), any());
    }

    /**
     * A locally-valued sale declares no engine consumption ({@code
     * "ENGINE".equals} false).
     */
    @Test
    void finalizeTransactionDeclaresNoConsumptionForLocalValuation() {
        state.payment.ticketDbId = 9L;
        state.payment.valuationStatus = "LOCAL";
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(service.consumptionService, never()).enqueueSale(any(), any(), any(), any());
    }

    /**
     * WITHOUT a card, the fiscal moment declares nothing loyalty-side: no
     * lease to confirm, no event to enqueue.
     *
     * <p>It does still ASK whether the programme was reachable, and freezes the
     * answer: a receipt printed for a non-holder while the service was silent
     * carries its own message (BO-03-03-30), so the question is asked on every
     * sale and not only on the ones that presented a card.
     */
    @Test
    void finalizeTransactionWithoutCardEnqueuesNoLoyaltyEvent() {
        state.payment.ticketDbId = 9L;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(fidelityService).isUnavailable();
        verify(fidelityService, never()).confirmLease(any(), any());
        verify(fidelityService, never()).buildTicketClosedPayload(
                any(), any(), any(), any(), any());
        verifyNoInteractions(fidEventOutboxService);
    }

    /**
     * BO-03-03-25/-29/-31: the closing FREEZES the loyalty zone on the sale —
     * the displayed earn, its advantage lines, the balance read at attachment
     * and whether the service answered. Frozen here and nowhere else: the
     * projection is cleared with the state a few lines later.
     */
    @Test
    void finalizeTransactionFreezesTheLoyaltyZoneOnTheSale() {
        state.payment.ticketDbId = 9L;
        state.fidelity.assignCard("2990000000019");
        state.fidelity.earnTotal = new java.math.BigDecimal("1.03");
        state.fidelity.availableBalance = new java.math.BigDecimal("42.30");
        state.fidelity.earnEntries.add(
                new com.intermarche.pos.ui.fidelity.FidelityState.EarnLine(
                        "SOCLE", "Cagnotte socle", new java.math.BigDecimal("0.28")));
        when(fidelityService.isUnavailable()).thenReturn(true);
        com.intermarche.pos.domain.sale.Ticket closed =
                org.mockito.Mockito.mock(com.intermarche.pos.domain.sale.Ticket.class);
        closed.ticketNumber = "C04-000003";
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            panache.when(() -> com.intermarche.pos.domain.sale.Ticket.findById(9L))
                    .thenReturn(closed);
            service.finalizeTransaction(state);
        }
        org.mockito.ArgumentCaptor<java.util.List<com.intermarche.pos.domain.sale.TicketFidelityLine>>
                frozen = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(ticketPersistenceService).storeFidelity(eq(9L),
                eq(new java.math.BigDecimal("1.03")), eq(new java.math.BigDecimal("42.30")),
                eq(true), frozen.capture(), eq(null), eq(null), eq(false));
        assertEquals(1, frozen.getValue().size());
        assertEquals("SOCLE", frozen.getValue().get(0).ruleCode);
        assertEquals("Cagnotte socle", frozen.getValue().get(0).label);
        assertEquals(new java.math.BigDecimal("0.28"), frozen.getValue().get(0).amount);
    }

    /**
     * A sale that displayed NO advantage still writes: an empty list and three
     * nulls, which is what the zone of a non-holder's receipt states.
     */
    @Test
    void finalizeTransactionFreezesAnEmptyLoyaltyZoneOnAbareSale() {
        state.payment.ticketDbId = 9L;
        when(fidelityService.isUnavailable()).thenReturn(false);
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        org.mockito.ArgumentCaptor<java.util.List<com.intermarche.pos.domain.sale.TicketFidelityLine>>
                frozen = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(ticketPersistenceService).storeFidelity(eq(9L), eq(null), eq(null),
                eq(false), frozen.capture(), eq(null), eq(null), eq(false));
        assertTrue(frozen.getValue().isEmpty());
    }

    /**
     * The FISCAL SEQUENCE of a card-bearing sale, in order: the lease is
     * confirmed FIRST (its id then travels), the closed ticket supplies the
     * reference, and the composed payload is enqueued. The outbox — not a
     * direct call — is what makes the declaration survive a dead imfid.
     */
    @Test
    void finalizeTransactionConfirmsTheLeaseThenEnqueuesTheClosedEvent() {
        state.payment.ticketDbId = 9L;
        state.fidelity.assignCard("2990000000019");
        com.intermarche.pos.domain.sale.Ticket closed =
                new com.intermarche.pos.domain.sale.Ticket();
        closed.ticketNumber = "C04-000001";
        when(fidelityService.confirmLease(eq(state), any())).thenReturn(77L);
        when(fidelityService.buildTicketClosedPayload(eq(state), any(), any(), any(), any()))
                .thenReturn("{\"ticketRef\":\"2026-C04-000001\"}");
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            panache.when(() -> com.intermarche.pos.domain.sale.Ticket.findById(9L))
                    .thenReturn(closed);
            service.finalizeTransaction(state);
        }
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(fidelityService, fidEventOutboxService);
        order.verify(fidelityService).confirmLease(eq(state), any());
        order.verify(fidelityService).buildTicketClosedPayload(
                eq(state), eq(java.time.LocalDate.now().getYear() + "-C04-000001"),
                eq("2990000000019"), any(), eq(77L));
        order.verify(fidEventOutboxService).enqueue(
                eq(com.intermarche.pos.domain.sync.FidEvent.EventType.TICKET_CLOSED),
                eq("{\"ticketRef\":\"2026-C04-000001\"}"));
    }

    /**
     * A sale paid WITHOUT the cagnotte still declares its earn: the lease id
     * is simply null and no burn is claimed.
     */
    @Test
    void finalizeTransactionEnqueuesTheEventWithoutALease() {
        state.payment.ticketDbId = 9L;
        state.fidelity.assignCard("2990000000019");
        com.intermarche.pos.domain.sale.Ticket closed =
                new com.intermarche.pos.domain.sale.Ticket();
        closed.ticketNumber = "C04-000002";
        when(fidelityService.confirmLease(eq(state), any())).thenReturn(null);
        when(fidelityService.buildTicketClosedPayload(eq(state), any(), any(), any(), any()))
                .thenReturn("{\"earn\":1}");
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            panache.when(() -> com.intermarche.pos.domain.sale.Ticket.findById(9L))
                    .thenReturn(closed);
            service.finalizeTransaction(state);
        }
        verify(fidelityService).buildTicketClosedPayload(
                eq(state), any(), eq("2990000000019"), any(), org.mockito.ArgumentMatchers.isNull());
        verify(fidEventOutboxService).enqueue(
                com.intermarche.pos.domain.sync.FidEvent.EventType.TICKET_CLOSED, "{\"earn\":1}");
    }

    /**
     * NO COUPLE, no event: without the verbatim valuation couple the payload
     * is null, and imfid would have nothing to recompute — the composer says
     * so and nothing is enqueued. The lease is still confirmed, because a
     * reserved balance must never stay locked.
     */
    @Test
    void finalizeTransactionEnqueuesNothingWhenThePayloadIsNull() {
        state.payment.ticketDbId = 9L;
        state.fidelity.assignCard("2990000000019");
        com.intermarche.pos.domain.sale.Ticket closed =
                new com.intermarche.pos.domain.sale.Ticket();
        closed.ticketNumber = "C04-000003";
        when(fidelityService.buildTicketClosedPayload(any(), any(), any(), any(), any()))
                .thenReturn(null);
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            panache.when(() -> com.intermarche.pos.domain.sale.Ticket.findById(9L))
                    .thenReturn(closed);
            service.finalizeTransaction(state);
        }
        verify(fidelityService).confirmLease(eq(state), any());
        verifyNoInteractions(fidEventOutboxService);
    }

    /**
     * A draft that VANISHED between the validation and the lookup enqueues
     * nothing rather than composing a reference from a null ticket: the
     * event would be unmatchable at ingestion.
     */
    @Test
    void finalizeTransactionEnqueuesNothingWhenTheClosedTicketIsGone() {
        state.payment.ticketDbId = 9L;
        state.fidelity.assignCard("2990000000019");
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            panache.when(() -> com.intermarche.pos.domain.sale.Ticket.findById(9L))
                    .thenReturn(null);
            service.finalizeTransaction(state);
        }
        verify(fidelityService).confirmLease(eq(state), any());
        verify(fidelityService, never()).buildTicketClosedPayload(any(), any(), any(), any(), any());
        verifyNoInteractions(fidEventOutboxService);
    }

    /**
     * Builds a registry instrument as issued by a sale.
     *
     * @param number the instrument number
     * @param amount the loaded face value
     * @return the instrument
     */
    private com.intermarche.pos.domain.payment.StoredValue issuedCard(String number, String amount) {
        com.intermarche.pos.domain.payment.StoredValue card = new com.intermarche.pos.domain.payment.StoredValue();
        card.number = number;
        card.initialAmount = new BigDecimal(amount);
        return card;
    }

    /**
     * Stubs the gift cards issued by the closed sale.
     *
     * @param panache the open Panache static mock
     * @param ticketId the closed draft id
     * @param issued the instruments to return
     */
    @SuppressWarnings("unchecked")
    private void stubIssuedGiftCards(
            org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache,
            long ticketId, java.util.List<com.intermarche.pos.domain.payment.StoredValue> issued) {
        io.quarkus.hibernate.orm.panache.PanacheQuery<com.intermarche.pos.domain.payment.StoredValue> query =
                mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(query.list()).thenReturn(issued);
        panache.when(() -> com.intermarche.pos.domain.payment.StoredValue
                .find("issuingTicketId", ticketId)).thenReturn(query);
    }

    /**
     * A sale that issued a gift card prints ITS voucher at the fiscal moment:
     * the customer leaves with the paper carrying the number and the loaded
     * value — the only thing that lets them use the instrument later, since
     * the register never writes the balance on the card itself.
     */
    @Test
    void finalizeTransactionPrintsTheIssuedGiftCardVoucher() {
        state.payment.ticketDbId = 9L;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubIssuedGiftCards(panache, 9L,
                    java.util.List.of(issuedCard("296000000000001", "25.00")));
            service.finalizeTransaction(state);
        }
        verify(ticketPrinterService).printGiftCardVoucher("296000000000001",
                new BigDecimal("25.00"));
    }

    /**
     * SEVERAL cards issued by the same sale each get their own voucher — a
     * customer buying two gift cards must leave with two papers, not one.
     */
    @Test
    void finalizeTransactionPrintsOneVoucherPerIssuedCard() {
        state.payment.ticketDbId = 9L;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubIssuedGiftCards(panache, 9L, java.util.List.of(
                    issuedCard("296000000000001", "25.00"),
                    issuedCard("296000000000002", "50.00")));
            service.finalizeTransaction(state);
        }
        verify(ticketPrinterService).printGiftCardVoucher("296000000000001",
                new BigDecimal("25.00"));
        verify(ticketPrinterService).printGiftCardVoucher("296000000000002",
                new BigDecimal("50.00"));
    }

    /**
     * A sale that issued NOTHING prints no voucher: the loop body is skipped,
     * and an ordinary sale must not produce a stray slip.
     */
    @Test
    void finalizeTransactionPrintsNoVoucherWhenNothingWasIssued() {
        state.payment.ticketDbId = 9L;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(ticketPrinterService, never()).printGiftCardVoucher(any(), any());
    }

    /**
     * Neutralizes the gift-card lookup of the fiscal moment (no card issued).
     *
     * @param panache the open Panache static mock
     * @param ticketId the closed draft id
     */
    @SuppressWarnings("unchecked")
    private void stubNoGiftCards(
            org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache,
            long ticketId) {
        io.quarkus.hibernate.orm.panache.PanacheQuery<com.intermarche.pos.domain.payment.StoredValue> empty =
                mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(empty.list()).thenReturn(java.util.List.of());
        panache.when(() -> com.intermarche.pos.domain.payment.StoredValue
                .find("issuingTicketId", ticketId)).thenReturn(empty);
    }

    /**
     * {@code finalizeTransaction} validates nothing when there is no draft but
     * still thanks the customer and clears the state ({@code ticketId != null}
     * false arm).
     */
    @Test
    void finalizeTransactionNoDraft() {
        state.payment.ticketDbId = null;
        service.finalizeTransaction(state);
        verify(ticketPersistenceService, never()).validateTicket(any());
        assertNull(state.lastClosedTicketId);
        verify(hardwareService).displayMessage("MERCI A BIENTOT");
    }

    // --------------------------------------------------
    // processBackupPayment
    // --------------------------------------------------

    /**
     * {@code processBackupPayment} rejects a null amount ({@code amount == null}
     * true arm of the first guard).
     */
    @Test
    void processBackupPaymentNullReturns() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processBackupPayment(state, null, "CB SECOURS", "TX1", false);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processBackupPayment} rejects a non-positive amount ({@code amount
     * == null} false, {@code amount.signum() <= 0} true).
     */
    @Test
    void processBackupPaymentZeroReturns() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processBackupPayment(state, BigDecimal.ZERO, "CB SECOURS", "TX1", false);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processBackupPayment} rejects a payment on a settled ticket: the
     * amount is positive but nothing is due ({@code amount == null} false,
     * {@code amount.signum() <= 0} false, {@code remaining.signum() <= 0} true).
     */
    @Test
    void processBackupPaymentNothingDueReturns() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processBackupPayment(state, new BigDecimal("5"), "CB SECOURS", "TX1", false);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processBackupPayment} caps the applied amount at the remaining due,
     * gives no change, persists the entry, completes the transaction and never
     * opens the drawer ({@code remaining.signum() <= 0} false, {@code manual}
     * carried, completion reached).
     */
    @Test
    void processBackupPaymentCapsCompletesNoDrawer() {
        state.ticket.totalAmount = new BigDecimal("15.00");
        state.payment.ticketDbId = 3L;
        service.processBackupPayment(state, new BigDecimal("20.00"), "CB SECOURS", "TX1", true);
        assertEquals(0, new BigDecimal("15.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("SECOURS  15,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
        verify(hardwareService, never()).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * {@code processBackupPayment} for a partial amount registers it and leaves
     * the transaction incomplete (completion not reached, the false arm of the
     * check).
     */
    @Test
    void processBackupPaymentPartialStaysIncomplete() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processBackupPayment(state, new BigDecimal("5.00"), "CB SECOURS", "TX2", false);
        assertEquals(0, new BigDecimal("5.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("SECOURS  5,00 E");
        assertFalse(state.payment.transactionComplete);
    }

    // --------------------------------------------------
    // processForeignCurrency
    // --------------------------------------------------

    /**
     * Builds a foreign currency with the given ISO code, display symbol (null to
     * fall back on the code) and administered euro-per-unit rate.
     *
     * @param code the ISO code
     * @param symbol the display symbol, or null to use the code
     * @param euroPerUnit how many euros one unit is worth
     * @return the currency
     */
    private com.intermarche.pos.domain.payment.Currency currency(String code, String symbol, String euroPerUnit) {
        com.intermarche.pos.domain.payment.Currency c = new com.intermarche.pos.domain.payment.Currency();
        c.code = code;
        c.symbol = symbol;
        c.euroPerUnit = new BigDecimal(euroPerUnit);
        return c;
    }

    /**
     * {@code processForeignCurrency} rejects a null currency ({@code currency ==
     * null} true arm of the first guard).
     */
    @Test
    void processForeignCurrencyNullCurrencyReturns() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processForeignCurrency(state, null, new BigDecimal("50"), new BigDecimal("47.50"));
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processForeignCurrency} rejects a null euro value ({@code currency
     * == null} false, {@code euroValue == null} true).
     */
    @Test
    void processForeignCurrencyNullEuroValueReturns() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processForeignCurrency(state, currency("CHF", "CHF", "1.05"),
                new BigDecimal("50"), null);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processForeignCurrency} rejects a non-positive euro value
     * ({@code currency == null} false, {@code euroValue == null} false,
     * {@code euroValue.signum() <= 0} true).
     */
    @Test
    void processForeignCurrencyZeroEuroValueReturns() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processForeignCurrency(state, currency("CHF", "CHF", "1.05"),
                new BigDecimal("0"), BigDecimal.ZERO);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processForeignCurrency} rejects a settlement on a ticket with
     * nothing due ({@code euroValue} positive so the first guard is fully false,
     * {@code remaining.signum() <= 0} true).
     */
    @Test
    void processForeignCurrencyNothingDueReturns() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processForeignCurrency(state, currency("CHF", "CHF", "1.05"),
                new BigDecimal("50"), new BigDecimal("10"));
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processForeignCurrency} for an exact euro value gives no change,
     * persists the entry, shows the three figures without a RENDU line, opens
     * the drawer and completes ({@code change.signum() > 0} false arm of both the
     * ternary and the display guard, drawer rule on).
     */
    @Test
    void processForeignCurrencyExactOpensDrawerCompletes() {
        state.ticket.totalAmount = new BigDecimal("47.50");
        state.payment.ticketDbId = 3L;
        service.processForeignCurrency(state, currency("CHF", "CHF", "1.05"),
                new BigDecimal("50"), new BigDecimal("47.50"));
        assertEquals(0, new BigDecimal("47.50").compareTo(state.payment.paidAmount));
        assertEquals(0, BigDecimal.ZERO.compareTo(state.payment.lastChangeAmount));
        verify(hardwareService).displayMessage("50,00 CHF = 47,50 E");
        verify(hardwareService, never()).displayMessage("RENDU 0,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
        verify(hardwareService).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * {@code processForeignCurrency} for foreign notes worth more than the due
     * caps at the remaining, gives euro change, shows the RENDU line and opens
     * the drawer ({@code change.signum() > 0} true arm of both the ternary and
     * the display guard).
     */
    @Test
    void processForeignCurrencyOverpaymentShowsChangeOpensDrawer() {
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        service.processForeignCurrency(state, currency("CHF", "CHF", "1.05"),
                new BigDecimal("50"), new BigDecimal("47.50"));
        assertEquals(0, new BigDecimal("40.00").compareTo(state.payment.paidAmount));
        assertEquals(0, new BigDecimal("7.50").compareTo(state.payment.lastChangeAmount));
        verify(hardwareService).displayMessage("50,00 CHF = 47,50 E");
        verify(hardwareService).displayMessage("RENDU 7,50 E");
        verify(hardwareService).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * {@code processForeignCurrency} in training registers the entry, shows the
     * figures using the ISO code when no symbol was given, but never opens the
     * drawer ({@code !trainingMode} false arm short-circuits the drawer pulse).
     */
    @Test
    void processForeignCurrencyTrainingNoDrawer() {
        state.trainingMode = true;
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processForeignCurrency(state, currency("USD", null, "0.90"),
                new BigDecimal("10"), new BigDecimal("9.00"));
        verify(hardwareService).displayMessage("10,00 USD = 9,00 E");
        verify(hardwareService, never()).openDrawer();
        assertFalse(state.payment.transactionComplete);
    }

    /**
     * BO-10-02-12: with the drawer-open-on-payment rule DISABLED, a foreign
     * currency settlement registers but the drawer stays shut ({@code
     * !trainingMode} true but {@code drawerOpenOnPayment()} false arm).
     */
    @Test
    void processForeignCurrencyDrawerRuleDisabledKeepsDrawerShut() {
        when(posSettingsService.drawerOpenOnPayment()).thenReturn(false);
        state.ticket.totalAmount = new BigDecimal("47.50");
        state.payment.ticketDbId = 3L;
        service.processForeignCurrency(state, currency("CHF", "CHF", "1.05"),
                new BigDecimal("50"), new BigDecimal("47.50"));
        verify(hardwareService).displayMessage("50,00 CHF = 47,50 E");
        verify(hardwareService, never()).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    // --------------------------------------------------
    // processCredit
    // --------------------------------------------------

    /**
     * Builds an account customer with the given account number and business
     * name (no contact, so {@code getDisplayName()} is the business name alone).
     *
     * @param number the account number
     * @param company the business name
     * @return the account customer
     */
    private com.intermarche.pos.domain.payment.AccountCustomer customer(String number, String company) {
        com.intermarche.pos.domain.payment.AccountCustomer c = new com.intermarche.pos.domain.payment.AccountCustomer();
        c.accountNumber = number;
        c.companyName = company;
        return c;
    }

    /**
     * {@code processCredit} rejects a null customer ({@code customer == null}
     * true arm of the first guard).
     */
    @Test
    void processCreditNullCustomerReturns() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processCredit(state, null, new BigDecimal("10"), false);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processCredit} rejects a null amount ({@code customer == null}
     * false, {@code amount == null} true).
     */
    @Test
    void processCreditNullAmountReturns() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processCredit(state, customer("C001", "ACME"), null, false);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processCredit} rejects a non-positive amount ({@code customer ==
     * null} false, {@code amount == null} false, {@code amount.signum() <= 0}
     * true).
     */
    @Test
    void processCreditZeroAmountReturns() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processCredit(state, customer("C001", "ACME"), BigDecimal.ZERO, false);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processCredit} rejects a charge on a settled ticket: the amount is
     * positive but nothing is due (first guard fully false, {@code
     * remaining.signum() <= 0} true).
     */
    @Test
    void processCreditNothingDueReturns() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processCredit(state, customer("C001", "ACME"), new BigDecimal("5"), false);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processCredit} caps the charge at the remaining due, persists the
     * entry, completes and never opens the drawer ({@code remaining.signum() <=
     * 0} false, {@code overLimit} carried, completion reached).
     */
    @Test
    void processCreditCapsCompletesNoDrawer() {
        state.ticket.totalAmount = new BigDecimal("15.00");
        state.payment.ticketDbId = 3L;
        service.processCredit(state, customer("C001", "ACME"), new BigDecimal("20.00"), true);
        assertEquals(0, new BigDecimal("15.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("CREDIT CLIENT  15,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
        verify(hardwareService, never()).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * {@code processCredit} for a partial charge registers it and leaves the
     * transaction incomplete (completion not reached, the false arm of the
     * check).
     */
    @Test
    void processCreditPartialStaysIncomplete() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCredit(state, customer("C002", "BETA"), new BigDecimal("5.00"), false);
        assertEquals(0, new BigDecimal("5.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("CREDIT CLIENT  5,00 E");
        assertFalse(state.payment.transactionComplete);
    }

    // --------------------------------------------------
    // finalizeTransaction — conditional printing (LC-08-03)
    // --------------------------------------------------

    /**
     * With conditional printing ENABLED and the choice not yet applied, the
     * closing prints the documents the decision names — here the sale ticket and
     * the card receipt — and records the choice as applied ({@code
     * isConditionalEnabled()} true AND {@code !printApplied} true, both {@code
     * decision.saleTicket()} and {@code decision.cardReceipt()} true). The
     * closing broom {@code payment.reset()} wipes {@code printApplied}, so the
     * documents printed — not the flag — are the durable proof the block ran.
     */
    @Test
    void finalizeTransactionConditionalEnabledPrintsNamedDocuments() {
        when(printPolicy.isConditionalEnabled()).thenReturn(true);
        state.payment.ticketDbId = 9L;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(ticketPersistenceService).validateTicket(9L);
        verify(ticketPrinterService).printTicket(9L);
        verify(ticketPrinterService).printCardReceipt(9L, false, null);
    }

    /**
     * With conditional printing ENABLED but the choice ALREADY applied, the
     * closing prints nothing on its own — a reloaded screen never produces a
     * second original ({@code isConditionalEnabled()} true AND {@code
     * !printApplied} false arm).
     */
    @Test
    void finalizeTransactionConditionalEnabledButAlreadyApplied() {
        when(printPolicy.isConditionalEnabled()).thenReturn(true);
        state.payment.ticketDbId = 9L;
        state.payment.printApplied = true;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(ticketPrinterService, never()).printTicket(any());
        verify(ticketPrinterService, never()).printCardReceipt(any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    /**
     * With conditional printing ENABLED and a decision naming NEITHER document,
     * the closing block is entered but prints nothing ({@code
     * decision.saleTicket()} false arm and {@code decision.cardReceipt()} false
     * arm, {@code decision.voucher()} false so the gift-card loop is skipped);
     * the ticket is still validated and the customer thanked.
     */
    @Test
    void finalizeTransactionConditionalEnabledDecisionNamesNothing() {
        when(printPolicy.isConditionalEnabled()).thenReturn(true);
        stubDecision(false, false, false);
        state.payment.ticketDbId = 9L;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            service.finalizeTransaction(state);
        }
        verify(ticketPrinterService, never()).printTicket(any());
        verify(ticketPrinterService, never()).printCardReceipt(any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
        verify(ticketPersistenceService).validateTicket(9L);
        verify(hardwareService).displayMessage("MERCI A BIENTOT");
    }

    // --------------------------------------------------
    // handlePaymentWithChange — defensive guard (reflection)
    // --------------------------------------------------

    /**
     * Invokes the private {@code handlePaymentWithChange} with a null tendered
     * amount to cover its defensive guard's {@code tendered == null} true arm,
     * unreachable through the public API (every caller pre-guards).
     *
     * @throws Exception if reflective invocation fails
     */
    @Test
    void handlePaymentWithChangeNullTenderedReturns() throws Exception {
        Method m = PaymentService.class.getDeclaredMethod(
                "handlePaymentWithChange", PosState.class, String.class, String.class, BigDecimal.class);
        m.setAccessible(true);
        m.invoke(service, state, "CASH", "ESPECES", null);
        verifyNoInteractions(hardwareService);
        verifyNoInteractions(ticketPersistenceService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * Invokes the private {@code handlePaymentWithChange} with a zero tendered
     * amount to cover its defensive guard's {@code tendered.signum() <= 0} true
     * arm (with {@code tendered == null} false), unreachable through the public
     * API.
     *
     * @throws Exception if reflective invocation fails
     */
    @Test
    void handlePaymentWithChangeZeroTenderedReturns() throws Exception {
        Method m = PaymentService.class.getDeclaredMethod(
                "handlePaymentWithChange", PosState.class, String.class, String.class, BigDecimal.class);
        m.setAccessible(true);
        m.invoke(service, state, "CASH", "ESPECES", BigDecimal.ZERO);
        verifyNoInteractions(hardwareService);
        verifyNoInteractions(ticketPersistenceService);
        assertTrue(state.payment.payments.isEmpty());
    }

    // --- Assiette TR locale, portée par les attributs article (BO-02-03-06) ---

    /**
     * Adds a line to the cart with the given total and eligibility.
     *
     * @param total the line total
     * @param eligible whether the article is meal-voucher eligible
     */
    private void cartLine(String total, boolean eligible) {
        com.intermarche.pos.ui.ticket.TicketState.TicketItem item =
                new com.intermarche.pos.ui.ticket.TicketState.TicketItem(
                        "EAN" + state.ticket.items.size(), null, "ART",
                        new java.math.BigDecimal(total), java.math.BigDecimal.ONE,
                        new java.math.BigDecimal("0.055"));
        item.mealVoucherEligible = eligible;
        state.ticket.items.add(item);
    }

    /**
     * WITHOUT an engine answer, the meal-ticket settlement is capped by what the
     * ELIGIBLE lines are worth: a 20 € ticket carrying 8 € of eligible articles
     * settles 8 €, not 20 €.
     */
    @Test
    void processTicketRestoIsCappedByTheEligibleLines() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "LOCAL";
        cartLine("8.00", true);
        cartLine("12.00", false);
        service.processTicketResto(state, new BigDecimal("20.00"));
        assertEquals(0, new BigDecimal("8.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("TR PLAFONNE  8,00 E");
    }

    /**
     * A SECOND eligible base caps at another figure, which is what proves the
     * cap is read from the lines and not hard-coded.
     */
    @Test
    void aSecondEligibleBaseCapsElsewhere() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "LOCAL";
        cartLine("5.50", true);
        cartLine("14.50", false);
        service.processTicketResto(state, new BigDecimal("20.00"));
        assertEquals(0, new BigDecimal("5.50").compareTo(state.payment.paidAmount));
    }

    /**
     * A request that FITS the eligible base is registered as it stands, without
     * a capping message (the {@code amount > allowed} false arm).
     */
    @Test
    void aRequestFittingTheEligibleBaseIsRegisteredAsIs() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "LOCAL";
        cartLine("8.00", true);
        cartLine("12.00", false);
        service.processTicketResto(state, new BigDecimal("3.00"));
        assertEquals(0, new BigDecimal("3.00").compareTo(state.payment.paidAmount));
        verify(hardwareService, never()).displayMessage("TR PLAFONNE  8,00 E");
    }

    /**
     * A cart carrying NO eligible line keeps the former behaviour — no cap at
     * all — because an attribute nobody declared cannot state a base. The
     * register is not made stricter by an empty referential.
     */
    @Test
    void aCartWithoutAnEligibleLineIsNotCapped() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "LOCAL";
        cartLine("20.00", false);
        service.processTicketResto(state, new BigDecimal("20.00"));
        assertEquals(0, new BigDecimal("20.00").compareTo(state.payment.paidAmount));
    }

    /**
     * The ENGINE still wins when it answered: it knows the offers, the
     * attributes do not. A 10 € engine base caps above the 4 € the lines
     * declare.
     */
    @Test
    void theEngineBaseWinsOverTheLines() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("10.00");
        cartLine("4.00", true);
        cartLine("16.00", false);
        service.processTicketResto(state, new BigDecimal("20.00"));
        assertEquals(0, new BigDecimal("10.00").compareTo(state.payment.paidAmount));
    }

    // --------------------------------------------------
    // Administered tender rules (BO-03-02-10 to -23)
    // --------------------------------------------------

    /**
     * A blocking rule leaves the sale EXACTLY as it was: nothing registered,
     * nothing persisted, nothing displayed, and the refusal on the screen
     * (BO-03-02-10/12/13/14).
     */
    @Test
    void aBlockingTenderRuleRegistersNothing() {
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.check(eq("CASH"), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new TenderRulesService.Verdict(
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.BLOCKING,
                        "MONTANT SUPERIEUR AU PLAFOND (25,00 E)"));
        service.processCash(state, new BigDecimal("40.00"));
        assertTrue(state.payment.payments.isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(state.payment.paidAmount));
        assertEquals("MONTANT SUPERIEUR AU PLAFOND (25,00 E)", state.ticket.transientError);
        verify(ticketPersistenceService, never()).addPaymentToTicket(any(Long.class), any());
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * A rule that only informs lets the settlement through and tells the cashier
     * afterwards — the other arm of the same guard.
     */
    @Test
    void anInformativeTenderRuleRegistersAndSpeaks() {
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.check(eq("CASH"), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new TenderRulesService.Verdict(
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.INFO,
                        "MONTANT SUPERIEUR AU PLAFOND (25,00 E)"));
        service.processCash(state, new BigDecimal("40.00"));
        assertEquals(1, state.payment.payments.size());
        assertEquals("MONTANT SUPERIEUR AU PLAFOND (25,00 E)", state.ticket.transientError);
    }

    /**
     * The count opposed to a settlement is the number of settlements of THAT
     * tender the sale already carries, not the number of settlements it carries
     * (BO-03-02-12).
     */
    @Test
    void theCountOpposedIsTheCountOfThatTender() {
        state.ticket.totalAmount = new BigDecimal("60.00");
        state.payment.ticketDbId = 3L;
        state.payment.addCashPayment(new BigDecimal("10.00"), new BigDecimal("10.00"));
        state.payment.addPayment("TR", new BigDecimal("10.00"));
        service.processCash(state, new BigDecimal("10.00"));
        verify(tenderRulesService).check(eq("CASH"), any(), eq(1));
    }

    /**
     * A tender the back office forbids change on settles what it settles and
     * gives nothing back (BO-03-02-16).
     */
    @Test
    void aTenderForbiddenChangeGivesNoneBack() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.changeAllowed(eq("CASH"), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(false);
        service.processCash(state, new BigDecimal("50.00"));
        assertEquals(0, BigDecimal.ZERO.compareTo(state.payment.lastChangeAmount));
        verify(hardwareService).displayMessage("ESPECES   20,00 E");
    }

    /**
     * A change ceiling that blocks refuses the settlement rather than capping
     * the change, the drawer staying shut (BO-03-02-11).
     */
    @Test
    void aBlockingChangeCeilingRefusesTheSettlement() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.checkChange(eq("CASH"), any()))
                .thenReturn(new TenderRulesService.Verdict(
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.BLOCKING,
                        "RENDU SUPERIEUR AU PLAFOND (8,00 E)"));
        service.processCash(state, new BigDecimal("50.00"));
        assertTrue(state.payment.payments.isEmpty());
        assertEquals("RENDU SUPERIEUR AU PLAFOND (8,00 E)", state.ticket.transientError);
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * The drawer follows the ADMINISTERED moment: a tender administered to open
     * only on change keeps it shut on an exact settlement and opens it when
     * change is owed (BO-03-02-19).
     */
    @Test
    void theDrawerFollowsTheAdministeredMoment() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.opensDrawer(eq("CASH"), eq(false), eq(false),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(false);
        service.processCash(state, new BigDecimal("20.00"));
        verify(hardwareService, never()).openDrawer();

        state.payment.reset();
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.opensDrawer(eq("CASH"), eq(true), eq(false),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        service.processCash(state, new BigDecimal("50.00"));
        verify(hardwareService).openDrawer();
    }

    /**
     * The moment is asked with the change the settlement actually produced, so a
     * tender administered on the change can tell the two cases apart.
     */
    @Test
    void theDrawerMomentIsAskedWithTheChangeThatWasGiven() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCash(state, new BigDecimal("50.00"));
        verify(tenderRulesService).opensDrawer(eq("CASH"), eq(true), eq(false),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    // --------------------------------------------------
    // Supervisor override of an administered bound (BO-03-02-10/12/13/14)
    // --------------------------------------------------

    /**
     * Stubs the bound check of one tender to the given verdict.
     *
     * @param methodKey the settlement key
     * @param level the control level the rule carries
     * @param message what the cashier is told
     */
    private void ruleOn(String methodKey,
            com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel level,
            String message) {
        when(tenderRulesService.check(eq(methodKey), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new TenderRulesService.Verdict(level, message));
    }

    /**
     * A rule at the supervisor level HOLDS the settlement instead of losing it:
     * nothing is registered, and the sale carries what the supervisor must
     * decide on — the tender, its label, the amount and the rule broken.
     */
    @Test
    void aSupervisorRuleHoldsTheSettlement() {
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        ruleOn("CASH", com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                "MONTANT SUPERIEUR AU PLAFOND (25,00 E)");
        service.processCash(state, new BigDecimal("40.00"));
        assertTrue(state.payment.payments.isEmpty());
        assertTrue(state.payment.isTenderAuthorizationPending());
        assertEquals("CASH", state.payment.tenderHeldMethod);
        assertEquals("ESPECES", state.payment.tenderHeldLabel);
        assertEquals(0, new BigDecimal("40.00").compareTo(state.payment.tenderHeldAmount));
        assertEquals("MONTANT SUPERIEUR AU PLAFOND (25,00 E)", state.payment.tenderHeldMessage);
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * A rule at the blocking level is never held: nobody passes it, so no
     * authorization is asked for — the other arm of the same guard.
     */
    @Test
    void aBlockingRuleIsNeverHeld() {
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        ruleOn("CASH", com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.BLOCKING,
                "MONTANT SUPERIEUR AU PLAFOND (25,00 E)");
        service.processCash(state, new BigDecimal("40.00"));
        assertFalse(state.payment.isTenderAuthorizationPending());
        assertEquals("MONTANT SUPERIEUR AU PLAFOND (25,00 E)", state.ticket.transientError);
    }

    /**
     * A supervisor authorizing the hold registers the settlement THAT WAS HELD,
     * opens the drawer for it and clears the hold.
     */
    @Test
    void anAuthorizedHoldRegistersTheHeldSettlement() {
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        ruleOn("CASH", com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                "MONTANT SUPERIEUR AU PLAFOND (25,00 E)");
        service.processCash(state, new BigDecimal("40.00"));
        when(endorsementService.authorize(eq("chef"), eq("secret"), any())).thenReturn(true);

        assertTrue(service.authorizeHeldTender(state, "chef", "secret"));
        assertEquals(1, state.payment.payments.size());
        assertEquals(0, new BigDecimal("40.00").compareTo(state.payment.paidAmount));
        assertFalse(state.payment.isTenderAuthorizationPending());
        assertNull(state.payment.tenderOverride);
        verify(hardwareService).openDrawer();
    }

    /**
     * A logged operator who IS a supervisor authorizes without typing anything —
     * the other leg of the credential disjunction.
     */
    @Test
    void aSupervisorAtTheTillAuthorizesWithoutCredentials() {
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        ruleOn("CASH", com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                "PLAFOND");
        service.processCash(state, new BigDecimal("40.00"));
        when(endorsementService.operatorIsSupervisor(state)).thenReturn(true);

        assertTrue(service.authorizeHeldTender(state, null, null));
        assertEquals(1, state.payment.payments.size());
    }

    /**
     * A refused credential registers nothing, keeps the hold and says so.
     */
    @Test
    void aRefusedCredentialKeepsTheHold() {
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        ruleOn("CASH", com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                "PLAFOND");
        service.processCash(state, new BigDecimal("40.00"));

        assertFalse(service.authorizeHeldTender(state, "quidam", "faux"));
        assertTrue(state.payment.payments.isEmpty());
        assertTrue(state.payment.isTenderAuthorizationPending());
        assertEquals("AUTORISATION REFUSEE", state.payment.tenderHeldMessage);
    }

    /**
     * Authorizing when nothing is held does nothing at all, and never asks for a
     * credential — the two legs of the guard.
     */
    @Test
    void authorizingNothingDoesNothing() {
        assertFalse(service.authorizeHeldTender(state, "chef", "secret"));
        state.payment.tenderHeldMethod = "CASH";
        assertFalse(service.authorizeHeldTender(state, "chef", "secret"));
        verifyNoInteractions(endorsementService);
    }

    /**
     * An authorization covers ONE tender: a hold released on cash does not let a
     * meal voucher past its own supervisor rule.
     */
    @Test
    void anAuthorizationCoversOnlyItsOwnTender() {
        state.ticket.totalAmount = new BigDecimal("60.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "LOCAL";
        state.payment.tenderOverride = "CASH";
        ruleOn("TR", com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                "PLAFOND TR");
        service.processTicketResto(state, new BigDecimal("30.00"));
        assertTrue(state.payment.payments.isEmpty());
        assertEquals("TR", state.payment.tenderHeldMethod);
    }

    /**
     * Abandoning the hold forgets the settlement and the authorization alike.
     */
    @Test
    void abandoningTheHoldForgetsEverything() {
        state.payment.tenderHeldMethod = "CASH";
        state.payment.tenderHeldAmount = new BigDecimal("40.00");
        state.payment.tenderOverride = "CASH";
        service.cancelHeldTender(state);
        assertFalse(state.payment.isTenderAuthorizationPending());
        assertNull(state.payment.tenderOverride);
        assertNull(state.payment.tenderHeldAmount);
    }

    // --------------------------------------------------
    // Change given as a credit note (BO-03-02-16)
    // --------------------------------------------------

    /**
     * A tender administered to give its change back in vouchers books the change
     * instead of handing cash over, and says so on the customer display.
     */
    @Test
    void changeAdministeredAsAVoucherIsBookedNotHandedOver() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.changeTender("CASH")).thenReturn("VOUCHER");
        service.processCash(state, new BigDecimal("50.00"));
        assertEquals(0, new BigDecimal("30.00").compareTo(state.payment.changeAsCreditNote));
        assertEquals(0, BigDecimal.ZERO.compareTo(state.payment.lastChangeAmount));
        verify(hardwareService).displayMessage("RENDU EN AVOIR 30,00 E");
    }

    /**
     * A tender whose change stays in its own tender books nothing — the other
     * arm of the same guard.
     */
    @Test
    void changeInTheTenderItselfBooksNoNote() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.changeTender("CASH")).thenReturn("CASH");
        service.processCash(state, new BigDecimal("50.00"));
        assertEquals(0, BigDecimal.ZERO.compareTo(state.payment.changeAsCreditNote));
        assertEquals(0, new BigDecimal("30.00").compareTo(state.payment.lastChangeAmount));
    }

    /**
     * An exact settlement books no note whatever the administered change tender
     * — the amount leg of the same guard.
     */
    @Test
    void anExactSettlementBooksNoNote() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.changeTender("CASH")).thenReturn("VOUCHER");
        service.processCash(state, new BigDecimal("20.00"));
        assertEquals(0, BigDecimal.ZERO.compareTo(state.payment.changeAsCreditNote));
    }

    /**
     * The booked change becomes a NUMBERED credit note at the fiscal moment, is
     * printed with its number, and stops being owed.
     */
    @Test
    void theBookedChangeBecomesACreditNoteAtTheFiscalMoment() {
        state.payment.ticketDbId = 9L;
        state.payment.changeAsCreditNote = new BigDecimal("30.00");
        when(ticketPersistenceService.issueChangeCreditNote(eq(9L), any()))
                .thenReturn("297000000000042");
        when(printPolicy.isAssociatedDocumentPrinted(
                com.intermarche.pos.ui.hardware.PrintPolicy.DOCUMENT_CREDIT_NOTE))
                .thenReturn(true);
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(ticketPersistenceService).issueChangeCreditNote(9L, new BigDecimal("30.00"));
        verify(ticketPrinterService).printChangeVoucher("297000000000042", new BigDecimal("30.00"));
    }

    /**
     * A sale owing no change as a note issues none, and a registry that answers
     * no number prints nothing — the two legs of the issuance guard.
     */
    @Test
    void aSaleOwingNoNoteIssuesNone() {
        state.payment.ticketDbId = 9L;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(ticketPersistenceService, never()).issueChangeCreditNote(any(), any());

        state.payment.ticketDbId = 11L;
        state.payment.changeAsCreditNote = new BigDecimal("5.00");
        when(ticketPersistenceService.issueChangeCreditNote(eq(11L), any())).thenReturn(null);
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 11L);
            service.finalizeTransaction(state);
        }
        verify(ticketPrinterService, never()).printChangeVoucher(any(), any());
    }

    // --------------------------------------------------
    // Enrichment: remaining branches
    // --------------------------------------------------

    /**
     * A change verdict that only INFORMS is shown AFTER the settlement is
     * registered when the tender verdict itself stayed silent
     * ({@code verdict.speaks()} false arm, {@code changeVerdict.speaks()} true
     * arm of the closing message guard).
     */
    @Test
    void anInformativeChangeVerdictSpeaksAfterSettlement() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.checkChange(eq("CASH"), any()))
                .thenReturn(new TenderRulesService.Verdict(
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.INFO,
                        "RENDU INHABITUEL"));
        service.processCash(state, new BigDecimal("20.00"));
        assertEquals(1, state.payment.payments.size());
        assertEquals("RENDU INHABITUEL", state.ticket.transientError);
    }

    /**
     * A closing whose {@code changeAsCreditNote} was cleared to null issues no
     * credit note: the {@code owed == null} true arm of the issuance guard,
     * unreachable through the money path (the field is initialized to zero and
     * only ever added to) but defended here.
     */
    @Test
    void finalizeTransactionWithNullChangeAsCreditNoteIssuesNone() {
        state.payment.ticketDbId = 9L;
        state.payment.changeAsCreditNote = null;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(ticketPersistenceService, never()).issueChangeCreditNote(any(), any());
    }

    /**
     * Invokes the private {@code handlePaymentWithChange} with a null method key:
     * {@code registeredCount} short-circuits on the null key ({@code methodKey ==
     * null} true arm) and, when the rule holds for a supervisor,
     * {@code overrideCovers} short-circuits on the null key ({@code methodKey !=
     * null} false arm). Both are unreachable through the public API, where every
     * caller passes a literal key.
     *
     * @throws Exception if reflective invocation fails
     */
    @Test
    void handlePaymentWithChangeNullMethodKeyCountsZeroAndHolds() throws Exception {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.check(org.mockito.ArgumentMatchers.isNull(), any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new TenderRulesService.Verdict(
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                        "PLAFOND"));
        Method m = PaymentService.class.getDeclaredMethod(
                "handlePaymentWithChange", PosState.class, String.class, String.class, BigDecimal.class);
        m.setAccessible(true);
        Object result = m.invoke(service, state, null, "X", new BigDecimal("20.00"));
        assertEquals(Boolean.FALSE, result);
        assertTrue(state.payment.payments.isEmpty());
        assertEquals(0, new BigDecimal("20.00").compareTo(state.payment.tenderHeldAmount));
    }

    /**
     * An authorized hold whose replay is REFUSED by a blocking change ceiling
     * registers nothing and opens no drawer ({@code registered} false arm of the
     * post-authorization drawer guard).
     */
    @Test
    void anAuthorizedHoldRefusedOnReplayOpensNoDrawer() {
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        ruleOn("CASH", com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                "PLAFOND");
        service.processCash(state, new BigDecimal("40.00"));
        when(endorsementService.authorize(any(), any(), any())).thenReturn(true);
        when(tenderRulesService.checkChange(eq("CASH"), any()))
                .thenReturn(new TenderRulesService.Verdict(
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.BLOCKING,
                        "RENDU SUPERIEUR AU PLAFOND"));
        assertFalse(service.authorizeHeldTender(state, "chef", "secret"));
        assertTrue(state.payment.payments.isEmpty());
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * An authorized hold in TRAINING registers the held settlement but opens no
     * drawer ({@code !state.trainingMode} false arm of the post-authorization
     * drawer guard).
     */
    @Test
    void anAuthorizedHoldInTrainingOpensNoDrawer() {
        state.trainingMode = true;
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        ruleOn("CASH", com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                "PLAFOND");
        service.processCash(state, new BigDecimal("40.00"));
        when(endorsementService.authorize(any(), any(), any())).thenReturn(true);
        assertTrue(service.authorizeHeldTender(state, "chef", "secret"));
        assertEquals(1, state.payment.payments.size());
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * An authorized hold registered while the drawer-open-on-payment rule is
     * DISABLED opens no drawer ({@code opensDrawerFor} false arm of the
     * post-authorization drawer guard).
     */
    @Test
    void anAuthorizedHoldWithDrawerRuleDisabledOpensNoDrawer() {
        when(posSettingsService.drawerOpenOnPayment()).thenReturn(false);
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        ruleOn("CASH", com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                "PLAFOND");
        service.processCash(state, new BigDecimal("40.00"));
        when(endorsementService.authorize(any(), any(), any())).thenReturn(true);
        assertTrue(service.authorizeHeldTender(state, "chef", "secret"));
        assertEquals(1, state.payment.payments.size());
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * A cheque settlement REFUSED by a blocking tender rule registers nothing and
     * opens no drawer: {@code registerCheque} returns on the false arm of its
     * {@code handlePaymentWithChange} guard. Exercised in training, where the
     * cheque is registered directly without a reading.
     */
    @Test
    void processChequeBlockedByRuleRegistersNothing() {
        state.trainingMode = true;
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.check(eq("CHEQUE"), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new TenderRulesService.Verdict(
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.BLOCKING,
                        "PLAFOND CHEQUE"));
        service.processCheque(state, new BigDecimal("20.00"));
        service.settlePendingChequeRead(state);
        assertTrue(state.payment.payments.isEmpty());
        assertEquals("PLAFOND CHEQUE", state.ticket.transientError);
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * Invokes the private {@code opensDrawerFor} on a fresh state whose
     * {@code lastChangeAmount} is still null: the {@code lastChangeAmount != null}
     * false arm of the change-due computation, unreachable through the public API
     * (every settlement sets the field before the drawer is consulted).
     *
     * @throws Exception if reflective invocation fails
     */
    @Test
    void opensDrawerForWithNullLastChangeAmount() throws Exception {
        state.payment.lastChangeAmount = null;
        Method m = PaymentService.class.getDeclaredMethod(
                "opensDrawerFor", PosState.class, String.class);
        m.setAccessible(true);
        Object result = m.invoke(service, state, "CASH");
        assertEquals(Boolean.TRUE, result);
        verify(tenderRulesService).opensDrawer("CASH", false, false, true);
    }

    /**
     * {@code creditTender} keeps an administered key the register KNOWS: a
     * customer-credit settlement is then registered under that key
     * ({@code forKey(administered) == null} false arm, versus the true arm the
     * default tests exercise where the unstubbed key resolves to null).
     */
    @Test
    void processCreditUsesTheKnownAdministeredTenderKey() {
        when(posSettingsService.customerCreditTender()).thenReturn("CHEQUE");
        state.ticket.totalAmount = new BigDecimal("15.00");
        state.payment.ticketDbId = 3L;
        service.processCredit(state, customer("C003", "GAMMA"), new BigDecimal("15.00"), false);
        assertEquals(1, state.payment.payments.size());
        assertEquals("CHEQUE", state.payment.payments.get(0).method);
        assertTrue(state.payment.transactionComplete);
    }

    // --------------------------------------------------
    // processNetRefund (EXP-38 signed settlement direction)
    // --------------------------------------------------

    /**
     * {@code processNetRefund} does nothing when money is still due (a positive
     * remaining): the ordinary tender methods collect it, so the refund direction
     * refuses and leaves the sale untouched (remaining.signum() &gt;= 0 arm).
     */
    @Test
    void processNetRefundReturnsFalseWhenNothingOwed() {
        state.ticket.totalAmount = new BigDecimal("10.00");
        state.payment.ticketDbId = 3L;
        boolean settled = service.processNetRefund(state, "CASH");
        assertFalse(settled);
        assertTrue(state.payment.payments.isEmpty());
        assertFalse(state.payment.transactionComplete);
    }

    /**
     * {@code processNetRefund} on a mixed ticket whose signed total is negative
     * pays the difference back: it records the negative settlement, drives the
     * display, brings the remaining to zero and completes the transaction
     * (remaining.signum() &lt; 0 arm; negative-total completion == 0 arm).
     */
    @Test
    void processNetRefundPaysBackNegativeTotal() {
        state.ticket.totalAmount = new BigDecimal("-5.00");
        state.payment.ticketDbId = 3L;
        boolean settled = service.processNetRefund(state, "CASH");
        assertTrue(settled);
        assertEquals(1, state.payment.payments.size());
        assertEquals(0, state.payment.paidAmount.compareTo(new BigDecimal("-5.00")));
        assertEquals(0, state.getRemaining().compareTo(BigDecimal.ZERO));
        assertTrue(state.payment.transactionComplete);
        verify(hardwareService).displayMessage("REMBOURSEMENT 5,00 E");
    }
}
