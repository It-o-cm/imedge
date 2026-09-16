package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.domain.sale.TicketLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link TicketLineOrder}, the administered printing order of the sale
 * ticket's articles (LC-08-01-07).
 */
class TicketLineOrderTest {

    /**
     * Builds a ticket line carrying a label and a family.
     *
     * @param label the product label, possibly null
     * @param familyLabel the family label, possibly null
     * @param familyCode the family code, possibly null
     * @return the line
     */
    private TicketLine line(String label, String familyLabel, String familyCode) {
        TicketLine line = new TicketLine();
        line.productLabel = label;
        line.familyLabel = familyLabel;
        line.familyCode = familyCode;
        return line;
    }

    /**
     * Reads the labels of a list, in order.
     *
     * @param lines the lines
     * @return their labels
     */
    private List<String> labels(List<TicketLine> lines) {
        List<String> read = new ArrayList<>();
        for (TicketLine line : lines) {
            read.add(line.productLabel);
        }
        return read;
    }

    // --------------------------------------------------
    // normalize
    // --------------------------------------------------

    /**
     * The two non-default orders are recognized.
     */
    @Test
    void normalizeKeepsTheKnownOrders() {
        assertEquals(TicketLineOrder.LABEL, TicketLineOrder.normalize("LABEL"));
        assertEquals(TicketLineOrder.FAMILY, TicketLineOrder.normalize("FAMILY"));
    }

    /**
     * Case and surrounding blanks are ignored.
     */
    @Test
    void normalizeIgnoresCaseAndBlanks() {
        assertEquals(TicketLineOrder.FAMILY, TicketLineOrder.normalize("  family "));
    }

    /**
     * The entry order is recognized as itself.
     */
    @Test
    void normalizeKeepsTheEntryOrder() {
        assertEquals(TicketLineOrder.ENTRY, TicketLineOrder.normalize("ENTRY"));
    }

    /**
     * A missing value falls back to the entry order (null arm).
     */
    @Test
    void normalizeNullFallsBackToEntry() {
        assertEquals(TicketLineOrder.ENTRY, TicketLineOrder.normalize(null));
    }

    /**
     * An unknown value falls back to the entry order — a back office that knows a
     * mode this register does not must never stop a ticket from printing.
     */
    @Test
    void normalizeUnknownFallsBackToEntry() {
        assertEquals(TicketLineOrder.ENTRY, TicketLineOrder.normalize("RAYON"));
    }

    /**
     * A blank value falls back to the entry order.
     */
    @Test
    void normalizeBlankFallsBackToEntry() {
        assertEquals(TicketLineOrder.ENTRY, TicketLineOrder.normalize("   "));
    }

    // --------------------------------------------------
    // groupsByFamily
    // --------------------------------------------------

    /**
     * Only the grouped order prints headings.
     */
    @Test
    void onlyTheFamilyOrderGroups() {
        assertTrue(TicketLineOrder.groupsByFamily("FAMILY"));
        assertFalse(TicketLineOrder.groupsByFamily("LABEL"));
        assertFalse(TicketLineOrder.groupsByFamily("ENTRY"));
        assertFalse(TicketLineOrder.groupsByFamily(null));
    }

    // --------------------------------------------------
    // apply
    // --------------------------------------------------

    /**
     * The entry order returns the lines untouched.
     */
    @Test
    void entryOrderKeepsTheRegistrationOrder() {
        List<TicketLine> lines = List.of(line("POMME", null, null),
                line("BANANE", null, null), line("CAROTTE", null, null));
        assertEquals(List.of("POMME", "BANANE", "CAROTTE"),
                labels(TicketLineOrder.apply(lines, TicketLineOrder.ENTRY)));
    }

    /**
     * An unknown order behaves as the entry order (the switch's default arm).
     */
    @Test
    void unknownOrderKeepsTheRegistrationOrder() {
        List<TicketLine> lines = List.of(line("POMME", null, null), line("BANANE", null, null));
        assertEquals(List.of("POMME", "BANANE"), labels(TicketLineOrder.apply(lines, "RAYON")));
    }

    /**
     * The label order sorts alphabetically.
     */
    @Test
    void labelOrderSortsAlphabetically() {
        List<TicketLine> lines = List.of(line("POMME", null, null),
                line("BANANE", null, null), line("CAROTTE", null, null));
        assertEquals(List.of("BANANE", "CAROTTE", "POMME"),
                labels(TicketLineOrder.apply(lines, TicketLineOrder.LABEL)));
    }

    /**
     * The label order ignores the case, so a lower-cased label does not land at the
     * end of the ticket.
     */
    @Test
    void labelOrderIgnoresTheCase() {
        List<TicketLine> lines = List.of(line("pomme", null, null), line("BANANE", null, null));
        assertEquals(List.of("BANANE", "pomme"),
                labels(TicketLineOrder.apply(lines, TicketLineOrder.LABEL)));
    }

    /**
     * A line carrying no label sorts as an empty one instead of failing.
     */
    @Test
    void labelOrderToleratesAMissingLabel() {
        List<TicketLine> lines = List.of(line("POMME", null, null), line(null, null, null));
        // List.of refuses a null element, so the expectation below is an Arrays list.
        assertEquals(java.util.Arrays.asList(null, "POMME"),
                labels(TicketLineOrder.apply(lines, TicketLineOrder.LABEL)));
    }

    /**
     * The family order groups the articles of one family together.
     */
    @Test
    void familyOrderGroupsTheFamilies() {
        List<TicketLine> lines = List.of(
                line("POMME", "FRUITS", null),
                line("LAIT", "CREMERIE", null),
                line("BANANE", "FRUITS", null));
        assertEquals(List.of("LAIT", "POMME", "BANANE"),
                labels(TicketLineOrder.apply(lines, TicketLineOrder.FAMILY)));
    }

    /**
     * Within one family the cashier's entry order survives: the sort is STABLE, so a
     * ticket printed twice reads the same both times.
     */
    @Test
    void familyOrderIsStableWithinAFamily() {
        List<TicketLine> lines = List.of(
                line("POMME", "FRUITS", null),
                line("BANANE", "FRUITS", null),
                line("CERISE", "FRUITS", null));
        assertEquals(List.of("POMME", "BANANE", "CERISE"),
                labels(TicketLineOrder.apply(lines, TicketLineOrder.FAMILY)));
    }

    /**
     * The given list is never touched: the stored lines keep their own order.
     */
    @Test
    void applyNeverTouchesTheGivenList() {
        List<TicketLine> lines = new ArrayList<>(List.of(
                line("POMME", null, null), line("BANANE", null, null)));
        TicketLineOrder.apply(lines, TicketLineOrder.LABEL);
        assertEquals(List.of("POMME", "BANANE"), labels(lines));
    }

    /**
     * A missing line collection yields an empty order rather than a failure.
     */
    @Test
    void applyToleratesMissingLines() {
        assertTrue(TicketLineOrder.apply(null, TicketLineOrder.LABEL).isEmpty());
    }

    /**
     * A null line WITHIN the list sorts as an empty label rather than failing — the
     * true arm of {@code line == null} in the label comparator. Its empty label sorts
     * ahead of a real one, and the returned order keeps the null element intact.
     */
    @Test
    void labelOrderToleratesANullLineInTheList() {
        List<TicketLine> lines = new ArrayList<>();
        lines.add(line("POMME", null, null));
        lines.add(null);
        List<TicketLine> ordered = TicketLineOrder.apply(lines, TicketLineOrder.LABEL);
        assertNull(ordered.get(0));
        assertEquals("POMME", ordered.get(1).productLabel);
    }

    // --------------------------------------------------
    // familyOf
    // --------------------------------------------------

    /**
     * The family label heads the group when the line carries one.
     */
    @Test
    void familyOfPrefersTheLabel() {
        assertEquals("FRUITS", TicketLineOrder.familyOf(line("POMME", " FRUITS ", "FR")));
    }

    /**
     * The family code heads the group when the label is missing.
     */
    @Test
    void familyOfFallsBackToTheCode() {
        assertEquals("FR", TicketLineOrder.familyOf(line("POMME", null, " FR ")));
    }

    /**
     * A blank label falls back to the code too (the blank arm of the first guard).
     */
    @Test
    void familyOfFallsBackToTheCodeOnABlankLabel() {
        assertEquals("FR", TicketLineOrder.familyOf(line("POMME", "   ", "FR")));
    }

    /**
     * An article carrying neither heads the catch-all group.
     */
    @Test
    void familyOfFallsBackToTheCatchAll() {
        assertEquals(TicketLineOrder.NO_FAMILY, TicketLineOrder.familyOf(line("POMME", null, null)));
    }

    /**
     * A blank code falls back to the catch-all too (the blank arm of the second
     * guard).
     */
    @Test
    void familyOfFallsBackToTheCatchAllOnABlankCode() {
        assertEquals(TicketLineOrder.NO_FAMILY, TicketLineOrder.familyOf(line("POMME", null, "  ")));
    }

    /**
     * A missing line heads the catch-all group rather than failing.
     */
    @Test
    void familyOfToleratesAMissingLine() {
        assertEquals(TicketLineOrder.NO_FAMILY, TicketLineOrder.familyOf(null));
    }
}
