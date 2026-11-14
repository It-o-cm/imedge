package com.intermarche.edge.ui.supervision;

import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.sync.IntegrationDeposit;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.service.sync.IntegrationRetryService;
import com.intermarche.edge.ui.admin.BackOfficeMessages;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The SUPERVISION CONSOLES of the back office (BO-08-01-17, BO-08-01-19,
 * BO-08-04-03 to -16, BO-08-04-20, BO-08-02-02): activity and register
 * health, integration with the replay of the failed deposits, hardware and
 * software inventory, function usage — each a list, a chart and an export.
 *
 * <p>Read-only screens, but the integration console carries one gesture:
 * the MANUAL REPLAY of a kept deposit, the forcing of BO-08-04-09 to -13 at
 * the central echelon.
 */
@Path("/admin/supervision")
public class SupervisionConsoleResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SupervisionConsoleResource.class);

    /** The activity console. */
    @Inject
    @Location("admin-supervision-activity")
    Template activityPage;

    /** The integration console. */
    @Inject
    @Location("admin-supervision-integration")
    Template integrationPage;

    /** The inventory console. */
    @Inject
    @Location("admin-supervision-inventory")
    Template inventoryPage;

    /** The usage console. */
    @Inject
    @Location("admin-supervision-usage")
    Template usagePage;

    /** The consoles' assembly. */
    @Inject
    SupervisionConsoleService consoleService;

    /** The page's labels: the inventory chart names each peripheral role in the page's language. */
    @Inject
    BackOfficeMessages i18n;

    /** The replay of the deposits. */
    @Inject
    IntegrationRetryService integrationRetryService;

    /**
     * Shows the activity console.
     *
     * @param uriInfo the query, read as the filter
     * @return the page
     */
    @GET
    @Path("/activity")
    @Authenticated
    @FeatureAccess(value = Feature.ACTIVITY_CONSOLE)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance activity(@Context UriInfo uriInfo) {
        LOGGER.info("Entering method activity");
        return activityPage.data("view", consoleService.activity(filter(uriInfo)));
    }

    /**
     * Exports the activity console's list.
     *
     * @param uriInfo the query, read as the filter
     * @param format {@code csv} or {@code xlsx}
     * @return the file
     */
    @GET
    @Path("/activity/export")
    @Authenticated
    @FeatureAccess(value = Feature.ACTIVITY_CONSOLE)
    public Response activityExport(@Context UriInfo uriInfo, @QueryParam("format") String format) {
        SupervisionConsoleService.ConsoleView view = consoleService.activity(filter(uriInfo));
        return ConsoleExport.export(format, "activite", "Console d'activité",
                SupervisionConsoleService.activityColumns(), SupervisionConsoleService.activityLines(view));
    }

    /**
     * Shows the integration console.
     *
     * @param uriInfo the query, read as the filter
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @Path("/integration")
    @Authenticated
    @FeatureAccess(value = Feature.INTEGRATION_CONSOLE)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance integration(@Context UriInfo uriInfo, @QueryParam("notice") String notice,
                                        @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method integration");
        return integrationPage.data("view", consoleService.integration(filter(uriInfo)))
                .data("notice", notice).data("noticeOk", noticeOk);
    }

    /**
     * Exports the integration console's list.
     *
     * @param uriInfo the query, read as the filter
     * @param format {@code csv} or {@code xlsx}
     * @return the file
     */
    @GET
    @Path("/integration/export")
    @Authenticated
    @FeatureAccess(value = Feature.INTEGRATION_CONSOLE)
    public Response integrationExport(@Context UriInfo uriInfo, @QueryParam("format") String format) {
        SupervisionConsoleService.ConsoleView view = consoleService.integration(filter(uriInfo));
        return ConsoleExport.export(format, "integration", "Console d'intégration",
                SupervisionConsoleService.integrationColumns(), SupervisionConsoleService.integrationLines(view));
    }

    /**
     * Replays one kept deposit now (BO-08-04-09 to -13, central echelon).
     *
     * @param id the deposit
     * @return a 303 redirect to the console with the notice
     */
    @POST
    @Path("/integration/{id}/replay")
    @Authenticated
    @FeatureAccess(value = Feature.INTEGRATION_CONSOLE, option = CrudOption.UPDATE)
    public Response replay(@PathParam("id") Long id) {
        LOGGER.info("Entering method replay with id: " + id);
        IntegrationDeposit deposit = integrationRetryService.replayNow(id);
        if (deposit == null) {
            return redirect("Dépôt introuvable ou déjà intégré.", false);
        }
        boolean applied = deposit.status == IntegrationDeposit.Status.APPLIED;
        return redirect("Dépôt " + deposit.code + " réintégré : " + deposit.status + ".", applied);
    }

    /**
     * Replays every failed or partial deposit now.
     *
     * @return a 303 redirect to the console with the notice
     */
    @POST
    @Path("/integration/replay-all")
    @Authenticated
    @FeatureAccess(value = Feature.INTEGRATION_CONSOLE, option = CrudOption.UPDATE)
    public Response replayAll() {
        LOGGER.info("Entering method replayAll");
        return redirect(integrationRetryService.retryAll() + " dépôt(s) réintégré(s).", true);
    }

    /**
     * Shows the inventory console.
     *
     * @param uriInfo the query, read as the filter
     * @return the page
     */
    @GET
    @Path("/inventory")
    @Authenticated
    @FeatureAccess(value = Feature.INVENTORY)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance inventory(@Context UriInfo uriInfo) {
        LOGGER.info("Entering method inventory");
        return inventoryPage.data("view", consoleService.inventory(filter(uriInfo), i18n::text));
    }

    /**
     * Exports the inventory console's list.
     *
     * @param uriInfo the query, read as the filter
     * @param format {@code csv} or {@code xlsx}
     * @return the file
     */
    @GET
    @Path("/inventory/export")
    @Authenticated
    @FeatureAccess(value = Feature.INVENTORY)
    public Response inventoryExport(@Context UriInfo uriInfo, @QueryParam("format") String format) {
        SupervisionConsoleService.ConsoleView view = consoleService.inventory(filter(uriInfo));
        return ConsoleExport.export(format, "inventaire", "Parc matériel et logiciel",
                SupervisionConsoleService.inventoryColumns(), SupervisionConsoleService.inventoryLines(view));
    }

    /**
     * Shows the usage console.
     *
     * @param uriInfo the query, read as the filter
     * @return the page
     */
    @GET
    @Path("/usage")
    @Authenticated
    @FeatureAccess(value = Feature.FUNCTION_USAGE)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance usage(@Context UriInfo uriInfo) {
        LOGGER.info("Entering method usage");
        return usagePage.data("view", consoleService.usage(filter(uriInfo)));
    }

    /**
     * Exports the usage console's list.
     *
     * @param uriInfo the query, read as the filter
     * @param format {@code csv} or {@code xlsx}
     * @return the file
     */
    @GET
    @Path("/usage/export")
    @Authenticated
    @FeatureAccess(value = Feature.FUNCTION_USAGE)
    public Response usageExport(@Context UriInfo uriInfo, @QueryParam("format") String format) {
        SupervisionConsoleService.ConsoleView view = consoleService.usage(filter(uriInfo));
        return ConsoleExport.export(format, "usage-fonctions", "Usage des fonctions",
                SupervisionConsoleService.usageColumns(), SupervisionConsoleService.usageLines(view));
    }

    /**
     * Reads the filter out of the query.
     *
     * @param uriInfo the request's address
     * @return the filter
     */
    private ConsoleFilter filter(UriInfo uriInfo) {
        return ConsoleFilter.from(uriInfo.getQueryParameters());
    }

    /**
     * Builds the 303 redirect to the integration console carrying the notice.
     *
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    private Response redirect(String notice, boolean ok) {
        return Response.seeOther(URI.create("/admin/supervision/integration?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8))).build();
    }
}
