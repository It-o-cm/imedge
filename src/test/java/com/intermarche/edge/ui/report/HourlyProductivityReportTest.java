package com.intermarche.edge.ui.report;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static com.intermarche.edge.ui.report.QueryStub.row;
import static com.intermarche.edge.ui.report.QueryStub.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HourlyProductivityReport}: the slices of takings,
 * customers and articles, the registers whose session was open during each
 * slice, the averages, the mean row closing the slices, and the metadata.
 * Branches: a ticket outside the hours, a null amount, a null register, a
 * session still open, one opened before the period, a session slice outside
 * the hours, a period that took nothing, no ticket at all.
 */
class HourlyProductivityReportTest {

    /** The day edited. */
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

    /**
     * Runs the report over the stub, on the day.
     *
     * @param stub the query stub
     * @param option the slice width, or null
     * @return the rows
     */
    private List<List<Object>> run(QueryStub stub, String option) {
        HourlyProductivityReport report = new HourlyProductivityReport();
        report.entityManager = stub.entityManager;
        ReportCriteria criteria = new ReportCriteria();
        criteria.period = ReportPeriod.DAY;
        criteria.reference = DAY;
        criteria.option = option;
        criteria.hourTo = LocalTime.of(20, 0);
        return report.rows(criteria);
    }

    /**
     * The slices sum the tickets, count the registers open from the sessions
     * and the tickets, and the mean row closes them.
     */
    @Test
    void theSlicesAndTheirMean() {
        QueryStub stub = new QueryStub()
                .answer("from CashSession s", rows(
                        row("C01", DAY.atTime(8, 30), DAY.atTime(10, 10)),
                        row("C02", DAY.minusDays(1).atTime(22, 0), null)))
                .answer("from Ticket t", rows(
                        row(DAY.atTime(9, 5), "C01", new BigDecimal("30.00"), 3),
                        row(DAY.atTime(9, 40), null, null, 1),
                        row(DAY.atTime(11, 0), "C03", new BigDecimal("10.00"), 2),
                        row(DAY.atTime(21, 0), "C01", new BigDecimal("99.00"), 9)));
        List<List<Object>> rows = run(stub, null);
        assertEquals(3, rows.size());
        List<Object> nine = rows.get(0);
        assertEquals("09:00 – 10:00", nine.get(0));
        assertEquals(new BigDecimal("30.00"), nine.get(1));
        assertEquals(new BigDecimal("75.0"), nine.get(2));
        assertEquals(2L, nine.get(3));
        assertEquals(3L, nine.get(4));
        assertEquals(new BigDecimal("10.00"), nine.get(5));
        assertEquals(new BigDecimal("0.67"), nine.get(6));
        assertEquals(new BigDecimal("15.00"), nine.get(7));
        assertEquals(4L, nine.get(8));
        List<Object> eleven = rows.get(1);
        assertEquals("11:00 – 12:00", eleven.get(0));
        assertEquals(2L, eleven.get(4));
        List<Object> mean = rows.get(2);
        assertTrue(ReportData.isSubtotal(mean));
        assertEquals("Moyenne par tranche", mean.get(0));
        assertEquals(new BigDecimal("20.00"), mean.get(1));
        assertEquals(null, mean.get(2));
        assertEquals(2L, mean.get(3));
        assertEquals(3L, mean.get(4));
        assertEquals(new BigDecimal("8.00"), mean.get(5));
        assertEquals(new BigDecimal("0.60"), mean.get(6));
        assertEquals(new BigDecimal("13.33"), mean.get(7));
        assertEquals(3L, mean.get(8));
    }

    /**
     * A period that took nothing shares nothing, and no ticket draws no row.
     */
    @Test
    void zeroTakingsAndNoTickets() {
        QueryStub stub = new QueryStub().answer("from Ticket t", rows(row(DAY.atTime(9, 5), "C01", null, 0)));
        List<List<Object>> rows = run(stub, "15");
        assertEquals("09:00 – 09:15", rows.get(0).get(0));
        assertEquals(BigDecimal.ZERO, rows.get(0).get(2));
        assertEquals(new BigDecimal("0.00"), rows.get(1).get(7));
        assertTrue(run(new QueryStub(), "30").isEmpty());
    }

    /**
     * The metadata: identity, chart, slice option, filters and the nine columns.
     */
    @Test
    void columnsAreTheNineOfTheRequirement() {
        HourlyProductivityReport report = new HourlyProductivityReport();
        assertEquals("hourly-productivity", report.code());
        assertEquals("État productivité horaire", report.label());
        assertEquals("BO-06-04-06", report.requirement());
        assertEquals(ReportFamily.SALES, report.family());
        assertEquals(1, report.chartColumn());
        assertEquals(TimeSlices.LABEL, report.optionLabel());
        assertEquals(TimeSlices.CHOICES, report.optionChoices());
        assertEquals(java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.HOURS),
                report.filters());
        assertEquals(9, report.columns().size());
        assertEquals(ReportColumn.Kind.DECIMAL, report.columns().get(6).kind());
        assertEquals("Nombre de clients moyen par TPV", report.columns().get(6).label());
    }
}
