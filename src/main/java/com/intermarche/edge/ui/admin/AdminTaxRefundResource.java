package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.sale.TaxRefundForm;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.security.FeatureAccess;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * The back office's DÉTAXE page ({@code /admin/tax-refunds}, BO-06-07-03):
 * the welcome desk prints the export sales slip of a customer's ticket, and
 * records the refund of its VAT when the stamped slip comes back.
 *
 * <p>Same admin cycle as the other back-office pages: POST, 303, a one-shot
 * notice; the slip itself is a PDF the desk prints.
 */
@Path("/admin/tax-refunds")
public class AdminTaxRefundResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminTaxRefundResource.class);

    /** The page this resource redirects to. */
    private static final String PAGE = "/admin/tax-refunds";

    /** The page template. */
    @Inject
    @Location("admin-tax-refunds")
    Template adminTaxRefunds;

    /** The detaxe module. */
    @Inject
    TaxRefundService taxRefundService;

    /** The signed-in operator. */
    @Inject
    SecurityIdentity identity;

    /** The back office's language, which the printed slip follows (BO-06-01-02). */
    @Inject
    BackOfficeMessages i18n;

    /**
     * Shows the latest slips and the form to print one.
     *
     * @param notice the one-shot notice, or null
     * @param noticeOk whether the notice reports a success
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.TAX_REFUND)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance page(@QueryParam("notice") String notice, @QueryParam("noticeOk") String noticeOk) {
        LOGGER.info("Entering method page");
        LOGGER.info("Exiting method page");
        return adminTaxRefunds
                .data("forms", TaxRefundForm.latest(100))
                .data("notice", notice)
                .data("noticeOk", !"false".equals(noticeOk));
    }

    /**
     * Prints the slip of a ticket.
     *
     * @param form the posted ticket number and customer
     * @return a 303 back to the page with the outcome
     */
    @POST
    @Path("/issue")
    @Authenticated
    @FeatureAccess(value = Feature.TAX_REFUND, option = CrudOption.CREATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response issue(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method issue");
        TaxRefundService.Outcome outcome = taxRefundService.issue(AdminForms.text(form, "ticket"),
                AdminForms.text(form, "customer"), AdminForms.text(form, "country"),
                AdminForms.text(form, "document"), operator());
        LOGGER.info("Exiting method issue");
        if (outcome.refusal() != null) {
            return AdminForms.redirect(PAGE, outcome.refusal(), false);
        }
        return AdminForms.redirect(PAGE, "Bordereau " + outcome.form().number + " prêt à imprimer.", true);
    }

    /**
     * Records the refund of a slip's VAT.
     *
     * @param form the posted slip number
     * @return a 303 back to the page with the outcome
     */
    @POST
    @Path("/refund")
    @Authenticated
    @FeatureAccess(value = Feature.TAX_REFUND, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response refund(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method refund");
        String refusal = taxRefundService.refund(AdminForms.text(form, "number"), operator());
        LOGGER.info("Exiting method refund");
        return refusal == null ? AdminForms.redirect(PAGE, "Remboursement de la détaxe enregistré.", true)
                : AdminForms.redirect(PAGE, refusal, false);
    }

    /**
     * Downloads a slip to print.
     *
     * @param number the slip's number
     * @return the PDF
     */
    @GET
    @Path("/slip")
    @Authenticated
    @FeatureAccess(value = Feature.TAX_REFUND)
    public Response slip(@QueryParam("number") String number) {
        LOGGER.info("Entering method slip");
        byte[] pdf = taxRefundService.slip(number, i18n.getLanguage());
        if (pdf == null) {
            throw new NotFoundException(Response.status(Response.Status.NOT_FOUND)
                    .entity("Bordereau inconnu : " + number).type(MediaType.TEXT_PLAIN).build());
        }
        LOGGER.info("Exiting method slip");
        return Response.ok(pdf, "application/pdf")
                .header("Content-Disposition", "attachment; filename=\"" + number + ".pdf\"").build();
    }

    /**
     * Names the signed-in operator.
     *
     * @return the principal's name, or null when nobody is signed in
     */
    private String operator() {
        return identity == null || identity.isAnonymous() ? null : identity.getPrincipal().getName();
    }
}
