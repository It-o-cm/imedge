package com.intermarche.edge.ui.report;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A report, executed: its definition, the rows it produced and the total row
 * computed from them.
 *
 * <p>The object the four renderings share. Rows carry RAW values, not text, so
 * the spreadsheet can write numbers as numbers and the chart can measure them;
 * only the screen, the CSV and the PDF turn them into characters, each by the
 * column's kind.
 */
public class ReportData {

    /** The report that produced these rows. */
    public final ReportDefinition definition;

    /** The rows, in the order the report returned them. */
    public final List<List<Object>> rows;

    /** The total row: one cell per column, null where nothing is totalled. */
    public final List<Object> totals;

    /**
     * Assembles an executed report and computes its total row.
     *
     * @param definition the report that produced the rows
     * @param rows the produced rows
     */
    public ReportData(ReportDefinition definition, List<List<Object>> rows) {
        this.definition = definition;
        this.rows = rows;
        this.totals = total(definition.columns(), rows);
    }

    /**
     * Returns the number of rows.
     *
     * @return the row count
     */
    public int size() {
        return rows.size();
    }

    /**
     * Whether the report produced nothing.
     *
     * @return true when there is not one row
     */
    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /**
     * Whether a total row is worth showing.
     *
     * <p>Not when nothing is totalled — a list of reason codes has no total —
     * and not when the report is empty, where a row of zeros would claim a
     * measurement that was never made.
     *
     * @return true when at least one column is totalled and a row exists
     */
    public boolean hasTotals() {
        if (rows.isEmpty()) {
            return false;
        }
        for (Object cell : totals) {
            if (cell != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sums every totalled column over every row.
     *
     * @param columns the report's columns
     * @param rows the produced rows
     * @return one cell per column, null where the column is not totalled
     */
    private static List<Object> total(List<ReportColumn> columns, List<List<Object>> rows) {
        List<Object> sums = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            ReportColumn column = columns.get(i);
            if (!column.totalled()) {
                sums.add(null);
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (List<Object> row : rows) {
                // A row shorter than the column list, or carrying null where a
                // number was expected, contributes nothing rather than breaking
                // the whole total: a report with one hole still has a usable sum.
                if (i >= row.size()) {
                    continue;
                }
                Object cell = row.get(i);
                if (cell instanceof BigDecimal decimal) {
                    sum = sum.add(decimal);
                } else if (cell instanceof Number number) {
                    sum = sum.add(BigDecimal.valueOf(number.doubleValue()));
                }
            }
            sums.add(column.kind() == ReportColumn.Kind.INTEGER ? (Object) sum.longValue() : sum);
        }
        return sums;
    }
}
