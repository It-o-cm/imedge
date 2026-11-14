package com.intermarche.edge.domain.barcode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link RegisterFunction} (BO-03-06-59), targeting 100% branch
 * coverage: the name resolution (null, blank, unknown, padded lower case) and
 * the number resolution (known and unknown).
 */
class RegisterFunctionTest {

    /**
     * Every function carries its number and its label.
     */
    @Test
    void eachFunctionCarriesItsNumberAndLabel() {
        assertEquals(5, RegisterFunction.values().length);
        assertEquals(1, RegisterFunction.METROLOGY.getNumber());
        assertEquals("Contrôle métrologique (CRC balance)", RegisterFunction.METROLOGY.getLabel());
        assertEquals(2, RegisterFunction.SURVEY.getNumber());
        assertEquals("Rappel de document", RegisterFunction.RECALL.getLabel());
    }

    /**
     * A name is resolved whatever its case and padding; null, blank and unknown
     * names resolve to nothing.
     */
    @Test
    void namesAreResolved() {
        assertSame(RegisterFunction.SURVEY, RegisterFunction.byName(" survey "));
        assertNull(RegisterFunction.byName(null));
        assertNull(RegisterFunction.byName("  "));
        assertNull(RegisterFunction.byName("REBOOT"));
    }

    /**
     * A number is resolved; an unknown one resolves to nothing.
     */
    @Test
    void numbersAreResolved() {
        assertSame(RegisterFunction.PARKED_TICKETS, RegisterFunction.byNumber(4));
        assertNull(RegisterFunction.byNumber(9));
    }
}
