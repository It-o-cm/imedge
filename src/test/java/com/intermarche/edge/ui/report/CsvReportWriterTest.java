package com.intermarche.edge.ui.report;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests dedicated to {@link CsvReportWriter}.
 *
 * <p>Branch enumeration for the target class: {@code write} has the first /
 * not-first column arm and the with- / without-totals arm; {@code append} has
 * the first / not-first cell arm and the ternary's short-row (null) / present
 * arm; {@code escape} has its null arm, its nothing-to-quote arm and each of the
 * four characters (separator, quote, line feed, carriage return) that force
 * quoting. The residual branch left red by {@code ReportWritersTest} is
 * {@code escape}'s null arm, reachable only through a column whose label is null,
 * which {@code csvNullColumnLabelRendersEmpty} exercises.
 */
class CsvReportWriterTest {

    /**
     * A report definition whose columns and rows the test controls entirely.
     *
     * @param code the report code
     * @param label the report title
     * @param columns the columns in display order
     * @param producedRows the rows the report returns
     */
    private record StubDefinition(String code, String label, List<ReportColumn> columns,
            List<List<Object>> producedRows) implements ReportDefinition {

        /**
         * {@inheritDoc}
         *
         * @return the report code
         */
        @Override
        public String code() {
            return code;
        }

        /**
         * {@inheritDoc}
         *
         * @return the report title
         */
        @Override
        public String label() {
            return label;
        }

        /**
         * {@inheritDoc}
         *
         * @return an arbitrary requirement code
         */
        @Override
        public String requirement() {
            return "BO-06-01-02";
        }

        /**
         * {@inheritDoc}
         *
         * @return an arbitrary family
         */
        @Override
        public ReportFamily family() {
            return ReportFamily.values()[0];
        }

        /**
         * {@inheritDoc}
         *
         * @return the columns in display order
         */
        @Override
        public List<ReportColumn> columns() {
            return columns;
        }

        /**
         * {@inheritDoc}
         *
         * @param criteria the ignored edition parameters
         * @return the produced rows
         */
        @Override
        public List<List<Object>> rows(ReportCriteria criteria) {
            return producedRows;
        }
    }

    /**
     * Builds the edition parameters used throughout: one named day.
     *
     * @return the criteria naming 16/09/2026
     */
    private ReportCriteria criteria() {
        ReportCriteria criteria = new ReportCriteria();
        criteria.period = ReportPeriod.DAY;
        criteria.reference = LocalDate.of(2026, 9, 16);
        return criteria;
    }

    /**
     * Writes a report of the given definition and rows to a CSV string.
     *
     * @param definition the report definition
     * @return the CSV, decoded as UTF-8
     */
    private String write(StubDefinition definition) {
        ReportData data = new ReportData(definition, definition.producedRows());
        return new String(new CsvReportWriter().write(data, criteria()), StandardCharsets.UTF_8);
    }

    /**
     * The CSV opens with a UTF-8 byte-order mark, names the report and its
     * period on the first line, then carries the column headers on the second.
     */
    @Test
    void csvOpensWithTheMarkTheHeadingAndTheHeaders() {
        StubDefinition definition = new StubDefinition("rep", "État", List.of(
                ReportColumn.text("a", "Type"), ReportColumn.count("b", "Nombre")),
                List.of(Arrays.asList("x", 1L)));
        String csv = write(definition);
        assertTrue(csv.startsWith("﻿État — Jour : 16/09/2026\n"));
        assertTrue(csv.contains("\nType;Nombre\n"));
    }

    /**
     * A field carrying the separator, a quote, a line feed or a carriage return
     * is quoted and its inner quotes are doubled, while a plain field is left
     * untouched — the four quoting arms and the nothing-to-quote arm of
     * {@code escape}.
     */
    @Test
    void csvQuotesEveryDangerousCharacterAndLeavesPlainFieldsAlone() {
        StubDefinition definition = new StubDefinition("rep", "T", List.of(
                ReportColumn.text("a", "A"), ReportColumn.text("b", "B"),
                ReportColumn.text("c", "C"), ReportColumn.text("d", "D"),
                ReportColumn.text("e", "E")),
                List.of(Arrays.asList("x;y", "say \"hi\"", "A\nB", "C\rD", "plain")));
        String csv = write(definition);
        assertTrue(csv.contains("\"x;y\""));
        assertTrue(csv.contains("\"say \"\"hi\"\"\""));
        assertTrue(csv.contains("\"A\nB\""));
        assertTrue(csv.contains("\"C\rD\""));
        assertTrue(csv.contains(";plain\n"));
    }

    /**
     * A column whose label is null renders as an empty field rather than the
     * text "null" — the residual null arm of {@code escape}.
     */
    @Test
    void csvNullColumnLabelRendersEmpty() {
        StubDefinition definition = new StubDefinition("rep", "T", List.of(
                ReportColumn.text("a", "First"),
                new ReportColumn("b", null, ReportColumn.Kind.TEXT, false)),
                List.of(Arrays.asList("x", "y")));
        String csv = write(definition);
        assertTrue(csv.contains("\nFirst;\n"));
        assertFalse(csv.contains("null"));
    }

    /**
     * A row shorter than the column list is padded with an empty last cell
     * rather than truncating the line — the null arm of the cell ternary in
     * {@code append}.
     */
    @Test
    void csvPadsAShortRow() {
        StubDefinition definition = new StubDefinition("rep", "T", List.of(
                ReportColumn.text("a", "A"), ReportColumn.text("b", "B")),
                List.of(List.of("only")));
        String csv = write(definition);
        String[] lines = csv.split("\n");
        assertEquals("only;", lines[2]);
    }

    /**
     * The total row closes the CSV when at least one column is totalled and a
     * row exists — the with-totals arm of {@code write}.
     */
    @Test
    void csvClosesWithTheTotalRow() {
        StubDefinition definition = new StubDefinition("rep", "T", List.of(
                ReportColumn.text("a", "A"), ReportColumn.amount("b", "B")),
                List.of(Arrays.asList("x", new BigDecimal("10.00"))));
        String csv = write(definition);
        String[] lines = csv.split("\n");
        assertEquals(4, lines.length);
        assertEquals(";10,00 €", lines[3]);
    }

    /**
     * A report whose columns are none of them totalled ends on its last data
     * row — the without-totals arm of {@code write}.
     */
    @Test
    void csvWithoutTotalsEndsOnTheLastRow() {
        StubDefinition definition = new StubDefinition("rep", "T", List.of(
                ReportColumn.text("a", "A"), ReportColumn.text("b", "B")),
                List.of(Arrays.asList("x", "y")));
        String csv = write(definition);
        assertTrue(csv.endsWith("x;y\n"));
    }

    /**
     * An empty report writes the heading and headers but no total row, since a
     * row of zeros would claim a measurement never made — the empty-rows arm of
     * {@code hasTotals} through {@code write}.
     */
    @Test
    void csvOfAnEmptyReportHasNoTotalRow() {
        StubDefinition definition = new StubDefinition("rep", "T", List.of(
                ReportColumn.text("a", "A"), ReportColumn.amount("b", "B")),
                List.of());
        String csv = write(definition);
        String[] lines = csv.split("\n");
        assertEquals(2, lines.length);
    }

    /**
     * The writer announces itself as UTF-8 CSV text and carries the csv format
     * token and extension.
     */
    @Test
    void csvAnnouncesItself() {
        CsvReportWriter writer = new CsvReportWriter();
        assertEquals("csv", writer.format());
        assertEquals("csv", writer.extension());
        assertEquals("text/csv; charset=UTF-8", writer.contentType());
    }
}
