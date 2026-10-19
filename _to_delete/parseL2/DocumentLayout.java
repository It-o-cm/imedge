package com.intermarche.edge.service;

import io.quarkus.qute.TemplateData;

import java.util.ArrayList;
import java.util.List;

/**
 * The typesetting primitives of a printed document, bound to one paper width.
 *
 * <p>Why this class exists. A receipt printer takes a stream of characters on a
 * fixed number of columns: aligning a value to the right edge, centring a
 * banner and wrapping a sentence are the three operations every layout of the
 * register performs, and they were locked inside {@code TicketPrinterService}
 * as private methods. A layout written as an administered template could not
 * reach them, so it could only pad with hard-coded spaces — which falls apart
 * the moment a label is longer than the author guessed, and which is why the
 * back office's starter template never matched the receipt the register
 * actually prints.
 *
 * <p>So the primitives move HERE, and both worlds call the same code: the Java
 * layout through its own helpers, a template through the {@code fmt} value the
 * renderer places in its data. That is the point — when a factory template is
 * asked to reproduce the built-in receipt character for character, it must not
 * be re-implementing the alignment, it must be running it.
 *
 * <p>The WIDTH is carried by the instance and not passed on every call, because
 * it belongs to the document and not to the line: it is the administered
 * {@code width} of the template, or the register's own paper width for the
 * built-in layout. A template that writes {@code {fmt.line(...)}} is therefore
 * right on any paper, and the administered width finally decides something.
 *
 * <p>Every primitive returns the text of a line WITHOUT its terminator. The
 * newline belongs to whoever assembles the document — a template ends its
 * lines by itself, a StringBuilder appends them — and a primitive that carried
 * one would double it in a template and be impossible to use inline.
 *
 * <p>{@code @TemplateData} is what makes these methods reachable from a
 * template: the build generates their value resolver, so {@code fmt.line(...)}
 * resolves without reflection at runtime. A bare engine assembled by hand —
 * which is what a unit test uses — needs a {@code ReflectionValueResolver}
 * instead, and resolves the very same methods.
 */
@TemplateData
public final class DocumentLayout {

    /** The paper width in characters, the number every primitive aligns on. */
    private final int width;

    /**
     * Builds the primitives for one paper width.
     *
     * @param width the paper width in characters; a width below one is raised
     *              to one, because no alignment is possible on nothing and a
     *              document printed too narrow beats a document that throws
     */
    public DocumentLayout(int width) {
        this.width = Math.max(1, width);
    }

    /**
     * Returns the paper width these primitives align on.
     *
     * @return the width in characters
     */
    public int getWidth() {
        return width;
    }

    /**
     * Lays a label and a value on one line, the value flush right.
     *
     * <p>When the two do not fit, ONE space is kept between them and the line
     * overflows rather than the value being cut: a truncated amount on a
     * receipt is worse than a line the printer wraps.
     *
     * @param left the label, flush left
     * @param right the value, flush right
     * @return the line, without its terminator
     */
    public String line(String left, String right) {
        String start = left == null ? "" : left;
        String end = right == null ? "" : right;
        int space = width - start.length() - end.length();
        if (space < 1) {
            space = 1;
        }
        return start + " ".repeat(space) + end;
    }

    /**
     * Centres a text on the paper.
     *
     * <p>Padded on the LEFT only: a receipt line carries no trailing spaces,
     * and a printer given them would advance the head for nothing.
     *
     * <p>A NULL text centres to nothing — it used to raise, so nothing is
     * being taken away. An EMPTY text keeps its historical padding: it is a
     * line of spaces nobody needs, but it is what the register has always
     * printed and this lot changes no document.
     *
     * @param text the text to centre, possibly null
     * @return the padded text, without its terminator
     */
    public String centre(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() >= width) {
            return text;
        }
        return " ".repeat((width - text.length()) / 2) + text;
    }

    /**
     * Draws a full-width horizontal rule.
     *
     * @return a line of dashes as wide as the paper, without its terminator
     */
    /**
     * Marks a text as BOLD for the printer (BO-03-05-01, -03, -05 and -06).
     *
     * <p>A DIRECTIVE AND NOT AN ESCAPE SEQUENCE. The register stays text: it
     * writes {@code [[BOLD]]…[[/BOLD]]} the way it already writes
     * {@code [[BARCODE …]]} and {@code [[IMAGE …]]}, and the hardware bridge
     * turns the pair into the printer's own emphasis commands. A layout that
     * reaches a printer which knows nothing of it shows the markers rather than
     * garbled control bytes, which is the failure a paramétreur can read.
     *
     * <p>The markers DO NOT COUNT as characters of the line: a bold line is
     * centred on the text it carries, so {@code centre(bold(x))} would pad on a
     * width the paper never sees. A layout therefore wraps the ALIGNED text,
     * {@code bold(centre(x))}, and the Javadoc says so because the other order
     * is the natural mistake.
     *
     * @param text the text to emphasise, possibly null
     * @return the text between its two markers, empty for a null or empty text
     */
    public String bold(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return "[[BOLD]]" + text + "[[/BOLD]]";
    }

    /**
     * Lays a text out FLUSH RIGHT on the paper (BO-03-05-01, -03).
     *
     * <p>The third alignment the questionnaire names, beside the centring this
     * class already gives and the plain text that is the left. A line longer
     * than the paper is answered untouched rather than cut: losing characters
     * off a banner the shop wrote is worse than a line that wraps on the
     * printer.
     *
     * @param text the text to push right, possibly null
     * @return the padded line
     */
    public String right(String text) {
        String value = text == null ? "" : text;
        int padding = width - value.length();
        return padding <= 0 ? value : " ".repeat(padding) + value;
    }

    public String rule() {
        return "-".repeat(width);
    }

    /**
     * Wraps a text onto lines no wider than the paper, breaking on spaces.
     *
     * <p>A word longer than the whole width is CUT, because the alternative is
     * a line the printer truncates silently; everything else breaks between
     * words, so an administered sentence stays readable whatever its length.
     *
     * @param text the text to wrap, possibly null or blank
     * @return the wrapped lines, without their terminators; empty when there is
     *         nothing to say
     */
    public List<String> wrap(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        StringBuilder current = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String piece = word;
            while (piece.length() > width) {
                if (current.length() > 0) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                lines.add(piece.substring(0, width));
                piece = piece.substring(width);
            }
            if (current.length() == 0) {
                current.append(piece);
            } else if (current.length() + 1 + piece.length() <= width) {
                current.append(' ').append(piece);
            } else {
                lines.add(current.toString());
                current.setLength(0);
                current.append(piece);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }
}
