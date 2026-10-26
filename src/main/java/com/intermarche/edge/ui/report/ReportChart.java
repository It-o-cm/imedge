package com.intermarche.edge.ui.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Draws an aggregate report as a chart (BO-06-01-04): bars, curves or a pie,
 * over one or several of its numeric columns, whatever its number of rows.
 *
 * <p>An SVG built on the SERVER and inlined in the page: no charting library, no
 * script, nothing to fetch. A back office opened on a store's network — which may
 * have no way out to a CDN — draws the same chart as one opened at head office,
 * and the same markup is what a browser prints when the operator prints the page.
 *
 * <p>« La totalité des affichages de rapports (hors liste) doivent être
 * visualisables par graphique »: every report that declares a plotted column is
 * drawable, and the operator chooses the TYPE and the SERIES — any numeric
 * column, several at once. A report of many rows is not refused any more: the
 * drawing grows wider than the page and its box scrolls sideways inside the
 * card, the labels thinning out so they stay legible; a pie of many slices keeps
 * its largest ones and gathers the rest under « Autres ». Only a LIST — tickets,
 * events — has no chart, by declaring no plotted column.
 */
public final class ReportChart {

    /** The chart types the operator chooses from. */
    public enum Type {

        /** One bar per row, side by side for several series. */
        BAR("Barres"),

        /** One curve per series, a point per row. */
        LINE("Courbes"),

        /** The shares of the first series, a slice per row. */
        PIE("Secteurs");

        /** The label shown in the chart-type selector. */
        private final String label;

        /**
         * Builds a type.
         *
         * @param label the selector label
         */
        Type(String label) {
            this.label = label;
        }

        /**
         * Returns the label shown in the chart-type selector.
         *
         * @return the French label
         */
        public String getLabel() {
            return label;
        }

        /**
         * Reads a submitted type, falling back to the bars.
         *
         * @param token the submitted name, possibly null or unknown
         * @return the type, never null
         */
        public static Type of(String token) {
            if (token == null) {
                return BAR;
            }
            for (Type type : values()) {
                if (type.name().equalsIgnoreCase(token.trim())) {
                    return type;
                }
            }
            return BAR;
        }
    }

    /** Drawing width in user units for a chart that fits the page. */
    private static final int WIDTH = 960;

    /** Drawing height in user units. */
    private static final int HEIGHT = 320;

    /** Room left at the bottom for the labels. */
    private static final int BOTTOM = 56;

    /** Room left at the top for the legend. */
    private static final int TOP = 28;

    /** Room left at the left for the axis. */
    private static final int LEFT = 8;

    /** The number of row labels written under the axis before they thin out. */
    static final int MAX_LABELS = 40;

    /** The width one row needs, in user units, before the drawing widens. */
    static final int ROW_WIDTH = 24;

    /** The width of one label character, in drawing units. */
    static final int CHAR_WIDTH = 7;

    /** The number of slices a pie draws before gathering the rest. */
    static final int MAX_SLICES = 12;

    /** The number of distinct series colours, reused beyond. */
    private static final int COLOURS = 6;

    /**
     * Not instantiable.
     */
    private ReportChart() {
    }

    /**
     * Whether a chart can be drawn for this executed report.
     *
     * @param data the executed report
     * @return true when the report declares a plotted column and has a data row
     */
    public static boolean available(ReportData data) {
        return data.definition.chartColumn() >= 0 && !data.dataRows().isEmpty();
    }

    /**
     * Returns the columns the operator may plot: every numeric column.
     *
     * @param data the executed report
     * @return the numeric columns, in display order
     */
    public static List<ReportColumn> plottable(ReportData data) {
        List<ReportColumn> numeric = new ArrayList<>();
        for (ReportColumn column : data.definition.columns()) {
            if (column.numeric()) {
                numeric.add(column);
            }
        }
        return numeric;
    }

    /**
     * Resolves the series to draw: the numeric columns named by their keys, or
     * the report's own plotted column when none of the keys names one.
     *
     * @param data the executed report
     * @param keys the submitted column keys, possibly empty
     * @return the column indexes, never empty for a drawable report
     */
    public static List<Integer> series(ReportData data, List<String> keys) {
        List<Integer> chosen = new ArrayList<>();
        List<ReportColumn> columns = data.definition.columns();
        for (String key : keys) {
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).key().equals(key) && columns.get(i).numeric() && !chosen.contains(i)) {
                    chosen.add(i);
                }
            }
        }
        if (chosen.isEmpty()) {
            chosen.add(data.definition.chartColumn());
        }
        return chosen;
    }

    /**
     * Renders the report's own series as bars, texts untranslated.
     *
     * @param data the executed report
     * @return the SVG markup, or an empty string when nothing can be drawn
     */
    public static String svg(ReportData data) {
        return svg(data, Type.BAR, List.of(), UnaryOperator.identity());
    }

    /**
     * Renders the chart as an inline SVG in the page's language: the rows'
     * labels, the series' names and the empty-chart notice are French texts
     * computed here, each handed to the translation before it is drawn.
     *
     * @param data the executed report
     * @param type the chart type
     * @param keys the keys of the columns to plot, empty for the report's own
     * @param translate the translation of a French text into the page's language
     * @return the SVG markup, or an empty string when nothing can be drawn
     */
    public static String svg(ReportData data, Type type, List<String> keys, UnaryOperator<String> translate) {
        if (!available(data)) {
            return "";
        }
        List<List<Object>> rows = data.dataRows();
        List<Integer> series = type == Type.PIE ? series(data, keys).subList(0, 1) : series(data, keys);
        BigDecimal top = BigDecimal.ZERO;
        for (List<Object> row : rows) {
            for (int index : series) {
                BigDecimal value = value(row, index);
                if (value.compareTo(top) > 0) {
                    top = value;
                }
            }
        }
        // Every value at zero: a flat chart divided by a zero maximum would
        // draw nothing at all, or worse, divide by zero. It says so instead.
        if (top.signum() <= 0) {
            return "<p class=\"report-chart-empty\">"
                    + escape(translate.apply("Aucune valeur à représenter sur la période.")) + "</p>";
        }
        if (type == Type.PIE) {
            return pie(data, rows, series.get(0), translate);
        }
        return axes(data, rows, series, top, type, translate);
    }

    /**
     * Draws bars or curves over a horizontal axis, a slot per row.
     *
     * @param data the executed report
     * @param rows the data rows
     * @param series the plotted column indexes
     * @param top the largest plotted value, positive
     * @param type bars or curves
     * @param translate the translation of the computed texts
     * @return the SVG markup
     */
    private static String axes(ReportData data, List<List<Object>> rows, List<Integer> series, BigDecimal top,
                               Type type, UnaryOperator<String> translate) {
        int count = rows.size();
        int width = Math.max(WIDTH, LEFT + count * ROW_WIDTH * (type == Type.BAR ? series.size() : 1));
        int slot = (width - LEFT) / count;
        int barWidth = Math.max(3, (int) (slot * 0.7 / series.size()));
        int step = (count + MAX_LABELS - 1) / MAX_LABELS;
        StringBuilder svg = open(width, data, series, translate, "none");
        svg.append("<line x1=\"0\" y1=\"").append(HEIGHT - BOTTOM).append("\" x2=\"").append(width)
                .append("\" y2=\"").append(HEIGHT - BOTTOM).append("\" class=\"report-chart-axis\"/>");
        for (int s = 0; s < series.size(); s++) {
            int index = series.get(s);
            ReportColumn column = data.definition.columns().get(index);
            StringBuilder points = new StringBuilder();
            StringBuilder marks = new StringBuilder();
            for (int i = 0; i < count; i++) {
                List<Object> row = rows.get(i);
                BigDecimal value = value(row, index);
                int height = height(value, top);
                String title = escape(translate.apply(label(data, row))) + " : "
                        + escape(ReportFormat.cell(column, value));
                if (type == Type.BAR) {
                    int x = LEFT + i * slot + (slot - barWidth * series.size()) / 2 + s * barWidth;
                    marks.append("<rect x=\"").append(x).append("\" y=\"").append(HEIGHT - BOTTOM - height)
                            .append("\" width=\"").append(barWidth).append("\" height=\"").append(height)
                            .append("\" class=\"report-chart-bar report-chart-s").append(s % COLOURS)
                            .append("\"><title>").append(title).append("</title></rect>");
                } else {
                    int x = LEFT + i * slot + slot / 2;
                    int y = HEIGHT - BOTTOM - height;
                    points.append(i == 0 ? "" : " ").append(x).append(',').append(y);
                    marks.append("<circle cx=\"").append(x).append("\" cy=\"").append(y)
                            .append("\" r=\"3\" class=\"report-chart-point report-chart-s").append(s % COLOURS)
                            .append("\"><title>").append(title).append("</title></circle>");
                }
            }
            if (type == Type.LINE) {
                svg.append("<polyline points=\"").append(points).append("\" class=\"report-chart-line report-chart-s")
                        .append(s % COLOURS).append("\"/>");
            }
            svg.append(marks);
        }
        for (int i = 0; i < count; i += step) {
            int x = LEFT + i * slot + slot / 2;
            svg.append("<text x=\"").append(x).append("\" y=\"").append(HEIGHT - BOTTOM + 16)
                    .append("\" class=\"report-chart-label\">")
                    .append(escape(shorten(translate.apply(label(data, rows.get(i))), slot * step / CHAR_WIDTH)))
                    .append("</text>");
        }
        return svg.append("</svg>").toString();
    }

    /**
     * Draws the shares of one series as a pie, the largest slices first and the
     * smallest gathered under « Autres » beyond {@link #MAX_SLICES}.
     *
     * @param data the executed report
     * @param rows the data rows
     * @param index the plotted column index
     * @param translate the translation of the computed texts
     * @return the SVG markup
     */
    private static String pie(ReportData data, List<List<Object>> rows, int index, UnaryOperator<String> translate) {
        List<String> names = new ArrayList<>();
        List<BigDecimal> values = new ArrayList<>();
        for (List<Object> row : rows) {
            BigDecimal value = value(row, index);
            if (value.signum() > 0) {
                names.add(translate.apply(label(data, row)));
                values.add(value);
            }
        }
        sortDescending(names, values);
        if (values.size() > MAX_SLICES) {
            BigDecimal rest = BigDecimal.ZERO;
            for (BigDecimal value : values.subList(MAX_SLICES - 1, values.size())) {
                rest = rest.add(value);
            }
            names = new ArrayList<>(names.subList(0, MAX_SLICES - 1));
            values = new ArrayList<>(values.subList(0, MAX_SLICES - 1));
            names.add(translate.apply("Autres"));
            values.add(rest);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            total = total.add(value);
        }
        ReportColumn column = data.definition.columns().get(index);
        // A pie keeps its proportions: stretched like the bars it would be an
        // oval, its legend squeezed.
        StringBuilder svg = open(WIDTH, data, List.of(index), translate, "xMinYMid meet");
        double angle = -Math.PI / 2;
        int cx = 160;
        int cy = 170;
        int radius = 130;
        for (int i = 0; i < values.size(); i++) {
            double share = values.get(i).doubleValue() / total.doubleValue();
            String title = escape(names.get(i)) + " : " + escape(ReportFormat.cell(column, values.get(i)));
            if (values.size() == 1) {
                svg.append("<circle cx=\"").append(cx).append("\" cy=\"").append(cy).append("\" r=\"").append(radius)
                        .append("\" class=\"report-chart-slice report-chart-p0\"><title>").append(title)
                        .append("</title></circle>");
            } else {
                double end = angle + share * 2 * Math.PI;
                svg.append("<path d=\"M ").append(cx).append(' ').append(cy)
                        .append(" L ").append(round(cx + radius * Math.cos(angle))).append(' ')
                        .append(round(cy + radius * Math.sin(angle)))
                        .append(" A ").append(radius).append(' ').append(radius).append(" 0 ")
                        .append(share > 0.5 ? 1 : 0).append(" 1 ")
                        .append(round(cx + radius * Math.cos(end))).append(' ')
                        .append(round(cy + radius * Math.sin(end)))
                        .append(" Z\" class=\"report-chart-slice report-chart-p").append(i % MAX_SLICES)
                        .append("\"><title>").append(title).append("</title></path>");
                angle = end;
            }
            int y = 52 + i * 20;
            svg.append("<rect x=\"340\" y=\"").append(y - 10).append("\" width=\"12\" height=\"12\"")
                    .append(" class=\"report-chart-slice report-chart-p").append(i % MAX_SLICES).append("\"/>")
                    .append("<text x=\"360\" y=\"").append(y).append("\" class=\"report-chart-legend\">")
                    .append(escape(names.get(i))).append(" — ")
                    .append(escape(ReportFormat.cell(column, values.get(i)))).append(" (")
                    .append(BigDecimal.valueOf(share * 100).setScale(1, RoundingMode.HALF_UP).toPlainString()
                            .replace('.', ','))
                    .append(" %)</text>");
        }
        return svg.append("</svg>").toString();
    }

    /**
     * Opens the SVG element and writes the legend of the series.
     *
     * @param width the drawing width
     * @param data the executed report
     * @param series the plotted column indexes
     * @param translate the translation of the computed texts
     * @param aspect the {@code preserveAspectRatio} of the drawing
     * @return the opened markup
     */
    private static StringBuilder open(int width, ReportData data, List<Integer> series,
                                      UnaryOperator<String> translate, String aspect) {
        StringBuilder names = new StringBuilder();
        for (int index : series) {
            names.append(names.length() == 0 ? "" : ", ")
                    .append(translate.apply(data.definition.columns().get(index).label()));
        }
        StringBuilder svg = new StringBuilder("<svg class=\"report-chart");
        if (width > WIDTH) {
            svg.append(" report-chart-wide\" style=\"width:").append(width).append("px");
        }
        svg.append("\" viewBox=\"0 0 ").append(width).append(' ').append(HEIGHT)
                .append("\" preserveAspectRatio=\"").append(aspect).append("\" role=\"img\" aria-label=\"")
                .append(escape(names.toString())).append("\">");
        int x = LEFT;
        for (int s = 0; s < series.size(); s++) {
            String name = translate.apply(data.definition.columns().get(series.get(s)).label());
            svg.append("<rect x=\"").append(x).append("\" y=\"6\" width=\"10\" height=\"10\" class=\"report-chart-bar")
                    .append(" report-chart-s").append(s % COLOURS).append("\"/>")
                    .append("<text x=\"").append(x + 14).append("\" y=\"15\" class=\"report-chart-legend\">")
                    .append(escape(name)).append("</text>");
            x += 24 + name.length() * 6;
        }
        return svg;
    }

    /**
     * Sorts the slices by descending value, the names following their values.
     *
     * @param names the slice names
     * @param values the slice values
     */
    private static void sortDescending(List<String> names, List<BigDecimal> values) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> values.get(b).compareTo(values.get(a)));
        List<String> sortedNames = new ArrayList<>();
        List<BigDecimal> sortedValues = new ArrayList<>();
        for (int i : order) {
            sortedNames.add(names.get(i));
            sortedValues.add(values.get(i));
        }
        names.clear();
        names.addAll(sortedNames);
        values.clear();
        values.addAll(sortedValues);
    }

    /**
     * Reads a row's plotted value.
     *
     * @param row the row
     * @param index the column index
     * @return the value, zero when the row is short
     */
    private static BigDecimal value(List<Object> row, int index) {
        return index < row.size() ? ReportFormat.decimal(row.get(index)) : BigDecimal.ZERO;
    }

    /**
     * Scales a value to a height of the drawing.
     *
     * @param value the value
     * @param top the largest value, positive
     * @return the height in user units, zero for a nonpositive value
     */
    private static int height(BigDecimal value, BigDecimal top) {
        return value.signum() <= 0 ? 0
                : value.multiply(BigDecimal.valueOf(HEIGHT - BOTTOM - TOP)).divide(top, 0, RoundingMode.HALF_UP)
                        .intValue();
    }

    /**
     * Reads a row's label for the chart.
     *
     * @param data the executed report
     * @param row the row
     * @return the rendered label, empty when the row is short or no label column is declared
     */
    private static String label(ReportData data, List<Object> row) {
        int index = data.definition.chartLabelColumn();
        if (index < 0 || index >= row.size()) {
            return "";
        }
        return ReportFormat.cell(data.definition.columns().get(index), row.get(index));
    }

    /**
     * Rounds a coordinate to a tenth.
     *
     * @param value the coordinate
     * @return the rounded coordinate, rendered with a dot
     */
    private static String round(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Shortens a label to what fits under its slot.
     *
     * @param value the rendered label
     * @param room the characters the slot holds
     * @return the label, cut to the room — ten characters at least — with an
     *         ellipsis when cut
     */
    static String shorten(String value, int room) {
        int fit = Math.max(10, room);
        return value.length() <= fit ? value : value.substring(0, fit - 1) + "…";
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
