package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.catalog.ArticleAttributeDefinition;
import com.intermarche.pos.domain.catalog.ArticleSelection;
import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductType;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributeDef;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributes;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jboss.logging.Logger;

/**
 * The ARTICLE FILE back-office screen ({@code /admin/articles}, BO-02-03-*):
 * lists and searches the referential articles (BO-02-03-30), edits on an
 * existing fiche the base data (BO-02-03-28/33) and the declared attributes
 * (BO-02-03-29/32), reads the sale-price history and the offers
 * (BO-02-03-34/35), and administers the on-screen article-code flag one fiche
 * at a time or in bulk (BO-02-03-45).
 * <p>
 * Only EXISTING fiches are edited here: the creation of an article stays on the
 * integration surfaces (CSV import, GraphQL), matching the "intégration en
 * provenance de la Gestion Commerciale" the requirements describe and the same
 * doctrine as {@code AdminStoreResource}. What is edited here is referential
 * data: on a store node it flows to every register at the next draw, which is
 * how the attributes reach the till. This screen carries NO sale behaviour — it
 * reads and administers; the register consumption of what it sets (the on-screen
 * article code, BO-10-02-35) lives on the sale surface, in its own lot.
 * <p>
 * "Deleting" an article is a deactivation (BO-02-03-33): the {@code active}
 * flag is cleared, never a row removed, because sold ticket lines hold product
 * foreign keys — the same contract the referential pull honours.
 * <p>
 * Access reserved to ADMIN, like every parameter screen. POST&nbsp;→&nbsp;303&nbsp;→&nbsp;notice.
 */
@Path("/admin/articles")
public class AdminArticleResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminArticleResource.class);

    /**
     * The attribute code under which the on-screen article-code flag is stored
     * (BO-02-03-45). A presentation attribute carried in the open
     * {@link Product#attributes} map, so it is distributed by the draw like any
     * other article datum without a dedicated column; the sale surface reads it
     * in its own lot (BO-10-02-35).
     */
    public static final String CODE_ON_SCREEN = "CODE_ARTICLE_ON_SCREEN";

    /** The article list template. */
    @Inject
    @Location("admin-articles")
    Template adminArticles;

    /** The article edit-form template. */
    @Inject
    @Location("admin-article")
    Template adminArticle;

    /**
     * A rendered attribute row of the fiche form: a well-known catalog entry
     * with its current boolean value. Every declared attribute is a boolean
     * toggle today ({@code ProductAttributeCatalog} holds only BOOL entries),
     * so the form renders one checkbox per row.
     */
    public static class AttributeRow {
        /** The attribute code (posted field name). */
        public String code;
        /** The operator-facing label. */
        public String label;
        /** The current boolean value. */
        public boolean checked;
    }

    /**
     * A rendered custom-attribute row of the fiche form (BO-02-03-32): an
     * administered {@link ArticleAttributeDefinition} with its current text
     * value, captured as free text.
     */
    public static class CustomAttrRow {
        /** The attribute code (posted field name). */
        public String code;
        /** The operator-facing label. */
        public String label;
        /** The current text value, empty when unset. */
        public String value;
    }

    /**
     * A rendered raw attribute entry of the fiche (BO-02-03-29): one code&nbsp;→&nbsp;value
     * pair of the article's full attribute map, shown read-only so nothing an
     * article carries is hidden.
     */
    public static class AttrEntry {
        /** The attribute code. */
        public String code;
        /** The stored text value. */
        public String value;
    }

    /**
     * Shows the article list, filtered by the multi-criteria search
     * (BO-02-03-30) or by an applied saved selection (BO-02-03-31), ordered by
     * EAN.
     *
     * @param uriInfo the request URI carrying the search criteria
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the list page
     */
    @GET
    @RolesAllowed("ADMIN")
    public TemplateInstance list(@Context UriInfo uriInfo,
                                 @QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list with uriInfo: " + uriInfo + ", notice: " + notice + ", noticeOk: " + noticeOk);
        MultivaluedMap<String, String> params = uriInfo.getQueryParameters();
        String selectionName = ArticleSearchCriteria.blankToNull(params.getFirst("selection"));
        ArticleSelection selection = selectionName != null
                ? ArticleSelection.findByName(selectionName) : null;
        ArticleSearchCriteria criteria = selection != null
                ? ArticleSearchCriteria.fromQueryString(selection.criteria)
                : ArticleSearchCriteria.fromParams(params);
        List<Product> matches = new ArrayList<>();
        for (Product product : Product.<Product>find("order by ean").list()) {
            if (criteria.matches(product)) {
                matches.add(product);
            }
        }
        LOGGER.info("Exiting method list");
        return adminArticles.data("products", matches)
                .data("criteria", criteria)
                .data("selectedSelection", selection != null ? selection.name : null)
                .data("types", ProductType.values())
                .data("attributeDefs", ProductAttributeCatalog.CATALOG)
                .data("selections", ArticleSelection.listAllOrdered())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Shows the edit form of one article fiche, with all its base data
     * (BO-02-03-28), all its attributes (BO-02-03-29), and its offers and
     * sale-price history (BO-02-03-34/35).
     *
     * @param id the product id
     * @return the form page; {@code product} is null when the id is unknown
     */
    @GET
    @Path("/edit")
    @RolesAllowed("ADMIN")
    public TemplateInstance edit(@QueryParam("id") Long id) {
        LOGGER.info("Entering method edit with id: " + id);
        Product product = id != null ? Product.<Product>findById(id) : null;
        List<Price> history = product != null ? Price.findByProduct(product.id) : List.of();
        List<Price> offers = new ArrayList<>();
        for (Price price : history) {
            if (price.priority != null && price.priority > 0) {
                offers.add(price);
            }
        }
        Price currentPrice = product != null ? Price.findCurrentPrice(product.id) : null;
        LOGGER.info("Exiting method edit");
        return adminArticle.data("product", product)
                .data("attributes", product != null ? attributeRows(product) : List.of())
                .data("customAttributes", product != null ? customAttributeRows(product) : List.of())
                .data("allAttributes", allAttributes(product))
                .data("codeOnScreen", product != null && ProductAttributes.flag(product, CODE_ON_SCREEN))
                .data("currentPrice", currentPrice)
                .data("offers", offers)
                .data("history", history);
    }

    /**
     * Saves the editable fields, the declared attributes and the presentation
     * flag of an existing fiche.
     *
     * @param form the posted form
     * @return a 303 redirect to the list with the notice
     */
    @POST
    @Path("/edit")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save with form: " + form);
        Long id = parseId(form.getFirst("id"));
        Product product = id != null ? Product.<Product>findById(id) : null;
        if (product == null) {
            LOGGER.info("Exiting method save");
            return redirect("Article introuvable.", false);
        }
        product.checkoutLabel = blankToNull(form.getFirst("checkoutLabel"));
        product.internalCode = blankToNull(form.getFirst("internalCode"));
        product.description = blankToNull(form.getFirst("description"));
        product.brand = blankToNull(form.getFirst("brand"));
        product.active = form.getFirst("active") != null;
        product.forbiddenToSale = form.getFirst("forbiddenToSale") != null;
        product.ageRestriction = parseAge(form.getFirst("ageRestriction"));
        // BO-02-03-17/33: the unit label and the three decimals were rendered
        // read-only although the data exists — now editable from the fiche.
        product.unitName = blankToNull(form.getFirst("unitName"));
        product.referenceWeight = parseDecimal(form.getFirst("referenceWeight"), 3);
        product.referenceVolume = parseDecimal(form.getFirst("referenceVolume"), 3);
        product.giftCardAmount = parseDecimal(form.getFirst("giftCardAmount"), 2);
        // BO-02-03-24/28: the variable-weight (bulk) marker was fed by the CSV
        // but absent from the fiche — now a checkbox, present = true.
        product.variableWeight = form.getFirst("variableWeight") != null;
        for (ProductAttributeDef def : ProductAttributeCatalog.CATALOG) {
            product.attributes.put(def.code(),
                    form.getFirst(def.code()) != null ? "true" : "false");
        }
        for (ArticleAttributeDefinition def : ArticleAttributeDefinition.listAllOrdered()) {
            String value = blankToNull(form.getFirst(def.code));
            if (value != null) {
                product.attributes.put(def.code, value);
            } else {
                product.attributes.remove(def.code);
            }
        }
        product.attributes.put(CODE_ON_SCREEN,
                form.getFirst("codeOnScreen") != null ? "true" : "false");
        LOGGER.info("Exiting method save");
        return redirect("Fiche article « " + product.ean + " » enregistrée.", true);
    }

    /**
     * Sets or clears the on-screen article-code flag in bulk (BO-02-03-45),
     * over every article matching the posted search criteria — an empty
     * criteria applies to the whole referential.
     *
     * @param form the posted form (a {@code value} of {@code on} to set, plus
     *        the search criteria scoping the batch)
     * @return a 303 redirect to the list with the notice
     */
    @POST
    @Path("/bulk-code")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response bulkCodeOnScreen(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method bulkCodeOnScreen with form: " + form);
        boolean value = form.getFirst("value") != null;
        ArticleSearchCriteria criteria = ArticleSearchCriteria.fromParams(form);
        int count = 0;
        for (Product product : Product.<Product>find("order by ean").list()) {
            if (criteria.matches(product)) {
                product.attributes.put(CODE_ON_SCREEN, value ? "true" : "false");
                count++;
            }
        }
        LOGGER.info("Exiting method bulkCodeOnScreen");
        return redirect("Code article à l'écran " + (value ? "activé" : "désactivé")
                + " sur " + count + " article(s).", true);
    }

    /**
     * Builds the well-known attribute rows of the form from the catalog and the
     * product's current values.
     *
     * @param product the edited product
     * @return the rendered rows, in catalog order
     */
    List<AttributeRow> attributeRows(Product product) {
        List<AttributeRow> rows = new ArrayList<>();
        for (ProductAttributeDef def : ProductAttributeCatalog.CATALOG) {
            AttributeRow row = new AttributeRow();
            row.code = def.code();
            row.label = def.label();
            String raw = product.attributes != null ? product.attributes.get(def.code()) : null;
            String effective = raw != null ? raw : def.defaultValue();
            row.checked = Boolean.parseBoolean(effective);
            rows.add(row);
        }
        return rows;
    }

    /**
     * Builds the custom-attribute rows of the form from the administered
     * definitions and the product's current values (BO-02-03-32).
     *
     * @param product the edited product
     * @return the rendered rows, in code order
     */
    List<CustomAttrRow> customAttributeRows(Product product) {
        List<CustomAttrRow> rows = new ArrayList<>();
        for (ArticleAttributeDefinition def : ArticleAttributeDefinition.listAllOrdered()) {
            CustomAttrRow row = new CustomAttrRow();
            row.code = def.code;
            row.label = def.label;
            String raw = product.attributes != null ? product.attributes.get(def.code) : null;
            row.value = raw != null ? raw : "";
            rows.add(row);
        }
        return rows;
    }

    /**
     * Builds the read-only view of the article's full attribute map, sorted by
     * code (BO-02-03-29), so nothing an article carries is hidden.
     *
     * @param product the edited product, or null
     * @return the entries in code order; empty when the product is null or bears
     *         no attribute
     */
    List<AttrEntry> allAttributes(Product product) {
        List<AttrEntry> entries = new ArrayList<>();
        if (product == null || product.attributes == null) {
            return entries;
        }
        Map<String, String> sorted = new TreeMap<>(product.attributes);
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            AttrEntry row = new AttrEntry();
            row.code = entry.getKey();
            row.value = entry.getValue();
            entries.add(row);
        }
        return entries;
    }

    /**
     * Parses the product id posted with the form.
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
     * Parses the age restriction: a positive integer, or null when empty or
     * not a positive number (an unrestricted article).
     *
     * @param raw the raw value, or null
     * @return the age, or null
     */
    private Integer parseAge(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int age = Integer.parseInt(raw.trim());
            return age > 0 ? age : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses a posted decimal field (BO-02-03-33), tolerating a French comma,
     * and normalises it to the given scale. A blank or unparseable value clears
     * the field (null), following the read-screen doctrine that a bad value
     * narrows nothing rather than faulting the save.
     *
     * @param raw the raw value, or null
     * @param scale the decimal scale to enforce (2 for money, 3 for weight/volume)
     * @return the parsed amount at the given scale, or null when blank or invalid
     */
    private BigDecimal parseDecimal(String raw, int scale) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim().replace(",", ".")).setScale(scale, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Normalises a posted text field to null when blank.
     *
     * @param raw the raw value, or null
     * @return the trimmed value, or null when absent or blank
     */
    private String blankToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Builds the 303 redirect carrying the one-shot notice.
     *
     * @param notice the message to display
     * @param ok whether it reports a success
     * @return the redirect response
     */
    private Response redirect(String notice, boolean ok) {
        String target = "/admin/articles?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8);
        return Response.seeOther(URI.create(target)).build();
    }
}
