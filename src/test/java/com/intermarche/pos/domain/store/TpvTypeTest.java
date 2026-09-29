package com.intermarche.pos.domain.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TpvType}, targeting 100% branch coverage.
 * <p>
 * Branch enumeration: {@code getLabel} on a constant; {@code isSelfService}
 * covers the manned arm and the self-service arm; {@code fromName} covers the
 * null arm, the unknown arm and the matched arm (trimming included).
 */
class TpvTypeTest {

    /**
     * Each constant carries its operator-facing label.
     */
    @Test
    void eachConstantCarriesItsLabel() {
        assertEquals("Caisse", TpvType.CASH_REGISTER.getLabel());
        assertEquals("Libre-service TGCS", TpvType.SELF_CHECKOUT_TGCS.getLabel());
    }

    /**
     * A manned till is not self-service; every self-service family is.
     */
    @Test
    void selfServiceIsTrueForEveryFamilyButTheMannedTill() {
        assertFalse(TpvType.CASH_REGISTER.isSelfService());
        assertTrue(TpvType.SELF_CHECKOUT_TGCS.isSelfService());
        assertTrue(TpvType.SELF_CHECKOUT_DN.isSelfService());
        assertTrue(TpvType.SELF_CHECKOUT_NCR.isSelfService());
    }

    /**
     * {@code fromName} resolves a known name (trimming it), and answers null for a
     * null or an unknown one.
     */
    @Test
    void fromNameResolvesTrimsOrAnswersNull() {
        assertSame(TpvType.SELF_CHECKOUT_NCR, TpvType.fromName(" SELF_CHECKOUT_NCR "));
        assertNull(TpvType.fromName(null));
        assertNull(TpvType.fromName("UNKNOWN"));
    }
}
