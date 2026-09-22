package com.intermarche.pos.domain.catalog.attribute;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProductAttributeCatalog}, targeting 100% branch coverage.
 * <p>
 * Exercises the lookup helpers on both arms of every null and known/unknown
 * guard, and asserts the catalog's declared content. Each test is fully isolated
 * and asserts absolute expected values.
 */
class ProductAttributeCatalogTest {

    /**
     * The catalog declares the twelve well-known behavioural attributes, in order —
     * every one a BOOL defaulting to false, except the recalled lots, which carry lot
     * numbers and default to nothing, and the eco-tax, which carries an amount
     * ({@code BO-10-04-14}).
     */
    @Test
    void catalogDeclaresTwelveWellKnownAttributes() {
        Assertions.assertEquals(12, ProductAttributeCatalog.CATALOG.size());
        Assertions.assertEquals(ProductAttributeCatalog.DISCOUNT_FORBIDDEN,
                ProductAttributeCatalog.CATALOG.get(0).code());
        Assertions.assertEquals(ProductAttributeCatalog.VAT_EXEMPT,
                ProductAttributeCatalog.CATALOG.get(1).code());
        Assertions.assertEquals(ProductAttributeCatalog.RECALL,
                ProductAttributeCatalog.CATALOG.get(2).code());
        // The recalled lots sit right after the blanket recall they refine
        // (LC-02-03-12/13).
        Assertions.assertEquals(ProductAttributeCatalog.RECALL_LOTS,
                ProductAttributeCatalog.CATALOG.get(3).code());
        // The three restricted-tender eligibilities sit together, right after the
        // meal voucher they generalize (LC-09-01-11 to -18).
        Assertions.assertEquals(ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE,
                ProductAttributeCatalog.CATALOG.get(4).code());
        Assertions.assertEquals(ProductAttributeCatalog.ECO_VOUCHER_ELIGIBLE,
                ProductAttributeCatalog.CATALOG.get(5).code());
        Assertions.assertEquals(ProductAttributeCatalog.SOCIAL_CARD_ELIGIBLE,
                ProductAttributeCatalog.CATALOG.get(6).code());
        for (ProductAttributeDef def : ProductAttributeCatalog.CATALOG) {
            if (ProductAttributeCatalog.RECALL_LOTS.equals(def.code())) {
                // One of the two non-booleans of the catalog: a recall names lot
                // NUMBERS, and a flag could not carry them.
                Assertions.assertEquals(ProductAttributeType.TEXT, def.type());
                Assertions.assertEquals("", def.defaultValue());
            } else if (ProductAttributeCatalog.ECO_TAX.equals(def.code())) {
                // The other one: an eco-tax is an AMOUNT, defaulting to none
                // (BO-10-04-14).
                Assertions.assertEquals(ProductAttributeType.DECIMAL, def.type());
                Assertions.assertEquals("0", def.defaultValue());
            } else {
                Assertions.assertEquals(ProductAttributeType.BOOL, def.type());
                Assertions.assertEquals("false", def.defaultValue());
            }
        }
    }

    /**
     * def returns the matching definition for a well-known code.
     */
    @Test
    void defResolvesKnownCode() {
        ProductAttributeDef def = ProductAttributeCatalog.def(ProductAttributeCatalog.VAT_EXEMPT);
        Assertions.assertNotNull(def);
        Assertions.assertEquals(ProductAttributeCatalog.VAT_EXEMPT, def.code());
    }

    /**
     * def returns null for a code the catalog does not declare (unknown arm).
     */
    @Test
    void defReturnsNullForUnknownCode() {
        Assertions.assertNull(ProductAttributeCatalog.def("SOME_GESCOM_ATTR"));
    }

    /**
     * def returns null for a null code (null arm), never throwing.
     */
    @Test
    void defReturnsNullForNullCode() {
        Assertions.assertNull(ProductAttributeCatalog.def(null));
    }

    /**
     * isKnown is true for a declared code and false for an undeclared one.
     */
    @Test
    void isKnownDistinguishesDeclaredCodes() {
        Assertions.assertTrue(ProductAttributeCatalog.isKnown(ProductAttributeCatalog.BULKY));
        Assertions.assertFalse(ProductAttributeCatalog.isKnown("SOME_GESCOM_ATTR"));
        Assertions.assertFalse(ProductAttributeCatalog.isKnown(null));
    }

    /**
     * defaultValue returns the declared default for a known code.
     */
    @Test
    void defaultValueReturnsDeclaredDefaultForKnownCode() {
        Assertions.assertEquals("false",
                ProductAttributeCatalog.defaultValue(ProductAttributeCatalog.PRICE_TO_ENTER));
    }

    /**
     * defaultValue returns null for an unknown code (unknown arm).
     */
    @Test
    void defaultValueReturnsNullForUnknownCode() {
        Assertions.assertNull(ProductAttributeCatalog.defaultValue("SOME_GESCOM_ATTR"));
    }
}
