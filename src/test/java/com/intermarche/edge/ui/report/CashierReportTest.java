package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.people.Employee;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CashierReport}.
 * <p>
 * The report aggregates three JPQL queries — takings, cancellations and price
 * forcings — grouped per cashier on a mocked {@link EntityManager}, then folds
 * them into one accumulator row per cashier, computes the average basket and the
 * articles-per-customer ratio and sorts the rows by takings descending. Every
 * collaborator is a Mockito mock, query results are fed as raw {@code Object[]}
 * rows and assertions bear absolute expected values. The tests cover both arms
 * of the three {@code line[2] == null} amount guards, both arms of the two
 * {@code tickets == 0} ratio ternaries, the three legs of the
 * {@code cashier == null || cashier.badgeId == null} badge guard, both arms of
 * the {@code cashier == null} name ternary, both arms of the
 * {@code criteria.cashiers.isEmpty()} filter/bind guards and the sort of a
 * multi-cashier result.
 */
class CashierReportTest {

    /**
     * Creates a fresh {@link TypedQuery} mock, erasing the unchecked cast to a
     * single suppression point.
     *
     * @param <T> the query result type
     * @return a bare typed-query mock
     */
    @SuppressWarnings("unchecked")
    private <T> TypedQuery<T> typedQueryMock() {
        return mock(TypedQuery.class);
    }

    /**
     * Wires the three per-cashier queries of {@code rows()} on the given
     * entity-manager mock, each returning the supplied pre-baked rows and each
     * returning itself from any {@code setParameter} call.
     *
     * @param entityManager the mocked entity manager
     * @param salesRows the rows of the takings query
     * @param cancelRows the rows of the cancellations query
     * @param forcingRows the rows of the price-forcing query
     */
    private void stubQueries(EntityManager entityManager, List<Object[]> salesRows,
            List<Object[]> cancelRows, List<Object[]> forcingRows) {
        TypedQuery<Object[]> salesQuery = typedQueryMock();
        when(entityManager.createQuery(contains("totalIncludingTax"), eq(Object[].class))).thenReturn(salesQuery);
        when(salesQuery.setParameter(anyString(), any())).thenReturn(salesQuery);
        when(salesQuery.getResultList()).thenReturn(salesRows);
        TypedQuery<Object[]> cancelQuery = typedQueryMock();
        when(entityManager.createQuery(contains("l.cancelled = true"), eq(Object[].class))).thenReturn(cancelQuery);
        when(cancelQuery.setParameter(anyString(), any())).thenReturn(cancelQuery);
        when(cancelQuery.getResultList()).thenReturn(cancelRows);
        TypedQuery<Object[]> forcingQuery = typedQueryMock();
        when(entityManager.createQuery(contains("originalUnitPrice - l.unitPrice"), eq(Object[].class))).thenReturn(forcingQuery);
        when(forcingQuery.setParameter(anyString(), any())).thenReturn(forcingQuery);
        when(forcingQuery.getResultList()).thenReturn(forcingRows);
    }

    /**
     * Builds a {@link CashierReport} whose {@link EntityManager} field is wired
     * to the given mock.
     *
     * @param entityManager the mocked entity manager
     * @return a report ready under test
     */
    private CashierReport reportWith(EntityManager entityManager) {
        CashierReport report = new CashierReport();
        report.entityManager = entityManager;
        return report;
    }

    /**
     * Builds an {@link Employee} with the given badge and name.
     *
     * @param badgeId the badge identifier, possibly null
     * @param firstName the first name
     * @param lastName the last name
     * @return the populated employee
     */
    private Employee employee(String badgeId, String firstName, String lastName) {
        Employee employee = new Employee();
        employee.badgeId = badgeId;
        employee.firstName = firstName;
        employee.lastName = lastName;
        return employee;
    }

    /**
     * With takings, cancellations and forcings for two named cashiers, non-null
     * amounts and non-zero ticket counts, {@code rows()} folds one row per
     * cashier, takes the non-null arm of every amount guard, the non-zero arm of
     * both ratio ternaries, the all-non-null leg of the badge guard, the
     * non-null arm of the name ternary and the non-empty arm of the cashier
     * filter/bind, then sorts the two rows by takings descending.
     */
    @Test
    void rowsAggregatesAndSortsTwoCashiers() {
        EntityManager entityManager = mock(EntityManager.class);
        Employee alice = employee("A1", "Alice", "Martin");
        Employee bob = employee("B1", "Bob", "Durand");
        stubQueries(entityManager,
                List.of(
                        new Object[]{alice, 4L, new BigDecimal("100.00"), 40L},
                        new Object[]{bob, 2L, new BigDecimal("200.00"), 10L}),
                List.<Object[]>of(new Object[]{alice, 1L, new BigDecimal("5.00")}),
                List.<Object[]>of(new Object[]{bob, 3L, new BigDecimal("7.50")}));
        CashierReport report = reportWith(entityManager);
        ReportCriteria criteria = new ReportCriteria();
        criteria.cashiers = Set.of("A1", "B1");
        List<List<Object>> rows = report.rows(criteria);
        assertEquals(2, rows.size());
        List<Object> first = rows.get(0);
        assertEquals("B1", first.get(0));
        assertEquals("Bob Durand", first.get(1));
        assertEquals(2L, first.get(2));
        assertEquals(0, new BigDecimal("200.00").compareTo((BigDecimal) first.get(3)));
        assertEquals(10L, first.get(4));
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) first.get(5)));
        assertEquals(0, new BigDecimal("5.0").compareTo((BigDecimal) first.get(6)));
        assertEquals(0L, first.get(7));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) first.get(8)));
        assertEquals(3L, first.get(9));
        assertEquals(0, new BigDecimal("7.50").compareTo((BigDecimal) first.get(10)));
        List<Object> second = rows.get(1);
        assertEquals("A1", second.get(0));
        assertEquals("Alice Martin", second.get(1));
        assertEquals(4L, second.get(2));
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) second.get(3)));
        assertEquals(40L, second.get(4));
        assertEquals(0, new BigDecimal("25.00").compareTo((BigDecimal) second.get(5)));
        assertEquals(0, new BigDecimal("10.0").compareTo((BigDecimal) second.get(6)));
        assertEquals(1L, second.get(7));
        assertEquals(0, new BigDecimal("5.00").compareTo((BigDecimal) second.get(8)));
        assertEquals(0L, second.get(9));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) second.get(10)));
    }

    /**
     * With a null takings amount, a null cancellation amount and a null forcing
     * amount for a single cashier, {@code rows()} takes the null arm of every
     * amount guard, coalescing each to zero, and keeps the non-zero ratio arms.
     */
    @Test
    void rowsTreatsNullAmountsAsZero() {
        EntityManager entityManager = mock(EntityManager.class);
        Employee zoe = employee("Z1", "Zoe", "Blanc");
        stubQueries(entityManager,
                List.<Object[]>of(new Object[]{zoe, 2L, null, 8L}),
                List.<Object[]>of(new Object[]{zoe, 1L, null}),
                List.<Object[]>of(new Object[]{zoe, 1L, null}));
        CashierReport report = reportWith(entityManager);
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        List<Object> row = rows.get(0);
        assertEquals("Z1", row.get(0));
        assertEquals(2L, row.get(2));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) row.get(3)));
        assertEquals(8L, row.get(4));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) row.get(5)));
        assertEquals(0, new BigDecimal("4.0").compareTo((BigDecimal) row.get(6)));
        assertEquals(1L, row.get(7));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) row.get(8)));
        assertEquals(1L, row.get(9));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) row.get(10)));
    }

    /**
     * With a takings line whose cashier is null, {@code rows()} takes the
     * {@code cashier == null} leg of the badge guard — placing the figure under
     * the « — » badge — and the {@code cashier == null} arm of the name
     * ternary, leaving the operator name blank.
     */
    @Test
    void rowsPlacesNullCashierUnderDash() {
        EntityManager entityManager = mock(EntityManager.class);
        stubQueries(entityManager,
                List.<Object[]>of(new Object[]{null, 3L, new BigDecimal("30.00"), 6L}),
                List.of(),
                List.of());
        CashierReport report = reportWith(entityManager);
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        List<Object> row = rows.get(0);
        assertEquals("—", row.get(0));
        assertEquals("", row.get(1));
        assertEquals(3L, row.get(2));
        assertEquals(0, new BigDecimal("30.00").compareTo((BigDecimal) row.get(3)));
        assertEquals(6L, row.get(4));
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) row.get(5)));
        assertEquals(0, new BigDecimal("2.0").compareTo((BigDecimal) row.get(6)));
    }

    /**
     * With a takings line whose cashier has a null badge but a name,
     * {@code rows()} takes the {@code cashier.badgeId == null} leg of the badge
     * guard — placing the figure under « — » — and the non-null arm of the name
     * ternary, exposing the cashier's full name.
     */
    @Test
    void rowsPlacesNullBadgeCashierUnderDashKeepingName() {
        EntityManager entityManager = mock(EntityManager.class);
        Employee nameless = employee(null, "Zoe", "Blanc");
        stubQueries(entityManager,
                List.<Object[]>of(new Object[]{nameless, 1L, new BigDecimal("50.00"), 5L}),
                List.of(),
                List.of());
        CashierReport report = reportWith(entityManager);
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        List<Object> row = rows.get(0);
        assertEquals("—", row.get(0));
        assertEquals("Zoe Blanc", row.get(1));
        assertEquals(1L, row.get(2));
    }

    /**
     * With every query returning no rows, {@code rows()} enters none of the
     * accumulation loops and returns an empty list.
     */
    @Test
    void rowsIsEmptyOnZeroSales() {
        EntityManager entityManager = mock(EntityManager.class);
        stubQueries(entityManager, List.of(), List.of(), List.of());
        CashierReport report = reportWith(entityManager);
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertTrue(rows.isEmpty());
    }

    /**
     * With a cashier that appears only through cancellations — no takings line —
     * {@code rows()} builds an accumulator with zero tickets, taking the
     * {@code tickets == 0} arm of both ratio ternaries, so the average basket and
     * articles-per-customer both collapse to zero.
     */
    @Test
    void rowsCollapsesRatiosWhenNoTickets() {
        EntityManager entityManager = mock(EntityManager.class);
        Employee alice = employee("A1", "Alice", "Martin");
        stubQueries(entityManager,
                List.of(),
                List.<Object[]>of(new Object[]{alice, 2L, new BigDecimal("10.00")}),
                List.of());
        CashierReport report = reportWith(entityManager);
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        List<Object> row = rows.get(0);
        assertEquals("A1", row.get(0));
        assertEquals(0L, row.get(2));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) row.get(3)));
        assertEquals(0L, row.get(4));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) row.get(5)));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) row.get(6)));
        assertEquals(2L, row.get(7));
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) row.get(8)));
    }

    /**
     * The report's descriptive metadata is fixed, so the getters return their
     * constant identity, family and column layout without touching persistence.
     */
    @Test
    void metadataIsConstant() {
        CashierReport report = reportWith(mock(EntityManager.class));
        assertEquals("cashiers", report.code());
        assertEquals("État caissière", report.label());
        assertEquals("BO-06-06-03", report.requirement());
        assertEquals(ReportFamily.CASHIERS, report.family());
        assertEquals(11, report.columns().size());
        assertEquals(3, report.chartColumn());
        assertEquals(1, report.chartLabelColumn());
    }
}
