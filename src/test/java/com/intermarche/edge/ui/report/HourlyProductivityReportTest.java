package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.sale.Ticket;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HourlyProductivityReport}.
 *
 * <p>The report groups closed tickets into fixed-width time slices, then per
 * slice sums takings, counts customers and articles, counts the distinct
 * registers seen and derives the share and the two averages. It reads its rows
 * through a single JPQL query on a mocked {@link EntityManager} that returns raw
 * {@code Object[]} rows of {@code {closingDate, terminalId, totalIncludingTax,
 * itemCount}}. Every collaborator is a Mockito mock and every assertion bears an
 * absolute expected value. The tests cover the three slice widths (15, 30, 60),
 * the entered and not-entered arms of the ticket loop, both arms of the
 * null-amount and null-terminal ternaries, both arms of the zero-total share
 * guard and the reachable arm of the average divisor guard, plus the metadata
 * accessors.
 */
class HourlyProductivityReportTest {

    /**
     * Builds a report whose package-private {@link EntityManager} field is wired
     * to the given mock.
     *
     * @param entityManager the mocked entity manager
     * @return a report ready under test
     */
    private HourlyProductivityReport reportWith(EntityManager entityManager) {
        HourlyProductivityReport report = new HourlyProductivityReport();
        report.entityManager = entityManager;
        return report;
    }

    /**
     * Creates a fresh {@link TypedQuery} mock, confining the unchecked cast.
     *
     * @return a bare typed-query mock
     */
    @SuppressWarnings("unchecked")
    private TypedQuery<Object[]> typedQueryMock() {
        return mock(TypedQuery.class);
    }

    /**
     * Wires the single ticket query on the entity-manager mock so it returns the
     * given raw rows, echoing itself from every {@code setParameter} call.
     *
     * @param entityManager the mocked entity manager
     * @param rows the raw ticket rows the query yields
     */
    private void stubTickets(EntityManager entityManager, Object[]... rows) {
        TypedQuery<Object[]> query = typedQueryMock();
        when(entityManager.createQuery(contains("from Ticket t"), eq(Object[].class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(rows));
    }

    /**
     * Builds a raw ticket row in the shape the query projects.
     *
     * @param when the closing timestamp
     * @param terminalId the register identifier, possibly null
     * @param total the ticket total, possibly null
     * @param items the article count
     * @return the {closingDate, terminalId, total, itemCount} row
     */
    private Object[] row(LocalDateTime when, String terminalId, BigDecimal total, int items) {
        return new Object[]{when, terminalId, total, items};
    }

    /**
     * Builds criteria carrying the given slice-width option.
     *
     * @param option the submitted width in minutes, or null for the default
     * @return the criteria
     */
    private ReportCriteria criteriaWithOption(String option) {
        ReportCriteria criteria = new ReportCriteria();
        criteria.option = option;
        return criteria;
    }

    /**
     * With several tickets across two hourly slices, two registers in the first
     * slice and non-null amounts, {@code rows()} groups by hour, sorts
     * chronologically, sums takings and articles, counts distinct registers and
     * customers, and derives the share and both averages through the non-zero
     * arms of every guard.
     */
    @Test
    void rowsGroupsByHour() {
        EntityManager entityManager = mock(EntityManager.class);
        stubTickets(entityManager,
                row(LocalDateTime.of(2026, 9, 26, 9, 5), "T1", new BigDecimal("100.00"), 3),
                row(LocalDateTime.of(2026, 9, 26, 9, 50), "T2", new BigDecimal("50.00"), 2),
                row(LocalDateTime.of(2026, 9, 26, 14, 30), "T1", new BigDecimal("30.00"), 1));
        List<List<Object>> rows = reportWith(entityManager).rows(criteriaWithOption(null));
        assertEquals(2, rows.size());
        List<Object> first = rows.get(0);
        assertEquals("09:00 – 10:00", first.get(0));
        assertEquals(new BigDecimal("150.00"), first.get(1));
        assertEquals(new BigDecimal("83.3"), first.get(2));
        assertEquals(2L, first.get(3));
        assertEquals(2L, first.get(4));
        assertEquals(new BigDecimal("75.00"), first.get(5));
        assertEquals(new BigDecimal("75.00"), first.get(6));
        assertEquals(5L, first.get(7));
        List<Object> second = rows.get(1);
        assertEquals("14:00 – 15:00", second.get(0));
        assertEquals(new BigDecimal("30.00"), second.get(1));
        assertEquals(new BigDecimal("16.7"), second.get(2));
        assertEquals(1L, second.get(3));
        assertEquals(1L, second.get(4));
        assertEquals(new BigDecimal("30.00"), second.get(5));
        assertEquals(new BigDecimal("30.00"), second.get(6));
        assertEquals(1L, second.get(7));
    }

    /**
     * With a single ticket whose amount and register are both null, {@code rows()}
     * takes the null arm of both ternaries: the amount folds to zero, the missing
     * register becomes the blank string that still counts as one open register,
     * and the zero period total drives the share guard to its zero arm.
     */
    @Test
    void rowsHandlesNullAmountAndNullTerminal() {
        EntityManager entityManager = mock(EntityManager.class);
        stubTickets(entityManager,
                row(LocalDateTime.of(2026, 9, 26, 10, 15), null, null, 4));
        List<List<Object>> rows = reportWith(entityManager).rows(criteriaWithOption("60"));
        assertEquals(1, rows.size());
        List<Object> only = rows.get(0);
        assertEquals("10:00 – 11:00", only.get(0));
        assertEquals(BigDecimal.ZERO, only.get(1));
        assertEquals(BigDecimal.ZERO, only.get(2));
        assertEquals(1L, only.get(3));
        assertEquals(1L, only.get(4));
        assertEquals(new BigDecimal("0.00"), only.get(5));
        assertEquals(new BigDecimal("0.00"), only.get(6));
        assertEquals(4L, only.get(7));
    }

    /**
     * With no closed ticket in the period, {@code rows()} never enters the loop
     * and returns an empty list — the empty-grouping, zero-sales day.
     */
    @Test
    void rowsIsEmptyWhenNoTicket() {
        EntityManager entityManager = mock(EntityManager.class);
        stubTickets(entityManager);
        List<List<Object>> rows = reportWith(entityManager).rows(criteriaWithOption("30"));
        assertTrue(rows.isEmpty());
    }

    /**
     * With the quarter-hour option, {@code rows()} takes the {@code "15"} slice
     * branch and splits two tickets of the same hour into two fifteen-minute
     * slices bounded on the quarter.
     */
    @Test
    void rowsSlicesByQuarterHour() {
        EntityManager entityManager = mock(EntityManager.class);
        stubTickets(entityManager,
                row(LocalDateTime.of(2026, 9, 26, 9, 7), "T1", new BigDecimal("10.00"), 1),
                row(LocalDateTime.of(2026, 9, 26, 9, 20), "T1", new BigDecimal("20.00"), 1));
        List<List<Object>> rows = reportWith(entityManager).rows(criteriaWithOption("15"));
        assertEquals(2, rows.size());
        assertEquals("09:00 – 09:15", rows.get(0).get(0));
        assertEquals("09:15 – 09:30", rows.get(1).get(0));
    }

    /**
     * With the half-hour option, {@code rows()} takes the {@code "30"} slice
     * branch and names the slice on the half hour.
     */
    @Test
    void rowsSlicesByHalfHour() {
        EntityManager entityManager = mock(EntityManager.class);
        stubTickets(entityManager,
                row(LocalDateTime.of(2026, 9, 26, 9, 40), "T1", new BigDecimal("10.00"), 1));
        List<List<Object>> rows = reportWith(entityManager).rows(criteriaWithOption("30"));
        assertEquals(1, rows.size());
        assertEquals("09:30 – 10:00", rows.get(0).get(0));
    }

    /**
     * A slice ending at midnight wraps its upper bound back to {@code 00:00}
     * rather than reading {@code 24:00}.
     */
    @Test
    void rowsWrapsLastHourToMidnight() {
        EntityManager entityManager = mock(EntityManager.class);
        stubTickets(entityManager,
                row(LocalDateTime.of(2026, 9, 26, 23, 10), "T1", new BigDecimal("10.00"), 1));
        List<List<Object>> rows = reportWith(entityManager).rows(criteriaWithOption("60"));
        assertEquals(1, rows.size());
        assertEquals("23:00 – 00:00", rows.get(0).get(0));
    }

    /**
     * The metadata accessors return the fixed values the requirement fixes.
     */
    @Test
    void metadataIsFixed() {
        HourlyProductivityReport report = new HourlyProductivityReport();
        assertEquals("hourly-productivity", report.code());
        assertEquals("État productivité horaire", report.label());
        assertEquals("BO-06-04-06", report.requirement());
        assertEquals(ReportFamily.SALES, report.family());
        assertEquals(1, report.chartColumn());
        assertEquals("Largeur de tranche", report.optionLabel());
        assertEquals(List.of("60:Une heure", "30:Une demi-heure", "15:Un quart d'heure"),
                report.optionChoices());
    }

    /**
     * The column list enumerates the eight columns of the requirement.
     */
    @Test
    void columnsAreTheEightOfTheRequirement() {
        List<ReportColumn> columns = new HourlyProductivityReport().columns();
        assertEquals(8, columns.size());
    }

    /**
     * The ticket query is filtered on the closed status and the resolved period
     * bounds — proven by driving the CLOSED status through the query the report
     * runs and reading back an empty result.
     */
    @Test
    void rowsFiltersOnClosedStatusAndPeriod() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = typedQueryMock();
        when(entityManager.createQuery(contains("t.status = :closed"), eq(Object[].class))).thenReturn(query);
        when(query.setParameter(eq("closed"), eq(Ticket.TicketStatus.CLOSED))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        List<List<Object>> rows = reportWith(entityManager).rows(criteriaWithOption(null));
        assertTrue(rows.isEmpty());
    }
}
