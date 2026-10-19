package com.intermarche.pos.ui;

import com.intermarche.pos.ui.auth.AuthState;
import com.intermarche.pos.ui.endorsement.EndorsementState;
import com.intermarche.pos.ui.fidelity.FidelityState;
import com.intermarche.pos.ui.payment.PaymentState;
import com.intermarche.pos.ui.reprintticket.ReprintState;
import com.intermarche.pos.ui.returnprocess.RefundState;
import com.intermarche.pos.ui.ticket.TicketState;
import jakarta.inject.Singleton;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;

/**
 * Global state of this POS terminal.
 * <p>
 * One executable serves exactly one register (one process = one terminal),
 * therefore a {@link Singleton} scope is legitimate here.
 * All monetary computations are {@link BigDecimal} (phase 0).
 * <p>
 * This singleton is the COMPOSITION ROOT of every in-memory state: ticket,
 * payment, fidelity, auth, endorsement, price-mod modal, reprint and refund
 * screens all hang off it, and cross-cutting flags (training mode, drawer
 * return URL, selection, donation line) live directly on it. Two contracts
 * every contributor must know: {@code version}/{@code touch()} is the
 * reactive heartbeat — a mutation without a touch is invisible to every
 * polling screen; and {@code clearTicket()} is the END-OF-SALE broom — it
 * resets the whole transactional sub-state (ticket, fidelity, payment,
 * selection, modal) and any new per-sale field MUST be added to it, or it
 * will leak into the next customer's sale. Memory only: the durable truth
 * is the draft, this object is rebuilt from it at recovery.
 */
@Singleton
public class PosState implements Serializable {

    /**
     * The localised operator messages: the two refusals this state posts itself
     * are bundle keys, never French literals (LC-13-06).
     */
    @jakarta.inject.Inject
    Messages i18n;
    private static final long serialVersionUID = 1L;

    /** The ticket being built. */
    public TicketState ticket = new TicketState();

    /** The payment in progress. */
    public PaymentState payment = new PaymentState();

    /** The fidelity card state. */
    public FidelityState fidelity = new FidelityState();

    /** The authentication state. */
    public AuthState auth = new AuthState();

    /** The manager endorsement state. */
    public EndorsementState endorsement = new EndorsementState();

    /** The price-modification modal state. */
    public PriceModState priceModState = new PriceModState();

    /** The pending age-check prompt (server-rendered modal on the sale screen). */
    public AgeCheckState ageCheck = new AgeCheckState();

    /**
     * True while the next article registered is to be marked "à enlever"
     * ({@code LC-02-08-02}).
     *
     * <p>It ARMS one article and one only: the goods-collection desk takes the
     * articles the customer will not carry out of the shop, and a mode that stayed
     * on would quietly send the rest of the basket to the desk too. The consumption
     * is in {@link com.intermarche.pos.ui.ticket.TicketState#addItem} — the single
     * funnel every scan, every keyed code and every group tile goes through, which
     * is what {@code LC-02-08-02} means by "saisie de l'article (scan, saisie EAN,
     * groupe articles)".
     */
    public boolean collectArmed = false;

    /**
     * The quantity the operator armed before naming an article
     * ({@code LC-02-13-04/05/06}), null when nothing is armed.
     *
     * <p>The quantity key has always applied a quantity to a line ALREADY registered.
     * The questionnaire asks for the other order too — key the quantity, then scan,
     * key the EAN, or key the internal code — and one armed value covers the three,
     * because the three end in the same {@code addItem}.
     *
     * <p>Like the collection arming, it arms ONE article: a quantity that stayed on
     * would multiply the rest of the basket.
     */
    public BigDecimal armedQuantity = null;

    /**
     * The GROSS weight in kilograms keyed on the MANUAL WEIGHT key and armed for
     * the next weighed article named ({@code LC-02-02-03}), null when nothing is
     * armed.
     *
     * <p>GROSS, and the screen says so in as many words, because on this path
     * nobody nets it. In an ordinary weighing the register SENDS the tare to the
     * scale, which returns the NET weight ({@code LC-14-01-06}); with no scale in
     * the loop the register is the only one left to deduct it
     * ({@code LC-14-01-08}). A cashier who copied a net figure would be taxed the
     * tare twice, which is why the screen asks for the gross one and states the
     * tare it is about to take off.
     *
     * <p>Like the armed quantity above, it arms ONE article: a weight left on
     * would be laid over the next fruit of the basket.
     */
    public BigDecimal pendingManualWeightKg = null;

    /**
     * The add suspended while the operator types what the article does not carry
     * ({@code LC-02-03-01/02/03}), never null.
     */
    public EntryPromptState entryPrompt = new EntryPromptState();

    /**
     * State of the entry prompt: an article whose price or whose quantity must be
     * keyed at the till suspended the add, and the sale waits for the value.
     *
     * <p>THE SAME PROMPT SERVES BOTH, because both are the same situation — the
     * referential does not carry the figure and the operator does. A "prix requis"
     * article (a gift card, a generic "Divers" line) asks for its price; a
     * decimal-quantity article (a hose sold by the metre, a liquid by the litre) asks
     * for its quantity and shows the price per unit of measure so the operator can
     * check what they are about to ring.
     *
     * <p>The two CHAIN: an article that requires both is asked its quantity, then its
     * price, and the line is created once.
     */
    public static class EntryPromptState implements java.io.Serializable {

        private static final long serialVersionUID = 1L;

        /** Asked for a price the referential does not carry (LC-02-03-03). */
        public static final String PRICE = "PRICE";

        /** Asked for a decimal quantity in the article's unit (LC-02-03-01). */
        public static final String QUANTITY = "QUANTITY";

        /** Whether the prompt is currently shown. */
        public boolean active = false;

        /** What is being asked: {@link #PRICE} or {@link #QUANTITY}. */
        public String kind;

        /** The EAN of the article the add was suspended on. */
        public String ean;

        /** The article's display label. */
        public String label;

        /** The article's unit of measure, empty when it is sold by the piece. */
        public String unitName = "";

        /** The catalog price per unit of measure, formatted, empty when there is none. */
        public String unitPriceFormatted = "";

        /** The quantity already resolved, one until a decimal quantity is keyed. */
        public BigDecimal quantity = BigDecimal.ONE;

        /** The price already keyed, null until a required price is given. */
        public BigDecimal price = null;

        /**
         * Clears the prompt, so the next add starts from nothing.
         */
        public void clear() {
            active = false;
            kind = null;
            ean = null;
            label = null;
            unitName = "";
            unitPriceFormatted = "";
            quantity = BigDecimal.ONE;
            price = null;
        }

        /**
         * Tells whether the prompt is asking for a price.
         *
         * @return true when a price is expected
         */
        public boolean isPriceKind() {
            return PRICE.equals(kind);
        }

        /**
         * Returns the bundle key of the title the prompt shows.
         *
         * <p>A KEY AND NOT THE SENTENCE: this holder is a nested value class
         * with no resolver injected, so the screen resolves what it displays
         * (LC-13-06).
         *
         * @return the key of the operator-facing title
         */
        public String getTitleKey() {
            return isPriceKind() ? "op.prixSaisir" : "op.quantiteSaisir";
        }
    }

    /**
     * The reason the operator gave for abandoning the ticket ({@code LC-04-04-10}),
     * empty until the abandon screen collects one.
     *
     * <p>It rides on the state rather than on the endorsement request because the
     * abandon may or may not go through a manager: the reason belongs to the gesture,
     * not to the authorization.
     */
    public String abandonReason = "";

    /**
     * True when the operator asked for the abandon ticket to be printed on this
     * abandon ({@code LC-04-04-12}, on-demand rule).
     */
    public boolean abandonPrintRequested = false;

    /**
     * State of the age-control prompt: a restricted product suspended the
     * add, and the sale waits for the cashier's CONFIRM (ID checked — the
     * parked add replays) or REFUSE (journalized, nothing added). The parked
     * gesture is replayed by KIND: SCAN through the recognition chain, PLU
     * through the weighing add, EAN_QTY through the quantity add.
     */
    public static class AgeCheckState {
        /** Whether the prompt is currently shown. */
        public boolean active = false;
        /** The restricted product's display label. */
        public String productLabel;
        /** The required minimum age. */
        public int threshold;
        /** The replay kind: SCAN, PLU or EAN_QTY. */
        public String kind;
        /** The parked code (scanned code, PLU or EAN). */
        public String code;
        /** The parked quantity (EAN_QTY kind only). */
        public java.math.BigDecimal quantity;

        /** Clears the prompt. */
        public void clear() {
            active = false; productLabel = null; threshold = 0;
            kind = null; code = null; quantity = null;
        }
    }

    /** The reprint screen state. */
    public ReprintState reprint = new ReprintState();

    /** The refund screen state. */
    public RefundState refund = new RefundState();

    /** The punctual container tare armed for the next weighing (LC-14-01-04/05). */
    public com.intermarche.pos.ui.balance.TareState tare =
            new com.intermarche.pos.ui.balance.TareState();

    /** The change-order slip staging (per-denomination amounts and rolls, LC-12-05). */
    public com.intermarche.pos.ui.cash.ChangeOrderState changeOrder =
            new com.intermarche.pos.ui.cash.ChangeOrderState();

    /** The invoice screen state (ticket billed, addressee, step). */
    public com.intermarche.pos.ui.invoice.InvoiceState invoice =
            new com.intermarche.pos.ui.invoice.InvoiceState();

    /** Index of the selected ticket line, or -1. */
    public int selectedTicketIndex = -1;

    /** Uid of the last entered line (cancellable without endorsement), or null. */
    public String lastEnteredItemId = null;

    /**
     * Raised by {@code TicketState.addItem} on every article entry — a new
     * line as well as a merge into an existing one — and consumed once by the
     * post-mutation funnel of the ticket service.
     *
     * <p>It exists because the two halves of the decision live apart: only
     * the ticket state knows an article was entered, and only the service
     * knows the administered threshold that says whether to warn about it.
     * A flag raised in one and read in the other keeps the policy out of the
     * state object without teaching six entry points the same rule.
     */
    public boolean lineJustEntered = false;


    /** Uid of the solidarity round-up line of the current ticket, or null. */
    public String donationLineUid = null;

    /**
     * The badge of the supervisor whose endorsement is held OPEN for the whole
     * ticket (EXP-33), or null in the ordinary per-gesture mode. While non-null,
     * guarded negative gestures proceed without re-asking; the FIN SUPERVISEUR key
     * or the end of the ticket clears it. Reset by {@link #clearTicket()} so a
     * supervisor authorization never leaks into the next customer's sale.
     */
    public String supervisorSessionBadge = null;

    /**
     * True while the operator armed the cancel-by-scan mode (EXP-36): the next
     * scanned code designates the line to cancel instead of adding one. Arms ONE
     * scan, like the quantity arming; reset by {@link #clearTicket()}.
     */
    public boolean cancelByScanArmed = false;

    /**
     * True while the register runs in RETURN MODE (EXP-40): the day's return till,
     * where every article entered rings as a return (a negative line) instead of a
     * sale. A DAY mode, not a per-sale one — deliberately NOT reset by
     * {@link #clearTicket()}: it stays on across tickets until the operator turns it
     * off, and a banner keeps it visible so no sale is rung as a return by surprise.
     */
    public boolean returnMode = false;

    /**
     * The code of the ORDER the current ticket was loaded from by a recall
     * (LC-06-03-07), or null when the ticket is no one's order. Held for the
     * sale's whole life so the closing can tell the store node the order was
     * settled, and reset by {@link #clearTicket()} so an order never leaks
     * into the next customer's sale.
     */
    public String recalledOrderCode = null;

    /**
     * The label of the administered return TYPE the operator chose (LC-05-04-01),
     * or null when they used one of the two base gestures. It names the kind of
     * return in progress — a container, a defective article — on the screen and in
     * the journal, and it is dropped with the gesture it belongs to.
     */
    public String returnTypeLabel = null;

    /**
     * True while the register runs in training mode: nothing is persisted
     * (no draft, no ticket number, no fiscal chain, no sync), the drawer
     * stays shut, refunds and session actions are blocked, and every screen
     * shows the training banner. Toggled under manager endorsement with an
     * empty cart.
     */
    public boolean trainingMode = false;

    /**
     * The interface language the OPERATOR chose on this register, or null to
     * follow the chain below it (LC-13-06-03/04): the language administered for
     * the signed-in operator, then the store's system language, then French.
     * <p>
     * Dropped at sign-out, as the requirement asks: the default language applies
     * again when the register closes, so a Dutch-speaking cashier's choice does
     * not greet the French-speaking one who takes the post after them.
     */
    public String language = null;

    /** Version counter used by the UI polling. */
    public long version = 0;

    /** Return URL stored while the drawer-open screen is shown. */
    public String returnUrl = null;

    /**
     * When the customer display last asked for its data, in epoch millis; 0 while
     * nothing has ever asked.
     * <p>
     * This till's customer display is a SECOND SCREEN showing {@code /customer}, not a
     * line display on the hardware bus. Such a screen has no device to probe: the only
     * evidence it exists is that something is polling its page, once a second. That is
     * what this timestamp records, and what lets the hardware gate report the display
     * as present on a till where no line display is bound. Volatile because the poll
     * and the gate run on different threads.
     */
    public volatile long customerDisplaySeenAt = 0L;

    /**
     * The last message sent to the customer display, empty when there is none.
     * <p>
     * A line display is a device the register WRITES to; the second screen showing
     * {@code /customer} is a page that READS the register. So a message that a VFD
     * would have shown has nowhere to go on this till unless it is kept here for the
     * page to pick up on its next poll. Everything else on that page is derived from
     * the sale; this one field is pushed, because "PAIEMENT REFUSE" or "CONTROLE
     * D'AGE EN COURS" is an event and not a state the cart can be read from.
     */
    public volatile String customerMessage = "";

    /** Database id of the last closed ticket, or null. */
    public Long lastClosedTicketId = null;

    /**
     * One-shot request raised when a CLOSED ticket number is scanned on the sale
     * screen (EXP-37): the ticket-fragment poll carries it and the screen navigates
     * to {@code /return}, the same relay the badge-close uses. Consumed (cleared) by
     * the poll that carries it, so it fires the navigation exactly once.
     */
    public boolean returnByScanRequested = false;

    /**
     * The Z close held back because the counted cash strays past the discrepancy
     * threshold (EXP-82): the counted total, the withdrawal, the denominations
     * detail, the paper-tender declaration and the variance wait here while the
     * operator recounts or a supervisor validates. Null when no close is pending
     * arbitration.
     */
    public PendingClose pendingClose = null;

    /**
     * A Z close awaiting an over-threshold discrepancy arbitration (EXP-82).
     */
    public static class PendingClose implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        /** The counted cash total, as posted. */
        public java.math.BigDecimal counted;
        /** The withdrawal, as posted. */
        public java.math.BigDecimal withdrawn;
        /** The denominations detail (JSON), as posted. */
        public String detail;
        /** The paper-tender declaration (JSON), as posted. */
        public String paperDeclaration;
        /** The variance (counted − theoretical) that crossed the threshold. */
        public java.math.BigDecimal variance;

        /**
         * Holds a pending close awaiting arbitration.
         *
         * @param counted the counted cash total
         * @param withdrawn the withdrawal
         * @param detail the denominations detail JSON
         * @param paperDeclaration the paper-tender declaration JSON
         * @param variance the over-threshold variance
         */
        public PendingClose(java.math.BigDecimal counted, java.math.BigDecimal withdrawn,
                String detail, String paperDeclaration, java.math.BigDecimal variance) {
            this.counted = counted;
            this.withdrawn = withdrawn;
            this.detail = detail;
            this.paperDeclaration = paperDeclaration;
            this.variance = variance;
        }

        /**
         * Returns the variance formatted for display (French comma).
         *
         * @return the formatted variance
         */
        public String getVarianceFormatted() {
            java.math.BigDecimal v = variance == null ? java.math.BigDecimal.ZERO : variance;
            return String.format("%.2f", v.setScale(2, RoundingMode.HALF_UP)).replace('.', ',');
        }
    }

    /**
     * Returns the resolver of the localised labels, for the sub-states.
     *
     * <p>A sub-state such as {@link com.intermarche.pos.ui.ticket.TicketState}
     * is a value object held in a field, not a bean, so it has no resolver of
     * its own; it reaches this one through its parent (LC-13-06).
     *
     * @return the resolver, or null in a unit test that wired none
     */
    public Messages messages() {
        return i18n;
    }

    /**
     * Bundle key of the one answer the register gives when a function needs a
     * closed ticket and there is none (LC-13-06).
     */
    public static final String NO_LAST_TICKET_KEY = "op.aucunTicket";

    /**
     * Bundle key of the one answer the register gives when training mode forbids
     * a real document (LC-13-06).
     */
    public static final String TRAINING_FORBIDDEN_KEY = "op.fonctionIndisponibleFormation";

    /**
     * THE gate of every function that works on the last closed ticket —
     * reprint, identification barcode, card-receipt duplicate, ticket by
     * email. They all need the same two conditions and used to check them
     * each in its own way: one printed nothing at all and said nothing, two
     * refused with "AUCUN TICKET A IMPRIMER", the fourth opened a whole screen
     * to announce there was no ticket, and training mode was refused with two
     * different sentences. Four functions on the same menu, four behaviours.
     *
     * <p>The caller reads it as a question — "may I?" — and does nothing more
     * when the answer is no: the message is already on the sale screen and the
     * state already touched.
     *
     * @return true when there is a last closed ticket and the register is not
     *         in training mode; false after the refusal has been posted
     */
    public boolean requireLastClosedTicket() {
        if (trainingMode) {
            // Training touches no real document: no duplicata, no counter bump,
            // and no number that could name a sale that never happened.
            ticket.setError(i18n.get(TRAINING_FORBIDDEN_KEY));
            touch();
            return false;
        }
        if (lastClosedTicketId == null) {
            ticket.setError(i18n.get(NO_LAST_TICKET_KEY));
            touch();
            return false;
        }
        return true;
    }

    // --- Current ticket pagination ---

    /** Number of ticket lines per page. */
    private static final int PAGE_SIZE = 6;

    /** Current page of the ticket display. */
    public int ticketCurrentPage = 0;

    /** Global error message, or null. */
    public String globalError = null;

    /**
     * The current back-office message pushed DOWN to this register (EXP-20),
     * shown non-disruptively on the cashier's sale screen (never the customer
     * display); empty when there is none. Pushed by the store node, not tied to
     * a sale, so it deliberately survives {@link #clearTicket()}.
     */
    public volatile String backOfficeMessage = "";

    /**
     * The current back-office SURVEY (EXP-89), or null when the down message is a
     * plain notice: a question the cashier answers, the answer travelling back up
     * to the node. Node-pushed, survives {@link #clearTicket()} like the message.
     */
    public volatile BackOfficeSurvey backOfficeSurvey = null;

    /**
     * The SERVICE REQUEST this lane has sent to the back office
     * (LC-01-07-03/-04), the register's own indicator state: no request, one
     * IN PROGRESS since it was pushed, or one the back office has ACKNOWLEDGED
     * and the cashier has not validated yet (LC-01-07-06).
     *
     * <p>TWO INDICATORS AND ONE STATE, deliberately: the acknowledged request
     * is no longer in progress — the questionnaire asks for an indicator
     * DISTINCT from the in-progress one, not for both at once, so one state
     * names what the lane owes the back office right now: wait (PENDING),
     * read the answer and validate (ACKED), or nothing (NONE).
     *
     * <p>Node-pushed, so it survives {@link #clearTicket()} like the message:
     * a request for help outlives the customer whose basket interrupted the
     * reading of its answer.
     */
    public volatile ServiceRequestStatus serviceRequest = ServiceRequestStatus.NONE;

    /**
     * The lifecycle of a service request as the lane sees it
     * (LC-01-07-03/-04/-05/-06).
     */
    public enum ServiceRequestStatus {

        /** Nothing was asked, or the request was cancelled, refused, validated. */
        NONE,

        /** Pushed, the back office has not acknowledged it yet (LC-01-07-03). */
        PENDING,

        /** The back office acknowledged it, the lane has not validated it (LC-01-07-04). */
        ACKED
    }

    /**
     * The administered reason of the service request in progress, or null:
     * what the indicator names so the cashier knows what was asked for.
     */
    public volatile String serviceRequestReason = null;

    /**
     * True while a back-office message has arrived and not been acknowledged
     * by the operator (LC-01-08-04): the indicator zone shows it and pressing
     * it consults the message. Set when a message is received, cleared by the
     * operator's acknowledgement or by the message's own disappearance
     * (LC-01-08-05). Survives {@link #clearTicket()} like the message.
     */
    public volatile boolean boMessageArrived = false;

    /**
     * A back-office survey pushed to the register (EXP-89): a question and the
     * answer options offered to the cashier.
     */
    public static class BackOfficeSurvey implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        /** The survey question shown to the cashier. */
        public String question;
        /** The answer options offered. */
        public java.util.List<String> options = new java.util.ArrayList<>();
    }

    /** The button menu currently shown; the sale gestures when the screen opens. */
    public PosMenu menu = PosMenu.ARTICLES;

    /**
     * Returns the five menus in tab order — what the bottom bar renders.
     *
     * @return the menus, never empty
     */
    public java.util.List<PosMenu> getMenus() {
        return PosMenu.ALL;
    }

    /**
     * Creates the POS state and wires the ticket back-reference.
     */
    public PosState() {
        this.ticket.setParent(this);
    }

    /**
     * Bumps the version counter so the UI polling refreshes.
     */
    public void touch() {
        this.version++;
    }

    /**
     * Clears the current ticket and every transaction-related sub-state.
     *
     * <p>IT ALSO BRINGS THE BUTTON MENU BACK TO {@link PosMenu#ARTICLES}. The next
     * customer's first gesture is a scan, and whoever just paid left the register on
     * the settlement menu: a new ticket that opens on the keys of the previous one
     * costs the cashier a tap on every sale. Done here rather than at the end of the
     * payment, because every way a ticket ends — paid, abandoned, parked, operator
     * logged out — passes through this broom, and a menu reset placed on one of them
     * would be missing from the others.
     */
    public void clearTicket() {
        ticket.clear();
        menu = PosMenu.ARTICLES;
        fidelity.clear();
        payment.reset();
        selectedTicketIndex = -1;
        lastEnteredItemId = null;
        donationLineUid = null;
        priceModState.clear();
        collectArmed = false;
        armedQuantity = null;
        pendingManualWeightKg = null;
        entryPrompt.clear();
        abandonReason = "";
        abandonPrintRequested = false;
        ticketCurrentPage = 0;
        customerMessage = "";
        invoice.clear();
        supervisorSessionBadge = null;
        cancelByScanArmed = false;
        recalledOrderCode = null;
    }

    /**
     * Clears only the registered payments.
     */
    public void clearPayments() {
        payment.clearPayments();
        touch();
    }

    // --- Computations ---

    /**
     * Returns the remaining amount due, rounded to 2 decimals.
     *
     * @return the remaining due (ticket total minus paid amount)
     */
    public BigDecimal getRemaining() {
        return ticket.totalAmount.subtract(payment.paidAmount).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Returns the remaining due formatted for display (French comma).
     *
     * @return the formatted remaining due
     */
    public String getRemainingFormatted() {
        return String.format("%.2f", getRemaining()).replace(".", ",");
    }

    /**
     * Returns the remaining due formatted for the numpad (dot separator).
     *
     * @return the remaining due as a numpad-ready string
     */
    public String getRemainingNumpad() {
        return String.format("%.2f", getRemaining());
    }

    /**
     * When the operator-forced monetics degraded mode expires, or null when it is
     * not forced ({@code LC-07-08-02} to {@code -05}).
     *
     * <p>AN EXPIRY AND NOT A BOOLEAN, deliberately. A degraded mode left on is a
     * till that stops asking the monetics for authorization, and the operator who
     * turned it on is not the one who will still be there in three hours. So the
     * shop administers how long a forcing lasts, the flag carries its own end, and
     * {@code LC-07-08-04}'s automatic deactivation costs no scheduler.
     */
    public java.time.LocalDateTime moneticsDegradedUntil = null;

    /**
     * Tells whether the operator has forced the monetics into degraded mode
     * ({@code LC-07-08-05}: this is what the permanent indicator reads).
     *
     * <p>Reading it EXPIRES it: the forcing ends by itself and every consumer —
     * the terminal gate, the screen indicator — sees the same instant of truth,
     * with no scheduled task to keep in step with them.
     *
     * @return true while the forcing is on and has not run out
     */
    public boolean isMoneticsDegradedForced() {
        if (moneticsDegradedUntil == null) {
            return false;
        }
        if (moneticsDegradedUntil.isBefore(java.time.LocalDateTime.now())) {
            moneticsDegradedUntil = null;
            return false;
        }
        return true;
    }

    /**
     * The legal cash-rounding step in cents, refreshed from the back office at
     * payment entry; zero or one means this shop does not round
     * ({@code LC-07-03-01}).
     *
     * <p>Held HERE and not read from the settings on every call because the screen,
     * the customer display and the printer all ask for the rounded amount several
     * times per second while the payment screen polls, and a parameter lookup per
     * question would be a database round trip per question.
     */
    public int cashRoundingStepCents = 0;

    /**
     * Returns what the customer settles in cash for the remaining due
     * ({@code LC-07-03-01}), which is the remaining due itself when the shop does
     * not round.
     *
     * @return the remaining due rounded to the administered step
     */
    public BigDecimal getCashRoundedRemaining() {
        return com.intermarche.pos.ui.payment.CashRounding.round(
                getRemaining(), cashRoundingStepCents);
    }

    /**
     * Tells whether the rounding actually changes what the customer hands over —
     * the only case worth showing a second figure for ({@code LC-07-03-02/03}).
     *
     * @return true when the rounded amount differs from the real one
     */
    public boolean isCashRoundingVisible() {
        return cashRoundingStepCents > 1
                && getCashRoundedRemaining().compareTo(getRemaining()) != 0;
    }

    /**
     * Returns the rounded remaining due formatted for display (French comma).
     *
     * @return the formatted rounded remaining due
     */
    public String getCashRoundedRemainingFormatted() {
        return String.format("%.2f", getCashRoundedRemaining()).replace(".", ",");
    }

    /**
     * Returns the rounded remaining due formatted for the numpad (dot separator).
     *
     * @return the rounded remaining due as a numpad-ready string
     */
    public String getCashRoundedRemainingNumpad() {
        return String.format("%.2f", getCashRoundedRemaining());
    }

    /**
     * Indicates whether the register is locked (no operator logged in).
     *
     * @return true if locked
     */
    public boolean isLocked() {
        return auth.isLocked;
    }

    /**
     * Indicates whether the current ticket is a NET REFUND (EXP-38): its signed
     * total is negative, so the returns outweigh the purchases and the register
     * owes the customer money. The pay screen then offers the refund direction
     * instead of the ordinary collection.
     *
     * @return true when the signed ticket total is strictly negative
     */
    public boolean isNetRefund() {
        return ticket.totalAmount.signum() < 0;
    }

    /**
     * Returns the amount the register owes the customer on a net-refund ticket
     * (EXP-38), formatted for display (French comma) — the absolute value of the
     * negative remaining.
     *
     * @return the formatted refund due
     */
    public String getRefundDueFormatted() {
        return String.format("%.2f", getRemaining().abs()).replace(".", ",");
    }

    /**
     * Returns the display name of the logged-in operator.
     *
     * @return the operator name, or an empty string
     */
    public String getOperatorName() { return auth.operatorName; }

    /**
     * Returns the line targeted by contextual actions: the selected line,
     * or the last line when nothing is selected.
     *
     * @return the target line, or null when the ticket is empty
     */
    public TicketState.TicketItem getTargetItem() {
        if (selectedTicketIndex >= 0 && selectedTicketIndex < ticket.items.size()) {
            return ticket.items.get(selectedTicketIndex);
        }
        if (!ticket.items.isEmpty()) {
            return ticket.items.get(ticket.items.size() - 1);
        }
        return null;
    }

    /**
     * Returns the explicitly selected line.
     *
     * @return the selected line, or null when nothing is selected
     */
    public TicketState.TicketItem getSelectedItem() {
        if (selectedTicketIndex >= 0 && selectedTicketIndex < ticket.items.size()) {
            return ticket.items.get(selectedTicketIndex);
        }
        return null;
    }

    /**
     * Returns the ticket lines visible on the current page.
     *
     * @return the sublist of lines for the current page
     */
    public List<TicketState.TicketItem> getVisibleItems() {
        // The DISPLAY sequence, not the cart: the cancelled lines are shown
        // struck through at the rank they held. Paging counts them, because the
        // operator counts them too — a page that hid them would renumber itself
        // under the eye at each cancellation.
        List<TicketState.TicketItem> shown = ticket.displayItems();
        if (shown.isEmpty()) return Collections.emptyList();
        int maxPage = Math.max(0, (shown.size() - 1) / PAGE_SIZE);
        if (ticketCurrentPage > maxPage) ticketCurrentPage = maxPage;
        // The clamp above is what keeps the window inside the list: with a
        // non-empty ticket, fromIndex = page * PAGE_SIZE <= maxPage *
        // PAGE_SIZE <= size - 1. No further bound check is reachable — one
        // used to sit here and could never fire.
        int fromIndex = ticketCurrentPage * PAGE_SIZE;
        int toIndex = Math.min(fromIndex + PAGE_SIZE, shown.size());
        return shown.subList(fromIndex, toIndex);
    }

    /**
     * Indicates whether a previous ticket page exists.
     *
     * @return true if not on the first page
     */
    public boolean isHasPreviousPage() { return ticketCurrentPage > 0; }

    /**
     * Indicates whether a next ticket page exists.
     *
     * @return true if more lines follow the current page
     */
    public boolean isHasNextPage() { return (ticketCurrentPage + 1) * PAGE_SIZE < ticket.items.size(); }

    /**
     * Returns the 1-based current ticket page number for display.
     *
     * @return the current page number
     */
    public int getTicketCurrentPageDisplay() { return ticketCurrentPage + 1; }

    /**
     * Returns the total number of ticket pages (at least one).
     *
     * @return the page count
     */
    public int getTicketTotalPages() {
        if (ticket.items.isEmpty()) return 1;
        return (int) Math.ceil((double) ticket.items.size() / PAGE_SIZE);
    }

    /**
     * Positions the ticket display on the page holding the line with the given
     * uid (P1): every article entry brings the view onto the line it just created
     * — so a cashier scanning many articles always sees the last one, instead of
     * staying frozen on the first page. Manual paging stays possible; the next
     * scan brings the view back to that line's page. No-op for a null or unknown
     * uid, so an annulment (which leaves the display on a still-existing page via
     * the clamp) is untouched.
     *
     * @param uid the uid of the line to reveal
     */
    public void showLinePage(String uid) {
        if (uid == null) return;
        for (int i = 0; i < ticket.items.size(); i++) {
            if (uid.equals(ticket.items.get(i).uid)) {
                ticketCurrentPage = i / PAGE_SIZE;
                return;
            }
        }
    }

    /**
     * Moves to the next ticket page if one exists.
     */
    public void nextPage() { if (isHasNextPage()) { ticketCurrentPage++; touch(); } }

    /**
     * Moves to the previous ticket page if one exists.
     */
    public void prevPage() { if (isHasPreviousPage()) { ticketCurrentPage--; touch(); } }
}
