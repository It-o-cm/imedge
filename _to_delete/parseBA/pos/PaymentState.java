package com.intermarche.pos.ui.payment;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory state of the payment in progress for the current ticket.
 * <p>
 * All monetary amounts are {@link BigDecimal} (phase 0).
 * <p>
 * {@code paymentInProgress} is THE payment-context discriminator of the
 * whole register — the voucher scan handler routes on it, parking refuses
 * on it, the training toggle refuses on it. It exists precisely because
 * {@code ticketDbId} stopped meaning "payment started" the day the draft
 * became early (first article): the id now lives for the whole sale, the
 * flag marks the payment phase alone. Payment entries keep their 1-based
 * registration order; restart recovery and the completion modal rebuild
 * from them, and {@code transactionComplete} + {@code lastChangeAmount}
 * are what the completion modal and the customer thank-you screen read —
 * both survive a restart through the recovery path.
 */
public class PaymentState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Number of payment lines displayable without a scrollbar (full list). */
    private static final int PAGE_SIZE = 5;

    /** The payments registered so far. */
    public List<PaymentEntry> payments = new ArrayList<>();

    /** The sum of registered payment amounts. */
    public BigDecimal paidAmount = BigDecimal.ZERO;

    /** True once the remaining due reaches zero. */
    public boolean transactionComplete = false;

    /** The database id of the draft ticket, or null before creation. */
    public Long ticketDbId = null;

    /**
     * Amount awaiting the virtual payment terminal's decision, or null when
     * no card request is pending (phase 6: the simulator accepts or refuses).
     */
    public BigDecimal pendingCardAmount = null;

    /**
     * Authorization number returned by the terminal for the card payment being
     * registered (BO-04-01-08), or null when none (degraded acceptance reaches
     * no monetique). Carried from the accept callback to the persisted
     * {@code CardPayment}; ephemeral, cleared once the entry is built.
     */
    public String pendingCardAuthNumber = null;

    /**
     * True when the card payment being registered was accepted in degraded mode
     * (BO-04-01-47/49). Carried from the accept callback to the persisted
     * {@code CardPayment}; ephemeral, cleared once the entry is built.
     */
    public boolean pendingCardDegraded = false;

    /**
     * The real card entry mode the terminal reported for the card payment being
     * registered (EXP-54): {@code "SANS_CONTACT"} or {@code "CODE"}, or null.
     * Carried from the accept callback to the persisted {@code CardPayment};
     * ephemeral, cleared once the entry is built.
     */
    public String pendingCardEntryMode = null;

    /**
     * True when a card payment of this transaction asked for the customer's
     * handwritten signature (LC-08-03-11): the conditional-printing rule adds
     * the card receipt back whatever the cashier chose. Held in memory for the
     * transaction only — the decision is taken before the state is cleared, so
     * no column carries it.
     */
    public boolean cardSignatureRequired = false;

    /**
     * The cashier's end-of-transaction printing choice (LC-08-03-01), or null
     * while none has been made. Only meaningful when conditional printing is
     * activated on the back office.
     */
    public com.intermarche.pos.ui.hardware.PrintChoice printChoice = null;

    /**
     * True once the printing choice has been applied and its documents sent to
     * the printer: the finalization then adds nothing, so a cashier who picked
     * "aucun ticket" is not handed a ticket by the closing step.
     */
    public boolean printApplied = false;

    /**
     * Amount awaiting the cheque reader, or null when no cheque is pending.
     * <p>
     * The cheque is read BEFORE the payment is registered: a cheque the reader
     * refuses must not leave a settled line on the ticket.
     */
    public BigDecimal pendingChequeAmount = null;

    /**
     * The magnetic line of the cheque being registered, or null when none was read.
     * <p>
     * Kept in memory only for now: it is shown to the cashier and journalled, but
     * the persisted {@code ChequePayment} carries no column for it yet.
     */
    public String pendingChequeLine = null;

    /**
     * Outcome of the remote valuation at payment entry (phase 7): LOCAL
     * (engine not configured), ENGINE (valued), DEGRADED (engine failed,
     * catalog prices apply). Null before payment entry.
     */
    public String valuationStatus = null;

    /**
     * Raw JSON of the engine's valuation when {@code valuationStatus} is
     * ENGINE — held for the lot 2 reconciliation, null otherwise.
     */
    public String valuationJson = null;

    /**
     * The engine's own total including tax, kept for log comparison against
     * the register's authoritative total. Null unless valued.
     */
    public BigDecimal valuationEngineTotal = null;

    /**
     * Total adjustment applied by the reconciliation (valued minus local
     * over the covered lines, advantages allocated), tax included. Negative
     * when the engine grants advantages; null before payment entry, zero
     * when valued without effect.
     */
    public BigDecimal valuationAdjustment = null;

    /**
     * Meal-voucher eligible base of the BASKET (tax included) from the
     * engine's MEAL_VOUCHER advantage, rewritten verbatim at each valuation.
     * Never decremented here: the allowance left for a new meal ticket is
     * derived at payment time as this base (capped by the threshold) minus
     * the meal tickets already registered. Null when the engine emitted none
     * (no cap applies).
     */
    public BigDecimal valuationMealEligible = null;

    /**
     * Meal-voucher threshold (legal cap per payment context) from the
     * engine, or null.
     */
    public BigDecimal valuationMealThreshold = null;

    /**
     * Upsell suggestions of the engine, ready to display (product labels
     * resolved). Never null, empty when none.
     */
    public java.util.ArrayList<String> valuationUpsells = new java.util.ArrayList<>();

    /**
     * Offer messages of the engine to show the seller during the scan and print
     * on the ticket (GB-01-07-05/06), rewritten at each valuation. Never null,
     * empty when the engine emitted none — a message carries no money, so the
     * list changes nothing about the totals.
     */
    public java.util.List<com.intermarche.pos.ui.valuation.ValuationService.EngineMessage>
            valuationMessages = new java.util.ArrayList<>();

    /**
     * True while the payment screen drives the transaction. Needed since the
     * early draft (phase 0 lot 2): {@link #ticketDbId} is set from the first
     * article, so it no longer indicates an active payment.
     */
    public boolean paymentInProgress = false;

    /** Current page of the payment history (previous / next). */
    public int currentPage = 0;

    /** Change given back on the last cash payment, or null. Kept for the end-of-transaction modal. */
    public BigDecimal lastChangeAmount = null;

    /** Current input mode of the payment numpad, or null. */
    public String inputMode = null;

    /** Current content of the payment numpad display. */
    public String temporaryInput = "0,00";

    /** True while the voucher panel is open (manual entry, or Catalina recognized at scan). */
    public boolean voucherPanelOpen = false;

    /** The technical code of the selected coupon type, or null. */
    public String pendingVoucherTypeCode = null;

    /** The display label of the selected coupon type, or null. */
    public String pendingVoucherLabel = null;

    /** The voucher number being entered, or null. */
    public String pendingVoucherNumber = null;

    /** True when the cashier must type the voucher amount. */
    public boolean pendingVoucherNeedsAmount = false;

    /** The current voucher entry error, or null. */
    public String voucherError = null;

    // --------------------------------------------------
    // Administered tender rules (BO-03-02-10/12/13/14/16)
    // --------------------------------------------------

    /**
     * The settlement key held back for a supervisor's authorization, or null
     * while none is (BO-03-02-10/12/13/14, control level "bloquant superviseur").
     *
     * <p>Held rather than refused, and held HERE rather than replayed from the
     * screen: the supervisor authorizes the settlement the cashier actually
     * asked for, not whatever the pad happens to show when they arrive.
     */
    public String tenderHeldMethod = null;

    /** The label the held settlement shows on the customer display, or null. */
    public String tenderHeldLabel = null;

    /** The amount handed over for the held settlement, or null when none is held. */
    public BigDecimal tenderHeldAmount = null;

    /** The rule the held settlement broke, shown in the authorization panel. */
    public String tenderHeldMessage = null;

    /**
     * The settlement key a supervisor has just authorized, or null.
     *
     * <p>Consumed by the very next registration of that tender and cleared at
     * once: an authorization that survived its settlement would apply to
     * whatever the cashier typed next.
     */
    public String tenderOverride = null;

    /**
     * The change this sale owes as a CREDIT NOTE rather than in cash
     * (BO-03-02-16), accumulated over the sale.
     *
     * <p>Accumulated and issued at the fiscal moment only, exactly like the gift
     * cards sold on the ticket: an abandoned sale never creates value.
     */
    public BigDecimal changeAsCreditNote = BigDecimal.ZERO;

    /**
     * Tells whether a settlement is waiting for a supervisor to allow an
     * administered bound to be passed.
     *
     * @return true while a settlement is held back
     */
    public boolean isTenderAuthorizationPending() {
        return tenderHeldMethod != null;
    }

    /**
     * Abandons the settlement held back for a supervisor's authorization.
     *
     * <p>The granted override goes with it, deliberately: an authorization kept
     * past the settlement it was given for is an authorization for the next one.
     */
    public void clearTenderHold() {
        tenderHeldMethod = null;
        tenderHeldLabel = null;
        tenderHeldAmount = null;
        tenderHeldMessage = null;
        tenderOverride = null;
    }

    /**
     * Registers a plain payment (card, cheque, meal ticket, fidelity...).
     *
     * @param method the payment method key
     * @param amount the paid amount
     */
    public void addPayment(String method, BigDecimal amount) {
        payments.add(new PaymentEntry(method, amount));
        paidAmount = paidAmount.add(amount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Registers the automatic recovery of a deposit already collected on an
     * order (LC-06-03-07), carrying the deposit's per-rate VAT detail and the
     * order code so both reach the persisted {@code DepositPayment} row and
     * the printed receipt (LC-06-03-08).
     *
     * @param amount the deposit recovered on the ticket
     * @param depositVatDetail the compact per-rate VAT detail of the deposit,
     *        or null when the recalled document carried none
     * @param depositOrderCode the code of the order the deposit was taken on,
     *        or null
     */
    public void addDepositPayment(BigDecimal amount, String depositVatDetail,
                                  String depositOrderCode) {
        payments.add(new PaymentEntry("ACOMPTE", amount, depositVatDetail, depositOrderCode));
        paidAmount = paidAmount.add(amount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Registers a card payment carrying the monetique traces the terminal
     * returned (authorization number, degraded-mode indicator), so they reach
     * the persisted {@code CardPayment} (BO-04-01-08/47/49).
     *
     * @param amount the paid amount
     * @param authorizationNumber the terminal authorization number, or null
     * @param degradedMode true when accepted in degraded mode
     */
    public void addCardPayment(BigDecimal amount, String authorizationNumber, boolean degradedMode) {
        addCardPayment(amount, authorizationNumber, degradedMode, null);
    }

    /**
     * Registers a card payment carrying the terminal traces AND the real entry
     * mode (EXP-54): contactless vs chip-and-PIN, traced onto the entry so it
     * reaches the persisted {@code CardPayment}.
     *
     * @param amount the paid amount
     * @param authorizationNumber the terminal authorization number, or null
     * @param degradedMode true when accepted in degraded mode
     * @param cardEntryMode the real card entry mode, or null when undiscriminated
     */
    public void addCardPayment(BigDecimal amount, String authorizationNumber, boolean degradedMode,
            String cardEntryMode) {
        PaymentEntry entry = new PaymentEntry("CARD", amount, degradedMode, authorizationNumber);
        entry.cardEntryMode = cardEntryMode;
        payments.add(entry);
        paidAmount = paidAmount.add(amount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Registers a cheque payment carrying the magnetic line the reader read, so it
     * reaches the persisted {@code ChequePayment}.
     *
     * @param amount the paid amount
     * @param magneticLine the CMC7 line as read, or null when the cheque was not read
     */
    public void addChequePayment(BigDecimal amount, String magneticLine) {
        PaymentEntry entry = new PaymentEntry("CHEQUE", amount);
        entry.magneticLine = magneticLine;
        payments.add(entry);
        paidAmount = paidAmount.add(amount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Registers a cash payment with the tendered amount.
     * <p>
     * Important: {@link #lastChangeAmount} is NOT cleared here, it is needed
     * by the end-of-transaction modal.
     *
     * @param amount the amount applied to the ticket
     * @param tenderedAmount the amount handed over by the customer
     */
    public void addCashPayment(BigDecimal amount, BigDecimal tenderedAmount) {
        payments.add(new PaymentEntry("CASH", amount, tenderedAmount));
        paidAmount = paidAmount.add(amount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Registers a customer-credit settlement, carrying the debtor.
     *
     * <p>The account rides ON THE ENTRY and not on the screen state: the entry is
     * what the persistence turns into a payment row, and a debtor kept beside it
     * would be lost the moment a second credit line is registered for another
     * account on the same sale.
     *
     * @param amount the amount charged to the account
     * @param accountNumber the account number the debt is charged to
     * @param accountName the account name as it stands at sale time
     * @param overLimit true when a supervisor authorized it over the ceiling
     * @param tenderKey the administered tender key the settlement is registered
     *        under (BO-10-04-09)
     */
    public void addCreditPayment(BigDecimal amount, String accountNumber, String accountName,
            boolean overLimit, String tenderKey) {
        PaymentEntry entry = new PaymentEntry(tenderKey, amount);
        entry.creditAccountNumber = accountNumber;
        entry.creditAccountName = accountName;
        entry.creditOverLimit = overLimit;
        payments.add(entry);
        paidAmount = paidAmount.add(amount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Adds a voucher payment carrying its type label and optional number.
     *
     * @param label the voucher type label shown to the cashier
     * @param number the voucher number, or null when there is none
     * @param amount the paid amount
     */
    public void addVoucherPayment(String label, String number, BigDecimal amount) {
        payments.add(new PaymentEntry(label, amount, number, true));
        paidAmount = paidAmount.add(amount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Clears the registered payments and resets the history pagination.
     */
    // --------------------------------------------------
    // Backup monetics (LC-07-07-06/09)
    // --------------------------------------------------

    /** True while the backup-monetics panel is open over the payment screen. */
    public boolean backupPanelOpen = false;

    /**
     * The request this till emitted, or null while none is pending. It is what an
     * answer is matched against, so it must outlive the screen refresh — the payment
     * page reloads itself on every version bump.
     */
    public transient BackupPaymentTicket.Request backupRequest = null;

    /** The request rendered as an SVG QR code, or null while none is pending. */
    public String backupRequestSvg = null;

    /** The refusal shown inside the backup panel, or null. */
    public String backupError = null;

    /** True once the operator asked to key the outcome in instead of scanning it. */
    public boolean backupManualEntry = false;

    /**
     * Closes the backup-monetics panel and forgets the pending request.
     *
     * <p>The request goes with it, deliberately: an answer arriving after the panel
     * was closed answers a question this till is no longer asking.
     */
    public void clearBackupPanel() {
        backupPanelOpen = false;
        backupRequest = null;
        backupRequestSvg = null;
        backupError = null;
        backupManualEntry = false;
    }

    // --------------------------------------------------
    // Foreign currency (LC-07-14)
    // --------------------------------------------------

    /** True while the foreign-currency panel is open over the payment screen. */
    public boolean currencyPanelOpen = false;

    /** The currency the operator selected, or null while none is. */
    public com.intermarche.pos.domain.payment.Currency selectedCurrency = null;

    /** The refusal shown inside the currency panel, or null. */
    public String currencyError = null;

    /**
     * Registers a settlement taken on a backup-monetics terminal.
     *
     * @param amount the amount that terminal accepted
     * @param methodLabel the scheme it reported
     * @param transactionNumber the sale the two codes were matched on
     * @param manual true when the outcome was keyed in rather than scanned
     */
    public void addBackupPayment(BigDecimal amount, String methodLabel, String transactionNumber,
            boolean manual) {
        PaymentEntry entry = new PaymentEntry("SECOURS", amount);
        entry.backupMethodLabel = methodLabel;
        entry.backupTransaction = transactionNumber;
        entry.backupManual = manual;
        payments.add(entry);
        paidAmount = paidAmount.add(amount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Registers a settlement handed over in a foreign currency, carrying what the
     * euro figure does not say.
     *
     * @param euroAmount the euro value credited to the sale
     * @param code the ISO code of the currency handed over
     * @param foreignAmount the amount handed over, in that currency
     * @param rate the euros-for-one-unit rate applied
     */
    public void addCurrencyPayment(BigDecimal euroAmount, String code, BigDecimal foreignAmount,
            BigDecimal rate) {
        PaymentEntry entry = new PaymentEntry("DEVISE", euroAmount);
        entry.currencyCode = code;
        entry.currencyAmount = foreignAmount;
        entry.currencyRate = rate;
        payments.add(entry);
        paidAmount = paidAmount.add(euroAmount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Closes the foreign-currency panel and forgets what it held.
     */
    public void clearCurrencyPanel() {
        currencyPanelOpen = false;
        selectedCurrency = null;
        currencyError = null;
    }

    // --------------------------------------------------
    // Customer credit (LC-07-09)
    // --------------------------------------------------

    /** How many matching accounts the credit panel shows at once. */
    public static final int CREDIT_ROWS = 5;

    /** True while the customer-credit panel is open over the payment screen. */
    public boolean creditPanelOpen = false;

    /** What the operator typed in the account search box. */
    public String creditSearch = "";

    /** The accounts matching the last search, empty before any. */
    public List<com.intermarche.pos.domain.payment.AccountCustomer> creditCustomers = new ArrayList<>();

    /** True once a search ran, so an empty list can be told from "not searched yet". */
    public boolean creditSearched = false;

    /** The account the operator confirmed, or null while none is named. */
    public com.intermarche.pos.domain.payment.AccountCustomer creditCustomer = null;

    /** The refusal or warning shown inside the credit panel, or null. */
    public String creditError = null;

    /**
     * The amount held back because it takes the account over its ceiling, awaiting
     * a supervisor ({@code LC-07-09-04}), or null when nothing is pending.
     */
    public BigDecimal creditPendingAmount = null;

    /**
     * Returns the accounts offered by the credit panel, capped to what it shows.
     *
     * @return at most {@link #CREDIT_ROWS} accounts
     */
    public List<com.intermarche.pos.domain.payment.AccountCustomer> getVisibleCreditCustomers() {
        return creditCustomers.size() <= CREDIT_ROWS
                ? creditCustomers
                : creditCustomers.subList(0, CREDIT_ROWS);
    }

    /**
     * Tells whether the credit search ran and matched nothing.
     *
     * @return true when the operator searched and no account matched
     */
    public boolean isCreditSearchWithoutMatch() {
        return creditSearched && creditCustomers.isEmpty();
    }

    /**
     * Tells whether a settlement is waiting for a supervisor to allow the ceiling
     * to be exceeded.
     *
     * @return true while an over-ceiling amount is held back
     */
    public boolean isCreditOverLimitPending() {
        return creditPendingAmount != null;
    }

    /**
     * Closes the customer-credit panel and forgets everything it held.
     *
     * <p>The pending over-ceiling amount is cleared with the rest, deliberately: an
     * authorization that survived the panel being closed would apply to whatever
     * the next operator typed.
     */
    public void clearCreditPanel() {
        creditPanelOpen = false;
        creditSearch = "";
        creditCustomers = new ArrayList<>();
        creditSearched = false;
        creditCustomer = null;
        creditError = null;
        creditPendingAmount = null;
    }

    /** The active fidelity lease id (imfid burn reservation), or null. */
    public Long fidReservationId = null;

    /** The lease expiry instant, for the half-life renewal. */
    public java.time.LocalDateTime fidLeaseExpiresAt = null;

    /** The lease duration in seconds, captured at grant (renewal timing). */
    public long fidLeaseSeconds = 0L;

    /**
     * The magnetic line of a FINISHED cheque reading, waiting to be registered,
     * or null.
     * <p>
     * The reading ends on the reader's own follower thread, which must not write
     * to the database: the register is a single-writer machine and the request
     * thread rewrites the same draft row on every revaluation, so two writers
     * race and the loser's settlement is silently lost. The follower therefore
     * DEPOSITS its outcome here and the next request registers it. Volatile
     * because the two threads are the only readers and writers of this field.
     */
    public volatile String chequeReadLine = null;

    /**
     * The operator-facing message of a FAILED cheque reading, waiting to be
     * shown, or null. Deposited by the follower thread for the same reason as
     * {@link #chequeReadLine}.
     */
    public volatile String chequeReadFailure = null;

    public void clearPayments() {
        payments.clear();
        paidAmount = BigDecimal.ZERO;
        currentPage = 0;
        clearTemporaryInputs();
    }

    /**
     * Removes a SINGLE registered payment without touching the others (EXP-63):
     * the entry at the index leaves the list and its amount is taken back off the
     * paid total. The other payments and the payment-in-progress state stand.
     *
     * @param index the 0-based index of the payment to remove
     * @return true when the index was valid and the payment was removed
     */
    public boolean removePayment(int index) {
        if (index < 0 || index >= payments.size()) {
            return false;
        }
        PaymentEntry removed = payments.remove(index);
        paidAmount = paidAmount.subtract(removed.amount);
        goToLastPage();
        return true;
    }

    /**
     * Resets the whole payment state for a new transaction.
     */
    public void reset() {
        clearPayments();
        fidReservationId = null;
        fidLeaseExpiresAt = null;
        fidLeaseSeconds = 0L;
        transactionComplete = false;
        ticketDbId = null;
        paymentInProgress = false;
        pendingCardAmount = null;
        pendingChequeAmount = null;
        pendingChequeLine = null;
        chequeReadLine = null;
        chequeReadFailure = null;
        pendingCardAuthNumber = null;
        pendingCardDegraded = false;
        pendingCardEntryMode = null;
        cardSignatureRequired = false;
        printChoice = null;
        printApplied = false;
        valuationStatus = null;
        valuationJson = null;
        valuationEngineTotal = null;
        valuationAdjustment = null;
        valuationMealEligible = null;
        valuationMealThreshold = null;
        valuationUpsells = new java.util.ArrayList<>();
        lastChangeAmount = null; // Cleared only here (full new transaction)
        changeAsCreditNote = BigDecimal.ZERO;
        clearTenderHold();
        clearPendingVoucher();
    }

    /**
     * Clears any in-progress voucher entry and its error message.
     */
    public void clearPendingVoucher() {
        voucherPanelOpen = false;
        pendingVoucherTypeCode = null;
        pendingVoucherLabel = null;
        pendingVoucherNumber = null;
        pendingVoucherNeedsAmount = false;
        voucherError = null;
    }

    /**
     * Resets the payment numpad input mode and display.
     */
    private void clearTemporaryInputs() {
        this.inputMode = null;
        this.temporaryInput = "0,00";
    }

    // --------------------------------------------------
    // Payment history pagination
    // --------------------------------------------------

    /**
     * Returns the total number of pages of payments (at least one).
     *
     * @return the page count
     */
    public int getTotalPages() {
        if (payments.isEmpty()) return 1;
        return (int) Math.ceil((double) payments.size() / PAGE_SIZE);
    }

    /**
     * Moves the view to the last page so the most recent payments are shown.
     */
    public void goToLastPage() {
        currentPage = getTotalPages() - 1;
    }


    /**
     * Indicates whether a previous page of payments exists.
     *
     * @return true if not on the first page
     */
    public boolean isHasPreviousPage() {
        return currentPage > 0;
    }

    /**
     * Indicates whether a next page of payments exists.
     *
     * @return true if more payments follow the current page
     */
    public boolean isHasNextPage() {
        return (currentPage + 1) * PAGE_SIZE < payments.size();
    }


    /**
     * Returns the 1-based current page number for display.
     *
     * @return the current page number
     */
    public int getCurrentPageDisplay() {
        return currentPage + 1;
    }

    /**
     * Indicates whether the payment at the given index belongs to the current page.
     *
     * @param index the index of the payment in the full list
     * @return true if the payment is on the current page
     */
    public boolean isOnCurrentPage(int index) {
        int fromIndex = currentPage * PAGE_SIZE;
        int toIndex = fromIndex + PAGE_SIZE;
        return index >= fromIndex && index < toIndex;
    }

    /**
     * Indicates whether the payment at the given index is among the two most recent.
     *
     * @param index the index of the payment in the full list
     * @return true if the payment is one of the last two
     */
    public boolean isAmongLastTwo(int index) {
        return index >= payments.size() - 2;
    }

    /**
     * Moves to the next page of payments if one exists.
     */
    public void nextPage() {
        if (isHasNextPage()) currentPage++;
    }

    /**
     * Moves to the previous page of payments if one exists.
     */
    public void prevPage() {
        if (isHasPreviousPage()) currentPage--;
    }

    /**
     * A single registered payment.
     */
    public static class PaymentEntry implements Serializable {
        private static final long serialVersionUID = 1L;

        /** The payment method key, or the voucher type label for voucher entries. */
        public String method;

        /** The amount applied to the ticket. */
        public BigDecimal amount;

        /** The tendered amount for cash payments, or null. */
        public BigDecimal tenderedAmount;

        /** The voucher number, or null. */
        public String voucherNumber;

        /** True when this entry is a voucher payment. */
        public boolean voucher;

        /** The card authorization number (BO-04-01-08), or null. */
        public String authorizationNumber;

        /** True when the card payment was accepted in degraded mode (BO-04-01-47/49). */
        public boolean degradedMode;

        /** The real card entry mode (EXP-54): SANS_CONTACT, CODE, or null. */
        public String cardEntryMode;

        /** The CMC7 magnetic line read off the cheque, or null. */
        public String magneticLine;

        /** The scheme a backup-monetics settlement reported (LC-07-07-08), or null. */
        public String backupMethodLabel;

        /** The transaction number the two backup QR codes were matched on, or null. */
        public String backupTransaction;

        /** True when a backup-monetics outcome was keyed in rather than scanned. */
        public boolean backupManual;

        /** The ISO code of the currency handed over (LC-07-14), or null. */
        public String currencyCode;

        /** The amount handed over in that currency, or null. */
        public BigDecimal currencyAmount;

        /** The euros-for-one-unit rate applied, or null. */
        public BigDecimal currencyRate;

        /** The account number charged by a customer-credit settlement, or null. */
        public String creditAccountNumber;

        /** The account name as it stood at sale time, or null. */
        public String creditAccountName;

        /** True when a supervisor authorized this settlement over the ceiling. */
        public boolean creditOverLimit;

        /**
         * The per-rate VAT detail of the deposit this entry recovers, in the
         * compact {@code DepositVat} form, or null on every other entry
         * (LC-06-03-05/-08): it rides from the recall of the order to the
         * persisted {@code DepositPayment} row, so the final receipt restates
         * the ventilation computed at collection time.
         */
        public String depositVatDetail;

        /**
         * The code of the order whose deposit this entry recovers, or null on
         * every other entry (LC-06-03-07).
         */
        public String depositOrderCode;

        /**
         * Creates a plain payment entry.
         *
         * @param method the payment method key
         * @param amount the paid amount
         */
        public PaymentEntry(String method, BigDecimal amount) {
            this.method = method;
            this.amount = amount;
            this.tenderedAmount = null;
        }

        /**
         * Creates a cash payment entry with the tendered amount.
         *
         * @param method the payment method key (CASH)
         * @param amount the amount applied to the ticket
         * @param tenderedAmount the amount handed over by the customer
         */
        public PaymentEntry(String method, BigDecimal amount, BigDecimal tenderedAmount) {
            this.method = method;
            this.amount = amount;
            this.tenderedAmount = tenderedAmount;
        }

        /**
         * Creates a voucher payment entry holding its display label and number.
         *
         * @param method the display label of the voucher type
         * @param amount the paid amount
         * @param voucherNumber the voucher number, or null when there is none
         * @param voucher always true; marks the entry as a voucher payment
         */
        public PaymentEntry(String method, BigDecimal amount, String voucherNumber, boolean voucher) {
            this.method = method;
            this.amount = amount;
            this.tenderedAmount = null;
            this.voucherNumber = voucherNumber;
            this.voucher = voucher;
        }

        /**
         * Creates a card payment entry carrying the monetique traces the
         * terminal returned (BO-04-01-08/47/49).
         *
         * @param method the payment method key (CARD)
         * @param amount the paid amount
         * @param degradedMode true when accepted in degraded mode
         * @param authorizationNumber the terminal authorization number, or null
         */
        public PaymentEntry(String method, BigDecimal amount, boolean degradedMode, String authorizationNumber) {
            this.method = method;
            this.amount = amount;
            this.tenderedAmount = null;
            this.degradedMode = degradedMode;
            this.authorizationNumber = authorizationNumber;
        }

        /**
         * Creates a deposit-recovery entry carrying the deposit's per-rate
         * VAT detail and the order it comes from (LC-06-03-07).
         *
         * @param method the payment method key (ACOMPTE)
         * @param amount the deposit recovered on the ticket
         * @param depositVatDetail the compact per-rate VAT detail of the
         *        deposit, or null when the recalled document carried none
         * @param depositOrderCode the code of the order the deposit was
         *        taken on, or null
         */
        public PaymentEntry(String method, BigDecimal amount, String depositVatDetail,
                            String depositOrderCode) {
            this.method = method;
            this.amount = amount;
            this.tenderedAmount = null;
            this.depositVatDetail = depositVatDetail;
            this.depositOrderCode = depositOrderCode;
        }

        /**
         * Indicates whether this entry recovers a deposit already collected
         * on an order (LC-06-03-07).
         *
         * @return true when the entry is a deposit recovery
         */
        public boolean isDepositRecovery() {
            return "ACOMPTE".equals(method);
        }

        /**
         * Returns the paid amount formatted for display (2 decimals, French comma).
         *
         * @return the formatted amount
         */
        public String getFormattedAmount() {
            return String.format("%.2f", amount.setScale(2, RoundingMode.HALF_UP)).replace(".", ",");
        }


        /**
         * Indicates whether this entry is a voucher payment.
         *
         * @return true if the entry was created as a voucher payment
         */
        public boolean isVoucher() {
            return voucher;
        }

        /**
         * Returns the voucher number for display, or an empty string when there is none.
         *
         * @return the voucher number, or an empty string
         */
        public String getVoucherNumber() {
            return voucherNumber != null ? voucherNumber : "";
        }
    }
}
