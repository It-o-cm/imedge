package com.intermarche.pos.domain.catalog.attribute;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProductAttributeType}, targeting 100% branch coverage.
 * <p>
 * A plain enum with no instance or static methods; only the compiler-synthesized
 * {@code values()} and {@code valueOf(String)} members are exercisable. Each test
 * is fully isolated and asserts absolute expected values.
 */
class ProductAttributeTypeTest {

    /**
     * values exposes exactly the four declared constants in declaration order.
     */
    @Test
    void valuesHoldsFourConstantsInOrder() {
        ProductAttributeType[] values = ProductAttributeType.values();
        Assertions.assertEquals(4, values.length);
        Assertions.assertEquals(ProductAttributeType.BOOL, values[0]);
        Assertions.assertEquals(ProductAttributeType.INT, values[1]);
        Assertions.assertEquals(ProductAttributeType.DECIMAL, values[2]);
        Assertions.assertEquals(ProductAttributeType.TEXT, values[3]);
    }

    /**
     * valueOf round-trips each declared name back to its constant.
     */
    @Test
    void valueOfResolvesEachConstant() {
        Assertions.assertSame(ProductAttributeType.BOOL, ProductAttributeType.valueOf("BOOL"));
        Assertions.assertSame(ProductAttributeType.INT, ProductAttributeType.valueOf("INT"));
        Assertions.assertSame(ProductAttributeType.DECIMAL, ProductAttributeType.valueOf("DECIMAL"));
        Assertions.assertSame(ProductAttributeType.TEXT, ProductAttributeType.valueOf("TEXT"));
    }

    /**
     * valueOf rejects an unknown name with IllegalArgumentException.
     */
    @Test
    void valueOfRejectsUnknownName() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ProductAttributeType.valueOf("MONEY"));
    }
}
