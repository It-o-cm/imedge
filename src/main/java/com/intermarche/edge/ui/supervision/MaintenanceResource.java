package com.intermarche.edge.ui.supervision;

import com.intermarche.edge.domain.deploy.MaintenanceAction;
import com.intermarche.edge.domain.deploy.NodeStatus;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.security.CurrentUser;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.service.deploy.MaintenanceService;
import com.intermarche.edge.service.deploy.NodeStatusService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The REMOTE TAKE-OVER of a point of sale (BO-08-04-17): the store node and
 * the registers this node knows, the maintenance orders that can be sent to
 * each — restart the payment terminal, reset a peripheral, pull the
 * referentials, replay the integrations, check the version — and the
 * history of every order sent, by whom, when, and what the node answered.
 *
 * <p>The order is not carried out from here: it is left for the node, which
 * collects it with the answer to its next report and says how it went in the
 * report after. On the central node an order for a register travels through
 * its store node.
 */
@Path("/admin/supervision/maintenance")
public class MaintenanceResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(MaintenanceResource.class);

    /** How many orders the history shows. */
    static final int HISTORY = 50;

    /** The maintenance page. */
    @Inject
    @Location("admin-supervision-maintenance")
    Template maintenancePage;

    /** The order book. */
    @Inject
    MaintenanceService maintenanceService;

    /** The known nodes. */
    @Inject
    NodeStatusService nodeStatusService;

    /** Who issues the order. */
    @Inject
    CurrentUser currentUser;

    /** The role of this node: the echelon the orders are issued from. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** One order type a node can receive, for the form. */
    public static class Choice {
        /** The type's name. */
        public final String value;
        /** Its French label. */
        public final String label;

        /**
         * Builds a choice.
         *
         * @param value the type's name
         * @param label its French label
         */
        public Choice(String value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    /**
     * Shows the nodes, their orders and the history.
     *
     * @param pdv the point of sale kept, or null for all
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.REMOTE_MAINTENANCE)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance page(@QueryParam("pdv") String pdv, @QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method page");
        List<NodeStatus> nodes = new ArrayList<>();
        for (NodeStatus status : nodeStatusService.all()) {
            if (pdv == null || pdv.isBlank() || pdv.trim().equals(status.pdvNumber)) {
                nodes.add(status);
            }
        }
        return maintenancePage.data("nodes", nodes)
                .data("registerChoices", choices("REGISTER"))
                .data("edgeChoices", choices("STORE"))
                .data("history", maintenanceService.history(HISTORY))
                .data("pdv", pdv)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Leaves an order for a node.
     *
     * @param nodeKey the node's key
     * @param type the order type
     * @param reason why, possibly blank
     * @return a 303 redirect to the page with the notice
     */
    @POST
    @Path("/issue")
    @Authenticated
    @FeatureAccess(value = Feature.REMOTE_MAINTENANCE, option = CrudOption.CREATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response issue(@FormParam("nodeKey") String nodeKey, @FormParam("type") String type,
                          @FormParam("reason") String reason) {
        LOGGER.info("Entering method issue with nodeKey: " + nodeKey + ", type: " + type);
        NodeStatus node = nodeKey == null ? null : nodeStatusService.find(nodeKey);
        MaintenanceAction.ActionType actionType = typeOf(type);
        if (node == null || actionType == null) {
            return redirect("Nœud ou ordre inconnu.", false);
        }
        String origin = "central".equalsIgnoreCase(role) ? "CENTRAL" : "STORE";
        MaintenanceAction action = maintenanceService.issue(node.kind, node.pdvNumber, node.nodeId, actionType,
                currentUser.getFullName(), reason == null || reason.isBlank() ? null : reason.trim(), origin);
        if (action == null) {
            return redirect("Cet ordre ne s'applique pas à ce nœud.", false);
        }
        return redirect("Ordre « " + actionType.getLabel() + " » envoyé à " + node.nodeId + ".", true);
    }

    /**
     * The order types a node kind can receive.
     *
     * @param kind REGISTER or STORE
     * @return the choices
     */
    static List<Choice> choices(String kind) {
        List<Choice> choices = new ArrayList<>();
        for (MaintenanceAction.ActionType type : MaintenanceAction.ActionType.values()) {
            if (type.appliesTo(kind)) {
                choices.add(new Choice(type.name(), type.getLabel()));
            }
        }
        return choices;
    }

    /**
     * Reads an order type.
     *
     * @param type the posted name
     * @return the type, or null when unknown
     */
    static MaintenanceAction.ActionType typeOf(String type) {
        for (MaintenanceAction.ActionType candidate : MaintenanceAction.ActionType.values()) {
            if (candidate.name().equals(type)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Builds the 303 redirect carrying the notice.
     *
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    private Response redirect(String notice, boolean ok) {
        return Response.seeOther(URI.create("/admin/supervision/maintenance?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8))).build();
    }
}
