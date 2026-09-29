package com.intermarche.pos.ui.report;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
 * Dedicated unit test for {@link XlsxReportWriter}, complementing
 * {@link ReportWritersTest} with the residual branch it left uncovered: the
 * false arm of the {@code c < row.size()} guard in {@code row}, reached only
 * when a produced row is SHORTER than the column list, so the missing trailing
 * cells are omitted rather than read out of bounds. A handful of core cell-kind
 * assertions accompany it so the class stands on its own.
 */
class XlsxReportWriterTest {

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
     * Reads the parts of a written workbook, keyed by their path.
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
     * Reads the single worksheet part of a written workbook.
     *
     * @param data the executed report
     * @param criteria the edition parameters
     * @return the worksheet XML
     * @throws Exception when the package cannot be read
     */
    private String sheet(ReportData data, ReportCriteria criteria) throws Exception {
        return parts(new XlsxReportWriter().write(data, criteria)).get("xl/worksheets/sheet1.xml");
    }

    /**
     * A row shorter than the column list omits its missing trailing cells rather
     * than reading past its end: the false arm of the {@code c < row.size()}
     * guard, the residual branch. The two present cells are written; the four
     * absent ones are not.
     *
     * @throws Exception when the package cannot be read
     */
    @Test
    void shortRowOmitsItsMissingCells() throws Exception {
        ReportFixture fixture = new ReportFixture(-1, List.of(List.of("K", "L")));
        String sheet = sheet(new ReportData(fixture, fixture.rows(null)), criteria());
        assertTrue(sheet.contains("r=\"A3\""));
        assertTrue(sheet.contains("r=\"B3\""));
        assertFalse(sheet.contains("r=\"C3\""));
        assertFalse(sheet.contains("r=\"D3\""));
        assertFalse(sheet.contains("r=\"E3\""));
        assertFalse(sheet.contains("r=\"F3\""));
    }

    /**
     * A null cell in the middle of a row is written as no cell at all: the true
     * arm of the {@code value == null} guard.
     *
     * @throws Exception when the package cannot be read
     */
    @Test
    void nullCellIsWrittenAsNoCell() throws Exception {
        ReportFixture fixture = new ReportFixture(-1,
                List.of(Arrays.asList("K", null, 1L, null, null, null)));
        String sheet = sheet(new ReportData(fixture, fixture.rows(null)), criteria());
        assertTrue(sheet.contains("r=\"A3\""));
        assertFalse(sheet.contains("r=\"B3\""));
    }

    /**
     * Each numeric kind is written as a number carrying its own style index:
     * integers count, amounts money, percentages a share.
     *
     * @throws Exception when the package cannot be read
     */
    @Test
    void numericKindsAreTypedByStyle() throws Exception {
        ReportFixture fixture = ReportFixture.standard();
        String sheet = sheet(new ReportData(fixture, fixture.rows(null)), criteria());
        assertTrue(sheet.contains("<c r=\"C3\" s=\"4\"><v>1284</v></c>"));
        assertTrue(sheet.contains("<c r=\"D3\" s=\"2\"><v>18422.55</v></c>"));
        assertTrue(sheet.contains("<c r=\"E3\" s=\"3\"><v>47.3</v></c>"));
    }

    /**
     * A textual cell is carried inline and styled by the text style, which is
     * zero on a body row.
     *
     * @throws Exception when the package cannot be read
     */
    @Test
    void textCellIsCarriedInline() throws Exception {
        ReportFixture fixture = ReportFixture.standard();
        String sheet = sheet(new ReportData(fixture, fixture.rows(null)), criteria());
        assertTrue(sheet.contains("<c r=\"A3\" s=\"0\" t=\"inlineStr\">"));
        assertTrue(sheet.contains("Carte &amp; &lt;banque&gt;"));
    }

    /**
     * A day, a time and a moment each become an Excel serial with its style, and
     * a value that is none of the three falls back to an inline string styled
     * zero rather than to a wrong date.
     *
     * @throws Exception when the package cannot be read
     */
    @Test
    void temporalKindsBecomeSerialsAndTheRestFallsBack() throws Exception {
        ReportDefinition definition = new ReportFixture(-1, List.of()) {
            /**
             * {@inheritDoc}
             *
             * @return one column of each temporal kind and a text-typed misfit
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
                LocalDate.of(2026, 9, 16), LocalTime.NOON,
                LocalDateTime.of(2026, 9, 16, 12, 0), "hier"));
        String sheet = sheet(new ReportData(definition, rows), criteria());
        assertTrue(sheet.contains("<c r=\"A3\" s=\"5\"><v>46281</v></c>"));
        assertTrue(sheet.contains("<c r=\"B3\" s=\"7\"><v>0.5000000000</v></c>"));
        assertTrue(sheet.contains("<c r=\"C3\" s=\"6\"><v>46281.5000000000</v></c>"));
        assertTrue(sheet.contains("<c r=\"D3\" s=\"0\" t=\"inlineStr\""));
    }

    /**
     * A report whose columns are all un-totalled and whose rows exist writes no
     * total row: the false arm of {@code hasTotals} in {@code sheet}. The last
     * row is the single body row, at line three.
     *
     * @throws Exception when the package cannot be read
     */
    @Test
    void reportWithoutTotalsWritesNoTotalRow() throws Exception {
        ReportDefinition definition = new ReportFixture(-1, List.of(List.of("x", "y"))) {
            /**
             * {@inheritDoc}
             *
             * @return two text columns, neither totalled
             */
            @Override
            public List<ReportColumn> columns() {
                return List.of(ReportColumn.text("a", "A"), ReportColumn.text("b", "B"));
            }
        };
        String sheet = sheet(new ReportData(definition, List.of(List.of("x", "y"))), criteria());
        assertTrue(sheet.contains("r=\"A3\""));
        assertFalse(sheet.contains("r=\"A4\""));
    }

    /**
     * A totalled report closes on a bold total row: the true arm of
     * {@code hasTotals}. The standard fixture totals its count and amount over
     * its two rows, so a fifth line carries the summed count and amount.
     *
     * @throws Exception when the package cannot be read
     */
    @Test
    void totalledReportWritesABoldTotalRow() throws Exception {
        ReportFixture fixture = ReportFixture.standard();
        String sheet = sheet(new ReportData(fixture, fixture.rows(null)), criteria());
        assertTrue(sheet.contains("<c r=\"C5\" s=\"4\"><v>2186</v></c>"));
        assertTrue(sheet.contains("<c r=\"D5\" s=\"2\"><v>35532.65</v></c>"));
    }

    /**
     * An empty report writes its title and header rows and nothing after: the
     * body loop runs zero times and there is no total row.
     *
     * @throws Exception when the package cannot be read
     */
    @Test
    void emptyReportWritesOnlyTitleAndHeader() throws Exception {
        ReportFixture fixture = new ReportFixture(-1, List.of());
        String sheet = sheet(new ReportData(fixture, fixture.rows(null)), criteria());
        assertTrue(sheet.contains("<row r=\"1\">"));
        assertTrue(sheet.contains("<row r=\"2\">"));
        assertFalse(sheet.contains("r=\"A3\""));
    }

    /**
     * The workbook carries the six parts a reader needs to open it, and the
     * title row names the report and its period.
     *
     * @throws Exception when the package cannot be read
     */
    @Test
    void workbookCarriesItsPartsAndTitle() throws Exception {
        ReportFixture fixture = ReportFixture.standard();
        Map<String, String> parts = parts(new XlsxReportWriter().write(
                new ReportData(fixture, fixture.rows(null)), criteria()));
        assertTrue(parts.containsKey("[Content_Types].xml"));
        assertTrue(parts.containsKey("_rels/.rels"));
        assertTrue(parts.containsKey("xl/workbook.xml"));
        assertTrue(parts.containsKey("xl/_rels/workbook.xml.rels"));
        assertTrue(parts.containsKey("xl/styles.xml"));
        assertTrue(parts.containsKey("xl/worksheets/sheet1.xml"));
        assertTrue(parts.get("xl/worksheets/sheet1.xml")
                .contains("&#233;tat des r&#232;glements".replace("&#233;", "É").replace("&#232;", "è")));
    }

    /**
     * Cell references run within the first twenty-six columns on one letter and
     * past them on two: both sides of the {@code rest < 0} break.
     */
    @Test
    void cellReferencesCrossTheTwentySixthColumn() {
        XlsxReportWriter writer = new XlsxReportWriter();
        assertEquals("A1", writer.reference(0, 1));
        assertEquals("Z1", writer.reference(25, 1));
        assertEquals("AA1", writer.reference(26, 1));
        assertEquals("AB12", writer.reference(27, 12));
    }

    /**
     * A sheet name is stripped of the characters Excel refuses, cut to the
     * length it accepts, and replaced by a default when nothing is left: the
     * illegal-character, too-long and empty arms of {@code sheetName}.
     */
    @Test
    void sheetNameIsMadeLegal() {
        XlsxReportWriter writer = new XlsxReportWriter();
        assertEquals("Ventes  rayon", writer.sheetName("Ventes /rayon"));
        assertEquals(31, writer.sheetName("A".repeat(40)).length());
        assertEquals("Rapport", writer.sheetName("[]"));
    }

    /**
     * A legal name short enough to keep is returned untouched: the false arm of
     * both the empty guard and the length guard in {@code sheetName}.
     */
    @Test
    void sheetNameShortAndLegalIsKept() {
        assertEquals("Ventes", new XlsxReportWriter().sheetName("Ventes"));
    }

    /**
     * The workbook announces itself as an OOXML spreadsheet on the three
     * identity methods.
     */
    @Test
    void workbookAnnouncesItself() {
        XlsxReportWriter writer = new XlsxReportWriter();
        assertEquals("xlsx", writer.format());
        assertEquals("xlsx", writer.extension());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                writer.contentType());
    }
}
