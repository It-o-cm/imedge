package com.intermarche.edge.ui.journal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Unit tests for {@link AdherentJournalDetail}. */
class AdherentJournalDetailTest {

    /** BO-02-05-05: a detail keeps its origin around the ticket detail. */
    @Test
    void aDetailKeepsItsOrigin() {
        JournalTicketDetail ticket = new JournalTicketDetail("N", "T", "CLOSED", "d", "t", "c", "b", "s", "f",
                "1", "2", "3", List.of(), List.of(), List.of());
        AdherentJournalDetail detail = new AdherentJournalDetail("AD1", "10001", "Lyon", "SALE", "25/10/2026 16:51",
                "printed", ticket);
        assertEquals("AD1", detail.adherent);
        assertEquals("10001", detail.pdv);
        assertEquals("Lyon", detail.pdvName);
        assertEquals("SALE", detail.kind);
        assertEquals("25/10/2026 16:51", detail.receivedAt);
        assertEquals("printed", detail.printed);
        assertSame(ticket, detail.ticket);
    }
}
