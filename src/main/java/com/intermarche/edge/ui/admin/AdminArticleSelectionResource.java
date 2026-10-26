package com.intermarche.edge.ui.admin;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.catalog.ArticleSelection;
import com.intermarche.edge.domain.catalog.ProductType;
import com.intermarche.edge.domain.catalog.VatRate;
import com.intermarche.edge.domain.catalog.attribute.ProductAttributeCatalog;
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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.jboss.logging.Logger;

/**
 * The ARTICLE SELECTIONS administration screen
 * ({@code /admin/article-selections}, BO-02-03-31): saves named, reusable
 * article perimeters — a grouping defined by the same multi-criteria engine the
 * fiche list uses ({@link ArticleSearchCriteria}) — so a report or an export can
 * restrict its scope to a selection. Create, redefine and delete selections.
 * <p>
 * A selection stores its criteria as the canonical query string of
 * {@link ArticleSearchCriteria}, normalised on save, so applying a selection is
 * exactly re-running that search. Access reserved to ADMIN, like every
 * article-area screen. POST&nbsp;→&nbsp;303&nbsp;→&nbsp;notice.
 * <p>
 * BO-02-03-31 in full: a list is saved under a name PER POINT OF SALE, found
 * again by searching its name, modified, DUPLICATED into a new distinct list
 * the chef de rayon then adapts, and deleted; its criteria are those of the
 * article search, EAN range, families and rayons, price range, labels and VAT
 * rate included. The lists are reused as the perimeter of the article export
 * and of the reports.
 */
@Path("/admin/article-selections")
public class AdminArticleSelectionResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminArticleSelectionResource.class);

    /** This node's point of sale, which owns the lists it saves. */
    @Inject
    com.intermarche.edge.service.NodeEchelonService nodeEchelonService;

    /** The selections list template. */
    @Inject
    @Location("admin-article-selections")
    Template adminArticleSelections;

    /**
     * Shows the saved selections, ordered by name, with the well-known
     * attribute catalog the create form offers as criteria.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the list page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE_SELECTION)
    public TemplateInstance list(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk,
                                 @QueryParam("q") String q,
                                 @QueryParam("edit") Long edit) {
        LOGGER.info("Entering method list with notice: " + notice + ", noticeOk: " + noticeOk);
        String needle = trimmed(q).toLowerCase();
        java.util.List<ArticleSelection> selections = new java.util.ArrayList<>();
        for (ArticleSelection selection : ArticleSelection.listForPdv(currentPdv())) {
            if (needle.isEmpty() || selection.name.toLowerCase().contains(needle)) {
                selections.add(selection);
            }
        }
        ArticleSelection edited = edit == null ? null : ArticleSelection.<ArticleSelection>findById(edit);
        LOGGER.info("Exiting method list");
        java.util.Map<Long, java.util.List<CriterionView>> views = new java.util.HashMap<>();
        for (ArticleSelection selection : selections) {
            views.put(selection.id, describe(selection.criteria));
        }
        return adminArticleSelections.data("selections", selections)
                .data("criteriaViews", views)
                .data("edited", edited)
                .data("criteria", ArticleSearchCriteria.fromQueryString(edited == null ? null : edited.criteria))
                .data("q", trimmed(q))
                .data("pdv", currentPdv())
                .data("attributeDefs", AdminArticleResource.boolDefs())
                .data("vatRates", VatRate.listAllOrdered())
                .data("familyNodes", AdminArticleResource.familyNodes())
                .data("types", ProductType.values())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * The point of sale of this node, null on a resource built without it.
     *
     * @return the PDV code, or null
     */
    String currentPdv() {
        return nodeEchelonService == null ? null : nodeEchelonService.currentPdv();
    }

    /**
     * Duplicates a list into a new, distinct one of this point of sale
     * (BO-02-03-31), under the posted name or "Copie de" the original's.
     *
     * @param form the posted form (id, name optional)
     * @return a 303 redirect carrying the notice
     */
    @POST
    @Path("/duplicate")
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE_SELECTION, option = CrudOption.CREATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response duplicate(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method duplicate with form: " + form);
        Long id = parseId(form.getFirst("id"));
        ArticleSelection original = id != null ? ArticleSelection.<ArticleSelection>findById(id) : null;
        if (original == null) {
            LOGGER.info("Exiting method duplicate");
            return redirect("Sélection introuvable.", false);
        }
        String name = trimmed(form.getFirst("name"));
        if (name.isEmpty()) {
            name = "Copie de " + original.name;
        }
        ArticleSelection clash = ArticleSelection.findForPdv(name, currentPdv());
        if (clash != null) {
            LOGGER.info("Exiting method duplicate");
            return redirect("Une sélection porte déjà le nom « " + name + " ».", false);
        }
        ArticleSelection copy = new ArticleSelection();
        copy.name = name;
        copy.criteria = original.criteria;
        copy.pdvCode = currentPdv();
        copy.persist();
        LOGGER.info("Exiting method duplicate");
        return redirect("Sélection « " + name + " » créée par duplication.", true);
    }

    /**
     * Saves a selection — create when no id is posted, redefine otherwise. The
     * criteria fields of the posted form are normalised through
     * {@link ArticleSearchCriteria} into a canonical query string.
     *
     * @param form the posted form (id optional, name required, plus criteria)
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE_SELECTION, option = CrudOption.CREATE, updateKey = "id")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save with form: " + form);
        String name = trimmed(form.getFirst("name"));
        if (name.isEmpty()) {
            LOGGER.info("Exiting method save");
            return redirect("Le nom de la sélection est obligatoire.", false);
        }
        Long id = parseId(form.getFirst("id"));
        ArticleSelection selection = id != null
                ? ArticleSelection.<ArticleSelection>findById(id) : null;
        if (id != null && selection == null) {
            LOGGER.info("Exiting method save");
            return redirect("Sélection introuvable.", false);
        }
        ArticleSelection clash = ArticleSelection.findForPdv(name, currentPdv());
        if (clash != null && !clash.equals(selection)) {
            LOGGER.info("Exiting method save");
            return redirect("Une sélection porte déjà le nom « " + name + " ».", false);
        }
        String criteria = ArticleSearchCriteria.fromParams(form).toQueryString();
        if (selection == null) {
            selection = new ArticleSelection();
            selection.name = name;
            selection.criteria = criteria;
            selection.pdvCode = currentPdv();
            selection.persist();
        } else {
            selection.name = name;
            selection.criteria = criteria;
        }
        LOGGER.info("Exiting method save");
        return redirect("Sélection « " + name + " » enregistrée.", true);
    }

    /**
     * Deletes a saved selection.
     *
     * @param form the posted form carrying the id
     * @return a 303 redirect carrying the outcome notice
     */
    @POST
    @Path("/delete")
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE_SELECTION, option = CrudOption.DELETE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response delete(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method delete with form: " + form);
        Long id = parseId(form.getFirst("id"));
        ArticleSelection selection = id != null
                ? ArticleSelection.<ArticleSelection>findById(id) : null;
        if (selection == null) {
            LOGGER.info("Exiting method delete");
            return redirect("Sélection introuvable.", false);
        }
        String name = selection.name;
        selection.delete();
        LOGGER.info("Exiting method delete");
        return redirect("Sélection « " + name + " » supprimée.", true);
    }

    /**
     * Parses a selection id, tolerating a null, blank or malformed value.
     *
     * @param raw the raw id, or null
     * @return the parsed id, or null when absent or malformed
     */
    private Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Trims a posted field, mapping a null to an empty string.
     *
     * @param raw the posted value, or null
     * @return the trimmed value, never null
     */
    private String trimmed(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /**
     * One criterion of a saved list as the list screen shows it (BO-02-03-31):
     * the bundle key of its label and either the bundle key of its value or
     * the value itself, so the page reads in its own language instead of
     * showing the stored query string.
     */
    public static final class CriterionView {

        /** The bundle key of the criterion's label, or null for an unknown name. */
        public final String labelKey;

        /** The raw parameter name, shown when the name is unknown. */
        public final String name;

        /** The value as stored, or the French label of an attribute. */
        public final String value;

        /** The bundle key of a coded value (status, sale), or null. */
        public final String valueKey;

        /**
         * Creates a view.
         *
         * @param labelKey the label key, or null
         * @param name the raw parameter name
         * @param value the value
         * @param valueKey the value key, or null
         */
        CriterionView(String labelKey, String name, String value, String valueKey) {
            this.labelKey = labelKey;
            this.name = name;
            this.value = value;
            this.valueKey = valueKey;
        }
    }

    /** The label key of each criterion a selection stores, by parameter name. */
    static final java.util.Map<String, String> CRITERION_LABELS = java.util.Map.ofEntries(
            java.util.Map.entry("text", "t.texte-ean-libelle-code-interne"),
            java.util.Map.entry("type", "t.type"),
            java.util.Map.entry("status", "t.statut"),
            java.util.Map.entry("forbidden", "t.vente"),
            java.util.Map.entry("eanFrom", "t.ean-de"),
            java.util.Map.entry("eanTo", "t.ean-a"),
            java.util.Map.entry("attr", "t.attribut"),
            java.util.Map.entry("plu", "t.plu"),
            java.util.Map.entry("internalCode", "t.code-interne"),
            java.util.Map.entry("description", "t.description"),
            java.util.Map.entry("checkoutLabel", "t.libelle-encaissement"),
            java.util.Map.entry("supplier", "t.p20-fournisseur-code-ou-nom"),
            java.util.Map.entry("supplierArticle", "t.p20-article-fournisseur-code-ou-nom"),
            java.util.Map.entry("brand", "t.marque"),
            java.util.Map.entry("family", "t.p20-familles-rayons"),
            java.util.Map.entry("priceMin", "t.p20-prix-de"),
            java.util.Map.entry("priceMax", "t.p20-prix-a"),
            java.util.Map.entry("vat", "t.tva"));

    /** The bundle key of each coded criterion value, by {@code name=value}. */
    static final java.util.Map<String, String> CRITERION_VALUES = java.util.Map.of(
            "status=ACTIVE", "t.actifs",
            "status=INACTIVE", "t.desactives",
            "forbidden=yes", "t.interdites",
            "forbidden=no", "t.autorisees");

    /**
     * Reads a stored perimeter back as the criteria a person set
     * (BO-02-03-31): one view per parameter, in stored order, an attribute
     * named by its label rather than its code.
     *
     * @param criteria the stored query string, possibly null or blank
     * @return the views, empty for a list taking every article
     */
    static java.util.List<CriterionView> describe(String criteria) {
        java.util.List<CriterionView> views = new java.util.ArrayList<>();
        if (criteria == null || criteria.isBlank()) {
            return views;
        }
        for (String pair : criteria.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = java.net.URLDecoder.decode(eq >= 0 ? pair.substring(0, eq) : pair, StandardCharsets.UTF_8);
            String value = java.net.URLDecoder.decode(eq >= 0 ? pair.substring(eq + 1) : "", StandardCharsets.UTF_8);
            com.intermarche.edge.domain.catalog.attribute.ProductAttributeDef attribute =
                    "attr".equals(name) ? ProductAttributeCatalog.def(value) : null;
            views.add(new CriterionView(CRITERION_LABELS.get(name), name,
                    attribute != null ? attribute.label() : value, CRITERION_VALUES.get(name + "=" + value)));
        }
        return views;
    }

    /**
     * Builds the 303 redirect back to the list carrying the one-shot notice.
     *
     * @param notice the message to display
     * @param ok whether it reports a success
     * @return the redirect response
     */
    private Response redirect(String notice, boolean ok) {
        String target = "/admin/article-selections?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8);
        return Response.seeOther(URI.create(target)).build();
    }
}
