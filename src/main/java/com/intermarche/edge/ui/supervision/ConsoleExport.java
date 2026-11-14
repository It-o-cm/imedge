package com.intermarche.edge.ui.supervision;

import com.intermarche.edge.ui.report.CsvReportWriter;
import com.intermarche.edge.ui.report.ReportColumn;
import com.intermarche.edge.ui.report.ReportCriteria;
import com.intermarche.edge.ui.report.ReportData;
import com.intermarche.edge.ui.report.ReportDefinition;
import com.intermarche.edge.ui.report.ReportFamily;
import com.intermarche.edge.ui.report.ReportWriter;
import com.intermarche.edge.ui.report.XlsxReportWriter;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Exports the list of a supervision or deployment console as CSV or XLS
 * (BO-08-01-17, BO-08-03-06, BO-08-04-15, "Export liste XLS, CSV").
 *
 * <p>The SAME writers as the report catalogue — the semicolon CSV with its
 * byte-order mark that a spreadsheet opens in French, and the Office Open
 * XML workbook — fed with the console's own columns and rows. A console is
 * not a report: it has no period to choose, so its heading reads the day it
 * was exported.
 */
public final class ConsoleExport {

    /** Utility class: no instance. */
    private ConsoleExport() {
    }

    /**
     * The rows of a console seen as a report the writers can render.
     *
     * @param code the file name
     * @param label the heading
     * @param columns the columns
     * @param lines the rows
     */
    record Table(String code, String label, List<ReportColumn> columns, List<List<Object>> lines)
            implements ReportDefinition {

        /**
         * The requirement block of the consoles.
         *
         * @return the block code
         */
        @Override
        public String requirement() {
            return "BO-08";
        }

        /**
         * The family the writers ask for; a console belongs to none, so the
         * first one is answered.
         *
         * @return a family
         */
        @Override
        public ReportFamily family() {
            return ReportFamily.SALES;
        }

        /**
         * The console's rows, whatever the criteria.
         *
         * @param criteria ignored
         * @return the rows
         */
        @Override
        public List<List<Object>> rows(ReportCriteria criteria) {
            return lines;
        }
    }

    /**
     * Renders a console's list as a downloadable file.
     *
     * @param format {@code xlsx} for a workbook, anything else for CSV
     * @param code the file name, without extension
     * @param label the heading written on the first line
     * @param columns the columns
     * @param rows the rows
     * @return the 200 response carrying the file as an attachment
     */
    public static Response export(String format, String code, String label, List<ReportColumn> columns,
                                  List<List<Object>> rows) {
        ReportWriter writer = "xlsx".equalsIgnoreCase(format) ? new XlsxReportWriter() : new CsvReportWriter();
        ReportData data = new ReportData(new Table(code, label, columns, rows), rows);
        byte[] bytes = writer.write(data, ReportCriteria.fromParams(null));
        return Response.ok(bytes, writer.contentType())
                .header("Content-Disposition", "attachment; filename=\"" + code + "." + writer.extension() + "\"")
                .build();
    }
}
