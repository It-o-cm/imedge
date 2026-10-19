package com.intermarche.edge.ui.dashboard;

import io.quarkus.qute.Location;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Manager-facing supervision IHM (phase 5 lot 2), separate from the register
 * screens: the dashboard page, its polled data (aggregations plus pending
 * supervisor calls), the call acknowledgement, and the inbound call endpoint
 * the registers POST to — the latter gated like the sync ingestion (store
 * role, optional shared token).
 * <p>
 * Security posture, explicit: the supervision page, its poll and the
 * acknowledgement require a signed-in MANAGER or ADMIN — supervision is a
 * management screen, not a public LAN page. The machine-facing inbound call
 * endpoint keeps its OWN gate instead (node role plus shared token) and
 * stays anonymous: a register pushes to it across the network and carries no
 * operator identity. The page itself works on any node (it reads the local database);
 * it only becomes the STORE dashboard by running on the consolidated node.
 * <p>
 * Chrome: the page renders inside the back-office gabarit
 * ({@code admin-layout}), the same as {@code /admin/settings} — one
 * surface, one chrome, one navigation. It does NOT carry the till's theme.
 */
@Path("/")
public class DashboardResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(DashboardResource.class);

    /** The order book: where a forced close is left for a register. */
    @Inject
    com.intermarche.edge.service.sync.RegisterCommandService registerCommandService;

    /** Who is signed in, recorded as the issuer of a forced close. */
    @Inject
    com.intermarche.edge.security.CurrentUser currentUser;

    @Inject @Location("dashboard") Template dashboard;
    @Inject DashboardService dashboardService;
    @Inject SupervisorCallRegistry supervisorCallRegistry;

    /** The back-office parameters (dashboard-alerts activation — BO-10-08-01). */
    @Inject com.intermarche.edge.service.PosSettingsService posSettingsService;

    /** Who is still speaking to this node (BO-04-03-05). */
    @Inject com.intermarche.edge.service.sync.RegisterPresenceService registerPresenceService;

    /** The role of this node: "register" (default) or "store". */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** Shared token of the inbound call endpoint; absent = open. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /**
     * An incoming supervisor call pushed by a register.
     */
    public static class CallDto {
        /** The calling register identifier. */
        public String terminalId;
        /** The calling operator display name. */
        public String operator;
        /** The call reason (an administered service reason, EXP-84). */
        public String reason;
        /** The free message the cashier typed, or null (EXP-19). */
        public String message;
    }

    /**
     * Shows the supervision dashboard.
     *
     * @return the dashboard page
     */
    @GET
    @Path("/dashboard")
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance dashboardPage() {
        LOGGER.info("Entering method dashboardPage");
        LOGGER.info("Exiting method dashboardPage");
        return dashboard.instance();
    }

    /**
     * Returns the polled dashboard data: today's aggregations and the
     * pending supervisor calls.
     *
     * @return the JSON dashboard data
     */
    @GET
    @Path("/dashboard-data")
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> dashboardData() {
        LOGGER.info("Entering method dashboardData");
        Map<String, Object> data = dashboardService.buildData();
        List<Map<String, Object>> calls = new ArrayList<>();
        // BO-10-08-01: the register alerts are shown only when the back office
        // activated them; disabled, the poll carries an empty call list.
        if (posSettingsService.dashboardAlertsEnabled()) {
            for (SupervisorCallRegistry.Call call : supervisorCallRegistry.getPending()) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("id", call.id);
                entry.put("terminal", call.terminalId);
                entry.put("operator", call.operator);
                entry.put("reason", call.reason);
                entry.put("severity", call.severity);
                entry.put("status", call.status.name());
                entry.put("statusLabel", call.status.getLabel());
                entry.put("refusalReason", call.refusalReason);
                entry.put("message", call.message);
                entry.put("time", call.time);
                calls.add(entry);
            }
        }
        data.put("calls", calls);
        // BO-04-03-05: the lanes that have gone quiet, beside the calls. A
        // register that stopped speaking is the one thing a supervision
        // screen cannot deduce from what it receives — it is defined by what
        // it stops receiving.
        List<Map<String, Object>> offline = new ArrayList<>();
        for (com.intermarche.edge.service.sync.RegisterPresenceService.Presence presence
                : registerPresenceService.offline()) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("terminal", presence.terminalId());
            entry.put("silent", presence.silentMinutes());
            offline.add(entry);
        }
        data.put("offlineRegisters", offline);
        LOGGER.info("Exiting method dashboardData");
        return data;
    }

    /**
     * Acknowledges a pending supervisor call.
     *
     * @param id the registry id of the call
     * @return 204
     */
    @GET
    @Path("/dashboard/ack/{id}")
    @RolesAllowed({"ADMIN", "MANAGER"})
    public Response acknowledge(@PathParam("id") long id) {
        LOGGER.info("Entering method acknowledge with id: " + id);
        supervisorCallRegistry.acknowledge(id);
        LOGGER.info("Exiting method acknowledge");
        return Response.noContent().build();
    }

    /**
     * Sends a service request BACK TO THE TILL ({@code BO-04-02-04}): the
     * back office has looked at it and decided the lane settles it itself.
     *
     * <p>Nothing travels down for this one. The manager says so to the
     * person standing at the lane — on the shop floor, by telephone, by
     * whatever a shop uses — and the board records the decision so no second
     * manager walks over. What the register needs told, the refusal, has its
     * own order.
     *
     * @param id the registry id of the call
     * @return 204, the dashboard refreshing itself on its own cycle
     */
    @GET
    @Path("/dashboard/call/{id}/to-till")
    @RolesAllowed({"ADMIN", "MANAGER"})
    public Response sendBackToTill(@PathParam("id") long id) {
        LOGGER.info("Entering method sendBackToTill with id: " + id);
        supervisorCallRegistry.settle(id, SupervisorCallRegistry.CallStatus.TO_HANDLE_AT_TILL,
                null, posSettingsService.settledCallsKept());
        LOGGER.info("Exiting method sendBackToTill");
        return Response.noContent().build();
    }

    /**
     * REFUSES a service request ({@code BO-04-02-05}): the board records the
     * refusal and the register is told, so the cashier reads why and the
     * function stays closed.
     *
     * <p>The wording travels as the REASON of a register order, down the
     * channel the forced close already uses: a refusal the lane never
     * receives is a cashier waiting for an answer that is not coming.
     *
     * @param id the registry id of the call
     * @param reason the wording the cashier reads, or null for the default
     * @return 204, or 404 when no call carries that id
     */
    @GET
    @Path("/dashboard/call/{id}/refuse")
    @RolesAllowed({"ADMIN", "MANAGER"})
    public Response refuse(@PathParam("id") long id,
                           @jakarta.ws.rs.QueryParam("reason") String reason) {
        LOGGER.info("Entering method refuse with id: " + id);
        String wording = reason == null || reason.isBlank()
                ? "Demande refusée par le back-office" : reason.trim();
        SupervisorCallRegistry.Call refused = supervisorCallRegistry.settle(id,
                SupervisorCallRegistry.CallStatus.REFUSED, wording,
                posSettingsService.settledCallsKept());
        if (refused == null) {
            LOGGER.info("Exiting method refuse: unknown call");
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        registerCommandService.issue(refused.terminalId,
                com.intermarche.edge.domain.sync.RegisterCommand.CommandType.SUPERVISOR_REFUSED,
                currentUser.getFullName(), wording);
        LOGGER.info("Exiting method refuse");
        return Response.noContent().build();
    }

    /**
     * AUTHORISES a service request ({@code BO-04-02-02}): the board records
     * the authorisation and the register is told, so the gesture the lane is
     * holding goes through without a code being typed at the till.
     *
     * <p>Authorising is not acting. The order says the gesture MAY be done;
     * the cashier still presses the key they were pressing. A back office
     * that performed the gesture itself would be signing, in the register's
     * journal, an action nobody at the lane had confirmed.
     *
     * @param id the registry id of the call
     * @return 204, or 404 when no call carries that id
     */
    @GET
    @Path("/dashboard/call/{id}/authorise")
    @RolesAllowed({"ADMIN", "MANAGER"})
    public Response authorise(@PathParam("id") long id) {
        LOGGER.info("Entering method authorise with id: " + id);
        SupervisorCallRegistry.Call granted = supervisorCallRegistry.settle(id,
                SupervisorCallRegistry.CallStatus.AUTHORISED, null,
                posSettingsService.settledCallsKept());
        if (granted == null) {
            LOGGER.info("Exiting method authorise: unknown call");
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        registerCommandService.issue(granted.terminalId,
                com.intermarche.edge.domain.sync.RegisterCommand.CommandType.SUPERVISOR_GRANTED,
                currentUser.getFullName(),
                "Autorisé par " + currentUser.getFullName());
        LOGGER.info("Exiting method authorise");
        return Response.noContent().build();
    }

    /**
     * Records that a supervisor settled a register's open requests AT THE
     * TILL ({@code BO-04-02-03}), gated like the call ingestion.
     *
     * <p>Pushed by the register the moment an endorsement is GRANTED there:
     * the back office then marks the demand « déjà traitée en caisse »
     * automatically, which is what the requirement asks for — nobody should
     * have to walk to a lane whose problem was solved while they were
     * reading about it.
     *
     * @param presentedToken the shared token presented by the register
     * @param dto the payload naming the register
     * @return 200 on registration, 401 on a bad token, 403 off-role
     */
    @POST
    @Path("/api/supervisor/settled")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response receiveSettled(@HeaderParam("X-Sync-Token") String presentedToken,
                                   CallDto dto) {
        LOGGER.info("Entering method receiveSettled with dto: " + dto);
        if (!"store".equalsIgnoreCase(role)) {
            LOGGER.info("Exiting method receiveSettled");
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("Ce nœud n'a pas le rôle store").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            LOGGER.info("Exiting method receiveSettled");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("Jeton invalide").build();
        }
        int settled = supervisorCallRegistry.settleAtTill(dto == null ? null : dto.terminalId,
                posSettingsService.settledCallsKept());
        LOGGER.infof("%d demande(s) marquée(s) déjà traitée(s) en caisse", settled);
        LOGGER.info("Exiting method receiveSettled");
        return Response.ok("OK").build();
    }

    /**
     * Leaves a FORCED CLOSE order for a register (LC-01-02-07, BO-04-03-13).
     *
     * <p>The order is written, not sent: the register collects it on its next
     * cycle and acknowledges it, and the acknowledgement is what proves it
     * obeyed. So this answers « demandé », never « fait » — the screen shows the
     * order as pending until the register says otherwise, which is the honest
     * state of a shop where a till may be off, unplugged or mid-sale.
     *
     * @param terminalId the register to close
     * @return 204, the dashboard refreshing itself on its own cycle
     */
    @POST
    @Path("/dashboard/force-close/{terminalId}")
    @RolesAllowed({"ADMIN", "MANAGER"})
    public Response forceClose(@PathParam("terminalId") String terminalId) {
        LOGGER.info("Entering method forceClose with terminalId: " + terminalId);
        registerCommandService.issue(terminalId,
                com.intermarche.edge.domain.sync.RegisterCommand.CommandType.CLOSE_SESSION,
                currentUser.getFullName(), "Fermeture demandée depuis le back-office");
        LOGGER.info("Exiting method forceClose");
        return Response.noContent().build();
    }

    /**
     * Receives a supervisor call pushed by a register; gated like the sync
     * ingestion.
     *
     * @param presentedToken the shared token presented by the register
     * @param dto the call payload
     * @return 200 on registration, 401 on a bad token, 403 off-role
     */
    @POST
    @Path("/api/supervisor/call")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    @jakarta.transaction.Transactional
    public Response receiveCall(@HeaderParam("X-Sync-Token") String presentedToken, CallDto dto) {
        LOGGER.info("Entering method receiveCall with presentedToken: ***" + ", dto: " + dto);
        if (!"store".equalsIgnoreCase(role)) {
            LOGGER.info("Exiting method receiveCall");
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("Ce nœud n'a pas le rôle store").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            LOGGER.info("Exiting method receiveCall");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("Jeton invalide").build();
        }
        supervisorCallRegistry.add(dto.terminalId, dto.operator, dto.reason, dto.message,
                severityOf(dto.reason));
        recordMessageConfirmation(dto);
        LOGGER.info("Exiting method receiveCall");
        return Response.ok("OK").build();
    }

    /**
     * The priority the administered service-request types give a reason
     * ({@code BO-04-02-07}).
     *
     * <p>Matched on the type's LABEL — what the register sends — and on its
     * CODE, so a register sending either is understood. A reason nothing
     * matches carries no priority rather than an invented one: it comes from
     * a lane still reading the semicolon setting, or from a gesture that
     * calls with a reason of its own, SONDAGE among them.
     *
     * @param reason the reason the register sent, possibly null
     * @return the priority, 0 when no administered type carries that reason
     */
    int severityOf(String reason) {
        if (reason == null || reason.isBlank()) {
            return 0;
        }
        String typed = reason.trim();
        for (com.intermarche.edge.domain.setting.PosList posList
                : com.intermarche.edge.domain.setting.PosList.findActiveByKind(
                        com.intermarche.edge.domain.setting.PosList.Kind.SERVICE_REQUEST)) {
            for (com.intermarche.edge.domain.setting.PosListItem item : posList.items) {
                if (item.active
                        && (typed.equalsIgnoreCase(item.label) || typed.equalsIgnoreCase(item.code))) {
                    return item.severity;
                }
            }
        }
        return 0;
    }

    /**
     * Files a cashier's answer to a store message as a CONFIRMATION
     * (BO-03-08-04).
     *
     * <p>NO NEW CHANNEL, AND THAT IS DELIBERATE. A register answering a survey
     * already comes up this road, as a supervisor call of reason SONDAGE
     * carrying the question and the answer — and that call already names the
     * register and the operator. The questionnaire asks the confirmation to be
     * kept "sous la forme n° caissière, nom-prénom caissière, date de
     * confirmation", which is exactly what that call carries: so it is filed
     * here rather than asking every deployed lane for a release.
     *
     * <p>The message is found by its QUESTION, which is what the register sent
     * back. A question nobody is asking any more — the message was deleted or
     * reworded while the cashier was answering — files nothing rather than
     * filing against the wrong message.
     *
     * <p>A call of any other reason passes through untouched: a supervisor call
     * is not a confirmation.
     *
     * @param dto the call the register posted
     */
    void recordMessageConfirmation(CallDto dto) {
        if (dto == null || !"SONDAGE".equalsIgnoreCase(dto.reason) || dto.message == null) {
            return;
        }
        int separator = dto.message.lastIndexOf(" : ");
        if (separator < 0) {
            return;
        }
        String question = dto.message.substring(0, separator).trim();
        String answer = dto.message.substring(separator + 3).trim();
        com.intermarche.edge.domain.setting.StoreMessage message =
                com.intermarche.edge.domain.setting.StoreMessage
                        .find("question", question).firstResult();
        if (message == null) {
            return;
        }
        com.intermarche.edge.domain.setting.StoreMessageAck ack =
                new com.intermarche.edge.domain.setting.StoreMessageAck();
        ack.messageId = message.id;
        ack.terminalId = dto.terminalId;
        ack.operatorName = dto.operator;
        ack.answer = answer;
        ack.persist();
        LOGGER.infof("Confirmation du message %d par %s sur %s", message.id,
                dto.operator, dto.terminalId);
    }

}
