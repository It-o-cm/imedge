package com.intermarche.pos.domain.safe;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link SafeVatLine}.
 *
 * <p>A line states what the PIECE states and recomputes nothing. The two null
 * guards of the total are covered on both arms, and so are the two legs of the
 * emptiness test — a line stating only a base and a line stating only a VAT are
 * both stated, which is what keeps a half-filled entry from being dropped.
 */
class SafeVatLineTest {

    /**
     * The empty constructor JPA needs leaves everything unset.
     */
    @Test
    void theEmptyConstructorLeavesEverythingUnset() {
        SafeVatLine line = new SafeVatLine();
        assertNull(line.rate);
        assertNull(line.base);
        assertNull(line.amount);
        assertFalse(line.isStated());
    }

    /**
     * The total is the base plus its VAT, as the piece states them.
     */
    @Test
    void theTotalIsTheBasePlusItsVat() {
        SafeVatLine line = new SafeVatLine(new BigDecimal("20.00"), new BigDecimal("100.00"),
                new BigDecimal("20.00"));
        assertEquals(new BigDecimal("120.00"), line.getTotal());
    }

    /**
     * A missing base or a missing VAT counts as zero — both null arms of the
     * total, and both together.
     */
    @Test
    void amissingBaseOrVatCountsAsZero() {
        assertEquals(new BigDecimal("20.00"),
                new SafeVatLine(null, null, new BigDecimal("20.00")).getTotal());
        assertEquals(new BigDecimal("100.00"),
                new SafeVatLine(null, new BigDecimal("100.00"), null).getTotal());
        assertEquals(BigDecimal.ZERO, new SafeVatLine(null, null, null).getTotal());
    }

    /**
     * A line is stated as soon as it carries a base OR a VAT — the two legs of
     * the compound guard, each alone.
     */
    @Test
    void alineIsStatedAsSoonAsItCarriesOneOfTheTwo() {
        assertTrue(new SafeVatLine(null, new BigDecimal("100.00"), null).isStated());
        assertTrue(new SafeVatLine(null, null, new BigDecimal("20.00")).isStated());
        assertTrue(new SafeVatLine(new BigDecimal("20.00"), new BigDecimal("100.00"),
                new BigDecimal("20.00")).isStated());
    }

    /**
     * A line carrying only a RATE states nothing: a rate with no figure under it
     * is a row the operator started and left, not a VAT the movement bears.
     */
    @Test
    void alineCarryingOnlyARateStatesNothing() {
        assertFalse(new SafeVatLine(new BigDecimal("20.00"), null, null).isStated());
        assertFalse(new SafeVatLine(new BigDecimal("20.00"), BigDecimal.ZERO, BigDecimal.ZERO)
                .isStated());
    }

    /**
     * The rate is formatted with two decimals for a template, and an absent one
     * is an empty string rather than a zero it does not bear.
     */
    @Test
    void theRateIsFormattedOrEmpty() {
        assertEquals("20.00", new SafeVatLine(new BigDecimal("20"), null, null).getRateFormatted());
        assertEquals("5.50",
                new SafeVatLine(new BigDecimal("5.5"), null, null).getRateFormatted());
        assertEquals("", new SafeVatLine(null, null, null).getRateFormatted());
    }
}
