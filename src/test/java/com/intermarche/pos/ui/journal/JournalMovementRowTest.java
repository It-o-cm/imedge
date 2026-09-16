package com.intermarche.pos.ui.journal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link JournalMovementRow}.
 * <p>
 * Branch enumeration (100%): the {@code cashier == null},
 * {@code reason == null} and {@code endorsedBy == null} guards are each
 * exercised on both arms — a null value becomes the empty string, a present one
 * is kept.
 */
class JournalMovementRowTest {

    /**
     * Present cashier, reason and endorsement are carried through unchanged
     * (all three non-null arms).
     */
    @Test
    void presentFieldsAreKept() {
        JournalMovementRow row = new JournalMovementRow("C04", "12341234", "WITHDRAWAL",
                "31/08/2026", "18:00", "150,00", "Prelevement coffre", "11111111");
        assertEquals("C04", row.terminal);
        assertEquals("12341234", row.cashier);
        assertEquals("WITHDRAWAL", row.type);
        assertEquals("31/08/2026", row.date);
        assertEquals("18:00", row.time);
        assertEquals("150,00", row.amount);
        assertEquals("Prelevement coffre", row.reason);
        assertEquals("11111111", row.endorsedBy);
    }

    /**
     * A null cashier, reason and endorsement all become the empty string (all
     * three null arms).
     */
    @Test
    void nullCashierReasonAndEndorsementBecomeEmpty() {
        JournalMovementRow row = new JournalMovementRow("C04", null, "DECLARATION",
                "31/08/2026", "09:00", "0,00", null, null);
        assertEquals("", row.cashier);
        assertEquals("", row.reason);
        assertEquals("", row.endorsedBy);
    }
}
