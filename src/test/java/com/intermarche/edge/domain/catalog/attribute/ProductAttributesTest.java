package com.intermarche.edge.domain.catalog.attribute;

import com.intermarche.edge.domain.catalog.Product;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProductAttributes}, targeting 100% branch coverage.
 * <p>
 * The resolver is pure and reads only the in-memory {@link Product#attributes}
 * map, so no Panache or CDI machinery is needed. Every null guard and every
 * present/absent branch of {@code raw} and {@code flag} is exercised on both
 * arms, and each named accessor is asserted true and false. Each test is fully
 * isolated and asserts absolute expected values.
 */
class ProductAttributesTest {

    /**
     * Builds a product carrying one attribute value.
     *
     * @param code the attribute code
     * @param value the raw value
     * @return the product
     */
    private Product productWith(String code, String value) {
        Product product = new Product();
        product.attributes.put(code, value);
        return product;
    }

    /**
     * raw returns the product's own value when the map carries it.
     */
    @Test
    void rawReturnsProductValueWhenPresent() {
        Product product = productWith(ProductAttributeCatalog.VAT_EXEMPT, "true");
        Assertions.assertEquals("true",
                ProductAttributes.raw(product, ProductAttributeCatalog.VAT_EXEMPT));
    }

    /**
     * raw falls back to the catalog default when the product carries no value
     * for a well-known code.
     */
    @Test
    void rawFallsBackToCatalogDefaultWhenAbsent() {
        Product product = new Product();
        Assertions.assertEquals("false",
                ProductAttributes.raw(product, ProductAttributeCatalog.VAT_EXEMPT));
    }

    /**
     * raw returns null for an unknown code the product does not carry (no
     * product value, no catalog default).
     */
    @Test
    void rawReturnsNullForUnknownAbsentCode() {
        Product product = new Product();
        Assertions.assertNull(ProductAttributes.raw(product, "SOME_GESCOM_ATTR"));
    }

    /**
     * raw returns an unknown code's own value when the product carries it —
     * the open map stores attributes the register does not act upon.
     */
    @Test
    void rawReturnsUnknownCodeOwnValue() {
        Product product = productWith("SOME_GESCOM_ATTR", "42");
        Assertions.assertEquals("42", ProductAttributes.raw(product, "SOME_GESCOM_ATTR"));
    }

    /**
     * raw tolerates a null product (null-product arm), yielding the catalog
     * default.
     */
    @Test
    void rawToleratesNullProduct() {
        Assertions.assertEquals("false",
                ProductAttributes.raw(null, ProductAttributeCatalog.VAT_EXEMPT));
    }

    /**
     * raw tolerates a product whose attribute map is null (null-map arm),
     * yielding the catalog default.
     */
    @Test
    void rawToleratesNullMap() {
        Product product = new Product();
        product.attributes = null;
        Assertions.assertEquals("false",
                ProductAttributes.raw(product, ProductAttributeCatalog.VAT_EXEMPT));
    }

    /**
     * flag is true when the resolved value is "true".
     */
    @Test
    void flagIsTrueWhenValueIsTrue() {
        Product product = productWith(ProductAttributeCatalog.BULKY, "true");
        Assertions.assertTrue(ProductAttributes.flag(product, ProductAttributeCatalog.BULKY));
    }

    /**
     * flag is false when the resolved value is "false".
     */
    @Test
    void flagIsFalseWhenValueIsFalse() {
        Product product = productWith(ProductAttributeCatalog.BULKY, "false");
        Assertions.assertFalse(ProductAttributes.flag(product, ProductAttributeCatalog.BULKY));
    }

    /**
     * flag is false when the resolved value is null (unknown, uncarried code).
     */
    @Test
    void flagIsFalseWhenValueIsNull() {
        Product product = new Product();
        Assertions.assertFalse(ProductAttributes.flag(product, "SOME_GESCOM_ATTR"));
    }

    /**
     * discountForbidden reflects the DISCOUNT_FORBIDDEN attribute, both arms.
     */
    @Test
    void discountForbiddenReflectsAttribute() {
        Assertions.assertTrue(ProductAttributes.discountForbidden(
                productWith(ProductAttributeCatalog.DISCOUNT_FORBIDDEN, "true")));
        Assertions.assertFalse(ProductAttributes.discountForbidden(new Product()));
    }

    /**
     * vatExempt reflects the VAT_EXEMPT attribute, both arms.
     */
    @Test
    void vatExemptReflectsAttribute() {
        Assertions.assertTrue(ProductAttributes.vatExempt(
                productWith(ProductAttributeCatalog.VAT_EXEMPT, "true")));
        Assertions.assertFalse(ProductAttributes.vatExempt(new Product()));
    }

    /**
     * recall reflects the RECALL attribute, both arms.
     */
    @Test
    void recallReflectsAttribute() {
        Assertions.assertTrue(ProductAttributes.recall(
                productWith(ProductAttributeCatalog.RECALL, "true")));
        Assertions.assertFalse(ProductAttributes.recall(new Product()));
    }

    /**
     * The recalled lots are read in referential order, whichever separator the
     * referential used, with the blanks trimmed and a lot named twice kept once.
     */
    @Test
    void recalledLotsReadsTheReferentialList() {
        Assertions.assertEquals(java.util.List.of("L123", "L456"), ProductAttributes.recalledLots(
                productWith(ProductAttributeCatalog.RECALL_LOTS, "L123;L456")));
        Assertions.assertEquals(java.util.List.of("L123", "L456"), ProductAttributes.recalledLots(
                productWith(ProductAttributeCatalog.RECALL_LOTS, " L123 , L456 ")));
        Assertions.assertEquals(java.util.List.of("L123"), ProductAttributes.recalledLots(
                productWith(ProductAttributeCatalog.RECALL_LOTS, "L123;L123")));
    }

    /**
     * An article with no lot recall lists nothing, on every arm that can produce it:
     * no attribute at all, an empty value, and a value made only of separators.
     */
    @Test
    void recalledLotsIsEmptyWhenTheArticleCarriesNone() {
        Assertions.assertTrue(ProductAttributes.recalledLots(new Product()).isEmpty());
        Assertions.assertTrue(ProductAttributes.recalledLots(null).isEmpty());
        Assertions.assertTrue(ProductAttributes.recalledLots(
                productWith(ProductAttributeCatalog.RECALL_LOTS, "")).isEmpty());
        Assertions.assertTrue(ProductAttributes.recalledLots(
                productWith(ProductAttributeCatalog.RECALL_LOTS, " ; , ")).isEmpty());
    }

    /**
     * A lot is recalled when the referential names it, ignoring case and blanks; every
     * way the question can answer no is covered: another lot, no lot at all, a blank
     * one, and an article that recalls nothing.
     */
    @Test
    void lotRecalledAnswersOnEveryArm() {
        Product product = productWith(ProductAttributeCatalog.RECALL_LOTS, "L123;L456");
        Assertions.assertTrue(ProductAttributes.lotRecalled(product, "L123"));
        Assertions.assertTrue(ProductAttributes.lotRecalled(product, " l456 "));
        Assertions.assertFalse(ProductAttributes.lotRecalled(product, "L999"));
        Assertions.assertFalse(ProductAttributes.lotRecalled(product, null));
        Assertions.assertFalse(ProductAttributes.lotRecalled(product, "  "));
        Assertions.assertFalse(ProductAttributes.lotRecalled(new Product(), "L123"));
    }

    /**
     * The joined lots are what a cashier checks by hand on the pack, and an
     * article that recalls nothing produces nothing at all rather than an
     * empty join the screen would still show.
     */
    @Test
    void recalledLotsJoinedListsTheLotsOrSaysNothing() {
        Assertions.assertEquals("L123, L456",
                ProductAttributes.recalledLotsJoined(
                        productWith(ProductAttributeCatalog.RECALL_LOTS, "L123;L456")));
        Assertions.assertNull(ProductAttributes.recalledLotsJoined(new Product()));
    }

    /**
     * The message lists the recalled lots so a cashier can check the pack by hand, and
     * an article that recalls nothing produces no message at all rather than an empty
     * one the screen would still show.
     */
    @Test
    void recalledLotsMessageListsTheLotsOrSaysNothing() {
        Assertions.assertEquals("PRODUIT EN RAPPEL - LOTS : L123, L456",
                ProductAttributes.recalledLotsMessage(
                        productWith(ProductAttributeCatalog.RECALL_LOTS, "L123;L456")));
        Assertions.assertNull(ProductAttributes.recalledLotsMessage(new Product()));
    }

    /**
     * mealVoucherEligible reflects the MEAL_VOUCHER_ELIGIBLE attribute, both arms.
     */
    @Test
    void mealVoucherEligibleReflectsAttribute() {
        Assertions.assertTrue(ProductAttributes.mealVoucherEligible(
                productWith(ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE, "true")));
        Assertions.assertFalse(ProductAttributes.mealVoucherEligible(new Product()));
    }


    /**
     * priceToEnter reflects the PRICE_TO_ENTER attribute, both arms.
     */
    @Test
    void priceToEnterReflectsAttribute() {
        Assertions.assertTrue(ProductAttributes.priceToEnter(
                productWith(ProductAttributeCatalog.PRICE_TO_ENTER, "true")));
        Assertions.assertFalse(ProductAttributes.priceToEnter(new Product()));
    }

    /**
     * quantityToEnter reflects the QUANTITY_TO_ENTER attribute, both arms.
     */
    @Test
    void quantityToEnterReflectsAttribute() {
        Assertions.assertTrue(ProductAttributes.quantityToEnter(
                productWith(ProductAttributeCatalog.QUANTITY_TO_ENTER, "true")));
        Assertions.assertFalse(ProductAttributes.quantityToEnter(new Product()));
    }

    /**
     * legalWarranty reflects the LEGAL_WARRANTY attribute, both arms.
     */
    @Test
    void legalWarrantyReflectsAttribute() {
        Assertions.assertTrue(ProductAttributes.legalWarranty(
                productWith(ProductAttributeCatalog.LEGAL_WARRANTY, "true")));
        Assertions.assertFalse(ProductAttributes.legalWarranty(new Product()));
    }

    /**
     * ecoTax reads the article's own amount, the decimal comma of an integrated
     * file accepted as a point (BO-10-04-14).
     */
    @Test
    void ecoTaxReadsTheArticlesOwnAmount() {
        Assertions.assertEquals(new java.math.BigDecimal("0.35"),
                ProductAttributes.ecoTax(productWith(ProductAttributeCatalog.ECO_TAX, "0.35")));
        Assertions.assertEquals(new java.math.BigDecimal("1.20"),
                ProductAttributes.ecoTax(productWith(ProductAttributeCatalog.ECO_TAX, " 1,20 ")));
    }

    /**
     * ecoTax falls back to zero on an article carrying nothing (the catalog
     * default), on a null product, and on a BLANK value — the leg a null check
     * alone would miss.
     */
    @Test
    void ecoTaxIsZeroWhenTheArticleCarriesNone() {
        Assertions.assertEquals(java.math.BigDecimal.ZERO,
                ProductAttributes.ecoTax(new Product()));
        Assertions.assertEquals(java.math.BigDecimal.ZERO, ProductAttributes.ecoTax(null));
        Assertions.assertEquals(java.math.BigDecimal.ZERO,
                ProductAttributes.ecoTax(productWith(ProductAttributeCatalog.ECO_TAX, "   ")));
    }

    /**
     * decimalAttribute reads a DECLARED eco-tax attribute (BO-10-06-05), and an
     * attribute neither carried nor known to the catalog is zero.
     */
    @Test
    void decimalAttributeReadsADeclaredEcoTax() {
        Assertions.assertEquals(new java.math.BigDecimal("0.50"),
                ProductAttributes.decimalAttribute(productWith("DEEE", "0,50"), "DEEE"));
        Assertions.assertEquals(java.math.BigDecimal.ZERO,
                ProductAttributes.decimalAttribute(new Product(), "DEEE"));
    }

    /**
     * ecoTax treats an unreadable amount as none rather than raising: one
     * malformed article must not stop an invoice from being drawn.
     */
    @Test
    void ecoTaxTreatsAnUnreadableAmountAsNone() {
        Assertions.assertEquals(java.math.BigDecimal.ZERO,
                ProductAttributes.ecoTax(productWith(ProductAttributeCatalog.ECO_TAX, "gratuit")));
    }

    /**
     * {@code recalledLots} returns an empty list for an article carrying no
     * recall attribute — the null-value arm of its guard.
     */
    @Test
    void recalledLotsAreEmptyForAnArticleCarryingNone() {
        Assertions.assertTrue(ProductAttributes.recalledLots(new Product()).isEmpty());
    }

    /**
     * {@code ecoTax} treats an attribute explicitly set to null as none — the
     * null-value arm of its guard, distinct from a blank or absent value.
     */
    @Test
    void ecoTaxIsZeroForAnExplicitlyNullAttribute() {
        Assertions.assertEquals(java.math.BigDecimal.ZERO,
                ProductAttributes.ecoTax(productWith(ProductAttributeCatalog.ECO_TAX, null)));
    }

    /**
     * {@code tare} reads the article's own tare, tolerating the French decimal
     * comma and surrounding spaces (LC-14-01-07, present-value arm).
     */
    @Test
    void tareReadsTheArticlesOwnTare() {
        Assertions.assertEquals(0, new java.math.BigDecimal("0.020").compareTo(
                ProductAttributes.tare(productWith(ProductAttributeCatalog.TARE, "0.020"))));
        Assertions.assertEquals(0, new java.math.BigDecimal("0.035").compareTo(
                ProductAttributes.tare(productWith(ProductAttributeCatalog.TARE, " 0,035 "))));
    }

    /**
     * {@code tare} falls back to zero when the article carries none: an absent
     * attribute resolves through the catalog default "0", and a null product or
     * a blank value are zero too (catalog-default, null-product and blank arms).
     */
    @Test
    void tareIsZeroWhenTheArticleCarriesNone() {
        Assertions.assertEquals(0, java.math.BigDecimal.ZERO.compareTo(
                ProductAttributes.tare(new Product())));
        Assertions.assertEquals(0, java.math.BigDecimal.ZERO.compareTo(
                ProductAttributes.tare(null)));
        Assertions.assertEquals(0, java.math.BigDecimal.ZERO.compareTo(
                ProductAttributes.tare(productWith(ProductAttributeCatalog.TARE, "   "))));
    }

    /**
     * {@code tare} treats an unreadable value as none rather than raising — one
     * malformed article must not stop a weighing (catch arm).
     */
    @Test
    void tareTreatsAnUnreadableValueAsNone() {
        Assertions.assertEquals(0, java.math.BigDecimal.ZERO.compareTo(
                ProductAttributes.tare(productWith(ProductAttributeCatalog.TARE, "lourd"))));
    }

    /**
     * {@code tare} clamps a negative value to zero — a mis-integrated negative
     * tare must never inflate a weighing (negative arm).
     */
    @Test
    void tareClampsANegativeValueToZero() {
        Assertions.assertEquals(0, java.math.BigDecimal.ZERO.compareTo(
                ProductAttributes.tare(productWith(ProductAttributeCatalog.TARE, "-0.010"))));
    }
    /**
     * {@code activationRequired} is the catalog flag demanding a second scan
     * (BO-03-06-44): absent it is false, declared true it is true, and a null
     * product answers false rather than raising.
     */
    @Test
    void activationRequiredReadsTheCatalogFlag() {
        Assertions.assertFalse(ProductAttributes.activationRequired(new Product()));
        Assertions.assertFalse(ProductAttributes.activationRequired(null));
        Assertions.assertTrue(ProductAttributes.activationRequired(
                productWith(ProductAttributeCatalog.ACTIVATION_REQUIRED, "true")));
        Assertions.assertFalse(ProductAttributes.activationRequired(
                productWith(ProductAttributeCatalog.ACTIVATION_REQUIRED, "false")));
    }

    /**
     * {@code activationProvider} answers the declared provider trimmed, and
     * NULL for every way of declaring none — a store may demand the second scan
     * before it knows who will settle it (BO-03-06-44, each leg of the guard).
     */
    @Test
    void activationProviderIsNullUntilOneIsNamed() {
        Assertions.assertNull(ProductAttributes.activationProvider(new Product()));
        Assertions.assertNull(ProductAttributes.activationProvider(null));
        Assertions.assertNull(ProductAttributes.activationProvider(
                productWith(ProductAttributeCatalog.ACTIVATION_PROVIDER, "   ")));
        Assertions.assertEquals("TRANSACTIS", ProductAttributes.activationProvider(
                productWith(ProductAttributeCatalog.ACTIVATION_PROVIDER, "  TRANSACTIS  ")));
    }

    /**
     * {@code soldOn} refuses an article the store took out of THIS register or
     * THIS island, and sells everywhere otherwise (BO-03-01-18). Each leg of
     * the two exclusion lists: an empty list excludes nothing, a list naming
     * something else excludes nothing, a null register or island matches
     * nothing, and the match ignores the case and the spaces.
     */
    @Test
    void soldOnHonoursTheTwoExclusionLists() {
        Assertions.assertTrue(ProductAttributes.soldOn(new Product(), "C04", "AVANT"));
        Assertions.assertTrue(ProductAttributes.soldOn(null, "C04", "AVANT"));

        Product byTerminal = productWith(ProductAttributeCatalog.DISABLED_TERMINALS,
                " c04 ; C07 ");
        Assertions.assertFalse(ProductAttributes.soldOn(byTerminal, "C04", "AVANT"));
        Assertions.assertFalse(ProductAttributes.soldOn(byTerminal, "C07", null));
        Assertions.assertTrue(ProductAttributes.soldOn(byTerminal, "C05", "AVANT"));
        Assertions.assertTrue(ProductAttributes.soldOn(byTerminal, null, "AVANT"));

        Product byIsland = productWith(ProductAttributeCatalog.DISABLED_ISLANDS, "LIBRE");
        Assertions.assertFalse(ProductAttributes.soldOn(byIsland, "C04", "libre"));
        Assertions.assertTrue(ProductAttributes.soldOn(byIsland, "C04", "AVANT"));
        Assertions.assertTrue(ProductAttributes.soldOn(byIsland, "C04", null));

        Product blank = productWith(ProductAttributeCatalog.DISABLED_ISLANDS, "   ");
        Assertions.assertTrue(ProductAttributes.soldOn(blank, "C04", "AVANT"));
    }

    /**
     * {@code translatedLabel} answers the wording administered for a language
     * and null for every other case (BO-03-01-20): a language nobody
     * administered, a null or blank tag, a pair with no language, a pair with
     * no wording, and an empty wording. The tag match ignores the case and the
     * spaces, as a back-office entry always eventually needs.
     */
    @Test
    void translatedLabelAnswersTheAdministeredWording() {
        Product product = productWith(ProductAttributeCatalog.TRANSLATED_LABELS,
                " NL = Melk ;pt=Leite;=sans langue;de=;   ");
        Assertions.assertEquals("Melk", ProductAttributes.translatedLabel(product, "nl"));
        Assertions.assertEquals("Melk", ProductAttributes.translatedLabel(product, " NL "));
        Assertions.assertEquals("Leite", ProductAttributes.translatedLabel(product, "pt"));
        Assertions.assertNull(ProductAttributes.translatedLabel(product, "de"));
        Assertions.assertNull(ProductAttributes.translatedLabel(product, "es"));
        Assertions.assertNull(ProductAttributes.translatedLabel(product, null));
        Assertions.assertNull(ProductAttributes.translatedLabel(product, "   "));
        Assertions.assertNull(ProductAttributes.translatedLabel(new Product(), "nl"));
        Assertions.assertNull(ProductAttributes.translatedLabel(null, "nl"));
    }

    /**
     * {@code saleLabel} shows the translation when one is administered and the
     * catalog's own wording otherwise — what every caller showed before
     * translations existed (BO-03-01-20, both arms).
     */
    @Test
    void saleLabelFallsBackOnTheCatalogWording() {
        Product product = productWith(ProductAttributeCatalog.TRANSLATED_LABELS, "nl=Melk");
        product.name = "LAIT DEMI-ECREME";
        Assertions.assertEquals("Melk", ProductAttributes.saleLabel(product, "nl"));
        Assertions.assertEquals("LAIT DEMI-ECREME", ProductAttributes.saleLabel(product, "fr"));
        Assertions.assertEquals("LAIT DEMI-ECREME", ProductAttributes.saleLabel(product, null));
        Assertions.assertNull(ProductAttributes.saleLabel(null, "nl"));
    }

    /** BO-03-01: a matching language with empty wording and absent register identities preserve their fallback. */
    @org.junit.jupiter.api.Test
    void coverageEmptyTranslationAndExcludedIdentityLegs() throws Exception {
        Product product = new Product();
        product.attributes.put(ProductAttributeCatalog.TRANSLATED_LABELS, "nl=   ;pt=Leite");
        org.junit.jupiter.api.Assertions.assertNull(ProductAttributes.translatedLabel(product, "nl"));
        var method = ProductAttributes.class.getDeclaredMethod("excluded", String.class, String.class);
        method.setAccessible(true);
        org.junit.jupiter.api.Assertions.assertEquals(false, method.invoke(null, "C04", null));
        org.junit.jupiter.api.Assertions.assertEquals(false, method.invoke(null, "C04", " "));
    }

    /** BO-03: known optional attribute codes always resolve through their non-null catalog defaults. */
    @org.junit.jupiter.api.Test
    void coverageKnownCatalogDefaultsAreNonNull() {
        org.junit.jupiter.api.Assertions.assertEquals("", ProductAttributeCatalog.defaultValue(ProductAttributeCatalog.RECALL_LOTS));
        org.junit.jupiter.api.Assertions.assertEquals("", ProductAttributeCatalog.defaultValue(ProductAttributeCatalog.TRANSLATED_LABELS));
        org.junit.jupiter.api.Assertions.assertEquals("", ProductAttributeCatalog.defaultValue(ProductAttributeCatalog.ACTIVATION_PROVIDER));
        org.junit.jupiter.api.Assertions.assertEquals("0", ProductAttributeCatalog.defaultValue(ProductAttributeCatalog.ECO_TAX));
        org.junit.jupiter.api.Assertions.assertTrue(ProductAttributes.recalledLots(null).isEmpty());
        org.junit.jupiter.api.Assertions.assertNull(ProductAttributes.translatedLabel(null, "nl"));
        org.junit.jupiter.api.Assertions.assertNull(ProductAttributes.activationProvider(null));
        org.junit.jupiter.api.Assertions.assertEquals(java.math.BigDecimal.ZERO, ProductAttributes.ecoTax(null));
        org.junit.jupiter.api.Assertions.assertEquals("0", ProductAttributeCatalog.defaultValue(ProductAttributeCatalog.TARE));
        org.junit.jupiter.api.Assertions.assertEquals(java.math.BigDecimal.ZERO, ProductAttributes.tare(null));
    }

    /**
     * BO-10-07-20: the article's own message wins, trimmed; without one the
     * shop's default applies, trimmed; without either, null.
     */
    @Test
    void forbiddenSaleMessagePrefersTheArticleThenTheShop() {
        Product own = productWith(ProductAttributeCatalog.FORBIDDEN_SALE_MESSAGE, " RAPPEL LOT 12 ");
        Assertions.assertEquals("RAPPEL LOT 12", ProductAttributes.forbiddenSaleMessage(own, "DEFAUT"));
        Product blank = productWith(ProductAttributeCatalog.FORBIDDEN_SALE_MESSAGE, " ");
        Assertions.assertEquals("DEFAUT", ProductAttributes.forbiddenSaleMessage(blank, " DEFAUT "));
        Assertions.assertNull(ProductAttributes.forbiddenSaleMessage(null, null));
        Assertions.assertNull(ProductAttributes.forbiddenSaleMessage(null, "  "));
    }

    /**
     * BO-10-02-34/35: the article's flag decides; an article without one —
     * no product, no map, no value, a blank one — takes the shop's parameter.
     */
    @Test
    void codeShownFollowsTheFlagThenTheShop() {
        Assertions.assertTrue(ProductAttributes.codeShown(
                productWith(ProductAttributeCatalog.CODE_ON_SCREEN, " TRUE "), false));
        Assertions.assertFalse(ProductAttributes.codeShown(
                productWith(ProductAttributeCatalog.CODE_ON_SCREEN, "false"), true));
        Assertions.assertTrue(ProductAttributes.codeShown(
                productWith(ProductAttributeCatalog.CODE_ON_SCREEN, " "), true));
        Assertions.assertTrue(ProductAttributes.codeShown(productWith("OTHER", "x"), true));
        Assertions.assertFalse(ProductAttributes.codeShown(null, false));
        Product noMap = new Product();
        noMap.attributes = null;
        Assertions.assertTrue(ProductAttributes.codeShown(noMap, true));
    }

    /**
     * checkoutMode answers a declared mode whatever its case and blanks, and
     * null for no mode or an unknown one (BO-02-03-05, -21 to -24).
     */
    @Test
    void checkoutModeReadsADeclaredMode() {
        Assertions.assertEquals(ProductAttributeCatalog.MODE_WEIGHED,
                ProductAttributes.checkoutMode(productWith(ProductAttributeCatalog.CHECKOUT_MODE, " weighed ")));
        Assertions.assertNull(ProductAttributes.checkoutMode(productWith(ProductAttributeCatalog.CHECKOUT_MODE, "TELEPORT")));
        Assertions.assertNull(ProductAttributes.checkoutMode(new Product()));
    }

    /**
     * Each entry mode reads its flag or its checkout mode, one test per leg, and
     * the price call is the mode alone.
     */
    @Test
    void theEntryModesReadTheirFlagOrTheirMode() {
        Product none = new Product();
        Assertions.assertFalse(ProductAttributes.priceToEnter(none));
        Assertions.assertFalse(ProductAttributes.quantityToEnter(none));
        Assertions.assertFalse(ProductAttributes.decimalQuantityToEnter(none));
        Assertions.assertFalse(ProductAttributes.weighed(none));
        Assertions.assertFalse(ProductAttributes.priceCallOnly(none));
        Assertions.assertTrue(ProductAttributes.priceToEnter(productWith(ProductAttributeCatalog.PRICE_TO_ENTER, "true")));
        Assertions.assertTrue(ProductAttributes.priceToEnter(productWith(ProductAttributeCatalog.CHECKOUT_MODE, "PRICE_TO_ENTER")));
        Assertions.assertTrue(ProductAttributes.quantityToEnter(productWith(ProductAttributeCatalog.QUANTITY_TO_ENTER, "true")));
        Assertions.assertTrue(ProductAttributes.quantityToEnter(productWith(ProductAttributeCatalog.CHECKOUT_MODE, "QUANTITY_TO_ENTER")));
        Assertions.assertTrue(ProductAttributes.decimalQuantityToEnter(
                productWith(ProductAttributeCatalog.DECIMAL_QUANTITY_TO_ENTER, "true")));
        Assertions.assertTrue(ProductAttributes.decimalQuantityToEnter(
                productWith(ProductAttributeCatalog.CHECKOUT_MODE, "DECIMAL_QUANTITY_TO_ENTER")));
        Assertions.assertTrue(ProductAttributes.weighed(productWith(ProductAttributeCatalog.WEIGHED, "true")));
        Assertions.assertTrue(ProductAttributes.weighed(productWith(ProductAttributeCatalog.CHECKOUT_MODE, "WEIGHED")));
        Assertions.assertTrue(ProductAttributes.priceCallOnly(productWith(ProductAttributeCatalog.CHECKOUT_MODE, "PRICE_CALL")));
    }

    /**
     * bulky and thirdPartySale read their flags (BO-02-03-25, -41 to -43).
     */
    @Test
    void bulkyAndThirdPartyReadTheirFlags() {
        Assertions.assertTrue(ProductAttributes.bulky(productWith(ProductAttributeCatalog.BULKY, "true")));
        Assertions.assertFalse(ProductAttributes.bulky(new Product()));
        Assertions.assertTrue(ProductAttributes.thirdPartySale(productWith(ProductAttributeCatalog.THIRD_PARTY_SALE, "true")));
        Assertions.assertFalse(ProductAttributes.thirdPartySale(null));
    }

    /**
     * service reads its flag (BO-05-01-03): set, the article is a service; a
     * bare article and no article at all are goods.
     */
    @Test
    void serviceReadsItsFlag() {
        Assertions.assertTrue(ProductAttributes.service(productWith(ProductAttributeCatalog.SERVICE, "true")));
        Assertions.assertFalse(ProductAttributes.service(productWith(ProductAttributeCatalog.SERVICE, "false")));
        Assertions.assertFalse(ProductAttributes.service(new Product()));
        Assertions.assertFalse(ProductAttributes.service(null));
    }

    /**
     * linkedArticleQuantity reads a positive multiplier and reads one for an
     * absent, zero, negative or unreadable value (BO-02-03-15).
     */
    @Test
    void linkedArticleQuantityIsStrictlyPositive() {
        Assertions.assertEquals(new java.math.BigDecimal("6"),
                ProductAttributes.linkedArticleQuantity(productWith(ProductAttributeCatalog.LINKED_ARTICLE_QUANTITY, "6.0")));
        Assertions.assertEquals(java.math.BigDecimal.ONE, ProductAttributes.linkedArticleQuantity(new Product()));
        Assertions.assertEquals(java.math.BigDecimal.ONE,
                ProductAttributes.linkedArticleQuantity(productWith(ProductAttributeCatalog.LINKED_ARTICLE_QUANTITY, "0")));
        Assertions.assertEquals(java.math.BigDecimal.ONE,
                ProductAttributes.linkedArticleQuantity(productWith(ProductAttributeCatalog.LINKED_ARTICLE_QUANTITY, "x")));
    }

    /**
     * complementaryEans splits on semicolons and commas, trims, and drops
     * blanks and repeats (BO-02-03-16).
     */
    @Test
    void complementaryEansSplitsTheList() {
        Assertions.assertEquals(java.util.List.of("A", "B"), ProductAttributes.complementaryEans(
                productWith(ProductAttributeCatalog.COMPLEMENTARY_EANS, " A ;B, ;A")));
        Assertions.assertTrue(ProductAttributes.complementaryEans(new Product()).isEmpty());
    }

    /**
     * vatNumber reads an integer and answers null for a blank or unreadable
     * value (BO-02-03-27).
     */
    @Test
    void vatNumberReadsAnInteger() {
        Assertions.assertEquals(3, ProductAttributes.vatNumber(productWith(ProductAttributeCatalog.VAT_NUMBER, " 3 ")));
        Assertions.assertNull(ProductAttributes.vatNumber(new Product()));
        Assertions.assertNull(ProductAttributes.vatNumber(productWith(ProductAttributeCatalog.VAT_NUMBER, "A")));
    }

    /**
     * mergePairs poses and removes codes, skips the malformed pairs and merges
     * nothing from a null or blank text (BO-02-03-18).
     */
    @Test
    void mergePairsPosesAndRemoves() {
        java.util.Map<String, String> map = new java.util.HashMap<>(java.util.Map.of("GONE", "x"));
        Assertions.assertEquals(2, ProductAttributes.mergePairs(map, "A=1 ; GONE= ;=skip;noequals"));
        Assertions.assertEquals(java.util.Map.of("A", "1"), map);
        Assertions.assertEquals(0, ProductAttributes.mergePairs(map, null));
        Assertions.assertEquals(0, ProductAttributes.mergePairs(map, " "));
    }
}
