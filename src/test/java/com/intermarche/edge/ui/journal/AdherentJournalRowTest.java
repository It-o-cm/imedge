package com.intermarche.edge.ui.journal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for {@link AdherentJournalRow}. */
class AdherentJournalRowTest {

    /** BO-02-05-05: a row keeps every column it is given. */
    @Test
    void aRowKeepsItsColumns() {
        AdherentJournalRow row = new AdherentJournalRow(7L, "AD1", "10001", "Lyon", "POS01", "POS01-1", "123",
                "25/10/2026", "16:50", "36,00", 2, "SALE");
        assertEquals(7L, row.id);
        assertEquals("AD1", row.adherent);
        assertEquals("10001", row.pdv);
        assertEquals("Lyon", row.pdvName);
        assertEquals("POS01", row.terminal);
        assertEquals("POS01-1", row.number);
        assertEquals("123", row.cashier);
        assertEquals("25/10/2026", row.date);
        assertEquals("16:50", row.time);
        assertEquals("36,00", row.amount);
        assertEquals(2, row.itemCount);
        assertEquals("SALE", row.kind);
    }
}
