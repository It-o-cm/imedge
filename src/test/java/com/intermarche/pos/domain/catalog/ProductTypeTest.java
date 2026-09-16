package com.intermarche.pos.domain.catalog;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProductType}, targeting 100% branch coverage.
 * <p>
 * {@code ProductType} is a plain enum with no instance or static methods, so
 * it carries no explicit branches; only the compiler-synthesized
 * {@code values()} and {@code valueOf(String)} members are exercisable. Each
 * test is fully isolated and asserts absolute expected values.
 */
class ProductTypeTest {

    /**
     * values exposes exactly the three declared constants in declaration order.
     */
    @Test
    void valuesHoldsThreeConstantsInOrder() {
        ProductType[] values = ProductType.values();
        Assertions.assertEquals(3, values.length);
        Assertions.assertEquals(ProductType.UNIT, values[0]);
        Assertions.assertEquals(ProductType.WEIGHT, values[1]);
        Assertions.assertEquals(ProductType.VOLUME, values[2]);
    }

    /**
     * Each constant reports its declared ordinal.
     */
    @Test
    void ordinalsAreStable() {
        Assertions.assertEquals(0, ProductType.UNIT.ordinal());
        Assertions.assertEquals(1, ProductType.WEIGHT.ordinal());
        Assertions.assertEquals(2, ProductType.VOLUME.ordinal());
    }

    /**
     * Each constant reports its declared name.
     */
    @Test
    void namesMatchConstants() {
        Assertions.assertEquals("UNIT", ProductType.UNIT.name());
        Assertions.assertEquals("WEIGHT", ProductType.WEIGHT.name());
        Assertions.assertEquals("VOLUME", ProductType.VOLUME.name());
    }

    /**
     * valueOf round-trips each declared name back to its constant.
     */
    @Test
    void valueOfResolvesEachConstant() {
        Assertions.assertSame(ProductType.UNIT, ProductType.valueOf("UNIT"));
        Assertions.assertSame(ProductType.WEIGHT, ProductType.valueOf("WEIGHT"));
        Assertions.assertSame(ProductType.VOLUME, ProductType.valueOf("VOLUME"));
    }

    /**
     * valueOf rejects an unknown name with IllegalArgumentException.
     */
    @Test
    void valueOfRejectsUnknownName() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ProductType.valueOf("PIECE"));
    }

    /**
     * valueOf rejects a null name with NullPointerException.
     */
    @Test
    void valueOfRejectsNullName() {
        Assertions.assertThrows(NullPointerException.class,
                () -> ProductType.valueOf(null));
    }
}
