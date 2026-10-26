package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.catalog.Price;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.ProductFamily;
import com.intermarche.edge.domain.catalog.ProductType;
import com.intermarche.edge.domain.util.DateTimeProvider;
import com.intermarche.edge.domain.catalog.attribute.ProductAttributeCatalog;
import com.intermarche.edge.domain.catalog.attribute.ProductAttributes;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The parsed multi-criteria form of the article search (BO-02-03-30) and the
 * saved perimeter of an {@code ArticleSelection} (BO-02-03-31) — one engine for
 * both, so the fiche list and a saved selection can never diverge.
 * <p>
 * A pure value object over article-intrinsic criteria: free text (matched
 * against the EAN, the commercial name and the internal code), the product
 * type, the active/inactive status, the forbidden-to-sale flag and any of the
 * well-known register attributes. Every field is optional and independent — an
 * absent field constrains nothing, so the criteria ANDs whatever is present and
 * an empty criteria matches every article. Following the read-screen doctrine
 * of {@code JournalCriteria}, a value that fails to parse is treated as absent
 * rather than raising: a bad filter narrows nothing, it does not fault.
 * <p>
 * <p>
 * BO-02-03-30/31 widened it to the criteria the chef de rayon asks for: the PLU,
 * the internal code, the description, the checkout label (libellé TPV), the
 * supplier and the supplier's article (code or name), the brand, one or several
 * families or rayons of the nomenclature (a node selects everything filed under
 * it), a range of the CURRENT sale price and the VAT regime. The free text also
 * reaches the additional sale codes (BO-02-03-03).
 * <p>
 * Two readings of the SAME criteria, kept side by side on purpose. {@link
 * #matches(Product)} is the reference: a pure predicate over one article, which
 * is what a test reads and what settles what a criterion MEANS. {@link #find()}
 * says the same thing in JPQL, so a catalog of a hundred thousand articles is
 * filtered by the database instead of being hydrated into objects and sieved in
 * Java. The screen uses the query; the predicate remains the definition the
 * query has to agree with, which is why every clause below names the arm of
 * {@code matches} it translates.
 */
public class ArticleSearchCriteria {

    /** The active/inactive status filter. */
    public enum Status {
        /** No status constraint. */
        ALL,
        /** Only active articles. */
        ACTIVE,
        /** Only inactive (deactivated) articles. */
        INACTIVE
    }

    /** Free text matched against the EAN, name and internal code, or null. */
    public String text;

    /** The required product type, or null for any type. */
    public ProductType type;

    /** The active/inactive filter; never null (defaults to {@link Status#ALL}). */
    public Status status = Status.ALL;

    /**
     * The required forbidden-to-sale value: {@code true} keeps only forbidden
     * articles, {@code false} only sellable ones, null keeps both.
     */
    public Boolean forbidden;

    /**
     * The inclusive lower bound of the EAN range (BO-02-03-30), or null for no
     * lower bound. EANs are compared as strings — the natural order of a
     * fixed-alphabet numeric code.
     */
    public String eanFrom;

    /**
     * The inclusive upper bound of the EAN range (BO-02-03-30), or null for no
     * upper bound.
     */
    public String eanTo;

    /**
     * The well-known attribute codes an article must carry as true, never null.
     * Only codes the register acts upon (declared in
     * {@link ProductAttributeCatalog}) are retained.
     */
    public Set<String> attributes = new LinkedHashSet<>();

    /** A fragment of the PLU, or null (BO-02-03-30). */
    public String plu;

    /** A fragment of the internal code, or null (BO-02-03-30). */
    public String internalCode;

    /** A fragment of the description, or null (BO-02-03-30). */
    public String description;

    /** A fragment of the checkout label (libellé TPV), or null (BO-02-03-30). */
    public String checkoutLabel;

    /** A fragment of the supplier's code or name, or null (BO-02-03-30). */
    public String supplier;

    /** A fragment of the supplier's article code or name, or null (BO-02-03-30). */
    public String supplierArticle;

    /** A fragment of the brand, or null (BO-02-03-30). */
    public String brand;

    /**
     * The codes of the nomenclature nodes (families, rayons, sous-familles) an
     * article must be filed under, directly or below, never null (BO-02-03-30/31).
     */
    public Set<String> families = new LinkedHashSet<>();

    /** The inclusive lower bound of the current sale price, or null (BO-02-03-30/31). */
    public BigDecimal priceMin;

    /** The inclusive upper bound of the current sale price, or null (BO-02-03-30/31). */
    public BigDecimal priceMax;

    /** The number of the VAT regime the article falls under, or null (BO-02-03-31). */
    public Integer vat;

    /**
     * Default constructor for an empty (match-everything) criteria.
     */
    public ArticleSearchCriteria() {
    }

    /**
     * Tells whether a product satisfies every present criterion.
     *
     * @param product the candidate product, or null
     * @return true when the product matches all present criteria; false for a
     *         null product
     */
    public boolean matches(Product product) {
        if (product == null) {
            return false;
        }
        if (text != null && !matchesText(product, text)) {
            return false;
        }
        if (type != null && product.productType != type) {
            return false;
        }
        if (status == Status.ACTIVE && !product.active) {
            return false;
        }
        if (status == Status.INACTIVE && product.active) {
            return false;
        }
        if (forbidden != null && product.forbiddenToSale != forbidden) {
            return false;
        }
        if (eanFrom != null && product.ean.compareTo(eanFrom) < 0) {
            return false;
        }
        if (eanTo != null && product.ean.compareTo(eanTo) > 0) {
            return false;
        }
        for (String code : attributes) {
            if (!ProductAttributes.flag(product, code)) {
                return false;
            }
        }
        return matchesFields(product) && matchesSupplier(product) && matchesCatalog(product);
    }

    /**
     * The fragment criteria on the article's own fields (BO-02-03-30).
     *
     * @param product the candidate product
     * @return true when every present fragment is found
     */
    private boolean matchesFields(Product product) {
        return (plu == null || contains(product.plu, plu.toLowerCase()))
                && (internalCode == null || contains(product.internalCode, internalCode.toLowerCase()))
                && (description == null || contains(product.description, description.toLowerCase()))
                && (checkoutLabel == null || contains(product.checkoutLabel, checkoutLabel.toLowerCase()))
                && (brand == null || contains(product.brand, brand.toLowerCase()));
    }

    /**
     * The supplier criteria, read from the article's supplier attributes
     * (BO-02-03-30).
     *
     * @param product the candidate product
     * @return true when every present supplier fragment is found
     */
    private boolean matchesSupplier(Product product) {
        return (supplier == null
                || containsAttribute(product, supplier, ProductAttributeCatalog.SUPPLIER_CODE, ProductAttributeCatalog.SUPPLIER_NAME))
                && (supplierArticle == null
                || containsAttribute(product, supplierArticle, ProductAttributeCatalog.SUPPLIER_ARTICLE_CODE,
                        ProductAttributeCatalog.SUPPLIER_ARTICLE_NAME));
    }

    /**
     * Tells whether one of two attributes of the article contains a fragment.
     *
     * @param product the candidate product
     * @param fragment the fragment, already non-null
     * @param first the first attribute code
     * @param second the second attribute code
     * @return true when either attribute contains the fragment
     */
    private boolean containsAttribute(Product product, String fragment, String first, String second) {
        String lower = fragment.toLowerCase();
        return contains(ProductAttributes.raw(product, first), lower)
                || contains(ProductAttributes.raw(product, second), lower);
    }

    /**
     * The criteria that read the referential around the article: the
     * nomenclature, the current price and the VAT regime (BO-02-03-30/31).
     *
     * @param product the candidate product
     * @return true when every present referential criterion holds
     */
    private boolean matchesCatalog(Product product) {
        if (!families.isEmpty() && !filedUnder(product)) {
            return false;
        }
        if (priceMin == null && priceMax == null && vat == null) {
            return true;
        }
        Price current = Price.findCurrentPrice(product.id);
        if (!priceInRange(current)) {
            return false;
        }
        Integer own = ProductAttributes.vatNumber(product);
        Integer regime = own != null ? own : current == null ? null : current.vatNumber();
        return vat == null || vat.equals(regime);
    }

    /**
     * Tells whether the article is filed under one of the selected nodes,
     * directly or below.
     *
     * <p>Inside a nomenclature scheme a node's parent is read off its code,
     * not off the navigation edges, so the code ancestry is climbed too: an
     * article filed under a sous-famille is found by its famille, its rayon
     * and its activité (BO-02-03-30/31).
     *
     * @param product the candidate product
     * @return true when one of its families or their ancestors is selected
     */
    private boolean filedUnder(Product product) {
        for (ProductFamily family : ProductFamily.findAllFamiliesForProduct(product)) {
            for (String code = family.code; code != null; code = family.nomenclature == null
                    ? null : family.nomenclature.parentCodeOf(code)) {
                if (families.contains(code)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Tells whether a current price satisfies the price range.
     *
     * @param current the current price, or null
     * @return true when no bound is set, or the price lies within the bounds
     */
    private boolean priceInRange(Price current) {
        if (priceMin == null && priceMax == null) {
            return true;
        }
        if (current == null || current.priceIncludingTax == null) {
            return false;
        }
        return (priceMin == null || current.priceIncludingTax.compareTo(priceMin) >= 0)
                && (priceMax == null || current.priceIncludingTax.compareTo(priceMax) <= 0);
    }

    /**
     * Builds the ordered query of the articles this criteria selects.
     *
     * <p>The database does the filtering. An explicit alias is used rather than
     * Panache's bare where-fragment form, because the attribute clauses carry a
     * sub-select and a sub-select needs to name what it is not.
     *
     * @return the query, ordered by EAN like the in-memory list it replaces
     */
    public PanacheQuery<Product> find() {
        String where = whereClause();
        if (where.isEmpty()) {
            return Product.find("from Product p order by p.ean");
        }
        return Product.find("from Product p where " + where + " order by p.ean", parameters());
    }

    /**
     * Renders the present criteria as a JPQL predicate over the alias {@code p}.
     *
     * <p>Every criterion is ANDed, and an absent one contributes nothing —
     * the same rule as {@link #matches(Product)}, which is why an empty criteria
     * produces an empty clause and not {@code 1=1}.
     *
     * @return the predicate, empty when the criteria constrains nothing
     */
    public String whereClause() {
        List<String> clauses = new ArrayList<>();
        if (text != null) {
            // The three-field OR of matchesText, parenthesised: without the
            // parentheses it would swallow every clause ANDed after it.
            clauses.add("(lower(p.ean) like :text or lower(p.name) like :text"
                    + " or lower(p.internalCode) like :text"
                    + " or p.id in (select s.id from Product s join s.saleCodes sc where lower(sc) like :text))");
        }
        if (type != null) {
            clauses.add("p.productType = :type");
        }
        if (status == Status.ACTIVE) {
            clauses.add("p.active = true");
        }
        if (status == Status.INACTIVE) {
            clauses.add("p.active = false");
        }
        if (forbidden != null) {
            clauses.add("p.forbiddenToSale = :forbidden");
        }
        if (eanFrom != null) {
            clauses.add("p.ean >= :eanFrom");
        }
        if (eanTo != null) {
            clauses.add("p.ean <= :eanTo");
        }
        int index = 0;
        for (String code : attributes) {
            clauses.add(attributeClause(code, index));
            index++;
        }
        addFieldClauses(clauses);
        addCatalogClauses(clauses);
        return String.join(" and ", clauses);
    }

    /**
     * The fragment and supplier clauses of BO-02-03-30.
     *
     * @param clauses the clauses being collected
     */
    private void addFieldClauses(List<String> clauses) {
        if (plu != null) {
            clauses.add("lower(p.plu) like :plu");
        }
        if (internalCode != null) {
            clauses.add("lower(p.internalCode) like :internalCode");
        }
        if (description != null) {
            clauses.add("lower(p.description) like :description");
        }
        if (checkoutLabel != null) {
            clauses.add("lower(p.checkoutLabel) like :checkoutLabel");
        }
        if (brand != null) {
            clauses.add("lower(p.brand) like :brand");
        }
        if (supplier != null) {
            clauses.add(textAttributeClause("su", "supplierCodes", "supplier"));
        }
        if (supplierArticle != null) {
            clauses.add(textAttributeClause("sa", "supplierArticleCodes", "supplierArticle"));
        }
    }

    /**
     * The predicate asking that one of several text attributes contain a
     * fragment.
     *
     * @param alias the alias prefix of the sub-select
     * @param codesParam the parameter naming the attribute codes
     * @param valueParam the parameter naming the fragment
     * @return the JPQL predicate over the alias {@code p}
     */
    private String textAttributeClause(String alias, String codesParam, String valueParam) {
        return "p.id in (select " + alias + ".id from Product " + alias + " join " + alias + ".attributes "
                + alias + "v where key(" + alias + "v) in :" + codesParam + " and lower(" + alias + "v) like :"
                + valueParam + ")";
    }

    /**
     * The nomenclature, price and VAT clauses of BO-02-03-30/31. The CURRENT
     * price is the valid price no other valid price outranks by priority, which
     * is what {@code Price.findCurrentPrice} returns.
     *
     * @param clauses the clauses being collected
     */
    private void addCatalogClauses(List<String> clauses) {
        if (!families.isEmpty()) {
            clauses.add("p.id in (select fp.id from ProductFamily f join f.products fp where f.code in :families)");
        }
        if (priceMin != null) {
            clauses.add("p.id in (select pc.product.id from Price pc where " + CURRENT_PRICE
                    + " and pc.priceIncludingTax >= :priceMin)");
        }
        if (priceMax != null) {
            clauses.add("p.id in (select pc.product.id from Price pc where " + CURRENT_PRICE
                    + " and pc.priceIncludingTax <= :priceMax)");
        }
        if (vat != null) {
            String own = "select va.id from Product va join va.attributes vv where key(vv) = 'VAT_NUMBER'";
            clauses.add("(p.id in (" + own + " and vv = :vatText) or (p.id not in (" + own + ")"
                    + " and p.id in (select pc.product.id from Price pc where " + CURRENT_PRICE
                    + " and pc.vat.number = :vat)))");
        }
    }

    /**
     * The JPQL condition selecting, over the alias {@code pc}, the price that
     * is current now: valid at {@code :now} and outranked by no other valid
     * price of the same article.
     */
    private static final String CURRENT_PRICE = "(pc.startDateTime is null or pc.startDateTime <= :now)"
            + " and (pc.endDateTime is null or pc.endDateTime > :now)"
            + " and not exists (select po.id from Price po where po.product = pc.product"
            + " and (po.startDateTime is null or po.startDateTime <= :now)"
            + " and (po.endDateTime is null or po.endDateTime > :now) and po.priority > pc.priority)";

    /**
     * The predicate asking that an article carry one attribute as true.
     *
     * <p>A sub-select per attribute and not a join: a join would multiply the
     * article by its attribute rows, and a second required attribute would then
     * match nothing.
     *
     * <p>Two shapes, because {@code ProductAttributes.flag} resolves a MISSING
     * value through the catalog default. An attribute defaulting to false — all
     * of them today — is carried only by the rows that state it, so the article
     * must be among those holding it true. An attribute defaulting to true is
     * the mirror image: the article matches unless a row states otherwise.
     * Writing only the first shape would put the query and {@link
     * #matches(Product)} silently at odds the day a default-true attribute is
     * declared.
     *
     * @param code the attribute code, known to the catalog
     * @param index the position of this attribute, which names its alias and
     *        its parameter
     * @return the JPQL predicate over the alias {@code p}
     */
    private String attributeClause(String code, int index) {
        String rows = "select a" + index + ".id from Product a" + index
                + " join a" + index + ".attributes v" + index
                + " where key(v" + index + ") = :attr" + index;
        if (defaultsToTrue(code)) {
            return "p.id not in (" + rows + " and lower(v" + index + ") <> 'true')";
        }
        return "p.id in (" + rows + " and lower(v" + index + ") = 'true')";
    }

    /**
     * Tells whether the catalog default of an attribute is itself true.
     *
     * @param code the attribute code
     * @return true when an article carrying no value for that code still flags
     */
    private boolean defaultsToTrue(String code) {
        return Boolean.parseBoolean(ProductAttributeCatalog.defaultValue(code));
    }

    /**
     * The values the predicate of {@link #whereClause()} binds.
     *
     * <p>Kept in declaration order so a failing query logs its parameters in the
     * order the clause reads them.
     *
     * @return the parameters by name, empty when the criteria constrains nothing
     */
    public Map<String, Object> parameters() {
        Map<String, Object> params = new LinkedHashMap<>();
        if (text != null) {
            // matchesText lowercases both sides and asks for containment.
            params.put("text", "%" + text.toLowerCase() + "%");
        }
        if (type != null) {
            params.put("type", type);
        }
        if (forbidden != null) {
            params.put("forbidden", forbidden);
        }
        if (eanFrom != null) {
            params.put("eanFrom", eanFrom);
        }
        if (eanTo != null) {
            params.put("eanTo", eanTo);
        }
        int index = 0;
        for (String code : attributes) {
            params.put("attr" + index, code);
            index++;
        }
        putFragment(params, "plu", plu);
        putFragment(params, "internalCode", internalCode);
        putFragment(params, "description", description);
        putFragment(params, "checkoutLabel", checkoutLabel);
        putFragment(params, "brand", brand);
        if (supplier != null) {
            params.put("supplierCodes", List.of(ProductAttributeCatalog.SUPPLIER_CODE, ProductAttributeCatalog.SUPPLIER_NAME));
            params.put("supplier", like(supplier));
        }
        if (supplierArticle != null) {
            params.put("supplierArticleCodes",
                    List.of(ProductAttributeCatalog.SUPPLIER_ARTICLE_CODE, ProductAttributeCatalog.SUPPLIER_ARTICLE_NAME));
            params.put("supplierArticle", like(supplierArticle));
        }
        if (!families.isEmpty()) {
            params.put("families", expandFamilies(families));
        }
        putIfPresent(params, "priceMin", priceMin);
        putIfPresent(params, "priceMax", priceMax);
        if (vat != null) {
            params.put("vat", vat);
            params.put("vatText", String.valueOf(vat));
        }
        if (priceMin != null || priceMax != null || vat != null) {
            params.put("now", DateTimeProvider.now());
        }
        return params;
    }

    /**
     * Binds a containment fragment when present.
     *
     * @param params the parameters being collected
     * @param name the parameter name
     * @param fragment the fragment, or null
     */
    private void putFragment(Map<String, Object> params, String name, String fragment) {
        if (fragment != null) {
            params.put(name, like(fragment));
        }
    }

    /**
     * Binds a value when present.
     *
     * @param params the parameters being collected
     * @param name the parameter name
     * @param value the value, or null
     */
    private void putIfPresent(Map<String, Object> params, String name, Object value) {
        if (value != null) {
            params.put(name, value);
        }
    }

    /**
     * The LIKE pattern of a containment fragment, lower-cased.
     *
     * @param fragment the fragment
     * @return the pattern
     */
    private static String like(String fragment) {
        return "%" + fragment.toLowerCase() + "%";
    }

    /**
     * Expands the selected nomenclature nodes with every node below them, so
     * selecting a rayon selects its families and their sous-familles.
     * <p>Both structures are walked: the navigation edges of the shop's own
     * groups, and, for a node of a nomenclature scheme, the nodes of that
     * scheme whose code names it as parent (BO-02-03-30/31).
     *
     * @param roots the selected node codes
     * @return the codes of the selected nodes and of their descendants
     */
    static Set<String> expandFamilies(Set<String> roots) {
        Set<String> expanded = new LinkedHashSet<>(roots);
        List<String> pending = new ArrayList<>(roots);
        Map<com.intermarche.edge.domain.catalog.Nomenclature, List<ProductFamily>> schemes = new LinkedHashMap<>();
        while (!pending.isEmpty()) {
            ProductFamily node = ProductFamily.findByCode(pending.remove(0));
            if (node == null) {
                continue;
            }
            List<ProductFamily> children = new ArrayList<>(node.productFamilies);
            if (node.nomenclature != null) {
                for (ProductFamily member : schemes.computeIfAbsent(node.nomenclature,
                        ProductFamily::listInNomenclature)) {
                    if (node.code.equals(member.parentCode())) {
                        children.add(member);
                    }
                }
            }
            for (ProductFamily child : children) {
                if (expanded.add(child.code)) {
                    pending.add(child.code);
                }
            }
        }
        return expanded;
    }

    /**
     * Tells whether the free text appears (case-insensitively) in the EAN, the
     * commercial name or the internal code.
     *
     * @param product the candidate product
     * @param needle the free text, already non-null
     * @return true when any of the three searched fields contains the text
     */
    private boolean matchesText(Product product, String needle) {
        String lower = needle.toLowerCase();
        return contains(product.ean, lower)
                || contains(product.name, lower)
                || contains(product.internalCode, lower)
                || product.saleCodes.stream().anyMatch(code -> contains(code, lower));
    }

    /**
     * Null-safe case-insensitive containment test.
     *
     * @param value the field value, or null
     * @param lower the already-lowercased needle
     * @return true when the value is non-null and contains the needle
     */
    private boolean contains(String value, String lower) {
        return value != null && value.toLowerCase().contains(lower);
    }

    /**
     * Parses a criteria from the raw query parameters of the search form. Every
     * value is trimmed; a blank or unparseable value is dropped so the
     * corresponding criterion contributes nothing.
     *
     * @param params the query parameters, or null for an empty criteria
     * @return the parsed criteria (never null)
     */
    public static ArticleSearchCriteria fromParams(MultivaluedMap<String, String> params) {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        if (params == null) {
            return criteria;
        }
        criteria.text = blankToNull(params.getFirst("text"));
        criteria.type = parseType(params.getFirst("type"));
        criteria.status = parseStatus(params.getFirst("status"));
        criteria.forbidden = parseForbidden(params.getFirst("forbidden"));
        criteria.eanFrom = blankToNull(params.getFirst("eanFrom"));
        criteria.eanTo = blankToNull(params.getFirst("eanTo"));
        addKnownAttributes(criteria.attributes, params.get("attr"));
        criteria.plu = blankToNull(params.getFirst("plu"));
        criteria.internalCode = blankToNull(params.getFirst("internalCode"));
        criteria.description = blankToNull(params.getFirst("description"));
        criteria.checkoutLabel = blankToNull(params.getFirst("checkoutLabel"));
        criteria.supplier = blankToNull(params.getFirst("supplier"));
        criteria.supplierArticle = blankToNull(params.getFirst("supplierArticle"));
        criteria.brand = blankToNull(params.getFirst("brand"));
        addFamilies(criteria.families, params.get("family"));
        criteria.priceMin = parseAmount(params.getFirst("priceMin"));
        criteria.priceMax = parseAmount(params.getFirst("priceMax"));
        criteria.vat = parseVat(params.getFirst("vat"));
        return criteria;
    }

    /**
     * Adds the node codes of a raw list, ignoring blanks; a single value may
     * carry several codes separated by commas or semicolons.
     *
     * @param target the set to fill
     * @param raw the raw values, or null
     */
    static void addFamilies(Set<String> target, List<String> raw) {
        if (raw == null) {
            return;
        }
        for (String value : raw) {
            for (String code : value == null ? new String[0] : value.split("[,;]")) {
                String trimmed = blankToNull(code);
                if (trimmed != null) {
                    target.add(trimmed);
                }
            }
        }
    }

    /**
     * Parses an amount, tolerating a French comma; a blank or malformed value
     * is no bound.
     *
     * @param raw the raw value, or null
     * @return the amount, or null
     */
    static BigDecimal parseAmount(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses a VAT regime number; a blank or malformed value is no criterion.
     *
     * @param raw the raw value, or null
     * @return the number, or null
     */
    static Integer parseVat(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Rebuilds a criteria from a canonical query string (an
     * {@code ArticleSelection}'s stored perimeter).
     *
     * @param queryString the stored query string, or null
     * @return the parsed criteria (never null)
     */
    public static ArticleSearchCriteria fromQueryString(String queryString) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        if (queryString != null && !queryString.isBlank()) {
            for (String pair : queryString.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String key = eq >= 0 ? pair.substring(0, eq) : pair;
                String value = eq >= 0 ? pair.substring(eq + 1) : "";
                params.add(decode(key), decode(value));
            }
        }
        return fromParams(params);
    }

    /**
     * Renders the present criteria as a canonical query string, suitable for
     * persisting as an {@code ArticleSelection} perimeter and for re-applying it
     * through {@link #fromQueryString(String)}.
     *
     * @return the query string; empty when the criteria matches everything
     */
    public String toQueryString() {
        StringBuilder sb = new StringBuilder();
        if (text != null) {
            append(sb, "text", text);
        }
        if (type != null) {
            append(sb, "type", type.name());
        }
        if (status != Status.ALL) {
            append(sb, "status", status.name());
        }
        if (forbidden != null) {
            append(sb, "forbidden", forbidden ? "yes" : "no");
        }
        if (eanFrom != null) {
            append(sb, "eanFrom", eanFrom);
        }
        if (eanTo != null) {
            append(sb, "eanTo", eanTo);
        }
        for (String code : attributes) {
            append(sb, "attr", code);
        }
        appendIfPresent(sb, "plu", plu);
        appendIfPresent(sb, "internalCode", internalCode);
        appendIfPresent(sb, "description", description);
        appendIfPresent(sb, "checkoutLabel", checkoutLabel);
        appendIfPresent(sb, "supplier", supplier);
        appendIfPresent(sb, "supplierArticle", supplierArticle);
        appendIfPresent(sb, "brand", brand);
        for (String code : families) {
            append(sb, "family", code);
        }
        appendIfPresent(sb, "priceMin", priceMin == null ? null : priceMin.toPlainString());
        appendIfPresent(sb, "priceMax", priceMax == null ? null : priceMax.toPlainString());
        appendIfPresent(sb, "vat", vat == null ? null : String.valueOf(vat));
        return sb.toString();
    }

    /**
     * Appends one pair when its value is present.
     *
     * @param sb the query-string builder
     * @param key the parameter name
     * @param value the parameter value, or null
     */
    private void appendIfPresent(StringBuilder sb, String key, String value) {
        if (value != null) {
            append(sb, key, value);
        }
    }

    /**
     * Whether the criteria constrains anything at all.
     *
     * @return true when every criterion is absent
     */
    public boolean isEmpty() {
        return toQueryString().isEmpty();
    }

    /**
     * Appends one {@code key=value} pair to the builder, prefixing an ampersand
     * once the builder is non-empty, both tokens URL-encoded.
     *
     * @param sb the query-string builder
     * @param key the parameter name
     * @param value the parameter value
     */
    private void append(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) {
            sb.append('&');
        }
        sb.append(encode(key)).append('=').append(encode(value));
    }

    /**
     * Parses a product type, tolerating a blank or unknown value.
     *
     * @param raw the raw value, or null
     * @return the type, or null when absent or unknown
     */
    static ProductType parseType(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return ProductType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Parses a status, defaulting to {@link Status#ALL} on a blank or unknown
     * value.
     *
     * @param raw the raw value, or null
     * @return the status, never null
     */
    static Status parseStatus(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return Status.ALL;
        }
        try {
            return Status.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Status.ALL;
        }
    }

    /**
     * Parses the forbidden filter: {@code yes} means forbidden only, {@code no}
     * means sellable only, anything else means no constraint.
     *
     * @param raw the raw value, or null
     * @return true, false, or null for no constraint
     */
    static Boolean parseForbidden(String raw) {
        String value = blankToNull(raw);
        if ("yes".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("no".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Adds the well-known attribute codes of a raw list to a target set,
     * ignoring blank values and codes the register does not act upon.
     *
     * @param target the set to fill
     * @param raw the raw values, or null
     */
    static void addKnownAttributes(Set<String> target, List<String> raw) {
        if (raw == null) {
            return;
        }
        for (String value : raw) {
            String trimmed = blankToNull(value);
            if (trimmed != null && ProductAttributeCatalog.isKnown(trimmed)) {
                target.add(trimmed);
            }
        }
    }

    /**
     * Trims a raw value, returning null when it is null or blank.
     *
     * @param raw the raw value
     * @return the trimmed value, or null
     */
    static String blankToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * URL-encodes one query-string token.
     *
     * @param value the raw token
     * @return the encoded token
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * URL-decodes one query-string token.
     *
     * @param value the encoded token
     * @return the decoded token
     */
    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
