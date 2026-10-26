package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.catalog.Price;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.ProductFamily;
import com.intermarche.edge.domain.catalog.ProductType;
import com.intermarche.edge.domain.catalog.VatRate;
import java.math.BigDecimal;
import java.util.Set;
import com.intermarche.edge.domain.catalog.attribute.ProductAttributeCatalog;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mockStatic;

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

    // --------------------------------------------------
    // The JPQL reading of the same criteria
    // --------------------------------------------------

    /**
     * An empty criteria constrains nothing: no clause and no parameter, which
     * is what makes the unfiltered list a plain ordered query.
     */
    @Test
    void anEmptyCriteriaProducesNoClause() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        Assertions.assertEquals("", criteria.whereClause());
        Assertions.assertTrue(criteria.parameters().isEmpty());
    }

    /**
     * The free text becomes the three-field OR, parenthesised, and binds one
     * lower-cased fragment wrapped in percent signs.
     */
    @Test
    void theFreeTextBecomesAParenthesisedOr() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.text = "LaIt";
        Assertions.assertEquals("(lower(p.ean) like :text or lower(p.name) like :text"
                + " or lower(p.internalCode) like :text"
                + " or p.id in (select s.id from Product s join s.saleCodes sc where lower(sc) like :text))",
                criteria.whereClause());
        Assertions.assertEquals("%lait%", criteria.parameters().get("text"));
    }

    /**
     * The type binds the enum itself, not its name.
     */
    @Test
    void theTypeBindsTheEnum() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.type = ProductType.WEIGHT;
        Assertions.assertEquals("p.productType = :type", criteria.whereClause());
        Assertions.assertEquals(ProductType.WEIGHT, criteria.parameters().get("type"));
    }

    /**
     * The ACTIVE status becomes a literal and binds nothing (first arm of the
     * status pair).
     */
    @Test
    void theActiveStatusBecomesALiteral() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.status = ArticleSearchCriteria.Status.ACTIVE;
        Assertions.assertEquals("p.active = true", criteria.whereClause());
        Assertions.assertTrue(criteria.parameters().isEmpty());
    }

    /**
     * The INACTIVE status becomes the opposite literal (second arm of the
     * status pair).
     */
    @Test
    void theInactiveStatusBecomesTheOppositeLiteral() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.status = ArticleSearchCriteria.Status.INACTIVE;
        Assertions.assertEquals("p.active = false", criteria.whereClause());
    }

    /**
     * The ALL status constrains nothing (fall-through of the status pair).
     */
    @Test
    void theAllStatusConstrainsNothing() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.status = ArticleSearchCriteria.Status.ALL;
        Assertions.assertEquals("", criteria.whereClause());
    }

    /**
     * The forbidden filter binds the boolean, in both of its values.
     */
    @Test
    void theForbiddenFilterBindsItsBoolean() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.forbidden = Boolean.TRUE;
        Assertions.assertEquals("p.forbiddenToSale = :forbidden", criteria.whereClause());
        Assertions.assertEquals(Boolean.TRUE, criteria.parameters().get("forbidden"));
        criteria.forbidden = Boolean.FALSE;
        Assertions.assertEquals(Boolean.FALSE, criteria.parameters().get("forbidden"));
    }

    /**
     * Each EAN bound becomes its own comparison, and the two coexist.
     */
    @Test
    void theEanBoundsBecomeComparisons() {
        ArticleSearchCriteria lower = new ArticleSearchCriteria();
        lower.eanFrom = "300";
        Assertions.assertEquals("p.ean >= :eanFrom", lower.whereClause());
        ArticleSearchCriteria upper = new ArticleSearchCriteria();
        upper.eanTo = "400";
        Assertions.assertEquals("p.ean <= :eanTo", upper.whereClause());
        ArticleSearchCriteria both = new ArticleSearchCriteria();
        both.eanFrom = "300";
        both.eanTo = "400";
        Assertions.assertEquals("p.ean >= :eanFrom and p.ean <= :eanTo", both.whereClause());
        Assertions.assertEquals("300", both.parameters().get("eanFrom"));
        Assertions.assertEquals("400", both.parameters().get("eanTo"));
    }

    /**
     * An attribute defaulting to false — every one of them today — asks that the
     * article be among those holding it true.
     */
    @Test
    void aFalseDefaultAttributeAsksForTheRowsHoldingItTrue() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.attributes.add(ProductAttributeCatalog.RECALL);
        Assertions.assertEquals("p.id in (select a0.id from Product a0 join a0.attributes v0"
                + " where key(v0) = :attr0 and lower(v0) = 'true')", criteria.whereClause());
        Assertions.assertEquals(ProductAttributeCatalog.RECALL,
                criteria.parameters().get("attr0"));
    }

    /**
     * Every BOOL attribute of the catalog defaults to false today, so the
     * mirror clause is unreachable through the catalog — this pins the
     * assumption the false-default clause rests on, and fails the day a
     * default-true attribute is declared without the query being revisited.
     */
    @Test
    void everyCatalogBooleanDefaultsToFalse() {
        for (com.intermarche.edge.domain.catalog.attribute.ProductAttributeDef def
                : ProductAttributeCatalog.CATALOG) {
            Assertions.assertFalse(Boolean.parseBoolean(def.defaultValue()),
                    "attribut " + def.code() + " : défaut vrai, la clause miroir devient atteignable");
        }
    }

    /**
     * Two attributes produce two sub-selects with distinct aliases and distinct
     * parameters, which is what lets both be required at once.
     */
    @Test
    void twoAttributesProduceTwoIndependentSubSelects() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.attributes.add(ProductAttributeCatalog.RECALL);
        criteria.attributes.add(ProductAttributeCatalog.BULKY);
        String where = criteria.whereClause();
        Assertions.assertTrue(where.contains("a0.attributes v0"));
        Assertions.assertTrue(where.contains("a1.attributes v1"));
        Assertions.assertTrue(where.contains(":attr0"));
        Assertions.assertTrue(where.contains(":attr1"));
        Assertions.assertEquals(2, criteria.parameters().size());
    }

    /**
     * Every criterion at once is ANDed in declaration order, so the clause a
     * reader checks is the clause the screen issues.
     */
    @Test
    void everyCriterionAtOnceIsAnded() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.text = "lait";
        criteria.type = ProductType.UNIT;
        criteria.status = ArticleSearchCriteria.Status.ACTIVE;
        criteria.forbidden = Boolean.FALSE;
        criteria.eanFrom = "300";
        criteria.eanTo = "400";
        Assertions.assertEquals("(lower(p.ean) like :text or lower(p.name) like :text"
                + " or lower(p.internalCode) like :text"
                + " or p.id in (select s.id from Product s join s.saleCodes sc where lower(sc) like :text))"
                + " and p.productType = :type"
                + " and p.active = true"
                + " and p.forbiddenToSale = :forbidden"
                + " and p.ean >= :eanFrom"
                + " and p.ean <= :eanTo", criteria.whereClause());
        Assertions.assertEquals(List.of("text", "type", "forbidden", "eanFrom", "eanTo"),
                List.copyOf(criteria.parameters().keySet()));
    }

    /**
     * An attribute whose catalog default is itself true takes the MIRROR clause:
     * the article matches unless a row states otherwise. No catalog attribute
     * defaults true today, so the branch is only reachable by making the catalog
     * answer true for a code — which is exactly what pins the {@code not in} shape
     * the query relies on the day a default-true attribute is declared.
     */
    @Test
    void aTrueDefaultAttributeTakesTheMirrorClause() {
        try (MockedStatic<ProductAttributeCatalog> catalog =
                mockStatic(ProductAttributeCatalog.class)) {
            catalog.when(() -> ProductAttributeCatalog.defaultValue("TRUE_DEF")).thenReturn("true");
            ArticleSearchCriteria criteria = new ArticleSearchCriteria();
            criteria.attributes.add("TRUE_DEF");
            Assertions.assertEquals("p.id not in (select a0.id from Product a0"
                    + " join a0.attributes v0 where key(v0) = :attr0"
                    + " and lower(v0) <> 'true')", criteria.whereClause());
            Assertions.assertEquals("TRUE_DEF", criteria.parameters().get("attr0"));
        }
    }

    /**
     * Builds a current price of the given amount under the given regime.
     *
     * @param amount the price including tax, or null
     * @param vatNumber the regime number, or null for no regime
     * @return the price
     */
    private Price price(String amount, Integer vatNumber) {
        Price price = new Price();
        price.priceIncludingTax = amount == null ? null : new BigDecimal(amount);
        price.vat = vatNumber == null ? null : new VatRate(vatNumber, new BigDecimal("0.2000"), "N");
        return price;
    }

    /**
     * The free text reaches the additional sale codes (BO-02-03-03).
     */
    @Test
    void theFreeTextMatchesASaleCode() {
        Product product = product("300", "Lait", null);
        product.saleCodes.add("ALT-77");
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.text = "alt-7";
        Assertions.assertTrue(criteria.matches(product));
        criteria.text = "zzz";
        Assertions.assertFalse(criteria.matches(product));
    }

    /**
     * Each field fragment of BO-02-03-30 matches by containment, and a miss on
     * any one of them excludes the article (one test per leg).
     */
    @Test
    void theFieldFragmentsMatchByContainment() {
        Product product = product("300", "Lait", "INT-1");
        product.plu = "1234";
        product.description = "Lait demi-écrémé";
        product.checkoutLabel = "LAIT DE";
        product.brand = "Lactel";
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.plu = "23";
        criteria.internalCode = "int";
        criteria.description = "DEMI";
        criteria.checkoutLabel = "lait";
        criteria.brand = "act";
        Assertions.assertTrue(criteria.matches(product));
        String[] misses = {"plu", "internalCode", "description", "checkoutLabel", "brand"};
        for (String miss : misses) {
            ArticleSearchCriteria one = ArticleSearchCriteria.fromQueryString(criteria.toQueryString());
            switch (miss) {
                case "plu" -> one.plu = "99";
                case "internalCode" -> one.internalCode = "zz";
                case "description" -> one.description = "zz";
                case "checkoutLabel" -> one.checkoutLabel = "zz";
                default -> one.brand = "zz";
            }
            Assertions.assertFalse(one.matches(product), miss);
        }
    }

    /**
     * The supplier criteria read the supplier attributes, code or name, and
     * the supplier's article attributes, code or name.
     */
    @Test
    void theSupplierCriteriaReadTheSupplierAttributes() {
        Product product = product("300", "Lait", null);
        product.attributes.put(ProductAttributeCatalog.SUPPLIER_NAME, "Lactalis");
        product.attributes.put(ProductAttributeCatalog.SUPPLIER_ARTICLE_CODE, "LX-9");
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.supplier = "lacta";
        criteria.supplierArticle = "lx-";
        Assertions.assertTrue(criteria.matches(product));
        product.attributes.remove(ProductAttributeCatalog.SUPPLIER_NAME);
        product.attributes.put(ProductAttributeCatalog.SUPPLIER_CODE, "LACTA01");
        product.attributes.remove(ProductAttributeCatalog.SUPPLIER_ARTICLE_CODE);
        product.attributes.put(ProductAttributeCatalog.SUPPLIER_ARTICLE_NAME, "Brique LX-9");
        Assertions.assertTrue(criteria.matches(product));
        criteria.supplier = "zz";
        Assertions.assertFalse(criteria.matches(product));
        criteria.supplier = null;
        criteria.supplierArticle = "zz";
        Assertions.assertFalse(criteria.matches(product));
    }

    /**
     * The family criterion matches an article filed under a selected node or
     * below it, and nothing else.
     */
    @Test
    void theFamilyCriterionReadsTheAncestry() {
        Product product = product("300", "Lait", null);
        ProductFamily rayon = new ProductFamily();
        rayon.code = "1002";
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.families.add("1002");
        try (MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            families.when(() -> ProductFamily.findAllFamiliesForProduct(product)).thenReturn(Set.of(rayon));
            Assertions.assertTrue(criteria.matches(product));
            criteria.families.clear();
            criteria.families.add("2001");
            Assertions.assertFalse(criteria.matches(product));
        }
    }

    /**
     * Inside a nomenclature scheme the family criterion climbs the code
     * ancestry: an article filed under a sous-famille is found by its
     * activité, and not by a node outside its branch.
     */
    @Test
    void theFamilyCriterionClimbsTheSchemeCodes() {
        Product product = product("300", "Lait", null);
        com.intermarche.edge.domain.catalog.Nomenclature scheme =
                org.mockito.Mockito.mock(com.intermarche.edge.domain.catalog.Nomenclature.class);
        org.mockito.Mockito.when(scheme.parentCodeOf("202002000001")).thenReturn("20200200");
        org.mockito.Mockito.when(scheme.parentCodeOf("20200200")).thenReturn("2020");
        org.mockito.Mockito.when(scheme.parentCodeOf("2020")).thenReturn("20");
        org.mockito.Mockito.when(scheme.parentCodeOf("20")).thenReturn(null);
        ProductFamily leaf = new ProductFamily();
        leaf.code = "202002000001";
        leaf.nomenclature = scheme;
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.families.add("20");
        try (MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            families.when(() -> ProductFamily.findAllFamiliesForProduct(product)).thenReturn(Set.of(leaf));
            Assertions.assertTrue(criteria.matches(product));
            criteria.families.clear();
            criteria.families.add("30");
            Assertions.assertFalse(criteria.matches(product));
        }
    }

    /**
     * A node of a nomenclature scheme expands to the scheme nodes whose code
     * names it as parent, and below them; the scheme is read once, and a node
     * of another branch or the node itself is not taken as a child.
     */
    @Test
    void aSchemeNodeExpandsToItsCodeDescendants() {
        com.intermarche.edge.domain.catalog.Nomenclature scheme =
                org.mockito.Mockito.mock(com.intermarche.edge.domain.catalog.Nomenclature.class);
        org.mockito.Mockito.when(scheme.parentCodeOf("20")).thenReturn(null);
        org.mockito.Mockito.when(scheme.parentCodeOf("2020")).thenReturn("20");
        org.mockito.Mockito.when(scheme.parentCodeOf("3030")).thenReturn("30");
        ProductFamily activity = new ProductFamily();
        activity.code = "20";
        activity.nomenclature = scheme;
        ProductFamily rayon = new ProductFamily();
        rayon.code = "2020";
        rayon.nomenclature = scheme;
        ProductFamily other = new ProductFamily();
        other.code = "3030";
        other.nomenclature = scheme;
        try (MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            families.when(() -> ProductFamily.findByCode("20")).thenReturn(activity);
            families.when(() -> ProductFamily.findByCode("2020")).thenReturn(rayon);
            families.when(() -> ProductFamily.listInNomenclature(scheme)).thenReturn(List.of(activity, rayon, other));
            Assertions.assertEquals(Set.of("20", "2020"), ArticleSearchCriteria.expandFamilies(Set.of("20")));
            families.verify(() -> ProductFamily.listInNomenclature(scheme), org.mockito.Mockito.times(1));
        }
    }

    /**
     * The price range reads the CURRENT price: inside the bounds, below the
     * minimum, above the maximum, without a price and without an amount.
     */
    @Test
    void thePriceRangeReadsTheCurrentPrice() {
        Product product = product("300", "Lait", null);
        product.id = 7L;
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.priceMin = new BigDecimal("1.00");
        criteria.priceMax = new BigDecimal("2.00");
        try (MockedStatic<Price> prices = mockStatic(Price.class)) {
            prices.when(() -> Price.findCurrentPrice(7L)).thenReturn(price("1.50", null));
            Assertions.assertTrue(criteria.matches(product));
            prices.when(() -> Price.findCurrentPrice(7L)).thenReturn(price("0.50", null));
            Assertions.assertFalse(criteria.matches(product));
            prices.when(() -> Price.findCurrentPrice(7L)).thenReturn(price("2.50", null));
            Assertions.assertFalse(criteria.matches(product));
            prices.when(() -> Price.findCurrentPrice(7L)).thenReturn(price(null, null));
            Assertions.assertFalse(criteria.matches(product));
            prices.when(() -> Price.findCurrentPrice(7L)).thenReturn(null);
            Assertions.assertFalse(criteria.matches(product));
            criteria.priceMin = null;
            prices.when(() -> Price.findCurrentPrice(7L)).thenReturn(price("0.10", null));
            Assertions.assertTrue(criteria.matches(product));
            criteria.priceMin = new BigDecimal("0.05");
            criteria.priceMax = null;
            Assertions.assertTrue(criteria.matches(product));
        }
    }

    /**
     * The VAT criterion reads the article's own regime first, the current
     * price's otherwise, and nothing when there is neither.
     */
    @Test
    void theVatCriterionReadsTheArticleThenThePrice() {
        Product product = product("300", "Lait", null);
        product.id = 7L;
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.vat = 3;
        try (MockedStatic<Price> prices = mockStatic(Price.class)) {
            prices.when(() -> Price.findCurrentPrice(7L)).thenReturn(price("1.00", 3));
            Assertions.assertTrue(criteria.matches(product));
            product.attributes.put(ProductAttributeCatalog.VAT_NUMBER, "1");
            Assertions.assertFalse(criteria.matches(product));
            product.attributes.remove(ProductAttributeCatalog.VAT_NUMBER);
            prices.when(() -> Price.findCurrentPrice(7L)).thenReturn(null);
            Assertions.assertFalse(criteria.matches(product));
        }
    }

    /**
     * Every lot-20 criterion renders its clause and binds its parameters, the
     * families expanded with their descendants and the current time bound for
     * the price and VAT clauses.
     */
    @Test
    void theLot20CriteriaRenderTheirClauses() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.plu = "12";
        criteria.internalCode = "I";
        criteria.description = "D";
        criteria.checkoutLabel = "C";
        criteria.brand = "B";
        criteria.supplier = "S";
        criteria.supplierArticle = "A";
        criteria.families.add("10");
        criteria.priceMin = BigDecimal.ONE;
        criteria.priceMax = BigDecimal.TEN;
        criteria.vat = 2;
        ProductFamily root = new ProductFamily();
        root.code = "10";
        ProductFamily child = new ProductFamily();
        child.code = "1001";
        root.productFamilies.add(child);
        child.productFamilies.add(root);
        try (MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            families.when(() -> ProductFamily.findByCode("10")).thenReturn(root);
            families.when(() -> ProductFamily.findByCode("1001")).thenReturn(child);
            String where = criteria.whereClause();
            Assertions.assertTrue(where.contains("lower(p.plu) like :plu"));
            Assertions.assertTrue(where.contains("lower(p.internalCode) like :internalCode"));
            Assertions.assertTrue(where.contains("lower(p.description) like :description"));
            Assertions.assertTrue(where.contains("lower(p.checkoutLabel) like :checkoutLabel"));
            Assertions.assertTrue(where.contains("lower(p.brand) like :brand"));
            Assertions.assertTrue(where.contains("key(suv) in :supplierCodes"));
            Assertions.assertTrue(where.contains("key(sav) in :supplierArticleCodes"));
            Assertions.assertTrue(where.contains("f.code in :families"));
            Assertions.assertTrue(where.contains("pc.priceIncludingTax >= :priceMin"));
            Assertions.assertTrue(where.contains("pc.priceIncludingTax <= :priceMax"));
            Assertions.assertTrue(where.contains("pc.vat.number = :vat"));
            java.util.Map<String, Object> params = criteria.parameters();
            Assertions.assertEquals("%12%", params.get("plu"));
            Assertions.assertEquals(Set.of("10", "1001"), params.get("families"));
            Assertions.assertEquals("2", params.get("vatText"));
            Assertions.assertEquals(2, params.get("vat"));
            Assertions.assertNotNull(params.get("now"));
            Assertions.assertEquals(List.of(ProductAttributeCatalog.SUPPLIER_CODE, ProductAttributeCatalog.SUPPLIER_NAME),
                    params.get("supplierCodes"));
            Assertions.assertEquals("%a%", params.get("supplierArticle"));
        }
    }

    /**
     * An unknown node expands to itself, and a criteria without price or VAT
     * binds no time.
     */
    @Test
    void anUnknownFamilyExpandsToItself() {
        try (MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            families.when(() -> ProductFamily.findByCode("99")).thenReturn(null);
            Assertions.assertEquals(Set.of("99"), ArticleSearchCriteria.expandFamilies(Set.of("99")));
        }
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.plu = "1";
        Assertions.assertFalse(criteria.parameters().containsKey("now"));
        criteria.plu = null;
        criteria.priceMax = BigDecimal.ONE;
        Assertions.assertTrue(criteria.parameters().containsKey("now"));
    }

    /**
     * The lot-20 criteria survive the query-string round trip, families given
     * as several values or one comma-separated value, malformed amounts and
     * VAT numbers dropped.
     */
    @Test
    void theLot20CriteriaRoundTrip() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("plu", "12");
        params.putSingle("internalCode", "I");
        params.putSingle("description", "D");
        params.putSingle("checkoutLabel", "C");
        params.putSingle("supplier", "S");
        params.putSingle("supplierArticle", "A");
        params.putSingle("brand", "B");
        params.add("family", "10, 20");
        params.add("family", " ");
        params.add("family", null);
        params.putSingle("priceMin", "1,5");
        params.putSingle("priceMax", "x");
        params.putSingle("vat", "3");
        ArticleSearchCriteria criteria = ArticleSearchCriteria.fromParams(params);
        Assertions.assertEquals(new BigDecimal("1.5"), criteria.priceMin);
        Assertions.assertNull(criteria.priceMax);
        Assertions.assertEquals(3, criteria.vat);
        Assertions.assertEquals(Set.of("10", "20"), criteria.families);
        String query = criteria.toQueryString();
        Assertions.assertEquals("plu=12&internalCode=I&description=D&checkoutLabel=C&supplier=S"
                + "&supplierArticle=A&brand=B&family=10&family=20&priceMin=1.5&vat=3", query);
        ArticleSearchCriteria back = ArticleSearchCriteria.fromQueryString(query);
        Assertions.assertEquals(query, back.toQueryString());
        Assertions.assertFalse(back.isEmpty());
        Assertions.assertTrue(new ArticleSearchCriteria().isEmpty());
        params.putSingle("priceMax", "4");
        params.putSingle("vat", "x");
        ArticleSearchCriteria other = ArticleSearchCriteria.fromParams(params);
        Assertions.assertNull(other.vat);
        Assertions.assertTrue(other.toQueryString().contains("priceMax=4"));
        Assertions.assertNull(ArticleSearchCriteria.parseAmount(" "));
        Assertions.assertNull(ArticleSearchCriteria.parseVat(null));
        java.util.Set<String> target = new java.util.LinkedHashSet<>();
        ArticleSearchCriteria.addFamilies(target, null);
        Assertions.assertTrue(target.isEmpty());
    }

    /**
     * A VAT criterion alone binds the current time, and a null family value
     * adds nothing.
     */
    @Test
    void aVatCriterionAloneBindsTheTime() {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria();
        criteria.vat = 1;
        Assertions.assertTrue(criteria.parameters().containsKey("now"));
        java.util.Set<String> target = new java.util.LinkedHashSet<>();
        ArticleSearchCriteria.addFamilies(target, java.util.Arrays.asList((String) null, "10"));
        Assertions.assertEquals(Set.of("10"), target);
    }
}
