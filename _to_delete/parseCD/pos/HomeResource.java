package com.intermarche.pos.ui.home;

import com.intermarche.pos.ui.DrawerMayBeOpen;
import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.PriceModType;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.ticket.TicketService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * JAX-RS resource driving the home screen: drawer-error handling, main pages,
 * navigation, line selection/cancellation, price-modification modal and item
 * addition actions.
 * <p>
 * Phase 0: monetary form values are parsed straight into {@link BigDecimal}.
 * <p>
 * This resource also owns the REACTIVE BACKBONE of the register: the
 * versioned fragment poll. Every mutation ends in {@code state.touch()}
 * (the invalidation signal), and {@code /ticket-fragment?v=} answers 204
 * when the client's version is current or the fresh fragment plus the new
 * version otherwise — the main screen, the payment page and the customer
 * display all live on this one contract, which is why a forgotten
 * {@code touch()} shows up as "the screen does not react" and nothing else.
 * The message zone rendered by the fragment is shared by errors AND
 * confirmations (SUPERVISEUR PRÉVENU rides the same channel as the
 * refusals) — a naming wart worth knowing, not a bug.
 */
@Path("/")
@DrawerMustBeClosed
public class HomeResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(HomeResource.class);

    /**
     * The localised operator messages: every sentence this class stamps on the
     * register's message band is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    @Inject Template main;
    @Inject Template supervisor;
    @Inject Template ticket;

    /** The back-office message consult screen (LC-01-08-04). */
    @Inject @Location("bo-message") Template boMessage;

    @Inject @Location("drawer-error") Template drawerError;

    /** The line-cancellation justification chooser (BO-06-06-04). */
    @Inject @Location("cancel-reason") Template cancelReason;

    /** The manual gross-weight entry screen (LC-02-02-03). */
    @Inject @Location("manual-weight") Template manualWeight;

    @Inject HomeService homeService;
    @Inject TicketService ticketService;
    @Inject HardwareService hardwareService;
    @Inject PosState state;

    /** The endorsement flow, for the FIN SUPERVISEUR gesture (EXP-33). */
    @Inject com.intermarche.pos.ui.endorsement.EndorsementService endorsementService;
    /** The exchange-health assembly behind the supervisor screen's panel. */
    @Inject com.intermarche.pos.ui.supervision.ExchangeHealthService exchangeHealthService;
    /** The forced monetics degraded mode (LC-07-08-03/04). */
    @Inject com.intermarche.pos.ui.payment.MoneticsDegradedService moneticsDegradedService;

    /** Settles a cheque reading that ended between two polls (see the poll). */
    @Inject com.intermarche.pos.ui.payment.PaymentService paymentService;

    // --- Drawer error handling ---

    /**
     * Shows the drawer-open error page.
     *
     * @return the drawer-error page
     */
    @GET
    @Path("/drawer-error")
    @Produces(MediaType.TEXT_HTML)
    @DrawerMayBeOpen
    public TemplateInstance drawerErrorPage() {
        LOGGER.info("Entering method drawerErrorPage");
        LOGGER.info("Exiting method drawerErrorPage");
        return drawerError.data("state", state);
    }

    /**
     * Resumes navigation after the drawer has been closed (manual fallback).
     *
     * @return a redirect to the stored return URL, or the home page
     */
    @GET
    @Path("/action/resume-after-drawer")
    @DrawerMayBeOpen
    public Response resumeAfterDrawer() {
        LOGGER.info("Entering method resumeAfterDrawer");
        String target = (state.returnUrl != null && !state.returnUrl.isEmpty()) ? state.returnUrl : "/";
        state.returnUrl = null;
        state.touch();
        LOGGER.info("Exiting method resumeAfterDrawer");
        return Response.seeOther(URI.create(target)).build();
    }

    /**
     * Polling endpoint reporting the drawer status and the redirect target
     * once it is closed.
     *
     * @return a JSON map with the drawer state and redirect URL
     */
    @GET
    @Path("/api/drawer-status")
    @Produces(MediaType.APPLICATION_JSON)
    @DrawerMayBeOpen
    public Map<String, Object> checkDrawerStatus() {
        LOGGER.info("Entering method checkDrawerStatus");
        if (!hardwareService.isDrawerOpen()) {
            String target = (state.returnUrl != null && !state.returnUrl.isEmpty()) ? state.returnUrl : "/";
            state.returnUrl = null;
            state.touch();
            LOGGER.info("Exiting method checkDrawerStatus");
            return Map.of("open", false, "redirect", target);
        }
        LOGGER.info("Exiting method checkDrawerStatus");
        return Map.of("open", true, "redirect", "");
    }

    // --- Main pages ---

    /**
     * Shows the home page.
     *
     * @return the home page
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance home() {
        LOGGER.info("Entering method home");
        // BO-06-06-04: a cancellation waiting for its justification takes the
        // screen. Asked HERE and not at the gesture because the gesture reaches
        // the register by three roads — the CANCEL key, the cancel-by-scan mode
        // and the line tapped on the ticket — and the question must be the same
        // on all three.
        if (state.ticket.pendingCancelUid != null) {
            LOGGER.info("Exiting method home");
            return cancelReason.data("state", state)
                    .data("reasons", homeService.cancelReasons());
        }
        LOGGER.info("Exiting method home");
        return main.data("state", state)
                .data("restrictedTenders", homeService.restrictedTenderRows())
                .data("waitingLabel", homeService.waitingLabel())
                .data("drawerAlert", homeService.drawerCapAlert())
                .data("discountPresets", homeService.discountPresets());
    }

    /**
     * Polling endpoint returning the ticket fragment when the state version changed.
     *
     * @param clientVersion the version known by the client, or null
     * @return a JSON map with the change flag and, when changed, the fragment data
     */
    @GET
    @Path("/ticket-fragment")
    @Produces(MediaType.APPLICATION_JSON)
    @DrawerMayBeOpen
    public Map<String, Object> getTicketFragment(@QueryParam("v") Long clientVersion) {
        LOGGER.info("Entering method getTicketFragment with clientVersion: " + clientVersion);
        Map<String, Object> result = new HashMap<>();
        // A cheque reading that ENDED rides the poll too, and for the same reason
        // as the two flags below: it finishes on the reader's own thread, far from
        // the page, and this poll is the first request able to register it — the
        // register writes its draft from one thread at a time.
        paymentService.settlePendingChequeRead(state);
        // The lock state rides on EVERY answer, version match included: the
        // poll is how an already-open screen learns that the register locked
        // and must navigate to /lock (see LockCheckFilter).
        result.put("locked", state.isLocked());
        // LC-01-07-03/-04 and LC-01-08-04: the indicator zone is rendered
        // SERVER-SIDE outside the fragment, so the lane's request state and
        // the message-arrived flag ride every answer — version match included
        // — and the page reloads once when they stop matching what it shows.
        result.put("serviceRequest", state.serviceRequest.name());
        result.put("boArrived", state.boMessageArrived);
        // The close asked for by a badge scan rides the same way, and for the
        // same reason: the gesture happens on the scan bus, far from the page,
        // and only this poll can send the screen to the closing (LC-01-02-03).
        result.put("closeRequested", state.auth.closeRequestedByBadge);
        // EXP-37: a scanned CLOSED ticket number sends the screen to the return.
        // One-shot: read and clear here so the navigation fires exactly once.
        boolean returnRequested = state.returnByScanRequested;
        if (returnRequested) {
            state.returnByScanRequested = false;
        }
        result.put("returnRequested", returnRequested);
        // Payability rides along for the same reason: the ENCAISSER control
        // is server-rendered OUTSIDE the polled fragment, so a cart filled
        // through the scan bus (hardware scanner, simulator) would leave the
        // button in its render-time state until a navigation. The client
        // reloads once when this flag stops matching the rendered control.
        result.put("payable", state.ticket.getTotalAmount().signum() > 0);
        if (clientVersion != null && state.version == clientVersion) {
            result.put("changed", false);
            LOGGER.info("Exiting method getTicketFragment");
            return result;
        }
        result.put("changed", true);
        result.put("version", state.version);
        // The eligible bases ride the FRAGMENT and not a field of this answer: they are
        // shown "en permanence jusqu'au paiement" inside the ticket zone, which the
        // poll replaces wholesale (LC-09-01-12/14/16/18).
        result.put("html", ticket.data("state", state)
                .data("restrictedTenders", homeService.restrictedTenderRows())
                .data("waitingLabel", homeService.waitingLabel())
                .render());
        result.put("total", state.ticket.getTotalFormatted());
        result.put("amount", state.ticket.getTotalAmount());
        // The age-check prompt is rendered SERVER-SIDE in main.html, so a scan
        // that parks a restricted article changes the state without changing
        // the page: the poll must know, or the cashier faces a screen that
        // simply stops responding to scans.
        result.put("ageCheckActive", state.ageCheck.active);
        // LC-02-03-01/03: same reason — the entry prompt is server-rendered and a
        // scan over the bus is what raises it.
        result.put("entryPromptActive", state.entryPrompt.active);
        result.put("fidelityActive", state.fidelity.active);
        // Attached-card summary (holder from the lookup, card, balance) —
        // shown permanently next to the fidelity icon, cleared with the card.
        result.put("fidelitySummary", state.fidelity.getDisplaySummary());
        result.put("fidelityEarn", state.fidelity.earnTotal != null
                && state.fidelity.earnTotal.signum() > 0
                ? i18n.format("op.avantageCarte",
                        String.format("%.2f", state.fidelity.earnTotal).replace('.', ','))
                : null);
        LOGGER.info("Exiting method getTicketFragment");
        return result;
    }

    /**
     * Shows the supervisor-call page.
     *
     * @param notice the one-shot outcome message of a pull relaunch, or null
     * @param noticeOk whether that message reports a success
     * @return the supervisor page
     */
    @GET
    @Path("/supervisor")
    @DrawerMayBeOpen
    public TemplateInstance supervisorPage(@QueryParam("notice") String notice,
                                           @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method supervisorPage with notice: " + notice + ", noticeOk: " + noticeOk);
        LOGGER.info("Exiting method supervisorPage");
        return supervisor.data("state", state)
                .data("health", exchangeHealthService.build())
                .data("serviceReasons", homeService.supervisorServiceReasons())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Sends a supervisor call carrying the chosen reason AND a free-text message
     * (EXP-19/84): the reason comes from the administered dropdown, the message
     * is optional.
     *
     * @param reason the chosen reason, possibly blank
     * @param message the free-text message, or null
     * @return a redirect to the home page (outcome in the message zone)
     */
    @POST
    @Path("/action/supervisor-message")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response supervisorMessage(@FormParam("reason") String reason,
                                      @FormParam("message") String message) {
        LOGGER.info("Entering method supervisorMessage");
        homeService.callSupervisor(reason == null ? "" : reason, message);
        LOGGER.info("Exiting method supervisorMessage");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Answers the back-office survey shown on the sale screen (EXP-89): the answer
     * travels back up, and the survey is cleared.
     *
     * @param answer the option the cashier chose
     * @return a redirect to the home page
     */
    @POST
    @Path("/action/survey-answer")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response surveyAnswer(@FormParam("answer") String answer) {
        LOGGER.info("Entering method surveyAnswer");
        homeService.answerSurvey(answer);
        LOGGER.info("Exiting method surveyAnswer");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Passes the back-office survey by without answering it (LC-03-07-10):
     * the key only exists on a survey the back office declared optional, and
     * nothing travels up.
     *
     * @return a redirect to the home page
     */
    @POST
    @Path("/action/survey-skip")
    public Response surveySkip() {
        LOGGER.info("Entering method surveySkip");
        homeService.skipSurvey();
        LOGGER.info("Exiting method surveySkip");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Shows the back-office message the operator asked to consult
     * (LC-01-08-04): the notice arrived on the cashier's screen — the banner
     * already showed it — and the indicator keeps it reachable until it is
     * acknowledged (LC-01-08-05). The screen names no survey's answer
     * buttons: a question is answered from the banner itself (EXP-89).
     *
     * <p>Caisse-side only, like everything this channel shows: the customer
     * display is another page and renders none of it (LC-01-08-02).
     *
     * @return the back-office message page
     */
    @GET
    @Path("/bo-message")
    @DrawerMayBeOpen
    public TemplateInstance boMessagePage() {
        LOGGER.info("Entering method boMessagePage");
        LOGGER.info("Exiting method boMessagePage");
        return boMessage.data("state", state);
    }

    /**
     * Acknowledges the back-office message (LC-01-08-05): the banner and the
     * indicator go, and the acknowledgement travels up so the store knows
     * who read the notice.
     *
     * @return a redirect to the home page (outcome in the message zone)
     */
    @POST
    @Path("/action/bo-message/ack")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response ackBoMessage() {
        LOGGER.info("Entering method ackBoMessage");
        homeService.ackBackOfficeMessage();
        LOGGER.info("Exiting method ackBoMessage");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Cancels the service request this lane has in progress (LC-01-07-05):
     * it disappears from the back office and the indicator from the caisse.
     *
     * @return a redirect to the supervisor page (outcome in the message zone)
     */
    @POST
    @Path("/action/service-request/cancel")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response cancelServiceRequest() {
        LOGGER.info("Entering method cancelServiceRequest");
        homeService.cancelServiceRequest();
        LOGGER.info("Exiting method cancelServiceRequest");
        return Response.seeOther(URI.create("/supervisor")).build();
    }

    /**
     * Validates the handling of a request the back office acknowledged
     * (LC-01-07-06): the indicator disappears from the caisse.
     *
     * @return a redirect to the supervisor page (outcome in the message zone)
     */
    @POST
    @Path("/action/service-request/validate")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response validateServiceRequest() {
        LOGGER.info("Entering method validateServiceRequest");
        homeService.validateServiceRequest();
        LOGGER.info("Exiting method validateServiceRequest");
        return Response.seeOther(URI.create("/supervisor")).build();
    }

    /**
     * Sends a supervisor call with the chosen reason and returns to the home
     * page (the outcome is shown in the message zone).
     *
     * @param reason the reason chosen on the supervisor page
     * @return a redirect to the home page
     */
    @GET
    @Path("/action/supervisor/{reason}")
    public Response callSupervisor(@PathParam("reason") String reason) {
        LOGGER.info("Entering method callSupervisor with reason: " + reason);
        homeService.callSupervisor(reason.toUpperCase().replace('-', ' '));
        LOGGER.info("Exiting method callSupervisor");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Requests the endorsed training-mode toggle.
     *
     * @return a redirect to the home page (the endorsement modal opens)
     */
    @GET
    @Path("/action/training")
    public Response toggleTraining() {
        LOGGER.info("Entering method toggleTraining");
        homeService.requestTrainingToggle();
        LOGGER.info("Exiting method toggleTraining");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Forces the monetics into degraded mode, or releases it (LC-07-08-03/04).
     *
     * <p>ONE key for both directions, like the training toggle beside it: the
     * operator sees one state and one gesture, and the register knows which of the
     * two the gesture means.
     *
     * @param login the supervisor's login, when the shop requires an endorsement
     * @param password the supervisor's password
     * @return a redirect to the home page
     */
    @POST
    @Path("/action/monetics-degraded")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response toggleMoneticsDegraded(@FormParam("login") String login,
            @FormParam("password") String password) {
        LOGGER.info("Entering method toggleMoneticsDegraded with login: " + login + ", password: ***");
        if (state.isMoneticsDegradedForced()) {
            moneticsDegradedService.deactivate(state, login, password);
        } else {
            moneticsDegradedService.activate(state, login, password);
        }
        LOGGER.info("Exiting method toggleMoneticsDegraded");
        return Response.seeOther(URI.create("/")).build();
    }

    // --- Menu navigation ---

    /**
     * Shows one of the five button menus.
     *
     * <p>One route for the five keys of the bottom bar. An unknown key lands on the
     * sale menu rather than on an error: a mistyped or stale link must never leave a
     * cashier in front of a blank grid.
     *
     * @param key the menu key carried by the bottom bar's link
     * @return the home page
     */
    @GET
    @Path("/action/menu/{key}")
    public TemplateInstance showMenu(@jakarta.ws.rs.PathParam("key") String key) {
        LOGGER.info("Entering method showMenu with key: " + key);
        homeService.selectMenu(com.intermarche.pos.ui.PosMenu.of(key));
        LOGGER.info("Exiting method showMenu");
        return home();
    }

    // --- Ticket navigation ---

    /**
     * Moves the ticket display to the previous page.
     *
     * @return the home page
     */
    @GET
    @Path("/action/ticket/prev")
    public TemplateInstance ticketPrev() {
        LOGGER.info("Entering method ticketPrev");
        state.prevPage();
        LOGGER.info("Exiting method ticketPrev");
        return home();
    }

    /**
     * Moves the ticket display to the next page.
     *
     * @return the home page
     */
    @GET
    @Path("/action/ticket/next")
    public TemplateInstance ticketNext() {
        LOGGER.info("Entering method ticketNext");
        state.nextPage();
        LOGGER.info("Exiting method ticketNext");
        return home();
    }

    // --- Selection & cancellation ---

    /**
     * Toggles the selection of a ticket line.
     *
     * @param uid the uid of the line to select (P2: identity, not page offset)
     * @return the home page
     */
    @GET
    @Path("/action/select/{uid}")
    public TemplateInstance selectLine(@PathParam("uid") String uid) {
        LOGGER.info("Entering method selectLine with uid: " + uid);
        homeService.selectLine(uid);
        LOGGER.info("Exiting method selectLine");
        return home();
    }

    /**
     * Arms a quantity for the next article named ({@code LC-02-13-04/05/06}).
     *
     * @param rawValue the quantity typed on the modal's keypad
     * @return a redirect to the sale screen
     */
    @POST
    @Path("/action/price-mod/arm")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response armQuantity(@FormParam("rawValue") String rawValue) {
        LOGGER.info("Entering method armQuantity with rawValue: " + rawValue);
        homeService.armQuantity(parseAmount(rawValue));
        LOGGER.info("Exiting method armQuantity");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Shows the MANUAL WEIGHT screen ({@code LC-02-02-03}): the operator types
     * the GROSS weight there, then names the article.
     *
     * <p>The screen states the container tare already armed, when there is one,
     * because that tare is the only part of the deduction known before the
     * article is chosen — the article tare and the administered default are
     * resolved at the article itself.
     *
     * @return the manual-weight page
     */
    @GET
    @Path("/manual-weight")
    public TemplateInstance manualWeightPage() {
        LOGGER.info("Entering method manualWeightPage");
        LOGGER.info("Exiting method manualWeightPage");
        return manualWeight.data("state", state)
                .data("containerLabel", state.tare.containerLabel)
                .data("containerTare", state.tare.hasContainer()
                        ? state.tare.containerTare.stripTrailingZeros().toPlainString() : null);
    }

    /**
     * Arms the gross weight typed on the manual-weight screen
     * ({@code LC-02-02-03}).
     *
     * @param rawValue the gross weight in kilograms, typed on the screen's keypad
     * @return a redirect to the sale screen, where the article is now named
     */
    @POST
    @Path("/action/manual-weight")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response armManualWeight(@FormParam("rawValue") String rawValue) {
        LOGGER.info("Entering method armManualWeight with rawValue: " + rawValue);
        homeService.armManualWeight(parseAmount(rawValue));
        LOGGER.info("Exiting method armManualWeight");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Gives up the armed manual weight ({@code LC-02-02-03}).
     *
     * @return a redirect to the sale screen
     */
    @GET
    @Path("/action/manual-weight/cancel")
    public Response cancelManualWeight() {
        LOGGER.info("Entering method cancelManualWeight");
        homeService.clearManualWeight();
        LOGGER.info("Exiting method cancelManualWeight");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Repeats the registration of the last article ({@code LC-02-13-12}).
     *
     * @return a 303 redirect to the sale screen (PRG pattern): this GET
     *         MUTATES, so it must never leave a RENDERED page the browser then
     *         sits on. The sale screen reloads itself on a payability flip, and
     *         the reload of a rendered {@code /action/repeat} adds a second unit
     *         nobody asked for — the same replay class {@code /action/finish}
     *         is a redirect for.
     */
    @GET
    @Path("/action/repeat")
    public Response repeatLastItem() {
        LOGGER.info("Entering method repeatLastItem");
        homeService.repeatLastItem();
        LOGGER.info("Exiting method repeatLastItem");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Arms the cancel-by-scan mode (EXP-36): the next scanned code names the line
     * to cancel.
     *
     * @return the home page
     */
    @GET
    @Path("/action/cancel-by-scan")
    public TemplateInstance armCancelByScan() {
        LOGGER.info("Entering method armCancelByScan");
        homeService.armCancelByScan();
        LOGGER.info("Exiting method armCancelByScan");
        return home();
    }

    /**
     * Places a SOUS-TOTAL marker after the last line (EXP-43).
     *
     * @return the home page
     */
    @GET
    @Path("/action/subtotal")
    public TemplateInstance markSubtotal() {
        LOGGER.info("Entering method markSubtotal");
        homeService.markSubtotal();
        LOGGER.info("Exiting method markSubtotal");
        return home();
    }

    /**
     * Toggles the day's RETURN MODE (EXP-40): each article entered rings as a
     * return until the mode is turned off again.
     *
     * @return the home page
     */
    @GET
    @Path("/action/return-mode")
    public TemplateInstance toggleReturnMode() {
        LOGGER.info("Entering method toggleReturnMode");
        homeService.toggleReturnMode();
        LOGGER.info("Exiting method toggleReturnMode");
        return home();
    }

    /**
     * Starts an administered return TYPE (LC-05-04-01): a labelled key that
     * launches one of the two base gestures. A type that opens a dedicated return
     * ticket sends the operator to the return screen; an in-sale one leaves them
     * on the sale screen, now in return mode.
     *
     * @param label the label of the type the operator tapped
     * @return a 303 redirect to the screen the gesture lives on
     */
    @GET
    @Path("/action/return-type")
    public jakarta.ws.rs.core.Response startReturnType(
            @jakarta.ws.rs.QueryParam("label") String label) {
        LOGGER.info("Entering method startReturnType with label: " + label);
        boolean dedicated = homeService.startReturnType(label);
        LOGGER.info("Exiting method startReturnType");
        return jakarta.ws.rs.core.Response.seeOther(
                java.net.URI.create(dedicated ? "/return" : "/")).build();
    }

    /**
     * Ends an open supervisor session (EXP-33): the FIN SUPERVISEUR key. The
     * following gestures ask for an endorsement again.
     *
     * @return the home page
     */
    @GET
    @Path("/action/supervisor-session/end")
    public TemplateInstance endSupervisorSession() {
        LOGGER.info("Entering method endSupervisorSession");
        endorsementService.endSupervisorSession(state);
        state.ticket.setNotice(i18n.get("main.finSuperviseur"));
        state.touch();
        LOGGER.info("Exiting method endSupervisorSession");
        return home();
    }

    /**
     * Takes the price or the decimal quantity the operator keyed on the entry prompt
     * ({@code LC-02-03-01/03}).
     *
     * @param rawValue the figure typed on the prompt's keypad
     * @return a redirect to the sale screen
     */
    @POST
    @Path("/action/entry/confirm")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response confirmEntry(@FormParam("rawValue") String rawValue) {
        LOGGER.info("Entering method confirmEntry with rawValue: " + rawValue);
        ticketService.confirmEntry(state, parseAmount(rawValue));
        LOGGER.info("Exiting method confirmEntry");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Gives up the suspended add: nothing is registered.
     *
     * @return a redirect to the sale screen
     */
    @GET
    @Path("/action/entry/cancel")
    public Response cancelEntry() {
        LOGGER.info("Entering method cancelEntry");
        ticketService.cancelEntry(state);
        LOGGER.info("Exiting method cancelEntry");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Reads a figure typed on an on-screen keypad, which sends it as plain digits.
     *
     * @param rawValue the typed value, possibly null or unreadable
     * @return the figure, or null when nothing readable was typed
     */
    private java.math.BigDecimal parseAmount(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(rawValue.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            // A keypad cannot produce this; a hand-made request can. It is a refusal,
            // not an incident.
            return null;
        }
    }

    /**
     * Marks the selected line "à enlever", or arms the next article for it
     * ({@code LC-02-08-01/02/03}).
     *
     * @return the home page
     */
    @GET
    @Path("/action/collect")
    public TemplateInstance toggleCollect() {
        LOGGER.info("Entering method toggleCollect");
        homeService.toggleCollect();
        LOGGER.info("Exiting method toggleCollect");
        return home();
    }

    /**
     * Cancels the targeted line (directly or via endorsement).
     *
     * @return the home page
     */
    @GET
    @Path("/action/cancelLine")
    public TemplateInstance cancelLine() {
        LOGGER.info("Entering method cancelLine");
        homeService.cancelLine();
        LOGGER.info("Exiting method cancelLine");
        return home();
    }

    /**
     * Records the justification the operator tapped and resumes the
     * cancellation ({@code BO-06-06-04}).
     *
     * @param code the code of the justification tapped
     * @return the home page, or the chooser again when the code is unknown
     */
    @GET
    @Path("/action/cancel-reason")
    public TemplateInstance cancelLineReason(@QueryParam("code") String code) {
        LOGGER.info("Entering method cancelLineReason with code: " + code);
        homeService.cancelLineWithReason(code);
        LOGGER.info("Exiting method cancelLineReason");
        return home();
    }

    /**
     * Abandons the cancellation the register was asking a justification for
     * ({@code BO-06-06-04}): the line stays on the ticket.
     *
     * @return the home page
     */
    @GET
    @Path("/action/cancel-reason/abandon")
    public TemplateInstance cancelLineReasonAbandoned() {
        LOGGER.info("Entering method cancelLineReasonAbandoned");
        homeService.cancelLineReasonAbandoned();
        LOGGER.info("Exiting method cancelLineReasonAbandoned");
        return home();
    }

    // --- Price-modification modal ---

    /**
     * Opens the price-modification modal for the targeted line — or applies the
     * figure typed BEFORE the key was pressed, and opens nothing.
     *
     * <p>The pre-entry gesture: the sale screen carries a pad of its own, the
     * cashier types 3 and presses QUANTITÉ, and the quantity is applied in one
     * tap instead of three. Nothing typed, the key opens its modal exactly as
     * before: the old gesture is never taken away, which is what lets the new one
     * be added everywhere at once.
     *
     * <p>The value is applied THROUGH the opening and not instead of it, so the
     * pre-entry inherits every refusal the opening already makes — an unknown
     * mode, a gesture with no line selected — rather than re-deciding them a
     * second way.
     *
     * @param type the modification type (remise, discount, force_price)
     * @param value the figure typed before the key, or null when none was
     * @return the home page
     */
    @GET
    @Path("/action/price-mod/{type}")
    public TemplateInstance openPriceMod(@PathParam("type") String type,
                                         @QueryParam("value") String value) {
        LOGGER.info("Entering method openPriceMod with type: " + type + ", value: " + value);
        homeService.openPriceMod(type);
        if (value != null && !value.isBlank()) {
            homeService.applyPreEntry(value);
        }
        LOGGER.info("Exiting method openPriceMod");
        return home();
    }

    /**
     * Closes the price-modification modal without applying anything.
     *
     * @return the home page
     */
    /**
     * Confirms the pending age check (ID verified): the parked add replays
     * and the sale resumes (phase: age control).
     *
     * @return a redirect to the sale screen
     */
    @GET
    @Path("/action/age-check/confirm")
    public Response ageCheckConfirm() {
        LOGGER.info("Entering method ageCheckConfirm");
        ticketService.confirmAgeCheck(state);
        LOGGER.info("Exiting method ageCheckConfirm");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Refuses the sale of the age-restricted product: nothing is added and
     * the refusal is journalized (phase: age control).
     *
     * @return a redirect to the sale screen
     */
    @GET
    @Path("/action/age-check/refuse")
    public Response ageCheckRefuse() {
        LOGGER.info("Entering method ageCheckRefuse");
        ticketService.refuseAgeCheck(state);
        LOGGER.info("Exiting method ageCheckRefuse");
        return Response.seeOther(URI.create("/")).build();
    }

    @GET
    @Path("/action/price-mod/cancel")
    public TemplateInstance cancelPriceMod() {
        LOGGER.info("Entering method cancelPriceMod");
        homeService.cancelPriceMod();
        LOGGER.info("Exiting method cancelPriceMod");
        return home();
    }

    /**
     * Applies one of the ready-made discount keys administered by the back
     * office (LC-03-02-02 and -04): the modal shows the keys, this route lands
     * the one the operator tapped. Refusals are named by the service — an
     * unknown label, a line that vanished, an already-reduced line without
     * CUMUL — and the modal closes after every outcome, like a submitted
     * gesture.
     *
     * @param label the label of the administered key, as rendered on its button
     * @return a 303 redirect to the home page (PRG pattern, so a browser
     *         reload never replays the POST)
     */
    @POST
    @Path("/action/price-mod/preset")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response applyPresetKey(@FormParam("label") String label) {
        LOGGER.info("Entering method applyPresetKey with label: " + label);
        homeService.applyPresetKey(label);
        LOGGER.info("Exiting method applyPresetKey");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Submits the price-modification value typed in the modal.
     *
     * @param type the name of the modification mode
     * @param uid the uid of the targeted ticket line
     * @param rawValue the raw typed value (French comma tolerated)
     * @return a 303 redirect to the home page (PRG pattern, so a browser
     *         reload never replays the POST)
     */
    @POST
    @Path("/action/price-mod/submit")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response submitPriceMod(
            @FormParam("type") String type,
            @FormParam("uid") String uid,
            @FormParam("rawValue") String rawValue) {
        LOGGER.info("Entering method submitPriceMod with type: " + type + ", uid: " + uid + ", rawValue: " + rawValue);

        BigDecimal value;
        try {
            if (rawValue == null || rawValue.isEmpty()) rawValue = "0";
            value = new BigDecimal(rawValue.replace(",", "."));
        } catch (NumberFormatException e) {
            state.ticket.setError(i18n.get("op.valeurInvalide"));
            state.priceModState.clear();
            state.touch();
            LOGGER.info("Exiting method submitPriceMod");
            return Response.seeOther(URI.create("/")).build();
        }

        // The posted word is the last place the mode is still a string. A stale page
        // or a forged post naming no mode applies nothing and says so, rather than
        // falling through the dispatch in silence.
        PriceModType mode = PriceModType.of(type);
        if (mode == null) {
            state.ticket.setError(i18n.get("op.modificationInconnue"));
            state.priceModState.clear();
            state.touch();
            LOGGER.info("Exiting method submitPriceMod");
            return Response.seeOther(URI.create("/")).build();
        }
        homeService.submitPriceMod(mode, uid, value);
        LOGGER.info("Exiting method submitPriceMod");
        return Response.seeOther(URI.create("/")).build();
    }

    // --- Other actions ---

    /**
     * Adds a weighed product by its PLU code.
     *
     * @param code the PLU code
     * @return the home page
     */
    @GET
    @Path("/action/add/{code}")
    public TemplateInstance addPlu(@PathParam("code") String code) {
        LOGGER.info("Entering method addPlu with code: " + code);
        ticketService.addItemByPlu(state, code);
        LOGGER.info("Exiting method addPlu");
        return home();
    }

    /**
     * Adds a known product by its EAN with a typed quantity.
     *
     * @param ean the EAN code
     * @param quantityStr the typed quantity (defaults to 1)
     * @return a 303 redirect to the home page (PRG pattern, so a browser
     *         reload never replays the POST)
     */
    @POST
    @Path("/action/manual-add-known")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response addManualKnown(@FormParam("ean") String ean, @FormParam("quantity") String quantityStr) {
        LOGGER.info("Entering method addManualKnown with ean: " + ean + ", quantityStr: " + quantityStr);
        int qty = 1;
        try { if(quantityStr != null && !quantityStr.isEmpty()) qty = Integer.parseInt(quantityStr); } catch(Exception e) {}
        if(qty <= 0) qty = 1;
        ticketService.addItemByEan(state, ean, BigDecimal.valueOf(qty));
        LOGGER.info("Exiting method addManualKnown");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Adds an unknown (unlisted) item with a typed label and price.
     *
     * @param label the label typed by the cashier
     * @param priceStr the price typed by the cashier
     * @return a 303 redirect to the home page (PRG pattern, so a browser
     *         reload never replays the POST)
     */
    @POST
    @Path("/action/manual-add-unknown")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response addManualUnknown(@FormParam("label") String label, @FormParam("price") String priceStr) {
        LOGGER.info("Entering method addManualUnknown with label: " + label + ", priceStr: " + priceStr);
        ticketService.addUnknownItem(state, label, priceStr);
        LOGGER.info("Exiting method addManualUnknown");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Adds a free-price line under a FAMILY CODE (EXP-24): "80" then "235" rings
     * an "Epicerie 2,35 €" line carrying the family's label.
     *
     * @param familyCode the family code keyed by the cashier
     * @param priceStr the price typed by the cashier
     * @return a 303 redirect to the home page (PRG pattern)
     */
    @POST
    @Path("/action/manual-add-family")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response addManualFamily(@FormParam("familyCode") String familyCode,
                                    @FormParam("price") String priceStr) {
        LOGGER.info("Entering method addManualFamily with familyCode: " + familyCode + ", priceStr: " + priceStr);
        ticketService.addFamilyPricedItem(state, familyCode, priceStr);
        LOGGER.info("Exiting method addManualFamily");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Adds a deposit-return line to the ticket.
     *
     * @return the home page
     */
    @GET
    @Path("/action/deposit")
    public TemplateInstance addDepositReturn() {
        LOGGER.info("Entering method addDepositReturn");
        ticketService.addDeposit(state);
        LOGGER.info("Exiting method addDepositReturn");
        return home();
    }

    /**
     * Sends the operator to the abandon screen, where the shop's rules are applied
     * before the ticket is given up ({@code LC-04-04-06} to {@code -12}).
     *
     * <p>It no longer requests the endorsement itself: the reason, the fate of a
     * settlement already taken and the printing are decided there, and a second road
     * to the same gesture would be a road around them.
     *
     * @return a redirect to the abandon screen
     */
    @GET
    @Path("/action/cancelTicket")
    public Response cancelTicket() {
        LOGGER.info("Entering method cancelTicket");
        LOGGER.info("Exiting method cancelTicket");
        return Response.seeOther(URI.create("/abandon")).build();
    }

    /**
     * Reprints the last closed ticket.
     *
     * <p>Answers with a redirect, like every other function of the TICKET menu:
     * these three used to re-render the sale screen under their own action URL,
     * so the address bar stayed on /action/print-last and a refresh printed the
     * ticket a second time.
     *
     * @return a redirect to the home page
     */
    @GET
    @Path("/action/print-last")
    public Response printLast() {
        LOGGER.info("Entering method printLast");
        homeService.printLastTicket();
        LOGGER.info("Exiting method printLast");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Prints the identification barcode of the last closed ticket, alone
     * (LC-08-01-04).
     *
     * @return a redirect to the home page
     */
    @GET
    @Path("/action/print-last-barcode")
    public Response printLastBarcode() {
        LOGGER.info("Entering method printLastBarcode");
        homeService.printLastTicketBarcode();
        LOGGER.info("Exiting method printLastBarcode");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Prints a duplicate of the last closed ticket's card receipt (LC-08-05-09).
     *
     * @return a redirect to the home page
     */
    @GET
    @Path("/action/print-last-card")
    public Response printLastCard() {
        LOGGER.info("Entering method printLastCard");
        homeService.printLastCardReceiptDuplicate();
        LOGGER.info("Exiting method printLastCard");
        return Response.seeOther(URI.create("/")).build();
    }
}
