package com.intermarche.pos.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DocumentLayout}, the typesetting primitives shared by
 * the register's built-in layouts and by administered templates.
 *
 * <p>Every leg of every guard is exercised, because these four methods decide
 * what a customer reads on paper and a document laid out wrong is a document
 * nobody can check afterwards: the width clamp on both arms, {@code line} on
 * the fitting, exactly-fitting and overflowing arms plus its two null arms,
 * {@code centre} on the shorter, equal, longer and null arms, and {@code wrap}
 * on the null arm, the blank arm, the break-between-words arm, the
 * cut-a-long-word arm with and without a line already in hand, and the flush of
 * the last line.
 */
class DocumentLayoutTest {

    /** The register's own paper, the width every receipt case uses. */
    private static final DocumentLayout PAPER = new DocumentLayout(42);

    /**
     * The width is carried by the instance and given back as it was.
     */
    @Test
    void theWidthIsTheOneGiven() {
        assertEquals(42, PAPER.getWidth());
        assertEquals(20, new DocumentLayout(20).getWidth());
    }

    /**
     * A width below one is raised to one: no alignment is possible on nothing,
     * and a document printed too narrow beats a document that throws.
     */
    @Test
    void awidthBelowOneIsRaisedToOne() {
        assertEquals(1, new DocumentLayout(0).getWidth());
        assertEquals(1, new DocumentLayout(-5).getWidth());
    }

    /**
     * A label and a value that fit are separated by exactly the spaces that
     * push the value onto the right edge.
     */
    @Test
    void lineFlushesTheValueRight() {
        String produced = PAPER.line("TOTAL TTC", "4,62 E");
        assertEquals(42, produced.length());
        assertTrue(produced.startsWith("TOTAL TTC"));
        assertTrue(produced.endsWith("4,62 E"));
    }

    /**
     * A label and a value whose lengths already fill the paper keep exactly one
     * space — the boundary of the guard, where the computed space is one.
     */
    @Test
    void lineKeepsOneSpaceWhenTheyJustFit() {
        String left = "A".repeat(35);
        String right = "B".repeat(6);
        assertEquals(left + " " + right, PAPER.line(left, right));
    }

    /**
     * A label and a value too long to fit OVERFLOW with one space rather than
     * the value being cut: a truncated amount on a receipt is worse than a line
     * the printer wraps.
     */
    @Test
    void lineOverflowsRatherThanTruncating() {
        String left = "A".repeat(40);
        String right = "B".repeat(20);
        String produced = PAPER.line(left, right);
        assertEquals(left + " " + right, produced);
        assertTrue(produced.length() > 42);
    }

    /**
     * A null label and a null value are read as empty, so a missing value
     * prints a blank line rather than raising in the middle of a sale.
     */
    @Test
    void lineToleratesNulls() {
        assertEquals(" ".repeat(42), PAPER.line(null, null));
        assertEquals("GAUCHE" + " ".repeat(36), PAPER.line("GAUCHE", null));
        assertEquals(" ".repeat(36) + "DROITE", PAPER.line(null, "DROITE"));
    }

    /**
     * A text shorter than the paper is padded on the LEFT only: a receipt line
     * carries no trailing spaces.
     */
    @Test
    void centrePadsOnTheLeftOnly() {
        String produced = PAPER.centre("MERCI");
        assertEquals(" ".repeat(18) + "MERCI", produced);
        assertEquals(23, produced.length());
    }

    /**
     * A text exactly as wide as the paper is returned untouched — the boundary
     * of the guard, which a text one character shorter would not reach.
     */
    @Test
    void centreLeavesAfullWidthTextAlone() {
        String full = "X".repeat(42);
        assertEquals(full, PAPER.centre(full));
    }

    /**
     * A text WIDER than the paper is returned untouched too: centring it would
     * only push it further out.
     */
    @Test
    void centreLeavesAnOverflowingTextAlone() {
        String tooLong = "X".repeat(60);
        assertEquals(tooLong, PAPER.centre(tooLong));
    }

    /**
     * A null text centres to nothing rather than raising — it used to raise,
     * so nothing is taken away. An EMPTY text keeps its historical padding:
     * a line of spaces nobody needs, but the one the register has always
     * printed, and this lot changes no document.
     */
    @Test
    void centreToleratesNullAndKeepsTheHistoricalEmptyPadding() {
        assertEquals("", PAPER.centre(null));
        assertEquals(" ".repeat(21), PAPER.centre(""));
    }

    /**
     * The rule spans the whole paper.
     */
    @Test
    void ruleSpansTheWholePaper() {
        assertEquals("-".repeat(42), PAPER.rule());
        assertEquals("-".repeat(20), new DocumentLayout(20).rule());
    }

    /**
     * A null text wraps to nothing — the null arm, which a caller printing an
     * unadministered message reaches.
     */
    @Test
    void wrapToleratesNull() {
        assertTrue(PAPER.wrap(null).isEmpty());
    }

    /**
     * A blank text wraps to nothing rather than to one empty line: an empty
     * line advances the paper for a message nobody wrote.
     */
    @Test
    void wrapOfablankTextSaysNothing() {
        assertTrue(PAPER.wrap("").isEmpty());
        assertTrue(PAPER.wrap("   ").isEmpty());
    }

    /**
     * A text shorter than the paper stays on one line.
     */
    @Test
    void wrapKeepsAshortTextOnOneLine() {
        assertEquals(List.of("MERCI DE VOTRE VISITE"), PAPER.wrap("MERCI DE VOTRE VISITE"));
    }

    /**
     * A text longer than the paper breaks BETWEEN words, every word survives,
     * and no line exceeds the width.
     */
    @Test
    void wrapBreaksBetweenWords() {
        String text = "FIDELITE INDISPONIBLE VOS AVANTAGES SERONT CREDITES LORS "
                + "DE VOTRE PROCHAIN PASSAGE EN MAGASIN";
        List<String> lines = new DocumentLayout(20).wrap(text);
        assertTrue(lines.size() > 1);
        for (String line : lines) {
            assertTrue(line.length() <= 20, "ligne trop large: '" + line + "'");
        }
        assertEquals(text, String.join(" ", lines));
    }

    /**
     * A word longer than the paper is CUT, and what was already in hand is
     * flushed first — the arm of the inner loop where a line is pending.
     */
    @Test
    void wrapCutsAlongWordAndFlushesWhatWasPending() {
        List<String> lines = new DocumentLayout(10).wrap("DEBUT " + "A".repeat(25) + " FIN");
        assertEquals(List.of("DEBUT", "AAAAAAAAAA", "AAAAAAAAAA", "AAAAA FIN"), lines);
    }

    /**
     * A long word arriving with NOTHING in hand is cut without an empty line
     * being emitted first — the other arm of that same inner guard.
     */
    @Test
    void wrapCutsAlongFirstWordWithoutAnEmptyLine() {
        List<String> lines = new DocumentLayout(10).wrap("A".repeat(25));
        assertEquals(List.of("AAAAAAAAAA", "AAAAAAAAAA", "AAAAA"), lines);
    }

    /**
     * A word that exactly fills the remaining room is appended rather than
     * pushed to the next line — the boundary of the fits-on-this-line guard.
     */
    @Test
    void wrapFillsAlineExactly() {
        assertEquals(List.of("ABCD EFGHI"), new DocumentLayout(10).wrap("ABCD EFGHI"));
        assertEquals(List.of("ABCD EFGHI", "J"), new DocumentLayout(10).wrap("ABCD EFGHI J"));
    }

    /**
     * BO-03-05-01 and -03: a text shorter than the paper is pushed flush right,
     * which is the third alignment a banner line may take.
     */
    @Test
    void rightPushesAShortTextToTheEdge() {
        assertEquals("     ABCDE", new DocumentLayout(10).right("ABCDE"));
    }

    /**
     * A text that exactly fills the paper is answered untouched — the boundary
     * of the padding guard, where the padding is zero and not one space.
     */
    @Test
    void rightLeavesAnExactlyFullLineAlone() {
        assertEquals("ABCDEFGHIJ", new DocumentLayout(10).right("ABCDEFGHIJ"));
    }

    /**
     * A text LONGER than the paper is answered untouched rather than cut:
     * losing characters off a line the shop wrote is worse than a line the
     * printer wraps — the negative-padding arm.
     */
    @Test
    void rightNeverCutsAnOversizedLine() {
        assertEquals("ABCDEFGHIJKL", new DocumentLayout(10).right("ABCDEFGHIJKL"));
    }

    /**
     * A null text is a whole line of paper and no characters — the null arm,
     * which a banner reaches whenever one of its values is absent.
     */
    @Test
    void rightReadsANullTextAsAnEmptyOne() {
        assertEquals(" ".repeat(10), new DocumentLayout(10).right(null));
    }

    /**
     * BO-03-05-08: the QR primitive emits the directive the hardware bridge
     * encodes, trimmed, and nothing at all for a payload nobody typed.
     */
    @Test
    void theQrPrimitiveEmitsItsDirective() {
        assertEquals("[[QR https://magasin.fr/avis]]", PAPER.qr("  https://magasin.fr/avis "));
        assertEquals("", PAPER.qr(null));
        assertEquals("", PAPER.qr("   "));
    }

    /**
     * BO-03-05-08: the bar-code primitive is its twin, for the shops whose
     * readers are linear.
     */
    @Test
    void theBarcodePrimitiveEmitsItsDirective() {
        assertEquals("[[BARCODE C04-000123]]", PAPER.barcode(" C04-000123 "));
        assertEquals("", PAPER.barcode(null));
        assertEquals("", PAPER.barcode("  "));
    }
}
