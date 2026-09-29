package com.intermarche.pos.domain.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link DisplayType}, targeting 100% branch coverage.
 * <p>
 * Branch enumeration: {@code getLabel} on a constant; {@code fromName} covers the
 * null arm, the unknown arm and the matched arm (trimming included).
 */
class DisplayTypeTest {

    /**
     * A constant carries its operator-facing label.
     */
    @Test
    void aConstantCarriesItsLabel() {
        assertEquals("Aucun", DisplayType.NONE.getLabel());
        assertEquals("Écran tactile", DisplayType.TOUCH.getLabel());
    }

    /**
     * {@code fromName} resolves a known name (trimming it), and answers null for a
     * null or an unknown one.
     */
    @Test
    void fromNameResolvesTrimsOrAnswersNull() {
        assertSame(DisplayType.GRAPHIC, DisplayType.fromName(" GRAPHIC "));
        assertNull(DisplayType.fromName(null));
        assertNull(DisplayType.fromName("UNKNOWN"));
    }
}
