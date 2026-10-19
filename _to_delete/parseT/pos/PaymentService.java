package com.intermarche.pos.ui.payment;

import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.valuation.ValuationReconciler;
import com.intermarche.pos.ui.valuation.ValuationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Payment orchestration: draft ticket creation, registration of the various
 * payment methods, hardware display and transaction finalization.
 * <p>
 * All monetary amounts are {@link BigDecimal} (phase 0). Completion is reached
 * when the remaining due, rounded to 2 decimals, is zero or below — this
 * replaces the previous double epsilon comparison.
 * <p>
 * One money path for everything: every method funnels into
 * {@code handlePaymentWithChange} (cap at the remaining due where the
 * method demands it, change on overpayment for cash-like methods,
 * persistence of the entry on the draft, completion check) — a new payment
 * method is a factory subclass plus a thin wrapper here, never new money
 * math. Per-method drawer rules are deliberate: cash, cheque and meal
 * vouchers open it (something physical goes in), card and loyalty never do,
 * and training keeps it shut everywhere. {@code processCard} parks the
 * amount and hands the transaction to the {@code PaymentTerminalClient}
 * port: the CardPayment entity only exists after the terminal's accept
 * decision, whatever the implementation behind the port.
 */
@ApplicationScoped
public class PaymentService {

    private static final Logger LOGGER = Logger.getLogger(PaymentService.class);

    /**
     * The localised operator messages: every sentence this class stamps on the
     * register's message band is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /**
     * The payment terminal port (virtual simulator, auto-accept or Verifone
     * skeleton — selected by {@code pos.tpe.mode}); the card path only ever
     * talks to this interface.
     */
    @Inject
    com.intermarche.pos.ui.hardware.terminal.PaymentTerminalClient terminal;

    @Inject
    HardwareService hardwareService;

    /** The cheque reader, reached through the hardware bridge. */
    @Inject
    com.intermarche.pos.ui.hardware.ChequeReadingService chequeReadingService;

    /** The back-office parameters (drawer-open rules — BO-10-02-12). */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /**
     * The register's own identity, for the cheque endorsement tokens
     * (BO-03-02-43): a cheque back saying WHICH till took it is what a bank
     * return is traced with.
     */
    @Inject
    com.intermarche.pos.service.TicketNumberService ticketNumberService;

    /** The point-of-sale number, for the cheque endorsement tokens (BO-03-02-43). */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "pos.pdv.number",
            defaultValue = "")
    String pdvNumber;

    /** The administered tender rules — ceilings, change, drawer (BO-03-02). */
    @Inject
    TenderRulesService tenderRulesService;

    /** The supplier gift-card issuer registry seam (EXP-66). */
    @Inject
    GiftCardIssuerClient giftCardIssuerClient;

    /** The Resto Flash mobile meal-voucher seam (EXP-67). */
    @Inject
    RestoFlashClient restoFlashClient;

    /** The SOGEC manufacturer-coupon compensation circuit (EXP-58). */
    @Inject
    SogecCompensationService sogecCompensationService;

    /** Checks the supervisor credential and journals the decision (BO-03-02-10). */
    @Inject
    com.intermarche.pos.ui.endorsement.EndorsementService endorsementService;

    /** The action code journalled when a supervisor passes an administered bound. */
    private static final String TENDER_OVERRIDE_ACTION = "DEPASSEMENT REGLE MODE DE REGLEMENT";

    /**
     * The settlement key whose change is handed over as a credit note
     * (BO-03-02-16) — the register's voucher tender, the one the stored-value
     * registry already numbers and redeems.
     */
    private static final String CREDIT_NOTE_TENDER = "VOUCHER";

    @Inject
    TicketPersistenceService ticketPersistenceService;

    /**
     * Derives and enqueues the campaign consumption of a finalized sale
     * (campaigns &amp; budgets spec §3).
     */
    @Inject
    com.intermarche.pos.service.sync.ConsumptionService consumptionService;

    /**
     * Prints the gift-card vouchers issued by a closed sale (phase: credit
     * notes & gift cards).
     */
    @Inject
    com.intermarche.pos.ui.hardware.TicketPrinterService ticketPrinterService;

    /** The conditional-printing rule (LC-08-03). */
    @Inject
    com.intermarche.pos.ui.hardware.PrintPolicy printPolicy;

    /** Emits the document the settlement calls for, at the closing (LC-08-04-16). */
    @Inject
    com.intermarche.pos.ui.invoice.InvoiceService invoiceService;

    /**
     * Technical EAN of the solidarity-rounding line (parameterized).
     * <p>
     * A register-generated line is still a line: every line carries an EAN the
     * valuation engine can resolve, because the engine prices the WHOLE ticket
     * and a line without an EAN does not exist.
     */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "pos.ean.donation")
    String donationEan;

    /** Loyalty lease lifecycle and event composition (imfid lots 2-3). */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.fidelity.FidelityService fidelityService;

    /** Loyalty fiscal-event outbox (imfid lot 3). */
    @jakarta.inject.Inject
    com.intermarche.pos.service.sync.register.FidEventOutboxService fidEventOutboxService;

    @Inject
    ValuationService valuationService;

    @Inject
    ValuationReconciler valuationReconciler;

    /** French display format for amounts on the customer display. */
    private final DecimalFormat df = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.FRENCH));

    /** How the endorsement dates a cheque. */
    private static final java.time.format.DateTimeFormatter ENDORSEMENT_DATE =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    // --------------------------------------------------
    // 1. INITIALIZATION
    // --------------------------------------------------

    /**
     * Initializes the payment screen: shows the total on the customer display
     * and runs a final draft synchronization. The draft normally exists since
     * the first article (lot 2); this sync picks up any late change (fidelity
     * card scanned after the last article) and recreates the draft defensively
     * if it is missing.
     *
     * @param state the current POS state
     */
    public void initPayment(PosState state) {
        LOGGER.info("Entering method initPayment with state: " + state);
        // Single-writer discipline: draft-writing gestures serialize on the
        // shared state — the pay screen reloads itself on every version bump
        // and re-enters initPayment (draft sync + revaluation) concurrently
        // with a payment registration or the terminal callback, which made
        // two transactions race on the same draft row (optimistic-lock).
        synchronized (state) {
            hardwareService.displayMessage(String.format(i18n.get("display.total"), df.format(state.ticket.totalAmount)));
            state.payment.paymentInProgress = true;
            // LC-07-03-01: the rounding step is read once, at payment entry, and
            // held on the state — the screen, the customer display and the printer
            // all ask for the rounded amount many times per second.
            state.cashRoundingStepCents = posSettingsService.cashRoundingStepCents();

            Long ticketId = ticketPersistenceService.syncDraft(state);
            if (ticketId == null && !state.trainingMode) {
                LOGGER.error("Impossible de créer/synchroniser le ticket (panier vide ou Store/Cashier manquant)");
            }

            // Phase 7 lot 4: final revaluation at payment entry — the cart was
            // already revalued after each mutation, this fixes the figure the
            // customer pays (fresh call, circuit permitting) and refreshes the
            // meal-voucher base and upsell hints.
            if (!state.trainingMode) {
                valuationService.revalueForPayment(state);
                hardwareService.displayMessage(String.format(i18n.get("display.total"), df.format(state.ticket.totalAmount)));
            } else {
                state.payment.valuationStatus = "LOCAL";
            }
        }
        LOGGER.info("Exiting method initPayment");
    }

    /**
     * Toggles the solidarity round-up: adds a zero-VAT donation line raising
     * the ticket total to the next whole euro, or removes it when already
     * present. Ignored on a completed transaction or an already-whole total.
     *
     * @param state the current POS state
     */
    public void toggleDonationRoundup(PosState state) {
        LOGGER.info("Entering method toggleDonationRoundup with state: " + state);
        if (state.payment.transactionComplete) { LOGGER.info("Exiting method toggleDonationRoundup"); return; }

        if (state.donationLineUid != null) {
            state.ticket.removeItemById(state.donationLineUid);
            state.donationLineUid = null;
        } else {
            BigDecimal total = state.ticket.totalAmount;
            BigDecimal roundedUp = total.setScale(0, RoundingMode.CEILING);
            BigDecimal difference = roundedUp.subtract(total);
            if (difference.signum() <= 0) { LOGGER.info("Exiting method toggleDonationRoundup"); return; }
            // Collected on behalf of the charity: out of VAT scope
            // Technical EAN, parameterized: a register-generated line is still
            // a line, and every line carries an EAN the valuation engine can
            // resolve (a line without one does not exist).
            state.ticket.addItem(donationEan, null, "ARRONDI SOLIDAIRE",
                    difference, BigDecimal.ONE, BigDecimal.ZERO);
            state.donationLineUid = state.lastEnteredItemId;
        }
        ticketPersistenceService.syncDraft(state);
        hardwareService.displayMessage(String.format(i18n.get("display.total"), df.format(state.ticket.totalAmount)));
        state.touch();
        LOGGER.info("Exiting method toggleDonationRoundup");
    }

    /**
     * Cancels a SINGLE registered payment without purging the others (EXP-63):
     * the entry leaves the in-memory list and the draft is re-persisted with the
     * remaining payments, so a restart recovery cannot resurrect the cancelled
     * one. Unlike {@link #cancelPayments}, the payment stays in progress and the
     * fidelity lease and valuation are untouched — only one line is undone.
     *
     * @param state the current POS state
     * @param index the 0-based index of the payment to cancel
     * @return true when the payment was cancelled, false on an out-of-range index
     */
    public boolean cancelOnePayment(PosState state, int index) {
        LOGGER.info("Entering method cancelOnePayment with index: " + index);
        synchronized (state) {
            PaymentState.PaymentEntry cancelled = index >= 0
                    && index < state.payment.payments.size()
                    ? state.payment.payments.get(index) : null;
            if (!state.payment.removePayment(index)) {
                LOGGER.info("Exiting method cancelOnePayment");
                return false;
            }
            Long ticketId = state.payment.ticketDbId;
            // BO-03-02-35: the slip of the one settlement undone, printed once
            // the removal succeeded — a refused index prints nothing.
            printCancellationSlip(ticketId, cancelled);
            if (ticketId != null) {
                // Re-persist the remaining payments through the same funnel used
                // to add them: clear the persisted set, replay what survives.
                ticketPersistenceService.removePaymentsFromTicket(ticketId);
                for (PaymentState.PaymentEntry entry : state.payment.payments) {
                    ticketPersistenceService.addPaymentToTicket(ticketId, entry);
                }
            }
            state.touch();
            LOGGER.info("Exiting method cancelOnePayment");
            return true;
        }
    }

    /**
     * Prints the slip of a settlement paid by gift card (BO-03-03-11).
     *
     * <p>The REMAINING balance is what the issuer answered minus what was just
     * settled: the register knows both and the customer is owed the
     * subtraction. A balance the issuer did not state leaves the line out
     * rather than printing a zero nobody verified.
     *
     * <p>A printer failure never fails the settlement: the money moved, the
     * paper is its witness.
     *
     * @param state the current POS state
     * @param cardNumber the gift-card number
     * @param amount the amount just settled
     * @param balanceBefore the balance the issuer answered, or null
     */
    private void printGiftCardPaymentSlip(PosState state, String cardNumber,
                                          BigDecimal amount, BigDecimal balanceBefore) {
        if (ticketPrinterService == null) {
            return;
        }
        try {
            BigDecimal remaining = balanceBefore == null
                    ? null : balanceBefore.subtract(amount).max(BigDecimal.ZERO);
            String ticketNumber = null;
            if (state.payment.ticketDbId != null) {
                com.intermarche.pos.domain.sale.Ticket ticket =
                        com.intermarche.pos.domain.sale.Ticket.findById(state.payment.ticketDbId);
                if (ticket != null) {
                    ticketNumber = ticket.ticketNumber;
                }
            }
            ticketPrinterService.printGiftCardPaymentReceipt(ticketNumber, cardNumber,
                    amount, remaining);
        } catch (Exception e) {
            LOGGER.warnf("Justificatif carte cadeau non imprime: %s", e.getMessage());
        }
    }

    /**
     * Prints the slip of one cancelled settlement (BO-03-02-35).
     *
     * <p>The tender's ADMINISTERED wording is used when the referential carries
     * one, and the settlement key otherwise: a slip naming "CHEQUE" to a
     * customer who was handed back a cheque is the point, and a store that has
     * not filled the referential in still gets a readable paper.
     *
     * <p>A printer failure never fails the cancellation: giving the cheque back
     * is the gesture, the paper is its witness. The register logs and goes on.
     *
     * @param ticketId the draft the settlement was on, or null
     * @param entry the cancelled settlement, or null when there was none
     */
    private void printCancellationSlip(Long ticketId, PaymentState.PaymentEntry entry) {
        if (entry == null || ticketPrinterService == null) {
            return;
        }
        try {
            String label = entry.method;
            com.intermarche.pos.domain.payment.TenderDefinition tender =
                    com.intermarche.pos.domain.payment.TenderDefinition.findByCode(entry.method);
            if (tender != null && tender.label != null && !tender.label.isBlank()) {
                label = tender.label;
            }
            String ticketNumber = null;
            if (ticketId != null) {
                com.intermarche.pos.domain.sale.Ticket ticket =
                        com.intermarche.pos.domain.sale.Ticket.findById(ticketId);
                if (ticket != null) {
                    ticketNumber = ticket.ticketNumber;
                }
            }
            ticketPrinterService.printPaymentCancellation(ticketNumber, label, entry.amount);
        } catch (Exception e) {
            LOGGER.warnf("Ticket d'annulation de reglement non imprime: %s", e.getMessage());
        }
    }

    /**
     * Cancels the registered payments coherently: clears the in-memory list
     * and removes the persisted payments from the draft, so a later restart
     * recovery cannot resurrect them. Each cancelled settlement leaves its own
     * slip (BO-03-02-35).
     *
     * @param state the current POS state
     */
    public void cancelPayments(PosState state) {
        LOGGER.info("Entering method cancelPayments with state: " + state);
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            Long ticketId = state.payment.ticketDbId;
            // The fidelity lease dies with the payments (imfid spec §5.3);
            // failure-tolerant — the TTL is the safety net.
            fidelityService.releaseLease(state);
            state.payment.paymentInProgress = false;
            state.payment.pendingCardAmount = null;
            // Phase 7: leaving the payment reverts the valuation — the cart goes
            // back to local totals and will be revalued at the next entry
            if (state.payment.valuationAdjustment != null || "ENGINE".equals(state.payment.valuationStatus)) {
                valuationReconciler.revert(state.ticket, ticketId);
                state.ticket.recomputeTotal();
            }
            state.payment.valuationStatus = null;
            state.payment.valuationJson = null;
            state.payment.valuationEngineTotal = null;
            state.payment.valuationAdjustment = null;
            state.payment.valuationMealEligible = null;
            state.payment.valuationMealThreshold = null;
            state.payment.valuationUpsells = new java.util.ArrayList<>();
            // BO-03-02-35: a settlement undone is an event. One slip per
            // settlement the customer actually handed over, printed BEFORE the
            // list is cleared — afterwards there is nothing left to name.
            for (PaymentState.PaymentEntry entry : state.payment.payments) {
                printCancellationSlip(ticketId, entry);
            }
            state.clearPayments();
            // Back to the cart: revalue immediately so the screen shows engine
            // totals again without waiting for the next mutation
            valuationService.revalue(state);
            if (ticketId != null) {
                ticketPersistenceService.removePaymentsFromTicket(ticketId);
            }
        }
        LOGGER.info("Exiting method cancelPayments");
    }

    // --------------------------------------------------
    // 2. COMMON PRIVATE METHOD
    // --------------------------------------------------

    /**
     * Registers a payment with change handling: caps the applied amount at the
     * remaining due, computes the change, updates the UI state, persists the
     * payment and drives the customer display.
     *
     * <p>THE ONE PLACE the administered tender rules are opposed to a settlement
     * (BO-03-02-10/11/12/13/14/16): every method funnels here, so a ceiling
     * checked here is checked for all of them, and a tender no row administers
     * passes through exactly as before.
     *
     * @param state the current POS state
     * @param methodKey the payment method key (CASH, CARD, TR, CHEQUE...)
     * @param displayName the label shown on the customer display
     * @param tendered the amount handed over by the customer
     * @return true when the settlement was registered, false when an
     *         administered rule refused it and the sale was left untouched
     */
    private boolean handlePaymentWithChange(PosState state, String methodKey, String displayName, BigDecimal tendered) {
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (tendered == null || tendered.signum() <= 0) return false;

            BigDecimal remaining = state.getRemaining();
            BigDecimal amountToPay = tendered.min(remaining);

            // The administered bounds are opposed BEFORE anything is registered:
            // a blocking rule must leave the sale exactly as it was, not undo a
            // settlement the cashier can already see on the screen.
            TenderRulesService.Verdict verdict =
                    tenderRulesService.check(methodKey, amountToPay, registeredCount(state, methodKey));
            if (verdict.refuses() && !overrideCovers(state, methodKey, verdict)) {
                if (verdict.level.allowsOverride()) {
                    holdForSupervisor(state, methodKey, displayName, tendered, verdict);
                } else {
                    state.ticket.setError(verdict.displayMessage(i18n));
                }
                state.touch();
                return false;
            }
            // Consumed the moment it lets a settlement through: an authorization
            // outliving its settlement authorizes the next one.
            state.payment.clearTenderHold();

            state.payment.clearPendingVoucher();

            BigDecimal change = tendered.subtract(amountToPay).setScale(2, RoundingMode.HALF_UP);
            // A tender the back office forbids change on settles what it settles
            // and gives nothing back — the overpayment is simply not taken.
            if (change.signum() > 0 && !tenderRulesService.changeAllowed(methodKey, true)) {
                change = BigDecimal.ZERO;
            }
            TenderRulesService.Verdict changeVerdict = tenderRulesService.checkChange(methodKey, change);
            if (changeVerdict.refuses()) {
                state.ticket.setError(changeVerdict.displayMessage(i18n));
                state.touch();
                return false;
            }

            // BO-03-02-16: the change of a tender administered to give it back as
            // a credit note is not money out of the drawer. It is BOOKED here and
            // CREATED at the fiscal moment, like the gift cards sold on the sale —
            // an abandoned sale never creates value.
            boolean changeAsNote = change.signum() > 0
                    && CREDIT_NOTE_TENDER.equals(tenderRulesService.changeTender(methodKey));
            if (changeAsNote) {
                state.payment.changeAsCreditNote = state.payment.changeAsCreditNote.add(change);
            }

            // UI state update
            state.payment.lastChangeAmount = (change.signum() > 0 && !changeAsNote)
                    ? change : BigDecimal.ZERO;

            // In-memory update
            if ("CASH".equals(methodKey)) {
                state.payment.addCashPayment(amountToPay, tendered);
            } else if ("CARD".equals(methodKey)) {
                // The card entry carries the terminal traces (authorization
                // number, degraded-mode indicator) parked by the accept leg.
                state.payment.addCardPayment(amountToPay,
                        state.payment.pendingCardAuthNumber, state.payment.pendingCardDegraded,
                        state.payment.pendingCardEntryMode);
            } else if ("CHEQUE".equals(methodKey)) {
                // The cheque entry carries the magnetic line parked by the reading.
                state.payment.addChequePayment(amountToPay, state.payment.pendingChequeLine);
            } else {
                state.payment.addPayment(methodKey, amountToPay);
            }
            state.touch();

            // Database persistence
            savePayment(state, methodKey);

            // Hardware display
            if (changeAsNote) {
                hardwareService.displayMessage(String.format(i18n.get("display.renduAvoir"), df.format(change)));
            } else if (change.signum() > 0) {
                hardwareService.displayMessage(String.format(i18n.get("display.donneRendu"), df.format(tendered), df.format(change)));
            } else {
                hardwareService.displayMessage(String.format("%-10s%s E", displayName, df.format(amountToPay)));
            }

            // A rule that informs or warns without blocking is shown AFTER the
            // settlement is registered: the cashier is told what happened, not
            // stopped from doing it.
            if (verdict.speaks()) {
                state.ticket.setError(verdict.displayMessage(i18n));
            } else if (changeVerdict.speaks()) {
                state.ticket.setError(changeVerdict.displayMessage(i18n));
            }

            checkCompletion(state);
            return true;
        }
    }

    /**
     * Issues and prints the credit note this sale owes as change
     * (BO-03-02-16).
     *
     * <p>The booked amount is NOT cleared here: {@code clearTicket()}, which ends
     * this same closing, resets the whole payment state — clearing it twice would
     * be a line that can never be observed to do anything.
     *
     * @param state the current POS state
     * @param ticketId the database id of the closed ticket
     */
    private void issueChangeCreditNote(PosState state, Long ticketId) {
        BigDecimal owed = state.payment.changeAsCreditNote;
        if (owed == null || owed.signum() <= 0) {
            return;
        }
        String number = ticketPersistenceService.issueChangeCreditNote(ticketId, owed);
        // BO-03-03-01: the note is ISSUED whatever the printing rule — it is a
        // registry fact and a balance the customer owns — but it is only
        // PRINTED when the shop named it among the forced documents, because
        // the end-of-transaction screen cannot ask for it.
        if (number != null
                && printPolicy.isAssociatedDocumentPrinted(
                        com.intermarche.pos.ui.hardware.PrintPolicy.DOCUMENT_CREDIT_NOTE)) {
            ticketPrinterService.printChangeVoucher(number, owed);
        }
    }

    /**
     * Tells whether a supervisor's authorization already covers the rule the
     * settlement just broke (BO-03-02-10/12/13/14).
     *
     * <p>An authorization covers ONE tender and only the levels that admit an
     * override: a supervisor can pass a "bloquant superviseur" rule, and nobody
     * passes a "bloquant" one — that is the whole difference between the two
     * levels the questionnaire asks for.
     *
     * @param state the current POS state
     * @param methodKey the settlement key
     * @param verdict the rule the settlement broke
     * @return true when the settlement goes through on that authorization
     */
    private boolean overrideCovers(PosState state, String methodKey,
            TenderRulesService.Verdict verdict) {
        return verdict.level.allowsOverride()
                && methodKey != null
                && methodKey.equals(state.payment.tenderOverride);
    }

    /**
     * Holds a settlement back until a supervisor allows the administered bound
     * to be passed (BO-03-02-10/12/13/14).
     *
     * @param state the current POS state
     * @param methodKey the settlement key
     * @param displayName the label the settlement shows on the customer display
     * @param tendered the amount handed over
     * @param verdict the rule the settlement broke
     */
    private void holdForSupervisor(PosState state, String methodKey, String displayName,
            BigDecimal tendered, TenderRulesService.Verdict verdict) {
        state.payment.tenderHeldMethod = methodKey;
        state.payment.tenderHeldLabel = displayName;
        state.payment.tenderHeldAmount = tendered;
        // The BARE rule, not the display one: the panel is itself the call for a
        // supervisor, so repeating "appeler un superviseur" inside it says nothing.
        state.payment.tenderHeldMessage = verdict.message;
        state.payment.tenderOverride = null;
    }

    /**
     * Lets a supervisor allow the held settlement, then registers it
     * (BO-03-02-10/12/13/14).
     *
     * <p>What is registered is the settlement THAT WAS HELD, replayed from the
     * state: the supervisor authorizes an amount on a tender, not whatever the
     * pad shows by the time they have walked over.
     *
     * @param state the current POS state
     * @param login the supervisor's login, ignored when the logged operator is one
     * @param password the supervisor's password
     * @return true when the settlement was registered
     */
    public boolean authorizeHeldTender(PosState state, String login, String password) {
        LOGGER.info("Entering method authorizeHeldTender with state: " + state + ", login: " + login + ", password: ***");
        String methodKey = state.payment.tenderHeldMethod;
        BigDecimal tendered = state.payment.tenderHeldAmount;
        String displayName = state.payment.tenderHeldLabel;
        if (methodKey == null || tendered == null) {
            LOGGER.info("Exiting method authorizeHeldTender");
            return false;
        }
        boolean granted = endorsementService.operatorIsSupervisor(state)
                || endorsementService.authorize(login, password, TENDER_OVERRIDE_ACTION);
        if (!granted) {
            state.payment.tenderHeldMessage = i18n.get("op.autorisationRefusee");
            state.touch();
            LOGGER.info("Exiting method authorizeHeldTender");
            return false;
        }
        state.payment.tenderOverride = methodKey;
        boolean registered = handlePaymentWithChange(state, methodKey, displayName, tendered);
        if (registered && !state.trainingMode && opensDrawerFor(state, methodKey)) {
            hardwareService.openDrawer();
        }
        LOGGER.info("Exiting method authorizeHeldTender");
        return registered;
    }

    /**
     * Abandons the settlement held back for a supervisor's authorization.
     *
     * @param state the current POS state
     */
    public void cancelHeldTender(PosState state) {
        LOGGER.info("Entering method cancelHeldTender with state: " + state);
        state.payment.clearTenderHold();
        state.touch();
        LOGGER.info("Exiting method cancelHeldTender");
    }

    /**
     * Counts the settlements of one tender already registered on the sale
     * (BO-03-02-12).
     *
     * @param state the current POS state
     * @param methodKey the settlement key, or null
     * @return how many settlements of that tender the sale already carries
     */
    private int registeredCount(PosState state, String methodKey) {
        if (methodKey == null) {
            return 0;
        }
        int count = 0;
        for (PaymentState.PaymentEntry entry : state.payment.payments) {
            if (methodKey.equals(entry.method)) {
                count++;
            }
        }
        return count;
    }

    // --------------------------------------------------
    // 3. PUBLIC ACTIONS
    // --------------------------------------------------

    /**
     * Registers a cash payment; the drawer always opens (deposit and change).
     *
     * @param state the current POS state
     * @param tendered the cash amount handed over
     */
    public void processCash(PosState state, BigDecimal tendered) {
        LOGGER.info("Entering method processCash with state: " + state + ", tendered: " + tendered);
        if (tendered == null || tendered.signum() <= 0) { LOGGER.info("Exiting method processCash"); return; }

        // LC-07-03-05: where the shop rounds, the coins to make up anything off
        // the step no longer circulate, so an amount off the step cannot have been
        // handed over. Refused rather than silently rounded: the cashier is telling
        // the register what is in their hand, and the register does not know better.
        if (!CashRounding.isTenderable(tendered, state.cashRoundingStepCents)) {
            state.ticket.setError(i18n.format("op.montantNonMultipleE",
                    df.format(BigDecimal.valueOf(state.cashRoundingStepCents, 2))));
            state.touch();
            LOGGER.info("Exiting method processCash");
            return;
        }

        // LC-07-03-01/06: the rounding applies to what is SETTLED IN CASH, so it is
        // decided here and not on the ticket total — a sale part-paid by card rounds
        // the remainder, which is what the customer actually hands over. The
        // difference is booked FIRST, so the cash payment that follows sees a
        // remaining due already on the step and the change comes out right.
        registerCashRounding(state, tendered);

        if (!handlePaymentWithChange(state, "CASH", "ESPECES", tendered)) {
            LOGGER.info("Exiting method processCash");
            return;
        }

        // Rule: cash = systematic drawer opening (deposit + change), unless the
        // back office disabled the drawer-open-on-payment rule (BO-10-02-12) or
        // administered another moment for this tender (BO-03-02-19).
        if (!state.trainingMode && opensDrawerFor(state, "CASH")) hardwareService.openDrawer(); // drawer stays shut in training
        LOGGER.info("Exiting method processCash");
    }

    /**
     * Registers a settlement taken on a backup-monetics terminal
     * ({@code LC-07-07-06} to {@code -09}).
     *
     * <p>Capped at the remaining due and NO CHANGE: a mobile terminal charges what
     * it was asked to charge, and an amount above what is left is a partial
     * authorization mismatch, not a customer handing over too much. Giving change on
     * it would take real money out of the drawer against a card settlement.
     *
     * <p>The drawer stays shut: nothing physical moves at this till.
     *
     * @param state the current POS state
     * @param amount the amount the mobile terminal accepted
     * @param methodLabel the scheme it reported
     * @param transactionNumber the sale the two codes were matched on
     * @param manual true when the outcome was keyed in rather than scanned
     */
    public void processBackupPayment(PosState state, BigDecimal amount, String methodLabel,
            String transactionNumber, boolean manual) {
        LOGGER.info("Entering method processBackupPayment with state: " + state + ", amount: " + amount + ", methodLabel: " + methodLabel + ", transactionNumber: " + transactionNumber + ", manual: " + manual);
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (amount == null || amount.signum() <= 0) { LOGGER.info("Exiting method processBackupPayment"); return; }
            BigDecimal remaining = state.getRemaining();
            if (remaining.signum() <= 0) { LOGGER.info("Exiting method processBackupPayment"); return; }

            state.payment.clearPendingVoucher();

            BigDecimal amountToPay = amount.min(remaining);
            state.payment.addBackupPayment(amountToPay, methodLabel, transactionNumber, manual);
            state.touch();
            savePayment(state, "SECOURS");
            hardwareService.displayMessage(String.format(i18n.get("display.secours"), df.format(amountToPay)));

            // Rule: backup monetics = no drawer opening (nothing physical).

            checkCompletion(state);
        }
        LOGGER.info("Exiting method processBackupPayment");
    }

    /**
     * Registers a settlement handed over in a foreign currency ({@code LC-07-14}).
     *
     * <p>Everything downstream works in EUROS: the euro value is what the sale is
     * credited with, what the change is computed on and what the accounts see. The
     * currency, the amount handed over and the rate ride on the entry so the receipt
     * can state all three ({@code LC-07-14-05}), and nowhere else.
     *
     * <p>The drawer opens: foreign notes go into it, like any physical tender.
     *
     * @param state the current POS state
     * @param currency the currency handed over
     * @param foreignAmount the amount handed over, in that currency
     * @param euroValue the euro value of that amount at the administered rate
     */
    public void processForeignCurrency(PosState state, com.intermarche.pos.domain.payment.Currency currency,
            BigDecimal foreignAmount, BigDecimal euroValue) {
        LOGGER.info("Entering method processForeignCurrency with state: " + state + ", currency: " + currency + ", foreignAmount: " + foreignAmount + ", euroValue: " + euroValue);
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (currency == null || euroValue == null || euroValue.signum() <= 0) { LOGGER.info("Exiting method processForeignCurrency"); return; }
            BigDecimal remaining = state.getRemaining();
            if (remaining.signum() <= 0) { LOGGER.info("Exiting method processForeignCurrency"); return; }

            state.payment.clearPendingVoucher();

            BigDecimal amountToPay = euroValue.min(remaining);
            BigDecimal change = euroValue.subtract(amountToPay).setScale(2, RoundingMode.HALF_UP);
            state.payment.lastChangeAmount = change.signum() > 0 ? change : BigDecimal.ZERO;

            state.payment.addCurrencyPayment(amountToPay, currency.code, foreignAmount,
                    currency.euroPerUnit);
            state.touch();
            savePayment(state, "DEVISE");

            // LC-07-14-05: the three figures the cashier and the customer need to
            // agree on — what was handed over, what it is worth, and at what rate.
            hardwareService.displayMessage(String.format("%s %s = %s E",
                    df.format(foreignAmount), currency.displayUnit(), df.format(euroValue)));
            if (change.signum() > 0) {
                hardwareService.displayMessage(String.format(i18n.get("display.rendu"), df.format(change)));
            }

            if (!state.trainingMode && opensDrawerFor(state, "DEVISE")) {
                hardwareService.openDrawer();
            }

            checkCompletion(state);
        }
        LOGGER.info("Exiting method processForeignCurrency");
    }

    /**
     * Books the legal rounding difference when this cash payment settles the sale
     * ({@code LC-07-03-06}).
     *
     * <p>ONLY WHEN IT SETTLES. A cash payment smaller than the rounded remainder is
     * a part payment: the sale goes on, its remainder is rounded again later, and
     * rounding a part payment would round the same sale twice. So the difference is
     * booked only when what is tendered covers the rounded remainder.
     *
     * @param state the current POS state
     * @param tendered the cash amount handed over
     */
    private void registerCashRounding(PosState state, BigDecimal tendered) {
        int step = state.cashRoundingStepCents;
        if (step <= 1) {
            return;
        }
        BigDecimal remaining = state.getRemaining();
        BigDecimal difference = CashRounding.difference(remaining, step);
        if (difference.signum() == 0) {
            return;
        }
        if (tendered.compareTo(CashRounding.round(remaining, step)) < 0) {
            return;
        }
        state.payment.addPayment("ARRONDI", difference);
        state.touch();
        savePayment(state, "ARRONDI");
        hardwareService.displayMessage(String.format(i18n.get("display.arrondi"), df.format(difference.negate())));
    }

    /**
     * Applies the CARD PAYMENT control point (BO-10-02-10).
     *
     * <p>Answers whether the card may be used, and leaves its sentence on the
     * sale screen when it may not.
     *
     * @param state the current POS state
     * @return true when the settlement may go on
     */
    private boolean cardPaymentAllowed(PosState state) {
        com.intermarche.pos.ui.endorsement.ControlLevel level =
                endorsementService.check(state, com.intermarche.pos.ui.endorsement.ControlPoint.CARD_PAYMENT);
        // BO-03-10-14: an island may silence the non-blocking alerts tied to a
        // tender. The refusal below is NOT silenced: a card the store forbids
        // stays forbidden and says so.
        if (level == com.intermarche.pos.ui.endorsement.ControlLevel.WARN) {
            if (!posSettingsService.islandBlockPaymentPopups()) {
                state.ticket.setWarning(endorsementService.controlMessage(
                        com.intermarche.pos.ui.endorsement.ControlPoint.CARD_PAYMENT, level));
            }
        } else if (!level.allows()) {
            state.ticket.setError(endorsementService.controlMessage(
                    com.intermarche.pos.ui.endorsement.ControlPoint.CARD_PAYMENT, level));
        }
        return level.allows();
    }

    /**
     * Registers a card payment; the amount defaults to the remaining due.
     *
     * @param state the current POS state
     * @param amount the amount to pay, or zero/negative to use the remaining due
     */
    public void processCard(PosState state, BigDecimal amount) {
        LOGGER.info("Entering method processCard with state: " + state + ", amount: " + amount);
        if (amount == null || amount.signum() <= 0) amount = state.getRemaining();
        if (amount.signum() <= 0) { LOGGER.info("Exiting method processCard"); return; }
        // BO-10-02-10: the shop may watch the use of the card at this register
        // — nothing, a warning, or a refusal. Said BEFORE the terminal is asked
        // anything, because a transaction started is a transaction to undo. The
        // point is not endorsable: see ControlPoint.isEndorsable().
        if (!cardPaymentAllowed(state)) { LOGGER.info("Exiting method processCard"); return; }

        // One transaction at a time: the pending amount is the UI-facing
        // in-flight marker, whatever the terminal implementation.
        if (state.payment.pendingCardAmount != null) { LOGGER.info("Exiting method processCard"); return; }
        BigDecimal requested = amount.setScale(2, RoundingMode.HALF_UP);
        state.payment.pendingCardAmount = requested;
        hardwareService.displayMessage(String.format(i18n.get("display.carte"), df.format(requested)));
        state.touch();

        // The decision arrives asynchronously on the callback: the virtual
        // terminal fires it from the simulator's accept/refuse endpoint,
        // the auto mode fires it synchronously, Verifone from its exchange
        // thread. Exactly one method runs per transaction.
        terminal.requestDebit(requested,
                new com.intermarche.pos.ui.hardware.terminal.TerminalTransactionCallback() {
            /**
             * Registers the accepted card payment.
             *
             * @param outcome the terminal outcome
             */
            @Override
            public void onAccepted(com.intermarche.pos.ui.hardware.terminal.TerminalOutcome outcome) {
                registerAcceptedCard(state, outcome);
            }

            /**
             * Drops the refused card payment and tells the cashier.
             *
             * @param outcome the refusal outcome
             */
            @Override
            public void onRefused(com.intermarche.pos.ui.hardware.terminal.TerminalOutcome outcome) {
                // LC-08-03-12: the not-completed transaction leaves a printed
                // trace when the back office forces it — the terminal's own
                // frame when it supplied one.
                if (outcome != null && printPolicy.isTnaReceiptForced()) {
                    ticketPrinterService.printCardTnaReceipt(outcome.amount, outcome.tnaFrame);
                }
                dropPendingCard(state, i18n.get("op.paiementRefuseTpe"));
            }

            /**
             * Drops the card payment on a terminal failure with the
             * terminal's own message.
             *
             * @param message the operator-facing failure message
             */
            @Override
            public void onError(String message) {
                dropPendingCard(state, message);
            }
        });

        // Rule: card = no drawer opening
        LOGGER.info("Exiting method processCard");
    }

    /**
     * Registers the pending card payment — the accept leg of the terminal
     * callback. Parks the terminal traces (authorization number, degraded-mode
     * indicator) so the money path carries them onto the CardPayment entity
     * (BO-04-01-08/47/49), then clears them.
     *
     * @param state the current POS state
     * @param outcome the terminal outcome carrying the monetique traces
     */
    private void registerAcceptedCard(PosState state,
            com.intermarche.pos.ui.hardware.terminal.TerminalOutcome outcome) {
        BigDecimal amount = state.payment.pendingCardAmount;
        if (amount == null) return;
        state.payment.pendingCardAmount = null;
        state.payment.pendingCardAuthNumber = outcome.authorizationNumber;
        state.payment.pendingCardDegraded = outcome.degradedMode;
        // EXP-54: trace the real card entry mode (contactless / chip-and-PIN)
        // the terminal returned, parked here for the money path to carry it.
        state.payment.pendingCardEntryMode = outcome.entryMode;
        // LC-08-03-11: a slip the customer must sign forces the card receipt
        // whatever the cashier chooses. One signed card in the transaction is
        // enough, so the flag only ever goes up.
        if (outcome.signatureRequired) {
            state.payment.cardSignatureRequired = true;
        }
        handlePaymentWithChange(state, "CARD", "CARTE", amount);
        state.payment.pendingCardAuthNumber = null;
        state.payment.pendingCardDegraded = false;
        state.payment.pendingCardEntryMode = null;
        state.touch();
    }

    /**
     * Drops the pending card payment with an operator-facing message — the
     * refuse and error legs of the terminal callback.
     *
     * @param state the current POS state
     * @param message the ticket error to show
     */
    private void dropPendingCard(PosState state, String message) {
        if (state.payment.pendingCardAmount == null) return;
        state.payment.pendingCardAmount = null;
        state.ticket.setError(message);
        hardwareService.displayMessage(i18n.both("display.paiementRefuse"));
        state.touch();
    }

    /**
     * Cancels the pending card payment from the register side and tells the
     * terminal to abandon its in-flight transaction.
     *
     * @param state the current POS state
     */
    public void cancelPendingCard(PosState state) {
        LOGGER.info("Entering method cancelPendingCard with state: " + state);
        if (state.payment.pendingCardAmount == null) { LOGGER.info("Exiting method cancelPendingCard"); return; }
        state.payment.pendingCardAmount = null;
        terminal.abort();
        hardwareService.displayMessage(String.format(i18n.get("display.total"), df.format(state.ticket.totalAmount)));
        state.touch();
        LOGGER.info("Exiting method cancelPendingCard");
    }

    /**
     * Registers a meal-ticket payment; the drawer opens to store the tickets.
     *
     * @param state the current POS state
     * @param amount the amount to pay, or zero/negative to use the remaining due
     */
    public void processTicketResto(PosState state, BigDecimal amount) {
        LOGGER.info("Entering method processTicketResto with state: " + state + ", amount: " + amount);
        if (amount == null || amount.signum() <= 0) amount = state.getRemaining();
        if (amount.signum() <= 0) { LOGGER.info("Exiting method processTicketResto"); return; }

        // Phase 7 lot 3: the engine's MEAL_VOUCHER advantage caps meal tickets
        // at min(eligible base, threshold, requested). The cap applies to the
        // BASKET: the allowance left for this payment is the capped base minus
        // the meal tickets already registered on the ticket. It is derived
        // from the payment list at every call, never kept as a decremented
        // field: each revaluation (every poll of the payment screen) rewrites
        // the engine hints, so a decremented field would silently re-open the
        // allowance and let a second full payment through. No advantage
        // emitted = no cap (local behavior unchanged). Single-writer
        // discipline: the check and the registration must be one atomic step,
        // or two concurrent submissions could both pass the check.
        synchronized (state) {
            boolean engineAnswered = "ENGINE".equals(state.payment.valuationStatus)
                    && state.payment.valuationMealEligible != null;
            // BO-02-03-06: without an engine advantage the cap comes from the
            // ARTICLE ATTRIBUTES carried by the lines. A register in local mode
            // used to accept meal tickets on anything; it now settles at most
            // what the eligible lines are worth. The engine still wins when it
            // answered: it knows the offers, the attributes do not.
            BigDecimal localBase = state.ticket.mealVoucherEligibleTotal();
            if (engineAnswered || localBase.signum() > 0) {
                BigDecimal allowed = engineAnswered
                        ? state.payment.valuationMealEligible : localBase;
                if (engineAnswered && state.payment.valuationMealThreshold != null) {
                    allowed = allowed.min(state.payment.valuationMealThreshold);
                }
                allowed = allowed.subtract(registeredMealTicketTotal(state)).max(BigDecimal.ZERO);
                if (allowed.signum() <= 0) {
                    hardwareService.displayMessage(i18n.both("display.trAucunArticleEligible"));
                    LOGGER.infof("Paiement TR refusé: assiette éligible épuisée");
                    LOGGER.info("Exiting method processTicketResto");
                    return;
                }
                if (amount.compareTo(allowed) > 0) {
                    amount = allowed;
                    hardwareService.displayMessage(String.format(i18n.get("display.trPlafonne"), df.format(allowed)));
                    LOGGER.infof("Paiement TR plafonné à %s (assiette moteur)", allowed);
                }
            }

            if (!handlePaymentWithChange(state, "TR", "TICKET", amount)) {
                LOGGER.info("Exiting method processTicketResto");
                return;
            }
        }

        // Rule: meal tickets = systematic drawer opening (to store the tickets),
        // unless the drawer-open-on-payment rule is disabled (BO-10-02-12) or
        // another moment is administered for this tender (BO-03-02-19).
        if (!state.trainingMode && opensDrawerFor(state, "TR")) hardwareService.openDrawer(); // drawer stays shut in training
        LOGGER.info("Exiting method processTicketResto");
    }

    /**
     * Sums the meal-ticket payments already registered on the current ticket.
     * The engine's meal-voucher cap applies to the basket, so the allowance
     * left for a new meal ticket is the capped base minus this total. Reading
     * the payment list keeps the rule correct across revaluations (which
     * rewrite the engine hints verbatim) and across payment cancellations
     * (which empty the list and thereby restore the full allowance).
     *
     * @param state the current POS state
     * @return the total of registered TR payments, zero when there is none
     */
    private BigDecimal registeredMealTicketTotal(PosState state) {
        BigDecimal total = BigDecimal.ZERO;
        for (PaymentState.PaymentEntry entry : state.payment.payments) {
            // EXP-60/67: the paper voucher, the TRD card and Resto Flash all draw
            // on the SAME meal-voucher eligible base, so all three consume the
            // allowance — else a customer could settle the eligible base once per
            // tender and pay non-eligible goods with a meal voucher.
            if ("TR".equals(entry.method) || "TRD".equals(entry.method)
                    || "RESTOFLASH".equals(entry.method)) {
                total = total.add(entry.amount);
            }
        }
        return total;
    }

    /**
     * The meal-voucher allowance still open on the ticket (EXP-60/67): the
     * eligible base (the engine's when it answered and capped by its threshold,
     * else the base carried by the article attributes) minus the meal-voucher
     * payments already registered. Null means NO base constraint applies (no
     * engine answer and no eligible line) — the historical unconstrained
     * behaviour of a paper meal voucher in local mode.
     *
     * @param state the current POS state
     * @return the remaining allowance (never negative), or null when unconstrained
     */
    private BigDecimal mealVoucherAllowance(PosState state) {
        boolean engineAnswered = "ENGINE".equals(state.payment.valuationStatus)
                && state.payment.valuationMealEligible != null;
        BigDecimal localBase = state.ticket.mealVoucherEligibleTotal();
        if (!engineAnswered && localBase.signum() <= 0) {
            return null;
        }
        BigDecimal allowed = engineAnswered ? state.payment.valuationMealEligible : localBase;
        if (engineAnswered && state.payment.valuationMealThreshold != null) {
            allowed = allowed.min(state.payment.valuationMealThreshold);
        }
        return allowed.subtract(registeredMealTicketTotal(state)).max(BigDecimal.ZERO);
    }

    /**
     * Registers a TRD-card (dematerialized meal-voucher) payment (EXP-60): bound
     * to the meal-voucher eligible base like the paper voucher AND capped by the
     * administered per-transaction plafond ({@code payment.trd-card-cap}, 0 = no
     * cap). Refused when the eligible base is exhausted; plafonné to the smaller
     * of the base allowance and the cap.
     *
     * @param state the current POS state
     * @param amount the amount to pay, or zero/negative to use the remaining due
     */
    public void processTrdCard(PosState state, BigDecimal amount) {
        LOGGER.info("Entering method processTrdCard with amount: " + amount);
        if (amount == null || amount.signum() <= 0) amount = state.getRemaining();
        if (amount.signum() <= 0) { LOGGER.info("Exiting method processTrdCard"); return; }
        synchronized (state) {
            BigDecimal allowance = mealVoucherAllowance(state);
            if (allowance != null) {
                if (allowance.signum() <= 0) {
                    hardwareService.displayMessage(i18n.both("display.trdAucunArticleEligible"));
                    LOGGER.info("Exiting method processTrdCard");
                    return;
                }
                if (amount.compareTo(allowance) > 0) {
                    amount = allowance;
                    hardwareService.displayMessage(String.format(i18n.get("display.trdPlafonne"), df.format(amount)));
                }
            }
            BigDecimal cap = posSettingsService.trdCardCap();
            if (cap != null && cap.signum() > 0 && amount.compareTo(cap) > 0) {
                amount = cap;
                hardwareService.displayMessage(String.format(i18n.get("display.trdPlafonne"), df.format(amount)));
            }
            if (!handlePaymentWithChange(state, "TRD", "CARTE TRD", amount)) {
                LOGGER.info("Exiting method processTrdCard");
                return;
            }
        }
        LOGGER.info("Exiting method processTrdCard");
    }

    /**
     * Registers a Resto Flash mobile meal-voucher payment (EXP-67): confirmed
     * through {@link RestoFlashClient} and bound to the SAME meal-voucher
     * eligible base as the other meal vouchers. A token Resto Flash does not
     * confirm settles nothing.
     *
     * @param state the current POS state
     * @param token the mobile payment token presented by the customer
     * @param amount the amount to pay, or zero/negative to use the remaining due
     */
    public void processRestoFlash(PosState state, String token, BigDecimal amount) {
        LOGGER.info("Entering method processRestoFlash with amount: " + amount);
        if (amount == null || amount.signum() <= 0) amount = state.getRemaining();
        if (amount.signum() <= 0) { LOGGER.info("Exiting method processRestoFlash"); return; }
        synchronized (state) {
            BigDecimal allowance = mealVoucherAllowance(state);
            if (allowance != null) {
                if (allowance.signum() <= 0) {
                    hardwareService.displayMessage(i18n.both("display.restoFlashAucunArticle"));
                    LOGGER.info("Exiting method processRestoFlash");
                    return;
                }
                if (amount.compareTo(allowance) > 0) {
                    amount = allowance;
                    hardwareService.displayMessage(String.format(i18n.get("display.restoFlashPlafonne"), df.format(amount)));
                }
            }
            if (!restoFlashClient.confirm(token, amount)) {
                hardwareService.displayMessage(i18n.both("display.restoFlashRefuse"));
                LOGGER.info("Exiting method processRestoFlash");
                return;
            }
            if (!handlePaymentWithChange(state, "RESTOFLASH", "RESTO FLASH", amount)) {
                LOGGER.info("Exiting method processRestoFlash");
                return;
            }
        }
        LOGGER.info("Exiting method processRestoFlash");
    }

    /**
     * Registers a supplier gift-card payment (EXP-66): the issuer registry is
     * interrogated for validity and balance, and the payment is capped at that
     * balance (and at the remaining due). An invalid card, or one with no
     * balance, settles nothing.
     *
     * @param state the current POS state
     * @param cardNumber the scanned or keyed gift-card number
     * @param amount the amount to pay, or zero/negative to use the remaining due
     */
    public void processSupplierGiftCard(PosState state, String cardNumber, BigDecimal amount) {
        LOGGER.info("Entering method processSupplierGiftCard with amount: " + amount);
        if (amount == null || amount.signum() <= 0) amount = state.getRemaining();
        if (amount.signum() <= 0) { LOGGER.info("Exiting method processSupplierGiftCard"); return; }
        synchronized (state) {
            GiftCardIssuerClient.IssuerVerdict verdict = giftCardIssuerClient.query(cardNumber);
            if (!verdict.valid()) {
                hardwareService.displayMessage(i18n.both("display.carteCadeauRefusee"));
                LOGGER.info("Exiting method processSupplierGiftCard");
                return;
            }
            BigDecimal balance = verdict.balance();
            if (balance != null && balance.signum() > 0 && amount.compareTo(balance) > 0) {
                amount = balance;
                hardwareService.displayMessage(String.format(i18n.get("display.carteCadeauMontant"), df.format(amount)));
            } else if (balance == null || balance.signum() <= 0) {
                hardwareService.displayMessage(i18n.both("display.carteCadeauSoldeNul"));
                LOGGER.info("Exiting method processSupplierGiftCard");
                return;
            }
            if (!handlePaymentWithChange(state, "GIFTCARD", "CARTE CADEAU", amount)) {
                LOGGER.info("Exiting method processSupplierGiftCard");
                return;
            }
            // BO-03-03-11: the slip of a settlement PAID by gift card, which is
            // not the voucher a customer buying one walks away with. What it
            // adds is the balance the card still carries — the one question
            // asked at the counter — and that balance is the issuer's answer
            // minus what was just spent.
            printGiftCardPaymentSlip(state, cardNumber, amount, balance);
        }
        LOGGER.info("Exiting method processSupplierGiftCard");
    }

    /**
     * Registers a SOGEC bon payment through the compensation circuit (EXP-58):
     * the bon settles the ticket and a compensation trace is recorded for the
     * supplier reimbursement — distinct from the generic GS1 coupon path.
     *
     * <p>THE BON SETTLES ITS OWN FACE VALUE, never the remaining due. It used to
     * settle the remainder whenever no amount reached it, and the screen sent none:
     * a 20 € bon closed a 56,50 € ticket and the receipt came out paid in full by
     * SOGEC. The value is the one the paper states — decoded from the bon's own
     * barcode when it carries it ({@code (390n)}), keyed by the cashier otherwise —
     * and a bon with no value settles NOTHING, because guessing it is exactly the
     * bug.
     *
     * <p>A bon worth more than what is left to pay is CAPPED at the remainder and
     * says so, like the meal-voucher allowance and the gift-card balance: the
     * register never hands change back against a manufacturer's coupon.
     *
     * @param state the current POS state
     * @param couponCode the SOGEC bon code
     * @param amount the face value keyed by the cashier, ignored when the bon's own
     *        barcode carries one
     */
    public void processSogec(PosState state, String couponCode, BigDecimal amount) {
        LOGGER.info("Entering method processSogec with amount: " + amount);
        BigDecimal faceValue = sogecCompensationService.faceValueOf(couponCode);
        if (faceValue == null) {
            faceValue = amount;
        }
        if (faceValue == null || faceValue.signum() <= 0) {
            hardwareService.displayMessage(i18n.both("display.bonSogecValeurRequise"));
            LOGGER.info("Exiting method processSogec: aucune valeur");
            return;
        }
        BigDecimal applied;
        synchronized (state) {
            BigDecimal remaining = state.getRemaining();
            if (remaining.signum() <= 0) {
                LOGGER.info("Exiting method processSogec: rien a payer");
                return;
            }
            applied = faceValue.min(remaining);
            if (applied.compareTo(faceValue) < 0) {
                hardwareService.displayMessage(
                        String.format(i18n.get("display.bonSogecPlafonne"), df.format(applied)));
            }
            if (!handlePaymentWithChange(state, "SOGEC", "BON SOGEC", applied)) {
                LOGGER.info("Exiting method processSogec");
                return;
            }
        }
        sogecCompensationService.recordCompensation(couponCode, applied);
        LOGGER.info("Exiting method processSogec");
    }

    /**
     * Registers a cheque payment; the drawer opens to store the cheque.
     *
     * @param state the current POS state
     * @param amount the amount to pay, or zero/negative to use the remaining due
     */
    public void processCheque(PosState state, BigDecimal amount) {
        LOGGER.info("Entering method processCheque with state: " + state + ", amount: " + amount);
        if (amount == null || amount.signum() <= 0) amount = state.getRemaining();
        if (amount.signum() <= 0) { LOGGER.info("Exiting method processCheque"); return; }
        if (state.trainingMode) {
            registerCheque(state, amount);
            LOGGER.info("Exiting method processCheque");
            return;
        }
        // The cheque is READ BEFORE it is registered. A payment settled first and read
        // afterwards would leave a paid line on the ticket for a cheque the reader
        // refused, and the cashier would have to undo a payment instead of simply
        // being told to present the cheque again.
        state.payment.pendingChequeAmount = amount;
        state.payment.pendingChequeLine = null;
        state.touch();
        // Built HERE, on the request thread: the reader prints the endorsement while it
        // still holds the cheque, so the text must leave with the start of the reading.
        String endorsement = endorsementText(amount);
        // The two callbacks fire on the reader's follower thread and only DEPOSIT
        // their outcome: registering from there would make a second writer of the
        // draft row the request thread rewrites on every revaluation.
        chequeReadingService.read(
                endorsement,
                line -> depositChequeRead(state, line),
                message -> depositChequeFailure(state, message));
        LOGGER.info("Exiting method processCheque");
    }

    /**
     * Composes what is printed on the back of the cheque.
     * <p>
     * What the register knows and the paper does not: WHEN the cheque was taken and
     * FOR HOW MUCH. Nothing is read from the database here — this service orchestrates
     * a payment, it does not query, and an endorsement is not worth a round trip in the
     * middle of a sale.
     *
     * @param amount the amount the cheque settles
     * @return the endorsement, one line per newline
     */
    private String endorsementText(BigDecimal amount) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String administered = posSettingsService == null
                ? null : posSettingsService.chequeEndorsementLines();
        if (administered == null || administered.isBlank()) {
            // Nothing administered: the date and the amount, exactly as before
            // the key existed (BO-03-02-43).
            return now.format(ENDORSEMENT_DATE) + "\n" + df.format(amount) + " EUR";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : administered.split(";")) {
            String rendered = fillEndorsementTokens(line.trim(), now, amount);
            if (rendered.isEmpty()) {
                // An empty administered line prints nothing rather than a blank
                // band: paper on the back of a cheque is scarce.
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(rendered);
        }
        return sb.toString();
    }

    /**
     * Substitutes the endorsement tokens of one administered line
     * (BO-03-02-43).
     *
     * <p>A token the register cannot answer renders EMPTY rather than leaving
     * the token visible: a cheque carrying {@code {pdv}} in ink is worse than
     * one carrying nothing there.
     *
     * @param line the administered line
     * @param now the moment the cheque is being taken
     * @param amount the amount the cheque settles
     * @return the line with its tokens replaced
     */
    private String fillEndorsementTokens(String line, java.time.LocalDateTime now,
                                         BigDecimal amount) {
        return line
                .replace("{date}", now.format(java.time.format.DateTimeFormatter
                        .ofPattern("dd/MM/yyyy")))
                .replace("{heure}", now.format(java.time.format.DateTimeFormatter
                        .ofPattern("HH:mm")))
                .replace("{montant}", df.format(amount))
                .replace("{caisse}", ticketNumberService == null
                        ? "" : safeText(ticketNumberService.getTerminalId()))
                .replace("{pdv}", safeText(pdvNumber))
                .trim();
    }

    /**
     * Returns a text, never null.
     *
     * @param value the text, or null
     * @return the text, or the empty string
     */
    private String safeText(String value) {
        return value == null ? "" : value;
    }

    /**
     * Deposits a finished reading for the next request to register.
     *
     * <p>Called from the reader's follower thread. It writes nothing but the
     * deposit and bumps the state version, which is what makes the polling screen
     * reload within a second and carry the registration on its own thread.
     *
     * @param state the current POS state
     * @param line the magnetic line the reader returned
     */
    private void depositChequeRead(PosState state, String line) {
        state.payment.chequeReadLine = line;
        state.touch();
    }

    /**
     * Deposits a failed reading for the next request to report.
     *
     * @param state the current POS state
     * @param message the operator-facing message the reader reported
     */
    private void depositChequeFailure(PosState state, String message) {
        state.payment.chequeReadFailure = message;
        state.touch();
    }

    /**
     * Settles a cheque reading deposited by the follower thread, on the REQUEST
     * thread that calls this.
     *
     * <p>The register writes its draft from one thread at a time: the reading ends
     * far from the page — like a badge on the scan bus — and only the poll can
     * bring its outcome back into a request. A reading that lands after the
     * cashier cancelled changes nothing: the deposit is consumed and dropped.
     *
     * @param state the current POS state
     */
    public void settlePendingChequeRead(PosState state) {
        String line;
        String failure;
        synchronized (state) {
            line = state.payment.chequeReadLine;
            failure = state.payment.chequeReadFailure;
            if (line == null && failure == null) {
                return;
            }
            state.payment.chequeReadLine = null;
            state.payment.chequeReadFailure = null;
        }
        LOGGER.info("Entering method settlePendingChequeRead");
        if (failure != null) {
            dropPendingCheque(state, failure);
            LOGGER.info("Exiting method settlePendingChequeRead");
            return;
        }
        registerReadCheque(state, line);
        LOGGER.info("Exiting method settlePendingChequeRead");
    }

    /**
     * Registers the cheque the reader read, on the request thread.
     *
     * <p>The amount is the one the cashier asked for, still held in
     * {@code pendingChequeAmount}: its absence means the cashier cancelled while
     * the document was being read, and a payment appearing by itself on a ticket
     * they moved on from is worse than a cheque re-presented.
     *
     * @param state the current POS state
     * @param line the magnetic line, kept for the journal
     */
    private void registerReadCheque(PosState state, String line) {
        BigDecimal amount;
        synchronized (state) {
            amount = state.payment.pendingChequeAmount;
            if (amount == null) {
                LOGGER.info("Cheque lu apres annulation cote caisse : ignore");
                return;
            }
            state.payment.pendingChequeAmount = null;
            state.payment.pendingChequeLine = line;
        }
        LOGGER.infof("Cheque lu : %s", line);
        registerCheque(state, amount);
    }

    /**
     * Drops the pending cheque and tells the cashier why.
     *
     * @param state the current POS state
     * @param message the operator-facing message
     */
    private void dropPendingCheque(PosState state, String message) {
        if (state.payment.pendingChequeAmount == null) {
            return;
        }
        state.payment.pendingChequeAmount = null;
        state.payment.pendingChequeLine = null;
        state.ticket.setError(message);
        state.touch();
    }

    /**
     * Cancels the pending cheque from the register side.
     *
     * <p>The reader is not told: it has no abort, and the document it is holding is
     * given back by its own timeout. Only the register stops expecting a cheque.
     *
     * @param state the current POS state
     */
    public void cancelPendingCheque(PosState state) {
        LOGGER.info("Entering method cancelPendingCheque with state: " + state);
        if (state.payment.pendingChequeAmount == null) {
            LOGGER.info("Exiting method cancelPendingCheque");
            return;
        }
        state.payment.pendingChequeAmount = null;
        state.payment.pendingChequeLine = null;
        // A deposit already made by the follower must go with it: the cashier
        // moved on, and that reading must never settle later.
        state.payment.chequeReadLine = null;
        state.payment.chequeReadFailure = null;
        state.touch();
        LOGGER.info("Exiting method cancelPendingCheque");
    }

    /**
     * Settles a cheque payment, whatever led to it.
     *
     * @param state the current POS state
     * @param amount the amount applied to the ticket
     */
    private void registerCheque(PosState state, BigDecimal amount) {
        if (!handlePaymentWithChange(state, "CHEQUE", "CHEQUE", amount)) {
            return;
        }
        // Ephemeral, like the card traces: cleared once the entry carries it, so the
        // next cheque of the same ticket cannot inherit this one's line.
        state.payment.pendingChequeLine = null;

        // Rule: cheque = systematic drawer opening (to store the cheque),
        // unless the drawer-open-on-payment rule is disabled (BO-10-02-12) or
        // another moment is administered for this tender (BO-03-02-19).
        if (!state.trainingMode && opensDrawerFor(state, "CHEQUE")) hardwareService.openDrawer(); // drawer stays shut in training
    }

    /**
     * Tells whether the drawer opens for the settlement just registered
     * (BO-03-02-19).
     *
     * <p>The register's own rule — the {@code pos.drawer.open-on-payment}
     * setting — is what a tender no row administers keeps, so the referential
     * refines the behaviour without replacing it.
     *
     * @param state the current POS state
     * @param methodKey the settlement key
     * @return true when the drawer must open now
     */
    private boolean opensDrawerFor(PosState state, String methodKey) {
        boolean changeDue = state.payment.lastChangeAmount != null
                && state.payment.lastChangeAmount.signum() > 0;
        return tenderRulesService.opensDrawer(methodKey, changeDue, false,
                posSettingsService.drawerOpenOnPayment());
    }

    /**
     * Registers a fidelity (virtual) payment capped at the remaining due.
     *
     * @param state the current POS state
     * @param amount the amount to pay, or zero/negative to use the remaining due
     */
    public void processFidelity(PosState state, BigDecimal amount) {
        LOGGER.info("Entering method processFidelity with state: " + state + ", amount: " + amount);
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (state.getRemaining().signum() <= 0) { LOGGER.info("Exiting method processFidelity"); return; }

            state.payment.clearPendingVoucher();

            // Reservation protocol (imfid spec §5): the lease is granted for
            // min(requested, remaining, burnableBase, availableBalance); no euro
            // moves before the fiscal confirmation. Refusals carry their exact
            // display message (closed nomenclature mapped by the service).
            com.intermarche.pos.ui.fidelity.FidelityService.BurnVerdict verdict =
                    fidelityService.reserveLease(state, amount, state.getRemaining());
            if (verdict.refusalMessage != null) {
                state.ticket.setError(verdict.refusalMessage);
                state.touch();
                LOGGER.info("Exiting method processFidelity");
                return;
            }
            BigDecimal amountToPay = verdict.grantedAmount;
            state.payment.addPayment("FIDELITY", amountToPay);
            state.touch();
            savePayment(state, "FIDELITY");
            hardwareService.displayMessage(String.format(i18n.get("display.fidelite"), df.format(amountToPay)));

            // Rule: fidelity = no drawer opening (virtual)

            checkCompletion(state);
        }
        LOGGER.info("Exiting method processFidelity");
    }

    /**
     * Registers a customer-credit settlement ({@code LC-07-09-01}).
     *
     * <p>Nothing is collected, so nothing is capped for change either: the caller —
     * {@link CreditClientService} — has already resolved the account, the ceiling and
     * the amount, and this method is what every other method's wrapper is, the thin
     * registration step. THE DRAWER STAYS SHUT: no money changes hands, and a drawer
     * that opened on a credit sale would be an invitation.
     *
     * @param state the current POS state
     * @param customer the account the debt is charged to
     * @param amount the amount to charge, already capped at the remaining due
     * @param overLimit true when a supervisor allowed the ceiling to be passed
     */
    public void processCredit(PosState state, com.intermarche.pos.domain.payment.AccountCustomer customer,
            BigDecimal amount, boolean overLimit) {
        LOGGER.info("Entering method processCredit with state: " + state + ", customer: " + customer + ", amount: " + amount + ", overLimit: " + overLimit);
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (customer == null || amount == null || amount.signum() <= 0) { LOGGER.info("Exiting method processCredit"); return; }
            if (state.getRemaining().signum() <= 0) { LOGGER.info("Exiting method processCredit"); return; }

            state.payment.clearPendingVoucher();

            BigDecimal amountToPay = amount.min(state.getRemaining());
            state.payment.addCreditPayment(amountToPay, customer.accountNumber,
                    customer.getDisplayName(), overLimit, creditTender());
            state.touch();
            savePayment(state, "CREDIT");
            hardwareService.displayMessage(
                    String.format(i18n.get("display.creditClient"), df.format(amountToPay)));

            // Rule: customer credit = no drawer opening (nothing physical).

            checkCompletion(state);
        }
        LOGGER.info("Exiting method processCredit");
    }

    /**
     * Registers a voucher payment, displays it and persists it.
     * <p>
     * The amount is assumed already capped at the remaining due by the caller.
     *
     * @param state the current POS state
     * @param label the voucher type label shown to the cashier
     * @param number the voucher number, or null when there is none
     * @param amount the paid amount
     */
    public void processVoucher(PosState state, String label, String number, BigDecimal amount) {
        LOGGER.info("Entering method processVoucher with state: " + state + ", label: " + label + ", number: " + number + ", amount: " + amount);
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (amount == null || amount.signum() <= 0) { LOGGER.info("Exiting method processVoucher"); return; }

            state.payment.addVoucherPayment(label, number, amount);
            state.touch();
            saveVoucherPayment(state);
            hardwareService.displayMessage(String.format("%-10s%s E", i18n.both("display.bon"), df.format(amount)));

            // Rule: voucher = no drawer opening (virtual)

            checkCompletion(state);
        }
        LOGGER.info("Exiting method processVoucher");
    }

    /**
     * Applies the cashier's end-of-transaction printing choice (LC-08-03-01 to
     * LC-08-03-06): the documents the {@link
     * com.intermarche.pos.ui.hardware.PrintPolicy} names for that choice are
     * sent to the printer at once.
     * <p>
     * Only the two documents that exist BEFORE the fiscal moment are printed
     * here — the sale ticket (printed from the still-open draft, so it is the
     * original and not a duplicata, exactly like the historical IMPRIMER
     * button) and the card receipt. The purchase vouchers are born at
     * validation, so {@link #finalizeTransaction} prints them under the very
     * same decision.
     * <p>
     * The choice is applied ONCE: a second call is ignored, so a reloaded
     * screen never prints a second original.
     *
     * @param state the current POS state
     * @param choice the cashier's choice, never null
     */
    public void applyPrintChoice(PosState state, com.intermarche.pos.ui.hardware.PrintChoice choice) {
        LOGGER.info("Entering method applyPrintChoice with state: " + state + ", choice: " + choice);
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (state.payment.printApplied) { LOGGER.info("Exiting method applyPrintChoice"); return; }
            Long ticketId = state.payment.ticketDbId;
            if (ticketId == null) { LOGGER.info("Exiting method applyPrintChoice"); return; }
            state.payment.printChoice = choice;
            com.intermarche.pos.domain.sale.Ticket ticket =
                    com.intermarche.pos.domain.sale.Ticket.findById(ticketId);
            com.intermarche.pos.ui.hardware.PrintPolicy.Decision decision =
                    printPolicy.decide(choice, ticket, state.payment.cardSignatureRequired);
            if (decision.saleTicket()) {
                // BO-03-03-36: the exit ticket REPLACES the sale receipt where
                // the store administers it — substitutive, not one ticket more.
                // It answers zero when it is not administered, and the receipt
                // prints exactly as before.
                if (ticketPrinterService.printExitTicket(ticketId) == 0) {
                    ticketPrinterService.printTicket(ticketId);
                }
            }
            if (decision.cardReceipt()) {
                ticketPrinterService.printCardReceipt(ticketId,
                        state.payment.cardSignatureRequired, null);
            }
            // BO-03-02-26, -31, -32 and -33: the slip of each tender used,
            // AFTER the receipt and not instead of it. A tender administered
            // "forced printing" comes out even when the receipt did not.
            ticketPrinterService.printTenderSlips(ticket, decision.saleTicket());
            state.payment.printApplied = true;
            state.touch();
        }
        LOGGER.info("Exiting method applyPrintChoice");
    }

    // --------------------------------------------------
    // 4. FINALIZATION
    // --------------------------------------------------

    /**
     * Closes the transaction: validates the ticket in database, remembers it
     * as the last closed ticket and clears the in-memory state.
     *
     * @param state the current POS state
     */
    public void finalizeTransaction(PosState state) {
        LOGGER.info("Entering method finalizeTransaction with state: " + state);
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            Long ticketId = state.payment.ticketDbId;

            if (ticketId != null) {
                ticketPersistenceService.validateTicket(ticketId);
                state.lastClosedTicketId = ticketId;
                LOGGER.info("Ticket validé et fermé en BDD ID: " + ticketId);
                // GB-01-07-05/06: freeze the engine offer messages on the ticket
                // so the receipt (and any reprint) states the very texts the
                // seller was shown. Empty in LOCAL/DEGRADED and when the engine
                // emitted none, in which case nothing is stored or printed.
                java.util.List<com.intermarche.pos.domain.sale.TicketMessage> ticketMessages =
                        new java.util.ArrayList<>();
                for (com.intermarche.pos.ui.valuation.ValuationService.EngineMessage engineMessage
                        : state.payment.valuationMessages) {
                    ticketMessages.add(new com.intermarche.pos.domain.sale.TicketMessage(
                            engineMessage.code, engineMessage.text, engineMessage.printMode));
                }
                ticketPersistenceService.storeEngineMessages(ticketId, ticketMessages);
                // Campaigns & budgets spec §3: a finalized sale valued by the
                // engine declares its consumption, derived from the retained
                // valuation (read here, before clearTicket, since the verbatim
                // offerCode lives only on the payment state). LOCAL/DEGRADED
                // applied no engine advantage, so nothing is declared. This only
                // enqueues a row — the delivery loop ships it, the sale never
                // waits on it.
                if ("ENGINE".equals(state.payment.valuationStatus)) {
                    com.intermarche.pos.domain.sale.Ticket valued =
                            com.intermarche.pos.domain.sale.Ticket.findById(ticketId);
                    if (valued != null) {
                        consumptionService.enqueueSale(valued.ticketNumber,
                                valued.store == null ? null : valued.store.code,
                                valued.closingDate, state.payment.valuationJson);
                    }
                }
                // Loyalty fiscal sequence (imfid spec §5.2 + §6): confirm the
                // lease (410 tolerated — the ingestion is authoritative), then
                // enqueue the ticket-closed event with the verbatim couple. The
                // outbox drains it; imfid recomputes and the recalcul fait foi.
                if (state.fidelity.active) {
                    java.time.LocalDate fiscalDate = java.time.LocalDate.now();
                    Long reservationId = fidelityService.confirmLease(state, fiscalDate);
                    com.intermarche.pos.domain.sale.Ticket closed =
                            com.intermarche.pos.domain.sale.Ticket.findById(ticketId);
                    if (closed != null) {
                        String ticketRef = fiscalDate.getYear() + "-" + closed.ticketNumber;
                        String payload = fidelityService.buildTicketClosedPayload(
                                state, ticketRef, state.fidelity.label, fiscalDate, reservationId);
                        if (payload != null) {
                            fidEventOutboxService.enqueue(
                                    com.intermarche.pos.domain.sync.FidEvent.EventType.TICKET_CLOSED, payload);
                        }
                    }
                }
                // BO-03-03-25/-29/-30/-31: freeze what the loyalty programme
                // said about this sale BEFORE the rendering below reads it. The
                // projection lives in the register's state and dies with the
                // sale; frozen on the row, a duplicata — here or on the store
                // node — states what the customer was shown on the day.
                freezeFidelity(state, ticketId);
                // LC-08-02-02: freeze the ticket AS PRINTED, here and nowhere
                // else — this is the only moment the loyalty section of the
                // rendering still exists, and the only place that knows what
                // this register's own printer produces. The synchronization
                // carries it up to the store node from the ticket row.
                com.intermarche.pos.domain.sale.Ticket sold =
                        com.intermarche.pos.domain.sale.Ticket.findById(ticketId);
                if (sold != null) {
                    ticketPersistenceService.storeFormattedContent(ticketId,
                            ticketPrinterService.renderTicket(sold, false, 0));
                }
                // What this closing prints (LC-08-03). With conditional
                // printing off the decision names every document, which is the
                // historical behaviour: the vouchers come out and nothing else
                // is printed on its own.
                com.intermarche.pos.domain.sale.Ticket printed =
                        com.intermarche.pos.domain.sale.Ticket.findById(ticketId);
                com.intermarche.pos.ui.hardware.PrintPolicy.Decision decision =
                        printPolicy.decide(state.payment.printChoice, printed,
                                state.payment.cardSignatureRequired);
                // A cashier who closed without touching the choice buttons
                // still gets the forced documents (GLC ticket, signed card
                // slip) — the choice defaults to "tous les tickets".
                if (printPolicy.isConditionalEnabled() && !state.payment.printApplied) {
                    if (decision.saleTicket()) {
                        ticketPrinterService.printTicket(ticketId);
                    }
                    if (decision.cardReceipt()) {
                        ticketPrinterService.printCardReceipt(ticketId,
                                state.payment.cardSignatureRequired, null);
                    }
                    state.payment.printApplied = true;
                }
                // LC-02-08-05: the articles the customer does not carry out get
                // their handover slip, additional to the receipt. After the
                // receipt, because it states a PAID sale — the desk hands goods
                // over against a sale that is over.
                if (printPolicy.isAssociatedDocumentPrinted(
                        com.intermarche.pos.ui.hardware.PrintPolicy.DOCUMENT_COLLECTION)) {
                    ticketPrinterService.printCollectionVoucher(ticketId);
                }
                // BO-03-03-10: a settlement taken on the loyalty balance gets
                // its own slip, additional to the receipt and after it — the
                // proof the customer comes back with on a disputed cagnotte
                // débit. A sale that did not touch the balance prints nothing.
                if (printPolicy.isAssociatedDocumentPrinted(
                        com.intermarche.pos.ui.hardware.PrintPolicy.DOCUMENT_LOYALTY)) {
                    ticketPrinterService.printLoyaltyReceipt(ticketId);
                }
                // LC-08-04-16: the settlement may call for a document of its own —
                // typically an invoice on a sale paid by customer credit. It is
                // emitted here, after the receipt, because it states a CLOSED sale
                // and because it is printed "en plus du ticket de caisse".
                invoiceService.autoPrint(ticketId);
                // Gift cards issued by this sale get their printed voucher —
                // the customer's proof, right after the fiscal moment (phase:
                // credit notes & gift cards).
                if (decision.voucher()) {
                    java.util.List<com.intermarche.pos.domain.payment.StoredValue> issued =
                            com.intermarche.pos.domain.payment.StoredValue
                                    .find("issuingTicketId", ticketId).list();
                    for (com.intermarche.pos.domain.payment.StoredValue card : issued) {
                        ticketPrinterService.printGiftCardVoucher(card.number, card.initialAmount);
                    }
                }
                // BO-03-02-16: the change a voucher-change tender owed becomes a
                // numbered credit note HERE — after the fiscal moment, like every
                // other instrument this sale creates. The gift-card sweep above
                // runs first and on its own rows, so the note is printed by the
                // branch that knows its amount rather than swept up with them.
                issueChangeCreditNote(state, ticketId);
            }

            hardwareService.displayMessage(i18n.both("display.merciBientot"));
            state.clearTicket();
        }
        LOGGER.info("Exiting method finalizeTransaction");
    }

    // --------------------------------------------------
    // Private helpers
    // --------------------------------------------------

    /**
     * Freezes on the closed ticket what the loyalty programme said about this
     * sale: the displayed earn, its advantage lines, the balance read when the
     * card was attached and whether the service answered at all.
     *
     * <p>Copied rather than referenced: {@code PosState} is cleared a few lines
     * later and the projection is not persisted anywhere else, so this is the
     * last moment the figures exist. A sale made without a card still writes —
     * it writes nothing but the availability flag, which is exactly what a
     * receipt printed for a non-holder needs to state (BO-03-03-30).
     *
     * @param state the current POS state
     * @param ticketId the database id of the closed ticket
     */
    private void freezeFidelity(PosState state, Long ticketId) {
        java.util.List<com.intermarche.pos.domain.sale.TicketFidelityLine> lines =
                new java.util.ArrayList<>();
        for (com.intermarche.pos.ui.fidelity.FidelityState.EarnLine line
                : state.fidelity.earnEntries) {
            lines.add(new com.intermarche.pos.domain.sale.TicketFidelityLine(
                    line.ruleCode, line.label, line.amount,
                    line.advantageType, line.advantageTypeLabel, line.advantageTypeOrder,
                    line.advantageCategory, line.advantageCategoryLabel));
        }
        ticketPersistenceService.storeFidelity(ticketId, state.fidelity.earnTotal,
                state.fidelity.availableBalance, fidelityService.isUnavailable(), lines,
                state.fidelity.projectedAfterEarn, state.fidelity.balanceAfter,
                state.fidelity.anonymousProjection);
    }

    /**
     * Persists the last registered payment entry on the draft ticket.
     *
     * @param state the current POS state
     * @param methodKey the payment method key (for logging context)
     */
    private void savePayment(PosState state, String methodKey) {
        if (state.trainingMode) return; // nothing persisted in training
        if (state.payment.ticketDbId == null) {
            LOGGER.error("Impossible de sauvegarder le paiement : aucun Ticket ID");
            return;
        }
        PaymentState.PaymentEntry lastEntry = state.payment.payments.get(state.payment.payments.size() - 1);
        ticketPersistenceService.addPaymentToTicket(state.payment.ticketDbId, lastEntry);
    }

    /**
     * The tender key customer credit is registered under ({@code BO-10-04-09}).
     *
     * <p>An administered key the register cannot BUILD is refused here rather
     * than at the database: the key picks the payment class the ticket line is
     * persisted as, so an unknown one would not produce a differently-labelled
     * settlement, it would lose the settlement altogether.
     *
     * @return the administered key when the register knows it, CREDIT otherwise
     */
    private String creditTender() {
        String administered = posSettingsService.customerCreditTender();
        if (com.intermarche.pos.domain.payment.PaymentTypes.forKey(administered) == null) {
            LOGGER.warnf("Moyen de paiement crédit inconnu (%s), CREDIT appliqué", administered);
            return "CREDIT";
        }
        return administered;
    }

    /**
     * Persists the last registered payment as a voucher payment.
     *
     * @param state the current POS state
     */
    private void saveVoucherPayment(PosState state) {
        if (state.trainingMode) return; // nothing persisted in training
        if (state.payment.ticketDbId == null) {
            LOGGER.error("Impossible de sauvegarder le bon : aucun Ticket ID");
            return;
        }
        PaymentState.PaymentEntry lastEntry = state.payment.payments.get(state.payment.payments.size() - 1);
        ticketPersistenceService.addPaymentToTicket(state.payment.ticketDbId, lastEntry);
    }

    /**
     * Marks the transaction complete when the remaining due (rounded to
     * 2 decimals) is zero or below.
     *
     * @param state the current POS state
     */
    private void checkCompletion(PosState state) {
        BigDecimal remaining = state.getRemaining();
        // EXP-38: a mixed ticket carries a SIGNED total. A positive (or zero) total
        // completes when the due is covered (remaining <= 0, the overpay is change).
        // A NEGATIVE total is a net refund the register OWES the customer: it is
        // complete only once the refund has actually been paid OUT (remaining == 0)
        // — otherwise the historical "<= 0" would mark it complete the instant the
        // pay screen opened, closing it without giving the money back.
        boolean complete = state.ticket.totalAmount.signum() >= 0
                ? remaining.signum() <= 0
                : remaining.signum() == 0;
        if (complete) {
            state.payment.transactionComplete = true;
            state.touch();
        }
    }

    /**
     * Settles the net refund of a mixed ticket whose signed total is NEGATIVE
     * (EXP-38): the returns outweigh the purchases, so the register pays the
     * difference back to the customer through the chosen tender. Records the
     * payout as a negative settlement on the SAME ticket — one document, signed
     * total — bringing the remaining to zero and completing the transaction.
     * Guarded to the refund direction: it does nothing when money is still due
     * (a positive remaining is collected through the ordinary tender methods).
     *
     * @param state the current POS state
     * @param methodKey the tender the refund is paid out in (CASH, CARD...)
     * @return true when the refund was settled, false when nothing was owed
     */
    public boolean processNetRefund(PosState state, String methodKey) {
        LOGGER.info("Entering method processNetRefund with methodKey: " + methodKey);
        synchronized (state) {
            BigDecimal remaining = state.getRemaining();
            if (remaining.signum() >= 0) {
                LOGGER.info("Exiting method processNetRefund");
                return false;
            }
            // A negative settlement: paying the customer back brings paidAmount
            // down to the (negative) total, i.e. remaining to zero.
            state.payment.addPayment(methodKey, remaining);
            savePayment(state, methodKey);
            hardwareService.displayMessage(String.format(i18n.get("display.remboursement"), df.format(remaining.abs())));
            checkCompletion(state);
            state.touch();
            LOGGER.info("Exiting method processNetRefund");
            return true;
        }
    }
}
