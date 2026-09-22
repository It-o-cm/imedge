package com.intermarche.pos.domain.safe;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests of {@link SafeInventoryLine}.
 *
 * <p>One counted denomination. Both arms of the denomination guard are covered,
 * and the amount is asserted on a real count as well as on an empty one — a
 * declared zero is a statement, not an absence.
 */
class SafeInventoryLineTest {

    /**
     * The empty constructor JPA needs leaves everything unset.
     */
    @Test
    void theEmptyConstructorLeavesEverythingUnset() {
        SafeInventoryLine line = new SafeInventoryLine();
        assertNull(line.denomination);
        assertEquals(0, line.quantity);
        assertEquals("", line.getDenominationLabel());
        assertEquals(BigDecimal.ZERO, line.amount());
    }

    /**
     * A counted line is worth the denomination times the quantity.
     */
    @Test
    void acountedLineIsWorthTheDenominationTimesTheQuantity() {
        SafeInventoryLine line = new SafeInventoryLine(SafeDenomination.N20, 7);
        assertEquals(new BigDecimal("140.00"), line.amount());
        assertEquals("140.00", line.getAmountFormatted());
        assertEquals("Billet 20 €", line.getDenominationLabel());
    }

    /**
     * A declared ZERO is worth nothing but is still a line: an inventory of an
     * empty safe must be recordable.
     */
    @Test
    void adeclaredZeroIsWorthNothingAndIsStillALine() {
        SafeInventoryLine line = new SafeInventoryLine(SafeDenomination.N50, 0);
        assertEquals(BigDecimal.ZERO, line.amount());
        assertEquals("0.00", line.getAmountFormatted());
    }

    /**
     * A line naming NO denomination is worth nothing — the null arm of the
     * guard, which a malformed row must not make explode.
     */
    @Test
    void alineWithoutADenominationIsWorthNothing() {
        SafeInventoryLine line = new SafeInventoryLine(null, 12);
        assertEquals(BigDecimal.ZERO, line.amount());
        assertEquals("0.00", line.getAmountFormatted());
    }
}
