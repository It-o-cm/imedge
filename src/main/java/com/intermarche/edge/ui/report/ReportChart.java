package com.intermarche.edge.ui.report;

import java.math.BigDecimal;
import java.util.List;

/**
 * Draws a report's plotted column as a bar chart (BO-06-01-04).
 *
 * <p>An SVG built on the SERVER and inlined in the page: no charting library, no
 * script, nothing to fetch. A back office opened on a store's network — which may
 * have no way out to a CDN — draws the same chart as one opened at head office,
 * and the same markup is what a browser prints when the operator prints the page.
 *
 * <p>A bar per row, so the chart only appears on reports whose rows are a small
 * ordered set: hours of the day, settlement methods, cashiers. A report that
 * returns four hundred tickets declares no plotted column and shows no chart
 * rather than four hundred slivers.
 */
public final class ReportChart {

    /** Drawing width in user units; the SVG scales to its box. */
    private static final int WIDTH = 960;

    /** Drawing height in user units. */
    private static final int HEIGHT = 320;

    /** Room left at the bottom for the labels. */
    private static final int BOTTOM = 56;

    /** Room left at the top for the value labels. */
    private static final int TOP = 24;

    /** Room left at the left for the axis. */
    private static final int LEFT = 8;

    /** The largest number of bars worth drawing. */
    static final int MAX_BARS = 40;

    /**
     * Not instantiable.
     */
    private ReportChart() {
    }

    /**
     * Whether a chart can be drawn for this executed report.
     *
     * @param data the executed report
     * @return true when the report declares a plotted column and has between one
     *         and {@link #MAX_BARS} rows
     */
    public static boolean available(ReportData data) {
        int column = data.definition.chartColumn();
        return column >= 0 && !data.rows.isEmpty() && data.rows.size() <= MAX_BARS;
    }

    /**
     * Renders the chart as an inline SVG.
     *
     * @param data the executed report
     * @return the SVG markup, or an empty string when nothing can be drawn
     */
    public static String svg(ReportData data) {
        if (!available(data)) {
            return "";
        }
        int valueIndex = data.definition.chartColumn();
        int labelIndex = data.definition.chartLabelColumn();
        ReportColumn valueColumn = data.definition.columns().get(valueIndex);
        BigDecimal top = BigDecimal.ZERO;
        for (List<Object> row : data.rows) {
            BigDecimal value = valueIndex < row.size() ? ReportFormat.decimal(row.get(valueIndex)) : BigDecimal.ZERO;
            if (value.compareTo(top) > 0) {
                top = value;
            }
        }
        // Every bar at zero: a flat chart divided by a zero maximum would draw
        // nothing at all, or worse, divide by zero. It says so instead.
        if (top.signum() <= 0) {
            return "<p class=\"report-chart-empty\">Aucune valeur à représenter sur la période.</p>";
        }
        int count = data.rows.size();
        int slot = (WIDTH - LEFT) / count;
        int barWidth = Math.max(4, (int) (slot * 0.62));
        StringBuilder svg = new StringBuilder();
        svg.append("<svg class=\"report-chart\" viewBox=\"0 0 ").append(WIDTH).append(' ').append(HEIGHT)
                .append("\" preserveAspectRatio=\"none\" role=\"img\" aria-label=\"")
                .append(escape(valueColumn.label())).append("\">");
        svg.append("<line x1=\"0\" y1=\"").append(HEIGHT - BOTTOM).append("\" x2=\"").append(WIDTH)
                .append("\" y2=\"").append(HEIGHT - BOTTOM).append("\" class=\"report-chart-axis\"/>");
        for (int i = 0; i < count; i++) {
            List<Object> row = data.rows.get(i);
            BigDecimal value = valueIndex < row.size() ? ReportFormat.decimal(row.get(valueIndex)) : BigDecimal.ZERO;
            int height = value.signum() <= 0 ? 0
                    : value.multiply(BigDecimal.valueOf(HEIGHT - BOTTOM - TOP))
                            .divide(top, 0, java.math.RoundingMode.HALF_UP).intValue();
            int x = LEFT + i * slot + (slot - barWidth) / 2;
            int y = HEIGHT - BOTTOM - height;
            svg.append("<rect x=\"").append(x).append("\" y=\"").append(y)
                    .append("\" width=\"").append(barWidth).append("\" height=\"").append(height)
                    .append("\" class=\"report-chart-bar\"><title>")
                    .append(escape(label(data, row, labelIndex))).append(" : ")
                    .append(escape(ReportFormat.cell(valueColumn, value)))
                    .append("</title></rect>");
            svg.append("<text x=\"").append(x + barWidth / 2).append("\" y=\"").append(HEIGHT - BOTTOM + 16)
                    .append("\" class=\"report-chart-label\">")
                    .append(escape(shorten(label(data, row, labelIndex)))).append("</text>");
        }
        svg.append("</svg>");
        return svg.toString();
    }

    /**
     * Reads a row's label for the chart.
     *
     * @param data the executed report
     * @param row the row
     * @param index the label column index
     * @return the rendered label, empty when the row is short
     */
    private static String label(ReportData data, List<Object> row, int index) {
        if (index < 0 || index >= row.size()) {
            return "";
        }
        return ReportFormat.cell(data.definition.columns().get(index), row.get(index));
    }

    /**
     * Shortens a bar's label to what fits under a bar.
     *
     * @param value the rendered label
     * @return the label, cut to ten characters
     */
    private static String shorten(String value) {
        return value.length() <= 10 ? value : value.substring(0, 9) + "…";
    }

    /**
     * Escapes the characters XML reserves.
     *
     * @param value the raw text
     * @return the escaped text
     */
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
