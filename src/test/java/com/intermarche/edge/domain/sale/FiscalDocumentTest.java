package com.intermarche.edge.domain.sale;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link FiscalDocument} (LC-08-01-05, LC-08-01-06, LC-08-04-21 to -23,
 * LC-05-07-03): the printed signature characters, the lines and payment shares a
 * document states, and its finders, whose queries are asserted on a static mock.
 */
class FiscalDocumentTest {

    /**
     * Builds a line of a sale.
     *
     * @param uid the line uid
     * @param cancelled whether the line was cancelled
     * @return the line
     */
    private static TicketLine line(String uid, boolean cancelled) {
        TicketLine line = new TicketLine();
        line.lineUid = uid;
        line.cancelled = cancelled;
        return line;
    }

    /**
     * The 1st, 11th, 21st and 31st characters of a long signature are printed; a
     * shorter signature gives what it has.
     */
    @Test
    void hashCharactersTakesEveryTenthCharacterFromTheFirst() {
        FiscalDocument document = new FiscalDocument();
        document.hash = "A123456789B123456789C123456789D123456789";
        assertEquals("ABCD", document.hashCharacters());
        document.hash = "A123456789B1";
        assertEquals("AB", document.hashCharacters());
    }

    /**
     * A whole-sale document states every line still sold; a part only its own;
     * a cancelled line is never stated.
     */
    @Test
    void coversStatesTheLinesOfTheDocument() {
        FiscalDocument whole = new FiscalDocument();
        assertTrue(whole.covers(line("L1", false)));
        assertFalse(whole.covers(line("L1", true)));
        FiscalDocument part = new FiscalDocument();
        part.lineUids = "L1,L3";
        assertTrue(part.covers(line("L3", false)));
        assertFalse(part.covers(line("L2", false)));
    }

    /**
     * A whole-sale document carries no shares; a part's shares are read per
     * payment position, a malformed entry ignored.
     */
    @Test
    void sharesReadsThePaymentPositions() {
        FiscalDocument whole = new FiscalDocument();
        assertNull(whole.shares());
        FiscalDocument part = new FiscalDocument();
        part.paymentShares = "0:3.00;bad;2:5.00";
        assertEquals(Map.of(0, new BigDecimal("3.00"), 2, new BigDecimal("5.00")), part.shares());
    }

    /**
     * The active documents of a sale are its simplified invoices and invoices not
     * cancelled, in part order.
     */
    @Test
    void activeForTicketAsksForTheUncancelledSaleDocuments() {
        FiscalDocument found = new FiscalDocument();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(
                    "ticketId = ?1 and cancelled = false and documentType in ?2 order by part",
                    7L, List.of(FiscalDocument.Type.FS, FiscalDocument.Type.FT))).thenReturn(List.of(found));
            assertEquals(List.of(found), FiscalDocument.activeForTicket(7L));
        }
    }

    /**
     * BO-10-06-03: a document is found by its number, and the documents of a
     * period are listed in type, series and rank order.
     */
    @Test
    @SuppressWarnings("unchecked")
    void findByNumberAndIssuedBetweenQueryTheDocuments() {
        FiscalDocument found = new FiscalDocument();
        io.quarkus.hibernate.orm.panache.PanacheQuery<FiscalDocument> query =
                org.mockito.Mockito.mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        org.mockito.Mockito.when(query.firstResult()).thenReturn(found);
        java.time.LocalDateTime from = java.time.LocalDateTime.of(2026, 10, 1, 0, 0);
        java.time.LocalDateTime to = java.time.LocalDateTime.of(2026, 11, 1, 0, 0);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("number", "FS C04/2026/00000001")).thenReturn(query);
            panache.when(() -> PanacheEntityBase.list(
                    "issuedAt >= ?1 and issuedAt < ?2 order by documentType, series, rank", from, to))
                    .thenReturn(List.of(found));
            assertSame(found, FiscalDocument.findByNumber("FS C04/2026/00000001"));
            assertEquals(List.of(found), FiscalDocument.issuedBetween(from, to));
        }
    }

    /**
     * The credit note of a return is found by the return, null when none.
     */
    @Test
    void forRefundFindsTheCreditNoteOrNothing() {
        FiscalDocument note = new FiscalDocument();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("refundId", 3L)).thenReturn(List.of(note));
            assertSame(note, FiscalDocument.forRefund(3L));
            assertNull(FiscalDocument.forRefund(4L));
        }
    }

    /**
     * The documents referring to another are found by type and number.
     */
    @Test
    void referringFindsByTypeAndSourceNumber() {
        FiscalDocument receipt = new FiscalDocument();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("documentType = ?1 and sourceNumber = ?2",
                    FiscalDocument.Type.RC, "FT C04/2026/00000001")).thenReturn(List.of(receipt));
            assertEquals(List.of(receipt),
                    FiscalDocument.referring(FiscalDocument.Type.RC, "FT C04/2026/00000001"));
        }
    }

    /**
     * The last document of a series is the highest rank, null for a new series.
     */
    @Test
    void lastOfFindsTheHighestRankOrNothing() {
        FiscalDocument last = new FiscalDocument();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("documentType = ?1 and series = ?2 order by rank desc",
                    FiscalDocument.Type.FS, "C04/2026")).thenReturn(List.of(last));
            assertSame(last, FiscalDocument.lastOf(FiscalDocument.Type.FS, "C04/2026"));
            assertNull(FiscalDocument.lastOf(FiscalDocument.Type.FS, "C04/2027"));
        }
    }

    /**
     * A document is found by its type, register, day and rank, null when none
     * matches.
     */
    @Test
    void findIssuedLooksUpTheDayOfTheRegister() {
        FiscalDocument issued = new FiscalDocument();
        LocalDate day = LocalDate.of(2026, 10, 23);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(
                    "documentType = ?1 and terminalId = ?2 and rank = ?3 and issuedAt >= ?4 and issuedAt < ?5",
                    FiscalDocument.Type.FS, "C04", 5L, day.atStartOfDay(), day.plusDays(1).atStartOfDay()))
                    .thenReturn(List.of(issued));
            assertSame(issued, FiscalDocument.findIssued(FiscalDocument.Type.FS, "C04", day, 5L));
            assertNull(FiscalDocument.findIssued(FiscalDocument.Type.FS, "C04", day, 6L));
        }
    }

    /**
     * LC-08-06-11: the final consumer is an absent number or 999999990, with or
     * without its country prefix; any other number names someone.
     */
    @Test
    void theFinalConsumer() {
        assertTrue(FiscalDocument.isFinalConsumer(null));
        assertTrue(FiscalDocument.isFinalConsumer("  "));
        assertTrue(FiscalDocument.isFinalConsumer("999999990"));
        assertTrue(FiscalDocument.isFinalConsumer("PT 999 999 990"));
        assertFalse(FiscalDocument.isFinalConsumer("123456789"));
    }

    /**
     * LC-08-06-11: the country of a tax number is its two-letter prefix, upper
     * case, Portugal without one — a number of two characters or one starting
     * with a single letter carries none.
     */
    @Test
    void theCountryOfATaxNumber() {
        assertEquals("PT", FiscalDocument.countryOf(null));
        assertEquals("PT", FiscalDocument.countryOf("123456789"));
        assertEquals("ES", FiscalDocument.countryOf(" es B12345678"));
        assertEquals("PT", FiscalDocument.countryOf("B12345678"));
        assertEquals("PT", FiscalDocument.countryOf("FR"));
    }

    /**
     * LC-08-06-11: the number of a tax number is what follows its prefix,
     * without spaces.
     */
    @Test
    void theNumberOfATaxNumber() {
        assertEquals("", FiscalDocument.numberOf(null));
        assertEquals("123456789", FiscalDocument.numberOf(" 123 456 789 "));
        assertEquals("B12345678", FiscalDocument.numberOf("ES B12345678"));
        assertEquals("1B2345678", FiscalDocument.numberOf("1B2345678"));
        assertEquals("B12345678", FiscalDocument.numberOf("B12345678"));
    }
}
