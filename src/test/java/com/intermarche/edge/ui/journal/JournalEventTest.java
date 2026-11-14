package com.intermarche.edge.ui.journal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link JournalEvent} (BO-04-01-14).
 */
class JournalEventTest {

    /**
     * The fifteen events of the requirement, all ranged but the training and
     * the offline sale.
     */
    @Test
    void theFifteenEvents() {
        assertEquals(15, JournalEvent.values().length);
        assertTrue(JournalEvent.CANCELLED.isAmountRange());
        assertFalse(JournalEvent.TRAINING.isAmountRange());
        assertFalse(JournalEvent.OFFLINE.isAmountRange());
    }

    /**
     * A name reads its event, trimmed; a blank, null or unknown one reads as none.
     */
    @Test
    void ofReadsTheName() {
        assertEquals(JournalEvent.NO_SALE, JournalEvent.of(" NO_SALE "));
        assertNull(JournalEvent.of(null));
        assertNull(JournalEvent.of("  "));
        assertNull(JournalEvent.of("NOPE"));
    }
}
