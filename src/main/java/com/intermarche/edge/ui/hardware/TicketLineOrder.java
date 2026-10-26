package com.intermarche.edge.ui.hardware;

import com.intermarche.edge.domain.catalog.attribute.ProductAttributes;
import com.intermarche.edge.domain.sale.TicketLine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The order the articles are PRINTED in on the sale ticket (LC-08-01-07), as the
 * back office administers it.
 * <p>
 * Three readings of the same basket, and the store picks one: the order the cashier
 * scanned them in — what the customer watched happen, and the register's historical
 * behaviour —, alphabetically by label, or grouped under their article family. Only
 * the grouped mode prints headings; the two others are a plain sort.
 * <p>
 * Over that order the shop may lay two further administered rules: the family
 * headings in the order the families appeared rather than alphabetically
 * (BO-03-03-17), and an ARTICLE ATTRIBUTE used as a sort criterion or to
 * isolate the lines carrying it at one end of the ticket (BO-03-03-18).
 * <p>
 * A pure static rule, deliberately outside the renderer ({@code TicketPrinterService}
 * on a register): it decides nothing about paper and talks to no collaborator, so it
 * can be exercised on its own exactly like {@code PrintPolicy}.
 * <p>
 * The order applies to the PRINTED ticket alone. The stored lines keep their entry
 * order and their line numbers whatever is administered — the ticket is a rendering,
 * the sale is the record — and the VAT ventilation, computed over the same lines,
 * cannot move because a sort cannot change a sum.
 * <p>
 * Non-instantiable.
 */
public final class TicketLineOrder {

    /** The articles print in the order they were registered — the default. */
    public static final String ENTRY = "ENTRY";

    /** The articles print sorted by label. */
    public static final String LABEL = "LABEL";

    /** The articles print grouped under their family, which is printed as a heading. */
    public static final String FAMILY = "FAMILY";

    /** The heading of the articles carrying no family. */
    public static final String NO_FAMILY = "DIVERS";

    /** The attribute is a sort criterion and nothing more — the default. */
    public static final String ISOLATE_NONE = "NONE";

    /** The lines carrying the attribute are isolated at the top of the ticket. */
    public static final String ISOLATE_START = "START";

    /** The lines carrying the attribute are isolated at the bottom of the ticket. */
    public static final String ISOLATE_END = "END";

    /**
     * Non-instantiable rule.
     */
    private TicketLineOrder() {
    }

    /**
     * Normalizes an administered value to one of the three known orders; anything
     * else — including a value from a back office that knows a mode this register
     * does not — falls back to the entry order, which is always printable.
     *
     * @param raw the administered value, possibly null or blank
     * @return {@link #ENTRY}, {@link #LABEL} or {@link #FAMILY}
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return ENTRY;
        }
        String value = raw.trim().toUpperCase();
        if (value.equals(LABEL) || value.equals(FAMILY)) {
            return value;
        }
        return ENTRY;
    }

    /**
     * Whether the order prints a family heading above each group.
     *
     * @param order the normalized order
     * @return true for the grouped order alone
     */
    public static boolean groupsByFamily(String order) {
        return FAMILY.equals(normalize(order));
    }

    /**
     * Normalizes an administered isolation to one of the three known ones;
     * anything else — including a value from a back office that knows a mode
     * this register does not — falls back to no isolation, which only sorts.
     *
     * @param raw the administered value, possibly null or blank
     * @return {@link #ISOLATE_NONE}, {@link #ISOLATE_START} or {@link #ISOLATE_END}
     */
    public static String normalizeIsolation(String raw) {
        if (raw == null) {
            return ISOLATE_NONE;
        }
        String value = raw.trim().toUpperCase();
        if (value.equals(ISOLATE_START) || value.equals(ISOLATE_END)) {
            return value;
        }
        return ISOLATE_NONE;
    }

    /**
     * Returns the lines in printing order, under the register's historical
     * reading: family headings alphabetically and no attribute criterion.
     *
     * @param lines the ticket's lines, in entry order
     * @param order the administered order, normalized on the way in
     * @return a new list in printing order; the given list is never touched
     */
    public static List<TicketLine> apply(List<TicketLine> lines, String order) {
        return apply(lines, order, true, null, ISOLATE_NONE);
    }

    /**
     * Returns the lines in printing order.
     *
     * <p>The sort is STABLE: within one family, or between two articles carrying the
     * same label, the cashier's entry order survives — a ticket whose identical lines
     * swap places from one print to the next would be unreadable next to the paper the
     * customer already holds.
     *
     * <p>THE ATTRIBUTE CRITERION COMES LAST, and that settles the priority the
     * questionnaire asks about (BO-03-03-18): it is applied over the order
     * already obtained, so an administered attribute PREVAILS over the
     * nomenclature rather than competing with it. A shop that wants its
     * nomenclature to win administers no attribute.
     *
     * @param lines the ticket's lines, in entry order
     * @param order the administered order, normalized on the way in
     * @param familyAlphabetical whether the family headings come out
     *        alphabetically (BO-03-03-17); false orders them by the first
     *        article of each family. Read by the grouped order alone.
     * @param attributeCode the article attribute the lines are sorted or
     *        isolated on (BO-03-03-18), null or blank for none
     * @param isolation where the lines carrying that attribute are isolated,
     *        normalized on the way in
     * @return a new list in printing order; the given list is never touched
     */
    public static List<TicketLine> apply(List<TicketLine> lines, String order,
            boolean familyAlphabetical, String attributeCode, String isolation) {
        return apply(lines, order, familyAlphabetical, attributeCode, isolation, 0);
    }

    /**
     * Returns the lines in printing order, the families being read at an
     * administered LEVEL of the nomenclature (BO-03-03-17).
     *
     * @param lines the ticket's lines, in entry order
     * @param order the administered order, normalized on the way in
     * @param familyAlphabetical whether the family headings come out
     *        alphabetically; false orders them by the first article of each
     *        family
     * @param attributeCode the article attribute the lines are sorted or
     *        isolated on (BO-03-03-18), null or blank for none
     * @param isolation where the lines carrying that attribute are isolated
     * @param level the nomenclature level the families are read at, one-based,
     *        zero for the article's own family
     * @return a new list in printing order; the given list is never touched
     */
    public static List<TicketLine> apply(List<TicketLine> lines, String order,
            boolean familyAlphabetical, String attributeCode, String isolation, int level) {
        if (lines == null) {
            return List.of();
        }
        List<TicketLine> ordered = new ArrayList<>(lines);
        switch (normalize(order)) {
            case LABEL -> ordered.sort(Comparator.comparing(
                    TicketLineOrder::labelOf, String.CASE_INSENSITIVE_ORDER));
            case FAMILY -> sortByFamily(ordered, familyAlphabetical, level);
            default -> {
                // Entry order: the list is already in it.
            }
        }
        return applyAttribute(ordered, attributeCode, isolation);
    }

    /**
     * Groups the lines under their family, the groups themselves in the
     * administered order (BO-03-03-17).
     *
     * <p>Alphabetically, which is what the register has always printed, or in
     * the order the families APPEARED in the basket — the reading a shop asks
     * for when the receipt is meant to follow the way the trolley was emptied
     * rather than a dictionary.
     *
     * @param ordered the lines, in entry order; sorted in place
     * @param alphabetical whether the headings come out alphabetically
     * @param level the nomenclature level the families are read at, zero
     *        for the article's own family
     */
    private static void sortByFamily(List<TicketLine> ordered, boolean alphabetical, int level) {
        // Each line's heading is resolved ONCE: at a level above the article,
        // it is a nomenclature lookup, and a comparator calls it at every
        // comparison.
        Map<TicketLine, String> headings = new java.util.IdentityHashMap<>();
        for (TicketLine line : ordered) {
            headings.put(line, familyAt(line, level));
        }
        if (alphabetical) {
            ordered.sort(Comparator.comparing(headings::get, String.CASE_INSENSITIVE_ORDER));
            return;
        }
        // The rank of each family is its first article's position. Read before
        // the insertion, so the first family ranks 0 and not 1.
        Map<String, Integer> firstSeen = new HashMap<>();
        for (TicketLine line : ordered) {
            firstSeen.putIfAbsent(headings.get(line), firstSeen.size());
        }
        ordered.sort(Comparator.comparingInt(line -> firstSeen.get(headings.get(line))));
    }

    /**
     * Sorts or isolates the lines on an article attribute (BO-03-03-18).
     *
     * <p>With no isolation the attribute is a plain sort criterion, the values
     * alphabetically and the lines carrying NONE at the end — a blank is not a
     * value that sorts first, it is the absence of one. With an isolation the
     * lines carrying a value are gathered at the top or at the bottom of the
     * ticket, each block keeping the order it already had.
     *
     * @param ordered the lines, in the order obtained so far
     * @param attributeCode the attribute code, null or blank for no criterion
     * @param isolation the administered isolation, normalized on the way in
     * @return the lines in their final order
     */
    private static List<TicketLine> applyAttribute(List<TicketLine> ordered,
            String attributeCode, String isolation) {
        if (attributeCode == null || attributeCode.isBlank()) {
            return ordered;
        }
        String code = attributeCode.trim();
        String mode = normalizeIsolation(isolation);
        if (ISOLATE_NONE.equals(mode)) {
            ordered.sort(Comparator
                    .comparing((TicketLine line) -> attributeOf(line, code).isEmpty())
                    .thenComparing(line -> attributeOf(line, code),
                            String.CASE_INSENSITIVE_ORDER));
            return ordered;
        }
        List<TicketLine> carrying = new ArrayList<>();
        List<TicketLine> others = new ArrayList<>();
        for (TicketLine line : ordered) {
            if (attributeOf(line, code).isEmpty()) {
                others.add(line);
            } else {
                carrying.add(line);
            }
        }
        List<TicketLine> isolated = new ArrayList<>();
        if (ISOLATE_START.equals(mode)) {
            isolated.addAll(carrying);
            isolated.addAll(others);
        } else {
            isolated.addAll(others);
            isolated.addAll(carrying);
        }
        return isolated;
    }

    /**
     * Reads an article attribute of a sold line (BO-03-03-18).
     *
     * <p>Through the product the line still references, which is also how the
     * conditional-printing rule reads the legal warranty: the attribute values
     * are held by the referential and are not snapshotted on the sold line, so
     * a line whose product is gone carries no value and sorts with the others
     * rather than failing a print.
     *
     * @param line the ticket line, possibly null
     * @param code the attribute code, possibly null or blank
     * @return the attribute's value, trimmed, empty when there is none
     */
    public static String attributeOf(TicketLine line, String code) {
        if (line == null || code == null || code.isBlank()) {
            return "";
        }
        String value = ProductAttributes.raw(line.product, code.trim());
        return value == null ? "" : value.trim();
    }

    /**
     * Returns the family a line is grouped and headed under.
     *
     * @param line the ticket line
     * @return its family label, its family code when it carries no label, and
     *         {@link #NO_FAMILY} when it carries neither
     */
    public static String familyOf(TicketLine line) {
        if (line == null) {
            return NO_FAMILY;
        }
        if (line.familyLabel != null && !line.familyLabel.isBlank()) {
            return line.familyLabel.trim();
        }
        if (line.familyCode != null && !line.familyCode.isBlank()) {
            return line.familyCode.trim();
        }
        return NO_FAMILY;
    }

    /**
     * The heading a line is grouped under at a LEVEL of the nomenclature
     * (BO-03-03-17): the label of the node of that level the article's family
     * descends from.
     *
     * <p>The node is found by its code, the nomenclature's codes being
     * prefixes of one another level after level: the family {@code 0102} of a
     * nomenclature whose first level is two characters long descends from the
     * node {@code 01}. Everything that cannot be resolved — no level asked, a
     * line without family, a family outside any nomenclature, a level the
     * nomenclature does not have or one below the article's own — keeps the
     * article's family, so asking for a level never loses a heading.
     *
     * @param line the sold line, possibly null
     * @param level the level, one-based, zero for the article's own family
     * @return the heading, never null
     */
    public static String familyAt(TicketLine line, int level) {
        if (level <= 0 || line == null || line.familyCode == null || line.familyCode.isBlank()) {
            return familyOf(line);
        }
        String code = line.familyCode.trim();
        com.intermarche.edge.domain.catalog.ProductFamily family =
                com.intermarche.edge.domain.catalog.ProductFamily.findByCode(code);
        if (family == null || family.nomenclature == null) {
            return familyOf(line);
        }
        com.intermarche.edge.domain.catalog.NomenclatureLevel wanted =
                family.nomenclature.levelAt(level - 1);
        if (wanted == null || wanted.codeLength <= 0 || wanted.codeLength >= code.length()) {
            return familyOf(line);
        }
        String nodeCode = code.substring(0, wanted.codeLength);
        com.intermarche.edge.domain.catalog.ProductFamily node =
                com.intermarche.edge.domain.catalog.ProductFamily.findByCode(nodeCode);
        if (node == null || node.description == null || node.description.isBlank()) {
            return nodeCode;
        }
        return node.description.trim();
    }

    /**
     * Returns the label a line is sorted on.
     *
     * @param line the ticket line
     * @return its product label, empty when it carries none
     */
    private static String labelOf(TicketLine line) {
        return line == null || line.productLabel == null ? "" : line.productLabel;
    }
}
