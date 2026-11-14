package com.intermarche.edge.ui.deploy;

import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.security.CurrentUser;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.ui.supervision.ConsoleExport;
import com.intermarche.edge.ui.supervision.ConsoleFilter;
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
 * The DEPLOYMENT screens of the back office (BO-08-03-01 to -06,
 * BO-08-03-09/10, BO-08-04-02): the console of every lot with its charts and
 * export, the releases, and the page of one lot where its targets, its
 * window, its launch and its forcings are administered.
 */
@Path("/admin/deployment")
public class DeploymentResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(DeploymentResource.class);

    /** The console page. */
    @Inject
    @Location("admin-deployment")
    Template consolePage;

    /** The lot page. */
    @Inject
    @Location("admin-deployment-lot")
    Template lotPage;

    /** The console's assembly and gestures. */
    @Inject
    DeploymentConsoleService consoleService;

    /** Who acts. */
    @Inject
    CurrentUser currentUser;

    /**
     * Shows the console.
     *
     * @param uriInfo the query, read as the filter
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.DEPLOYMENT)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance console(@Context UriInfo uriInfo, @QueryParam("notice") String notice,
                                    @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method console");
        return consolePage.data("data", consoleService.console(ConsoleFilter.from(uriInfo.getQueryParameters())))
                .data("notice", notice).data("noticeOk", noticeOk);
    }

    /**
     * Exports the console's list.
     *
     * @param uriInfo the query, read as the filter
     * @param format {@code csv} or {@code xlsx}
     * @return the file
     */
    @GET
    @Path("/export")
    @Authenticated
    @FeatureAccess(value = Feature.DEPLOYMENT)
    public Response export(@Context UriInfo uriInfo, @QueryParam("format") String format) {
        DeploymentConsoleService.ConsoleData data = consoleService.console(
                ConsoleFilter.from(uriInfo.getQueryParameters()));
        return ConsoleExport.export(format, "deploiement", "Console de déploiement",
                DeploymentConsoleService.columns(), DeploymentConsoleService.lines(data));
    }

    /**
     * Publishes a release.
     *
     * @param product IMEDGE or IMPOS
     * @param version the version
     * @param kind MAJOR, MINOR or PATCH
     * @param notes what it brings
     * @param defective whether its package fails its self-test
     * @return a 303 redirect to the console with the notice
     */
    @POST
    @Path("/release")
    @Authenticated
    @FeatureAccess(value = Feature.DEPLOYMENT, option = CrudOption.CREATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createRelease(@FormParam("product") String product, @FormParam("version") String version,
                                  @FormParam("kind") String kind, @FormParam("notes") String notes,
                                  @FormParam("defective") boolean defective) {
        String error = consoleService.createRelease(product, version, kind, notes, defective);
        return error == null ? redirect("/admin/deployment", "Version publiée.", true)
                : redirect("/admin/deployment", error, false);
    }

    /**
     * Creates a lot, as a draft, and opens its page.
     *
     * @param code the lot's code
     * @param label what it is for
     * @param product IMEDGE or IMPOS
     * @param version the version it distributes
     * @param pilot whether it is a pilot phase
     * @param minVersion the lowest version a node must run
     * @param start the window's start, {@code jj/mm/aaaa hh:mn}
     * @param end the window's end, {@code jj/mm/aaaa hh:mn}
     * @return a 303 redirect to the lot's page, or to the console with the refusal
     */
    @POST
    @Path("/lot")
    @Authenticated
    @FeatureAccess(value = Feature.DEPLOYMENT, option = CrudOption.CREATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createLot(@FormParam("code") String code, @FormParam("label") String label,
                              @FormParam("product") String product, @FormParam("version") String version,
                              @FormParam("pilot") boolean pilot, @FormParam("minVersion") String minVersion,
                              @FormParam("windowStart") String start, @FormParam("windowEnd") String end) {
        String error = consoleService.createLot(code, label, product, version, pilot, minVersion, start, end,
                currentUser.getFullName());
        return error == null ? redirect(lotPath(code.trim()), "Lot créé.", true)
                : redirect("/admin/deployment", error, false);
    }

    /**
     * Shows one lot.
     *
     * @param code the lot's code
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page, or a 303 redirect to the console when the lot is unknown
     */
    @GET
    @Path("/lot/{code}")
    @Authenticated
    @FeatureAccess(value = Feature.DEPLOYMENT)
    @Produces(MediaType.TEXT_HTML)
    public Response lot(@PathParam("code") String code, @QueryParam("notice") String notice,
                        @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        DeploymentConsoleService.LotView view = consoleService.lot(code);
        if (view == null) {
            return redirect("/admin/deployment", "Lot introuvable.", false);
        }
        TemplateInstance page = lotPage.data("view", view).data("notice", notice).data("noticeOk", noticeOk);
        return Response.ok(page).build();
    }

    /**
     * Adds targets to a lot.
     *
     * @param code the lot's code
     * @param mode UNIT, ECHELON, ATTRIBUTE, IMPORT or RECALL
     * @param level the echelon or attribute
     * @param value the point of sale, echelon, attribute or selection
     * @param rule ALL, EVEN or ODD
     * @param lines the imported list
     * @return a 303 redirect to the lot's page with the outcome
     */
    @POST
    @Path("/lot/{code}/targets")
    @Authenticated
    @FeatureAccess(value = Feature.DEPLOYMENT, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response addTargets(@PathParam("code") String code, @FormParam("mode") String mode,
                               @FormParam("level") String level, @FormParam("value") String value,
                               @FormParam("rule") String rule, @FormParam("lines") String lines) {
        String outcome = consoleService.addTargets(code, mode, level, value, rule, lines);
        return redirect(lotPath(code), outcome, outcome.contains("ajoutée"));
    }

    /**
     * Removes one target from a lot.
     *
     * @param code the lot's code
     * @param id the target
     * @return a 303 redirect to the lot's page with the notice
     */
    @POST
    @Path("/lot/{code}/target/{id}/remove")
    @Authenticated
    @FeatureAccess(value = Feature.DEPLOYMENT, option = CrudOption.DELETE)
    public Response removeTarget(@PathParam("code") String code, @PathParam("id") Long id) {
        return outcome(code, consoleService.removeTarget(code, id), "Cible retirée.");
    }

    /**
     * Saves the lot's targets as a named selection.
     *
     * @param code the lot's code
     * @param name the selection's name
     * @return a 303 redirect to the lot's page with the notice
     */
    @POST
    @Path("/lot/{code}/selection")
    @Authenticated
    @FeatureAccess(value = Feature.DEPLOYMENT, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response saveSelection(@PathParam("code") String code, @FormParam("name") String name) {
        return outcome(code, consoleService.saveSelection(code, name, currentUser.getFullName()),
                "Sélection enregistrée.");
    }

    /**
     * Changes the lot's window.
     *
     * @param code the lot's code
     * @param start the window's start
     * @param end the window's end
     * @return a 303 redirect to the lot's page with the notice
     */
    @POST
    @Path("/lot/{code}/window")
    @Authenticated
    @FeatureAccess(value = Feature.DEPLOYMENT, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateWindow(@PathParam("code") String code, @FormParam("windowStart") String start,
                                 @FormParam("windowEnd") String end) {
        return outcome(code, consoleService.updateWindow(code, start, end), "Période enregistrée.");
    }

    /**
     * Launches the lot.
     *
     * @param code the lot's code
     * @return a 303 redirect to the lot's page with the notice
     */
    @POST
    @Path("/lot/{code}/schedule")
    @Authenticated
    @FeatureAccess(value = Feature.DEPLOYMENT, option = CrudOption.UPDATE)
    public Response schedule(@PathParam("code") String code) {
        return outcome(code, consoleService.schedule(code), "Lot lancé.");
    }

    /**
     * Closes the lot.
     *
     * @param code the lot's code
     * @return a 303 redirect to the lot's page with the notice
     */
    @POST
    @Path("/lot/{code}/close")
    @Authenticated
    @FeatureAccess(value = Feature.DEPLOYMENT, option = CrudOption.UPDATE)
    public Response close(@PathParam("code") String code) {
        return outcome(code, consoleService.close(code), "Lot clôturé.");
    }

    /**
     * Forces one target (BO-08-04-02).
     *
     * @param code the lot's code
     * @param id the target
     * @return a 303 redirect to the lot's page with the notice
     */
    @POST
    @Path("/lot/{code}/target/{id}/force")
    @Authenticated
    @FeatureAccess(value = Feature.DEPLOYMENT, option = CrudOption.UPDATE)
    public Response forceTarget(@PathParam("code") String code, @PathParam("id") Long id) {
        return forced(code, consoleService.force(code, id));
    }

    /**
     * Forces every failed or rolled-back target of the lot (BO-08-04-02).
     *
     * @param code the lot's code
     * @return a 303 redirect to the lot's page with the notice
     */
    @POST
    @Path("/lot/{code}/force-failed")
    @Authenticated
    @FeatureAccess(value = Feature.DEPLOYMENT, option = CrudOption.UPDATE)
    public Response forceFailed(@PathParam("code") String code) {
        return forced(code, consoleService.force(code, null));
    }

    /**
     * Redirects after a forcing.
     *
     * @param code the lot's code
     * @param count how many targets were forced, -1 for a closed lot
     * @return the redirect
     */
    private Response forced(String code, int count) {
        return count < 0 ? redirect(lotPath(code), "Lot introuvable ou clôturé.", false)
                : redirect(lotPath(code), count + " cible(s) forcée(s).", count > 0);
    }

    /**
     * Redirects to a lot's page with a gesture's outcome.
     *
     * @param code the lot's code
     * @param error the refusal, null on success
     * @param success the wording of a success
     * @return the redirect
     */
    private Response outcome(String code, String error, String success) {
        return error == null ? redirect(lotPath(code), success, true) : redirect(lotPath(code), error, false);
    }

    /**
     * The path of a lot's page.
     *
     * @param code the lot's code
     * @return the path
     */
    static String lotPath(String code) {
        return "/admin/deployment/lot/" + URLEncoder.encode(code, StandardCharsets.UTF_8);
    }

    /**
     * Builds a 303 redirect carrying the notice.
     *
     * @param path where to go
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    private Response redirect(String path, String notice, boolean ok) {
        return Response.seeOther(URI.create(path + "?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8))).build();
    }
}
