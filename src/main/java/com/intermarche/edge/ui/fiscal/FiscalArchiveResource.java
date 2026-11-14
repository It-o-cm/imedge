package com.intermarche.edge.ui.fiscal;

import com.intermarche.edge.domain.fiscal.FiscalArchive;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.security.CurrentUser;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.fiscal.FiscalArchiveService;
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

import java.time.LocalDate;
import java.util.List;

/**
 * The FISCAL ARCHIVES screen ({@code /admin/fiscal/archives},
 * {@code BO-05-01-01}): the {@code TaxAuditExtracts} and
 * {@code TaxAuditReports} archives of the point of sale, at the disposal of
 * its management controller — listed with their period, size and SHA-256
 * fingerprint, produced on demand for any period, and downloaded.
 *
 * <p>The profile grant {@code fiscal_archive} is what puts it in the
 * controller's menu; the node also produces the month just ended by itself
 * when {@code fiscal.archive-auto} is on.
 */
@Path("/admin/fiscal/archives")
public class FiscalArchiveResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(FiscalArchiveResource.class);

    /** How many archives the screen lists. */
    static final int SHOWN = 60;

    /** The screen. */
    @Inject
    @Location("admin-fiscal-archives")
    Template adminFiscalArchives;

    /** Produces the archives. */
    @Inject
    FiscalArchiveService fiscalArchiveService;

    /** The automatic production, shown on the screen. */
    @Inject
    PosSettingsService posSettingsService;

    /** Who asks, named on the archive. */
    @Inject
    CurrentUser currentUser;

    /**
     * Lists the archives kept.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.FISCAL_ARCHIVE)
    public TemplateInstance list(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list");
        LocalDate today = LocalDate.now();
        List<FiscalArchive> archives = FiscalArchive.latest(
                List.of(FiscalArchive.Kind.TAX_AUDIT_EXTRACTS, FiscalArchive.Kind.TAX_AUDIT_REPORTS), SHOWN);
        LOGGER.info("Exiting method list with " + archives.size() + " archive(s)");
        return adminFiscalArchives.data("archives", archives)
                .data("from", today.minusMonths(1).withDayOfMonth(1).toString())
                .data("to", today.withDayOfMonth(1).minusDays(1).toString())
                .data("pdv", fiscalArchiveService.pdvNumber())
                .data("automatic", posSettingsService.fiscalArchiveAuto())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Produces the two archives of a period.
     *
     * @param form the posted form: {@code from}, {@code to}
     * @return a 303 back to the list with the notice
     */
    @POST
    @Path("/generate")
    @Authenticated
    @FeatureAccess(value = Feature.FISCAL_ARCHIVE, option = CrudOption.CREATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @jakarta.transaction.Transactional
    public Response generate(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method generate");
        LocalDate from = AdminForms.day(form.getFirst("from"));
        LocalDate to = AdminForms.day(form.getFirst("to"));
        if (from == null || to == null || to.isBefore(from) || to.isAfter(LocalDate.now())) {
            LOGGER.info("Exiting method generate: unusable period");
            return back("Période invalide : le premier jour précède le dernier, qui n'est pas dans le futur.", false);
        }
        List<FiscalArchive> archives = fiscalArchiveService.generate(from, to,
                currentUser == null ? null : currentUser.getName(), false);
        LOGGER.info("Exiting method generate");
        return back("Archives produites : " + archives.get(0).fileName + ", " + archives.get(1).fileName, true);
    }

    /**
     * Downloads an archive as it was produced.
     *
     * @param id the archive's identifier
     * @return the file, or 404
     */
    @GET
    @Path("/download")
    @Authenticated
    @FeatureAccess(value = Feature.FISCAL_ARCHIVE)
    public Response download(@QueryParam("id") Long id) {
        LOGGER.info("Entering method download with id: " + id);
        FiscalArchive archive = id == null ? null : FiscalArchive.<FiscalArchive>findById(id);
        if (archive == null) {
            LOGGER.info("Exiting method download: unknown archive");
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        LOGGER.info("Exiting method download");
        return TransactionExportResource.file(archive.content,
                archive.kind == FiscalArchive.Kind.SAFT_MONTHLY ? "application/xml" : "application/zip",
                archive.fileName);
    }

    /**
     * Builds the 303 back to this screen.
     *
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    private Response back(String notice, boolean ok) {
        return AdminForms.redirect("/admin/fiscal/archives", notice, ok);
    }
}
