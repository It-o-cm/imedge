package com.intermarche.edge.ui.admin;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.security.CurrentUser;
import com.intermarche.edge.service.DayEndService;
import com.intermarche.edge.service.sync.RegisterPresenceService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * The END OF DAY screen ({@code BO-09-01-04} to {@code -10},
 * {@code BO-09-02-01} to {@code -04}, {@code BO-09-03}, {@code BO-04-03-06}).
 *
 * <p>Three alerts and one button. The alerts are the three things that make
 * an end of day go wrong — a lane still open, a lane gone quiet, a cart left
 * parked — and the button launches the close for whoever is looking at them.
 * Before this screen the back office had the automatic close and no way to
 * see what it was about to close.
 *
 * <p>The countdown is the same one the dashboard banner shows, and it comes
 * from the same clock as the automatic close: a manager reading two screens
 * reads one truth.
 */
@ApplicationScoped
@Path("/admin/day-end")
public class AdminDayEndResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminDayEndResource.class);

    /** The end-of-day picture and its manual launch. */
    @Inject
    DayEndService dayEndService;

    /** Who is pressing the button. */
    @Inject
    CurrentUser currentUser;

    /** The consolidated closes of the point of sale (BO-03-12-02). */
    @Inject
    com.intermarche.edge.service.StorePeriodCloseService storePeriodCloseService;

    /** Every register this node knows, for the connectivity card. */
    @Inject
    RegisterPresenceService registerPresenceService;

    /** The programmed end-of-period editions (BO-09-01-10, BO-09-01-11). */
    @Inject
    com.intermarche.edge.service.ScheduledReportService scheduledReportService;

    /** The report catalog, for the programming form's list of states. */
    @Inject
    com.intermarche.edge.ui.report.ReportService reportService;

    /** The parameters, written by the programming form. */
    @Inject
    com.intermarche.edge.service.PosSettingsService posSettingsService;

    /** Which periods a day closes (BO-09-01-07). */
    @Inject
    com.intermarche.edge.service.PeriodScheduleService periodScheduleService;

    /** The end-of-period treatment, run by the manual launch (BO-09-02, BO-09-03-03). */
    @Inject
    com.intermarche.edge.service.PeriodCloseService periodCloseService;

    /** The tickets on hold recorded and reset at the close (BO-09-01-05, -08, BO-09-02-01). */
    @Inject
    com.intermarche.edge.service.ParkedTicketCloseService parkedTicketCloseService;

    /** The technical entry points of the end of period (BO-09-02-04). */
    @Inject
    com.intermarche.edge.service.PeriodHookService periodHookService;

    /** The end-of-day template. */
    @Inject
    @Location("admin-day-end")
    Template adminDayEnd;

    /**
     * Shows the end-of-day picture.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the end-of-day page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.DAY_END)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance page(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method page");
        DayEndService.DayEndState state = dayEndService.state();
        List<RegisterPresenceService.Presence> registers = registerPresenceService.all();
        LOGGER.info("Exiting method page");
        return adminDayEnd.data("state", state)
                .data("registers", registers)
                .data("offlineMinutes", registerPresenceService.offlineMinutes())
                // BO-09-01-11: what is programmed, offered against the whole
                // catalog so the form is a CHOICE and not a code to type.
                .data("reports", reportService.catalog().all())
                .data("programmed", scheduledReportService.programmed())
                .data("reportFormat", scheduledReportService.format() == null
                        ? "" : scheduledReportService.format().format())
                .data("runs", scheduledReportService.latest(20))
                // BO-09-01-07: which periods today closes, so the screen says
                // whether the night that comes is an ordinary one.
                .data("periodEnds", periodScheduleService.endingOn(java.time.LocalDate.now()))
                .data("closes", storePeriodCloseService.latest(10))
                // BO-09-01-05, -08, BO-09-02-01: the tickets on hold the closes
                // recorded and the resets ordered; BO-09-02-04: the entry points
                // run; BO-09-03: what the end of period is set to do.
                .data("parkedRecords", parkedTicketCloseService.latest(20))
                .data("hookRuns", periodHookService.latest(10))
                .data("autoLaunch", posSettingsService.periodAutoLaunch())
                .data("parkedRecord", posSettingsService.periodParkedRecord())
                .data("parkedRaz", posSettingsService.periodParkedRaz())
                .data("registerCheck", posSettingsService.periodRegisterCheck())
                .data("scoAutoDeclaration", posSettingsService.periodScoAutoDeclaration())
                .data("forcedCloseTicket", posSettingsService.periodForcedCloseTicket())
                .data("hooksEnabled", posSettingsService.periodHooksEnabled())
                .data("reportsPrinter", posSettingsService.periodReportsPrinter())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Programs the states edited automatically at the end of period
     * ({@code BO-09-01-11}).
     *
     * <p>The order of the boxes is the order of the catalog, which is the
     * order the editions come out in: a form of checkboxes cannot express an
     * order of its own, and inventing one would be a promise the screen
     * cannot keep.
     *
     * @param form the submitted selection and format
     * @return a 303 back to the screen
     */
    @POST
    @Path("/reports")
    @Authenticated
    @FeatureAccess(value = Feature.DAY_END, option = CrudOption.UPDATE)
    @jakarta.ws.rs.Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response programReports(jakarta.ws.rs.core.MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method programReports");
        List<String> chosen = form.get("report");
        StringBuilder codes = new StringBuilder();
        if (chosen != null) {
            for (String code : chosen) {
                if (code == null || code.isBlank()) {
                    continue;
                }
                if (codes.length() > 0) {
                    codes.append(';');
                }
                codes.append(code.trim());
            }
        }
        posSettingsService.store("period.reports", codes.toString());
        String format = form.getFirst("format");
        posSettingsService.store("period.reports-format",
                format == null || format.isBlank() ? "pdf" : format.trim());
        LOGGER.info("Exiting method programReports");
        return AdminForms.redirect("/admin/day-end",
                codes.length() == 0
                        ? "Aucun \u00e9tat ne sera \u00e9dit\u00e9 en fin de p\u00e9riode."
                        : "\u00c9tats programm\u00e9s : " + codes + ".", true);
    }

    /**
     * Hands back one automatic edition ({@code BO-09-01-10}).
     *
     * @param id the edition's identifier
     * @return the document, or 404 when the edition is unknown or failed
     */
    @GET
    @Path("/report/{id}")
    @Authenticated
    @FeatureAccess(value = Feature.DAY_END)
    public Response downloadRun(@jakarta.ws.rs.PathParam("id") long id) {
        LOGGER.info("Entering method downloadRun with id: " + id);
        com.intermarche.edge.domain.session.ScheduledReportRun run =
                com.intermarche.edge.domain.session.ScheduledReportRun.findById(id);
        if (run == null || run.content == null) {
            LOGGER.info("Exiting method downloadRun: nothing to hand back");
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        com.intermarche.edge.ui.report.ReportWriter writer = reportService.writer(run.format);
        String name = run.reportCode + "-" + run.periodEnd + "."
                + (writer == null ? run.format : writer.extension());
        LOGGER.info("Exiting method downloadRun");
        return Response.ok(run.content)
                .type(writer == null ? MediaType.APPLICATION_OCTET_STREAM : writer.contentType())
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                .build();
    }

    /**
     * Launches the end of day by hand ({@code BO-09-02-01} to {@code -03}).
     *
     * @return a 303 back to the screen with what the launch found
     */
    @POST
    @Path("/launch")
    @Authenticated
    @FeatureAccess(value = Feature.DAY_END, option = CrudOption.UPDATE)
    public Response launch() {
        LOGGER.info("Entering method launch");
        String who = currentUser.getFullName();
        DayEndService.DayEndState state = dayEndService.launch(who);
        // BO-09-02-01 to -04: the manual end of day runs the end-of-period
        // treatment the automatic one runs, once a day.
        boolean treated = periodCloseService.runEndOfPeriod(
                com.intermarche.edge.domain.util.DateTimeProvider.now().toLocalDate(), who);
        String notice = "Fin de journée lancée : " + state.openRegisters().size()
                + " caisse(s) ouverte(s) à fermer, " + state.offlineRegisters().size()
                + " déconnectée(s), " + state.parkedCarts().size() + " ticket(s) en attente. "
                + (treated ? "Traitement de fin de période exécuté."
                        : "Traitement de fin de période déjà exécuté aujourd'hui.");
        LOGGER.info("Exiting method launch");
        return AdminForms.redirect("/admin/day-end", notice, !state.hasAlert());
    }

    /**
     * Orders the reset of the tickets on hold ({@code BO-09-01-08}): of the
     * register the form names, or of every register.
     *
     * @param form the posted form (terminal, optional)
     * @return a 303 back to the screen with what the reset covers
     */
    @POST
    @Path("/parked-raz")
    @Authenticated
    @FeatureAccess(value = Feature.DAY_END, option = CrudOption.UPDATE)
    @jakarta.ws.rs.Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response razParked(jakarta.ws.rs.core.MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method razParked");
        int count = parkedTicketCloseService.raz(AdminForms.text(form, "terminal"), currentUser.getFullName(),
                com.intermarche.edge.domain.util.DateTimeProvider.now().toLocalDate());
        LOGGER.info("Exiting method razParked");
        return count == 0
                ? AdminForms.redirect("/admin/day-end", "Aucun ticket en attente à remettre à zéro.", true)
                : AdminForms.redirect("/admin/day-end",
                        "Remise à zéro demandée pour " + count + " ticket(s) en attente.", true);
    }
}
