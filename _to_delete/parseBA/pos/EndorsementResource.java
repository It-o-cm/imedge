package com.intermarche.pos.ui.endorsement;

import com.intermarche.pos.domain.sale.Refund;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.PriceModType;
import com.intermarche.pos.ui.returnprocess.RefundService;
import com.intermarche.pos.ui.ticket.TicketService;
import com.intermarche.pos.ui.ticket.TicketState;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Map;

/**
 * JAX-RS resource driving the manager-endorsement modal: polling endpoint,
 * validation of the endorsed action and cancellation.
 * <p>
 * Phase 0: the pending price-modification value flows as {@link BigDecimal}.
 * <p>
 * The dispatch switch below is the REGISTRY of every guarded gesture of the
 * register: ticket and line cancellations, the three price modifications,
 * the refund methods (REFUND_&lt;METHOD&gt;_&lt;ticketId&gt;) and the
 * training toggle. Adding a guarded gesture is exactly two touches — one
 * request call parking the action string, one branch here executing it —
 * and the execution ALWAYS happens after the PIN validation, never before:
 * the parked string is inert until a manager credential passes.
 */
@Path("/")
public class EndorsementResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EndorsementResource.class);

    /**
     * The localised operator messages: every sentence this class shows the
     * operator is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;


    @Inject EndorsementService endorsementService;
    @Inject TicketService ticketService;
    @Inject RefundService refundService;
    @Inject com.intermarche.pos.ui.home.HomeService homeService;

    /** Undoes the settlements an abandoned ticket had already taken (LC-04-04-07/09). */
    @Inject com.intermarche.pos.ui.payment.PaymentService paymentService;

    /** Replays a cash movement the modal just authorized. */
    @Inject com.intermarche.pos.ui.cash.CashMovementResource cashMovementResource;

    /** Replays a withdrawal or a transfer the modal just authorized. */
    @Inject com.intermarche.pos.ui.cash.DrawerOperationsResource drawerOperationsResource;

    @Inject com.intermarche.pos.ui.cash.FinancialMovementResource financialMovementResource;

    @Inject PosState state;

    /** The back-office parameters, for the administered cumulation rule. */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /**
     * Returns the endorsement modal state for the UI polling.
     *
     * @return a JSON map with the modal state (active, action, scanned badge, error)
     */
    @GET
    @Path("/endorsement-data")
    @Produces("application/json")
    public Map<String, Object> getEndorsementData() {
        LOGGER.info("Entering method getEndorsementData");
        String badge = state.endorsement.scannedBadge;
        if (badge != null) state.endorsement.clearScannedBadge();
        LOGGER.info("Exiting method getEndorsementData");
        return Map.of(
                "active", state.endorsement.active,
                "action", state.endorsement.requestedAction != null ? state.endorsement.requestedAction : "",
                "scannedBadge", badge != null ? badge : "",
                "error", state.endorsement.error != null ? state.endorsement.error : ""
        );
    }

    /**
     * Validates the pending endorsement with the presented credentials and,
     * on success, executes the endorsed action.
     *
     * @param login the badge id or login name presented for the endorsement
     * @param password the raw PIN presented for the endorsement
     * @return a 303 redirect to the main page (PRG pattern, so a browser
     *         reload never replays the POST); any refusal message travels
     *         through the shared state
     */
    @POST
    @Path("/action/endorse-validate")
    @Consumes("application/x-www-form-urlencoded")
    public Response validateEndorsement(@FormParam("login") String login, @FormParam("password") String password) {
        LOGGER.info("Entering method validateEndorsement with login: " + login + ", password: ***");
        String actionToExecute = state.endorsement.requestedAction;

        if (actionToExecute == null) {
            endorsementService.clearRequest(state);
            LOGGER.info("Exiting method validateEndorsement");
            return redirectHome();
        }

        // BO-04-02-02: an authorisation granted at the BACK OFFICE lets the
        // pending gesture through without a code being typed here. The
        // cashier still pressed the key; what the back office supplied is
        // the endorsement, not the gesture.
        String remote = endorsementService.remotelyGranted(state);
        if (remote != null || endorsementService.authorize(login, password, actionToExecute)) {
            // EXP-33: in PER_TICKET scope the endorsement stays open on the sale so
            // the following guarded gestures do not re-ask, until FIN SUPERVISEUR.
            endorsementService.openSupervisorSessionIfPerTicket(state,
                    remote != null ? remote : login);
            String landing = state.endorsement.returnPath;
            Response own = executeApprovedAction(actionToExecute,
                    remote != null ? remote : login);
            endorsementService.clearRequest(state);
            state.touch();
            LOGGER.info("Exiting method validateEndorsement");
            return landingOf(own, landing);
        } else {
            state.endorsement.error = i18n.get("op.autorisationRefusee2");
            state.touch();
            LOGGER.info("Exiting method validateEndorsement");
            return redirectHome();
        }
    }

    /**
     * Executes an APPROVED endorsed action — the registry of every guarded
     * gesture. Called only after a manager credential passed, or through the
     * connected-supervisor shortcut below (LC-01-05-07): the approval always
     * precedes the execution, never the reverse.
     *
     * @param actionToExecute the parked action string
     * @param endorserBadge the badge or login of the manager who endorsed
     * @return the landing the gesture wants, or null to land on the sale screen
     */
    private Response executeApprovedAction(String actionToExecute, String endorserBadge) {
            if (actionToExecute.equals("CANCEL_TICKET")) {
                // LC-04-04-07/09: a settlement already taken is undone with the
                // ticket — the lease released, the valuation reverted, the entries
                // dropped. The abandon screen named them before the operator
                // confirmed, which is what makes this an accepted proposal and not a
                // silent loss. With nothing settled it is a no-op.
                if (!state.payment.payments.isEmpty()) {
                    paymentService.cancelPayments(state);
                }
                ticketService.cancelTicket(state);
            } else if (actionToExecute.equals("CANCEL_PAYMENT")) {
                // BO-10-02-02: the manager endorsed the abandon of the
                // settlements already taken; the gesture resumes exactly where
                // the payment screen left it.
                paymentService.cancelPayments(state);
            } else if (actionToExecute.startsWith("CANCEL_LINE_")) {
                String uid = actionToExecute.substring("CANCEL_LINE_".length());
                ticketService.cancelItemById(state, uid);
            }
            else if (actionToExecute.equals("PRICE_MODIFICATION")) {
                PriceModType type = state.endorsement.pendingPriceType;
                String uid = state.endorsement.pendingTargetUid;
                BigDecimal val = state.endorsement.pendingValue;

                if (type != null && type.isTicketLevel()) {
                    // Ticket-level gesture: no target line — the global
                    // discount applies to the whole sale (phase: global
                    // ticket discount) and recomputes internally; the flow
                    // then falls through to the common endorsement tail.
                    ticketService.applyGlobalDiscount(state, type, val);
                } else {
                    TicketState.TicketItem item = state.ticket.items.stream()
                            .filter(i -> i.uid.equals(uid))
                            .findFirst()
                            .orElse(null);

                    if (item != null) {
                        // LC-03-02-10: an endorsed remise or discount on an
                        // already-reduced line is refused where the store
                        // forbids the cumulation — and the refusal is NAMED, so
                        // a manager who just approved the gesture is told why
                        // nothing moved rather than left before a silent no-op.
                        if ((type == PriceModType.REMISE || type == PriceModType.DISCOUNT)
                                && ticketService.isAlreadyReduced(item)
                                && !posSettingsService.discountCumulativeOnDiscounted()) {
                            state.ticket.setError(i18n.get("op.articleDejaRemise"));
                        } else {
                            // Type dispatch
                            if (type == PriceModType.REMISE) ticketService.applyRemise(item, val);
                            else if (type == PriceModType.DISCOUNT) ticketService.applyDiscount(item, val);
                            else if (type == PriceModType.FORCE_PRICE) ticketService.forcePrice(item, val);
                            ticketService.recalculateTotal(state);
                        }
                    }
                }
            }
            else if (actionToExecute.equals("TRAINING_TOGGLE")) {
                homeService.performTrainingToggle();
            }
            else if (actionToExecute.equals(
                    com.intermarche.pos.ui.home.HomeService.RETURN_MODE_ACTION)) {
                // LC-05-01-01: the endorsed entry into MODE RETOUR — the till
                // starts taking articles back only once a manager has answered.
                homeService.performReturnModeToggle();
            }
            else if (actionToExecute.startsWith("REFUND_")) {
                // Format: REFUND_<METHOD>_<ticketId>
                String[] parts = actionToExecute.split("_");
                try {
                    Refund.RefundMethod method = Refund.RefundMethod.valueOf(parts[1]);
                    refundService.performRefund(state, method);
                } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
                    state.endorsement.error = i18n.get("op.actionRemboursementInconnue");
                } catch (IllegalStateException e) {
                    // Guard refusal: the message is already on the refund screen
                }
            }
            else if (actionToExecute.startsWith("PRINT_BADGE_")) {
                // (Re)prints an operator's badge number (LC-01-06-01).
                homeService.printOperatorBadge(actionToExecute.substring("PRINT_BADGE_".length()));
            }
            else if (actionToExecute.equals(
                    com.intermarche.pos.ui.cash.CashMovementResource.ENDORSEMENT_ACTION)) {
                // The drawer gestures carry a FORM, not just a code: the screen
                // parked what was typed and it is replayed here, through the
                // screen's own guards, now that a manager has answered.
                return cashMovementResource.performEndorsed(state.endorsement.pendingForm, endorserBadge);
            }
            else if (actionToExecute.equals(
                    com.intermarche.pos.ui.cash.DrawerOperationsResource.ENDORSEMENT_ACTION)) {
                java.util.Map<String, String> form = state.endorsement.pendingForm;
                // One action code for two gestures, as the journal has always
                // named them; the parked form says which of the two it is.
                if ("transfer".equals(form.get("op"))) {
                    return drawerOperationsResource.performEndorsedTransfer(form, endorserBadge);
                }
                if ("apport".equals(form.get("op"))) {
                    return drawerOperationsResource.performEndorsedApport(form, endorserBadge);
                }
                if ("safe-deposit".equals(form.get("op"))) {
                    return drawerOperationsResource.performEndorsedSafeDeposit(form, endorserBadge);
                }
                return drawerOperationsResource.performEndorsedWithdrawal(form, endorserBadge);
            }
            else if (actionToExecute.equals(
                    com.intermarche.pos.ui.cash.FinancialMovementResource.ENDORSEMENT_ACTION)) {
                // LC-12-06: the financial-movement screen parked what was typed
                // (source, destination, amount); it is replayed here, through
                // the screen's own guards, once a manager has answered.
                return financialMovementResource.performEndorsed(state.endorsement.pendingForm, endorserBadge);
            }
            return null;
    }

    /**
     * The logged operator's badge, or null when no one is logged.
     *
     * <p>Null-safe because the shortcut can be reached before the auth state
     * carries anyone, and an endorsement badge is a label on a journal line,
     * never a permission: the role was already checked above.
     *
     * @return the operator's badge, or null
     */
    private String operatorBadge() {
        return state.auth != null ? state.auth.operatorBadgeId : null;
    }

    /**
     * Chooses where the operator lands after an endorsed gesture: the landing
     * the gesture itself produced, else the screen that parked it, else the
     * sale screen.
     *
     * @param own the response the gesture returned, or null
     * @param landing the parked return path, or null
     * @return the response to send
     */
    private Response landingOf(Response own, String landing) {
        if (own != null) {
            return own;
        }
        if (landing != null && !landing.isBlank()) {
            return Response.seeOther(URI.create(landing)).build();
        }
        return redirectHome();
    }

    /**
     * Connected-supervisor shortcut (LC-01-05-07): when the LOGGED operator
     * already holds the MANAGER or ADMIN role, the pending endorsement is
     * executed directly — no second credential is asked. The role check is
     * done server-side here, never trusted from the page.
     *
     * @return a 303 redirect to the main page (PRG pattern, so a browser
     *         reload never replays the POST); any refusal message travels
     *         through the shared state
     */
    @POST
    @Path("/action/endorse-self")
    public Response selfEndorse() {
        LOGGER.info("Entering method selfEndorse");
        String actionToExecute = state.endorsement.requestedAction;
        if (actionToExecute == null) {
            endorsementService.clearRequest(state);
            LOGGER.info("Exiting method selfEndorse");
            return redirectHome();
        }
        if (!endorsementService.operatorIsSupervisor(state)) {
            state.endorsement.error = i18n.get("op.autorisationRefusee2");
            state.touch();
            LOGGER.info("Exiting method selfEndorse");
            return redirectHome();
        }
        String landing = state.endorsement.returnPath;
        Response own = executeApprovedAction(actionToExecute, operatorBadge());
        endorsementService.clearRequest(state);
        state.touch();
        LOGGER.info("Exiting method selfEndorse");
        return landingOf(own, landing);
    }

    /**
     * Cancels the pending endorsement request.
     *
     * <p>Redirects instead of rendering, like every other action of this
     * class. Rendering the main page from here meant building its data map
     * here too, and this one was short of {@code restrictedTenders}, which
     * the ticket fragment reads: the page then died on a missing key instead
     * of showing a cancelled endorsement. The home resource owns that map and
     * is the only place that should build it.
     *
     * @return the 303 redirect to the main page
     */
    @GET
    @Path("/action/endorse-cancel")
    public Response cancelEndorsement() {
        LOGGER.info("Entering method cancelEndorsement");
        endorsementService.clearRequest(state);
        state.touch();
        LOGGER.info("Exiting method cancelEndorsement");
        return redirectHome();
    }

    /**
     * Builds the 303 redirect to the main page used by every POST action
     * (PRG pattern, so a browser reload never replays the POST).
     *
     * @return a 303 See Other response targeting "/"
     */
    private Response redirectHome() {
        return Response.seeOther(URI.create("/")).build();
    }
}
