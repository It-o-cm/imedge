package com.intermarche.edge.domain.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link PromotionChannel}, targeting 100% branch coverage.
 * <p>
 * Branch enumeration: {@code getLabel} on a constant; {@code fromName} covers the
 * null arm, the unknown arm and the matched arm (trimming included).
 */
class PromotionChannelTest {

    /**
     * A constant carries its operator-facing label.
     */
    @Test
    void aConstantCarriesItsLabel() {
        assertEquals("Point de vente", PromotionChannel.POINT_OF_SALE.getLabel());
        assertEquals("Web", PromotionChannel.WEB.getLabel());
    }

    /**
     * {@code fromName} resolves a known name (trimming it), and answers null for a
     * null or an unknown one.
     */
    @Test
    void fromNameResolvesTrimsOrAnswersNull() {
        assertSame(PromotionChannel.OTHER, PromotionChannel.fromName(" OTHER "));
        assertNull(PromotionChannel.fromName(null));
        assertNull(PromotionChannel.fromName("UNKNOWN"));
    }
}
