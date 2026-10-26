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
     * eco-tax and the software tare, which carry amounts
     * ({@code BO-10-04-14}, {@code LC-14-01-07}, {@code BO-03-06-44}).
     */
    @Test
    void catalogDeclaresTwelveWellKnownAttributes() {
        Assertions.assertEquals(34, ProductAttributeCatalog.CATALOG.size());
        // Lot 20: the supplier's article name closes the lot-20 catalog (BO-02-03-30).
        Assertions.assertEquals(ProductAttributeCatalog.SUPPLIER_ARTICLE_NAME,
                ProductAttributeCatalog.CATALOG.get(32).code());
        // Lot 21: the service nature closes the catalog, a BOOL defaulting to
        // false (BO-05-01-03).
        Assertions.assertEquals(ProductAttributeCatalog.SERVICE,
                ProductAttributeCatalog.CATALOG.get(33).code());
        Assertions.assertEquals(ProductAttributeType.BOOL, ProductAttributeCatalog.def(ProductAttributeCatalog.SERVICE).type());
        Assertions.assertEquals("false", ProductAttributeCatalog.defaultValue(ProductAttributeCatalog.SERVICE));
        Assertions.assertEquals(ProductAttributeCatalog.CHECKOUT_MODE,
                ProductAttributeCatalog.CATALOG.get(21).code());
        Assertions.assertEquals("1", ProductAttributeCatalog.defaultValue(ProductAttributeCatalog.LINKED_ARTICLE_QUANTITY));
        Assertions.assertEquals(5, ProductAttributeCatalog.CHECKOUT_MODES.size());
        // BO-10-07-20: the message of a forbidden sale or a recall closes the lot-19 catalog.
        Assertions.assertEquals(ProductAttributeCatalog.FORBIDDEN_SALE_MESSAGE,
                ProductAttributeCatalog.CATALOG.get(20).code());
        Assertions.assertEquals("", ProductAttributeCatalog.defaultValue(ProductAttributeCatalog.LINKED_ARTICLE_EAN));
        Assertions.assertEquals("false", ProductAttributeCatalog.defaultValue(ProductAttributeCatalog.INFO_MESSAGE_BLOCKING));
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
        Assertions.assertEquals(ProductAttributeCatalog.TARE,
                ProductAttributeCatalog.CATALOG.get(12).code());
        // The dematerialised pair closes the catalog: the flag demanding the
        // second scan, then the provider that owes the activation
        // (BO-03-06-44).
        Assertions.assertEquals(ProductAttributeCatalog.ACTIVATION_REQUIRED,
                ProductAttributeCatalog.CATALOG.get(13).code());
        Assertions.assertEquals(ProductAttributeCatalog.ACTIVATION_PROVIDER,
                ProductAttributeCatalog.CATALOG.get(14).code());
        // BO-03-01-18: the two exclusion lists close the catalog.
        Assertions.assertEquals(ProductAttributeCatalog.DISABLED_ISLANDS,
                ProductAttributeCatalog.CATALOG.get(15).code());
        Assertions.assertEquals(ProductAttributeCatalog.DISABLED_TERMINALS,
                ProductAttributeCatalog.CATALOG.get(16).code());
        // BO-03-01-20: the translated wordings close the catalog.
        Assertions.assertEquals(ProductAttributeCatalog.TRANSLATED_LABELS,
                ProductAttributeCatalog.CATALOG.get(17).code());
        for (ProductAttributeDef def : ProductAttributeCatalog.CATALOG.subList(0, 21)) {
            if (ProductAttributeCatalog.RECALL_LOTS.equals(def.code())
                    || ProductAttributeCatalog.ACTIVATION_PROVIDER.equals(def.code())
                    || ProductAttributeCatalog.DISABLED_ISLANDS.equals(def.code())
                    || ProductAttributeCatalog.DISABLED_TERMINALS.equals(def.code())
                    || ProductAttributeCatalog.TRANSLATED_LABELS.equals(def.code())
                    || ProductAttributeCatalog.LINKED_ARTICLE_EAN.equals(def.code())
                    || ProductAttributeCatalog.FORBIDDEN_SALE_MESSAGE.equals(def.code())) {
                // The TEXT attributes: a recall names lot NUMBERS, a
                // dematerialised article names its PROVIDER, and the two
                // exclusion lists name islands and registers — none is a flag.
                Assertions.assertEquals(ProductAttributeType.TEXT, def.type());
                Assertions.assertEquals("", def.defaultValue());
            } else if (ProductAttributeCatalog.ECO_TAX.equals(def.code())
                    || ProductAttributeCatalog.TARE.equals(def.code())) {
                // The two amount attributes: an eco-tax (BO-10-04-14) and a
                // software tare in kilograms (LC-14-01-07), both DECIMAL and
                // defaulting to none.
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
