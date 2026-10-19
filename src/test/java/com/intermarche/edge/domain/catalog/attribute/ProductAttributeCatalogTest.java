package com.intermarche.edge.domain.catalog.attribute;

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
     * The catalog declares the well-known behavioural attributes, in order —
     * every one a BOOL defaulting to false, except the recalled lots and the
     * dematerialised provider, which carry text and default to nothing, and the
     * eco-tax, which carries an amount ({@code BO-10-04-14},
     * {@code BO-03-06-44}).
     */
    @Test
    void catalogDeclaresTwelveWellKnownAttributes() {
        Assertions.assertEquals(17, ProductAttributeCatalog.CATALOG.size());
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
        // The dematerialised pair closes the catalog: the flag demanding the
        // second scan, then the provider that owes the activation
        // (BO-03-06-44).
        Assertions.assertEquals(ProductAttributeCatalog.ACTIVATION_REQUIRED,
                ProductAttributeCatalog.CATALOG.get(12).code());
        Assertions.assertEquals(ProductAttributeCatalog.ACTIVATION_PROVIDER,
                ProductAttributeCatalog.CATALOG.get(13).code());
        // BO-03-01-18: the two exclusion lists close the catalog.
        Assertions.assertEquals(ProductAttributeCatalog.DISABLED_ISLANDS,
                ProductAttributeCatalog.CATALOG.get(14).code());
        Assertions.assertEquals(ProductAttributeCatalog.DISABLED_TERMINALS,
                ProductAttributeCatalog.CATALOG.get(15).code());
        // BO-03-01-20: the translated wordings close the catalog.
        Assertions.assertEquals(ProductAttributeCatalog.TRANSLATED_LABELS,
                ProductAttributeCatalog.CATALOG.get(16).code());
        for (ProductAttributeDef def : ProductAttributeCatalog.CATALOG) {
            if (ProductAttributeCatalog.RECALL_LOTS.equals(def.code())
                    || ProductAttributeCatalog.ACTIVATION_PROVIDER.equals(def.code())
                    || ProductAttributeCatalog.DISABLED_ISLANDS.equals(def.code())
                    || ProductAttributeCatalog.DISABLED_TERMINALS.equals(def.code())
                    || ProductAttributeCatalog.TRANSLATED_LABELS.equals(def.code())) {
                // The TEXT attributes: a recall names lot NUMBERS, a
                // dematerialised article names its PROVIDER, and the two
                // exclusion lists name islands and registers — none is a flag.
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
