package com.intermarche.edge.domain.store;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CashFunction} (BO-03-10-16/17): the labels and paths
 * of the keys, the tolerant name resolution and the list parsing — null,
 * blank, unknown, duplicate and readable entries.
 */
class CashFunctionTest {

    /** A function carries its label and every path its key leads to. */
    @Test
    void aFunctionCarriesItsLabelAndPaths() {
        assertEquals("Mise en attente", CashFunction.PARK.getLabel());
        assertEquals(List.of("/parked", "/action/parked/park"), CashFunction.PARK.getPaths());
        assertEquals(List.of("/abandon"), CashFunction.ABANDON.getPaths());
        for (CashFunction function : CashFunction.values()) {
            assertTrue(!function.getPaths().isEmpty());
        }
    }

    /** A name resolves whatever its case and blanks; null, blank and unknown names do not. */
    @Test
    void namesResolveTolerantly() {
        assertSame(CashFunction.ABANDON, CashFunction.fromName("  abandon "));
        assertNull(CashFunction.fromName(null));
        assertNull(CashFunction.fromName("  "));
        assertNull(CashFunction.fromName("FLY"));
    }

    /** A list keeps its readable entries once, in order; null reads as empty. */
    @Test
    void listsAreParsedInOrderWithoutDuplicates() {
        assertEquals(List.of(CashFunction.ABANDON, CashFunction.LOCK),
                CashFunction.parseList("abandon;;FLY;LOCK;abandon"));
        assertTrue(CashFunction.parseList(null).isEmpty());
    }
}
