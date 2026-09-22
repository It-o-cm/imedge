package com.intermarche.pos.ui.report;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a report as a PDF (BO-06-01-03) — paginated, landscape, with the
 * period in the header and a page count in the footer.
 *
 * <p>Written by hand against the PDF 1.4 structure, with no library added to the
 * build, for the same reason as the spreadsheet: a report is text, rules and
 * page breaks, and the part of PDF that does those three is a few hundred lines
 * against a dependency of several megabytes. The two standard Type 1 fonts —
 * Helvetica and its bold — are guaranteed present in every reader, so the file
 * carries no font of its own.
 *
 * <p>Landscape A4, because a report is wide and a portrait page either drops
 * columns or shrinks them below reading size.
 */
@ApplicationScoped
public class PdfReportWriter implements ReportWriter {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PdfReportWriter.class);

    /** The encoding the standard fonts read, and the one accents survive in. */
    private static final Charset WIN_ANSI = Charset.forName("windows-1252");

    /** Page width in points: A4 landscape. */
    private static final int WIDTH = 842;

    /** Page height in points: A4 landscape. */
    private static final int HEIGHT = 595;

    /** Left and right margin in points. */
    private static final int MARGIN = 28;

    /** Body font size in points. */
    private static final int FONT_SIZE = 8;

    /** Height of one table row in points. */
    private static final int ROW_HEIGHT = 13;

    /** Vertical position where the first table row is drawn. */
    private static final int TABLE_TOP = HEIGHT - 72;

    /** Vertical position below which a page is full. */
    private static final int TABLE_BOTTOM = 40;

    /**
     * {@inheritDoc}
     *
     * @return the {@code pdf} token
     */
    @Override
    public String format() {
        return "pdf";
    }

    /**
     * {@inheritDoc}
     *
     * @return the PDF media type
     */
    @Override
    public String contentType() {
        return "application/pdf";
    }

    /**
     * {@inheritDoc}
     *
     * @return the {@code pdf} extension
     */
    @Override
    public String extension() {
        return "pdf";
    }

    /**
     * {@inheritDoc}
     *
     * @param data the executed report
     * @param criteria the edition parameters, printed in the header
     * @return the document's bytes
     */
    @Override
    public byte[] write(ReportData data, ReportCriteria criteria) {
        LOGGER.info("Entering method write for report: " + data.definition.code());
        List<List<Object>> body = new ArrayList<>(data.rows);
        if (data.hasTotals()) {
            body.add(data.totals);
        }
        int perPage = Math.max(1, (TABLE_TOP - TABLE_BOTTOM) / ROW_HEIGHT);
        int pageCount = Math.max(1, (body.size() + perPage - 1) / perPage);
        int[] widths = widths(data);
        List<String> pages = new ArrayList<>();
        for (int p = 0; p < pageCount; p++) {
            int from = p * perPage;
            int to = Math.min(from + perPage, body.size());
            boolean totalsHere = data.hasTotals() && to == body.size();
            pages.add(content(data, criteria, body.subList(from, to), widths, p + 1, pageCount, totalsHere));
        }
        LOGGER.info("Exiting method write");
        return assemble(pages);
    }

    /**
     * Shares the printable width between the columns, in proportion to the
     * longest text each one has to carry.
     *
     * <p>Proportional and not equal: a column of dates and a column of labels
     * given the same width either waste half a page or clip the labels. The
     * share is bounded below so a narrow column still shows its header.
     *
     * @param data the executed report
     * @return one width in points per column
     */
    private int[] widths(ReportData data) {
        List<ReportColumn> columns = data.definition.columns();
        int[] longest = new int[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            longest[c] = columns.get(c).label().length();
        }
        for (List<Object> row : data.rows) {
            for (int c = 0; c < columns.size() && c < row.size(); c++) {
                longest[c] = Math.max(longest[c], ReportFormat.cell(columns.get(c), row.get(c)).length());
            }
        }
        int total = 0;
        for (int length : longest) {
            total += Math.max(6, length);
        }
        int available = WIDTH - 2 * MARGIN;
        int[] widths = new int[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            widths[c] = Math.max(30, Math.max(6, longest[c]) * available / Math.max(1, total));
        }
        return widths;
    }

    /**
     * Draws one page: the title, the period, the header rule, the rows and the
     * page number.
     *
     * @param data the executed report
     * @param criteria the edition parameters
     * @param rows the rows this page carries
     * @param widths the column widths
     * @param page the 1-based page number
     * @param pageCount the number of pages
     * @param totalsLast whether the last row of this page is the total row
     * @return the page's content stream
     */
    private String content(ReportData data, ReportCriteria criteria, List<List<Object>> rows,
                           int[] widths, int page, int pageCount, boolean totalsLast) {
        List<ReportColumn> columns = data.definition.columns();
        StringBuilder out = new StringBuilder();
        out.append(text("F2", 13, MARGIN, HEIGHT - 36, data.definition.label()));
        out.append(text("F1", 9, MARGIN, HEIGHT - 52,
                ReportHeading.period(criteria) + "   —   " + data.definition.requirement()));
        int y = TABLE_TOP;
        int x = MARGIN;
        for (int c = 0; c < columns.size(); c++) {
            out.append(text("F2", FONT_SIZE, x, y, clip(columns.get(c).label(), widths[c])));
            x += widths[c];
        }
        out.append(rule(MARGIN, y - 4, WIDTH - MARGIN, y - 4));
        y -= ROW_HEIGHT;
        for (int r = 0; r < rows.size(); r++) {
            boolean total = totalsLast && r == rows.size() - 1;
            if (total) {
                out.append(rule(MARGIN, y + ROW_HEIGHT - 4, WIDTH - MARGIN, y + ROW_HEIGHT - 4));
            }
            x = MARGIN;
            List<Object> row = rows.get(r);
            for (int c = 0; c < columns.size(); c++) {
                Object value = c < row.size() ? row.get(c) : null;
                String rendered = ReportFormat.cell(columns.get(c), value);
                if (total && c == 0 && rendered.isEmpty()) {
                    rendered = "TOTAL";
                }
                out.append(text(total ? "F2" : "F1", FONT_SIZE, x, y, clip(rendered, widths[c])));
                x += widths[c];
            }
            y -= ROW_HEIGHT;
        }
        out.append(text("F1", 7, MARGIN, 22, data.size() + " ligne(s)"));
        out.append(text("F1", 7, WIDTH - MARGIN - 60, 22, "Page " + page + " / " + pageCount));
        return out.toString();
    }

    /**
     * Emits one run of text.
     *
     * @param font the font resource name
     * @param size the font size in points
     * @param x the left edge in points
     * @param y the baseline in points
     * @param value the characters to draw
     * @return the content-stream fragment
     */
    private String text(String font, int size, int x, int y, String value) {
        return "BT /" + font + " " + size + " Tf " + x + " " + y + " Td (" + escape(value) + ") Tj ET\n";
    }

    /**
     * Emits one horizontal rule.
     *
     * @param x1 the start's horizontal position
     * @param y1 the start's vertical position
     * @param x2 the end's horizontal position
     * @param y2 the end's vertical position
     * @return the content-stream fragment
     */
    private String rule(int x1, int y1, int x2, int y2) {
        return "0.5 w " + x1 + " " + y1 + " m " + x2 + " " + y2 + " l S\n";
    }

    /**
     * Cuts a text to what its column can show, at roughly half an em per
     * character — the average width of Helvetica's lower case.
     *
     * @param value the rendered text
     * @param width the column's width in points
     * @return the text, shortened with an ellipsis when it did not fit
     */
    String clip(String value, int width) {
        int room = Math.max(1, (int) ((width - 4) / (FONT_SIZE * 0.5)));
        if (value.length() <= room) {
            return value;
        }
        return value.substring(0, Math.max(1, room - 1)) + "…";
    }

    /**
     * Escapes the three characters a PDF string reserves, and replaces the
     * narrow no-break space the amounts are grouped with.
     *
     * <p>U+202F does not exist in WinAnsiEncoding, and a standard font asked for
     * a character it has no place for draws a question mark: without this
     * substitution every amount over a thousand prints {@code 1?284}. U+00A0 is
     * in the encoding, does not break a line either, and is what the reader
     * expects to see between the thousands.
     *
     * @param value the raw text
     * @return the escaped text
     */
    private String escape(String value) {
        return value.replace('\u202f', '\u00a0')
                .replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    /**
     * Assembles the objects, the cross-reference table and the trailer.
     *
     * @param pages the content stream of each page
     * @return the document's bytes
     */
    private byte[] assemble(List<String> pages) {
        List<String> objects = new ArrayList<>();
        int contentFirst = 5;
        StringBuilder kids = new StringBuilder();
        for (int p = 0; p < pages.size(); p++) {
            kids.append(contentFirst + pages.size() + p).append(" 0 R ");
        }
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        objects.add("<< /Type /Pages /Count " + pages.size() + " /Kids [" + kids.toString().trim() + "] >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
        for (String page : pages) {
            objects.add("<< /Length " + page.getBytes(WIN_ANSI).length + " >>\nstream\n" + page + "endstream");
        }
        for (int p = 0; p < pages.size(); p++) {
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + WIDTH + " " + HEIGHT + "]"
                    + " /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >>"
                    + " /Contents " + (contentFirst + p) + " 0 R >>");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        byte[] header = "%PDF-1.4\n".getBytes(WIN_ANSI);
        out.writeBytes(header);
        int position = header.length;
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(position);
            byte[] body = ((i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n").getBytes(WIN_ANSI);
            out.writeBytes(body);
            position += body.length;
        }
        StringBuilder xref = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) {
            xref.append(String.format("%010d 00000 n \n", offset));
        }
        xref.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(position).append("\n%%EOF\n");
        out.writeBytes(xref.toString().getBytes(WIN_ANSI));
        return out.toByteArray();
    }
}
