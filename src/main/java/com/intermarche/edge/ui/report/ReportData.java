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

    /**
     * The rows, in the order the report returned them, each group of a broken
     * report closed by its {@link Subtotal} row.
     */
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
        this.totals = total(definition.columns(), rows);
        int index = breakIndex(definition);
        this.rows = index < 0 ? rows : broken(definition, rows, index);
    }

    /**
     * A SUBTOTAL row of a broken report (« rupture »): the sums of the
     * totalled columns over one group, named in the break column. A row like
     * the others for the renderings — the CSV and the spreadsheet carry it as
     * the paper does — and recognisable by its type, so the grand total and
     * the chart never count it twice.
     */
    public static final class Subtotal extends ArrayList<Object> {

        /** Serialization version, the list being serializable. */
        private static final long serialVersionUID = 1L;
    }

    /**
     * Whether a row is a subtotal the frame inserted.
     *
     * @param row the row
     * @return true for a {@link Subtotal}
     */
    public static boolean isSubtotal(List<Object> row) {
        return row instanceof Subtotal;
    }

    /**
     * Returns the rows the report produced, without the subtotals.
     *
     * @return the data rows, in order
     */
    public List<List<Object>> dataRows() {
        List<List<Object>> data = new ArrayList<>();
        for (List<Object> row : rows) {
            if (!isSubtotal(row)) {
                data.add(row);
            }
        }
        return data;
    }

    /**
     * Closes every group of consecutive rows sharing the break column's value
     * with its subtotal row.
     *
     * @param definition the report, read for its columns
     * @param rows the rows, ordered by the break column
     * @param index the break column index
     * @return the rows with one subtotal after each group
     */
    private static List<List<Object>> broken(ReportDefinition definition, List<List<Object>> rows, int index) {
        List<List<Object>> out = new ArrayList<>();
        List<List<Object>> group = new ArrayList<>();
        String current = null;
        for (List<Object> row : rows) {
            String key = index < row.size() ? String.valueOf(row.get(index)) : "";
            if (!group.isEmpty() && !key.equals(current)) {
                out.add(subtotal(definition, group, current, index));
                group = new ArrayList<>();
            }
            current = key;
            group.add(row);
            out.add(row);
        }
        if (!group.isEmpty()) {
            out.add(subtotal(definition, group, current, index));
        }
        return out;
    }

    /**
     * Finds the index of the column a report breaks on.
     *
     * @param definition the report
     * @return the index, or -1 when the report declares no break or names no column
     */
    private static int breakIndex(ReportDefinition definition) {
        String key = definition.breakKey();
        if (key == null) {
            return -1;
        }
        List<ReportColumn> columns = definition.columns();
        for (int i = 0; i < columns.size(); i++) {
            if (key.equals(columns.get(i).key())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Builds the subtotal row of one group.
     *
     * @param definition the report
     * @param group the group's rows
     * @param key the group's break value
     * @param index the break column index
     * @return the subtotal row
     */
    private static Subtotal subtotal(ReportDefinition definition, List<List<Object>> group, String key, int index) {
        Subtotal row = new Subtotal();
        List<Object> sums = total(definition.columns(), group);
        for (int i = 0; i < sums.size(); i++) {
            row.add(i == index ? "Sous-total " + key : sums.get(i));
        }
        return row;
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
                // A subtotal the report wrote itself — a mean, a recap — is no
                // data: counting it would count its rows twice.
                if (isSubtotal(row)) {
                    continue;
                }
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
