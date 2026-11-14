package com.intermarche.edge.ui.fiscal;

import com.intermarche.edge.domain.fiscal.EReport;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.fiscal.EReportingService;
import com.intermarche.edge.ui.admin.AdminForms;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The E-REPORTING screen ({@code /admin/fiscal/e-reporting},
 * {@code BO-05-01-03}): the periods of transaction data extracted for the
 * administration — consumer sales and international sales —, their
 * transmission through the dematerialisation platform and their
 * acknowledgement; a period is extracted again and transmitted from here while
 * it is not accepted.
 */
@Path("/admin/fiscal/e-reporting")
public class EReportingResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EReportingResource.class);

    /** How many periods the screen lists. */
    static final int SHOWN = 36;

    /** The screen. */
    @Inject
    @Location("admin-e-reporting")
    Template adminEReporting;

    /** The e-reporting. */
    @Inject
    EReportingService eReportingService;

    /** The activation and the period, shown on the screen. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * Lists the periods extracted.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.E_REPORTING)
    public TemplateInstance list(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list");
        String periodicity = posSettingsService.ereportingPeriod();
        LocalDate[] last = EReportingService.pastPeriods(LocalDate.now(), periodicity, 1).get(0);
        List<EReport> reports = EReport.latest(SHOWN);
        LOGGER.info("Exiting method list with " + reports.size() + " period(s)");
        return adminEReporting.data("reports", reports)
                .data("enabled", posSettingsService.ereportingEnabled())
                .data("periodicity", periodicity)
                .data("start", last[0].toString())
                .data("end", last[1].toString())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Extracts a period, or extracts it again.
     *
     * @param form the posted form: {@code start}, {@code end}
     * @return a 303 back to the list with the notice
     */
    @POST
    @Path("/extract")
    @Authenticated
    @FeatureAccess(value = Feature.E_REPORTING, option = CrudOption.CREATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @jakarta.transaction.Transactional
    public Response extract(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method extract");
        LocalDate start = AdminForms.day(form.getFirst("start"));
        LocalDate end = AdminForms.day(form.getFirst("end"));
        if (start == null || end == null || end.isBefore(start)) {
            LOGGER.info("Exiting method extract: unusable period");
            return back("Période invalide.", false);
        }
        EReport report = eReportingService.extract(start, end, LocalDateTime.now());
        if (report == null) {
            LOGGER.info("Exiting method extract: accepted period");
            return back("Cette période est déjà acceptée par l'administration.", false);
        }
        LOGGER.info("Exiting method extract");
        return back("Période extraite : " + report.transactionCount + " transaction(s).", true);
    }

    /**
     * Transmits one period now.
     *
     * @param form the posted form: {@code id}
     * @return a 303 back to the list with the notice
     */
    @POST
    @Path("/transmit")
    @Authenticated
    @FeatureAccess(value = Feature.E_REPORTING, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @jakarta.transaction.Transactional
    public Response transmit(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method transmit");
        Long id = AdminForms.id(form.getFirst("id"));
        EReport report = id == null ? null : EReport.<EReport>findById(id);
        if (report == null || report.status.isLast()) {
            LOGGER.info("Exiting method transmit: nothing to transmit");
            return back("Transmission inconnue ou déjà acceptée.", false);
        }
        boolean sent = eReportingService.transmit(report, LocalDateTime.now());
        LOGGER.info("Exiting method transmit");
        return sent ? back("Période transmise.", true) : back("Transmission en échec : " + report.message, false);
    }

    /**
     * Downloads the transmitted document of a period.
     *
     * @param id the transmission's identifier
     * @return the XML file, or 404
     */
    @GET
    @Path("/document")
    @Authenticated
    @FeatureAccess(value = Feature.E_REPORTING)
    public Response document(@QueryParam("id") Long id) {
        LOGGER.info("Entering method document with id: " + id);
        EReport report = id == null ? null : EReport.<EReport>findById(id);
        if (report == null) {
            LOGGER.info("Exiting method document: unknown period");
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        LOGGER.info("Exiting method document");
        return TransactionExportResource.file(report.payload.getBytes(StandardCharsets.UTF_8), "application/xml",
                "e-reporting-" + report.periodStart + "-" + report.periodEnd + ".xml");
    }

    /**
     * Builds the 303 back to this screen.
     *
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    private Response back(String notice, boolean ok) {
        return AdminForms.redirect("/admin/fiscal/e-reporting", notice, ok);
    }
}
