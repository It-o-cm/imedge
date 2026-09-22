package com.intermarche.pos.ui.dashboard;

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
    com.intermarche.pos.service.sync.RegisterCommandService registerCommandService;

    /** Who is signed in, recorded as the issuer of a forced close. */
    @Inject
    com.intermarche.pos.security.CurrentUser currentUser;

    @Inject @Location("dashboard") Template dashboard;
    @Inject DashboardService dashboardService;
    @Inject SupervisorCallRegistry supervisorCallRegistry;

    /** The back-office parameters (dashboard-alerts activation — BO-10-08-01). */
    @Inject com.intermarche.pos.service.PosSettingsService posSettingsService;

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
        /** The call reason. */
        public String reason;
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
                entry.put("time", call.time);
                calls.add(entry);
            }
        }
        data.put("calls", calls);
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
                com.intermarche.pos.domain.sync.RegisterCommand.CommandType.CLOSE_SESSION,
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
        supervisorCallRegistry.add(dto.terminalId, dto.operator, dto.reason);
        LOGGER.info("Exiting method receiveCall");
        return Response.ok("OK").build();
    }
}
