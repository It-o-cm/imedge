package com.intermarche.pos.service;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.payment.TicketPayment;
import com.intermarche.pos.domain.payment.VoucherPayment;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketFidelityLine;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.sale.VatBreakdown;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.domain.session.TicketCounter;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.PaymentState;
import com.intermarche.pos.ui.ticket.TicketState;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Persists the in-memory ticket into the register's database.
 * <p>
 * Phase 0 lot 2: the draft is persisted from the first article and kept in
 * sync after every cart mutation ({@link #syncDraft(PosState)}), so a register
 * restart never loses the cart. Existing lines are reconciled by their stable
 * uid (in-place updates for quantity merges and price modifications, orphan
 * removal for cancelled lines). An emptied or abandoned cart cancels the
 * draft, documenting the gap in the per-terminal number sequence.
 * <p>
 * Phase 1: each line carries the real VAT rate captured at sale time, the
 * ticket totals come from the per-rate ventilation ({@link VatBreakdown}),
 * and ticket validation chains the ticket to the previous closed ticket of
 * the same register (SHA-256 signature, perpetual grand total) under the
 * counter row lock. Lifecycle transitions are recorded in the technical
 * event journal.
 * <p>
 * {@code syncDraft} is the SINGLE WRITE FUNNEL of the sale: every cart
 * mutation, the payment entry, the parking, the digital receipt and the
 * store sync all flow through the draft it maintains, and
 * {@code ticketDbId} is the pivot everything keys on. This is why the
 * phase 6 training mode needs exactly ONE guard — syncDraft returning null
 * neutralizes the whole fiscal surface downstream (no number burnt, no
 * chain, no outbox row, no digital receipt, nothing to recover), with no
 * scattered ifs to maintain.
 * <p>
 * {@code validateTicket} is the FISCAL MOMENT, entirely under the counter
 * row lock: status flip, closing date, signature chained over
 * number|terminal|date|totals|previous, perpetual grand total incremented
 * and snapshotted on the ticket, journal entry and outbox row — one
 * transaction, atomic per register. The signature input order is part of
 * the fiscal contract: changing it breaks verifiability of the whole
 * history.
 */
@ApplicationScoped
public class TicketPersistenceService {

    private static final Logger LOGGER = Logger.getLogger(TicketPersistenceService.class);

    @Inject
    Instance<TicketPayment.Factory> factoryInstances;

    @Inject
    TicketNumberService ticketNumberService;

    @Inject
    CashSessionService cashSessionService;

    @Inject
    TechnicalEventService technicalEventService;

    @Inject
    SyncOutboxService syncOutboxService;

    /**
     * Reverses a ticket's campaign consumption when it is cancelled (campaigns
     * &amp; budgets spec §3).
     */
    @Inject
    com.intermarche.pos.service.sync.ConsumptionService consumptionService;

    /** Payment factories indexed by their method key. */
    private final Map<String, TicketPayment.Factory> paymentFactories = new HashMap<>();

    /**
     * Indexes the discovered payment factories by their method key.
     */
    @PostConstruct
    public void init() {
        LOGGER.info("Entering method init");
        for (TicketPayment.Factory factory : factoryInstances) {
            paymentFactories.put(factory.getKey(), factory);
        }
        LOGGER.info("Exiting method init");
    }

    // --------------------------------------------------
    // 1. DRAFT SYNCHRONIZATION (called after every cart mutation)
    // --------------------------------------------------

    /**
     * Synchronizes the in-memory cart with its database draft.
     * <ul>
     *   <li>No draft yet and a non-empty cart: creates the draft (reserving the
     *       next sequential ticket number) and stores its id in
     *       {@code state.payment.ticketDbId} — this method is the single owner
     *       of that field on the creation path.</li>
     *   <li>Existing draft: reconciles lines by uid (update in place, add new,
     *       orphan-remove cancelled ones) and refreshes totals and fidelity.</li>
     *   <li>Existing draft and an emptied cart: cancels the draft and clears
     *       {@code state.payment.ticketDbId}.</li>
     * </ul>
     *
     * @param state the current POS state
     * @return the database id of the draft, or null when no draft exists anymore
     */
    @Transactional
    public Long syncDraft(PosState state) {
        LOGGER.info("Entering method syncDraft with state: " + state);
        // Training mode: nothing fiscal ever reaches the database
        if (state.trainingMode) { LOGGER.info("Exiting method syncDraft"); return null; }

        Long ticketId = state.payment.ticketDbId;

        // Emptied cart: cancel the draft, if any
        if (state.ticket.items.isEmpty()) {
            if (ticketId != null) {
                doCancelDraft(ticketId);
                state.payment.ticketDbId = null;
            }
            LOGGER.info("Exiting method syncDraft");
            return null;
        }

        if (ticketId == null) {
            Long newId = createDraft(state);
            state.payment.ticketDbId = newId;
            LOGGER.info("Exiting method syncDraft");
            return newId;
        }

        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null || ticket.status != Ticket.TicketStatus.OPEN) {
            LOGGER.warnf("Draft %d introuvable ou non OPEN, resynchronisation par création", ticketId);
            Long newId = createDraft(state);
            state.payment.ticketDbId = newId;
            LOGGER.info("Exiting method syncDraft");
            return newId;
        }

        reconcileLines(ticket, state);
        applyHeaderAndTotals(ticket, state);
        ticket.persist();
        LOGGER.info("Exiting method syncDraft");
        return ticket.id;
    }

    /**
     * Creates the database draft from the current cart, reserving the next
     * sequential ticket number of this terminal.
     *
     * @param state the current POS state (non-empty cart)
     * @return the database id of the created draft, or null when the store or
     *         the cashier cannot be resolved
     */
    private Long createDraft(PosState state) {
        Store store = Store.findAll().firstResult();
        Employee cashier = (state.auth.operatorId != null) ? Employee.findById(state.auth.operatorId) : null;
        if (store == null || cashier == null) {
            LOGGER.error("Impossible de créer le ticket (Store ou Cashier manquant)");
            return null;
        }
        CashSession session = cashSessionService.getOpenSession();
        if (session == null) {
            LOGGER.error("Impossible de créer le ticket : aucune session de caisse ouverte");
            return null;
        }

        Ticket ticket = new Ticket();
        ticket.ticketNumber = ticketNumberService.nextTicketNumber();
        ticket.terminalId = ticketNumberService.getTerminalId();
        ticket.creationDate = LocalDateTime.now();
        ticket.status = Ticket.TicketStatus.OPEN;
        // Access key of the online digital receipt (short, printable, unguessable)
        ticket.digitalKey = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ticket.store = store;
        ticket.cashier = cashier;
        ticket.session = session;
        for (TicketState.TicketItem item : state.ticket.items) {
            ticket.addLine(mapLine(item, ticket.lines.size() + 1));
        }
        applyHeaderAndTotals(ticket, state);
        ticket.persist();
        LOGGER.infof("Ticket créé en BDD (Draft) ID: %d (%s)", ticket.id, ticket.ticketNumber);
        return ticket.id;
    }

    /**
     * Reconciles the draft lines with the in-memory cart by their stable uid:
     * updates matched lines in place, removes vanished ones (orphan removal),
     * appends new ones.
     *
     * @param ticket the managed draft ticket
     * @param state the current POS state
     */
    private void reconcileLines(Ticket ticket, PosState state) {
        Set<String> memoryUids = new HashSet<>();
        for (TicketState.TicketItem item : state.ticket.items) {
            memoryUids.add(item.uid);
        }

        // Remove lines that no longer exist in memory, EXCEPT the ones marked
        // cancelled (campaign lot C4, BO-04-01-16): a cancelled line has left
        // the live cart on purpose but is kept as a conserved witness, so the
        // orphan-removal that used to drop it now steps over it. Every other
        // vanished line (a merge that emptied a uid, a legacy null-uid line) is
        // still dropped.
        ticket.lines.removeIf(line -> line.lineUid == null
                || (!memoryUids.contains(line.lineUid) && !line.cancelled));

        Map<String, TicketLine> existingByUid = new HashMap<>();
        for (TicketLine line : ticket.lines) {
            existingByUid.put(line.lineUid, line);
        }

        int lineNumber = 1;
        for (TicketState.TicketItem item : state.ticket.items) {
            TicketLine line = existingByUid.get(item.uid);
            if (line == null) {
                ticket.addLine(mapLine(item, lineNumber));
            } else {
                line.lineNumber = lineNumber;
                line.productLabel = item.label;
                line.quantity = item.quantity;
                line.unitPrice = item.unitPrice;
        line.modifierLabel = item.modifierLabel;
        line.originalUnitPrice = item.modifierLabel != null ? item.originalUnitPrice : null;
        line.modifierType = item.modifierType;
        line.modifierValue = item.modifierValue;
                line.vatRate = item.vatRate;
                line.modifierLabel = item.modifierLabel;
                line.originalUnitPrice = item.modifierLabel != null ? item.originalUnitPrice : null;
                line.modifierType = item.modifierType;
                line.modifierValue = item.modifierValue;
                line.totalPrice = item.getTotalPrice().setScale(2, RoundingMode.HALF_UP);
                // EXP-43: the SOUS-TOTAL marker is placed AFTER a line already exists,
                // so it is refreshed on update, not only at line creation.
                line.subtotalBoundary = item.subtotalBoundary;
                line.returnLine = item.returnLine;
                line.embeddedWeight = item.embeddedWeight;
                line.embeddedUnitPrice = item.embeddedUnitPrice;
            }
            lineNumber++;
        }
        // The conserved witnesses (lot C4) are NOT part of the sold sequence:
        // they would otherwise keep the number they had when they were rung,
        // and a live line renumbered by this very loop would end up sharing it.
        // They are numbered after the sold lines, in their existing order, so
        // no two lines of one ticket ever carry the same number.
        for (TicketLine line : ticket.lines) {
            if (line.cancelled) {
                line.lineNumber = lineNumber;
                lineNumber++;
            }
        }
    }

    /**
     * Refreshes the header fields and financial totals of the draft from the
     * in-memory state (fidelity card included, so a card scanned mid-cart is
     * picked up by the next synchronization). Totals come from the per-rate
     * VAT ventilation of the lines — the same rule the in-memory total and
     * the printed ticket follow.
     *
     * @param ticket the managed draft ticket
     * @param state the current POS state
     */
    private void applyHeaderAndTotals(Ticket ticket, PosState state) {
        ticket.fidelityCard = state.fidelity.active ? state.fidelity.label : null;
        ticket.globalDiscountType = state.ticket.globalDiscountType;
        ticket.globalDiscountValue = state.ticket.globalDiscountValue;
        ticket.globalDiscountApplied = state.ticket.globalDiscountApplied;
        ticket.itemCount = state.ticket.items.size();

        VatBreakdown breakdown = new VatBreakdown();
        for (TicketState.TicketItem item : state.ticket.items) {
            breakdown.add(item.vatRate, item.getTotalPrice().setScale(2, RoundingMode.HALF_UP));
        }
        ticket.totalIncludingTax = breakdown.getTotalIncludingTax();
        ticket.totalExcludingTax = breakdown.getTotalExcludingTax();
        ticket.totalVat = breakdown.getTotalVat();
    }

    // --------------------------------------------------
    // 2. PAYMENTS
    // --------------------------------------------------

    /**
     * Adds a payment to the existing draft ticket.
     *
     * @param ticketId the database id of the draft ticket
     * @param entry the in-memory payment entry to persist
     */
    @Transactional
    public void addPaymentToTicket(Long ticketId, PaymentState.PaymentEntry entry) {
        LOGGER.info("Entering method addPaymentToTicket with ticketId: " + ticketId + ", entry: " + entry);
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) throw new IllegalArgumentException("Ticket introuvable : " + ticketId);
        BigDecimal amount = entry.amount;
        BigDecimal tendered = entry.tenderedAmount;
        String factoryKey = entry.isVoucher() ? "VOUCHER" : entry.method;
        TicketPayment.Factory factory = paymentFactories.get(factoryKey);
        if (factory == null) throw new IllegalArgumentException("Mode inconnu : " + factoryKey);
        TicketPayment payment = factory.create(amount, tendered);
        if (payment instanceof VoucherPayment voucherPayment) {
            voucherPayment.voucherLabel = entry.method;
            voucherPayment.voucherNumber = entry.voucherNumber;
        }
        if (payment instanceof com.intermarche.pos.domain.payment.CardPayment cardPayment) {
            cardPayment.authorizationNumber = entry.authorizationNumber;
            cardPayment.degradedMode = entry.degradedMode;
            cardPayment.cardEntryMode = entry.cardEntryMode;
        }
        if (payment instanceof com.intermarche.pos.domain.payment.ChequePayment chequePayment) {
            chequePayment.magneticLine = entry.magneticLine;
        }
        if (payment instanceof com.intermarche.pos.domain.payment.BackupPayment backupPayment) {
            backupPayment.methodLabel = entry.backupMethodLabel;
            backupPayment.transactionNumber = entry.backupTransaction;
            backupPayment.manual = entry.backupManual;
        }
        if (payment instanceof com.intermarche.pos.domain.payment.ForeignCurrencyPayment currencyPayment) {
            currencyPayment.currencyCode = entry.currencyCode;
            currencyPayment.foreignAmount = entry.currencyAmount;
            currencyPayment.exchangeRate = entry.currencyRate;
        }
        if (payment instanceof com.intermarche.pos.domain.payment.CreditPayment creditPayment) {
            creditPayment.accountNumber = entry.creditAccountNumber;
            creditPayment.accountName = entry.creditAccountName;
            creditPayment.overLimit = entry.creditOverLimit;
        }
        payment.paymentIndex = ticket.payments.size() + 1;
        ticket.addPayment(payment);
        ticket.persist();
        LOGGER.info("Exiting method addPaymentToTicket");
    }

    /**
     * Removes every payment persisted on the draft (payment-cancellation
     * coherence: the in-memory list is cleared by the caller, the database
     * must follow, otherwise a later recovery would resurrect ghost payments).
     *
     * @param ticketId the database id of the draft ticket
     */
    @Transactional
    public void removePaymentsFromTicket(Long ticketId) {
        LOGGER.info("Entering method removePaymentsFromTicket with ticketId: " + ticketId);
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) { LOGGER.info("Exiting method removePaymentsFromTicket"); return; }
        int removed = ticket.payments.size();
        ticket.payments.clear();
        ticket.persist();
        if (removed > 0) {
            technicalEventService.log(TechnicalEvent.EventType.PAYMENTS_CLEARED,
                    ticket.ticketNumber + " (" + removed + ")");
        }
        LOGGER.info("Exiting method removePaymentsFromTicket");
    }

    /**
     * Marks a draft line as cancelled instead of letting it be dropped
     * (campaign lot C4, BO-04-01-16). Called on the article-cancellation
     * gesture BEFORE the line leaves the in-memory cart, so the subsequent
     * draft reconciliation keeps the now-marked line (see
     * {@link #reconcileLines}) as a conserved witness carrying its author and
     * timestamp. A no-op when the ticket or the line cannot be resolved (the
     * cancellation still empties the cart in memory; the witness is best-effort
     * and never blocks the sale). The marking never touches the totals — they
     * are recomputed from the live cart, which no longer holds the line.
     *
     * @param ticketId the database id of the draft ticket, or null (no draft)
     * @param lineUid the stable uid of the line being cancelled
     * @param operatorBadgeId the badge of the operator performing the cancellation, or null
     */
    @Transactional
    public void markLineCancelled(Long ticketId, String lineUid, String operatorBadgeId) {
        LOGGER.info("Entering method markLineCancelled with ticketId: " + ticketId + ", lineUid: " + lineUid + ", operatorBadgeId: " + operatorBadgeId);
        if (ticketId == null || lineUid == null) { LOGGER.info("Exiting method markLineCancelled"); return; }
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) { LOGGER.info("Exiting method markLineCancelled"); return; }
        int lastLineNumber = 0;
        for (TicketLine line : ticket.lines) {
            if (line.lineNumber > lastLineNumber) { lastLineNumber = line.lineNumber; }
        }
        for (TicketLine line : ticket.lines) {
            if (lineUid.equals(line.lineUid) && !line.cancelled) {
                line.cancelled = true;
                line.cancellationDate = LocalDateTime.now();
                line.cancelledBy = operatorBadgeId;
                // Last-line vs. mid-ticket cancellation (R10): the fraud indicator
                // rests on whether the cancelled line was the ticket's last at the
                // moment of the gesture, so compare its position to the greatest
                // line number carried then.
                line.cancelledLast = line.lineNumber == lastLineNumber;
                ticket.persist();
                LOGGER.info("Exiting method markLineCancelled");
                return;
            }
        }
        LOGGER.info("Exiting method markLineCancelled");
    }

    // --------------------------------------------------
    // 3. FINALIZATION / CANCELLATION
    // --------------------------------------------------

    /**
     * Freezes the printed form of a closed ticket (LC-08-02-02).
     *
     * <p>Written by the register that made the sale, right after the fiscal moment,
     * because it is the only place that can render what its own printer produced. The
     * store synchronization carries it up from here.
     *
     * @param ticketId the database id of the closed ticket
     * @param content the ticket as printed, or null to leave the field alone
     */
    @Transactional
    public void storeFormattedContent(Long ticketId, String content) {
        LOGGER.info("Entering method storeFormattedContent with ticketId: " + ticketId + ", content: " + content);
        if (ticketId == null || content == null || content.isBlank()) {
            LOGGER.info("Exiting method storeFormattedContent");
            return;
        }
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) {
            LOGGER.info("Exiting method storeFormattedContent");
            return;
        }
        ticket.formattedContent = content;
        ticket.persist();
        LOGGER.info("Exiting method storeFormattedContent");
    }

    /**
     * Freezes on the closed ticket what the loyalty programme said about this
     * sale (BO-03-03-25, -29, -30, -31).
     *
     * <p>Written from the register's live projection, which dies with the sale:
     * this is the last moment the figures exist, and the row is what a
     * duplicata — here or on the store node — restates afterwards. A sale made
     * without a card still writes: the availability flag alone is what a
     * receipt printed for a non-holder needs.
     *
     * @param ticketId the database id of the closed ticket, or null
     * @param earnTotal the earn the sale displayed, or null when it displayed none
     * @param availableBalance the balance read when the card was attached, or null
     * @param unavailable whether the loyalty service was unreachable during the sale
     * @param lines the advantage lines the sale displayed, never null
     */
    @Transactional
    public void storeFidelity(Long ticketId, BigDecimal earnTotal, BigDecimal availableBalance,
            boolean unavailable, List<TicketFidelityLine> lines,
            BigDecimal projectedAfterEarn, BigDecimal balanceAfter, boolean anonymousProjection) {
        LOGGER.info("Entering method storeFidelity with ticketId: " + ticketId + ", earnTotal: " + earnTotal + ", availableBalance: " + availableBalance + ", unavailable: " + unavailable + ", lines: " + lines + ", projectedAfterEarn: " + projectedAfterEarn + ", balanceAfter: " + balanceAfter + ", anonymousProjection: " + anonymousProjection);
        if (ticketId == null) {
            LOGGER.info("Exiting method storeFidelity");
            return;
        }
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) {
            LOGGER.info("Exiting method storeFidelity");
            return;
        }
        ticket.fidelityEarnTotal = earnTotal;
        ticket.fidelityAvailableBalance = availableBalance;
        ticket.fidelityProjectedAfterEarn = projectedAfterEarn;
        ticket.fidelityBalanceAfter = balanceAfter;
        ticket.fidelityAnonymousProjection = anonymousProjection;
        ticket.fidelityUnavailable = unavailable;
        if (ticket.fidelityLines == null) {
            ticket.fidelityLines = new java.util.ArrayList<>();
        }
        ticket.fidelityLines.clear();
        ticket.fidelityLines.addAll(lines);
        ticket.persist();
        LOGGER.info("Exiting method storeFidelity");
    }

    /**
     * Freezes the engine offer messages on a closed ticket (GB-01-07-05/06), so
     * the receipt and any reprint state the very texts the seller was shown. An
     * empty list — a LOCAL/DEGRADED sale, or an engine that emitted none — leaves
     * the ticket with no message rows and nothing printed.
     *
     * @param ticketId the database id of the closed ticket, or null
     * @param messages the engine messages retained at payment, never null
     */
    @Transactional
    public void storeEngineMessages(Long ticketId,
            List<com.intermarche.pos.domain.sale.TicketMessage> messages) {
        LOGGER.info("Entering method storeEngineMessages with ticketId: " + ticketId);
        if (ticketId == null || messages == null || messages.isEmpty()) {
            LOGGER.info("Exiting method storeEngineMessages: nothing to store");
            return;
        }
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) {
            LOGGER.info("Exiting method storeEngineMessages: ticket gone");
            return;
        }
        if (ticket.messages == null) {
            ticket.messages = new java.util.ArrayList<>();
        }
        ticket.messages.clear();
        ticket.messages.addAll(messages);
        ticket.persist();
        LOGGER.info("Exiting method storeEngineMessages");
    }

    /**
     * Issues the CREDIT NOTE a sale owes as change, when the tender that
     * overpaid is administered to give its change back in vouchers
     * (BO-03-02-16).
     *
     * <p>Called after the fiscal moment and not before, exactly like the gift
     * cards sold on the ticket: a sale that never closed owes no change, and an
     * abandoned sale must not leave a numbered balance behind it.
     *
     * @param ticketId the database id of the closed ticket
     * @param amount the change to hand over as a credit note
     * @return the registry number of the issued note, or null when nothing was owed
     */
    @Transactional
    public String issueChangeCreditNote(Long ticketId, java.math.BigDecimal amount) {
        LOGGER.info("Entering method issueChangeCreditNote with ticketId: " + ticketId + ", amount: " + amount);
        if (ticketId == null || amount == null || amount.signum() <= 0) {
            LOGGER.info("Exiting method issueChangeCreditNote");
            return null;
        }
        com.intermarche.pos.domain.payment.StoredValue note =
                new com.intermarche.pos.domain.payment.StoredValue();
        note.kind = com.intermarche.pos.domain.payment.StoredValue.Kind.CREDIT_NOTE;
        note.initialAmount = amount;
        note.balance = amount;
        note.issuedAt = LocalDateTime.now();
        note.issuingTicketId = ticketId;
        // Same NOT NULL, 20-character column as every other instrument: a SHORT
        // unique placeholder carries the INSERT, the definitive number is derived
        // from the generated id right after.
        note.number = "T" + Long.toUnsignedString(
                java.util.UUID.randomUUID().getMostSignificantBits(), 36);
        note.persistAndFlush();
        note.number = com.intermarche.pos.domain.payment.StoredValue.CREDIT_NOTE_PREFIX
                + String.format("%012d", note.id);
        LOGGER.info("Exiting method issueChangeCreditNote");
        return note.number;
    }

    /**
     * Marks the ticket as closed and chains it to the previous closed ticket
     * of the same register: under the counter row lock (serializing closings
     * per terminal), the closing date is set, the SHA-256 signature is
     * computed over the fiscal fields and the previous signature, and the
     * perpetual grand total is advanced. The closure is recorded in the
     * technical event journal.
     *
     * @param ticketId the database id of the ticket to close
     */
    @Transactional
    public void validateTicket(Long ticketId) {
        LOGGER.info("Entering method validateTicket with ticketId: " + ticketId);
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) {
            LOGGER.info("Exiting method validateTicket");
            return;
        }
        TicketCounter counter = ticketNumberService.lockCounter(ticket.terminalId);

        ticket.status = Ticket.TicketStatus.CLOSED;
        ticket.closingDate = LocalDateTime.now();
        ticket.previousSignature = (counter.lastSignature != null) ? counter.lastSignature : "GENESIS";
        ticket.signature = sha256Hex(String.join("|",
                ticket.ticketNumber,
                ticket.terminalId,
                ticket.closingDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                ticket.totalIncludingTax.toPlainString(),
                ticket.totalVat.toPlainString(),
                ticket.previousSignature));

        counter.grandTotal = counter.grandTotal.add(ticket.totalIncludingTax);
        counter.lastSignature = ticket.signature;

        // --- Stored-value settlement, INSIDE the fiscal transaction
        //     (phase: credit notes & gift cards) ---
        // (1) Debit the redeemed registry instruments: the balance only
        //     moves HERE — a cancelled payment or a crash before this point
        //     never burns stored value.
        for (com.intermarche.pos.domain.payment.TicketPayment payment : ticket.payments) {
            if (payment instanceof com.intermarche.pos.domain.payment.VoucherPayment voucher
                    && com.intermarche.pos.domain.payment.StoredValue.isRegistryNumber(voucher.voucherNumber)) {
                com.intermarche.pos.domain.payment.StoredValue instrument =
                        com.intermarche.pos.domain.payment.StoredValue.findByNumber(voucher.voucherNumber);
                if (instrument != null) {
                    instrument.balance = instrument.balance.subtract(payment.amount).max(java.math.BigDecimal.ZERO);
                    instrument.lastRedeemedTicketId = ticket.id;
                    if (instrument.balance.signum() == 0) {
                        instrument.status = com.intermarche.pos.domain.payment.StoredValue.Status.EXHAUSTED;
                        instrument.exhaustedAt = LocalDateTime.now();
                    }
                }
            }
        }
        // (2) Issue the gift cards sold on this ticket: one ACTIVE registry
        //     instrument per unit, numbered from its own row id. Issued at
        //     the fiscal moment only — an abandoned cart never creates value.
        for (com.intermarche.pos.domain.sale.TicketLine line : ticket.lines) {
            // A cancelled line (lot C4) never sold anything: it must not issue a
            // gift card, exactly as it contributes nothing to the totals.
            if (line.cancelled) continue;
            if (line.ean == null) continue;
            com.intermarche.pos.domain.catalog.Product product =
                    com.intermarche.pos.domain.catalog.Product.find("ean", line.ean).firstResult();
            if (product == null || product.giftCardAmount == null) continue;
            int units = line.quantity.intValue();
            for (int i = 0; i < units; i++) {
                com.intermarche.pos.domain.payment.StoredValue card = new com.intermarche.pos.domain.payment.StoredValue();
                card.kind = com.intermarche.pos.domain.payment.StoredValue.Kind.GIFT_CARD;
                card.initialAmount = product.giftCardAmount;
                card.balance = product.giftCardAmount;
                card.issuedAt = LocalDateTime.now();
                card.issuingTicketId = ticket.id;
                // Same NOT NULL, 20-character column as the credit note: a
                // SHORT unique placeholder carries the INSERT, the definitive
                // number is derived from the generated id right after.
                card.number = "T" + Long.toUnsignedString(
                        java.util.UUID.randomUUID().getMostSignificantBits(), 36);
                card.persistAndFlush();
                card.number = com.intermarche.pos.domain.payment.StoredValue.GIFT_CARD_PREFIX
                        + String.format("%012d", card.id);
            }
        }
        ticket.grandTotal = counter.grandTotal;

        ticket.persist();
        technicalEventService.log(TechnicalEvent.EventType.TICKET_CLOSED, ticket.ticketNumber);
        syncOutboxService.enqueue(SyncOutbox.EntityType.TICKET, ticket.id);
        LOGGER.info("Exiting method validateTicket");
    }

    /**
     * Marks the draft as cancelled (abandoned cart); the reserved sequence
     * number stays consumed and the row documents the gap.
     *
     * @param ticketId the database id of the draft to cancel
     */
    @Transactional
    public void cancelDraft(Long ticketId) {
        LOGGER.info("Entering method cancelDraft with ticketId: " + ticketId);
        doCancelDraft(ticketId);
        LOGGER.info("Exiting method cancelDraft");
    }

    /**
     * Cancellation body shared by {@link #cancelDraft(Long)} and
     * {@link #syncDraft(PosState)} (already inside a transaction).
     *
     * @param ticketId the database id of the draft to cancel
     */
    private void doCancelDraft(Long ticketId) {
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket != null && ticket.status == Ticket.TicketStatus.OPEN) {
            ticket.status = Ticket.TicketStatus.CANCELLED;
            ticket.persist();
            technicalEventService.log(TechnicalEvent.EventType.TICKET_CANCELLED, ticket.ticketNumber);
            syncOutboxService.enqueue(SyncOutbox.EntityType.TICKET, ticket.id);
            // Campaigns & budgets spec §3: reverse the campaign consumption if a
            // sale was declared for this ticket. An abandoned draft never
            // finalized, so it usually has none — the reversal is enqueued only
            // when a matching SALE declaration exists.
            consumptionService.enqueueCancellation(ticket.ticketNumber,
                    ticket.store == null ? null : ticket.store.code,
                    com.intermarche.pos.domain.util.DateTimeProvider.now());
            LOGGER.infof("Draft annulé ID: %d (%s)", ticket.id, ticket.ticketNumber);
        }
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Maps an in-memory ticket item to a persistent ticket line, carrying its
     * stable uid and the EAN / PLU snapshots needed for restart recovery.
     *
     * @param item the in-memory item
     * @param lineNumber the 1-based line number
     * @return the persistent line
     */
    private TicketLine mapLine(TicketState.TicketItem item, int lineNumber) {
        TicketLine line = new TicketLine();
        line.lineNumber = lineNumber;
        line.lineUid = item.uid;
        line.ean = item.ean;
        line.plu = item.plu;
        if (item.plu != null) {
            line.product = Product.findByPlu(item.plu);
        } else if (item.ean != null) {
            line.product = Product.findByEan(item.ean);
        }
        // Nomenclature snapshot (BO-04-01-11): the family the article was sold
        // under, captured here at line creation exactly as the price is, and
        // never re-resolved on later reconciliations — a referential
        // re-parenting after the sale must not move a consolidated line.
        if (line.product != null) {
            com.intermarche.pos.domain.catalog.ProductFamily family =
                    com.intermarche.pos.domain.catalog.ProductFamily.findDirectFamily(line.product);
            if (family != null) {
                line.familyCode = family.code;
                line.familyLabel = family.description;
            }
        }
        // BO-10-02-20: the article's internal code, snapshotted beside the
        // family and for the same reason — the paper must keep saying what it
        // said, whatever the referential does afterwards.
        line.secondCode = line.product == null ? null : line.product.internalCode;
        // BO-10-05-04: the tare the scale deducted, snapshotted with the rest.
        line.appliedTareKg = item.appliedTareKg;
        line.productLabel = item.label;
        line.quantity = item.quantity;
        line.unitPrice = item.unitPrice;
        line.totalPrice = item.getTotalPrice().setScale(2, RoundingMode.HALF_UP);
        line.vatRate = item.vatRate;
        line.deposit = item.isNegative();
        line.moneyProduct = item.moneyProduct;
        line.priceEmbedded = item.priceEmbedded;
        // EXP-26/LC-02-05-04: the deduced weight + price/kg travel to the fiscal ticket.
        line.embeddedWeight = item.embeddedWeight;
        line.embeddedUnitPrice = item.embeddedUnitPrice;
        line.discountForbidden = item.discountForbidden;
        // LC-02-08: which door the article leaves by is transactional data — the
        // collection voucher is printed from the closed ticket.
        line.toCollect = item.toCollect;
        // EXP-43: a SOUS-TOTAL marker is fiscal data — printed, duplicated, dematerialized.
        line.subtotalBoundary = item.subtotalBoundary;
        line.returnLine = item.returnLine;
        // LC-09-01-11 to -18: what the article was payable with, snapshotted.
        line.restrictedTenders = item.restrictedTenders;
        // LC-02-03-02: the unit of measure the article was sold in.
        line.unitName = item.unitName;
        line.decimalQuantity = item.decimalQuantity;
        // LC-11-03-02: the identifiers a GS1 code carried are transactional data of the
        // line, kept whether or not this version has a rule for any of them.
        line.gs1Data = item.gs1Data;
        line.gs1ExpiryDate = item.gs1ExpiryDate;
        // BO-03-06-44: the activation code and the provider that owes it are
        // transactional data of the line, kept for the same reason the GS1
        // identifiers above are kept.
        line.activationCode = item.activationCode;
        line.activationProvider = item.activationProvider;
        return line;
    }

    /**
     * Computes the SHA-256 signature of a chaining payload, as lowercase hex.
     *
     * @param payload the pipe-joined fiscal payload
     * @return the 64-character hex signature
     */
    private String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JVM specification; this cannot happen
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
