package com.intermarche.pos.domain.catalog.attribute;

import com.intermarche.pos.domain.catalog.Product;
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
     * bulky reflects the BULKY attribute, both arms.
     */
    @Test
    void bulkyReflectsAttribute() {
        Assertions.assertTrue(ProductAttributes.bulky(
                productWith(ProductAttributeCatalog.BULKY, "true")));
        Assertions.assertFalse(ProductAttributes.bulky(new Product()));
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
}
