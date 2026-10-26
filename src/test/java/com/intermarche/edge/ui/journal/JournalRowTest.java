package com.intermarche.edge.ui.journal;

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

    /**
     * The VAT ventilation splits into one sentence per rate, and a ticket
     * without ventilation has no part; each part reads in Dutch through the
     * bundles (BO-04-01-55).
     */
    @Test
    void theVatVentilationSplitsPerRate() {
        JournalRow row = new JournalRow(7L, "C04", "C04-00000123", "12341234",
                "31/08/2026", "14:30", "12,50", 3, "N", "N");
        assertEquals(java.util.List.of(), row.getVatParts());
        row.vat = "5,5 % HT 4,74 TVA 0,26 | 20 % HT 3,20 TVA 0,64";
        assertEquals(java.util.List.of("5,5 % HT 4,74 TVA 0,26", "20 % HT 3,20 TVA 0,64"), row.getVatParts());
        assertEquals("20 % excl. btw 3,20 btw 0,64",
                com.intermarche.edge.ui.admin.BackOfficeMessages.in("nl").text(row.getVatParts().get(1)));
    }
}
