package com.intermarche.edge.ui.fiscal;

import com.intermarche.edge.domain.fiscal.EInvoice;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.fiscal.EInvoicingService;
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
import java.time.LocalDateTime;
import java.util.List;

/**
 * The E-INVOICING screen ({@code /admin/fiscal/e-invoicing},
 * {@code BO-05-01-02}): the electronic invoices of the point of sale — their
 * structured form, their deposit on the dematerialisation platform and their
 * life cycle, status by status — with the gestures that do not wait for the
 * scheduler: queue the invoices concerned, deposit one, refresh its status.
 */
@Path("/admin/fiscal/e-invoicing")
public class EInvoicingResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EInvoicingResource.class);

    /** How many invoices the screen lists. */
    static final int SHOWN = 200;

    /** The screen. */
    @Inject
    @Location("admin-e-invoicing")
    Template adminEInvoicing;

    /** The electronic invoices. */
    @Inject
    EInvoicingService eInvoicingService;

    /** The activation and the platform, shown on the screen. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * Lists the electronic invoices, of one status or all.
     *
     * @param status the status, blank for all
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.E_INVOICING)
    public TemplateInstance list(@QueryParam("status") String status, @QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list with status: " + status);
        EInvoice.Status chosen = EInvoice.Status.of(status);
        List<EInvoice> invoices = EInvoice.latest(chosen, SHOWN);
        LOGGER.info("Exiting method list with " + invoices.size() + " invoice(s)");
        return adminEInvoicing.data("invoices", invoices)
                .data("statuses", List.of(EInvoice.Status.values()))
                .data("status", chosen == null ? "" : chosen.name())
                .data("enabled", posSettingsService.einvoicingEnabled())
                .data("platformCode", posSettingsService.pdpProviderCode())
                .data("platformReady", eInvoicingService.platform() != null)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Queues now the recent invoices concerned that are not queued yet.
     *
     * @return a 303 back to the list with the notice
     */
    @POST
    @Path("/scan")
    @Authenticated
    @FeatureAccess(value = Feature.E_INVOICING, option = CrudOption.CREATE)
    @jakarta.transaction.Transactional
    public Response scan() {
        LOGGER.info("Entering method scan");
        int queued = eInvoicingService.scan(LocalDateTime.now());
        LOGGER.info("Exiting method scan");
        return back(queued + " facture(s) mise(s) en file.", true);
    }

    /**
     * Deposits one invoice now.
     *
     * @param form the posted form: {@code id}
     * @return a 303 back to the list with the notice
     */
    @POST
    @Path("/deposit")
    @Authenticated
    @FeatureAccess(value = Feature.E_INVOICING, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @jakarta.transaction.Transactional
    public Response deposit(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deposit");
        EInvoice invoice = invoiceOf(form);
        if (invoice == null) {
            LOGGER.info("Exiting method deposit: unknown invoice");
            return back("Facture électronique inconnue.", false);
        }
        boolean deposited = eInvoicingService.deposit(invoice, LocalDateTime.now());
        LOGGER.info("Exiting method deposit");
        return deposited ? back("Facture déposée sur la plateforme.", true)
                : back("Dépôt en échec : " + invoice.lastError, false);
    }

    /**
     * Refreshes the status of one invoice now.
     *
     * @param form the posted form: {@code id}
     * @return a 303 back to the list with the notice
     */
    @POST
    @Path("/refresh")
    @Authenticated
    @FeatureAccess(value = Feature.E_INVOICING, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @jakarta.transaction.Transactional
    public Response refresh(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method refresh");
        EInvoice invoice = invoiceOf(form);
        if (invoice == null) {
            LOGGER.info("Exiting method refresh: unknown invoice");
            return back("Facture électronique inconnue.", false);
        }
        boolean changed = eInvoicingService.refresh(invoice, LocalDateTime.now());
        LOGGER.info("Exiting method refresh");
        return back(changed ? "Statut mis à jour." : "Statut inchangé.", true);
    }

    /**
     * Downloads the structured invoice as it was deposited.
     *
     * @param id the electronic invoice's identifier
     * @return the XML file, or 404
     */
    @GET
    @Path("/document")
    @Authenticated
    @FeatureAccess(value = Feature.E_INVOICING)
    public Response document(@QueryParam("id") Long id) {
        LOGGER.info("Entering method document with id: " + id);
        EInvoice invoice = id == null ? null : EInvoice.<EInvoice>findById(id);
        if (invoice == null) {
            LOGGER.info("Exiting method document: unknown invoice");
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        LOGGER.info("Exiting method document");
        return TransactionExportResource.file(invoice.payload.getBytes(StandardCharsets.UTF_8), "application/xml",
                "facture-" + invoice.invoiceNumber + "-cii.xml");
    }

    /**
     * Reads the electronic invoice a form names.
     *
     * @param form the posted form
     * @return the invoice, or null
     */
    private EInvoice invoiceOf(MultivaluedMap<String, String> form) {
        Long id = AdminForms.id(form.getFirst("id"));
        return id == null ? null : EInvoice.<EInvoice>findById(id);
    }

    /**
     * Builds the 303 back to this screen.
     *
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    private Response back(String notice, boolean ok) {
        return AdminForms.redirect("/admin/fiscal/e-invoicing", notice, ok);
    }
}
