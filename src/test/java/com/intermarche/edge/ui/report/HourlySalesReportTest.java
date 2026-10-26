package com.intermarche.edge.ui.report;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static com.intermarche.edge.ui.report.QueryStub.row;
import static com.intermarche.edge.ui.report.QueryStub.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HourlySalesReport}: slices of the chosen width, the
 * hour range zooming on part of the day, the article criteria passed to the
 * query, the null quantities and totals read as zero.
 */
class HourlySalesReportTest {

    /**
     * Runs the report over the stub.
     *
     * @param stub the query stub
     * @param criteria the edition parameters
     * @return the rows
     */
    private List<List<Object>> run(QueryStub stub, ReportCriteria criteria) {
        HourlySalesReport report = new HourlySalesReport();
        report.entityManager = stub.entityManager;
        return report.rows(criteria);
    }

    /**
     * By the hour, the slices are summed and ordered; null cells read as zero.
     */
    @Test
    void slicesByTheHourAreSummed() {
        QueryStub stub = new QueryStub().answer("from Ticket t join t.lines l", rows(
                row(LocalDateTime.of(2026, 10, 20, 9, 10), new BigDecimal("2"), new BigDecimal("4.00")),
                row(LocalDateTime.of(2026, 10, 20, 8, 50), new BigDecimal("1"), new BigDecimal("1.50")),
                row(LocalDateTime.of(2026, 10, 20, 9, 40), null, null)));
        List<List<Object>> rows = run(stub, new ReportCriteria());
        assertEquals(2, rows.size());
        assertEquals("08:00 – 09:00", rows.get(0).get(0));
        assertEquals(new BigDecimal("1"), rows.get(0).get(1));
        assertEquals("09:00 – 10:00", rows.get(1).get(0));
        assertEquals(new BigDecimal("2"), rows.get(1).get(1));
        assertEquals(new BigDecimal("4.00"), rows.get(1).get(2));
    }

    /**
     * Zoomed on a range by the quarter, the slices outside the range are left
     * out and the article criteria reach the query.
     */
    @Test
    void theQuarterZoomsOnTheRange() {
        QueryStub stub = new QueryStub().answer("from Ticket t join t.lines l", rows(
                row(LocalDateTime.of(2026, 10, 20, 9, 20), new BigDecimal("3"), new BigDecimal("6.00")),
                row(LocalDateTime.of(2026, 10, 20, 11, 0), new BigDecimal("1"), new BigDecimal("1.00"))));
        ReportCriteria criteria = new ReportCriteria();
        criteria.option = "15";
        criteria.hourFrom = LocalTime.of(9, 0);
        criteria.hourTo = LocalTime.of(10, 0);
        criteria.families.add("1002");
        List<List<Object>> rows = run(stub, criteria);
        assertEquals(1, rows.size());
        assertEquals("09:15 – 09:30", rows.get(0).get(0));
        assertTrue(stub.asked("l.familyCode like :family0"));
        assertEquals("1002%", stub.parameters.get("family0"));
    }

    /**
     * The metadata: code, title, requirement, family, chart, the slice option
     * and the filters.
     */
    @Test
    void metadataAccessorsReturnConstants() {
        HourlySalesReport report = new HourlySalesReport();
        assertEquals("hourly-sales", report.code());
        assertEquals("État ventes horaires", report.label());
        assertEquals("BO-06-04-07", report.requirement());
        assertEquals(ReportFamily.SALES, report.family());
        assertEquals(3, report.columns().size());
        assertEquals(2, report.chartColumn());
        assertEquals(TimeSlices.LABEL, report.optionLabel());
        assertEquals(TimeSlices.CHOICES, report.optionChoices());
        assertEquals(java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.HOURS,
                ReportFilter.ARTICLES), report.filters());
    }
}
