package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductType;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ArticleSearchCriteria}, targeting 100% branch coverage.
 * <p>
 * A pure value object — no Panache, no CDI, no time — so every test builds its
 * inputs directly and asserts absolute values. The enumeration covers, for
 * {@code matches}: the null-product guard; both arms of each present-field
 * guard (text, type, status ACTIVE/INACTIVE, forbidden, attribute loop); and
 * each independent leg of the free-text OR (EAN, name, internal code) plus the
 * all-miss fall-through, with the null and non-null arms of the null-safe
 * containment. For the parsers: blank, valid and invalid arms of type/status,
 * the yes/no/neither arms of forbidden, and the null/blank/non-blank arms of
 * {@code blankToNull}. For the query-string round-trip: the null/blank/pair
 * shapes of {@code fromQueryString} and the empty/non-empty builder arms of
 * {@code toQueryString}.
 */
class ArticleSearchCriteriaTest {

    /**
     * Builds a product with the given identity fields, type active by default.
     *
     * @param ean the EAN, possibly null
     * @param name the commercial name, possibly null
     * @param internalCode the internal code, possibly null
     * @return the configured product
     */
    private Product product(String ean, String name, String internalCode) {
        Product product = new Product();
        product.ean = ean;
        product.name = name;
        product.internalCode = internalCode;
        product.productType = ProductType.UNIT;
        return product;
    }

    /**
     * matches rejects a null product (null-guard arm).
     */
    @Test
    void matchesRejectsNullProduct() {
        Assertions.assertFalse(new ArticleSearchCriteria().matches(null));
    }

    /**
     * An empty criteria matches any product (text/type/forbidden null arms,
     * status ALL both false, empty attribute loop, return true).
     */
    @Test
    void emptyCriteriaMatchesAny() {
        Assertions.assertTrue(new ArticleSearchCriteria().matches(product("1", "Lait", null)));
    }

    /**
     * Free text matches on the EAN (first OR leg true, contains non-null-hit arm).
     */
    @Test
    void textMatchesEan() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.text = "376";
        Assertions.assertTrue(criteria.matches(product("3760001", "Lait", null)));
    }

    /**
     * Free text matches on the name when the EAN is null (contains null arm on
     * the EAN, second OR leg true).
     */
    @Test
    void textMatchesNameWithNullEan() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.text = "lai";
        Assertions.assertTrue(criteria.matches(product(null, "Lait demi-écrémé", null)));
    }

    /**
     * Free text matches on the internal code when the name is null (EAN
     * non-null-miss arm, name contains null arm, third OR leg true).
     */
    @Test
    void textMatchesInternalCodeWithNullName() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.text = "int-4";
        Assertions.assertTrue(criteria.matches(product("3760001", null, "INT-42")));
    }

    /**
     * Free text that appears nowhere rejects the product (all three OR legs
     * false, internal-code null arm), so the text guard returns false.
     */
    @Test
    void textMissRejects() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.text = "zzz";
        Assertions.assertFalse(criteria.matches(product("3760001", "Lait", null)));
    }

    /**
     * A type filter rejects a product of another type (type non-null, not-equal
     * arm).
     */
    @Test
    void typeMismatchRejects() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.type = ProductType.WEIGHT;
        Assertions.assertFalse(criteria.matches(product("1", "Lait", null)));
    }

    /**
     * A type filter accepts a product of that type (type non-null, equal arm).
     */
    @Test
    void typeMatchAccepts() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.type = ProductType.UNIT;
        Assertions.assertTrue(criteria.matches(product("1", "Lait", null)));
    }

    /**
     * The ACTIVE status rejects an inactive product (status ACTIVE, !active arm).
     */
    @Test
    void statusActiveRejectsInactive() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.status = ArticleSearchCriteria.Status.ACTIVE;
        Product product = product("1", "Lait", null);
        product.active = false;
        Assertions.assertFalse(criteria.matches(product));
    }

    /**
     * The ACTIVE status accepts an active product (status ACTIVE, active arm).
     */
    @Test
    void statusActiveAcceptsActive() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.status = ArticleSearchCriteria.Status.ACTIVE;
        Assertions.assertTrue(criteria.matches(product("1", "Lait", null)));
    }

    /**
     * The INACTIVE status rejects an active product (status INACTIVE, active arm).
     */
    @Test
    void statusInactiveRejectsActive() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.status = ArticleSearchCriteria.Status.INACTIVE;
        Assertions.assertFalse(criteria.matches(product("1", "Lait", null)));
    }

    /**
     * The INACTIVE status accepts an inactive product (status INACTIVE, !active
     * arm).
     */
    @Test
    void statusInactiveAcceptsInactive() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.status = ArticleSearchCriteria.Status.INACTIVE;
        Product product = product("1", "Lait", null);
        product.active = false;
        Assertions.assertTrue(criteria.matches(product));
    }

    /**
     * A forbidden filter rejects a product of the opposite flag (forbidden
     * non-null, not-equal arm).
     */
    @Test
    void forbiddenMismatchRejects() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.forbidden = Boolean.TRUE;
        Assertions.assertFalse(criteria.matches(product("1", "Lait", null)));
    }

    /**
     * A forbidden filter accepts a product of the same flag (forbidden non-null,
     * equal arm).
     */
    @Test
    void forbiddenMatchAccepts() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.forbidden = Boolean.TRUE;
        Product product = product("1", "Lait", null);
        product.forbiddenToSale = true;
        Assertions.assertTrue(criteria.matches(product));
    }

    /**
     * A required attribute rejects a product that does not carry it (loop body,
     * flag-false arm).
     */
    @Test
    void requiredAttributeMissingRejects() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.attributes.add(ProductAttributeCatalog.VAT_EXEMPT);
        Assertions.assertFalse(criteria.matches(product("1", "Lait", null)));
    }

    /**
     * A required attribute accepts a product that carries it (loop body,
     * flag-true arm, then return true).
     */
    @Test
    void requiredAttributePresentAccepts() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.attributes.add(ProductAttributeCatalog.VAT_EXEMPT);
        Product product = product("1", "Lait", null);
        product.attributes.put(ProductAttributeCatalog.VAT_EXEMPT, "true");
        Assertions.assertTrue(criteria.matches(product));
    }

    /**
     * fromParams returns an empty criteria for null params (params-null arm).
     */
    @Test
    void fromParamsNullYieldsEmpty() {
        ArticleSearchCriteria criteria = ArticleSearchCriteria.fromParams(null);
        Assertions.assertNull(criteria.text);
        Assertions.assertNull(criteria.type);
        Assertions.assertEquals(ArticleSearchCriteria.Status.ALL, criteria.status);
        Assertions.assertNull(criteria.forbidden);
        Assertions.assertTrue(criteria.attributes.isEmpty());
    }

    /**
     * fromParams parses every field and keeps only well-known attribute codes
     * (attr known kept, unknown dropped, blank dropped).
     */
    @Test
    void fromParamsParsesAllFields() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("text", "  lait  ");
        params.putSingle("type", "weight");
        params.putSingle("status", "active");
        params.putSingle("forbidden", "yes");
        params.put("attr", List.of(ProductAttributeCatalog.VAT_EXEMPT, "NOT_A_CODE", "  "));
        ArticleSearchCriteria criteria = ArticleSearchCriteria.fromParams(params);
        Assertions.assertEquals("lait", criteria.text);
        Assertions.assertEquals(ProductType.WEIGHT, criteria.type);
        Assertions.assertEquals(ArticleSearchCriteria.Status.ACTIVE, criteria.status);
        Assertions.assertEquals(Boolean.TRUE, criteria.forbidden);
        Assertions.assertEquals(java.util.Set.of(ProductAttributeCatalog.VAT_EXEMPT), criteria.attributes);
    }

    /**
     * fromParams with non-null params but no attr list exercises the raw-null
     * arm of the attribute collector.
     */
    @Test
    void fromParamsWithoutAttrList() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("text", "lait");
        ArticleSearchCriteria criteria = ArticleSearchCriteria.fromParams(params);
        Assertions.assertTrue(criteria.attributes.isEmpty());
    }

    /**
     * parseType returns null on a blank value.
     */
    @Test
    void parseTypeBlankIsNull() {
        Assertions.assertNull(ArticleSearchCriteria.parseType("   "));
    }

    /**
     * parseType parses a valid case-insensitive name.
     */
    @Test
    void parseTypeValid() {
        Assertions.assertEquals(ProductType.VOLUME, ArticleSearchCriteria.parseType("volume"));
    }

    /**
     * parseType returns null on an unknown name (invalid arm).
     */
    @Test
    void parseTypeUnknownIsNull() {
        Assertions.assertNull(ArticleSearchCriteria.parseType("PALLET"));
    }

    /**
     * parseStatus defaults to ALL on a blank value.
     */
    @Test
    void parseStatusBlankIsAll() {
        Assertions.assertEquals(ArticleSearchCriteria.Status.ALL,
                ArticleSearchCriteria.parseStatus(null));
    }

    /**
     * parseStatus parses a valid case-insensitive name.
     */
    @Test
    void parseStatusValid() {
        Assertions.assertEquals(ArticleSearchCriteria.Status.INACTIVE,
                ArticleSearchCriteria.parseStatus("inactive"));
    }

    /**
     * parseStatus falls back to ALL on an unknown name (invalid arm).
     */
    @Test
    void parseStatusUnknownIsAll() {
        Assertions.assertEquals(ArticleSearchCriteria.Status.ALL,
                ArticleSearchCriteria.parseStatus("ARCHIVED"));
    }

    /**
     * parseForbidden maps yes to true (case-insensitively).
     */
    @Test
    void parseForbiddenYes() {
        Assertions.assertEquals(Boolean.TRUE, ArticleSearchCriteria.parseForbidden("YES"));
    }

    /**
     * parseForbidden maps no to false.
     */
    @Test
    void parseForbiddenNo() {
        Assertions.assertEquals(Boolean.FALSE, ArticleSearchCriteria.parseForbidden("no"));
    }

    /**
     * parseForbidden maps anything else (including blank) to null.
     */
    @Test
    void parseForbiddenOtherIsNull() {
        Assertions.assertNull(ArticleSearchCriteria.parseForbidden("maybe"));
        Assertions.assertNull(ArticleSearchCriteria.parseForbidden(null));
    }

    /**
     * blankToNull maps null to null, blank to null, and trims a non-blank value.
     */
    @Test
    void blankToNullArms() {
        Assertions.assertNull(ArticleSearchCriteria.blankToNull(null));
        Assertions.assertNull(ArticleSearchCriteria.blankToNull("   "));
        Assertions.assertEquals("x", ArticleSearchCriteria.blankToNull("  x  "));
    }

    /**
     * An empty criteria renders an empty query string (every optional-field arm
     * false).
     */
    @Test
    void toQueryStringEmpty() {
        Assertions.assertEquals("", new ArticleSearchCriteria().toQueryString());
    }

    /**
     * toQueryString renders every present field, ampersand-joined (first append
     * empty-builder arm, later appends non-empty arm), forbidden true as "yes".
     */
    @Test
    void toQueryStringFull() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.text = "lait";
        criteria.type = ProductType.UNIT;
        criteria.status = ArticleSearchCriteria.Status.ACTIVE;
        criteria.forbidden = Boolean.TRUE;
        criteria.attributes.add(ProductAttributeCatalog.VAT_EXEMPT);
        Assertions.assertEquals("text=lait&type=UNIT&status=ACTIVE&forbidden=yes&attr="
                + ProductAttributeCatalog.VAT_EXEMPT, criteria.toQueryString());
    }

    /**
     * toQueryString renders forbidden false as "no" (the else arm of the
     * ternary).
     */
    @Test
    void toQueryStringForbiddenNo() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.forbidden = Boolean.FALSE;
        Assertions.assertEquals("forbidden=no", criteria.toQueryString());
    }

    /**
     * fromQueryString returns an empty criteria for a null query string
     * (null arm).
     */
    @Test
    void fromQueryStringNull() {
        Assertions.assertEquals("", ArticleSearchCriteria.fromQueryString(null).toQueryString());
    }

    /**
     * fromQueryString returns an empty criteria for a blank query string
     * (blank arm).
     */
    @Test
    void fromQueryStringBlank() {
        Assertions.assertEquals("", ArticleSearchCriteria.fromQueryString("   ").toQueryString());
    }

    /**
     * fromQueryString parses pairs, tolerating an empty pair and a pair without
     * an equals sign (both parse arms).
     */
    @Test
    void fromQueryStringParsesPairs() {
        ArticleSearchCriteria criteria = ArticleSearchCriteria.fromQueryString("type=UNIT&&status");
        Assertions.assertEquals(ProductType.UNIT, criteria.type);
        Assertions.assertEquals(ArticleSearchCriteria.Status.ALL, criteria.status);
    }

    /**
     * A full criteria round-trips through toQueryString and fromQueryString to
     * an equivalent criteria.
     */
    @Test
    void queryStringRoundTrip() {
        ArticleSearchCriteria original = new ArticleSearchCriteria();
        original.text = "lait bio";
        original.type = ProductType.WEIGHT;
        original.status = ArticleSearchCriteria.Status.INACTIVE;
        original.forbidden = Boolean.FALSE;
        original.attributes.add(ProductAttributeCatalog.VAT_EXEMPT);
        ArticleSearchCriteria restored = ArticleSearchCriteria.fromQueryString(original.toQueryString());
        Assertions.assertEquals("lait bio", restored.text);
        Assertions.assertEquals(ProductType.WEIGHT, restored.type);
        Assertions.assertEquals(ArticleSearchCriteria.Status.INACTIVE, restored.status);
        Assertions.assertEquals(Boolean.FALSE, restored.forbidden);
        Assertions.assertEquals(java.util.Set.of(ProductAttributeCatalog.VAT_EXEMPT), restored.attributes);
    }

    /**
     * The EAN lower bound rejects an article below it (BO-02-03-30): the
     * {@code eanFrom != null} arm and the {@code compareTo < 0} true arm.
     */
    @Test
    void eanRangeRejectsBelowLowerBound() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.eanFrom = "3000000000200";
        Assertions.assertFalse(criteria.matches(product("3000000000100", "Lait", null)));
    }

    /**
     * The EAN lower bound is inclusive (BO-02-03-30): an article exactly at the
     * bound passes ({@code compareTo < 0} false arm on equality).
     */
    @Test
    void eanRangeAcceptsAtLowerBound() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.eanFrom = "3000000000100";
        Assertions.assertTrue(criteria.matches(product("3000000000100", "Lait", null)));
    }

    /**
     * The EAN upper bound rejects an article above it (BO-02-03-30): the
     * {@code eanTo != null} arm and the {@code compareTo > 0} true arm.
     */
    @Test
    void eanRangeRejectsAboveUpperBound() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.eanTo = "3000000000200";
        Assertions.assertFalse(criteria.matches(product("3000000000300", "Lait", null)));
    }

    /**
     * The EAN upper bound is inclusive (BO-02-03-30): an article exactly at the
     * bound passes ({@code compareTo > 0} false arm on equality).
     */
    @Test
    void eanRangeAcceptsAtUpperBound() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.eanTo = "3000000000300";
        Assertions.assertTrue(criteria.matches(product("3000000000300", "Lait", null)));
    }

    /**
     * The EAN range survives the query-string round trip (BO-02-03-30):
     * {@code fromParams} parses both bounds and {@code toQueryString} carries
     * them (both present arms).
     */
    @Test
    void eanRangeQueryStringRoundTrip() {
        ArticleSearchCriteria original = new ArticleSearchCriteria();
        original.eanFrom = "3000000000100";
        original.eanTo = "3000000000300";
        ArticleSearchCriteria restored = ArticleSearchCriteria.fromQueryString(original.toQueryString());
        Assertions.assertEquals("3000000000100", restored.eanFrom);
        Assertions.assertEquals("3000000000300", restored.eanTo);
    }
}
