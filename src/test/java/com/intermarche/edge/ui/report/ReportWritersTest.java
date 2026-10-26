package com.intermarche.edge.ui.report;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the three renderers: {@link CsvReportWriter},
 * {@link XlsxReportWriter} and {@link PdfReportWriter}.
 * <p>
 * Branch enumeration: the CSV's {@code escape} has its null arm, its
 * nothing-to-quote arm and each of the four characters that force quoting;
 * {@code append} has the short-row arm; {@code write} has the with- and
 * without-totals arms. The spreadsheet's {@code cell} has one arm per column
 * kind, {@code serial} has its three temporal arms and its fallback,
 * {@code reference} is read on both sides of the 26-column boundary, and
 * {@code sheetName} has its illegal-character, too-long and empty arms. The
 * PDF's {@code clip} has its fits / does-not-fit arms, {@code escape} has each
 * of its four substitutions, and {@code write} is read on one page and on
 * several.
 */
class ReportWritersTest {

    /** The encoding the PDF's standard fonts read. */
    private static final Charset WIN_ANSI = Charset.forName("windows-1252");

    /**
     * Builds the edition parameters used throughout: one named day.
     *
     * @return the criteria
     */
    private ReportCriteria criteria() {
        ReportCriteria criteria = new ReportCriteria();
        criteria.period = ReportPeriod.DAY;
        criteria.reference = LocalDate.of(2026, 9, 16);
        return criteria;
    }

    /**
     * Builds the standard executed report.
     *
     * @return the executed report
     */
    private ReportData data() {
        ReportFixture fixture = ReportFixture.standard();
        return new ReportData(fixture, fixture.rows(null));
    }

    /**
     * The CSV opens with a byte-order mark, names the report and its period,
     * then carries the headers.
     */
    @Test
    void csvOpensWithTheMarkAndTheHeading() {
        String csv = new String(new CsvReportWriter().write(data(), criteria()), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("﻿État des règlements — Jour : 16/09/2026\n"));
        assertTrue(csv.contains("Type;Libellé du mode;Nombre;Valeur nette;% du total;Dernier règlement\n"));
    }

    /**
     * A field carrying the separator or a quote is quoted, and its quotes are
     * doubled.
     */
    @Test
    void csvQuotesWhatNeedsQuoting() {
        String csv = new String(new CsvReportWriter().write(data(), criteria()), StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"Espèces \"\"caisse\"\"\""));
    }

    /**
     * A field carrying a line break is quoted too, and a plain one is not.
     */
    @Test
    void csvQuotesLineBreaksAndLeavesPlainFieldsAlone() {
        ReportFixture fixture = new ReportFixture(-1, List.of(
                Arrays.asList("A\nB", "plain", 1L, BigDecimal.ONE, null, null),
                Arrays.asList("C\rD", "x;y", 1L, BigDecimal.ONE, null, null)));
        String csv = new String(new CsvReportWriter().write(
                new ReportData(fixture, fixture.rows(null)), criteria()), StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"A\nB\""));
        assertTrue(csv.contains("\"C\rD\""));
        assertTrue(csv.contains("\"x;y\""));
        assertTrue(csv.contains(";plain;"));
    }

    /**
     * The total row closes the CSV when the report has one, and a short row is
     * padded rather than truncating the line.
     */
    @Test
    void csvClosesWithTheTotalAndPadsShortRows() {
        ReportFixture fixture = new ReportFixture(-1, List.of(List.of("K", "L", 3L)));
        String csv = new String(new CsvReportWriter().write(
                new ReportData(fixture, fixture.rows(null)), criteria()), StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        assertEquals(4, lines.length);
        assertEquals(";;3;0,00 €;;", lines[3]);
    }

    /**
     * A report with no total row ends on its last row.
     */
    @Test
    void csvWithoutTotalsEndsOnTheLastRow() {
        ReportDefinition definition = new ReportFixture(-1, List.of(List.of("x", "y"))) {
            /**
             * {@inheritDoc}
             *
             * @return two columns, neither totalled
             */
            @Override
            public List<ReportColumn> columns() {
                return List.of(ReportColumn.text("a", "A"), ReportColumn.text("b", "B"));
            }
        };
        String csv = new String(new CsvReportWriter().write(
                new ReportData(definition, List.of(List.of("x", "y"))), criteria()), StandardCharsets.UTF_8);
        assertTrue(csv.endsWith("x;y\n"));
    }

    /**
     * The CSV announces itself as UTF-8 text and carries the csv extension.
     */
    @Test
    void csvAnnouncesItself() {
        CsvReportWriter writer = new CsvReportWriter();
        assertEquals("csv", writer.format());
        assertEquals("csv", writer.extension());
        assertTrue(writer.contentType().contains("text/csv"));
    }

    /**
     * Reads the parts of a written workbook.
     *
     * @param bytes the workbook
     * @return each part's text, by name
     * @throws Exception when the package cannot be read
     */
    private Map<String, String> parts(byte[] bytes) throws Exception {
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry = zip.getNextEntry();
            while (entry != null) {
                parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                entry = zip.getNextEntry();
            }
        }
        return parts;
    }

    /**
     * The workbook carries the six parts a reader needs to open it.
     *
     * @throws Exception when the package cannot be read
     */
    @Test
    void workbookCarriesItsParts() throws Exception {
        Map<String, String> parts = parts(new XlsxReportWriter().write(data(), criteria()));
        assertTrue(parts.containsKey("[Content_Types].xml"));
        assertTrue(parts.containsKey("_rels/.rels"));
        assertTrue(parts.containsKey("xl/workbook.xml"));
        assertTrue(parts.containsKey("xl/_rels/workbook.xml.rels"));
        assertTrue(parts.containsKey("xl/styles.xml"));
        assertTrue(parts.containsKey("xl/worksheets/sheet1.xml"));
    }

    /**
     * Figures are written as numbers and dates as serials, each with its own
     * style — the whole point of the spreadsheet over the CSV.
     *
     * @throws Exception when the package cannot be read
     */
    @Test
    void figuresAreWrittenAsNumbers() throws Exception {
        String sheet = parts(new XlsxReportWriter().write(data(), criteria())).get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("<c r=\"D3\" s=\"2\"><v>18422.55</v></c>"));
        assertTrue(sheet.contains("<c r=\"C3\" s=\"4\"><v>1284</v></c>"));
        assertTrue(sheet.contains("<c r=\"E3\" s=\"3\"><v>47.3</v></c>"));
        assertTrue(sheet.contains("<c r=\"F3\" s=\"6\">"));
    }

    /**
     * The characters XML reserves are escaped rather than breaking the sheet.
     *
     * @throws Exception when the package cannot be read
     */
    @Test
    void reservedCharactersAreEscaped() throws Exception {
        String sheet = parts(new XlsxReportWriter().write(data(), criteria())).get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("Carte &amp; &lt;banque&gt;"));
        assertTrue(sheet.contains("Esp&#232;ces &quot;caisse&quot;".replace("&#232;", "è")));
    }

    /**
     * A day, a time and a moment each become a serial; anything else falls back
     * to text rather than to a wrong date.
     *
     * @throws Exception when the package cannot be read
     */
    @Test
    void temporalValuesBecomeSerialsAndTheRestFallsBack() throws Exception {
        ReportDefinition definition = new ReportFixture(-1, List.of()) {
            /**
             * {@inheritDoc}
             *
             * @return one column of each temporal kind
             */
            @Override
            public List<ReportColumn> columns() {
                return List.of(
                        new ReportColumn("d", "D", ReportColumn.Kind.DATE, false),
                        new ReportColumn("h", "H", ReportColumn.Kind.TIME, false),
                        new ReportColumn("m", "M", ReportColumn.Kind.DATETIME, false),
                        new ReportColumn("x", "X", ReportColumn.Kind.DATE, false));
            }
        };
        List<List<Object>> rows = List.of(Arrays.asList(
                LocalDate.of(2026, 9, 16), java.time.LocalTime.NOON,
                LocalDateTime.of(2026, 9, 16, 12, 0), "hier"));
        String sheet = parts(new XlsxReportWriter().write(new ReportData(definition, rows), criteria()))
                .get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("<c r=\"A3\" s=\"5\"><v>46281</v></c>"));
        assertTrue(sheet.contains("<c r=\"B3\" s=\"7\"><v>0.5000000000</v></c>"));
        assertTrue(sheet.contains("<c r=\"C3\" s=\"6\"><v>46281.5000000000</v></c>"));
        assertTrue(sheet.contains("<c r=\"D3\" s=\"0\" t=\"inlineStr\""));
    }

    /**
     * A null cell is written as no cell at all, which is what a sparse sheet
     * expects.
     *
     * @throws Exception when the package cannot be read
     */
    @Test
    void nullCellIsWrittenAsNoCell() throws Exception {
        ReportFixture fixture = new ReportFixture(-1, List.of(Arrays.asList("K", null, 1L, null, null, null)));
        String sheet = parts(new XlsxReportWriter().write(
                new ReportData(fixture, fixture.rows(null)), criteria())).get("xl/worksheets/sheet1.xml");
        assertFalse(sheet.contains("r=\"B3\""));
        assertTrue(sheet.contains("r=\"A3\""));
    }

    /**
     * Cell references run past the twenty-sixth column into two letters.
     */
    @Test
    void cellReferencesPassTheTwentySixthColumn() {
        XlsxReportWriter writer = new XlsxReportWriter();
        assertEquals("A1", writer.reference(0, 1));
        assertEquals("Z1", writer.reference(25, 1));
        assertEquals("AA1", writer.reference(26, 1));
        assertEquals("AB12", writer.reference(27, 12));
    }

    /**
     * A sheet name is stripped of the characters Excel refuses, cut to the
     * length it accepts, and replaced when nothing is left.
     */
    @Test
    void sheetNameIsMadeLegal() {
        XlsxReportWriter writer = new XlsxReportWriter();
        assertEquals("Ventes  rayon", writer.sheetName("Ventes /rayon"));
        assertEquals(31, writer.sheetName("A".repeat(40)).length());
        assertEquals("Rapport", writer.sheetName("[]"));
    }

    /**
     * The workbook announces itself as an OOXML spreadsheet.
     */
    @Test
    void workbookAnnouncesItself() {
        XlsxReportWriter writer = new XlsxReportWriter();
        assertEquals("xlsx", writer.format());
        assertEquals("xlsx", writer.extension());
        assertTrue(writer.contentType().contains("spreadsheetml"));
    }

    /**
     * The PDF opens on its version, closes on its end marker and carries one
     * page object for a short report.
     */
    @Test
    void pdfIsOnePageForAShortReport() {
        String pdf = new String(new PdfReportWriter().write(data(), criteria()), WIN_ANSI);
        assertTrue(pdf.startsWith("%PDF-1.4\n"));
        assertTrue(pdf.endsWith("%%EOF\n"));
        assertEquals(1, count(pdf, "/Type /Page "));
        assertTrue(pdf.contains("Page 1 / 1"), pdf);
    }

    /**
     * A long report is broken into pages, and the last one carries the total.
     */
    @Test
    void longReportIsBrokenIntoPages() {
        java.util.List<java.util.List<Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 120; i++) {
            rows.add(Arrays.asList("K" + i, "L" + i, 1L, BigDecimal.ONE, null, null));
        }
        ReportFixture fixture = new ReportFixture(-1, rows);
        String pdf = new String(new PdfReportWriter().write(
                new ReportData(fixture, rows), criteria()), WIN_ANSI);
        assertEquals(4, count(pdf, "/Type /Page "));
        assertTrue(pdf.contains("Page 4 / 4"), pdf);
        assertTrue(pdf.contains("TOTAL"), pdf);
    }

    /**
     * The narrow space the amounts are grouped with is replaced by one the
     * standard fonts can draw, and the three reserved characters are escaped.
     */
    @Test
    void pdfSubstitutesTheNarrowSpaceAndEscapes() {
        ReportFixture fixture = new ReportFixture(-1, List.of(
                Arrays.asList("(a)\\b", "L", 1284L, new BigDecimal("1234.00"), null, null)));
        String pdf = new String(new PdfReportWriter().write(
                new ReportData(fixture, fixture.rows(null)), criteria()), WIN_ANSI);
        assertTrue(pdf.contains("\\(a\\)\\\\b"), pdf);
        assertFalse(pdf.contains(" "));
        assertTrue(pdf.contains("1 284"), pdf);
    }

    /**
     * A text wider than its column is cut and marked, and one that fits is left
     * alone.
     */
    @Test
    void pdfClipsWhatDoesNotFit() {
        PdfReportWriter writer = new PdfReportWriter();
        assertEquals("court", writer.clip("court", 200));
        assertTrue(writer.clip("A".repeat(200), 40).endsWith("…"));
        assertTrue(writer.clip("A".repeat(200), 40).length() < 200);
    }

    /**
     * The PDF announces itself.
     */
    @Test
    void pdfAnnouncesItself() {
        PdfReportWriter writer = new PdfReportWriter();
        assertEquals("pdf", writer.format());
        assertEquals("pdf", writer.extension());
        assertEquals("application/pdf", writer.contentType());
    }

    /**
     * Counts the occurrences of a fragment.
     *
     * @param haystack the text searched
     * @param needle the fragment counted
     * @return the number of occurrences
     */
    private int count(String haystack, String needle) {
        int found = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            found++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return found;
    }

    /**
     * A report stating a preamble, broken on its first column and signed.
     *
     * @param rows the rows
     * @return the executed report
     */
    private ReportData signed(java.util.List<java.util.List<Object>> rows) {
        ReportFixture fixture = new ReportFixture(-1, rows) {
            /**
             * {@inheritDoc}
             *
             * @param criteria the edition parameters
             * @return one line
             */
            @Override
            public java.util.List<String> preamble(ReportCriteria criteria) {
                return java.util.List.of("Sondage n° 1");
            }

            /**
             * {@inheritDoc}
             *
             * @return the type column
             */
            @Override
            public String breakKey() {
                return "k";
            }

            /**
             * {@inheritDoc}
             *
             * @return the cashier's signature
             */
            @Override
            public String signature() {
                return "Signature de la caissière";
            }
        };
        return new ReportData(fixture, rows);
    }

    /**
     * Two rows of two groups.
     *
     * @return the rows
     */
    private java.util.List<java.util.List<Object>> grouped() {
        return java.util.List.of(
                Arrays.asList("A", "L1", 1L, BigDecimal.ONE, null, null),
                Arrays.asList("B", "L2", 2L, BigDecimal.TEN, null, null));
    }

    /**
     * The CSV states the preamble under the heading, the subtotals and the
     * signature line.
     */
    @Test
    void csvStatesThePreambleTheSubtotalsAndTheSignature() {
        String csv = new String(new CsvReportWriter().write(signed(grouped()), criteria()), StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        assertEquals("Sondage n° 1", lines[1]);
        assertTrue(csv.contains("Sous-total A"));
        assertTrue(csv.endsWith("Signature de la caissière :\n"));
    }

    /**
     * The workbook writes the preamble rows, the subtotals in bold and the
     * signature two rows under the total.
     *
     * @throws Exception when the package cannot be read
     */
    @Test
    void workbookStatesThePreambleTheSubtotalsAndTheSignature() throws Exception {
        String sheet = parts(new XlsxReportWriter().write(signed(grouped()), criteria())).get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("<row r=\"2\"><c r=\"A2\" s=\"0\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Sondage n° 1"));
        assertTrue(sheet.contains("s=\"1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Sous-total A"));
        assertTrue(sheet.contains("<row r=\"10\"><c r=\"A10\" s=\"1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Signature de la caissière :"));
    }

    /**
     * The PDF prints the preamble, draws the subtotals in bold and signs the
     * last page only.
     */
    @Test
    void pdfStatesThePreambleTheSubtotalsAndTheSignature() {
        java.util.List<java.util.List<Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            rows.add(Arrays.asList("K" + i, "L" + i, 1L, BigDecimal.ONE, null, null));
        }
        String pdf = new String(new PdfReportWriter().write(signed(rows), criteria()), WIN_ANSI);
        assertTrue(pdf.contains("(Sondage n\u00b0 1) Tj"), pdf);
        assertTrue(pdf.contains("/F2 8 Tf"), pdf);
        assertTrue(pdf.contains("(Sous-total K0) Tj"), pdf);
        assertEquals(1, count(pdf, "Signature de la caissi"));
        assertTrue(count(pdf, "/Type /Page ") > 1);
    }

    /**
     * A report whose texts the bundles know, edited in Dutch (BO-06-01-02).
     *
     * @return the executed report
     */
    private ReportData translatable() {
        List<List<Object>> rows = List.of(Arrays.asList("Espèces", new BigDecimal("12.50")));
        ReportFixture fixture = new ReportFixture(-1, rows) {
            /**
             * {@inheritDoc}
             *
             * @return a title the bundles translate
             */
            @Override
            public String label() {
                return "Montant de la TVA";
            }

            /**
             * {@inheritDoc}
             *
             * @return a text column and an amount column
             */
            @Override
            public List<ReportColumn> columns() {
                return List.of(ReportColumn.text("label", "Désignation"), ReportColumn.amount("total", "Montant TTC"));
            }

            /**
             * {@inheritDoc}
             *
             * @param criteria the edition parameters
             * @return one sentence built around a value
             */
            @Override
            public List<String> preamble(ReportCriteria criteria) {
                return List.of("Client : SARL Durand");
            }

            /**
             * {@inheritDoc}
             *
             * @return the customer's signature
             */
            @Override
            public String signature() {
                return "Signature du client";
            }
        };
        return new ReportData(fixture, rows);
    }

    /**
     * The edition parameters of a Dutch export.
     *
     * @return the criteria naming 16/09/2026, in Dutch
     */
    private ReportCriteria dutchCriteria() {
        ReportCriteria criteria = criteria();
        criteria.language = "nl";
        return criteria;
    }

    /**
     * The CSV of a Dutch page writes its title, period, preamble, headings,
     * textual cells and signature in Dutch, its amounts as in French.
     */
    @Test
    void csvFollowsTheEditionLanguage() {
        com.intermarche.edge.ui.admin.BackOfficeMessages dutch = com.intermarche.edge.ui.admin.BackOfficeMessages.in("nl");
        String csv = new String(new CsvReportWriter().write(translatable(), dutchCriteria()), StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        assertEquals("\ufeffBtw-bedrag — " + dutch.text("Jour") + " : 16/09/2026", lines[0]);
        assertEquals("Klant: SARL Durand", lines[1]);
        assertEquals("Omschrijving;" + dutch.text("Montant TTC"), lines[2]);
        assertEquals(dutch.text("Espèces") + ";12,50 €", lines[3]);
        assertTrue(csv.endsWith("Handtekening van de klant :\n"));
        assertFalse(csv.contains("Désignation"));
    }

    /**
     * The workbook of a Dutch page names its sheet, writes its texts in Dutch
     * and keeps its amounts as numbers.
     *
     * @throws Exception when the package cannot be read
     */
    @Test
    void workbookFollowsTheEditionLanguage() throws Exception {
        Map<String, String> parts = parts(new XlsxReportWriter().write(translatable(), dutchCriteria()));
        String sheet = parts.get("xl/worksheets/sheet1.xml");
        assertTrue(parts.get("xl/workbook.xml").contains("name=\"Btw-bedrag\""));
        assertTrue(sheet.contains(">Btw-bedrag — "));
        assertTrue(sheet.contains(">Klant: SARL Durand<"));
        assertTrue(sheet.contains(">Omschrijving<"));
        assertTrue(sheet.contains("<v>12.50</v>"));
        assertTrue(sheet.contains(">Handtekening van de klant :<"));
        assertFalse(sheet.contains("Désignation"));
    }

    /**
     * The PDF of a Dutch page prints its texts, its total, its line count and
     * its page number in Dutch.
     */
    @Test
    void pdfFollowsTheEditionLanguage() {
        com.intermarche.edge.ui.admin.BackOfficeMessages dutch = com.intermarche.edge.ui.admin.BackOfficeMessages.in("nl");
        String pdf = new String(new PdfReportWriter().write(translatable(), dutchCriteria()), WIN_ANSI);
        assertTrue(pdf.contains("(Btw-bedrag)"), pdf);
        assertTrue(pdf.contains("(Klant: SARL Durand)"), pdf);
        assertTrue(pdf.contains("(Omschrijving)"), pdf);
        assertTrue(pdf.contains("(" + dutch.text("Espèces") + ")"), pdf);
        assertTrue(pdf.contains("(12,50"), pdf);
        assertTrue(pdf.contains("(" + dutch.get("t.total-2") + ")"), pdf);
        assertTrue(pdf.contains("1 " + dutch.get("t.ligne-s").replace("(", "\\(").replace(")", "\\)")), pdf);
        assertTrue(pdf.contains(dutch.get("t.page") + " 1 / 1"), pdf);
        assertTrue(pdf.contains("Handtekening van de klant : "), pdf);
        assertFalse(pdf.contains("Désignation"));
    }
}
