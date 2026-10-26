package com.intermarche.edge.ui.report;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;

/**
 * Renders a report as a semicolon-separated CSV (BO-06-01-02).
 *
 * <p>Semicolons and a UTF-8 byte-order mark, because the file is opened in a
 * French Excel nine times out of ten: with commas it lands in one column, and
 * without the mark the accented labels arrive as mojibake. The same two choices
 * the journal export already made.
 *
 * <p>Values are rendered for a HUMAN, like the screen — an export is the list in
 * another container, not another measurement. The reader who wants to compute
 * takes the spreadsheet.
 *
 * <p>The title, the period, the preamble, the headings, the textual cells and
 * the signature are written in the language of the edition
 * ({@link ReportCriteria#language}), as the screen shows them; the numbers are
 * written the same whatever the language.
 */
@ApplicationScoped
public class CsvReportWriter implements ReportWriter {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CsvReportWriter.class);

    /** The field separator a French Excel expects. */
    private static final char SEPARATOR = ';';

    /**
     * {@inheritDoc}
     *
     * @return the {@code csv} token
     */
    @Override
    public String format() {
        return "csv";
    }

    /**
     * {@inheritDoc}
     *
     * @return the CSV media type, UTF-8
     */
    @Override
    public String contentType() {
        return "text/csv; charset=UTF-8";
    }

    /**
     * {@inheritDoc}
     *
     * @return the {@code csv} extension
     */
    @Override
    public String extension() {
        return "csv";
    }

    /**
     * {@inheritDoc}
     *
     * @param data the executed report
     * @param criteria the edition parameters, written as a first line
     * @return the CSV bytes, byte-order mark included
     */
    @Override
    public byte[] write(ReportData data, ReportCriteria criteria) {
        LOGGER.info("Entering method write for report: " + data.definition.code());
        com.intermarche.edge.ui.admin.BackOfficeMessages messages =
                com.intermarche.edge.ui.admin.BackOfficeMessages.in(criteria.language);
        StringBuilder csv = new StringBuilder("﻿");
        csv.append(escape(messages.text(data.definition.label()) + " — " + ReportHeading.period(criteria, messages)))
                .append('\n');
        for (String line : data.definition.preamble(criteria)) {
            csv.append(escape(messages.text(line))).append('\n');
        }
        boolean first = true;
        for (ReportColumn column : data.definition.columns()) {
            if (!first) {
                csv.append(SEPARATOR);
            }
            first = false;
            csv.append(escape(messages.text(column.label())));
        }
        csv.append('\n');
        for (java.util.List<Object> row : data.rows) {
            append(csv, data, row, messages);
        }
        if (data.hasTotals()) {
            append(csv, data, data.totals, messages);
        }
        if (data.definition.signature() != null) {
            csv.append(escape(messages.text(data.definition.signature()) + " :")).append('\n');
        }
        LOGGER.info("Exiting method write");
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Appends one row, cell by cell, rendered by its column's kind.
     *
     * @param csv the document being built
     * @param data the executed report, read for its columns
     * @param row the row to append
     * @param messages the language of the edition, for the textual cells
     */
    private void append(StringBuilder csv, ReportData data, java.util.List<Object> row,
                        com.intermarche.edge.ui.admin.BackOfficeMessages messages) {
        for (int i = 0; i < data.definition.columns().size(); i++) {
            if (i > 0) {
                csv.append(SEPARATOR);
            }
            Object value = i < row.size() ? row.get(i) : null;
            ReportColumn column = data.definition.columns().get(i);
            String rendered = ReportFormat.cell(column, value);
            csv.append(escape(column.numeric() ? rendered : messages.text(rendered)));
        }
        csv.append('\n');
    }

    /**
     * Quotes a field when it carries a separator, a quote or a line break.
     *
     * @param value the rendered text
     * @return the CSV field
     */
    private String escape(String value) {
        if (value.indexOf(SEPARATOR) < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
