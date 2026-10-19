package com.intermarche.edge.ui.hardware;

import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.sale.TicketLine;
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

    /**
     * Builds a ticket line whose product carries one declared attribute, so
     * {@link TicketLineOrder#attributeOf} reads a known value through the product.
     *
     * @param label the product label
     * @param code the attribute code, stored on the product when a value is given
     * @param value the attribute value, or null to leave the product without it
     * @return the line referencing a product with that attribute
     */
    private TicketLine lineWithAttribute(String label, String code, String value) {
        TicketLine built = line(label, null, null);
        Product product = new Product();
        if (value != null) {
            product.attributes.put(code, value);
        }
        built.product = product;
        return built;
    }

    // --------------------------------------------------
    // normalizeIsolation
    // --------------------------------------------------

    /**
     * The two non-default isolations are recognized.
     */
    @Test
    void normalizeIsolationKeepsStartAndEnd() {
        assertEquals(TicketLineOrder.ISOLATE_START, TicketLineOrder.normalizeIsolation("START"));
        assertEquals(TicketLineOrder.ISOLATE_END, TicketLineOrder.normalizeIsolation("END"));
    }

    /**
     * Case and surrounding blanks are ignored.
     */
    @Test
    void normalizeIsolationIgnoresCaseAndBlanks() {
        assertEquals(TicketLineOrder.ISOLATE_START, TicketLineOrder.normalizeIsolation("  start "));
    }

    /**
     * A missing isolation falls back to no isolation (null arm).
     */
    @Test
    void normalizeIsolationNullFallsBackToNone() {
        assertEquals(TicketLineOrder.ISOLATE_NONE, TicketLineOrder.normalizeIsolation(null));
    }

    /**
     * An unknown isolation falls back to no isolation — a back office that knows a
     * mode this register does not must never stop a ticket from printing.
     */
    @Test
    void normalizeIsolationUnknownFallsBackToNone() {
        assertEquals(TicketLineOrder.ISOLATE_NONE, TicketLineOrder.normalizeIsolation("MIDDLE"));
    }

    // --------------------------------------------------
    // apply (five arguments)
    // --------------------------------------------------

    /**
     * With the family headings NOT alphabetical (BO-03-03-17), the groups come out
     * in the order their first article appeared: FRUITS before CREMERIE here, the
     * opposite of the alphabetical reading.
     */
    @Test
    void familyOrderByAppearanceWhenNotAlphabetical() {
        List<TicketLine> lines = List.of(
                line("POMME", "FRUITS", null),
                line("LAIT", "CREMERIE", null),
                line("BANANE", "FRUITS", null));
        assertEquals(List.of("POMME", "BANANE", "LAIT"),
                labels(TicketLineOrder.apply(lines, TicketLineOrder.FAMILY,
                        false, null, TicketLineOrder.ISOLATE_NONE)));
    }

    /**
     * A blank attribute code is no criterion (the blank arm of the attribute guard),
     * leaving the order obtained from the nomenclature untouched.
     */
    @Test
    void blankAttributeIsNoCriterion() {
        List<TicketLine> lines = List.of(line("POMME", null, null), line("BANANE", null, null));
        assertEquals(List.of("POMME", "BANANE"),
                labels(TicketLineOrder.apply(lines, TicketLineOrder.ENTRY,
                        true, "   ", TicketLineOrder.ISOLATE_NONE)));
    }

    /**
     * With no isolation the attribute is a plain sort criterion: the values come out
     * alphabetically and the line carrying none sorts at the end — a blank is the
     * absence of a value, not one that sorts first.
     */
    @Test
    void attributeSortOrdersByValueThenEmptyLast() {
        List<TicketLine> lines = List.of(
                lineWithAttribute("BRIE", "ZONE", "FRAIS"),
                lineWithAttribute("CLOU", "ZONE", null),
                lineWithAttribute("BANANE", "ZONE", "AMBIANT"));
        assertEquals(List.of("BANANE", "BRIE", "CLOU"),
                labels(TicketLineOrder.apply(lines, TicketLineOrder.ENTRY,
                        true, "ZONE", TicketLineOrder.ISOLATE_NONE)));
    }

    /**
     * START gathers the lines carrying the attribute at the top of the ticket, each
     * block keeping the order it already had.
     */
    @Test
    void attributeIsolateStartGathersCarryingFirst() {
        List<TicketLine> lines = List.of(
                lineWithAttribute("CLOU", "ZONE", null),
                lineWithAttribute("BRIE", "ZONE", "FRAIS"),
                lineWithAttribute("VIS", "ZONE", null));
        assertEquals(List.of("BRIE", "CLOU", "VIS"),
                labels(TicketLineOrder.apply(lines, TicketLineOrder.ENTRY,
                        true, "ZONE", TicketLineOrder.ISOLATE_START)));
    }

    /**
     * END gathers the lines carrying the attribute at the bottom of the ticket, each
     * block keeping the order it already had.
     */
    @Test
    void attributeIsolateEndGathersCarryingLast() {
        List<TicketLine> lines = List.of(
                lineWithAttribute("BRIE", "ZONE", "FRAIS"),
                lineWithAttribute("CLOU", "ZONE", null),
                lineWithAttribute("CAMEMBERT", "ZONE", "FRAIS"));
        assertEquals(List.of("CLOU", "BRIE", "CAMEMBERT"),
                labels(TicketLineOrder.apply(lines, TicketLineOrder.ENTRY,
                        true, "ZONE", TicketLineOrder.ISOLATE_END)));
    }

    // --------------------------------------------------
    // attributeOf
    // --------------------------------------------------

    /**
     * The attribute value is read through the product and trimmed.
     */
    @Test
    void attributeOfReadsTheProductValueTrimmed() {
        assertEquals("FRAIS",
                TicketLineOrder.attributeOf(lineWithAttribute("BRIE", "ZONE", " FRAIS "), "ZONE"));
    }

    /**
     * A missing line carries no value (the null-line arm of the guard).
     */
    @Test
    void attributeOfIsEmptyForNullLine() {
        assertEquals("", TicketLineOrder.attributeOf(null, "ZONE"));
    }

    /**
     * A null code carries no value (the null-code arm of the guard).
     */
    @Test
    void attributeOfIsEmptyForNullCode() {
        assertEquals("", TicketLineOrder.attributeOf(lineWithAttribute("BRIE", "ZONE", "FRAIS"), null));
    }

    /**
     * A blank code carries no value (the blank-code arm of the guard).
     */
    @Test
    void attributeOfIsEmptyForBlankCode() {
        assertEquals("", TicketLineOrder.attributeOf(lineWithAttribute("BRIE", "ZONE", "FRAIS"), "  "));
    }

    /**
     * A line whose product has no such attribute carries no value (the null-value
     * arm of the final ternary): a gone referential value sorts with the others
     * rather than failing a print.
     */
    @Test
    void attributeOfIsEmptyWhenTheProductHasNoValue() {
        assertEquals("", TicketLineOrder.attributeOf(lineWithAttribute("CLOU", "ZONE", null), "ZONE"));
    }
}
