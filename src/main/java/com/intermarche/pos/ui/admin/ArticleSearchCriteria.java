package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductType;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributes;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

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
        return true;
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
                    + " or lower(p.internalCode) like :text)");
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
        return String.join(" and ", clauses);
    }

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
        return params;
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
                || contains(product.internalCode, lower);
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
        return criteria;
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
        return sb.toString();
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
