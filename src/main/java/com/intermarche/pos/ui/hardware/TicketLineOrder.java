package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.domain.sale.TicketLine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The order the articles are PRINTED in on the sale ticket (LC-08-01-07), as the
 * back office administers it.
 * <p>
 * Three readings of the same basket, and the store picks one: the order the cashier
 * scanned them in — what the customer watched happen, and the register's historical
 * behaviour —, alphabetically by label, or grouped under their article family. Only
 * the grouped mode prints headings; the two others are a plain sort.
 * <p>
 * A pure static rule, deliberately outside the renderer: it decides nothing about
 * paper and talks to no collaborator, so it can be exercised on its own.
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
     * Returns the lines in printing order.
     *
     * <p>The sort is STABLE: within one family, or between two articles carrying the
     * same label, the cashier's entry order survives — a ticket whose identical lines
     * swap places from one print to the next would be unreadable next to the paper the
     * customer already holds.
     *
     * @param lines the ticket's lines, in entry order
     * @param order the administered order, normalized on the way in
     * @return a new list in printing order; the given list is never touched
     */
    public static List<TicketLine> apply(List<TicketLine> lines, String order) {
        if (lines == null) {
            return List.of();
        }
        List<TicketLine> ordered = new ArrayList<>(lines);
        switch (normalize(order)) {
            case LABEL -> ordered.sort(Comparator.comparing(
                    TicketLineOrder::labelOf, String.CASE_INSENSITIVE_ORDER));
            case FAMILY -> ordered.sort(Comparator.comparing(
                    TicketLineOrder::familyOf, String.CASE_INSENSITIVE_ORDER));
            default -> {
                // Entry order: the list is already in it.
            }
        }
        return ordered;
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
     * Returns the label a line is sorted on.
     *
     * @param line the ticket line
     * @return its product label, empty when it carries none
     */
    private static String labelOf(TicketLine line) {
        return line == null || line.productLabel == null ? "" : line.productLabel;
    }
}
