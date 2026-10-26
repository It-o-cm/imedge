package com.intermarche.edge.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit tests for {@link BannerLines}, the two-way door between the banner's
 * source and the table the back office edits it in (BO-03-05-05 and -06).
 *
 * <p>Branch enumeration: {@code parse} covers the null source, the empty
 * source, the three alignments, the bold wrapper alone and combined with each
 * alignment, the inactive marker alone and combined, a line the class did not
 * write, and the trailing-empty-line trim with both its legs; {@code format}
 * covers null rows, no rows, each alignment, bold, inactive, and a null text.
 * The ROUND TRIP is asserted on a banner carrying every shape at once, because
 * that is the property the screen depends on.
 */
class BannerLinesTest {

    /**
     * A null and an empty source carry no line — the two short-circuit arms.
     */
    @Test
    void parseReadsNothingFromNothing() {
        assertTrue(BannerLines.parse(null).isEmpty());
        assertTrue(BannerLines.parse("").isEmpty());
    }

    /**
     * The three alignments are read back as the three alignments, and a plain
     * line is a LEFT line carrying its own text.
     */
    @Test
    void parseReadsTheThreeAlignments() {
        List<BannerLines.Line> rows = BannerLines.parse(
                "BONJOUR\n{fmt.centre(\"BIENVENUE\")}\n{fmt.right(\"SIRET 123\")}");
        assertEquals(3, rows.size());
        assertEquals(new BannerLines.Line("BONJOUR", BannerLines.Align.LEFT, false, true), rows.get(0));
        assertEquals(new BannerLines.Line("BIENVENUE", BannerLines.Align.CENTRE, false, true), rows.get(1));
        assertEquals(new BannerLines.Line("SIRET 123", BannerLines.Align.RIGHT, false, true), rows.get(2));
    }

    /**
     * Bold wraps an aligned line and a plain one alike, and the wrapper is not
     * mistaken for the text.
     */
    @Test
    void parseReadsTheEmphasis() {
        List<BannerLines.Line> rows = BannerLines.parse(
                "{fmt.bold({fmt.centre(\"SOLDES\")})}\n{fmt.bold(MERCI)}");
        assertEquals(new BannerLines.Line("SOLDES", BannerLines.Align.CENTRE, true, true), rows.get(0));
        assertEquals(new BannerLines.Line("MERCI", BannerLines.Align.LEFT, true, true), rows.get(1));
    }

    /**
     * An inactive line KEEPS ITS TEXT: switching a seasonal line off in October
     * must not cost retyping it in December. The marker combines with the
     * emphasis and with an alignment.
     */
    @Test
    void parseReadsAnInactiveLineWithoutLosingIt() {
        List<BannerLines.Line> rows = BannerLines.parse(
                "{! OFF !}{fmt.bold({fmt.centre(\"JOYEUSES FETES\")})}");
        assertEquals(1, rows.size());
        assertEquals("JOYEUSES FETES", rows.get(0).text());
        assertEquals(BannerLines.Align.CENTRE, rows.get(0).align());
        assertTrue(rows.get(0).bold());
        assertFalse(rows.get(0).active());
    }

    /**
     * A line this class did not write — a loop, a condition, a value read from
     * the sale — is answered verbatim as a LEFT line, so the table never
     * silently drops what it does not understand.
     */
    @Test
    void parseKeepsALineItDidNotWrite() {
        List<BannerLines.Line> rows = BannerLines.parse("Caisse {terminal} du {date}");
        assertEquals(new BannerLines.Line("Caisse {terminal} du {date}",
                BannerLines.Align.LEFT, false, true), rows.get(0));
    }

    /**
     * The trailing empty line a text block always ends on is trimmed, and only
     * that one: an empty line in the MIDDLE is a spacing the shop asked for,
     * and a trailing CENTRED empty line is a deliberate blank too.
     */
    @Test
    void parseTrimsOnlyTheTrailingEmptyLines() {
        List<BannerLines.Line> rows = BannerLines.parse("A\n\nB\n\n");
        assertEquals(3, rows.size());
        assertEquals("A", rows.get(0).text());
        assertEquals("", rows.get(1).text());
        assertEquals("B", rows.get(2).text());
        List<BannerLines.Line> kept = BannerLines.parse("A\n{fmt.centre(\"\")}");
        assertEquals(2, kept.size());
        assertEquals(BannerLines.Align.CENTRE, kept.get(1).align());
    }

    /**
     * No rows and null rows write no source — the two short-circuit arms.
     */
    @Test
    void formatWritesNothingFromNothing() {
        assertEquals("", BannerLines.format(null));
        assertEquals("", BannerLines.format(List.of()));
    }

    /**
     * Each shape is written the way the parser reads it, a null text counting
     * as an empty one.
     */
    @Test
    void formatWritesEachShape() {
        assertEquals("GAUCHE\n", BannerLines.format(List.of(
                new BannerLines.Line("GAUCHE", BannerLines.Align.LEFT, false, true))));
        assertEquals("{fmt.centre(\"CENTRE\")}\n", BannerLines.format(List.of(
                new BannerLines.Line("CENTRE", BannerLines.Align.CENTRE, false, true))));
        assertEquals("{fmt.right(\"DROITE\")}\n", BannerLines.format(List.of(
                new BannerLines.Line("DROITE", BannerLines.Align.RIGHT, false, true))));
        assertEquals("{fmt.bold(GRAS)}\n", BannerLines.format(List.of(
                new BannerLines.Line("GRAS", BannerLines.Align.LEFT, true, true))));
        assertEquals("{! OFF !}ETEINTE\n", BannerLines.format(List.of(
                new BannerLines.Line("ETEINTE", BannerLines.Align.LEFT, false, false))));
        assertEquals("\n", BannerLines.format(List.of(
                new BannerLines.Line(null, BannerLines.Align.LEFT, false, true))));
    }

    /**
     * THE PROPERTY THE SCREEN DEPENDS ON: a banner carrying every shape at once
     * survives the table and comes back as the same source, so a paramétreur
     * may move between the table and the raw source without losing a line.
     */
    @Test
    void theRoundTripIsExact() {
        String source = "BONJOUR\n"
                + "{fmt.centre(\"BIENVENUE\")}\n"
                + "{fmt.right(\"SIRET 123\")}\n"
                + "{fmt.bold({fmt.centre(\"SOLDES\")})}\n"
                + "{! OFF !}{fmt.right(\"HORS SAISON\")}\n";
        assertEquals(source, BannerLines.format(BannerLines.parse(source)));
    }
    /** BO-03-05: a banner made only of trailing blank rows carries no editable or printed line. */
    @Test
    void allBlankRowsAreTrimmedCompletely() {
        assertTrue(BannerLines.parse("\n\n").isEmpty());
    }

}
