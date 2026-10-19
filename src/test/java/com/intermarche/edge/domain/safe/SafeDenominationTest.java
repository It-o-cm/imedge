package com.intermarche.edge.domain.safe;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link SafeDenomination}.
 *
 * <p>Pure enum, no Panache and no CDI. What is proved here is what the safe
 * counts in: the catalogue is the euro area's and is wider than the drawer's on
 * purpose, the value of a count is the unit times the quantity with both null
 * and non-positive arms covered, and the name lookup answers on every leg of its
 * guard.
 */
class SafeDenominationTest {

    /**
     * The catalogue holds the seven notes and the eight coins of the euro area,
     * largest first.
     */
    @Test
    void theCatalogueHoldsSevenNotesAndEightCoins() {
        assertEquals(15, SafeDenomination.values().length);
        List<SafeDenomination> notes = SafeDenomination.notes();
        List<SafeDenomination> coins = SafeDenomination.coins();
        assertEquals(7, notes.size());
        assertEquals(8, coins.size());
        assertSame(SafeDenomination.N500, notes.get(0));
        assertSame(SafeDenomination.N5, notes.get(notes.size() - 1));
        assertSame(SafeDenomination.C2, coins.get(0));
        assertSame(SafeDenomination.C001, coins.get(coins.size() - 1));
    }

    /**
     * The safe counts notes the drawer never keeps: the requirement's coin
     * deliveries come back in whatever the bank sends.
     */
    @Test
    void theSafeCountsNotesTheDrawerNeverKeeps() {
        assertTrue(SafeDenomination.N500.isNote());
        assertTrue(SafeDenomination.N200.isNote());
        assertTrue(SafeDenomination.N100.isNote());
        assertFalse(SafeDenomination.C2.isNote());
    }

    /**
     * Each denomination carries its unit value and its label.
     */
    @Test
    void eachDenominationCarriesItsValueAndLabel() {
        assertEquals(new BigDecimal("50.00"), SafeDenomination.N50.getValue());
        assertEquals(new BigDecimal("0.05"), SafeDenomination.C005.getValue());
        assertEquals("Billet 50 €", SafeDenomination.N50.getLabel());
        assertEquals("Pièce 0,05 €", SafeDenomination.C005.getLabel());
    }

    /**
     * A count is worth the unit times the quantity.
     */
    @Test
    void acountIsWorthTheUnitTimesTheQuantity() {
        assertEquals(new BigDecimal("150.00"), SafeDenomination.N50.amountOf(3));
        assertEquals(new BigDecimal("0.10"), SafeDenomination.C005.amountOf(2));
    }

    /**
     * A null, zero or negative quantity is worth nothing — the three legs of the
     * guard, which a single null check would leave half-covered.
     */
    @Test
    void anAbsentOrNonPositiveQuantityIsWorthNothing() {
        assertEquals(BigDecimal.ZERO, SafeDenomination.N50.amountOf(null));
        assertEquals(BigDecimal.ZERO, SafeDenomination.N50.amountOf(0));
        assertEquals(BigDecimal.ZERO, SafeDenomination.N50.amountOf(-2));
    }

    /**
     * The name lookup resolves a stored name, padding tolerated, and answers
     * null on a null or unknown one — the three legs of its guard.
     */
    @Test
    void theNameLookupResolvesAStoredNameAndNothingElse() {
        assertSame(SafeDenomination.N20, SafeDenomination.fromName("N20"));
        assertSame(SafeDenomination.N20, SafeDenomination.fromName("  N20  "));
        assertNull(SafeDenomination.fromName(null));
        assertNull(SafeDenomination.fromName("N1000"));
    }
}
