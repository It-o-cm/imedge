package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.fiscal.FiscalArchive;
import com.intermarche.edge.domain.fiscal.SaftTransmission;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.sale.FiscalDocument;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.service.export.SaftExportService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The SAF-T EXPORT back-office screen ({@code /admin/saft}, BO-10-06-03): the
 * Portuguese fiscal documents the registers issued over a period, and the
 * audit file drawn from them for the tax administration.
 *
 * <p>The documents are the registers' — numbered, signed and printed at the
 * till — and this screen never touches them: it lists them and draws the file;
 * the certification number the file states is the parameter
 * {@code fiscal.certificate-number}, administered at the country echelon.
 *
 * <p>THE MONTHLY EXTRACTION ({@code BO-05-01-04}): the point of sale extracts
 * the file of the month before, before the administered deadline day, keeps it
 * here and declares it transmitted; the screen says when the previous month is
 * late. THE SAF-T AT THE FLOW ({@code BO-05-01-05}) is supervised here too:
 * what waits, what went and what failed, and the relaunch of the failures,
 * with whether the messages to the AT carry its WS-Security header
 * ({@code BO-11-04-02}).
 */
@Path("/admin/saft")
public class AdminSaftResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminSaftResource.class);

    /** The screen. */
    @Inject
    @Location("admin-saft")
    Template adminSaft;

    /** Draws the audit file. */
    @Inject
    SaftExportService saftExportService;

    /** The certification number shown beside the period. */
    @Inject
    com.intermarche.edge.service.PosSettingsService posSettingsService;

    /** Keeps the monthly files the point of sale extracts (BO-05-01-04). */
    @Inject
    com.intermarche.edge.service.fiscal.FiscalArchiveService fiscalArchiveService;

    /** The SAF-T at the flow and its supervision (BO-05-01-05). */
    @Inject
    com.intermarche.edge.service.fiscal.SaftStreamService saftStreamService;

    /** Where the WS-Security header of the AT's messages stands (BO-11-04-02). */
    @Inject
    com.intermarche.edge.service.fiscal.AtWsSecurity atWsSecurity;

    /** Who extracts or declares a file transmitted. */
    @Inject
    com.intermarche.edge.security.CurrentUser currentUser;

    /** How many monthly files and sends the screen lists. */
    static final int SHOWN = 24;

    /**
     * Shows the documents of a period and the export of their file.
     *
     * @param from the first day, ISO, or null for the first day of the month
     * @param to the last day, ISO, or null for today
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.SAFT_EXPORT)
    public TemplateInstance page(@QueryParam("from") String from, @QueryParam("to") String to,
                                 @QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @jakarta.ws.rs.DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method page with from: " + from + ", to: " + to);
        LocalDate today = LocalDate.now();
        LocalDate first = day(from, today.withDayOfMonth(1));
        LocalDate last = day(to, today);
        List<FiscalDocument> documents =
                FiscalDocument.issuedBetween(first.atStartOfDay(), last.plusDays(1).atStartOfDay());
        LocalDate previous = today.minusMonths(1).withDayOfMonth(1);
        List<FiscalArchive> previousFiles = FiscalArchive.forPeriod(FiscalArchive.Kind.SAFT_MONTHLY, previous,
                previous.withDayOfMonth(previous.lengthOfMonth()));
        boolean transmitted = false;
        for (FiscalArchive file : previousFiles) {
            transmitted |= file.transmittedAt != null;
        }
        int deadline = posSettingsService.saftDeadlineDay();
        LOGGER.info("Exiting method page with " + documents.size() + " document(s)");
        return adminSaft.data("documents", documents)
                .data("from", first.toString())
                .data("to", last.toString())
                .data("certificate", posSettingsService.fiscalCertificateNumber())
                .data("month", previous.toString().substring(0, 7))
                .data("monthLabel", previous.format(java.time.format.DateTimeFormatter.ofPattern("MM/yyyy")))
                .data("deadline", deadline)
                .data("previousExtracted", !previousFiles.isEmpty())
                .data("previousTransmitted", transmitted)
                .data("late", late(transmitted, today.getDayOfMonth(), deadline))
                .data("monthlyFiles", FiscalArchive.latest(List.of(FiscalArchive.Kind.SAFT_MONTHLY), SHOWN))
                .data("realtime", posSettingsService.saftRealtime())
                .data("endpoints", saftStreamService.endpoints().size())
                .data("wsSecurity", atWsSecurity.status().name())
                .data("pending", SaftTransmission.countIn(SaftTransmission.Status.PENDING))
                .data("sent", SaftTransmission.countIn(SaftTransmission.Status.SENT))
                .data("failed", SaftTransmission.countIn(SaftTransmission.Status.FAILED))
                .data("transmissions", SaftTransmission.latest(SHOWN))
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Extracts the SAF-T file of a month and keeps it at the point of sale's
     * disposal, to be transmitted to the administration before the deadline
     * ({@code BO-05-01-04}).
     *
     * @param form the posted form: {@code month}, as {@code yyyy-MM}
     * @return a 303 back to the screen with the notice
     */
    @POST
    @Path("/monthly")
    @Authenticated
    @FeatureAccess(value = Feature.SAFT_EXPORT, option = CrudOption.CREATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response monthly(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method monthly");
        LocalDate first = month(form.getFirst("month"));
        if (first == null || first.isAfter(LocalDate.now())) {
            LOGGER.info("Exiting method monthly: unusable month");
            return back("Mois invalide.", false);
        }
        LocalDate last = first.withDayOfMonth(first.lengthOfMonth());
        LocalDateTime now = LocalDateTime.now();
        String xml = saftExportService.export(first, last, now);
        FiscalArchive file = fiscalArchiveService.keep(FiscalArchive.Kind.SAFT_MONTHLY,
                fiscalArchiveService.pdvNumber(), first, last, xml.getBytes(StandardCharsets.UTF_8),
                currentUser == null ? null : currentUser.getName(), false, now);
        LOGGER.info("Exiting method monthly");
        return back("Fichier SAF-T extrait : " + file.fileName, true);
    }

    /**
     * Downloads a monthly SAF-T file as it was extracted.
     *
     * @param id the file's identifier
     * @return the file, or 404
     */
    @GET
    @Path("/monthly/download")
    @Authenticated
    @FeatureAccess(value = Feature.SAFT_EXPORT)
    public Response download(@QueryParam("id") Long id) {
        LOGGER.info("Entering method download with id: " + id);
        FiscalArchive file = id == null ? null : FiscalArchive.<FiscalArchive>findById(id);
        if (file == null || file.kind != FiscalArchive.Kind.SAFT_MONTHLY) {
            LOGGER.info("Exiting method download: unknown file");
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        LOGGER.info("Exiting method download");
        return Response.ok(file.content, "application/xml; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"" + file.fileName + "\"").build();
    }

    /**
     * Declares a monthly file transmitted to the administration
     * ({@code BO-05-01-04}).
     *
     * @param form the posted form: {@code id}
     * @return a 303 back to the screen with the notice
     */
    @POST
    @Path("/monthly/transmitted")
    @Authenticated
    @FeatureAccess(value = Feature.SAFT_EXPORT, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response transmitted(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method transmitted");
        Long id = AdminForms.id(form.getFirst("id"));
        FiscalArchive file = id == null ? null : FiscalArchive.<FiscalArchive>findById(id);
        if (file == null || file.kind != FiscalArchive.Kind.SAFT_MONTHLY) {
            LOGGER.info("Exiting method transmitted: unknown file");
            return back("Fichier SAF-T inconnu.", false);
        }
        file.transmittedAt = LocalDateTime.now();
        String who = currentUser == null ? null : currentUser.getName();
        file.transmittedBy = who == null || who.isBlank() ? "—" : who;
        file.persist();
        LOGGER.info("Exiting method transmitted");
        return back("Fichier SAF-T déclaré transmis à l'administration.", true);
    }

    /**
     * Relaunches every failed send of the SAF-T at the flow
     * ({@code BO-05-01-05}).
     *
     * @return a 303 back to the screen with the notice
     */
    @POST
    @Path("/relaunch")
    @Authenticated
    @FeatureAccess(value = Feature.SAFT_EXPORT, option = CrudOption.UPDATE)
    public Response relaunch() {
        LOGGER.info("Entering method relaunch");
        int relaunched = saftStreamService.relaunch();
        LOGGER.info("Exiting method relaunch");
        return back(relaunched + " envoi(s) SAF-T relancé(s).", true);
    }

    /**
     * Whether the previous month's file is late ({@code BO-05-01-04}): not
     * transmitted, and the deadline day passed.
     *
     * @param transmitted whether a file of the previous month was transmitted
     * @param dayOfMonth today's day of the month
     * @param deadline the administered deadline day
     * @return true when the screen warns of the delay
     */
    static boolean late(boolean transmitted, int dayOfMonth, int deadline) {
        return !transmitted && dayOfMonth > deadline;
    }

    /**
     * Reads a month typed in the form.
     *
     * @param value the month, {@code yyyy-MM}, possibly null
     * @return its first day, or null when unreadable
     */
    static LocalDate month(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.YearMonth.parse(value.trim()).atDay(1);
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    /**
     * Builds the 303 back to this screen.
     *
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    private Response back(String notice, boolean ok) {
        return AdminForms.redirect("/admin/saft", notice, ok);
    }

    /**
     * Downloads the SAF-T file of a period.
     *
     * @param from the first day, ISO, or null for the first day of the month
     * @param to the last day, ISO, or null for today
     * @return the XML file, named after the company and the period
     */
    @GET
    @Path("/export")
    @Authenticated
    @FeatureAccess(value = Feature.SAFT_EXPORT)
    @Produces("application/xml; charset=UTF-8")
    public Response export(@QueryParam("from") String from, @QueryParam("to") String to) {
        LOGGER.info("Entering method export with from: " + from + ", to: " + to);
        LocalDate today = LocalDate.now();
        LocalDate first = day(from, today.withDayOfMonth(1));
        LocalDate last = day(to, today);
        String file = saftExportService.export(first, last, LocalDateTime.now());
        LOGGER.info("Exiting method export");
        return Response.ok(file)
                .header("Content-Disposition",
                        "attachment; filename=\"" + saftExportService.fileName(first, last) + "\"")
                .build();
    }

    /**
     * Reads a day typed in the form; blank or unreadable, the default.
     *
     * @param value the ISO day, possibly null
     * @param fallback the day used otherwise
     * @return the day
     */
    static LocalDate day(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException unreadable) {
            return fallback;
        }
    }
}
