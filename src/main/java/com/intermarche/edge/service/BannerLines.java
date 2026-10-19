package com.intermarche.edge.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE LINES OF A BANNER, as the back office edits them and as the layout
 * stores them (BO-03-05-05 and -06).
 *
 * <p>The questionnaire asks for a banner edited LINE BY LINE: each line
 * activated or not, centred, flush left or flush right, in bold or not, and a
 * deactivated line printing nothing. A free-text layout gives none of that,
 * and a second storage beside the layout would give a banner two truths.
 *
 * <p>So there is ONE storage — the banner's own source — and this class is
 * the two-way door onto it: {@link #parse} reads a source as rows for the
 * table, {@link #format} writes rows back as a source. The round trip is exact
 * for every shape the table can produce, which is what lets a paramétreur move
 * between the table and the raw source without losing a line.
 *
 * <p>A line is written as the aligned text, wrapped in {@code fmt.bold} when it
 * is bold, and prefixed by the inactive marker when it is switched off — a
 * marker and not a deletion, so a seasonal line can be turned off in October
 * and back on in December without being retyped.
 */
public final class BannerLines {

    /** The marker an inactive line carries, kept so the text survives. */
    private static final String OFF = "{! OFF !}";

    /** A line laid out by one of the layout's alignment helpers. */
    private static final Pattern ALIGNED =
            Pattern.compile("^\\{fmt\\.(centre|right)\\(\"(.*)\"\\)\\}$");

    /** A line wrapped in the emphasis helper. */
    private static final Pattern BOLD =
            Pattern.compile("^\\{fmt\\.bold\\((.*)\\)\\}$");

    /**
     * Not instantiated: the class is the door and not a thing.
     */
    private BannerLines() {
    }

    /**
     * How a line is laid out on the paper.
     */
    public enum Align {

        /** Flush left, which is plain text. */
        LEFT,

        /** Centred on the administered width. */
        CENTRE,

        /** Flush right on the administered width. */
        RIGHT
    }

    /**
     * ONE LINE of a banner, as the table shows it.
     *
     * @param text the text printed, never null
     * @param align how it is laid out
     * @param bold whether it is emphasised
     * @param active whether it is printed at all
     */
    public record Line(String text, Align align, boolean bold, boolean active) {
    }

    /**
     * Reads a banner source as the rows of the editing table.
     *
     * <p>A line this class did not write — a loop, a condition, anything a
     * paramétreur typed by hand — is answered as a LEFT line carrying its own
     * text verbatim: the table never silently drops what it does not
     * understand, and writing the rows back restores it unchanged.
     *
     * @param source the banner source, possibly null
     * @return the rows, empty for a null or empty source
     */
    public static List<Line> parse(String source) {
        List<Line> rows = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return rows;
        }
        for (String raw : source.split("\\n", -1)) {
            String line = raw;
            boolean active = true;
            if (line.startsWith(OFF)) {
                active = false;
                line = line.substring(OFF.length());
            }
            boolean bold = false;
            Matcher emphasised = BOLD.matcher(line);
            if (emphasised.matches()) {
                bold = true;
                line = emphasised.group(1);
            }
            Align align = Align.LEFT;
            String text = line;
            Matcher aligned = ALIGNED.matcher(line);
            if (aligned.matches()) {
                align = "centre".equals(aligned.group(1)) ? Align.CENTRE : Align.RIGHT;
                text = aligned.group(2);
            }
            // Anything else is a LEFT line carrying its own text: a plain line
            // needs no helper, and a line this class did not write survives the
            // round trip rather than being dropped or quoted.
            rows.add(new Line(text, align, bold, active));
        }
        // A source ends on a newline far more often than it carries a last
        // empty line on purpose, and an empty trailing row would come back as a
        // blank line of paper on every ticket.
        while (!rows.isEmpty() && rows.get(rows.size() - 1).text().isEmpty()
                && rows.get(rows.size() - 1).align() == Align.LEFT) {
            rows.remove(rows.size() - 1);
        }
        return rows;
    }

    /**
     * Writes the rows of the editing table back as a banner source.
     *
     * @param rows the rows, possibly null
     * @return the source, empty for no rows
     */
    public static String format(List<Line> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Line row : rows) {
            String text = row.text() == null ? "" : row.text();
            String body = switch (row.align()) {
                case CENTRE -> "{fmt.centre(\"" + text + "\")}";
                case RIGHT -> "{fmt.right(\"" + text + "\")}";
                case LEFT -> text;
            };
            if (row.bold()) {
                body = "{fmt.bold(" + body + ")}";
            }
            if (!row.active()) {
                body = OFF + body;
            }
            out.append(body).append('\n');
        }
        return out.toString();
    }
}
