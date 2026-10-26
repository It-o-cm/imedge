package com.intermarche.edge.ui.admin;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.catalog.ArticleAttributeDefinition;
import com.intermarche.edge.domain.catalog.ArticleSelection;
import com.intermarche.edge.domain.catalog.EegProvider;
import com.intermarche.edge.domain.catalog.EgalimRegime;
import com.intermarche.edge.domain.catalog.MeasureUnit;
import com.intermarche.edge.domain.catalog.Price;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.ProductFamily;
import com.intermarche.edge.domain.catalog.ProductType;
import com.intermarche.edge.domain.catalog.VatRate;
import com.intermarche.edge.domain.catalog.attribute.ProductAttributeType;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.catalog.attribute.ProductAttributeCatalog;
import com.intermarche.edge.domain.catalog.attribute.ProductAttributeDef;
import com.intermarche.edge.domain.catalog.attribute.ProductAttributes;
import com.intermarche.edge.ui.journal.JournalPage;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
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
import jakarta.ws.rs.Produces;
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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
 * The EGAlim classification is SHOWN and never edited here, on the fiche and in
 * the list alike: the classification is integrated from the Gestion Commerciale
 * with the article, the ceiling is administered on {@code
 * /admin/egalim-regimes}, and the cap itself is arbitrated by the moteur de
 * valorisation. What this screen adds is the reading an operator cannot get
 * anywhere else — which category an article falls under, what the ceiling of
 * that category is today, and the two silent cases: an article classified
 * nowhere, and an article naming a regime the referential does not declare.
 * <p>
 * "Deleting" an article is a deactivation (BO-02-03-33): the {@code active}
 * flag is cleared, never a row removed, because sold ticket lines hold product
 * foreign keys — the same contract the referential pull honours.
 * <p>
 * Lot 20 (BO-02-03-03 to -44) completes the fiche: every basic datum is shown
 * — the nomenclature filing, the VAT code and the rate it applies, the
 * checkout mode, the unit of measure, the additional sale codes, the supplier,
 * the linked and complementary articles — and every one is editable, the EAN,
 * the name, the PLU, the type and the VAT code included, each datum subject to
 * the rights administered per profile on {@code /admin/article-field-rights}
 * (BO-02-03-33). An article never sold is DELETED, one already sold is
 * deactivated because its ticket lines hold it. The list exports its
 * perimeter (BO-02-03-31) and sends its prices to the electronic shelf labels
 * (BO-02-03-38); the fiche reads the price a label displays (BO-02-03-37).
 * <p>
 * Access reserved to ADMIN, like every parameter screen. POST&nbsp;→&nbsp;303&nbsp;→&nbsp;notice.
 */
@Path("/admin/articles")
public class AdminArticleResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminArticleResource.class);

    /**
     * The number of articles one page of the list shows.
     *
     * <p>A hundred, like the journal: enough that scrolling replaces clicking
     * for an ordinary search, few enough that a catalog of a hundred thousand
     * articles is never hydrated to render one screen.
     */
    static final int PAGE_SIZE = 100;

    /**
     * The attribute code under which the on-screen article-code flag is stored
     * (BO-02-03-45). A presentation attribute carried in the open
     * {@link Product#attributes} map, so it is distributed by the draw like any
     * other article datum without a dedicated column; the sale surface reads it
     * in its own lot (BO-10-02-35).
     */
    public static final String CODE_ON_SCREEN = "CODE_ARTICLE_ON_SCREEN";

    /**
     * The back-office parameters: the shop's article-code display, which
     * initialises the flag of an article that carries none (BO-10-02-34/35).
     */
    @Inject
    com.intermarche.edge.service.PosSettingsService posSettingsService;

    /** Which data of the fiche the signed-in account may change (BO-02-03-33). */
    @Inject
    ArticleFieldRights articleFieldRights;

    /** The signed-in account. */
    @Inject
    com.intermarche.edge.security.CurrentUser currentUser;

    /** The electronic shelf label connectors (BO-02-03-37/38). */
    @Inject
    EegGateway eegGateway;

    /** This node's point of sale, which owns the article selections (BO-02-03-31). */
    @Inject
    com.intermarche.edge.service.NodeEchelonService nodeEchelonService;

    /**
     * The catalog codes the fiche edits through a field of their own rather
     * than through the generic attribute rows.
     */
    static final Set<String> DEDICATED_CODES = Set.of(
            ProductAttributeCatalog.LINKED_ARTICLE_EAN, ProductAttributeCatalog.FORBIDDEN_SALE_MESSAGE,
            ProductAttributeCatalog.CHECKOUT_MODE, ProductAttributeCatalog.LINKED_ARTICLE_QUANTITY,
            ProductAttributeCatalog.COMPLEMENTARY_EANS, ProductAttributeCatalog.UNIT_CODE,
            ProductAttributeCatalog.VAT_NUMBER, ProductAttributeCatalog.SUPPLIER_CODE,
            ProductAttributeCatalog.SUPPLIER_NAME, ProductAttributeCatalog.SUPPLIER_ARTICLE_CODE,
            ProductAttributeCatalog.SUPPLIER_ARTICLE_NAME);

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
     * The VAT an article is rung at, as the fiche shows it (BO-02-03-27/28): the
     * regime's code letter, its rate, its label, whether it is an exemption, and
     * where it comes from — the article itself or its price.
     */
    public static class VatView {
        /** The regime's code letter, or null when no regime is named. */
        public String code;
        /** The rate, formatted in percent. */
        public String rate;
        /** The regime's label. */
        public String label;
        /** Whether the article is exempt of VAT. */
        public boolean exempt;
        /** Whether the regime is the article's own rather than its price's. */
        public boolean fromArticle;
    }

    /** One node of an article's nomenclature filing, top first (BO-02-03-14/28). */
    public static class PathNode {
        /** The level's label (Activité, Rayon…), or null when unnamed. */
        public String level;
        /** The node code. */
        public String code;
        /** The node description. */
        public String description;
    }

    /** One price an electronic shelf label displays (BO-02-03-37). */
    public static class EegPrice {
        /** The provider's label. */
        public String provider;
        /** The displayed price, or null when the provider gave none. */
        public BigDecimal price;
        /** Whether the displayed price differs from the referential's. */
        public boolean differs;
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
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE)
    @Transactional
    public TemplateInstance list(@Context UriInfo uriInfo,
                                 @QueryParam("page") @DefaultValue("1") int page,
                                 @QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list with uriInfo: " + uriInfo + ", page: " + page + ", notice: " + notice + ", noticeOk: " + noticeOk);
        MultivaluedMap<String, String> params = uriInfo.getQueryParameters();
        String selectionName = ArticleSearchCriteria.blankToNull(params.getFirst("selection"));
        ArticleSelection selection = selectionName != null
                ? ArticleSelection.findForPdv(selectionName, currentPdv()) : null;
        ArticleSearchCriteria criteria = selection != null
                ? ArticleSearchCriteria.fromQueryString(selection.criteria)
                : ArticleSearchCriteria.fromParams(params);
        PanacheQuery<Product> query = criteria.find();
        long total = query.count();
        int number = clampPage(page, total);
        JournalPage<Product> window = new JournalPage<>(
                query.page(number - 1, PAGE_SIZE).list(), number, PAGE_SIZE, total);
        LOGGER.info("Exiting method list");
        return adminArticles.data("page", window)
                .data("egalimCaps", egalimCaps())
                .data("baseQuery", baseQuery(criteria, selection))
                .data("criteria", criteria)
                .data("selectedSelection", selection != null ? selection.name : null)
                .data("types", ProductType.values())
                .data("attributeDefs", boolDefs())
                .data("vatRates", VatRate.listAllOrdered())
                .data("familyNodes", familyNodes())
                .data("criteriaQuery", criteria.toQueryString())
                .data("eegEnabled", posSettingsService != null && posSettingsService.eegEnabled())
                .data("selections", ArticleSelection.listForPdv(currentPdv()))
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
     * The BOOLEAN catalog entries, the ones a search can require as true and the
     * fiche renders as checkboxes.
     *
     * @return the boolean definitions, in catalog order
     */
    static List<ProductAttributeDef> boolDefs() {
        List<ProductAttributeDef> defs = new ArrayList<>();
        for (ProductAttributeDef def : ProductAttributeCatalog.CATALOG) {
            if (def.type() == ProductAttributeType.BOOL) {
                defs.add(def);
            }
        }
        return defs;
    }

    /**
     * The nodes of the nomenclatures, offered by the family criterion
     * (BO-02-03-30/31).
     *
     * @return the classified nodes, by code
     */
    static List<ProductFamily> familyNodes() {
        return ProductFamily.list("nomenclature is not null order by code");
    }

    /**
     * Renders every EGAlim regime of the referential as the one line the list
     * prints beside an article: its ceiling, or the words saying it has none.
     *
     * <p>Read ONCE per page and not once per row: the referential holds a
     * handful of categories against a hundred thousand articles, and a lookup
     * per line would turn a page of a hundred articles into a hundred queries
     * for three answers.
     *
     * <p>A code the map does not answer is a classification the referential
     * does not declare — the list says so rather than printing an empty cell,
     * because an article pointing at a regime nobody administers is exactly the
     * case an operator must see: the valuation engine will not cap it.
     *
     * @return the regime code → ceiling text map, empty when nothing is administered
     */
    private Map<String, String> egalimCaps() {
        LOGGER.info("Entering method egalimCaps");
        Map<String, String> caps = new TreeMap<>();
        for (EgalimRegime regime : EgalimRegime.listAllOrdered()) {
            caps.put(regime.code, regime.isCapped() ? regime.getCapFormatted() + " %" : "non plafonné");
        }
        LOGGER.info("Exiting method egalimCaps");
        return caps;
    }

    /**
     * Clamps a requested page number into the pages the result actually has, so
     * a stale link or a hand-typed number lands on the last page rather than on
     * an empty list.
     *
     * @param requested the 1-based page number asked for
     * @param total the number of matching articles
     * @return the page number to read, between 1 and the page count
     */
    private int clampPage(int requested, long total) {
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, JournalPage.pageCount(total, PAGE_SIZE));
    }

    /**
     * Renders what a page link has to carry over: the search perimeter, so that
     * turning a page keeps the list the operator was reading.
     *
     * <p>A selection wins over the loose criteria, exactly as it does when the
     * page is built: an applied selection IS the perimeter, and re-sending the
     * criteria beside it would let the two drift apart on the next click.
     *
     * @param criteria the parsed criteria
     * @param selection the applied saved selection, or null
     * @return the query string, terminated by an ampersand when non-empty so a
     *         page number can be appended to it
     */
    private String baseQuery(ArticleSearchCriteria criteria, ArticleSelection selection) {
        String query = selection != null
                ? "selection=" + URLEncoder.encode(selection.name, StandardCharsets.UTF_8)
                : criteria.toQueryString();
        return query.isEmpty() ? "" : query + "&";
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
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE)
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
        return details(adminArticle.data("product", product), product, currentPrice)
                .data("egalimRegime", product != null ? EgalimRegime.findByCode(product.egalimRegime) : null)
                .data("attributes", product != null ? attributeRows(product) : List.of())
                .data("customAttributes", product != null ? customAttributeRows(product) : List.of())
                .data("allAttributes", allAttributes(product))
                .data("linkedArticleEan", ProductAttributes.raw(product, ProductAttributeCatalog.LINKED_ARTICLE_EAN))
                .data("forbiddenSaleMessage", ProductAttributes.raw(product, ProductAttributeCatalog.FORBIDDEN_SALE_MESSAGE))
                .data("codeOnScreen", ProductAttributes.codeShown(product, codeOnScreenDefault()))
                .data("currentPrice", currentPrice)
                .data("offers", offers)
                .data("history", history);
    }

    /**
     * Adds to the fiche what lot 20 shows of an article (BO-02-03-03 to -44):
     * its sale codes, its checkout mode, its unit, its VAT, its nomenclature
     * filing, its supplier, its links, its other attributes, the data the
     * signed-in account may change and, when the flow is active, the prices the
     * electronic shelf labels display.
     *
     * @param page the fiche being built
     * @param product the article, or null when unknown
     * @param currentPrice its current price, or null
     * @return the fiche
     */
    TemplateInstance details(TemplateInstance page, Product product, Price currentPrice) {
        if (product == null) {
            return page.data("editable", editableNames()).data("eegEnabled", false);
        }
        boolean eeg = posSettingsService != null && posSettingsService.eegEnabled();
        return page.data("saleCodes", String.join("\n", new TreeSet<>(product.saleCodes)))
                .data("checkoutModes", ProductAttributeCatalog.CHECKOUT_MODES)
                .data("checkoutMode", ProductAttributes.raw(product, ProductAttributeCatalog.CHECKOUT_MODE))
                .data("units", MeasureUnit.listActive())
                .data("unitCode", ProductAttributes.raw(product, ProductAttributeCatalog.UNIT_CODE))
                .data("vatRates", VatRate.listAllOrdered())
                .data("vatNumber", ProductAttributes.raw(product, ProductAttributeCatalog.VAT_NUMBER))
                .data("vat", vatView(product, currentPrice))
                .data("nomenclaturePath", nomenclaturePath(product))
                .data("linkedArticleQuantity", ownValue(product, ProductAttributeCatalog.LINKED_ARTICLE_QUANTITY))
                .data("complementaryEans", ProductAttributes.raw(product, ProductAttributeCatalog.COMPLEMENTARY_EANS))
                .data("supplierCode", ProductAttributes.raw(product, ProductAttributeCatalog.SUPPLIER_CODE))
                .data("supplierName", ProductAttributes.raw(product, ProductAttributeCatalog.SUPPLIER_NAME))
                .data("supplierArticleCode", ProductAttributes.raw(product, ProductAttributeCatalog.SUPPLIER_ARTICLE_CODE))
                .data("supplierArticleName", ProductAttributes.raw(product, ProductAttributeCatalog.SUPPLIER_ARTICLE_NAME))
                .data("textAttributes", textAttributeRows(product))
                .data("types", ProductType.values())
                .data("editable", editableNames())
                .data("eegEnabled", eeg)
                .data("eegPrices", eeg ? eegPrices(product) : List.of());
    }

    /**
     * The value an article carries for a code, never the catalog default.
     *
     * @param product the article, or null
     * @param code the attribute code
     * @return the own value, empty when it carries none
     */
    static String ownValue(Product product, String code) {
        String value = product == null || product.attributes == null ? null : product.attributes.get(code);
        return value == null ? "" : value;
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
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE, option = CrudOption.UPDATE)
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
        Set<ArticleField> editable = editable();
        String refusal = refusal(product, form, editable);
        if (refusal != null) {
            LOGGER.info("Exiting method save");
            return redirect(refusal, false);
        }
        if (editable.contains(ArticleField.LINKS)) {
            String linkedEan = blankToNull(form.getFirst(ProductAttributeCatalog.LINKED_ARTICLE_EAN));
            if (linkedEan == null) product.attributes.remove(ProductAttributeCatalog.LINKED_ARTICLE_EAN);
            else product.attributes.put(ProductAttributeCatalog.LINKED_ARTICLE_EAN, linkedEan);
            putPosted(product, form, ProductAttributeCatalog.LINKED_ARTICLE_QUANTITY);
            putPosted(product, form, ProductAttributeCatalog.COMPLEMENTARY_EANS);
        }
        if (editable.contains(ArticleField.MESSAGES)) {
            // BO-10-07-20: the article's own forbidden-sale / recall message, typed on
            // the fiche; blank removes it and the shop's default answers at the till.
            String forbiddenMessage = blankToNull(form.getFirst(ProductAttributeCatalog.FORBIDDEN_SALE_MESSAGE));
            if (forbiddenMessage == null) product.attributes.remove(ProductAttributeCatalog.FORBIDDEN_SALE_MESSAGE);
            else product.attributes.put(ProductAttributeCatalog.FORBIDDEN_SALE_MESSAGE, forbiddenMessage);
            // BO-03-08-09: the sentence the cashier reads when this article is
            // rung. Vide = l'article ne dit rien.
            product.infoMessage = blankToNull(form.getFirst("infoMessage"));
        }
        saveIdentity(product, form, editable);
        saveCharacteristics(product, form, editable);
        saveClassification(product, form, editable);
        if (editable.contains(ArticleField.ATTRIBUTES)) {
            saveAttributes(product, form);
        }
        if (editable.contains(ArticleField.PRESENTATION)) {
            product.attributes.put(CODE_ON_SCREEN,
                    form.getFirst("codeOnScreen") != null ? "true" : "false");
        }
        LOGGER.info("Exiting method save");
        return redirect("Fiche article « " + product.ean + " » enregistrée.", true);
    }

    /**
     * The data of the fiche the signed-in account may change (BO-02-03-33):
     * all of them on a resource built without the rights.
     *
     * @return the changeable data
     */
    Set<ArticleField> editable() {
        if (articleFieldRights == null || currentUser == null) {
            return EnumSet.allOf(ArticleField.class);
        }
        return articleFieldRights.editable(currentUser.getName());
    }

    /**
     * The names of the changeable data, for the template to disable the others.
     *
     * @return the names
     */
    Set<String> editableNames() {
        Set<String> names = new TreeSet<>();
        for (ArticleField field : editable()) {
            names.add(field.name());
        }
        return names;
    }

    /**
     * Checks a posted fiche before anything is written, so a refused save leaves
     * the article exactly as it was.
     *
     * @param product the article
     * @param form the posted form
     * @param editable the data the account may change
     * @return the refusal, or null when the fiche may be saved
     */
    String refusal(Product product, MultivaluedMap<String, String> form, Set<ArticleField> editable) {
        if (editable.contains(ArticleField.LINKS)
                && invalidLinkedArticle(product.ean, blankToNull(form.getFirst(ProductAttributeCatalog.LINKED_ARTICLE_EAN)))) {
            return "Article lié introuvable, inactif ou formant une boucle.";
        }
        String ean = blankToNull(form.getFirst("ean"));
        if (editable.contains(ArticleField.EAN) && ean != null && !ean.equals(product.ean)
                && (Product.findByEan(ean) != null || Product.findActiveBySaleCode(ean) != null)) {
            return "Le code EAN " + ean + " est déjà porté par un autre article.";
        }
        String plu = blankToNull(form.getFirst("plu"));
        Product pluHolder = editable.contains(ArticleField.PLU) && plu != null ? Product.findByPlu(plu) : null;
        if (pluHolder != null && pluHolder != product) {
            return "Le PLU " + plu + " est déjà porté par un autre article.";
        }
        String clash = editable.contains(ArticleField.SALE_CODES) && form.containsKey("saleCodes")
                ? saleCodeClash(product, saleCodes(form.getFirst("saleCodes"), product.ean)) : null;
        if (clash != null) {
            return "Le code de vente " + clash + " est déjà porté par un autre article.";
        }
        String vatNumber = blankToNull(form.getFirst(ProductAttributeCatalog.VAT_NUMBER));
        if (editable.contains(ArticleField.VAT) && vatNumber != null
                && VatRate.findByNumber(ArticleSearchCriteria.parseVat(vatNumber)) == null) {
            return "Aucun régime de TVA ne porte le numéro " + vatNumber + ".";
        }
        String node = blankToNull(form.getFirst("nomenclatureNode"));
        ProductFamily family = editable.contains(ArticleField.NOMENCLATURE) && node != null
                ? ProductFamily.findByCode(node) : null;
        if (node != null && editable.contains(ArticleField.NOMENCLATURE) && (family == null || family.nomenclature == null)) {
            return "Aucun nœud de nomenclature ne porte le code " + node + ".";
        }
        return null;
    }

    /**
     * Saves the identity of the article: EAN, names, codes (BO-02-03-03, -04,
     * -33).
     *
     * @param product the article
     * @param form the posted form
     * @param editable the data the account may change
     */
    private void saveIdentity(Product product, MultivaluedMap<String, String> form, Set<ArticleField> editable) {
        String ean = blankToNull(form.getFirst("ean"));
        if (editable.contains(ArticleField.EAN) && ean != null) {
            product.ean = ean;
        }
        String name = blankToNull(form.getFirst("name"));
        if (editable.contains(ArticleField.NAME) && name != null) {
            product.name = name;
        }
        if (editable.contains(ArticleField.CHECKOUT_LABEL)) {
            product.checkoutLabel = blankToNull(form.getFirst("checkoutLabel"));
        }
        if (editable.contains(ArticleField.INTERNAL_CODE)) {
            product.internalCode = blankToNull(form.getFirst("internalCode"));
        }
        if (editable.contains(ArticleField.SALE_CODES) && form.containsKey("saleCodes")) {
            product.saleCodes = saleCodes(form.getFirst("saleCodes"), product.ean);
        }
        if (editable.contains(ArticleField.BRAND)) {
            product.description = blankToNull(form.getFirst("description"));
            product.brand = blankToNull(form.getFirst("brand"));
        }
    }

    /**
     * Saves the characteristics: status, PLU, type, unit, measures, gift card
     * (BO-02-03-17, -24, -33).
     *
     * @param product the article
     * @param form the posted form
     * @param editable the data the account may change
     */
    private void saveCharacteristics(Product product, MultivaluedMap<String, String> form, Set<ArticleField> editable) {
        if (editable.contains(ArticleField.STATUS)) {
            product.active = form.getFirst("active") != null;
            product.forbiddenToSale = form.getFirst("forbiddenToSale") != null;
            product.ageRestriction = parseAge(form.getFirst("ageRestriction"));
        }
        if (editable.contains(ArticleField.PLU) && form.containsKey("plu")) {
            product.plu = blankToNull(form.getFirst("plu"));
        }
        ProductType type = ArticleSearchCriteria.parseType(form.getFirst("productType"));
        if (editable.contains(ArticleField.TYPE) && type != null) {
            product.productType = type;
        }
        if (editable.contains(ArticleField.UNIT)) {
            // BO-02-03-17: a declared unit gives its symbol to the article; without
            // one the free label stays, as it was.
            MeasureUnit unit = MeasureUnit.findByCode(form.getFirst(ProductAttributeCatalog.UNIT_CODE));
            if (unit != null) {
                product.attributes.put(ProductAttributeCatalog.UNIT_CODE, unit.code);
                product.unitName = unit.symbol;
            } else {
                product.attributes.remove(ProductAttributeCatalog.UNIT_CODE);
                product.unitName = blankToNull(form.getFirst("unitName"));
            }
        }
        if (editable.contains(ArticleField.MEASURES)) {
            // BO-02-03-24/28: the variable-weight (bulk) marker travels with the
            // measures it governs.
            product.referenceWeight = parseDecimal(form.getFirst("referenceWeight"), 3);
            product.referenceVolume = parseDecimal(form.getFirst("referenceVolume"), 3);
            product.variableWeight = form.getFirst("variableWeight") != null;
        }
        if (editable.contains(ArticleField.GIFT_CARD)) {
            product.giftCardAmount = parseDecimal(form.getFirst("giftCardAmount"), 2);
        }
    }

    /**
     * Saves the classification: checkout mode, VAT code, nomenclature filing
     * and supplier (BO-02-03-05, -14, -21 to -24, -27, -30).
     *
     * @param product the article
     * @param form the posted form
     * @param editable the data the account may change
     */
    private void saveClassification(Product product, MultivaluedMap<String, String> form, Set<ArticleField> editable) {
        if (editable.contains(ArticleField.CHECKOUT_MODE)) {
            putPosted(product, form, ProductAttributeCatalog.CHECKOUT_MODE);
        }
        if (editable.contains(ArticleField.VAT)) {
            putPosted(product, form, ProductAttributeCatalog.VAT_NUMBER);
        }
        String node = blankToNull(form.getFirst("nomenclatureNode"));
        if (editable.contains(ArticleField.NOMENCLATURE) && node != null) {
            ProductFamily.fileUnderNomenclature(product, node);
        }
        if (editable.contains(ArticleField.SUPPLIER)) {
            putPosted(product, form, ProductAttributeCatalog.SUPPLIER_CODE);
            putPosted(product, form, ProductAttributeCatalog.SUPPLIER_NAME);
            putPosted(product, form, ProductAttributeCatalog.SUPPLIER_ARTICLE_CODE);
            putPosted(product, form, ProductAttributeCatalog.SUPPLIER_ARTICLE_NAME);
        }
    }

    /**
     * Saves the attributes: the boolean catalog entries, the valued ones and the
     * administered custom ones (BO-02-03-18, -29, -32).
     *
     * @param product the article
     * @param form the posted form
     */
    private void saveAttributes(Product product, MultivaluedMap<String, String> form) {
        for (ProductAttributeDef def : ProductAttributeCatalog.CATALOG) {
            if (def.type() == ProductAttributeType.BOOL) {
                product.attributes.put(def.code(), form.getFirst(def.code()) != null ? "true" : "false");
            } else if (!DEDICATED_CODES.contains(def.code())) {
                putPosted(product, form, def.code());
            }
        }
        for (ArticleAttributeDefinition def : ArticleAttributeDefinition.listAllOrdered()) {
            String value = blankToNull(form.getFirst(def.code));
            if (value != null) {
                product.attributes.put(def.code, value);
            } else {
                product.attributes.remove(def.code);
            }
        }
    }

    /**
     * Poses an attribute from the posted field of the same name when the form
     * carries it: a blank value withdraws the attribute, an absent field leaves
     * it alone.
     *
     * @param product the article
     * @param form the posted form
     * @param code the attribute code, which is also the field name
     */
    private void putPosted(Product product, MultivaluedMap<String, String> form, String code) {
        if (!form.containsKey(code)) {
            return;
        }
        String value = blankToNull(form.getFirst(code));
        if (value == null) {
            product.attributes.remove(code);
        } else {
            product.attributes.put(code, value);
        }
    }

    /**
     * Reads the additional sale codes typed on the fiche: one per line, or
     * separated by commas or semicolons; blanks and the article's own EAN are
     * dropped (BO-02-03-03).
     *
     * @param raw the posted text, or null
     * @param ean the article's EAN
     * @return the codes, sorted
     */
    static Set<String> saleCodes(String raw, String ean) {
        Set<String> codes = new TreeSet<>();
        for (String entry : raw == null ? new String[0] : raw.split("[\\s,;]+")) {
            if (!entry.isEmpty() && !entry.equals(ean)) {
                codes.add(entry);
            }
        }
        return codes;
    }

    /**
     * The first sale code another article already answers to, by its EAN or by
     * one of its own sale codes.
     *
     * @param product the article being saved
     * @param codes the codes it would carry
     * @return the clashing code, or null when none clashes
     */
    String saleCodeClash(Product product, Set<String> codes) {
        for (String code : codes) {
            Product byEan = Product.findByEan(code);
            Product bySaleCode = Product.find("select p from Product p join p.saleCodes c where c = ?1", code)
                    .firstResult();
            if (byEan != null && byEan != product || bySaleCode != null && bySaleCode != product) {
                return code;
            }
        }
        return null;
    }

    /**
     * The flag an article without one shows on its fiche: the shop's
     * article-code display (BO-10-02-34/35), off on a resource built without
     * its parameters.
     *
     * @return true when the shop shows the article codes
     */
    boolean codeOnScreenDefault() {
        return posSettingsService != null && posSettingsService.showEan();
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
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response bulkCodeOnScreen(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method bulkCodeOnScreen with form: " + form);
        boolean value = form.getFirst("value") != null;
        ArticleSearchCriteria criteria = perimeter(form);
        int count = 0;
        // The whole perimeter and not the page on screen: the bar says "on the
        // N filtered articles", and N is the total, not what fits on one page.
        for (Product product : criteria.find().list()) {
            product.attributes.put(CODE_ON_SCREEN, value ? "true" : "false");
            count++;
        }
        LOGGER.info("Exiting method bulkCodeOnScreen");
        return redirect("Code article à l'écran " + (value ? "activé" : "désactivé")
                + " sur " + count + " article(s).", true);
    }

    /**
     * Rejects missing/inactive targets and cycles before any article field is modified (LC-02-03-04).
     * @param sourceEan article being edited
     * @param linkedEan requested companion EAN, null to disable the link
     * @return true when the requested chain cannot be registered safely
     */
    boolean invalidLinkedArticle(String sourceEan, String linkedEan) {
        java.util.Set<String> visited = new java.util.HashSet<>();
        visited.add(sourceEan);
        String next = linkedEan;
        while (next != null) {
            if (!visited.add(next)) return true;
            Product target = Product.find("ean = ?1 and active = true", next).firstResult();
            if (target == null) return true;
            next = blankToNull(ProductAttributes.raw(target, ProductAttributeCatalog.LINKED_ARTICLE_EAN));
        }
        return false;
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
            // LC-02-03-04, BO-10-07-20: only the boolean attributes are
            // checkboxes; the valued ones have a text field (BO-02-03-18).
            if (def.type() != ProductAttributeType.BOOL) continue;
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
     * Builds the valued attribute rows of the fiche: the catalog entries that are
     * not booleans and have no field of their own (BO-02-03-18/29).
     *
     * @param product the edited product
     * @return the rows, in catalog order
     */
    List<CustomAttrRow> textAttributeRows(Product product) {
        List<CustomAttrRow> rows = new ArrayList<>();
        for (ProductAttributeDef def : ProductAttributeCatalog.CATALOG) {
            if (def.type() == ProductAttributeType.BOOL || DEDICATED_CODES.contains(def.code())) {
                continue;
            }
            CustomAttrRow row = new CustomAttrRow();
            row.code = def.code();
            row.label = def.label();
            row.value = ownValue(product, def.code());
            rows.add(row);
        }
        return rows;
    }

    /**
     * Describes the VAT a line of the article is rung at (BO-02-03-27/28): the
     * article's own regime when it names one, the price's otherwise, and the
     * exemption whoever states it.
     *
     * @param product the article
     * @param currentPrice its current price, or null
     * @return the view
     */
    static VatView vatView(Product product, Price currentPrice) {
        Integer own = ProductAttributes.vatNumber(product);
        VatRate regime = own == null ? null : VatRate.findByNumber(own);
        VatView view = new VatView();
        view.fromArticle = regime != null;
        if (regime == null && currentPrice != null) {
            regime = currentPrice.vat;
        }
        view.code = regime == null ? null : regime.code();
        view.rate = regime == null ? "" : regime.getRateFormatted();
        view.label = regime == null ? "" : regime.getLabel();
        view.exempt = ProductAttributes.vatExempt(product) || regime != null && regime.exempt;
        return view;
    }

    /**
     * The article's filing in the nomenclature, top level first (BO-02-03-14,
     * -28): the deepest node it is filed under and every node above it.
     *
     * @param product the article, or null
     * @return the path, empty when the article is filed nowhere
     */
    static List<PathNode> nomenclaturePath(Product product) {
        List<PathNode> path = new ArrayList<>();
        ProductFamily node = ProductFamily.findDirectFamily(product);
        int guard = 0;
        while (node != null && guard++ < 16) {
            PathNode step = new PathNode();
            step.code = node.code;
            step.description = node.description;
            com.intermarche.edge.domain.catalog.NomenclatureLevel level = node.nomenclature == null
                    || node.level == null ? null : node.nomenclature.levelAt(node.level);
            step.level = level == null ? null : level.label;
            path.add(0, step);
            String parent = node.parentCode();
            node = parent == null ? null : ProductFamily.findByCode(parent);
        }
        return path;
    }

    /**
     * Reads, from every active provider, the price its labels display for the
     * article right now (BO-02-03-37).
     *
     * @param product the article
     * @return one entry per active provider
     */
    List<EegPrice> eegPrices(Product product) {
        Price current = Price.findCurrentPrice(product.id);
        List<EegPrice> prices = new ArrayList<>();
        for (EegProvider provider : EegProvider.listActive()) {
            EegPrice price = new EegPrice();
            price.provider = provider.label;
            price.price = eegGateway.readPrice(provider, product.ean);
            price.differs = price.price != null && current != null && current.priceIncludingTax != null
                    && price.price.compareTo(current.priceIncludingTax) != 0;
            prices.add(price);
        }
        return prices;
    }

    /**
     * The perimeter a list gesture works on: an applied selection, the canonical
     * query string of the list, or the criteria posted field by field.
     *
     * @param form the posted form
     * @return the criteria
     */
    ArticleSearchCriteria perimeter(MultivaluedMap<String, String> form) {
        String name = blankToNull(form.getFirst("selection"));
        ArticleSelection selection = name == null ? null : ArticleSelection.findForPdv(name, currentPdv());
        if (selection != null) {
            return ArticleSearchCriteria.fromQueryString(selection.criteria);
        }
        if (form.containsKey("query")) {
            return ArticleSearchCriteria.fromQueryString(form.getFirst("query"));
        }
        return ArticleSearchCriteria.fromParams(form);
    }

    /**
     * Deletes an article (BO-02-03-33): physically when it was never sold, its
     * prices and its family memberships with it; an article some ticket line
     * holds is deactivated instead, because the line must keep naming what was
     * sold.
     *
     * @param form the posted form (id)
     * @return a 303 redirect to the list with the notice
     */
    @POST
    @Path("/delete")
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE, option = CrudOption.DELETE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response delete(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method delete with form: " + form);
        Long id = parseId(form.getFirst("id"));
        Product product = id != null ? Product.<Product>findById(id) : null;
        if (product == null) {
            LOGGER.info("Exiting method delete");
            return redirect("Article introuvable.", false);
        }
        if (!editable().contains(ArticleField.DELETE)) {
            LOGGER.info("Exiting method delete");
            return redirect("Votre profil ne permet pas de supprimer un article.", false);
        }
        if (TicketLine.count("product.id", product.id) > 0) {
            product.active = false;
            LOGGER.info("Exiting method delete");
            return redirect("Article « " + product.ean + " » déjà vendu : désactivé et conservé pour ses tickets.", true);
        }
        for (ProductFamily family : ProductFamily.<ProductFamily>find(
                "select pf from ProductFamily pf join pf.products p where p.id = ?1", product.id).list()) {
            family.products.remove(product);
        }
        Price.delete("product.id", product.id);
        product.delete();
        LOGGER.info("Exiting method delete");
        return redirect("Article « " + product.ean + " » supprimé.", true);
    }

    /**
     * Exports the articles of a perimeter as CSV (BO-02-03-31): the list on
     * screen, or an applied selection, reused as the scope of an export.
     *
     * @param uriInfo the request carrying the criteria or the selection
     * @return the CSV attachment
     */
    @GET
    @Path("/export")
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE)
    @Produces("text/csv")
    @Transactional
    public Response export(@Context UriInfo uriInfo) {
        LOGGER.info("Entering method export");
        StringBuilder csv = new StringBuilder("EAN;LIBELLE;LIBELLE_ENCAISSEMENT;CODE_INTERNE;PLU;MARQUE;PRIX_TTC;TVA;ACTIF\n");
        for (Product product : perimeter(uriInfo.getQueryParameters()).find().list()) {
            Price price = Price.findCurrentPrice(product.id);
            csv.append(String.join(";", cell(product.ean), cell(product.name), cell(product.checkoutLabel),
                    cell(product.internalCode), cell(product.plu), cell(product.brand),
                    price == null || price.priceIncludingTax == null ? "" : price.priceIncludingTax.toPlainString(),
                    price == null || price.vatRate() == null ? "" : price.vatRate().toPlainString(),
                    String.valueOf(product.active))).append('\n');
        }
        LOGGER.info("Exiting method export");
        return Response.ok(csv.toString())
                .header("Content-Disposition", "attachment; filename=\"articles.csv\"").build();
    }

    /**
     * Renders one CSV cell, quoted when it carries the separator or a quote.
     *
     * @param value the value, or null
     * @return the cell
     */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(";") || value.contains("\"")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Sends the current prices of a perimeter to every active electronic shelf
     * label provider (BO-02-03-38), when the flow is active.
     *
     * @param form the posted form carrying the perimeter
     * @return a 303 redirect to the list with the notice
     */
    @POST
    @Path("/eeg-push")
    @Authenticated
    @FeatureAccess(value = Feature.ARTICLE, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response eegPush(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method eegPush with form: " + form);
        if (posSettingsService == null || !posSettingsService.eegEnabled()) {
            LOGGER.info("Exiting method eegPush");
            return redirect("Le flux étiquettes électroniques n'est pas activé.", false);
        }
        List<EegProvider> providers = EegProvider.listActive();
        if (providers.isEmpty()) {
            LOGGER.info("Exiting method eegPush");
            return redirect("Aucun fournisseur d'étiquettes électroniques actif.", false);
        }
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        for (Product product : perimeter(form).find().list()) {
            Price price = Price.findCurrentPrice(product.id);
            if (product.active && price != null && price.priceIncludingTax != null) {
                prices.put(product.ean, price.priceIncludingTax.setScale(2, RoundingMode.HALF_UP));
            }
        }
        int reached = 0;
        for (EegProvider provider : providers) {
            if (eegGateway.push(provider, prices)) {
                reached++;
            }
        }
        LOGGER.info("Exiting method eegPush");
        return redirect("Prix de " + prices.size() + " article(s) envoyés à " + reached + " fournisseur(s) d'EEG sur "
                + providers.size() + ".", reached == providers.size());
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
