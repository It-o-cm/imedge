package com.intermarche.edge.ui.report;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReportData}.
 * <p>
 * Branch enumeration: {@code total} has the not-totalled arm, the short-row
 * arm, the BigDecimal arm, the other-Number arm, the neither arm, and the
 * integer / decimal arms of the sum's own kind. {@code hasTotals} has the
 * empty-rows arm, the all-null arm and the found arm. {@code isEmpty} and
 * {@code size} are read on both an empty and a filled report.
 */
class ReportDataTest {

    /**
     * Builds a report over the given rows.
     *
     * @param rows the rows
     * @return the executed report
     */
    private ReportData data(List<List<Object>> rows) {
        return new ReportData(ReportFixture.standard(), rows);
    }

    /**
     * The totalled columns are summed and the others left null.
     */
    @Test
    void totalledColumnsAreSummed() {
        ReportData data = data(ReportFixture.standard().rows(null));
        assertNull(data.totals.get(0));
        assertNull(data.totals.get(1));
        assertEquals(2186L, data.totals.get(2));
        assertEquals(0, new BigDecimal("35532.65").compareTo((BigDecimal) data.totals.get(3)));
        assertNull(data.totals.get(4));
        assertNull(data.totals.get(5));
    }

    /**
     * A row shorter than the column list contributes nothing to the columns it
     * lacks, and the sum still holds for the rest.
     */
    @Test
    void shortRowContributesNothing() {
        ReportData data = data(List.of(List.of("K", "L", 2L)));
        assertEquals(2L, data.totals.get(2));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) data.totals.get(3)));
    }

    /**
     * A cell carrying a non-number where a figure was expected contributes
     * nothing rather than breaking the whole total.
     */
    @Test
    void nonNumericCellContributesNothing() {
        ReportData data = data(List.of(
                Arrays.asList("K", "L", "deux", new BigDecimal("10.00"), null, null),
                Arrays.asList("K", "L", 3L, new BigDecimal("5.00"), null, null)));
        assertEquals(3L, data.totals.get(2));
        assertEquals(0, new BigDecimal("15.00").compareTo((BigDecimal) data.totals.get(3)));
    }

    /**
     * A null cell in a totalled column contributes nothing.
     */
    @Test
    void nullCellContributesNothing() {
        ReportData data = data(List.of(Arrays.asList("K", "L", null, null, null, null)));
        assertEquals(0L, data.totals.get(2));
    }

    /**
     * A count total is a whole number, an amount total keeps its decimals.
     */
    @Test
    void countTotalIsWholeAndAmountTotalIsNot() {
        ReportData data = data(List.of(Arrays.asList("K", "L", 7L, new BigDecimal("1.25"), null, null)));
        assertTrue(data.totals.get(2) instanceof Long);
        assertTrue(data.totals.get(3) instanceof BigDecimal);
    }

    /**
     * An empty report shows no total row, even though its columns are totalled.
     */
    @Test
    void emptyReportShowsNoTotalRow() {
        ReportData data = data(List.of());
        assertTrue(data.isEmpty());
        assertEquals(0, data.size());
        assertFalse(data.hasTotals());
    }

    /**
     * A report whose columns are none of them totalled shows no total row.
     */
    @Test
    void reportWithoutTotalledColumnsShowsNoTotalRow() {
        ReportDefinition definition = new ReportFixture(-1, List.of()) {
            /**
             * {@inheritDoc}
             *
             * @return two columns, neither of them totalled
             */
            @Override
            public List<ReportColumn> columns() {
                return List.of(ReportColumn.text("a", "A"), ReportColumn.text("b", "B"));
            }
        };
        ReportData data = new ReportData(definition, List.of(List.of("x", "y")));
        assertFalse(data.hasTotals());
    }

    /**
     * A filled report with a totalled column shows its total row.
     */
    @Test
    void filledReportShowsItsTotalRow() {
        assertTrue(data(ReportFixture.standard().rows(null)).hasTotals());
        assertEquals(2, data(ReportFixture.standard().rows(null)).size());
    }
}
