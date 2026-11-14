package com.intermarche.edge.ui.report;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import static com.intermarche.edge.ui.report.QueryStub.row;
import static com.intermarche.edge.ui.report.QueryStub.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LocationSalesReport}: the articles sold over the
 * period, summed per article or per slice and article, the code read off the
 * EAN or the PLU, the internal number read off the catalogue, the hours and
 * article criteria, and the metadata.
 */
class LocationSalesReportTest {

    /** A moment of the period. */
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 20, 10, 10);

    /**
     * The stub of the period's lines and the catalogue.
     *
     * @return the stub
     */
    private QueryStub stub() {
        return new QueryStub()
                .answer("from Ticket t join t.lines l", rows(
                        row(AT, "301", null, "Pain", "1002", new BigDecimal("1.20"), new BigDecimal("2"),
                                new BigDecimal("2.40")),
                        row(AT.plusMinutes(40), "301", null, "Pain", "1002", new BigDecimal("1.30"), null, null),
                        row(AT, null, "77", "Pommes", null, new BigDecimal("2.00"), new BigDecimal("0.5"),
                                new BigDecimal("1.00")),
                        row(AT, null, null, "Divers", null, null, BigDecimal.ONE, BigDecimal.ONE),
                        row(AT.withHour(21), "301", null, "Pain", "1002", BigDecimal.ONE, BigDecimal.ONE,
                                BigDecimal.ONE)))
                .answer("from Product p", rows(row("301", "INT301"), row("77", null)));
    }

    /**
     * Runs the report.
     *
     * @param stub the stub
     * @param option the option, or null
     * @return the rows
     */
    private List<List<Object>> run(QueryStub stub, String option) {
        LocationSalesReport report = new LocationSalesReport();
        report.entityManager = stub.entityManager;
        ReportCriteria criteria = new ReportCriteria();
        criteria.option = option;
        criteria.hourTo = LocalTime.of(20, 0);
        criteria.eans.add("301");
        return report.rows(criteria);
    }

    /**
     * Over the whole period: one row per article, summed, the latest price,
     * the internal number when the catalogue has one, the late line out of
     * the hours; the article criteria reach the query.
     */
    @Test
    void perArticle() {
        QueryStub stub = stub();
        List<List<Object>> rows = run(stub, null);
        assertEquals(3, rows.size());
        assertEquals(Arrays.asList("", "", "", "Divers", "", null, BigDecimal.ONE, BigDecimal.ONE), rows.get(0));
        assertEquals(Arrays.asList("", "INT301", "301", "Pain", "1002", new BigDecimal("1.30"), new BigDecimal("2"),
                new BigDecimal("2.40")), rows.get(1));
        assertEquals("", rows.get(2).get(1));
        assertEquals("77", rows.get(2).get(2));
        assertTrue(stub.asked("l.ean in :eans"));
        assertEquals(java.util.Set.of("", "77", "301"), stub.parameters.get("eans"));
    }

    /**
     * Sliced by half-hour: the article sold in two slices is two rows, named
     * by their slices.
     */
    @Test
    void perSliceAndArticle() {
        List<List<Object>> rows = run(stub(), "30");
        assertEquals(4, rows.size());
        assertEquals("10:00 – 10:30", rows.get(0).get(0));
        assertEquals("10:30 – 11:00", rows.get(3).get(0));
        assertEquals(new BigDecimal("1.30"), rows.get(3).get(5));
    }

    /**
     * A period without sale reads nothing and asks nothing of the catalogue.
     */
    @Test
    void anEmptyPeriod() {
        QueryStub stub = new QueryStub();
        assertTrue(run(stub, null).isEmpty());
        assertFalse(stub.asked("from Product p"));
    }

    /**
     * The metadata: identity, columns, chart, options and filters.
     */
    @Test
    void metadataDescribesTheReport() {
        LocationSalesReport report = new LocationSalesReport();
        assertEquals("location-sales", report.code());
        assertEquals("État ventes par emplacement", report.label());
        assertEquals("BO-06-04-02", report.requirement());
        assertEquals(ReportFamily.SALES, report.family());
        assertEquals(8, report.columns().size());
        assertTrue(report.columns().get(6).totalled());
        assertEquals(7, report.chartColumn());
        assertEquals(3, report.chartLabelColumn());
        assertEquals("Détail horaire", report.optionLabel());
        assertEquals(4, report.optionChoices().size());
        assertEquals(java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.HOURS,
                ReportFilter.ARTICLES), report.filters());
    }
}
