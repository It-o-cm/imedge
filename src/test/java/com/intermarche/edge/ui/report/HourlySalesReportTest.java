package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.sale.Ticket;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HourlySalesReport}.
 * <p>
 * The report groups closed-ticket lines by hour on a mocked
 * {@link EntityManager}: every collaborator is a Mockito mock, the query result
 * is fed as raw {@code Object[]} rows and assertions bear absolute expected
 * values. The tests cover both arms of the {@code filter != null &&
 * !filter.isBlank()} guard (null, blank, non-blank), the entered/not-entered
 * arms of the aggregation loop, both arms of the {@code computeIfAbsent}
 * new-hour/existing-hour split, both arms of each null-amount ternary and the
 * chronological sort, plus every constant metadata accessor.
 */
class HourlySalesReportTest {

    /**
     * Builds a {@link HourlySalesReport} whose package-private
     * {@link EntityManager} field is wired to the given mock.
     *
     * @param entityManager the mocked entity manager
     * @return a report ready under test
     */
    private HourlySalesReport reportWith(EntityManager entityManager) {
        HourlySalesReport report = new HourlySalesReport();
        report.entityManager = entityManager;
        return report;
    }

    /**
     * Creates a fresh {@link TypedQuery} mock, erasing the unchecked cast to a
     * single suppression point.
     *
     * @return a bare typed-query mock
     */
    @SuppressWarnings("unchecked")
    private TypedQuery<Object[]> typedQueryMock() {
        return mock(TypedQuery.class);
    }

    /**
     * Wires the aggregation query on the given entity-manager mock so it returns
     * the supplied rows and returns itself from any {@code setParameter} call.
     *
     * @param entityManager the mocked entity manager
     * @param rows the pre-baked result rows
     * @return the routed typed-query mock, for parameter verification
     */
    private TypedQuery<Object[]> stubQuery(EntityManager entityManager, List<Object[]> rows) {
        TypedQuery<Object[]> query = typedQueryMock();
        when(entityManager.createQuery(contains("from Ticket t join t.lines l"), eq(Object[].class)))
                .thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(rows);
        return query;
    }

    /**
     * Builds a raw result row of {closingDate, quantity, totalPrice}.
     *
     * @param closingDate the ticket closing instant
     * @param quantity the line quantity, possibly null
     * @param totalPrice the line total price, possibly null
     * @return the three-cell row
     */
    private Object[] row(LocalDateTime closingDate, BigDecimal quantity, BigDecimal totalPrice) {
        return new Object[]{closingDate, quantity, totalPrice};
    }

    /**
     * Builds a fresh criteria with the given article filter option.
     *
     * @param option the article filter, possibly null
     * @return the criteria
     */
    private ReportCriteria criteriaWith(String option) {
        ReportCriteria criteria = new ReportCriteria();
        criteria.option = option;
        return criteria;
    }

    /**
     * With an empty result list and no filter, {@code rows} enters neither the
     * aggregation loop nor the emission loop and returns no row, taking the false
     * arm of the {@code filter != null} guard.
     */
    @Test
    void rowsReturnsEmptyWhenNoSales() {
        EntityManager entityManager = mock(EntityManager.class);
        stubQuery(entityManager, List.of());
        HourlySalesReport report = reportWith(entityManager);
        List<List<Object>> rows = report.rows(criteriaWith(null));
        assertTrue(rows.isEmpty());
    }

    /**
     * With lines across three hours, one hour carrying two lines and the input
     * out of chronological order, {@code rows} groups by hour, sums quantities
     * and takings, orders the hours chronologically and wraps the last slice past
     * midnight — covering the loop-entered, new-hour and existing-hour arms and
     * the non-null arms of both amount ternaries.
     */
    @Test
    void rowsGroupsByHourAndSortsChronologically() {
        EntityManager entityManager = mock(EntityManager.class);
        stubQuery(entityManager, List.of(
                row(LocalDateTime.of(2026, 9, 26, 14, 10), new BigDecimal("2"), new BigDecimal("5.00")),
                row(LocalDateTime.of(2026, 9, 26, 9, 30), new BigDecimal("1"), new BigDecimal("3.00")),
                row(LocalDateTime.of(2026, 9, 26, 14, 55), new BigDecimal("3"), new BigDecimal("7.50")),
                row(LocalDateTime.of(2026, 9, 26, 23, 5), new BigDecimal("4"), new BigDecimal("9.00"))));
        HourlySalesReport report = reportWith(entityManager);
        List<List<Object>> rows = report.rows(criteriaWith(null));
        assertEquals(3, rows.size());
        assertEquals(List.of("09:00 – 10:00", new BigDecimal("1"), new BigDecimal("3.00")), rows.get(0));
        assertEquals(List.of("14:00 – 15:00", new BigDecimal("5"), new BigDecimal("12.50")), rows.get(1));
        assertEquals(List.of("23:00 – 00:00", new BigDecimal("4"), new BigDecimal("9.00")), rows.get(2));
    }

    /**
     * With a single line whose quantity and total price are both null,
     * {@code rows} takes the null arm of both amount ternaries and reads the slot
     * as zero units and zero turnover.
     */
    @Test
    void rowsTreatsNullQuantityAndPriceAsZero() {
        EntityManager entityManager = mock(EntityManager.class);
        stubQuery(entityManager, List.<Object[]>of(
                row(LocalDateTime.of(2026, 9, 26, 8, 0), null, null)));
        HourlySalesReport report = reportWith(entityManager);
        List<List<Object>> rows = report.rows(criteriaWith(null));
        assertEquals(1, rows.size());
        assertEquals(List.of("08:00 – 09:00", BigDecimal.ZERO, BigDecimal.ZERO), rows.get(0));
    }

    /**
     * With a non-blank article filter surrounded by spaces, {@code rows} takes
     * the true arm of both filter guards, appends the article clause to the JPQL
     * and binds the trimmed filter and its EAN prefix.
     */
    @Test
    void rowsAppliesArticleFilter() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = stubQuery(entityManager, List.<Object[]>of(
                row(LocalDateTime.of(2026, 9, 26, 10, 0), new BigDecimal("1"), new BigDecimal("2.00"))));
        HourlySalesReport report = reportWith(entityManager);
        List<List<Object>> rows = report.rows(criteriaWith("  FR01  "));
        assertEquals(1, rows.size());
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createQuery(jpql.capture(), eq(Object[].class));
        assertTrue(jpql.getValue().contains("and (l.familyCode = :filter or l.ean like :prefix or l.plu = :filter)"));
        verify(query).setParameter("filter", "FR01");
        verify(query).setParameter("prefix", "FR01%");
    }

    /**
     * With a blank filter, {@code rows} takes the false arm of the
     * {@code !filter.isBlank()} operand, leaves the article clause out of the JPQL
     * and binds neither the filter nor the prefix parameter.
     */
    @Test
    void rowsIgnoresBlankFilter() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = stubQuery(entityManager, List.of());
        HourlySalesReport report = reportWith(entityManager);
        List<List<Object>> rows = report.rows(criteriaWith("   "));
        assertTrue(rows.isEmpty());
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createQuery(jpql.capture(), eq(Object[].class));
        assertFalse(jpql.getValue().contains("l.familyCode = :filter"));
        verify(query, never()).setParameter(eq("filter"), any());
        verify(query, never()).setParameter(eq("prefix"), any());
    }

    /**
     * The closed status, period bounds and their parameter names are bound on the
     * query regardless of the filter, which the empty-filter case must still wire.
     */
    @Test
    void rowsBindsStatusAndPeriodBounds() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = stubQuery(entityManager, List.of());
        HourlySalesReport report = reportWith(entityManager);
        report.rows(criteriaWith(null));
        verify(query).setParameter("closed", Ticket.TicketStatus.CLOSED);
        verify(query).setParameter(eq("from"), any());
        verify(query).setParameter(eq("to"), any());
    }

    /**
     * The report's constant metadata — code, label, requirement, family, chart
     * column and option label — are the fixed values the requirement names.
     */
    @Test
    void metadataAccessorsReturnConstants() {
        HourlySalesReport report = new HourlySalesReport();
        assertEquals("hourly-sales", report.code());
        assertEquals("État ventes horaires", report.label());
        assertEquals("BO-06-04-07", report.requirement());
        assertEquals(ReportFamily.SALES, report.family());
        assertEquals(2, report.chartColumn());
        assertEquals("Filtre article (code famille ou début d'EAN)", report.optionLabel());
    }

    /**
     * The three declared columns are a text slot, a totalled unit count and a
     * totalled turnover amount, in that order.
     */
    @Test
    void columnsDescribeSlotUnitsAndTurnover() {
        HourlySalesReport report = new HourlySalesReport();
        List<ReportColumn> columns = report.columns();
        assertEquals(3, columns.size());
        assertEquals(new ReportColumn("slot", "Tranche horaire", ReportColumn.Kind.TEXT, false), columns.get(0));
        assertEquals(new ReportColumn("units", "Unités vendues", ReportColumn.Kind.INTEGER, true), columns.get(1));
        assertEquals(new ReportColumn("turnover", "Chiffre d'affaires", ReportColumn.Kind.AMOUNT, true), columns.get(2));
    }
}
