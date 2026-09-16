package com.intermarche.pos.domain.catalog.attribute;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProductAttributeDef}, targeting 100% branch coverage.
 * <p>
 * A record with only compiler-generated accessors and value semantics; the tests
 * assert the accessors and the record equality/hash contract. Each test is fully
 * isolated and asserts absolute expected values.
 */
class ProductAttributeDefTest {

    /**
     * The four accessors return the constructor arguments verbatim.
     */
    @Test
    void accessorsReturnComponents() {
        ProductAttributeDef def = new ProductAttributeDef("VAT_EXEMPT",
                ProductAttributeType.BOOL, "TVA exonérée", "false");
        Assertions.assertEquals("VAT_EXEMPT", def.code());
        Assertions.assertEquals(ProductAttributeType.BOOL, def.type());
        Assertions.assertEquals("TVA exonérée", def.label());
        Assertions.assertEquals("false", def.defaultValue());
    }

    /**
     * Two definitions with identical components are equal and share a hash.
     */
    @Test
    void equalsAndHashCodeHonourValueSemantics() {
        ProductAttributeDef a = new ProductAttributeDef("BULKY",
                ProductAttributeType.BOOL, "Encombrant", "false");
        ProductAttributeDef b = new ProductAttributeDef("BULKY",
                ProductAttributeType.BOOL, "Encombrant", "false");
        Assertions.assertEquals(a, b);
        Assertions.assertEquals(a.hashCode(), b.hashCode());
    }

    /**
     * A definition differing in any component is not equal.
     */
    @Test
    void equalsRejectsDifferentComponent() {
        ProductAttributeDef a = new ProductAttributeDef("BULKY",
                ProductAttributeType.BOOL, "Encombrant", "false");
        ProductAttributeDef b = new ProductAttributeDef("BULKY",
                ProductAttributeType.BOOL, "Encombrant", "true");
        Assertions.assertNotEquals(a, b);
    }
}
