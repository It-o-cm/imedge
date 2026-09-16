package com.intermarche.pos.ui.journal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link JournalSort}.
 * <p>
 * Branch enumeration (100%): {@code fromKey} covers the null-key arm (default
 * DATE), the matched-key arm (loop returns a match) and the unknown-key arm
 * (loop exhausts, default DATE).
 */
class JournalSortTest {

    /**
     * A null key falls back to the DATE default (null arm).
     */
    @Test
    void nullKeyFallsBackToDate() {
        assertEquals(JournalSort.DATE, JournalSort.fromKey(null));
    }

    /**
     * A known key resolves to its column (matched arm).
     */
    @Test
    void knownKeyResolves() {
        assertEquals(JournalSort.AMOUNT, JournalSort.fromKey("amount"));
        assertEquals("t.totalIncludingTax", JournalSort.fromKey("amount").path);
    }

    /**
     * An unknown key falls back to the DATE default (loop-exhausted arm).
     */
    @Test
    void unknownKeyFallsBackToDate() {
        assertEquals(JournalSort.DATE, JournalSort.fromKey("nope"));
    }
}
