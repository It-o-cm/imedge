package com.intermarche.pos.ui.home;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.catalog.attribute.RestrictedTender;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.PriceModType;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.ui.ticket.TicketService;
import com.intermarche.pos.ui.ticket.TicketState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Home screen service: navigation, line selection, ticket-level actions and
 * the price-modification modal lifecycle.
 * <p>
 * Phase 0: price-modification values flow as {@link BigDecimal}.
 * <p>
 * Orchestrator of the main screen's gestures, and the place where the
 * line-modification family SPLITS: {@code submitPriceMod} routes the three
 * price types through the manager endorsement (parked gesture), while
 * QUANTITY applies directly — multiplying a scanned line is a normal sale
 * action, not an exception. The training toggle keeps the same two-step
 * shape as every guarded gesture (request parks, endorsed dispatch
 * performs), and the supervisor call is the register's only REAL-TIME
 * outbound HTTP (3-second timeout, outcome dropped into the message zone):
 * a call is a signal, not a document, hence no outbox.
 */
@ApplicationScoped
public class HomeService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(HomeService.class);

    /**
     * The localised operator messages: every sentence this class stamps on the
     * register's message band is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    @Inject
    PosState state;

    @Inject
    TicketService ticketService;

    /**
     * Resolves the software tare to deduct from a manually typed weight
     * ({@code LC-14-01-08}).
     *
     * <p>NOTHING IN THIS CLASS CALLS IT YET, and deliberately so: the tare is
     * deducted from a weight TYPED BEFORE THE ARTICLE, on the manual-weight key
     * of {@code LC-02-02-03}, and that key does not exist. It was briefly wired
     * onto the QUANTITE key over an existing weighed line, which put a keyed
     * figure over a measurement — the one thing {@code LC-02-13-15} forbids. The
     * resolver stays injected here because this is the service that will own that
     * key.
     */
    @Inject
    com.intermarche.pos.ui.balance.TareService tareService;

    /** The back-office parameters (endorsement policy — LC-03-02-08). */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** The administered lists: service requests and return types (BO-04-02-07). */
    @Inject
    com.intermarche.pos.service.PosListService posListService;

    /** The cash session, read for the drawer-cap alert (EXP-17). */
    @Inject
    com.intermarche.pos.service.CashSessionService cashSessionService;

    /** The payment writer, for the direct (unendorsed) ticket abandon (EXP-32). */
    @Inject
    com.intermarche.pos.ui.payment.PaymentService paymentService;

    /** Printer service — used by the endorsed operator-badge reprint. */
    @Inject
    com.intermarche.pos.ui.hardware.TicketPrinterService ticketPrinterService;

    @Inject
    EndorsementService endorsementService;

    @Inject
    TechnicalEventService technicalEventService;

    @Inject
    TicketNumberService ticketNumberService;

    @Inject
    SyncOutboxService syncOutboxService;

    @Inject
    ObjectMapper objectMapper;

    /** Shared token sent with the supervisor call; absent = none. */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "pos.sync.token")
    java.util.Optional<String> supervisorToken;

    /** HTTP client of the real-time supervisor call. */
    private static final java.net.http.HttpClient SUPERVISOR_CLIENT = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(3))
            .build();

    // --- Navigation ---

    /**
     * Shows one of the five button menus.
     *
     * @param menu the menu to show, never null
     */
    public void selectMenu(com.intermarche.pos.ui.PosMenu menu) {
        LOGGER.info("Entering method selectMenu with menu: " + menu);
        if (menu != null && menu.isDirect()) {
            // A DIRECT KEY IS NOT A MENU: it carries no button list, so selecting
            // it would leave the screen showing an empty block. The bottom bar
            // links it to its screen and never here; a stale page or a hand-typed
            // URL that still asks for it keeps the menu it was on.
            state.touch();
            LOGGER.info("Exiting method selectMenu: direct key, menu unchanged");
            return;
        }
        state.menu = menu;
        state.touch();
        LOGGER.info("Exiting method selectMenu");
    }

    /**
     * Toggles the selection of a ticket line by its index.
     *
     * @param index the index of the line in the full ticket
     */
    public void selectLine(String uid) {
        LOGGER.info("Entering method selectLine with uid: " + uid);
        // P2: select by the line's IDENTITY, not by a page-local offset. The
        // template passes the visible-page rank, which coincides with the ticket
        // rank only on page 1 — on any other page an offset would select the wrong
        // line. Resolving the uid to its whole-ticket index is exact whatever the
        // page, and stays correct even if a line moved between render and click.
        int index = -1;
        for (int i = 0; i < state.ticket.items.size(); i++) {
            if (state.ticket.items.get(i).uid.equals(uid)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            state.touch();
            LOGGER.info("Exiting method selectLine");
            return;
        }
        if (state.selectedTicketIndex == index) {
            state.selectedTicketIndex = -1;
        } else {
            state.selectedTicketIndex = index;
        }
        state.touch();
        LOGGER.info("Exiting method selectLine");
    }

    /**
     * One restricted tender and the part of the running basket it may pay
     * ({@code LC-09-01-12}, {@code -14}, {@code -16}, {@code -18}).
     *
     * @param label  the tender's wording, as the cashier reads it
     * @param amount the eligible total, formatted for display
     */
    public record RestrictedTenderRow(String label, String amount) {
    }

    /**
     * Totals what the running basket may be paid with, tender by tender.
     *
     * <p>RECOMPUTED FROM THE LINES AT EVERY CALL, never kept as a field the sale
     * updates. The screen asks for a total that is "réactualisé à chaque nouvel
     * enregistrement d'article", and a cart also loses lines, changes quantities and
     * takes discounts — a running field would have to be corrected in every one of
     * those places, and the first one forgotten would be a wrong figure on screen with
     * nothing to show it was wrong.
     *
     * <p>A tender no line is eligible for is DROPPED rather than shown at zero: the
     * indicator exists to tell the cashier what the customer can use, and three
     * permanent zeroes beside the total teach them to stop reading it.
     *
     * @return the tenders with an eligible amount, in administered order, possibly empty
     */
    /**
     * The waiting label shown on the sale screen between two customers (EXP-03):
     * the administered "next customer" prompt rendered when the ticket is empty,
     * threaded to both the full sale page and the polled ticket fragment.
     *
     * @return the administered waiting label
     */
    public String waitingLabel() {
        LOGGER.info("Entering method waitingLabel");
        LOGGER.info("Exiting method waitingLabel");
        return posSettingsService.waitingLabel();
    }

    /**
     * The drawer-cap alert shown on the sale screen (EXP-17): a PRÉLÈVEMENT
     * warning naming the drawer's theoretical cash when it exceeds the
     * administered cap, or null when the cap is not exceeded (or not set). It
     * never blocks the sale — it is only an indicator.
     *
     * <p>IT ASKS FOR BANKNOTES, and says so. The ceiling is the sum the shop's
     * insurance guarantees on the whole till — float and takings together — and what
     * takes a drawer back under it is the notes: the coins have to stay, there is
     * change to give. The alert used to ask for a "prélèvement" without saying of
     * what, which left the cashier to guess and the coins to leave with the rest.
     *
     * @return the alert message, or null when nothing to warn about
     */
    public String drawerCapAlert() {
        LOGGER.info("Entering method drawerCapAlert");
        java.math.BigDecimal excess = cashSessionService.drawerCapExcess();
        if (excess == null) {
            LOGGER.info("Exiting method drawerCapAlert");
            return null;
        }
        LOGGER.info("Exiting method drawerCapAlert");
        return i18n.format("op.prelevementBilletsContenuTiroir",
                String.format("%.2f", excess).replace('.', ','));
    }

    public List<RestrictedTenderRow> restrictedTenderRows() {
        LOGGER.info("Entering method restrictedTenderRows");
        List<RestrictedTenderRow> rows = new ArrayList<>();
        for (RestrictedTender tender
                : RestrictedTender.administered(posSettingsService.restrictedTenders())) {
            BigDecimal eligible = BigDecimal.ZERO;
            for (TicketState.TicketItem item : state.ticket.items) {
                if (tender.covers(item.restrictedTenders)) {
                    eligible = eligible.add(item.getTotalPrice());
                }
            }
            if (eligible.signum() > 0) {
                rows.add(new RestrictedTenderRow(tender.label(),
                        String.format("%.2f", eligible.setScale(2, RoundingMode.HALF_UP))
                                .replace('.', ',')));
            }
        }
        LOGGER.info("Exiting method restrictedTenderRows");
        return rows;
    }

    /**
     * Arms a quantity for the NEXT article named ({@code LC-02-13-04/05/06}).
     *
     * <p>The quantity key has always applied a quantity to a line already registered.
     * This is the other order the questionnaire asks for — key the quantity, then
     * scan, key the EAN or key the internal code — and the three are covered at once
     * because the three end in the same {@code addItem}.
     *
     * @param quantity the quantity to arm
     */
    public void armQuantity(BigDecimal quantity) {
        LOGGER.info("Entering method armQuantity with quantity: " + quantity);
        if (quantity == null || quantity.signum() <= 0) {
            state.ticket.setError(i18n.get("op.quantiteInvalide"));
            state.priceModState.clear();
            state.touch();
            LOGGER.info("Exiting method armQuantity");
            return;
        }
        state.armedQuantity = quantity;
        state.priceModState.clear();
        state.ticket.setNotice(i18n.format("op.quantiteSaisissezArticle",
                quantity.stripTrailingZeros().toPlainString()));
        state.touch();
        LOGGER.info("Exiting method armQuantity");
    }

    /**
     * Arms the GROSS weight keyed by the operator for the NEXT weighed article
     * named ({@code LC-02-02-03}): the key, then the weight, then the article —
     * the order the requirement states.
     *
     * <p>It is the fallback of a lane whose scale is absent or refused, and the
     * only path where the register deducts the tare itself
     * ({@code LC-14-01-08}): the deduction happens at the article, where the
     * article tare is known, not here.
     *
     * <p>A null, zero or negative weight arms nothing and says so, exactly as
     * the quantity key does: an armed figure nobody can see is worse than a
     * refusal.
     *
     * @param grossWeightKg the gross weight in kilograms, as typed
     */
    public void armManualWeight(BigDecimal grossWeightKg) {
        LOGGER.info("Entering method armManualWeight with grossWeightKg: " + grossWeightKg);
        if (grossWeightKg == null || grossWeightKg.signum() <= 0) {
            state.pendingManualWeightKg = null;
            state.ticket.setError(i18n.get("op.poidsInvalide"));
            state.touch();
            LOGGER.info("Exiting method armManualWeight");
            return;
        }
        state.pendingManualWeightKg = grossWeightKg;
        state.ticket.setNotice(i18n.format("op.poidsBrutSaisissezArticle",
                grossWeightKg.stripTrailingZeros().toPlainString().replace('.', ',')));
        state.touch();
        LOGGER.info("Exiting method armManualWeight");
    }

    /**
     * Gives up the armed manual weight ({@code LC-02-02-03}): the operator left
     * the weighing screen without choosing an article, and a weight left armed
     * would be laid over the next one they ring.
     */
    public void clearManualWeight() {
        LOGGER.info("Entering method clearManualWeight");
        state.pendingManualWeightKg = null;
        state.touch();
        LOGGER.info("Exiting method clearManualWeight");
    }

    /**
     * Takes ONE UNIT — or the quantity keyed just before — off the line a
     * cancel-by-scan found, rather than striking the whole line out.
     *
     * <p>SCANNING AN ARTICLE TWICE AND CANCELLING IT ONCE ARE NOT THE SAME
     * GESTURE. The register used to remove the line whole: a customer taking one
     * of the three yogurts back lost all three, and the cashier had to re-scan
     * the other two. The scan names an ARTICLE, and the unit is what leaves the
     * basket with it — so the line loses one unit, and only disappears when its
     * last unit goes.
     *
     * <p>A QUANTITY KEYED FIRST IS HONOURED, because that is how the operator
     * says "take two back": the armed quantity is consumed here exactly as the
     * article entry consumes it, so it never leaks onto the next scan. A keyed
     * quantity at or above what the line carries empties it, and the line is then
     * cancelled through the ordinary path — with its endorsement, because
     * removing a line is the gesture a manager answers for.
     *
     * <p>Only a UNIT line is decremented. A weighed line's quantity is a weight
     * ({@code LC-02-13-15}) and half a weighing means nothing: it is cancelled
     * whole, as before.
     *
     * <p>Package-private so every refusal arm can be reached directly, like
     * {@link #cancelLineByUid(String)} beside it: through {@code cancelByScan}
     * the line is always found, so the not-found arm would be untestable.
     *
     * @param targetUid the uid of the line the scan found
     * @return true when the line was decremented and nothing else must happen,
     *         false when the caller must cancel the line outright
     */
    boolean decrementByScan(String targetUid) {
        LOGGER.info("Entering method decrementByScan with targetUid: " + targetUid);
        BigDecimal asked = state.armedQuantity;
        state.armedQuantity = null;
        TicketState.TicketItem item = state.ticket.items.stream()
                .filter(i -> i.uid.equals(targetUid))
                .findFirst()
                .orElse(null);
        if (item == null || (item.plu != null && !item.plu.isEmpty())
                || item.priceEmbedded || item.moneyProduct
                || item.getTotalPrice().signum() < 0) {
            LOGGER.info("Exiting method decrementByScan with false");
            return false;
        }
        BigDecimal units = asked == null || asked.signum() <= 0 ? BigDecimal.ONE : asked;
        if (item.quantity.compareTo(units) <= 0) {
            LOGGER.info("Exiting method decrementByScan with false");
            return false;
        }
        item.quantity = item.quantity.subtract(units);
        ticketService.recalculateTotal(state);
        state.ticket.setNotice(i18n.format("op.quantiteRetireeArticle",
                units.stripTrailingZeros().toPlainString()));
        LOGGER.info("Exiting method decrementByScan with true");
        return true;
    }

    /**
     * Repeats the registration of the last article ({@code LC-02-13-12/13}).
     *
     * <p>IT ADDS ONE TO THE LINE, it does not ring a second one: the register groups
     * identical articles, and {@code LC-02-13-17} says that where grouping is on the
     * repetition raises the quantity of the existing line. Adding through the ordinary
     * path would also re-ask a "prix requis" article for its price, which
     * {@code LC-02-13-14} forbids — the line already knows what was paid.
     *
     * <p>TWO KINDS OF ARTICLE ARE REFUSED, and refused OUT LOUD. A weighed line is a
     * measurement ({@code LC-02-13-15}) and a price-embedded label names one physical
     * object ({@code LC-02-13-16}); repeating either would invent a weight nobody put
     * on the scale.
     *
     * <p>THERE IS NO CEILING, and a keyed quantity is no longer refused. The register
     * used to turn down a line whose quantity was not one, to protect the operator
     * from repeating over a figure they had deliberately entered (EXP-29). That guard
     * read the QUANTITY, which it cannot tell apart from the quantity the repetition
     * itself had just raised: the second press on the same line therefore refused what
     * the first press had done, capping the function at two units and sending the
     * cashier to the quantity key for a third. Adding one unit is an explicit gesture
     * with a visible outcome on the line, so it is allowed as often as it is pressed.
     */
    public void repeatLastItem() {
        LOGGER.info("Entering method repeatLastItem");
        TicketState.TicketItem last = lastEnteredItem();
        if (last == null) {
            state.ticket.setError(i18n.get("op.aucunArticleRepeter"));
            state.touch();
            LOGGER.info("Exiting method repeatLastItem");
            return;
        }
        if (last.plu != null && !last.plu.isEmpty()) {
            state.ticket.setError(i18n.get("op.repetitionImpossibleArticlePesee"));
            state.touch();
            LOGGER.info("Exiting method repeatLastItem");
            return;
        }
        if (last.priceEmbedded) {
            state.ticket.setError(i18n.get("op.repetitionImpossibleArticlePrix"));
            state.touch();
            LOGGER.info("Exiting method repeatLastItem");
            return;
        }
        last.quantity = last.quantity.add(BigDecimal.ONE);
        ticketService.recalculateTotal(state);
        state.ticket.setNotice(i18n.format("op.articleRepete", last.label));
        state.touch();
        LOGGER.info("Exiting method repeatLastItem");
    }

    /**
     * Returns the line the last registration created, when it is still on the ticket.
     *
     * @return the line, or null when nothing was registered or it has been cancelled
     */
    private TicketState.TicketItem lastEnteredItem() {
        if (state.lastEnteredItemId == null) {
            return null;
        }
        for (TicketState.TicketItem item : state.ticket.items) {
            if (state.lastEnteredItemId.equals(item.uid)) {
                return item;
            }
        }
        return null;
    }

    /**
     * The "à enlever" key ({@code LC-02-08-01}).
     *
     * <p>ONE KEY, TWO GESTURES, told apart by whether a line is EXPLICITLY selected.
     * With a line selected, it marks or unmarks that line ({@code LC-02-08-03}) —
     * the article was already rung and the cashier changes their mind about where it
     * goes. With nothing selected, it arms the next article ({@code LC-02-08-02}) —
     * the cashier knows before scanning that this one goes to the desk.
     *
     * <p>The implicit "last line" fallback the other line gestures use is
     * deliberately NOT applied here: it would make the key unable to arm anything as
     * soon as the ticket had a line, which is every moment but the first.
     */
    public void toggleCollect() {
        LOGGER.info("Entering method toggleCollect");
        TicketState.TicketItem selected = state.getSelectedItem();
        if (selected != null) {
            selected.toCollect = !selected.toCollect;
            state.selectedTicketIndex = -1;
            state.ticket.setNotice(i18n.get(selected.toCollect
                    ? "op.articleMarqueEnlever" : "op.marquageEnleverRetire"));
            state.touch();
            LOGGER.info("Exiting method toggleCollect");
            return;
        }
        state.collectArmed = !state.collectArmed;
        state.ticket.setNotice(i18n.get(state.collectArmed ? "op.prochainArticleEnlever" : "op.marquageEnleverAnnule"));
        state.touch();
        LOGGER.info("Exiting method toggleCollect");
    }

    // --- Ticket actions ---

    /**
     * Cancels the targeted line: directly when it is the last entered line,
     * otherwise through a manager endorsement.
     */
    public void cancelLine() {
        LOGGER.info("Entering method cancelLine");
        String targetUid = null;
        if (state.selectedTicketIndex >= 0 && state.selectedTicketIndex < state.ticket.items.size()) {
            targetUid = state.ticket.items.get(state.selectedTicketIndex).uid;
        } else if (!state.ticket.items.isEmpty()) {
            targetUid = state.ticket.items.get(state.ticket.items.size() - 1).uid;
        }

        if (targetUid == null) { LOGGER.info("Exiting method cancelLine"); return; }

        cancelLineByUid(targetUid);

        state.selectedTicketIndex = -1;
        state.touch();
        LOGGER.info("Exiting method cancelLine");
    }

    /**
     * Cancels the line named by its uid — the shared decision for the CANCEL key
     * and the cancel-by-scan mode (EXP-32/35/36).
     *
     * <p>Order of guards: the anti-fraud last-article guard first (EXP-35), then
     * the per-gesture endorsement decision (EXP-32/33). The last-entered quick
     * cancel and an older line each carry their own administered flag; either
     * proceeds directly when authorized (flag off, connected supervisor, or an open
     * supervisor session), otherwise it parks the CANCEL_LINE endorsement.
     *
     * @param targetUid the uid of the line to cancel
     */
    void cancelLineByUid(String targetUid) {
        LOGGER.info("Entering method cancelLineByUid with targetUid: " + targetUid);
        if (state.ticket.items.size() == 1
                && targetUid.equals(state.ticket.items.get(0).uid)
                && !onlyNegativeLines()) {
            // EXP-35: cancelling the only line would zero the ticket, a known
            // fraud door. The refusal names the exit: the ticket abandon. A ticket
            // that sold NOTHING is exempt — see onlyNegativeLines().
            state.ticket.setError(i18n.get("op.dernierArticleUtilisezAbandon"));
            LOGGER.info("Exiting method cancelLineByUid");
            return;
        }
        // BO-06-06-04: WHY before WHO. A shop that administers justifications
        // for this gesture is asked for one before anything else — before the
        // aval, so the reason survives the parking the aval imposes, and because
        // a cashier explains the cancellation before a manager authorises it. No
        // list administered, no question: the gesture is the one of before.
        if (state.ticket.pendingCancelReasonCode == null
                && !posListService.justificationsOf(
                        com.intermarche.pos.service.PosListService.FUNCTION_CANCEL_LINE).isEmpty()) {
            state.ticket.pendingCancelUid = targetUid;
            state.touch();
            LOGGER.info("Exiting method cancelLineByUid");
            return;
        }
        boolean isLast = targetUid.equals(state.lastEnteredItemId);
        String gestureKey = isLast ? "cancel-last" : "cancel-line";
        if (endorsementService.gestureAuthorized(state, gestureKey)) {
            cancelLineNow(targetUid, isLast);
            LOGGER.info("Exiting method cancelLineByUid");
            return;
        }
        // BO-10-02-01: the gesture IS watched — WHAT the shop does with it is
        // administered. NONE is not reachable here: the flag above is the off
        // switch, so this point only ever says warn, refuse, or ask a manager.
        com.intermarche.pos.ui.endorsement.ControlLevel level =
                endorsementService.check(state, com.intermarche.pos.ui.endorsement.ControlPoint.CANCEL_LINE);
        switch (level) {
            case BLOCK -> state.ticket.setError(endorsementService.controlMessage(
                    com.intermarche.pos.ui.endorsement.ControlPoint.CANCEL_LINE, level));
            case WARN -> {
                state.ticket.setWarning(endorsementService.controlMessage(
                        com.intermarche.pos.ui.endorsement.ControlPoint.CANCEL_LINE, level));
                cancelLineNow(targetUid, isLast);
            }
            default -> endorsementService.requestAuthorization(state, "CANCEL_LINE_" + targetUid);
        }
        LOGGER.info("Exiting method cancelLineByUid");
    }

    /**
     * Cancels the line, here and now, with no question asked.
     *
     * <p>Split out because the cancellation happens on two paths — the
     * unwatched gesture and the control point that let it through with a
     * warning — and a line cancelled two ways would eventually be cancelled two
     * different ways.
     *
     * @param targetUid the uid of the line to cancel
     * @param isLast whether that line was the last one registered
     */
    private void cancelLineNow(String targetUid, boolean isLast) {
        ticketService.cancelItemById(state, targetUid);
        if (isLast) {
            state.lastEnteredItemId = null;
        }
    }

    /**
     * Records the justification the operator tapped and resumes the
     * cancellation it was asked for ({@code BO-06-06-04}).
     *
     * <p>Nothing is cancelled here: the reason is stored and the gesture
     * re-enters {@link #cancelLineByUid(String)} at its start, so the
     * anti-fraud guard, the administered control point and the aval all apply
     * exactly as they would have. A code the administered list does not carry
     * is refused rather than recorded as itself — a state grouping by reason
     * must not grow a column of codes nobody administered.
     *
     * @param code the code of the justification the operator tapped
     */
    public void cancelLineWithReason(String code) {
        LOGGER.info("Entering method cancelLineWithReason with code: " + code);
        String targetUid = state.ticket.pendingCancelUid;
        if (targetUid == null) {
            LOGGER.info("Exiting method cancelLineWithReason");
            return;
        }
        com.intermarche.pos.service.PosListService.ListChoice justification =
                posListService.justificationOf(
                        com.intermarche.pos.service.PosListService.FUNCTION_CANCEL_LINE, code);
        if (justification == null) {
            state.ticket.setError(i18n.get("op.motifAnnulationInconnu"));
            state.touch();
            LOGGER.info("Exiting method cancelLineWithReason");
            return;
        }
        state.ticket.pendingCancelUid = null;
        state.ticket.pendingCancelReasonCode = justification.code();
        state.ticket.pendingCancelReasonLabel = justification.label();
        cancelLineByUid(targetUid);
        LOGGER.info("Exiting method cancelLineWithReason");
    }

    /**
     * Abandons the cancellation the register was asking a justification for
     * ({@code BO-06-06-04}): the line stays on the ticket.
     */
    public void cancelLineReasonAbandoned() {
        LOGGER.info("Entering method cancelLineReasonAbandoned");
        state.ticket.pendingCancelUid = null;
        state.ticket.pendingCancelReasonCode = null;
        state.ticket.pendingCancelReasonLabel = null;
        state.touch();
        LOGGER.info("Exiting method cancelLineReasonAbandoned");
    }

    /**
     * The justifications offered for a line cancellation
     * ({@code BO-06-06-04}), for the chooser screen.
     *
     * @return the justifications, in display order, empty when the shop
     *         administers none
     */
    public List<com.intermarche.pos.service.PosListService.ListChoice> cancelReasons() {
        LOGGER.info("Entering method cancelReasons");
        LOGGER.info("Exiting method cancelReasons");
        return posListService.justificationsOf(
                com.intermarche.pos.service.PosListService.FUNCTION_CANCEL_LINE);
    }

    /**
     * Applies the QUANTITY control point to a quantity the cashier just keyed
     * (BO-10-02-06).
     *
     * <p>Answers whether the caller may go on. A warning leaves a notice and
     * lets it go on; a refusal and an aval request both stop it, the second
     * having parked the gesture so the manager's credential replays it.
     *
     * @param uid the line the quantity is keyed on
     * @param value the quantity keyed
     * @return true when the quantity may be applied
     */
    private boolean quantityControlPassed(String uid, BigDecimal value) {
        int ceiling = posSettingsService.controlQuantityMax();
        // A ceiling of zero or less is the absence of a ceiling, not a ceiling
        // every quantity exceeds: the same reading as the ticket total's.
        if (ceiling <= 0 || value.compareTo(BigDecimal.valueOf(ceiling)) <= 0) {
            return true;
        }
        com.intermarche.pos.ui.endorsement.ControlLevel level =
                endorsementService.check(state, com.intermarche.pos.ui.endorsement.ControlPoint.QUANTITY);
        switch (level) {
            case BLOCK -> {
                state.ticket.setError(endorsementService.controlMessage(
                        com.intermarche.pos.ui.endorsement.ControlPoint.QUANTITY, level));
                return false;
            }
            case ENDORSE -> {
                endorsementService.requestPriceModification(
                        state, com.intermarche.pos.ui.PriceModType.QUANTITY, uid, value);
                return false;
            }
            case WARN -> state.ticket.setWarning(endorsementService.controlMessage(
                    com.intermarche.pos.ui.endorsement.ControlPoint.QUANTITY, level));
            default -> {
                // No control administered: the quantity goes through.
            }
        }
        return true;
    }

    /**
     * Applies the ITEM AMOUNT control point to a price the cashier just keyed
     * (BO-10-02-16).
     *
     * <p>Answers whether the caller may go on, exactly as the quantity's does:
     * a warning leaves a notice and lets it go on, a refusal and an aval
     * request both stop it, the second having parked the gesture so the
     * manager's credential replays it.
     *
     * <p>THE COMPARISON IS ABSOLUTE. The questionnaire asks the ceiling to hold
     * "aussi bien aux ventes qu'aux retours", and a return is the same figure
     * with the other sign: comparing the signed value would let a shop plafond
     * its sales and leave its refunds open.
     *
     * <p>A blank ceiling is the absence of a ceiling and not a ceiling of zero,
     * the same reading as the ticket total's.
     *
     * @param uid the line the price is keyed on
     * @param value the price keyed
     * @return true when the price may be applied
     */
    private boolean itemAmountControlPassed(String uid, BigDecimal value) {
        java.math.BigDecimal ceiling = posSettingsService.controlItemAmountMax();
        if (ceiling == null || value == null || value.abs().compareTo(ceiling) <= 0) {
            return true;
        }
        com.intermarche.pos.ui.endorsement.ControlLevel level =
                endorsementService.check(state, com.intermarche.pos.ui.endorsement.ControlPoint.ITEM_AMOUNT);
        switch (level) {
            case BLOCK -> {
                state.ticket.setError(endorsementService.controlMessage(
                        com.intermarche.pos.ui.endorsement.ControlPoint.ITEM_AMOUNT, level));
                return false;
            }
            case ENDORSE -> {
                endorsementService.requestPriceModification(
                        state, com.intermarche.pos.ui.PriceModType.FORCE_PRICE, uid, value);
                return false;
            }
            case WARN -> state.ticket.setWarning(endorsementService.controlMessage(
                    com.intermarche.pos.ui.endorsement.ControlPoint.ITEM_AMOUNT, level));
            default -> {
                // No control administered: the price goes through.
            }
        }
        return true;
    }

    /**
     * Whether the ticket holds nothing but negative lines — a deposit return, a
     * reprise — and therefore nothing sold.
     *
     * <p>What the last-article guard protects is a SALE being quietly zeroed. A
     * ticket carrying only money owed BACK to the customer has no sale to empty, so
     * the door that guard watches does not exist there. Without this exemption a
     * cashier who rang a deposit return by mistake had no way out but the ticket
     * abandon — its reason, its endorsement, its printed document — for a bottle the
     * customer changed their mind about. Worse, a second press on DÉCONSIGNATION
     * adds no line but raises the quantity of the first, and the quantity key
     * refuses a negative line: the ticket was a dead end.
     *
     * <p>An empty ticket answers true, and that costs nothing: the only caller
     * already demands exactly one line, so the answer is never read there. A guard
     * against the empty case would be a branch no test could ever reach.
     *
     * @return true when every line of the ticket is negative
     */
    private boolean onlyNegativeLines() {
        return state.ticket.items.stream().allMatch(TicketState.TicketItem::isNegative);
    }

    /**
     * Requests a manager endorsement to cancel the whole ticket.
     */
    public void cancelTicket() {
        LOGGER.info("Entering method cancelTicket");
        if (endorsementService.gestureAuthorized(state, "abandon")) {
            // Administered WITHOUT endorsement (EXP-32), or connected supervisor,
            // or an open supervisor session: the same execution the approved
            // CANCEL_TICKET dispatch runs, ceremony skipped. A settlement already
            // taken is undone with the ticket.
            if (!state.payment.payments.isEmpty()) {
                paymentService.cancelPayments(state);
            }
            ticketService.cancelTicket(state);
            state.touch();
        } else {
            endorsementService.requestAuthorization(state, "CANCEL_TICKET");
        }
        LOGGER.info("Exiting method cancelTicket");
    }

    /**
     * Places a SOUS-TOTAL marker after the last line (EXP-43): a subtotal of the
     * group since the previous marker is drawn on the screen and printed on the
     * ticket. Refuses out loud on an empty ticket.
     */
    public void markSubtotal() {
        LOGGER.info("Entering method markSubtotal");
        if (state.ticket.markSubtotal()) {
            state.ticket.setNotice(i18n.get("op.sousTotalPose"));
        } else {
            state.ticket.setError(i18n.get("op.aucunArticleSousTotal"));
        }
        state.touch();
        LOGGER.info("Exiting method markSubtotal");
    }

    /**
     * The parked action string of a MODE RETOUR entry awaiting a supervisor
     * endorsement (LC-05-01-01); the endorsement dispatch replays it through
     * {@link #performReturnModeToggle()}.
     */
    public static final String RETURN_MODE_ACTION = "RETURN_MODE_TOGGLE";

    /**
     * Toggles RETURN MODE (EXP-40): the day's return till, where each article
     * entered rings as a return (a negative line) instead of a sale. A day mode,
     * not a per-sale one — it stays on across tickets until toggled off, and a
     * banner keeps it visible. Return-without-ticket (EXP-39) is exactly this
     * mode applied to an article that has no original ticket to recall.
     * <p>
     * Four back-office decisions gate the gesture: a register dedicated to
     * returns never leaves the mode (LC-05-01-03), a store may forbid taking an
     * article back without its origin ticket (LC-05-02-09/14) or inside a ticket
     * that already carries sales (LC-05-01-01), and the entry may require a
     * supervisor endorsement. Only the ENTRY is gated — leaving the mode hands
     * the till back its ordinary powers.
     */
    public void toggleReturnMode() {
        LOGGER.info("Entering method toggleReturnMode");
        // LC-05-01-03: a register the back office dedicated to returns is in
        // return mode for good — it sells nothing, so the key has nothing left
        // to toggle and says so instead of handing the till back to sales.
        if (posSettingsService.returnDedicatedRegister()) {
            state.returnMode = true;
            state.ticket.setError(i18n.get("op.caisseDedieeRetours"));
            state.touch();
            LOGGER.info("Exiting method toggleReturnMode");
            return;
        }
        if (state.returnMode) {
            // LC-05-01-02: where the store forbids mixing, a ticket opened as a
            // return stays a return to the end — leaving the mode over returned
            // lines would ring the next articles as a sale on the same ticket.
            if (!posSettingsService.returnInSaleAllowed() && hasReturnLine()) {
                state.ticket.setError(i18n.get("op.ticketRetourVenteInterdite"));
                state.touch();
                LOGGER.info("Exiting method toggleReturnMode");
                return;
            }
            performReturnModeToggle();
            LOGGER.info("Exiting method toggleReturnMode");
            return;
        }
        // LC-05-02-09/14: taking an article back without its origin ticket can be
        // forbidden store-wide; the recall-based return (/return) is then the only
        // way in.
        // BO-03-13-06: the administered return TYPE may carry the permission the
        // store withholds in general — a type declared "ticket d'origine non
        // trouvé" is exactly the case the questionnaire asks for, and it stays
        // under the supervisor endorsement below. Without a type, the store-wide
        // key decides as it always did.
        if (!posSettingsService.returnWithoutOriginAllowed() && !activeTypeAllowsNoOrigin()) {
            state.ticket.setError(i18n.get("op.retourSansTicketOrigine"));
            state.touch();
            LOGGER.info("Exiting method toggleReturnMode");
            return;
        }
        // LC-05-01-01: a return inside a ticket that already carries sales is a
        // permission of its own — without it the return starts on an empty ticket.
        if (!posSettingsService.returnInSaleAllowed() && !state.ticket.items.isEmpty()) {
            state.ticket.setError(i18n.get("op.retourTicketVenteInterdit"));
            state.touch();
            LOGGER.info("Exiting method toggleReturnMode");
            return;
        }
        // LC-05-01-01: supervisor authorization when the back office asks for it.
        // Only the ENTRY is endorsed — ending the mode gives the till back its
        // ordinary powers and needs no manager.
        // BO-03-13-08: a type declared "articles spécifiques" is dispensed from
        // the endorsement — an exchange of equal value does not call a manager.
        // Every other type goes through the authorization exactly as before.
        if (activeTypeIsEndorsementExempt() || endorsementService.gestureAuthorized(state, "return")) {
            performReturnModeToggle();
        } else {
            endorsementService.requestAuthorization(state, RETURN_MODE_ACTION);
            state.touch();
        }
        LOGGER.info("Exiting method toggleReturnMode");
    }

    /**
     * Starts the administered return TYPE the operator tapped (LC-05-04-01): a
     * labelled shortcut to one of the two base gestures, so a store can offer
     * "Contenant" or "Article défectueux" rather than make the cashier remember
     * which base gesture each case calls for.
     *
     * <p>The type is resolved BY LABEL, never by position: the administered list
     * is re-read at every tap and a store that reorders it mid-day must not turn
     * one key into another. An unknown label is refused rather than defaulted —
     * a key that does something else than it says is worse than a key that does
     * nothing.
     *
     * <p>An in-sale type goes through the ordinary MODE RETOUR entry, guards and
     * supervisor endorsement included: a labelled shortcut may not buy its way
     * past a permission. A dedicated-ticket type only records the type, the
     * return screen being where that gesture lives.
     *
     * @param label the label of the type the operator tapped, possibly null
     * @return true when the type opens a dedicated return ticket, so the caller
     *         sends the operator to the return screen
     */
    public boolean startReturnType(String label) {
        LOGGER.info("Entering method startReturnType with label: " + label);
        String typed = label == null ? "" : label.trim();
        com.intermarche.pos.service.PosSettingsService.ReturnType type = null;
        for (com.intermarche.pos.service.PosSettingsService.ReturnType candidate
                : posListService.returnTypes()) {
            if (candidate.label().equalsIgnoreCase(typed)) {
                type = candidate;
                break;
            }
        }
        if (type == null) {
            state.ticket.setError(i18n.get("op.typeRetourInconnu"));
            state.touch();
            LOGGER.info("Exiting method startReturnType");
            return false;
        }
        state.returnTypeLabel = type.label();
        technicalEventService.log(TechnicalEvent.EventType.RETURN_TYPE_SELECTED, type.label());
        if (type.dedicatedTicket()) {
            state.touch();
            LOGGER.info("Exiting method startReturnType");
            return true;
        }
        if (!state.returnMode) {
            toggleReturnMode();
        }
        LOGGER.info("Exiting method startReturnType");
        return false;
    }

    /**
     * Whether the return type the operator chose may be used when the original
     * ticket was not found (BO-03-13-06).
     *
     * @return true when the active type carries the no-origin permission
     */
    private boolean activeTypeAllowsNoOrigin() {
        com.intermarche.pos.service.PosSettingsService.ReturnType type =
                posListService.returnType(state.returnTypeLabel);
        return type != null && type.originOptional();
    }

    /**
     * Whether the return type the operator chose is dispensed from the
     * supervisor endorsement (BO-03-13-08).
     *
     * @return true when the active type is exempt
     */
    private boolean activeTypeIsEndorsementExempt() {
        com.intermarche.pos.service.PosSettingsService.ReturnType type =
                posListService.returnType(state.returnTypeLabel);
        return type != null && type.endorsementExempt();
    }

    /**
     * Tells whether the ticket in progress already carries a returned article
     * (a negative line): the test for a ticket opened as a return, which may
     * not turn back into a sale when the store forbids mixing (LC-05-01-02).
     *
     * @return true when at least one line of the ticket is negative
     */
    private boolean hasReturnLine() {
        LOGGER.info("Entering method hasReturnLine");
        boolean found = state.ticket.items.stream()
                .anyMatch(item -> item.getTotalPrice().signum() < 0);
        LOGGER.info("Exiting method hasReturnLine");
        return found;
    }

    /**
     * Performs the return-mode toggle once every guard and the supervisor
     * endorsement have passed (LC-05-01-01): flips the mode and announces it on
     * the sale screen, where a banner keeps it visible.
     */
    public void performReturnModeToggle() {
        LOGGER.info("Entering method performReturnModeToggle");
        state.returnMode = !state.returnMode;
        if (!state.returnMode) {
            // LC-05-04-01: the chosen return type belongs to the gesture, and the
            // gesture is over. Left behind, it would label the next return with
            // the previous one's type.
            state.returnTypeLabel = null;
        }
        state.ticket.setNotice(i18n.get(state.returnMode ? "op.modeRetourActiveArticles" : "op.modeRetourTermine"));
        state.touch();
        LOGGER.info("Exiting method performReturnModeToggle");
    }

    /**
     * Arms the cancel-by-scan mode (EXP-36): the next scanned code designates the
     * line to cancel instead of adding one. Mirrors {@link #armQuantity} — arms one
     * scan, disarmed by the scan entry once used.
     */
    public void armCancelByScan() {
        LOGGER.info("Entering method armCancelByScan");
        state.cancelByScanArmed = true;
        state.priceModState.clear();
        state.ticket.setNotice(i18n.get("op.scannezArticleAnnuler"));
        state.touch();
        LOGGER.info("Exiting method armCancelByScan");
    }

    /**
     * Cancels the ticket line whose EAN matches a scanned code (EXP-36), through
     * the shared {@link #cancelLineByUid} decision (endorsement and last-article
     * guards apply). Refuses out loud when no line carries that code.
     *
     * @param code the scanned code designating the line to cancel
     */
    public void cancelByScan(String code) {
        LOGGER.info("Entering method cancelByScan with code: " + code);
        state.cancelByScanArmed = false;
        String targetUid = null;
        if (code != null) {
            for (TicketState.TicketItem item : state.ticket.items) {
                if (code.equals(item.ean)) {
                    targetUid = item.uid;
                    break;
                }
            }
        }
        if (targetUid == null) {
            state.ticket.setError(i18n.get("op.articleAbsentTicket"));
        } else if (!decrementByScan(targetUid)) {
            cancelLineByUid(targetUid);
        }
        state.selectedTicketIndex = -1;
        state.touch();
        LOGGER.info("Exiting method cancelByScan");
    }

    /**
     * Reprints the last closed ticket.
     *
     * <p>Refusals go through {@link PosState#requireLastClosedTicket()}: this
     * function used to be the one that printed nothing and said nothing when
     * the register had closed no sale yet, which on a touch screen is a broken
     * button.
     */
    public void printLastTicket() {
        LOGGER.info("Entering method printLastTicket");
        // RECOVER FIRST. The DERNIER keys work on the last sale THIS REGISTER
        // closed, not on the last sale this PROCESS closed: a restart emptied
        // PosState and the four keys answered "AUCUN TICKET" over a ticket that
        // was still in the database and still on the customer's hands.
        ticketService.resolveLastClosedTicketId(state);
        if (!state.requireLastClosedTicket()) {
            LOGGER.info("Exiting method printLastTicket");
            return;
        }
        ticketService.reprintTicket(state.lastClosedTicketId);
        // ACKNOWLEDGE. The three DERNIER keys redirect to the sale screen and,
        // when they succeeded, changed nothing on it: the register printed and
        // said nothing, which on a till whose printer is a remote bridge is
        // indistinguishable from a dead button.
        state.ticket.setNotice(i18n.get("op.ticketReimprime"));
        LOGGER.info("Exiting method printLastTicket");
    }

    /**
     * Prints the identification barcode of the last closed ticket, alone
     * (LC-08-01-04).
     *
     * <p>Refusals go through {@link PosState#requireLastClosedTicket()}. The slip
     * states no amount, but it carries a real ticket number, and a number that
     * leaves the register must name a sale that happened.
     */
    public void printLastTicketBarcode() {
        LOGGER.info("Entering method printLastTicketBarcode");
        ticketService.resolveLastClosedTicketId(state);
        if (!state.requireLastClosedTicket()) {
            LOGGER.info("Exiting method printLastTicketBarcode");
            return;
        }
        ticketService.printTicketIdentityBarcode(state.lastClosedTicketId);
        state.ticket.setNotice(i18n.get("op.codeBarresImprime"));
        LOGGER.info("Exiting method printLastTicketBarcode");
    }

    /**
     * Prints a duplicate of the last closed ticket's card receipt (LC-08-05-09).
     *
     * <p>The register builds the slip from the traces it holds — amount, authorization
     * number, degraded acceptance. A monetique that returns its own print frames will
     * replace that body; the DUPLICATA mention and the moment it is asked for do not
     * change with it. Refusals go through {@link PosState#requireLastClosedTicket()};
     * a ticket settled without a card prints nothing, which is not a refusal.
     */
    public void printLastCardReceiptDuplicate() {
        LOGGER.info("Entering method printLastCardReceiptDuplicate");
        ticketService.resolveLastClosedTicketId(state);
        if (!state.requireLastClosedTicket()) {
            LOGGER.info("Exiting method printLastCardReceiptDuplicate");
            return;
        }
        // A sale settled without a card prints nothing. That is not a failure,
        // but pressing the key and getting silence is: the operator is told the
        // ticket has no card slip to duplicate.
        if (ticketService.printCardReceiptDuplicate(state.lastClosedTicketId) > 0) {
            state.ticket.setNotice(i18n.get("op.duplicataCbImprime"));
        } else {
            state.ticket.setError(i18n.get("op.aucunPaiementCarteTicket"));
        }
        LOGGER.info("Exiting method printLastCardReceiptDuplicate");
    }

    // --- Price modifications ---

    /**
     * Opens the price-modification modal on the named mode.
     *
     * <p>A word that names no mode opens nothing: the key's URL is the only place
     * where the mode is still a string, and a stale page or a hand-typed address must
     * not open a modal whose title and buttons nobody can decide.
     *
     * @param type the mode's name, as the key's URL spells it
     */
    public void openPriceMod(String type) {
        LOGGER.info("Entering method openPriceMod with type: " + type);
        PriceModType mode = PriceModType.of(type == null ? null : type.toUpperCase());
        if (mode == null) {
            state.ticket.setError(i18n.get("op.modificationInconnue"));
            state.touch();
            LOGGER.info("Exiting method openPriceMod");
            return;
        }
        // Ticket-level gestures target the whole sale: no line selection
        // required (phase: global ticket discount).
        if (mode.isTicketLevel()) {
            state.priceModState.set(mode, null, i18n.get("op.ticketComplet"));
            state.touch();
            LOGGER.info("Exiting method openPriceMod");
            return;
        }
        // THE QUANTITY KEY READS THE EXPLICIT SELECTION, not the fallback.
        // getTargetItem() answers the last line when nothing is selected, which
        // is the right answer for the price gestures and the wrong one here: a
        // quantity keyed with no line chosen landed on the last article rung
        // instead of arming for the next one, so the arming below never fired
        // on a ticket that already carried a line (LC-02-13-04/05/06).
        TicketState.TicketItem target = mode == PriceModType.QUANTITY
                ? state.getSelectedItem() : state.getTargetItem();
        if (target == null && mode == PriceModType.QUANTITY) {
            // NO LINE SELECTED AND A QUANTITY KEYED: the figure is for the article
            // ABOUT TO BE RUNG. Keying the quantity and then the article is the
            // order LC-02-13-04/05/06 asks for, and a cashier who has selected
            // nothing can only mean that one — there is no other line the quantity
            // could belong to. So the modal opens WITHOUT a target line, and
            // validating it arms the quantity for the next article instead of
            // refusing the gesture.
            state.priceModState.set(mode, null, i18n.get("op.prochainArticle"));
        } else if (target == null) {
            state.ticket.setError(i18n.get("op.aucuneLigneSelectionnee"));
        } else {
            // The line is captured HERE, at opening, and not read again while the
            // modal is up: what the operator is about to modify is the line as it
            // was when the gesture started.
            state.priceModState.set(mode, target.uid, target.label, target.getHtml(),
                    target.getPriceFormatted(), target.getModifierLabel());
        }
        state.touch();
        LOGGER.info("Exiting method openPriceMod");
    }

    /**
     * Applies the figure typed BEFORE the key was pressed, and closes the modal
     * the key had just opened.
     *
     * <p>Called only after {@link #openPriceMod(String)}, and deliberately so:
     * the opening is what validates the mode and captures the target line, so the
     * pre-entry inherits those two refusals instead of re-deciding them. A modal
     * that did not open — unknown mode, no line selected — leaves the typed figure
     * unused rather than applying it to a line nobody chose.
     *
     * @param rawValue the figure typed on the sale screen's own pad
     */
    public void applyPreEntry(String rawValue) {
        LOGGER.info("Entering method applyPreEntry with rawValue: " + rawValue);
        if (!state.priceModState.active) {
            LOGGER.info("Exiting method applyPreEntry");
            return;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(rawValue.replace(",", "."));
        } catch (NumberFormatException malformed) {
            // Same refusal as the modal's own: the figure is named invalid and
            // nothing is applied. A pre-entry cannot be "half applied", since the
            // operator is no longer looking at a field to correct.
            state.ticket.setError(i18n.get("op.valeurInvalide"));
            state.priceModState.clear();
            state.touch();
            LOGGER.info("Exiting method applyPreEntry");
            return;
        }
        submitPriceMod(state.priceModState.type, state.priceModState.targetUid,
                preEntryAmount(state.priceModState.type, rawValue, value));
        LOGGER.info("Exiting method applyPreEntry");
    }

    /**
     * Reads a pre-entered figure as what the gesture about to consume it expects.
     *
     * <p>AN AMOUNT IS KEYED IN CENTS, WITHOUT EXCEPTION. The pre-entry pad is the one
     * place where the register cannot know in advance whether the figure is an
     * amount: the same buffer feeds the quantity key, the percentage gestures and the
     * two that move money. So the gesture decides — a remise in euros and a forced
     * price are amounts, and their figure is cents; a quantity and a percentage are
     * not amounts and keep what was keyed.
     *
     * <p>A separator keyed on an amount is DROPPED, not honoured: the pad still
     * carries a comma because quantities need it, and {@code 3,50} on a remise means
     * the same three euros fifty as {@code 350}. There is no spelling of an amount
     * that places its own comma.
     *
     * @param type the gesture about to consume the figure
     * @param rawValue the figure as the pad held it
     * @param value the figure already parsed, for the gestures that are not amounts
     * @return the value the gesture expects
     */
    private BigDecimal preEntryAmount(PriceModType type, String rawValue, BigDecimal value) {
        boolean amountGesture = type == PriceModType.REMISE
                || type == PriceModType.GLOBAL_REMISE
                || type == PriceModType.FORCE_PRICE;
        if (!amountGesture) {
            return value;
        }
        String digits = rawValue == null ? "" : rawValue.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return value;
        }
        return new BigDecimal(digits).movePointLeft(2);
    }

    /**
     * Closes the price-modification modal without applying anything.
     */
    public void cancelPriceMod() {
        LOGGER.info("Entering method cancelPriceMod");
        state.priceModState.clear();
        state.touch();
        LOGGER.info("Exiting method cancelPriceMod");
    }

    /**
     * Calls a supervisor: pushes the register, operator and reason to the
     * store node in real time, and journals the call locally. The message
     * zone tells the cashier the outcome.
     *
     * @param reason the call reason chosen on the supervisor page
     */
    /**
     * Requests the manager endorsement toggling the training mode; refused
     * over a non-empty cart or an active payment (no mixing of training and
     * real transactions).
     */
    public void requestTrainingToggle() {
        LOGGER.info("Entering method requestTrainingToggle");
        if (!state.ticket.items.isEmpty() || state.payment.paymentInProgress) {
            state.ticket.setError(i18n.get("op.terminezAnnulezTicketAbord"));
            state.touch();
            LOGGER.info("Exiting method requestTrainingToggle");
            return;
        }
        // P6: a connected supervisor toggles training without asking themselves.
        if (endorsementService.selfAuthorized(state)) {
            performTrainingToggle();
        } else {
            endorsementService.requestAuthorization(state, "TRAINING_TOGGLE");
        }
        state.touch();
        LOGGER.info("Exiting method requestTrainingToggle");
    }

    /**
     * Performs the endorsed training toggle: flips the mode, re-checks the
     * empty-cart guard and journals the transition.
     */
    public void performTrainingToggle() {
        LOGGER.info("Entering method performTrainingToggle");
        if (!state.ticket.items.isEmpty() || state.payment.paymentInProgress) {
            state.ticket.setError(i18n.get("op.terminezAnnulezTicketAbord"));
            LOGGER.info("Exiting method performTrainingToggle");
            return;
        }
        state.trainingMode = !state.trainingMode;
        technicalEventService.log(state.trainingMode
                ? TechnicalEvent.EventType.TRAINING_STARTED
                : TechnicalEvent.EventType.TRAINING_ENDED, null);
        state.ticket.setError(i18n.get(state.trainingMode ? "op.modeFormationActive" : "op.modeFormationTermine"));
        LOGGER.info("Exiting method performTrainingToggle");
    }

    public void callSupervisor(String reason) {
        callSupervisor(reason, null);
    }

    /**
     * Calls the back office with a reason AND an optional FREE-TEXT message
     * (EXP-19): the message rides the same real-time push and journal as the
     * reason, so a cashier can say more than the administered list allows. A
     * blank message degrades to the plain reason call.
     *
     * @param reason the reason chosen (administered list, EXP-84), possibly blank
     * @param message the free-text message, or null/blank when none
     */
    public void callSupervisor(String reason, String message) {
        LOGGER.info("Entering method callSupervisor with reason: " + reason + ", message: ***");
        String journalled = (message == null || message.isBlank())
                ? reason : reason + " — " + message;
        technicalEventService.log(TechnicalEvent.EventType.SUPERVISOR_CALLED, journalled);
        if (!syncOutboxService.isEnabled()) {
            state.ticket.setError(i18n.get("op.supervisionNonConfigureeCaisse"));
            state.touch();
            LOGGER.info("Exiting method callSupervisor");
            return;
        }
        try {
            java.util.Map<String, String> payload = new java.util.HashMap<>();
            payload.put("terminalId", ticketNumberService.getTerminalId());
            payload.put("operator", state.getOperatorName());
            payload.put("reason", reason);
            if (message != null && !message.isBlank()) {
                payload.put("message", message);
            }
            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(syncOutboxService.getStoreUrl() + "/api/supervisor/call"))
                    .timeout(java.time.Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            String sharedToken = supervisorToken.orElse("");
            if (!sharedToken.isBlank()) {
                builder.header("X-Sync-Token", sharedToken);
            }
            java.net.http.HttpResponse<String> response = SUPERVISOR_CLIENT.send(
                    builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                // A CONFIRMATION, not a refusal: the zone is shared, so the
                // setter is what decides whether it is drawn red with an ERR
                // marker or as an acknowledgement.
                // LC-01-07-03: the request is IN PROGRESS the moment the back
                // office took it — the lane shows it until it is cancelled,
                // refused, acknowledged or validated.
                state.serviceRequest = PosState.ServiceRequestStatus.PENDING;
                state.serviceRequestReason = reason;
                state.ticket.setNotice(i18n.get("op.superviseurPrevenu"));
            } else {
                state.ticket.setError(i18n.format("op.appelSuperviseurRefuse", String.valueOf(response.statusCode())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.ticket.setError(i18n.get("op.appelSuperviseurInterrompu"));
        } catch (Exception e) {
            state.ticket.setError(i18n.get("op.appelSuperviseurImpossible"));
        }
        state.touch();
        LOGGER.info("Exiting method callSupervisor");
    }

    /**
     * Cancels the service request this lane has in progress (LC-01-07-05): the
     * request disappears from the back office and the indicator from the
     * caisse.
     *
     * <p>Refused on no pending request — there is nothing to cancel — and kept
     * when the back office cannot be reached, so the operator can try again:
     * a cancellation the node never heard of would leave the banner up there
     * and take the indicator down here, which is exactly the disagreement the
     * requirement refuses.
     */
    public void cancelServiceRequest() {
        LOGGER.info("Entering method cancelServiceRequest");
        if (!syncOutboxService.isEnabled()) {
            state.ticket.setError(i18n.get("op.supervisionNonConfigureeCaisse"));
            state.touch();
            LOGGER.info("Exiting method cancelServiceRequest");
            return;
        }
        if (state.serviceRequest != PosState.ServiceRequestStatus.PENDING) {
            state.ticket.setNotice(i18n.get("supervisor.aucuneDemandeEnCours"));
            state.touch();
            LOGGER.info("Exiting method cancelServiceRequest");
            return;
        }
        try {
            java.util.Map<String, String> payload = new java.util.HashMap<>();
            payload.put("terminalId", ticketNumberService.getTerminalId());
            int status = postToNode("/api/supervisor/cancel", payload);
            if (status >= 200 && status < 300) {
                state.serviceRequest = PosState.ServiceRequestStatus.NONE;
                state.serviceRequestReason = null;
                state.ticket.setNotice(i18n.get("supervisor.demandeAnnulee"));
            } else {
                state.ticket.setError(i18n.format("op.appelSuperviseurRefuse",
                        String.valueOf(status)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.ticket.setError(i18n.get("op.appelSuperviseurInterrompu"));
        } catch (Exception e) {
            state.ticket.setError(i18n.get("op.appelSuperviseurImpossible"));
        }
        state.touch();
        LOGGER.info("Exiting method cancelServiceRequest");
    }

    /**
     * Validates the handling of a request the back office has ACKNOWLEDGED
     * (LC-01-07-06): the operator closes it, and the indicator disappears
     * from the caisse.
     *
     * <p>Refused while the request is still pending — the back office has not
     * answered yet, and validating a demand nobody has read would tell the
     * lane it is done when the banner says it is not. The gesture is purely
     * local: the back office already dropped the call when it acknowledged
     * it, so nothing travels up.
     */
    public void validateServiceRequest() {
        LOGGER.info("Entering method validateServiceRequest");
        if (state.serviceRequest != PosState.ServiceRequestStatus.ACKED) {
            state.ticket.setNotice(i18n.get("supervisor.demandeNonAcquittee"));
            state.touch();
            LOGGER.info("Exiting method validateServiceRequest");
            return;
        }
        state.serviceRequest = PosState.ServiceRequestStatus.NONE;
        state.serviceRequestReason = null;
        state.ticket.setNotice(i18n.get("supervisor.demandeTraitee"));
        state.touch();
        LOGGER.info("Exiting method validateServiceRequest");
    }

    /**
     * Acknowledges the back-office message the operator has consulted
     * (LC-01-08-05): the banner and the indicator go, and the acknowledgement
     * travels up so the store knows WHO read the notice (BO-03-08-04).
     *
     * <p>The message stays when the node cannot be reached, for the same
     * reason as the cancellation: an acknowledgement the store never filed
     * would re-serve the notice on the next poll, and the honest state is the
     * one the operator can try again. A survey is answered by its own buttons
     * (EXP-89); acknowledging with nothing to acknowledge is a no-op notice.
     */
    public void ackBackOfficeMessage() {
        LOGGER.info("Entering method ackBackOfficeMessage");
        if ((state.backOfficeMessage == null || state.backOfficeMessage.isBlank())
                && state.backOfficeSurvey == null) {
            state.ticket.setNotice(i18n.get("supervisor.aucunMessage"));
            state.touch();
            LOGGER.info("Exiting method ackBackOfficeMessage");
            return;
        }
        if (!syncOutboxService.isEnabled()) {
            state.ticket.setError(i18n.get("op.supervisionNonConfigureeCaisse"));
            state.touch();
            LOGGER.info("Exiting method ackBackOfficeMessage");
            return;
        }
        try {
            java.util.Map<String, String> payload = new java.util.HashMap<>();
            payload.put("terminalId", ticketNumberService.getTerminalId());
            payload.put("operatorCode", state.auth.operatorBadgeId);
            payload.put("operatorName", state.getOperatorName());
            payload.put("text", state.backOfficeMessage);
            int status = postToNode("/api/supervisor/ack-message", payload);
            if (status >= 200 && status < 300) {
                state.backOfficeMessage = "";
                state.backOfficeSurvey = null;
                state.boMessageArrived = false;
                state.ticket.setNotice(i18n.get("supervisor.messageAcquite"));
            } else {
                state.ticket.setError(i18n.format("op.appelSuperviseurRefuse",
                        String.valueOf(status)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.ticket.setError(i18n.get("op.appelSuperviseurInterrompu"));
        } catch (Exception e) {
            state.ticket.setError(i18n.get("op.appelSuperviseurImpossible"));
        }
        state.touch();
        LOGGER.info("Exiting method ackBackOfficeMessage");
    }

    /**
     * Posts one JSON payload to the store node's supervisor channel, on the
     * same client and token as the supervisor call itself.
     *
     * @param path the machine-surface path under the store URL
     * @param payload the fields to send
     * @return the HTTP status the node answered
     * @throws Exception when the node cannot be reached or the payload cannot
     *         be written
     */
    private int postToNode(String path, java.util.Map<String, String> payload) throws Exception {
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(syncOutboxService.getStoreUrl() + path))
                .timeout(java.time.Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(payload)));
        String sharedToken = supervisorToken.orElse("");
        if (!sharedToken.isBlank()) {
            builder.header("X-Sync-Token", sharedToken);
        }
        java.net.http.HttpResponse<String> response = SUPERVISOR_CLIENT.send(
                builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    /**
     * Answers the back-office survey the register was shown (EXP-89): the answer
     * travels back up the same channel as a supervisor call (reason SONDAGE), and
     * the survey is cleared so it stops showing. A no-op when no survey is
     * pending or the answer is blank.
     *
     * @param answer the option the cashier chose
     */
    /**
     * The administered service-request types for the supervisor screen
     * (EXP-84, {@code BO-04-02-07}): the distributed referential when the
     * store node holds one, the semicolon-separated setting otherwise.
     *
     * @return the types, possibly empty
     */
    public java.util.List<com.intermarche.pos.service.PosListService.ServiceRequestType>
            supervisorServiceReasons() {
        LOGGER.info("Entering method supervisorServiceReasons");
        LOGGER.info("Exiting method supervisorServiceReasons");
        return posListService.serviceRequestTypes();
    }

    public void answerSurvey(String answer) {
        LOGGER.info("Entering method answerSurvey");
        if (state.backOfficeSurvey == null || answer == null || answer.isBlank()) {
            LOGGER.info("Exiting method answerSurvey");
            return;
        }
        String question = state.backOfficeSurvey.question;
        callSupervisor("SONDAGE", question + " : " + answer);
        state.backOfficeSurvey = null;
        state.backOfficeMessage = "";
        state.touch();
        LOGGER.info("Exiting method answerSurvey");
    }

    /**
     * Sets the current back-office message pushed DOWN to this register (EXP-20)
     * — the caisse-side consumption of the store node's message channel. A survey
     * (question + options, EXP-89) may accompany it; a blank text with no survey
     * clears the message. The store node (imedge) is the producer; this is the
     * register end only.
     *
     * @param text the message text, or blank to clear
     * @param survey the accompanying survey, or null for a plain notice
     */
    public void receiveBackOfficeMessage(String text, PosState.BackOfficeSurvey survey) {
        LOGGER.info("Entering method receiveBackOfficeMessage");
        state.backOfficeMessage = text == null ? "" : text;
        state.backOfficeSurvey = survey;
        // LC-01-08-04: a message that has ARRIVED is consultable — the
        // indicator stays until the operator acknowledges it, and a blank
        // answer (the node taking the notice down, LC-01-08-05) lowers it
        // again: there is nothing left to consult.
        state.boMessageArrived = (text != null && !text.isBlank()) || survey != null;
        state.touch();
        LOGGER.info("Exiting method receiveBackOfficeMessage");
    }

    /**
     * Submits a price modification, which is routed through a manager endorsement.
     *
     * @param type the modification mode, or null when the submitted word named none
     * @param uid the uid of the targeted ticket line
     * @param value the modification value (euros or percent depending on the type)
     */
    public void submitPriceMod(PriceModType type, String uid, BigDecimal value) {
        LOGGER.info("Entering method submitPriceMod with type: " + type + ", uid: " + uid + ", value: " + value);
        if (type == PriceModType.FORCE_PRICE && !itemAmountControlPassed(uid, value)) {
            // BO-10-02-16: the control has spoken — it refused the figure, or it
            // parked the gesture for a manager. Either way nothing is applied
            // here, and the modal closes as it does after any submission.
            state.priceModState.clear();
            state.touch();
            LOGGER.info("Exiting method submitPriceMod");
            return;
        }
        if (type == PriceModType.QUANTITY && (uid == null || uid.isBlank())) {
            // The modal opened with no target line because there was none to open
            // on: there is therefore no line to multiply, and the figure is armed
            // for the article the cashier is about to ring (LC-02-13-04/05/06).
            armQuantity(value);
        } else if (type == PriceModType.QUANTITY) {
            // Multiplying a scanned line is a normal sale action: no endorsement
            applyLineQuantity(uid, value);
        } else if (isDiscountGesture(type) && !posSettingsService.discountEnabled()) {
            // BO-03-07-01: remises and rabais deactivated at the back office —
            // the gesture is refused before any endorsement or application.
            state.ticket.setError(i18n.get("op.remisesDesactivees"));
        } else if (endorsementService.gestureAuthorized(state,
                type == PriceModType.FORCE_PRICE ? "price-force" : "discount")) {
            // Authorized without a modal (EXP-32/33: flag off, connected
            // supervisor, or an open supervisor session): the cashier applies the
            // gesture directly — same execution as the approved endorsement.
            applyGestureDirectly(type, uid, value);
        } else {
            endorsementService.requestPriceModification(state, type, uid, value);
        }
        state.priceModState.clear();
        state.touch();
        LOGGER.info("Exiting method submitPriceMod");
    }

    /**
     * Whether a price-gesture type is a discount or rebate — the family the
     * back office can deactivate (BO-03-07-01). Price forcing is excluded: it
     * is not a discount and answers to its own administration.
     *
     * @param type the gesture type
     * @return true for a line or global remise/discount
     */
    private boolean isDiscountGesture(PriceModType type) {
        return type == PriceModType.REMISE || type == PriceModType.DISCOUNT
                || type == PriceModType.GLOBAL_REMISE || type == PriceModType.GLOBAL_DISCOUNT;
    }

    /**
     * Applies a price gesture without the endorsement ceremony — the exact
     * mirror of the approved-endorsement dispatch, used when the back office
     * administered the gestures as free (LC-03-02-08).
     *
     * @param type the gesture mode
     * @param uid the targeted line uid, or null for a global gesture
     * @param value the typed value
     */
    private void applyGestureDirectly(PriceModType type, String uid, BigDecimal value) {
        if (type != null && type.isTicketLevel()) {
            ticketService.applyGlobalDiscount(state, type, value);
            return;
        }
        TicketState.TicketItem item = state.ticket.items.stream()
                .filter(i -> i.uid.equals(uid))
                .findFirst()
                .orElse(null);
        if (item == null) {
            state.ticket.setError(i18n.get("op.ligneIntrouvable"));
            return;
        }
        // LC-03-02-10: a manual remise or discount on an already-reduced line is
        // refused where the store forbids the cumulation — and the refusal is
        // NAMED, which is what the line asks beyond the guard itself. The guard
        // still lives in TicketService; this only composes it with the
        // administered rule to say why nothing moved.
        if ((type == PriceModType.REMISE || type == PriceModType.DISCOUNT)
                && ticketService.isAlreadyReduced(item)
                && !posSettingsService.discountCumulativeOnDiscounted()) {
            state.ticket.setError(i18n.get("op.articleDejaRemise"));
            return;
        }
        if (type == PriceModType.REMISE) ticketService.applyRemise(item, value);
        else if (type == PriceModType.DISCOUNT) ticketService.applyDiscount(item, value);
        else if (type == PriceModType.FORCE_PRICE) ticketService.forcePrice(item, value);
        ticketService.recalculateTotal(state);
    }

    /**
     * The ready-made discount keys the back office administers, for the
     * price-modification modal to render (LC-03-02-02 and -04).
     *
     * @return the administered keys, in order, never null
     */
    public java.util.List<com.intermarche.pos.service.PosSettingsService.DiscountPreset> discountPresets() {
        LOGGER.info("Entering method discountPresets");
        LOGGER.info("Exiting method discountPresets");
        return posSettingsService.discountPresets();
    }

    /**
     * Applies one of the ready-made discount keys shown in the open
     * price-modification modal (LC-03-02-02 and -04).
     *
     * <p>THE SAME GESTES as a keyed figure — the key decides nothing the
     * register's guards do not already decide, and every refusal below is
     * NAMED so the operator knows why nothing moved: the family switch
     * (BO-03-07-01), a target line that vanished, a label nobody administered,
     * and an already-reduced line the key is not allowed to pile on
     * (BO-03-07-11). On success the modal closes like any submitted gesture.
     *
     * @param label the label of the administered key the operator tapped
     */
    public void applyPresetKey(String label) {
        LOGGER.info("Entering method applyPresetKey with label: " + label);
        if (!posSettingsService.discountEnabled()) {
            state.ticket.setError(i18n.get("op.remisesDesactivees"));
            state.priceModState.clear();
            state.touch();
            LOGGER.info("Exiting method applyPresetKey");
            return;
        }
        com.intermarche.pos.service.PosSettingsService.DiscountPreset preset =
                posSettingsService.discountPreset(label);
        if (preset == null) {
            state.ticket.setError(i18n.get("op.presetInconnu"));
            state.priceModState.clear();
            state.touch();
            LOGGER.info("Exiting method applyPresetKey");
            return;
        }
        TicketState.TicketItem item = state.ticket.items.stream()
                .filter(i -> i.uid.equals(state.priceModState.targetUid))
                .findFirst()
                .orElse(null);
        if (item == null) {
            state.ticket.setError(i18n.get("op.ligneIntrouvable"));
            state.priceModState.clear();
            state.touch();
            LOGGER.info("Exiting method applyPresetKey");
            return;
        }
        if (ticketService.isAlreadyReduced(item) && !preset.cumulative()) {
            state.ticket.setError(i18n.get("op.articleDejaRemise"));
            state.priceModState.clear();
            state.touch();
            LOGGER.info("Exiting method applyPresetKey");
            return;
        }
        ticketService.applyPreset(item, label);
        ticketService.recalculateTotal(state);
        state.priceModState.clear();
        state.touch();
        LOGGER.info("Exiting method applyPresetKey");
    }

    /**
     * Applies a typed quantity on a ticket line. Two families and a refusal
     * ({@code LC-02-13-02} and {@code -03}): a unit EAN line takes a WHOLE
     * quantity between 1 and 999, and a line whose article is of the
     * DECIMAL-QUANTITY type — sold by the metre, the square metre or the litre —
     * takes a decimal quantity of at most three places, up to the same ceiling.
     * Decimals keyed on anything else are refused, which is the error
     * {@code LC-02-13-03} asks for.
     *
     * <p>A WEIGHING IS NOT A QUANTITY THIS KEY MAY REWRITE. The quantity of a
     * weighed line IS its weight, and that weight was MEASURED — by the scale, by
     * the fruits screen, by an embedded-weight label. 1,234 kg of bananas cannot
     * become "3" because the operator pressed QUANTITE: no gesture turns a
     * measurement into a count. The register says so out loud, which is the same
     * refusal {@link com.intermarche.pos.ui.ticket.TicketState#addItem} already
     * applies to an armed quantity and {@link #repeatLastItem()} to a repetition
     * ({@code LC-02-13-15}). Typing a weight by hand is a DIFFERENT gesture, on a
     * different key and before the article ({@code LC-02-02-03}), not a quantity
     * keyed over a line already weighed.
     *
     * <p>Price-embedded sticker lines are refused for their own reason — one
     * physical sticker is one object at its printed total ({@code LC-02-13-16}) —
     * as are money products and negative (deposit) lines.
     *
     * @param uid the uid of the targeted line
     * @param value the typed quantity, in the article's unit of measure
     */
    private void applyLineQuantity(String uid, BigDecimal value) {
        TicketState.TicketItem item = state.ticket.items.stream()
                .filter(i -> i.uid.equals(uid))
                .findFirst()
                .orElse(null);
        if (item == null) {
            state.ticket.setError(i18n.get("op.ligneIntrouvable"));
            return;
        }
        boolean hasEan = item.ean != null && !item.ean.isEmpty();
        boolean weighedLine = item.plu != null && !item.plu.isEmpty();
        if (!hasEan || weighedLine || item.priceEmbedded
                || item.getTotalPrice().signum() < 0 || item.moneyProduct) {
            state.ticket.setError(i18n.get("op.quantiteNonModifiableLigne"));
            return;
        }
        // BO-10-02-06: above the administered ceiling the shop decides what
        // happens — nothing, a warning, a refusal, or a manager's aval. The
        // absolute bounds below stand whatever the ceiling says: they are what
        // the line can physically carry, not a policy.
        if (value != null && !quantityControlPassed(uid, value)) {
            return;
        }
        if (item.decimalQuantity) {
            if (value == null || value.signum() <= 0 || value.scale() > 3
                    || value.compareTo(BigDecimal.valueOf(999)) > 0) {
                state.ticket.setError(i18n.get("op.quantiteInvalide"));
                return;
            }
            item.quantity = value;
        } else {
            if (value == null || value.stripTrailingZeros().scale() > 0
                    || value.compareTo(BigDecimal.ONE) < 0
                    || value.compareTo(BigDecimal.valueOf(999)) > 0) {
                state.ticket.setError(i18n.get("op.quantiteInvalidePlage"));
                return;
            }
            item.quantity = BigDecimal.valueOf(value.intValueExact());
        }
        ticketService.recalculateTotal(state);
    }
    /**
     * (Re)prints an operator's badge number (LC-01-06-01) — executed ONLY
     * through the endorsement dispatch, after a manager approval. The
     * operator is resolved by badge id first, then by login name; an unknown
     * number lands in the message zone, never on paper.
     *
     * @param operatorNumber the typed badge id or login name
     */
    public void printOperatorBadge(String operatorNumber) {
        LOGGER.info("Entering method printOperatorBadge with operatorNumber: " + operatorNumber);
        com.intermarche.pos.domain.people.Employee employee = com.intermarche.pos.domain.people.Employee
                .<com.intermarche.pos.domain.people.Employee>find("badgeId", operatorNumber).firstResult();
        if (employee == null) {
            employee = com.intermarche.pos.domain.people.Employee
                    .<com.intermarche.pos.domain.people.Employee>find("loginName", operatorNumber).firstResult();
        }
        if (employee == null || !employee.active) {
            state.ticket.setError(i18n.format("op.operateurIntrouvable", operatorNumber));
            LOGGER.info("Exiting method printOperatorBadge");
            return;
        }
        ticketPrinterService.printOperatorBadge(employee);
        state.ticket.setError(i18n.get("op.badgeOperateurImprime"));
        LOGGER.info("Exiting method printOperatorBadge");
    }
}