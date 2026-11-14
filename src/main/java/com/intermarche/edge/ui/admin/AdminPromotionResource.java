package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.catalog.Promotion;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.security.FeatureAccess;
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

/**
 * The back office's PROMOTIONS page ({@code /admin/promotions}): the number,
 * the label, the type — by value or in percent — and the upstream financing of
 * each promotion the promotional states report on (BO-06-08-01, BO-06-08-02).
 *
 * <p>The number is the offer identifier the valuation engine stamps on the
 * lines, or the rule code of a loyalty advantage: the states join the sold
 * lines to this list by it. Same admin cycle as the other back-office pages:
 * POST, 303, a one-shot notice.
 */
@Path("/admin/promotions")
public class AdminPromotionResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminPromotionResource.class);

    /** The page this resource redirects to. */
    private static final String PAGE = "/admin/promotions";

    /** The page template. */
    @Inject
    @Location("admin-promotions")
    Template adminPromotions;

    /**
     * Shows the promotions and the form to describe one.
     *
     * @param notice the one-shot notice, or null
     * @param noticeOk whether the notice reports a success
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.PROMOTION)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance page(@QueryParam("notice") String notice, @QueryParam("noticeOk") String noticeOk) {
        LOGGER.info("Entering method page");
        LOGGER.info("Exiting method page");
        return adminPromotions
                .data("promotions", Promotion.listOrdered())
                .data("notice", notice)
                .data("noticeOk", !"false".equals(noticeOk));
    }

    /**
     * Creates or updates a promotion, found by its number.
     *
     * @param form the posted promotion: code, label, kind, upstreamFunded
     * @return a 303 back to the page with a notice
     */
    @POST
    @Path("/save")
    @Authenticated
    @FeatureAccess(value = Feature.PROMOTION, option = CrudOption.CREATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save");
        String code = AdminForms.text(form, "code");
        String label = AdminForms.text(form, "label");
        if (code == null || label == null) {
            LOGGER.info("Exiting method save: incomplete");
            return AdminForms.redirect(PAGE, "Numéro et libellé de la promotion obligatoires.", false);
        }
        String kind = Promotion.BY_PERCENT.equals(AdminForms.text(form, "kind"))
                ? Promotion.BY_PERCENT : Promotion.BY_VALUE;
        Promotion promotion = Promotion.findByCode(code);
        boolean created = promotion == null;
        if (created) {
            promotion = new Promotion();
            promotion.code = code;
        }
        promotion.label = label;
        promotion.kind = kind;
        promotion.upstreamFunded = AdminForms.checkbox(form, "upstreamFunded");
        if (created) {
            promotion.persist();
        }
        LOGGER.infof("Promotion %s enregistrée (%s)", code, kind);
        LOGGER.info("Exiting method save");
        return AdminForms.redirect(PAGE, "Promotion enregistrée.", true);
    }

    /**
     * Removes a promotion's description; its sales stay counted under its number.
     *
     * @param form the posted number
     * @return a 303 back to the page with a notice
     */
    @POST
    @Path("/delete")
    @Authenticated
    @FeatureAccess(value = Feature.PROMOTION, option = CrudOption.DELETE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response delete(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method delete");
        Promotion promotion = Promotion.findByCode(AdminForms.text(form, "code"));
        if (promotion == null) {
            LOGGER.info("Exiting method delete: unknown");
            return AdminForms.redirect(PAGE, "Promotion inconnue.", false);
        }
        promotion.delete();
        LOGGER.info("Exiting method delete");
        return AdminForms.redirect(PAGE, "Promotion supprimée.", true);
    }
}
