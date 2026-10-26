package com.intermarche.edge.ui.report;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReportChart} and {@link ReportHeading}.
 * <p>
 * Branch enumeration — {@code Type.of}: null, a known name, an unknown one.
 * {@code available}: no plotted column, no data row, drawable. {@code
 * plottable}: numeric and text columns. {@code series}: a numeric key, a text
 * key, a repeated key, an unknown key, no key. {@code svg}: not drawable, all
 * zero, pie, bars, curves; inside the axes: the narrow and the wide drawing,
 * the first and the following points of a curve, the thinned labels; inside
 * the pie: the nonpositive slice skipped, the gathered slices, the single
 * slice, the large and the small arc. {@code value}: the short row. {@code
 * label}: a negative index, a short row, an index in range. {@code shorten}:
 * both arms. Heading: one day, several days, partial days.
 */
class ReportChartTest {

    /**
     * Builds an executed report over the given rows, plotting the amount.
     *
     * @param rows the rows
     * @return the executed report
     */
    private ReportData drawable(List<List<Object>> rows) {
        return new ReportData(new ReportFixture(3, rows), rows);
    }

    /**
     * Builds one fixture row.
     *
     * @param label the row's label
     * @param count the count
     * @param amount the amount
     * @return the row
     */
    private List<Object> row(String label, long count, String amount) {
        return Arrays.asList("K", label, count, new BigDecimal(amount), BigDecimal.ONE, null);
    }

    /**
     * The chart types read their token, null and unknown ones meaning bars,
     * and each carries its label.
     */
    @Test
    void typesAreReadFromTheirToken() {
        assertEquals(ReportChart.Type.BAR, ReportChart.Type.of(null));
        assertEquals(ReportChart.Type.LINE, ReportChart.Type.of(" line "));
        assertEquals(ReportChart.Type.PIE, ReportChart.Type.of("PIE"));
        assertEquals(ReportChart.Type.BAR, ReportChart.Type.of("radar"));
        assertEquals("Barres", ReportChart.Type.BAR.getLabel());
        assertEquals("Courbes", ReportChart.Type.LINE.getLabel());
        assertEquals("Secteurs", ReportChart.Type.PIE.getLabel());
    }

    /**
     * A report declaring no plotted column has no chart, nor one without a
     * data row; a subtotal row alone is no data.
     */
    @Test
    void onlyAnAggregateWithDataIsDrawn() {
        List<List<Object>> rows = List.of(row("A", 1, "1"));
        assertFalse(ReportChart.available(new ReportData(new ReportFixture(-1, rows), rows)));
        assertEquals("", ReportChart.svg(new ReportData(new ReportFixture(-1, rows), rows)));
        assertFalse(ReportChart.available(drawable(List.of())));
        ReportData.Subtotal subtotal = new ReportData.Subtotal();
        subtotal.addAll(row("S", 1, "1"));
        assertFalse(ReportChart.available(drawable(List.of(subtotal))));
        assertTrue(ReportChart.available(drawable(rows)));
    }

    /**
     * The plottable columns are the numeric ones, and the series chosen are
     * the numeric columns named, each once, the report's own when none is.
     */
    @Test
    void seriesAreTheNumericColumnsNamed() {
        ReportData data = drawable(List.of(row("A", 1, "1")));
        List<ReportColumn> plottable = ReportChart.plottable(data);
        assertEquals(List.of("n", "a", "p"), plottable.stream().map(ReportColumn::key).toList());
        assertEquals(List.of(2, 4), ReportChart.series(data, List.of("n", "k", "n", "zz", "p")));
        assertEquals(List.of(3), ReportChart.series(data, List.of()));
        assertEquals(List.of(3), ReportChart.series(data, List.of("k")));
    }

    /**
     * One bar per row, its tooltip naming the row and the value, the label
     * escaped and translated; a row of a larger value than the first one
     * raises the top, a smaller one does not.
     */
    @Test
    void barsCarryTheirTooltipTranslated() {
        List<List<Object>> rows = List.of(row("A & <b>", 1, "10"), row("B", 2, "20"), row("C", 3, "5"));
        String svg = ReportChart.svg(drawable(rows), ReportChart.Type.BAR, List.of(), text -> "<" + text + ">");
        assertTrue(svg.startsWith("<svg class=\"report-chart\" viewBox=\"0 0 960 320\""));
        assertTrue(svg.contains("<title>&lt;A &amp; &lt;b&gt;&gt; : 10,00 €</title>"));
        assertTrue(svg.contains("report-chart-s0"));
        assertEquals(3, svg.split("class=\"report-chart-bar report-chart-s0\"><title>").length - 1);
        assertTrue(svg.contains("aria-label=\"&lt;Valeur nette&gt;\""));
    }

    /**
     * Several series are drawn side by side, each in its own colour and named
     * in the legend.
     */
    @Test
    void severalSeriesAreDrawnSideBySide() {
        List<List<Object>> rows = List.of(row("A", 4, "10"), row("B", 2, "20"));
        String svg = ReportChart.svg(drawable(rows), ReportChart.Type.BAR, List.of("a", "n"),
                UnaryOperator.identity());
        assertTrue(svg.contains("report-chart-s1"));
        assertTrue(svg.contains("aria-label=\"Valeur nette, Nombre\""));
        assertTrue(svg.contains(">Nombre</text>"));
    }

    /**
     * Curves draw one polyline per series, the first point without a leading
     * space, and a point per row.
     */
    @Test
    void curvesDrawAPolylinePerSeries() {
        List<List<Object>> rows = List.of(row("A", 1, "10"), row("B", 2, "0"));
        String svg = ReportChart.svg(drawable(rows), ReportChart.Type.LINE, List.of(), UnaryOperator.identity());
        assertTrue(svg.contains("<polyline points=\"246,"));
        assertTrue(svg.contains("<circle cx="));
        assertFalse(svg.contains("<rect x=\"8\" y=\"6\" width=\"10\" height=\"10\" class=\"report-chart-bar report-chart-s1"));
    }

    /**
     * A report of many rows draws a wider chart that scrolls, its labels
     * thinned out to one in two.
     */
    @Test
    void manyRowsWidenTheChartAndThinTheLabels() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < ReportChart.MAX_LABELS + 10; i++) {
            rows.add(row("R" + i, i, String.valueOf(i + 1)));
        }
        String svg = ReportChart.svg(drawable(rows));
        int width = 8 + rows.size() * ReportChart.ROW_WIDTH;
        assertTrue(svg.startsWith("<svg class=\"report-chart report-chart-wide\" style=\"width:" + width + "px\""));
        assertTrue(svg.contains(">R0</text>"));
        assertFalse(svg.contains(">R1</text>"));
        assertTrue(svg.contains(">R2</text>"));
    }

    /**
     * Every value at zero says so rather than drawing an empty frame.
     */
    @Test
    void everyValueAtZeroSaysSoInstead() {
        List<List<Object>> rows = List.of(row("A", 1, "0"));
        assertTrue(ReportChart.svg(drawable(rows), ReportChart.Type.BAR, List.of(), text -> "NL " + text)
                .contains("NL Aucune valeur à représenter sur la période."));
    }

    /**
     * A pie draws the first series only, a slice per positive row, the large
     * arc for a share above half, and its legend with the share.
     */
    @Test
    void aPieDrawsTheSharesOfTheFirstSeries() {
        List<List<Object>> rows = List.of(row("A", 1, "75"), row("B", 2, "25"), row("C", 3, "-5"));
        String svg = ReportChart.svg(drawable(rows), ReportChart.Type.PIE, List.of("a", "n"), UnaryOperator.identity());
        assertTrue(svg.contains(" 0 1 1 "));
        assertTrue(svg.contains(" 0 0 1 "));
        assertTrue(svg.contains("A — 75,00 € (75,0 %)"));
        assertTrue(svg.contains("B — 25,00 € (25,0 %)"));
        assertFalse(svg.contains(">C"));
        assertTrue(svg.contains("aria-label=\"Valeur nette\""));
        assertTrue(svg.contains("preserveAspectRatio=\"xMinYMid meet\""));
        assertTrue(ReportChart.svg(drawable(rows), ReportChart.Type.BAR, List.of(), UnaryOperator.identity())
                .contains("preserveAspectRatio=\"none\""));
    }

    /**
     * A pie of a single positive row is a full disc.
     */
    @Test
    void aSingleSliceIsAFullDisc() {
        String svg = ReportChart.svg(drawable(List.of(row("A", 1, "5"))), ReportChart.Type.PIE, List.of(),
                UnaryOperator.identity());
        assertTrue(svg.contains("<circle cx=\"160\" cy=\"170\" r=\"130\" class=\"report-chart-slice report-chart-p0\">"));
    }

    /**
     * A pie of many rows keeps its largest slices and gathers the others.
     */
    @Test
    void aPieGathersTheSmallestSlices() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 1; i <= ReportChart.MAX_SLICES + 2; i++) {
            rows.add(row("R" + i, i, String.valueOf(i)));
        }
        String svg = ReportChart.svg(drawable(rows), ReportChart.Type.PIE, List.of(), text -> text.toUpperCase());
        assertTrue(svg.contains("AUTRES — 6,00 €"));
        assertTrue(svg.contains("R14 — 14,00 €"));
        assertFalse(svg.contains("R3 —"));
    }

    /**
     * A row shorter than the plotted column is drawn flat, and without its
     * label when it is shorter than the label column too.
     */
    @Test
    void shortRowsAreDrawnFlatAndUnnamed() {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row("A", 1, "10"));
        rows.add(List.of("K"));
        String svg = ReportChart.svg(drawable(rows));
        assertTrue(svg.contains("height=\"0\" class=\"report-chart-bar report-chart-s0\"><title> : 0,00 €</title>"));
    }

    /**
     * A negative label column names no bar.
     */
    @Test
    void negativeLabelColumnNamesNoBar() {
        List<List<Object>> rows = List.of(row("A", 1, "10"));
        ReportFixture fixture = new ReportFixture(3, rows) {
            /**
             * {@inheritDoc}
             *
             * @return no label column
             */
            @Override
            public int chartLabelColumn() {
                return -1;
            }
        };
        String svg = ReportChart.svg(new ReportData(fixture, rows));
        assertTrue(svg.contains("<title> : 10,00 €</title>"));
    }

    /**
     * A label is cut to what its slot holds, ten characters at least: a wide
     * slot keeps it whole, a narrow one cuts it with an ellipsis.
     */
    @Test
    void longLabelsAreCutToTheirSlot() {
        String svg = ReportChart.svg(drawable(List.of(row("Espèces caisse", 1, "10"))));
        assertTrue(svg.contains(">Espèces caisse</text>"));
        assertEquals("Espèces c…", ReportChart.shorten("Espèces caisse", 3));
        assertEquals("Espèces caiss…", ReportChart.shorten("Espèces caisse du soir", 14));
        assertEquals("Espèces", ReportChart.shorten("Espèces", 3));
    }

    /**
     * A single day reads as one date rather than as a range.
     */
    @Test
    void oneDayReadsAsOneDate() {
        ReportCriteria criteria = new ReportCriteria();
        criteria.period = ReportPeriod.DAY;
        criteria.reference = LocalDate.of(2026, 9, 16);
        assertEquals("Jour : 16/09/2026", ReportHeading.period(criteria));
    }

    /**
     * Several days read as a range whose upper bound is the LAST day covered,
     * not the exclusive bound the frame computes with.
     */
    @Test
    void severalDaysReadAsAnInclusiveRange() {
        ReportCriteria criteria = new ReportCriteria();
        criteria.period = ReportPeriod.WEEK;
        criteria.reference = LocalDate.of(2026, 9, 16);
        assertEquals("Semaine : du 14/09/2026 au 20/09/2026", ReportHeading.period(criteria));
    }

    /**
     * Bounds that are not whole days are printed to the minute.
     */
    @Test
    void partialDaysArePrintedToTheMinute() {
        ReportCriteria criteria = new ReportCriteria() {
            /**
             * {@inheritDoc}
             *
             * @return a bound in the middle of a day
             */
            @Override
            public java.time.LocalDateTime from() {
                return java.time.LocalDateTime.of(2026, 9, 16, 8, 30);
            }
        };
        criteria.period = ReportPeriod.RANGE;
        assertTrue(ReportHeading.period(criteria).contains("du 16/09/2026 08:30 au "));
    }
}
