package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.catalog.MeasureUnit;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.attribute.ProductAttributeCatalog;
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
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;

/**
 * The UNITS OF MEASURE screen ({@code /admin/measure-units}, BO-02-03-17): the
 * codes the Gestion Commerciale writes on the articles, declared with the
 * label the back office reads and the symbol the documents print.
 *
 * <p>Changing a unit's symbol re-labels at once every article carrying its
 * code, so the tickets and invoices of the registers follow at the next draw:
 * the symbol is what travels with the article, the code is what the feed
 * says. A unit some article still carries cannot be removed — it is
 * deactivated instead, which keeps the article's label and stops the unit
 * from being given to a new one.
 *
 * <p>POST&nbsp;→&nbsp;303&nbsp;→&nbsp;notice, the back-office cycle.
 */
@Path("/admin/measure-units")
public class AdminMeasureUnitResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminMeasureUnitResource.class);

    /** The screen the gestures return to. */
    static final String PAGE = "/admin/measure-units";

    /** The units template. */
    @Inject
    @Location("admin-measure-units")
    Template adminMeasureUnits;

    /**
     * Shows the units, and the unit being edited when one is named.
     *
     * @param edit the id of the unit to edit, or null for a creation form
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.MEASURE_UNIT)
    @Transactional
    public TemplateInstance page(@QueryParam("edit") Long edit,
                                 @QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method page with edit: " + edit);
        List<MeasureUnit> units = MeasureUnit.listAllOrdered();
        MeasureUnit edited = edit == null ? null : MeasureUnit.<MeasureUnit>findById(edit);
        LOGGER.info("Exiting method page");
        return adminMeasureUnits.data("units", units)
                .data("edited", edited)
                .data("maxUnits", MeasureUnit.MAX_UNITS)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Creates or updates a unit; an updated symbol re-labels the articles that
     * carry the unit's code.
     *
     * @param form the posted form (id, code, label, symbol, decimalAllowed, active)
     * @return a 303 redirect carrying the notice
     */
    @POST
    @Path("/save")
    @Authenticated
    @FeatureAccess(value = Feature.MEASURE_UNIT, option = CrudOption.CREATE, updateKey = "id")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save with form: " + form);
        String code = code(AdminForms.text(form, "code"));
        String label = AdminForms.text(form, "label");
        String symbol = AdminForms.text(form, "symbol");
        if (code == null || label == null || symbol == null) {
            LOGGER.info("Exiting method save");
            return AdminForms.redirect(PAGE, "Code (1 ou 2 caractères), libellé et symbole sont obligatoires.", false);
        }
        Long id = AdminForms.id(AdminForms.text(form, "id"));
        MeasureUnit edited = id == null ? null : MeasureUnit.<MeasureUnit>findById(id);
        if (id != null && edited == null) {
            LOGGER.info("Exiting method save");
            return AdminForms.redirect(PAGE, "Unité de mesure introuvable.", false);
        }
        MeasureUnit holder = MeasureUnit.findByCode(code);
        if (holder != null && holder != edited) {
            LOGGER.info("Exiting method save");
            return AdminForms.redirect(PAGE, "Le code " + code + " est déjà porté par une autre unité.", false);
        }
        if (edited == null && MeasureUnit.count() >= MeasureUnit.MAX_UNITS) {
            LOGGER.info("Exiting method save");
            return AdminForms.redirect(PAGE, "Le référentiel compte déjà 99 unités de mesure.", false);
        }
        MeasureUnit unit = edited == null ? new MeasureUnit() : edited;
        String previousCode = unit.code;
        unit.code = code;
        unit.label = label;
        unit.symbol = symbol;
        unit.decimalAllowed = AdminForms.checkbox(form, "decimalAllowed");
        unit.active = edited == null || AdminForms.checkbox(form, "active");
        if (edited == null) {
            unit.persist();
        }
        int relabelled = relabel(previousCode == null ? code : previousCode, unit);
        LOGGER.info("Exiting method save");
        return AdminForms.redirect(PAGE, "Unité « " + code + " » enregistrée, " + relabelled
                + " article(s) mis à jour.", true);
    }

    /**
     * Seeds the standard units of the questionnaire that the referential does
     * not declare yet.
     *
     * @return a 303 redirect carrying the notice
     */
    @POST
    @Path("/standard")
    @Authenticated
    @FeatureAccess(value = Feature.MEASURE_UNIT, option = CrudOption.CREATE)
    @Transactional
    public Response seedStandard() {
        LOGGER.info("Entering method seedStandard");
        int created = 0;
        for (String[] standard : MeasureUnit.STANDARD) {
            if (MeasureUnit.findByCode(standard[0]) != null) {
                continue;
            }
            MeasureUnit unit = new MeasureUnit();
            unit.code = standard[0];
            unit.label = standard[1];
            unit.symbol = standard[2];
            unit.decimalAllowed = Boolean.parseBoolean(standard[3]);
            unit.persist();
            created++;
        }
        LOGGER.info("Exiting method seedStandard");
        return AdminForms.redirect(PAGE, created + " unité(s) standard créée(s).", true);
    }

    /**
     * Removes a unit no article carries; a unit still carried is refused.
     *
     * @param form the posted form (id)
     * @return a 303 redirect carrying the notice
     */
    @POST
    @Path("/delete")
    @Authenticated
    @FeatureAccess(value = Feature.MEASURE_UNIT, option = CrudOption.DELETE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response delete(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method delete with form: " + form);
        Long id = AdminForms.id(AdminForms.text(form, "id"));
        MeasureUnit unit = id == null ? null : MeasureUnit.<MeasureUnit>findById(id);
        if (unit == null) {
            LOGGER.info("Exiting method delete");
            return AdminForms.redirect(PAGE, "Unité de mesure introuvable.", false);
        }
        if (!carriers(unit.code).isEmpty()) {
            LOGGER.info("Exiting method delete");
            return AdminForms.redirect(PAGE, "Des articles portent encore l'unité « " + unit.code
                    + " » : désactivez-la plutôt.", false);
        }
        unit.delete();
        LOGGER.info("Exiting method delete");
        return AdminForms.redirect(PAGE, "Unité « " + unit.code + " » supprimée.", true);
    }

    /**
     * Normalises a posted unit code: one or two letters or digits, upper-cased.
     *
     * @param raw the posted value, or null
     * @return the code, or null when absent or malformed
     */
    static String code(String raw) {
        if (raw == null) {
            return null;
        }
        String code = raw.toUpperCase(Locale.ROOT);
        return code.matches("[0-9A-Z]{1,2}") ? code : null;
    }

    /**
     * Re-labels the articles carrying a unit code with the unit's symbol, and
     * moves them to its new code when the code itself was corrected.
     *
     * @param previousCode the code the articles carry
     * @param unit the unit as saved
     * @return the number of articles re-labelled
     */
    private int relabel(String previousCode, MeasureUnit unit) {
        int count = 0;
        for (Product product : carriers(previousCode)) {
            product.unitName = unit.symbol;
            product.attributes.put(ProductAttributeCatalog.UNIT_CODE, unit.code);
            count++;
        }
        return count;
    }

    /**
     * Lists the articles carrying a unit code.
     *
     * @param code the unit code
     * @return the articles
     */
    private List<Product> carriers(String code) {
        return Product.list("select p from Product p join p.attributes a where key(a) = ?1 and a = ?2",
                ProductAttributeCatalog.UNIT_CODE, code);
    }
}
