package com.intermarche.edge.ui.admin;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.store.CheckoutIsland;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.store.PromotionChannel;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.util.List;
import org.jboss.logging.Logger;

/**
 * The CHECKOUT ISLAND administration screen ({@code /admin/checkout-islands}) —
 * the island promoted to a settings-bearing object (BO-03-10-01/02/03/04/08/10).
 *
 * <p>An island is created, activated and removed here (BO-03-10-01/02/03); it
 * carries its PDV, whether change may be given (BO-03-10-04), the promotion
 * channel it tags transactions with (BO-03-10-08) and whether it declares the
 * end of period on its own (BO-03-10-10). The registers attached to it are
 * administered as checkout points on their own screen — this screen never edits
 * the semicolon terminal list the island used to carry.
 *
 * <p>Two guards the requirement asks for: a deletion is REFUSED while checkout
 * points are still attached (BO-03-10-02), and a deactivation PROPAGATES to the
 * island's registers, which are deactivated with it (BO-03-10-03).
 */
@Path("/admin/checkout-islands")
public class AdminCheckoutIslandResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminCheckoutIslandResource.class);

    /** The redirect target, equal to the resource path. */
    private static final String PAGE = "/admin/checkout-islands";

    /** The checkout-islands page template. */
    @Inject
    @Location("admin-checkout-islands")
    Template adminCheckoutIslands;

    /**
     * Shows the checkout-islands page.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.CHECKOUT_ISLAND)
    @Transactional
    public TemplateInstance islandsPage(@QueryParam("notice") String notice,
                                        @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method islandsPage with notice: " + notice + ", noticeOk: " + noticeOk);
        List<CheckoutIsland> islands = CheckoutIsland.list("order by code");
        LOGGER.info("Exiting method islandsPage");
        return adminCheckoutIslands.data("islands", islands)
                .data("pdvs", Pdv.listAllOrdered())
                .data("channels", PromotionChannel.values())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Upserts a checkout island by its code (BO-03-10-01/03/04/08/10). A
     * deactivation propagates to the island's registers (BO-03-10-03).
     *
     * @param form the posted form (code, label, pdvNumber, active,
     *        changeGivingAllowed, promotionChannel, autoPeriodCloseDeclaration,
     *        hostBadges, planexaExcluded)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/save")
    @Authenticated
    @FeatureAccess(value = Feature.CHECKOUT_ISLAND, option = CrudOption.CREATE, updateKey = "code", entity = com.intermarche.edge.domain.store.CheckoutIsland.class)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveIsland(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveIsland with form: " + form);
        String code = AdminForms.text(form, "code");
        if (code == null) {
            LOGGER.info("Exiting method saveIsland");
            return AdminForms.redirect(PAGE, "Le code de l'îlot est obligatoire.", false);
        }
        String pdvNumber = AdminForms.text(form, "pdvNumber");
        if (pdvNumber != null && Pdv.findByNumber(pdvNumber) == null) {
            LOGGER.info("Exiting method saveIsland");
            return AdminForms.redirect(PAGE, "Point de vente inconnu : " + pdvNumber, false);
        }
        CheckoutIsland existing = CheckoutIsland.findByCode(code);
        CheckoutIsland island = existing;
        if (island == null) {
            island = new CheckoutIsland();
            island.code = code;
        }
        String label = AdminForms.text(form, "label");
        // The label column refuses null: an island saved without one is named
        // by its code rather than answering 500.
        island.label = label == null ? code : label;
        island.pdvNumber = pdvNumber;
        island.changeGivingAllowed = AdminForms.checkbox(form, "changeGivingAllowed");
        PromotionChannel channel = PromotionChannel.fromName(AdminForms.text(form, "promotionChannel"));
        island.promotionChannel = channel == null ? PromotionChannel.POINT_OF_SALE : channel;
        island.autoPeriodCloseDeclaration = AdminForms.checkbox(form, "autoPeriodCloseDeclaration");
        // BO-09-03-01: the hosts attached to the island, declared automatically
        // at the end of period where the store administers it.
        island.hostBadges = AdminForms.text(form, "hostBadges");
        // BO-03-10-06: an island needing no hostess is left out of Planexa.
        island.planexaExcluded = AdminForms.checkbox(form, "planexaExcluded");
        boolean active = AdminForms.checkbox(form, "active");
        island.active = active;
        // The new island is persisted once its fields are set: Hibernate takes
        // the inserted state at persist time, so an earlier persist would insert
        // a null label into a NOT NULL column.
        if (existing == null) {
            island.persist();
        }
        if (!active) {
            propagateDeactivation(code);
        }
        LOGGER.info("Exiting method saveIsland");
        return AdminForms.redirect(PAGE, existing == null ? "Îlot créé." : "Îlot enregistré.", true);
    }

    /**
     * Deletes a checkout island by its code, REFUSED while registers are still
     * attached to it (BO-03-10-02).
     *
     * @param form the posted form (code)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/delete")
    @Authenticated
    @FeatureAccess(value = Feature.CHECKOUT_ISLAND, option = CrudOption.DELETE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deleteIsland(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deleteIsland with form: " + form);
        String code = AdminForms.text(form, "code");
        CheckoutIsland island = CheckoutIsland.findByCode(code);
        if (island == null) {
            LOGGER.info("Exiting method deleteIsland");
            return AdminForms.redirect(PAGE, "Îlot inconnu.", false);
        }
        long attached = CheckoutPoint.countByIsland(code);
        if (attached > 0) {
            LOGGER.info("Exiting method deleteIsland");
            return AdminForms.redirect(PAGE, "Îlot rattaché à " + attached
                    + " caisse(s) : détachez-les avant de le supprimer.", false);
        }
        island.delete();
        LOGGER.info("Exiting method deleteIsland");
        return AdminForms.redirect(PAGE, "Îlot supprimé.", true);
    }

    /**
     * Deactivates every active checkout point of an island — the propagation a
     * deactivated island imposes on its registers (BO-03-10-03).
     *
     * @param islandCode the deactivated island's code
     */
    private void propagateDeactivation(String islandCode) {
        for (CheckoutPoint point : CheckoutPoint.<CheckoutPoint>list("islandCode = ?1 and active = true", islandCode)) {
            point.active = false;
            point.persist();
        }
    }
}
