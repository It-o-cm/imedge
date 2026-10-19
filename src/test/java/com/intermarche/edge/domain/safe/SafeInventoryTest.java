package com.intermarche.edge.domain.safe;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link SafeInventory}.
 *
 * <p>A count observes; writing the discrepancy off is a movement. Both arms of
 * {@code isBalanced} are covered, including the null variance a half-built row
 * carries, and {@code isAdjustable} on all four combinations of its two legs —
 * balanced or not, crossed with written off or not.
 */
class SafeInventoryTest {

    /**
     * Builds a count of two denominations.
     *
     * @return the inventory, its totals not yet computed
     */
    private SafeInventory inventory() {
        SafeInventory inventory = new SafeInventory();
        inventory.countedAt = LocalDateTime.of(2026, 9, 16, 18, 0);
        inventory.lines = new ArrayList<>(List.of(
                new SafeInventoryLine(SafeDenomination.N50, 10),
                new SafeInventoryLine(SafeDenomination.C1, 25)));
        return inventory;
    }

    /**
     * The counted total is the sum of the lines.
     */
    @Test
    void thecountedTotalIsTheSumOfTheLines() {
        assertEquals(new BigDecimal("525.00"), inventory().countedTotal());
    }

    /**
     * A count with NO lines totals nothing — the null arm of the guard.
     */
    @Test
    void acountWithoutLinesTotalsNothing() {
        SafeInventory inventory = inventory();
        inventory.lines = null;
        assertEquals(BigDecimal.ZERO, inventory.countedTotal());
    }

    /**
     * A count matching the ledger is balanced, including a row whose variance
     * was never written — the two arms of the guard.
     */
    @Test
    void acountMatchingTheLedgerIsBalanced() {
        SafeInventory inventory = inventory();
        assertEquals(BigDecimal.ZERO, inventory.variance);
        assertTrue(inventory.isBalanced());
        inventory.variance = null;
        assertTrue(inventory.isBalanced());
        inventory.variance = new BigDecimal("-15.00");
        assertFalse(inventory.isBalanced());
    }

    /**
     * A standing discrepancy may be written off; a balanced count and an already
     * corrected one may not — the two legs of the guard, each alone.
     */
    @Test
    void onlyAstandingDiscrepancyIsAdjustable() {
        SafeInventory balanced = inventory();
        assertFalse(balanced.isAdjustable());
        SafeInventory standing = inventory();
        standing.variance = new BigDecimal("-15.00");
        assertTrue(standing.isAdjustable());
        standing.adjustment = new SafeMovement();
        assertFalse(standing.isAdjustable());
    }

    /**
     * The three amounts are formatted with two decimals, and a row carrying none
     * reads as zero rather than exploding — the null arms of the three guards.
     */
    @Test
    void theamountsAreFormattedOrReadAsZero() {
        SafeInventory inventory = inventory();
        inventory.countedAmount = new BigDecimal("525");
        inventory.theoreticalAmount = new BigDecimal("540");
        inventory.variance = new BigDecimal("-15");
        assertEquals("525.00", inventory.getCountedAmountFormatted());
        assertEquals("540.00", inventory.getTheoreticalAmountFormatted());
        assertEquals("-15.00", inventory.getVarianceFormatted());
        inventory.countedAmount = null;
        inventory.theoreticalAmount = null;
        inventory.variance = null;
        assertEquals("0.00", inventory.getCountedAmountFormatted());
        assertEquals("0.00", inventory.getTheoreticalAmountFormatted());
        assertEquals("0.00", inventory.getVarianceFormatted());
    }

    /**
     * The history finder asks for every count, newest first.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thehistoryFinderAsksForEveryCountNewestFirst() {
        SafeInventory inventory = inventory();
        PanacheQuery<SafeInventory> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(inventory));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> SafeInventory.find("order by countedAt desc, id desc"))
                    .thenReturn(query);
            assertEquals(List.of(inventory), SafeInventory.listNewestFirst());
        }
    }

    /**
     * The checksum hashes the identifying and financial fields, the lines counted
     * rather than hashed — both arms of that guard.
     */
    @Test
    void thechecksumHashesTheSalientFields() {
        SafeInventory inventory = inventory();
        inventory.comment = "Contrôle hebdomadaire";
        int expected = Objects.hash(LocalDateTime.of(2026, 9, 16, 18, 0), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, "Contrôle hebdomadaire", 2);
        assertEquals(expected, inventory.getChecksum());
        inventory.lines = null;
        int withoutLines = Objects.hash(LocalDateTime.of(2026, 9, 16, 18, 0), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, "Contrôle hebdomadaire", 0);
        assertEquals(withoutLines, inventory.getChecksum());
    }
}
