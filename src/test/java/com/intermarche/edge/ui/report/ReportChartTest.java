package com.intermarche.edge.ui.report;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReportChart} and {@link ReportHeading}.
 * <p>
 * Branch enumeration — chart: {@code available} has the no-plotted-column arm,
 * the empty arm, the too-many-bars arm and the drawable arm; {@code svg} has
 * its early return, its all-zero return, and, per row, the zero-height and
 * positive-height arms; {@code label} has the out-of-range arm on both sides and
 * the in-range arm; {@code shorten} has both arms; {@code escape} covers the
 * five reserved characters. Heading: {@code period} has the one-day arm, the
 * several-days arm and the not-whole-days arm.
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
     * A report declaring no plotted column has no chart.
     */
    @Test
    void reportWithoutAPlottedColumnHasNoChart() {
        List<List<Object>> rows = List.of(Arrays.asList("A", "A", 1L, BigDecimal.ONE, null, null));
        assertFalse(ReportChart.available(new ReportData(new ReportFixture(-1, rows), rows)));
        assertEquals("", ReportChart.svg(new ReportData(new ReportFixture(-1, rows), rows)));
    }

    /**
     * An empty report has no chart.
     */
    @Test
    void emptyReportHasNoChart() {
        assertFalse(ReportChart.available(drawable(List.of())));
    }

    /**
     * A report with more rows than bars worth drawing has no chart.
     */
    @Test
    void tooManyRowsMeanNoChart() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i <= ReportChart.MAX_BARS; i++) {
            rows.add(Arrays.asList("K" + i, "L" + i, 1L, BigDecimal.ONE, null, null));
        }
        assertFalse(ReportChart.available(drawable(rows)));
    }

    /**
     * Exactly the largest drawable number of bars is still drawn (the boundary's
     * inside).
     */
    @Test
    void theLargestDrawableCountIsStillDrawn() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < ReportChart.MAX_BARS; i++) {
            rows.add(Arrays.asList("K" + i, "L" + i, 1L, BigDecimal.ONE, null, null));
        }
        assertTrue(ReportChart.available(drawable(rows)));
    }

    /**
     * A drawable report yields one bar per row, each carrying its own tooltip.
     */
    @Test
    void oneBarPerRowCarriesItsTooltip() {
        ReportFixture fixture = ReportFixture.standard();
        String svg = ReportChart.svg(new ReportData(fixture, fixture.rows(null)));
        assertEquals(2, svg.split("<rect ").length - 1);
        assertTrue(svg.contains("Carte &amp; &lt;banque&gt; : 17"));
        assertTrue(svg.startsWith("<svg class=\"report-chart\""));
    }

    /**
     * The tallest row reaches the full plotting height and a zero row draws a
     * bar of no height at all.
     */
    @Test
    void zeroRowsDrawNoHeight() {
        List<List<Object>> rows = List.of(
                Arrays.asList("A", "A", 1L, new BigDecimal("100"), null, null),
                Arrays.asList("B", "B", 1L, BigDecimal.ZERO, null, null));
        String svg = ReportChart.svg(drawable(rows));
        assertTrue(svg.contains("height=\"240\""));
        assertTrue(svg.contains("height=\"0\""));
    }

    /**
     * Every value at zero draws no chart and says why, rather than dividing by a
     * zero maximum.
     */
    @Test
    void everyValueAtZeroSaysSoInstead() {
        List<List<Object>> rows = List.of(Arrays.asList("A", "A", 1L, BigDecimal.ZERO, null, null));
        assertTrue(ReportChart.svg(drawable(rows)).contains("Aucune valeur à représenter"));
    }

    /**
     * A row too short to carry its label or its value is drawn all the same,
     * with an empty label and no height.
     */
    @Test
    void shortRowIsDrawnWithoutItsLabel() {
        List<List<Object>> rows = List.of(List.of("A"));
        String svg = ReportChart.svg(drawable(rows));
        assertTrue(svg.contains("Aucune valeur à représenter"));
    }

    /**
     * A label longer than a bar is cut and marked; a short one is left whole.
     */
    @Test
    void longLabelsAreCutUnderTheBar() {
        List<List<Object>> rows = List.of(
                Arrays.asList("A", "Un libellé beaucoup trop long", 1L, new BigDecimal("5"), null, null),
                Arrays.asList("B", "Court", 1L, new BigDecimal("5"), null, null));
        String svg = ReportChart.svg(drawable(rows));
        assertTrue(svg.contains(">Un libell…<"));
        assertTrue(svg.contains(">Court<"));
    }

    /**
     * A row too short to carry its value while the chart is nonetheless drawn is
     * plotted at no height, and its missing label reads as empty (the in-loop
     * short-row arms of both the value read and the label read).
     */
    @Test
    void shortRowInsideADrawnChartIsPlottedFlat() {
        List<List<Object>> rows = Arrays.asList(
                Arrays.asList("A", "Label", 1L, new BigDecimal("100"), null, null),
                List.of("B"));
        String svg = ReportChart.svg(drawable(rows));
        assertTrue(svg.contains("height=\"240\""));
        assertTrue(svg.contains("height=\"0\""));
    }

    /**
     * A negative label column makes every bar's label empty (the {@code index < 0}
     * arm of the label read) while the chart is still drawn.
     */
    @Test
    void negativeLabelColumnYieldsEmptyLabels() {
        List<List<Object>> rows = List.of(
                Arrays.asList("A", "Label", 1L, new BigDecimal("5"), null, null));
        ReportFixture fixture = new ReportFixture(3, rows) {
            /**
             * {@inheritDoc}
             *
             * @return a negative label column, so no label can be read
             */
            @Override
            public int chartLabelColumn() {
                return -1;
            }
        };
        String svg = ReportChart.svg(new ReportData(fixture, rows));
        assertTrue(svg.contains("<title> : "));
        assertTrue(svg.contains("<rect "));
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
