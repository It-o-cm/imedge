package com.intermarche.pos.ui.journal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link JournalRow}.
 * <p>
 * The row is a straight value carrier with no branches: the single test pins
 * every field to its constructor argument.
 */
class JournalRowTest {

    /**
     * The constructor stores every column verbatim.
     */
    @Test
    void constructorStoresEveryColumn() {
        JournalRow row = new JournalRow(7L, "C04", "C04-00000123", "12341234",
                "31/08/2026", "14:30", "12,50", 3, "N", "N");
        assertEquals(7L, row.id);
        assertEquals("C04", row.terminal);
        assertEquals("C04-00000123", row.transaction);
        assertEquals("12341234", row.cashier);
        assertEquals("31/08/2026", row.date);
        assertEquals("14:30", row.time);
        assertEquals("12,50", row.amount);
        assertEquals(3, row.itemCount);
        assertEquals("N", row.trainingMode);
        assertEquals("N", row.autonomous);
    }
}
