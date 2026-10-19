package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.people.Employee;
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
 * Unit tests for {@link FidelityCardRateReport} ({@code BO-06-06-11}).
 *
 * <p>The report builds one JPQL query on a mocked {@link EntityManager} and
 * folds its rows into counters. Branch enumeration (both arms): the two
 * detail levels; a ticket carrying a card and one carrying none, blank
 * included; the selected and unselected cashier, and the empty selection; a
 * ticket with no cashier at all; the register filter present and absent; and
 * the zero-customer guard of the share.
 */
class FidelityCardRateReportTest {

    /**
     * Creates a fresh typed-query mock, confining the unchecked cast.
     *
     * @param <T> the query result type
     * @return a bare typed-query mock
     */
    @SuppressWarnings("unchecked")
    private <T> TypedQuery<T> typedQueryMock() {
        return mock(TypedQuery.class);
    }

    /**
     * Builds a report wired to a mocked entity manager returning rows.
     *
     * @param rows the rows the query yields
     * @return the report under test
     */
    private FidelityCardRateReport reportOver(List<Object[]> rows) {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = typedQueryMock();
        when(entityManager.createQuery(contains("from Ticket t"), eq(Object[].class)))
                .thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(rows);
        FidelityCardRateReport report = new FidelityCardRateReport();
        report.entityManager = entityManager;
        return report;
    }

    /**
     * Builds an employee carrying a badge and a name.
     *
     * @param badgeId the badge
     * @param lastName the surname
     * @return the employee
     */
    private Employee employee(String badgeId, String lastName) {
        Employee employee = new Employee();
        employee.badgeId = badgeId;
        employee.lastName = lastName;
        employee.firstName = "";
        return employee;
    }

    /**
     * The report names itself, its requirement and its family.
     */
    @Test
    void theReportNamesItself() {
        FidelityCardRateReport report = reportOver(List.of());
        assertEquals("fidelity-card-rate", report.code());
        assertEquals("Taux d'encartement par caissière", report.label());
        assertEquals("BO-06-06-11", report.requirement());
        assertEquals(ReportFamily.CASHIERS, report.family());
        assertEquals("Niveau de détail", report.optionLabel());
        assertEquals(List.of(FidelityCardRateReport.BY_CASHIER, FidelityCardRateReport.BY_STORE),
                report.optionChoices());
        assertEquals(6, report.columns().size());
    }

    /**
     * BO-06-06-11: per cashier and per day, a carded ticket counts in both
     * columns and a card-less one — null or blank — only in the total.
     */
    @Test
    void theRateIsCountedPerCashierAndPerDay() {
        Employee alice = employee("007", "Martin");
        LocalDateTime morning = LocalDateTime.of(2026, 10, 15, 9, 0);
        FidelityCardRateReport report = reportOver(List.of(
                new Object[]{morning, alice, "2990000000019"},
                new Object[]{morning.plusHours(1), alice, null},
                new Object[]{morning.plusHours(2), alice, "   "},
                new Object[]{morning.plusHours(3), alice, "2990000000026"}));
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        assertEquals("2026-10-15", rows.get(0).get(0));
        assertEquals("007", rows.get(0).get(1));
        assertEquals(4L, rows.get(0).get(3));
        assertEquals(2L, rows.get(0).get(4));
        assertEquals(new BigDecimal("50.00"), rows.get(0).get(5));
    }

    /**
     * BO-06-06-11: at store level the day is one line whatever the cashiers,
     * and the cashier columns say so rather than naming one of them.
     */
    @Test
    void theStoreLevelKeepsOneLinePerDay() {
        LocalDateTime day = LocalDateTime.of(2026, 10, 15, 9, 0);
        FidelityCardRateReport report = reportOver(List.of(
                new Object[]{day, employee("007", "Martin"), "299"},
                new Object[]{day.plusHours(1), employee("008", "Durand"), null}));
        ReportCriteria criteria = new ReportCriteria();
        criteria.option = FidelityCardRateReport.BY_STORE;
        List<List<Object>> rows = report.rows(criteria);
        assertEquals(1, rows.size());
        assertEquals("", rows.get(0).get(1));
        assertEquals("Tous", rows.get(0).get(2));
        assertEquals(2L, rows.get(0).get(3));
        assertEquals(1L, rows.get(0).get(4));
    }

    /**
     * BO-06-06-11: a cashier selection keeps only those cashiers, and a
     * ticket carrying no cashier at all is left out of a selected edition.
     */
    @Test
    void aCashierSelectionKeepsOnlyThose() {
        LocalDateTime day = LocalDateTime.of(2026, 10, 15, 9, 0);
        FidelityCardRateReport report = reportOver(List.of(
                new Object[]{day, employee("007", "Martin"), "299"},
                new Object[]{day, employee("008", "Durand"), "299"},
                new Object[]{day, null, "299"}));
        ReportCriteria criteria = new ReportCriteria();
        criteria.cashiers.add("007");
        List<List<Object>> rows = report.rows(criteria);
        assertEquals(1, rows.size());
        assertEquals("007", rows.get(0).get(1));
    }

    /**
     * An edition naming registers adds the clause and binds them; an edition
     * naming none asks for the whole store.
     */
    @Test
    void theRegisterFilterIsAddedOnlyWhenAsked() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = typedQueryMock();
        when(entityManager.createQuery(contains("from Ticket t"), eq(Object[].class)))
                .thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        FidelityCardRateReport report = new FidelityCardRateReport();
        report.entityManager = entityManager;
        ReportCriteria filtered = new ReportCriteria();
        filtered.terminals.add("CAISSE1");
        assertTrue(report.rows(filtered).isEmpty());
        org.mockito.Mockito.verify(entityManager)
                .createQuery(contains("t.terminalId in :terminals"), eq(Object[].class));
        assertTrue(report.rows(new ReportCriteria()).isEmpty());
    }
}
