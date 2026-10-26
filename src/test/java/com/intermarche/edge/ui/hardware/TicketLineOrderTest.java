package com.intermarche.edge.ui.hardware;

import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.sale.TicketLine;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
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

    /**
     * Builds a ticket line whose product carries one article attribute.
     *
     * @param label the product label
     * @param code the attribute's code, null for a line whose product carries none
     * @param value the attribute's value
     * @return the line
     */
    private TicketLine attributeLine(String label, String code, String value) {
        TicketLine line = new TicketLine();
        line.productLabel = label;
        line.product = new com.intermarche.edge.domain.catalog.Product();
        if (code != null) {
            line.product.attributes.put(code, value);
        }
        return line;
    }

    /**
     * {@code normalizeIsolation} answers one of its three values: the two named
     * isolations whatever the case and the surrounding blanks, and no isolation
     * for a missing value, a blank one and a mode this register does not know.
     */
    @Test
    void normalizeIsolationAnswersOneOfItsThreeValues() {
        assertEquals(TicketLineOrder.ISOLATE_START, TicketLineOrder.normalizeIsolation("START"));
        assertEquals(TicketLineOrder.ISOLATE_START, TicketLineOrder.normalizeIsolation("  start "));
        assertEquals(TicketLineOrder.ISOLATE_END, TicketLineOrder.normalizeIsolation("end"));
        assertEquals(TicketLineOrder.ISOLATE_NONE, TicketLineOrder.normalizeIsolation(null));
        assertEquals(TicketLineOrder.ISOLATE_NONE, TicketLineOrder.normalizeIsolation("   "));
        assertEquals(TicketLineOrder.ISOLATE_NONE, TicketLineOrder.normalizeIsolation("MILIEU"));
    }

    /**
     * BO-03-03-17: with the alphabetical option OFF, the family groups come out
     * in the order their FIRST article appeared — CREMERIE before BOULANGERIE
     * here, which is the opposite of the alphabetical print and the whole point
     * of the option.
     */
    @Test
    void familyGroupsFollowTheirFirstArticleWhenNotAlphabetical() {
        List<TicketLine> lines = new ArrayList<>(List.of(
                line("LAIT", "CREMERIE", null),
                line("PAIN", "BOULANGERIE", null),
                line("YAOURT", "CREMERIE", null)));
        assertEquals(List.of("LAIT", "YAOURT", "PAIN"),
                labels(TicketLineOrder.apply(lines, TicketLineOrder.FAMILY,
                        false, null, TicketLineOrder.ISOLATE_NONE)));
        assertEquals(List.of("PAIN", "LAIT", "YAOURT"),
                labels(TicketLineOrder.apply(lines, TicketLineOrder.FAMILY,
                        true, null, TicketLineOrder.ISOLATE_NONE)));
    }

    /**
     * BO-03-03-18: an administered attribute with no isolation is a SORT
     * criterion — the values alphabetically, and the lines carrying none at the
     * end, because a blank is the absence of a value and not a value that comes
     * first.
     */
    @Test
    void anAdministeredAttributeSortsTheLinesAndSendsTheBlanksLast() {
        List<TicketLine> lines = new ArrayList<>(List.of(
                attributeLine("SANS", null, null),
                attributeLine("ZONE B", "RAYON", "B"),
                attributeLine("ZONE A", "RAYON", "A")));
        assertEquals(List.of("ZONE A", "ZONE B", "SANS"),
                labels(TicketLineOrder.apply(lines, TicketLineOrder.ENTRY,
                        true, "RAYON", TicketLineOrder.ISOLATE_NONE)));
    }

    /**
     * BO-03-03-18: the two isolations put the lines carrying the attribute at
     * one end of the ticket or the other, each block keeping the order it
     * already had.
     */
    @Test
    void anAdministeredAttributeIsolatesAtEitherEnd() {
        List<TicketLine> lines = new ArrayList<>(List.of(
                attributeLine("SANS 1", null, null),
                attributeLine("AVEC", "RAYON", "B"),
                attributeLine("SANS 2", null, null)));
        assertEquals(List.of("AVEC", "SANS 1", "SANS 2"),
                labels(TicketLineOrder.apply(lines, TicketLineOrder.ENTRY,
                        true, "RAYON", TicketLineOrder.ISOLATE_START)));
        assertEquals(List.of("SANS 1", "SANS 2", "AVEC"),
                labels(TicketLineOrder.apply(lines, TicketLineOrder.ENTRY,
                        true, "RAYON", TicketLineOrder.ISOLATE_END)));
    }

    /**
     * No attribute administered — null or blank — and the order obtained so far
     * stands: the attribute criterion is the LAST step and it must be able to
     * do nothing at all.
     */
    @Test
    void noAttributeAdministeredLeavesTheOrderAlone() {
        List<TicketLine> lines = new ArrayList<>(List.of(
                attributeLine("B", "RAYON", "Z"),
                attributeLine("A", "RAYON", "A")));
        assertEquals(List.of("B", "A"),
                labels(TicketLineOrder.apply(lines, TicketLineOrder.ENTRY,
                        true, null, TicketLineOrder.ISOLATE_START)));
        assertEquals(List.of("B", "A"),
                labels(TicketLineOrder.apply(lines, TicketLineOrder.ENTRY,
                        true, "   ", TicketLineOrder.ISOLATE_START)));
    }

    /**
     * {@code attributeOf} answers the empty value on each of its guards — no
     * line, no code, a blank code — and on a line whose product carries
     * neither the attribute nor a catalog default for it.
     */
    @Test
    void attributeOfToleratesEveryMissingPiece() {
        assertEquals("", TicketLineOrder.attributeOf(null, "RAYON"));
        assertEquals("", TicketLineOrder.attributeOf(attributeLine("A", "RAYON", "B"), null));
        assertEquals("", TicketLineOrder.attributeOf(attributeLine("A", "RAYON", "B"), "  "));
        assertEquals("", TicketLineOrder.attributeOf(attributeLine("A", null, null), "RAYON"));
        assertEquals("", TicketLineOrder.attributeOf(new TicketLine(), "RAYON"));
    }

    /**
     * {@code attributeOf} trims what the referential holds: a value padded in
     * an import must sort with its own kind and not in a column of its own.
     */
    @Test
    void attributeOfTrimsTheReferentialValue() {
        assertEquals("B", TicketLineOrder.attributeOf(attributeLine("A", "RAYON", "  B "), "RAYON"));
    }

    /**
     * The two-argument {@code apply} keeps the register's historical reading:
     * alphabetical family groups and no attribute criterion.
     */
    @Test
    void theShortApplyKeepsTheHistoricalReading() {
        List<TicketLine> lines = new ArrayList<>(List.of(
                line("LAIT", "CREMERIE", null),
                line("PAIN", "BOULANGERIE", null)));
        assertEquals(List.of("PAIN", "LAIT"),
                labels(TicketLineOrder.apply(lines, TicketLineOrder.FAMILY)));
    }

    // familyAt (BO-03-03-17)

    /**
     * Builds a family of a nomenclature whose level of the given rank is the
     * given length long, the other ranks being absent.
     *
     * @param code the family code
     * @param description the family label, possibly null
     * @param rank the rank of the only level, or -1 for a family outside any
     *        nomenclature
     * @param codeLength the code length of that level
     * @return the family
     */
    private static com.intermarche.edge.domain.catalog.ProductFamily family(String code, String description,
            int rank, int codeLength) {
        com.intermarche.edge.domain.catalog.ProductFamily family = new com.intermarche.edge.domain.catalog.ProductFamily();
        family.code = code;
        family.description = description;
        if (rank >= 0) {
            com.intermarche.edge.domain.catalog.Nomenclature nomenclature =
                    org.mockito.Mockito.mock(com.intermarche.edge.domain.catalog.Nomenclature.class);
            com.intermarche.edge.domain.catalog.NomenclatureLevel level =
                    new com.intermarche.edge.domain.catalog.NomenclatureLevel();
            level.rank = rank;
            level.codeLength = codeLength;
            org.mockito.Mockito.when(nomenclature.levelAt(rank)).thenReturn(level);
            family.nomenclature = nomenclature;
        }
        return family;
    }

    /**
     * Opens a Panache static mock under which {@code ProductFamily.findByCode}
     * answers the given families by code, and nothing for any other code.
     *
     * @param families the families the referential holds
     * @return the opened static mock, closed by the caller
     */
    @SuppressWarnings("unchecked")
    private static MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> families(
            com.intermarche.edge.domain.catalog.ProductFamily... families) {
        MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class);
        panache.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.find(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Object[].class)))
                .thenAnswer(invocation -> {
                    io.quarkus.hibernate.orm.panache.PanacheQuery<Object> query =
                            org.mockito.Mockito.mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
                    Object found = null;
                    for (com.intermarche.edge.domain.catalog.ProductFamily candidate : families) {
                        if (candidate.code.equals(invocation.getArgument(1))) {
                            found = candidate;
                        }
                    }
                    org.mockito.Mockito.when(query.firstResult()).thenReturn(found);
                    return query;
                });
        return panache;
    }

    /**
     * At level zero, on a missing line, or on a line without family code, the
     * heading is the line's own family and nothing is looked up.
     */
    @Test
    void familyAtKeepsTheOwnFamilyWithoutALevelOrACode() {
        assertEquals("FRUITS", TicketLineOrder.familyAt(line("POMME", "FRUITS", "0102"), 0));
        assertEquals(TicketLineOrder.NO_FAMILY, TicketLineOrder.familyAt(null, 1));
        assertEquals("FRUITS", TicketLineOrder.familyAt(line("POMME", "FRUITS", null), 1));
        assertEquals("FRUITS", TicketLineOrder.familyAt(line("POMME", "FRUITS", "  "), 1));
    }

    /**
     * At a level of the nomenclature, the heading is the label of the node of
     * that level the family descends from.
     */
    @Test
    void familyAtReadsTheNodeOfTheLevel() {
        try (var panache = families(family("0102", "POMMES", 0, 2), family("01", " FRUITS ET LEGUMES ", -1, 0))) {
            assertEquals("FRUITS ET LEGUMES", TicketLineOrder.familyAt(line("POMME", "POMMES", "0102"), 1));
        }
    }

    /**
     * A node that is not in the referential, or that carries no label, is
     * named by its code.
     */
    @Test
    void familyAtNamesAnUnlabelledNodeByItsCode() {
        try (var panache = families(family("0102", "POMMES", 0, 2))) {
            assertEquals("01", TicketLineOrder.familyAt(line("POMME", "POMMES", "0102"), 1));
        }
        try (var panache = families(family("0102", "POMMES", 0, 2), family("01", null, -1, 0))) {
            assertEquals("01", TicketLineOrder.familyAt(line("POMME", "POMMES", "0102"), 1));
        }
        try (var panache = families(family("0102", "POMMES", 0, 2), family("01", "  ", -1, 0))) {
            assertEquals("01", TicketLineOrder.familyAt(line("POMME", "POMMES", "0102"), 1));
        }
    }

    /**
     * Everything that cannot be resolved keeps the article's family: a family
     * unknown to the referential, one outside any nomenclature, a level the
     * nomenclature does not have, a level of no length, and a level at or below
     * the article's own.
     */
    @Test
    void familyAtKeepsTheOwnFamilyWhenTheLevelCannotBeResolved() {
        TicketLine apple = line("POMME", "POMMES", "0102");
        try (var panache = families()) {
            assertEquals("POMMES", TicketLineOrder.familyAt(apple, 1));
        }
        try (var panache = families(family("0102", "POMMES", -1, 0))) {
            assertEquals("POMMES", TicketLineOrder.familyAt(apple, 1));
        }
        try (var panache = families(family("0102", "POMMES", 3, 2))) {
            assertEquals("POMMES", TicketLineOrder.familyAt(apple, 1));
        }
        try (var panache = families(family("0102", "POMMES", 0, 0))) {
            assertEquals("POMMES", TicketLineOrder.familyAt(apple, 1));
        }
        try (var panache = families(family("0102", "POMMES", 0, 4))) {
            assertEquals("POMMES", TicketLineOrder.familyAt(apple, 1));
        }
    }

    /**
     * The grouped order reads the families at the administered level, in
     * alphabetical order and in order of appearance alike.
     */
    @Test
    void theGroupedOrderReadsTheFamiliesAtTheLevel() {
        List<TicketLine> lines = new ArrayList<>();
        lines.add(line("LAIT", "LAITS", "0201"));
        lines.add(line("POMME", "POMMES", "0102"));
        lines.add(line("YAOURT", "YAOURTS", "0202"));
        try (var panache = families(family("0201", "LAITS", 0, 2), family("0102", "POMMES", 0, 2),
                family("0202", "YAOURTS", 0, 2), family("01", "FRUITS", -1, 0), family("02", "CREMERIE", -1, 0))) {
            assertEquals(List.of("LAIT", "YAOURT", "POMME"), labels(TicketLineOrder.apply(lines,
                    TicketLineOrder.FAMILY, true, null, TicketLineOrder.ISOLATE_NONE, 1)));
            assertEquals(List.of("LAIT", "YAOURT", "POMME"), labels(TicketLineOrder.apply(lines,
                    TicketLineOrder.FAMILY, false, null, TicketLineOrder.ISOLATE_NONE, 1)));
        }
    }
}
