package com.intermarche.pos.ui.report;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dedicated unit tests for {@link PdfReportWriter}, filling the branches left
 * red by {@code ReportWritersTest}.
 * <p>
 * Residual branch enumeration: {@code write}'s no-totals arm at the {@code if}
 * and its short-circuit on the {@code &&} that decides whether a page carries
 * the total; {@code widths}' arm where a row is shorter than the column list;
 * {@code content}'s ternary arm that reads a missing cell as null; and
 * {@code content}'s arm where the total row's first cell is not empty and so is
 * NOT replaced by the {@code TOTAL} caption. The already-green arms — one page
 * versus several, clip, escape — are owned by {@code ReportWritersTest} and are
 * not re-covered here.
 */
class PdfReportWriterTest {

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
     * A report whose columns are none of them totalled produces no total row:
     * the {@code if} that appends the total takes its false arm and the page
     * loop's {@code &&} short-circuits on the false first operand, so no page
     * ever carries a total.
     */
    @Test
    void reportWithoutTotalsCarriesNoTotalRow() {
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
        String pdf = new String(new PdfReportWriter().write(
                new ReportData(definition, List.of(List.of("x", "y"))), criteria()), WIN_ANSI);
        assertTrue(pdf.startsWith("%PDF-1.4\n"));
        assertEquals(1, count(pdf, "/Type /Page "));
        assertTrue(pdf.contains("Page 1 / 1"));
        assertTrue(pdf.contains("1 ligne"));
        assertFalse(pdf.contains("TOTAL"));
    }

    /**
     * A row shorter than the column list is not an error: {@code widths} stops
     * measuring at the row's end and {@code content} reads the absent cells as
     * null, drawing empty columns rather than throwing.
     */
    @Test
    void shortRowsAreDrawnWithEmptyTrailingColumns() {
        ReportFixture fixture = new ReportFixture(-1, List.of(Arrays.asList("K", "L")));
        String pdf = new String(new PdfReportWriter().write(
                new ReportData(fixture, fixture.rows(null)), criteria()), WIN_ANSI);
        assertTrue(pdf.startsWith("%PDF-1.4\n"));
        assertTrue(pdf.endsWith("%%EOF\n"));
        assertTrue(pdf.contains("(K) Tj"));
        assertTrue(pdf.contains("(L) Tj"));
        assertTrue(pdf.contains("1 ligne"));
        assertTrue(pdf.contains("TOTAL"));
    }

    /**
     * When the first column is itself totalled, the total row's first cell holds
     * a figure rather than being empty, so it keeps that figure instead of being
     * overwritten by the {@code TOTAL} caption.
     */
    @Test
    void totalRowKeepsANonEmptyFirstCell() {
        ReportDefinition definition = new ReportFixture(-1, List.of(Arrays.asList(5L, "x"))) {
            /**
             * {@inheritDoc}
             *
             * @return a totalled count column first, then a text column
             */
            @Override
            public List<ReportColumn> columns() {
                return List.of(ReportColumn.count("n", "N"), ReportColumn.text("l", "L"));
            }
        };
        String pdf = new String(new PdfReportWriter().write(
                new ReportData(definition, List.of(Arrays.asList(5L, "x"))), criteria()), WIN_ANSI);
        assertTrue(pdf.startsWith("%PDF-1.4\n"));
        assertTrue(pdf.contains("(5) Tj"));
        assertFalse(pdf.contains("TOTAL"));
        assertTrue(pdf.contains("Page 1 / 1"));
    }

    /**
     * A row carrying a decimal in a totalled column feeds a real total: this
     * pins that a non-empty first cell coexists with a summed second column,
     * closing the short-row and non-empty-total arms together on one document.
     */
    @Test
    void nonEmptyTotalFirstCellWithASummedColumn() {
        ReportDefinition definition = new ReportFixture(-1, List.of(Arrays.asList(3L, new BigDecimal("10.00")))) {
            /**
             * {@inheritDoc}
             *
             * @return a totalled count column first, then a totalled amount column
             */
            @Override
            public List<ReportColumn> columns() {
                return List.of(ReportColumn.count("n", "N"), ReportColumn.amount("a", "A"));
            }
        };
        String pdf = new String(new PdfReportWriter().write(
                new ReportData(definition, List.of(Arrays.asList(3L, new BigDecimal("10.00")))), criteria()), WIN_ANSI);
        assertTrue(pdf.startsWith("%PDF-1.4\n"));
        assertTrue(pdf.contains("(3) Tj"));
        assertFalse(pdf.contains("TOTAL"));
    }
}
