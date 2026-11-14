package com.intermarche.edge.domain.catalog;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link UnitFormat} (BO-10-08-28, BO-10-08-29): weights,
 * measures and the decomposition line, with the historical writing and an
 * administered one.
 */
class UnitFormatTest {

    /** The administered writing used by the cases: one measure decimal, two weight decimals, KG. */
    private static final UnitFormat ADMINISTERED = new UnitFormat(1, 2, "KG");

    /** A weight carries its decimals and its symbol. */
    @Test
    void weightsCarryDecimalsAndSymbol() {
        assertEquals("0,188 kg", UnitFormat.DEFAULT.weight(new BigDecimal("0.188")));
        assertEquals("0,19 KG", ADMINISTERED.weight(new BigDecimal("0.188")));
        assertEquals("0,19", ADMINISTERED.weightNumber(new BigDecimal("0.188")));
    }

    /** A whole measure keeps no decimal, any other the administered ones. */
    @Test
    void measuresKeepTheirDecimals() {
        assertEquals("2", UnitFormat.DEFAULT.measure(new BigDecimal("2.000")));
        assertEquals("2,36", UnitFormat.DEFAULT.measure(new BigDecimal("2.355")));
        assertEquals("2,4", ADMINISTERED.measure(new BigDecimal("2.355")));
    }

    /** The decomposition line of the four cases, and none for a single piece. */
    @Test
    void detailLinesFollowTheWriting() {
        assertEquals("0,750 kg x 4,00 E/kg", UnitFormat.DEFAULT.detailLine(BigDecimal.ONE, new BigDecimal("3"),
                null, true, new BigDecimal("0.75"), new BigDecimal("4"), "E"));
        assertEquals("0,19 KG x 17,99 €/KG", ADMINISTERED.detailLine(new BigDecimal("0.188"),
                new BigDecimal("17.99"), null, true, null, null, "€"));
        assertEquals("1,3 m x 12,00 €/m", ADMINISTERED.detailLine(new BigDecimal("1.25"),
                new BigDecimal("12"), "m", false, null, null, "€"));
        assertEquals("5 x 2,50 €", ADMINISTERED.detailLine(new BigDecimal("5"),
                new BigDecimal("2.5"), " ", false, null, new BigDecimal("4"), "€"));
        assertEquals("", ADMINISTERED.detailLine(BigDecimal.ONE, new BigDecimal("2.5"),
                null, false, new BigDecimal("0.5"), null, "€"));
    }

    /** No writing handed over is the historical one; one handed over stands. */
    @Test
    void orDefaultReplacesOnlyAMissingWriting() {
        assertSame(UnitFormat.DEFAULT, UnitFormat.orDefault(null));
        assertSame(ADMINISTERED, UnitFormat.orDefault(ADMINISTERED));
    }

    /**
     * LS-03-04-04: a line's quantity is a weight only on a weighing of a PLU
     * article — not without a PLU (null or empty), not on a price-embedded line,
     * not when a weight is stated beside the quantity.
     */
    @Test
    void aQuantityIsAWeightOnlyOnAWeighing() {
        org.junit.jupiter.api.Assertions.assertTrue(UnitFormat.weighedQuantity("4021", false, null));
        org.junit.jupiter.api.Assertions.assertFalse(UnitFormat.weighedQuantity(null, false, null));
        org.junit.jupiter.api.Assertions.assertFalse(UnitFormat.weighedQuantity("", false, null));
        org.junit.jupiter.api.Assertions.assertFalse(UnitFormat.weighedQuantity("230", true, null));
        org.junit.jupiter.api.Assertions.assertFalse(UnitFormat.weighedQuantity("230", false, new BigDecimal("0.512")));
    }
}
