package com.intermarche.pos.ui.report;

import com.intermarche.pos.domain.people.Employee;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CancellationReport}.
 *
 * <p>The report reads cancelled lines through one JPQL query on a mocked
 * {@link EntityManager}; every collaborator is a Mockito mock, the result rows
 * are fed as raw {@code Object[]} and assertions bear absolute expected values.
 * The tests cover both arms of the {@code terminals.isEmpty()} guard, the
 * entered/not-entered arms of the row loop, both arms of every per-row ternary
 * (null and non-null cashier, product label, amount, ticket number, supervisor)
 * and the three branches of the private {@code shorten} helper (null signature,
 * signature of at most sixteen characters, longer signature).
 */
class CancellationReportTest {

    /**
     * Creates a fresh {@link TypedQuery} mock, confining the unchecked cast to a
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
     * Wires the cancelled-lines query on the given entity-manager mock so it
     * returns the supplied rows and returns itself from every parameter binding.
     *
     * @param entityManager the mocked entity manager
     * @param rows the result rows to serve
     * @return the routed typed-query mock, for verification of parameter binding
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
     * Builds a report wired to the given entity-manager mock.
     *
     * @param entityManager the mocked entity manager
     * @return a report ready under test
     */
    private CancellationReport reportWith(EntityManager entityManager) {
        CancellationReport report = new CancellationReport();
        report.entityManager = entityManager;
        return report;
    }

    /**
     * Builds a cashier with the given badge and name parts.
     *
     * @param badgeId the badge identifier
     * @param firstName the first name
     * @param lastName the last name
     * @return the populated employee
     */
    private Employee cashier(String badgeId, String firstName, String lastName) {
        Employee employee = new Employee();
        employee.badgeId = badgeId;
        employee.firstName = firstName;
        employee.lastName = lastName;
        return employee;
    }

    /**
     * With one restricting terminal and a fully populated row whose signature is
     * short, {@code rows} enters the terminals guard on both uses, enters the
     * loop, takes the non-null arm of every ternary, appends the terminals
     * parameter and returns the signature verbatim.
     */
    @Test
    void rowsWithTerminalsFullRow() {
        EntityManager entityManager = mock(EntityManager.class);
        LocalDateTime when = LocalDateTime.of(2026, 9, 26, 10, 15);
        Object[] row = new Object[]{when, cashier("C123", "Alice", "Martin"), "Milk",
                new BigDecimal("3.50"), "T-0001", "SUPER1", "SIGN-SHORT", "CAISSE1"};
        TypedQuery<Object[]> query = stubQuery(entityManager, List.<Object[]>of(row));
        ReportCriteria criteria = new ReportCriteria();
        criteria.terminals.add("CAISSE1");
        CancellationReport report = reportWith(entityManager);
        List<List<Object>> rows = report.rows(criteria);
        assertEquals(1, rows.size());
        List<Object> only = rows.get(0);
        assertEquals(when, only.get(0));
        assertEquals("C123", only.get(1));
        assertEquals("Alice Martin", only.get(2));
        assertEquals("Annulation de ligne", only.get(3));
        assertEquals("Milk", only.get(4));
        assertEquals(new BigDecimal("3.50"), only.get(5));
        assertEquals("T-0001", only.get(6));
        assertEquals("SUPER1", only.get(7));
        assertEquals("SIGN-SHORT", only.get(8));
        verify(query).setParameter("terminals", criteria.terminals);
    }

    /**
     * With no restricting terminal and a row whose amount is null, {@code rows}
     * takes the empty arm of the terminals guard, never binds the terminals
     * parameter and substitutes {@link BigDecimal#ZERO} for the missing amount.
     */
    @Test
    void rowsWithNullAmount() {
        EntityManager entityManager = mock(EntityManager.class);
        LocalDateTime when = LocalDateTime.of(2026, 9, 26, 11, 0);
        Object[] row = new Object[]{when, cashier("C900", "Bob", "Durand"), "Bread",
                null, "T-0002", "SUPER2", "SIG", "CAISSE2"};
        TypedQuery<Object[]> query = stubQuery(entityManager, List.<Object[]>of(row));
        ReportCriteria criteria = new ReportCriteria();
        CancellationReport report = reportWith(entityManager);
        List<List<Object>> rows = report.rows(criteria);
        assertEquals(1, rows.size());
        assertEquals(BigDecimal.ZERO, rows.get(0).get(5));
        verify(query, org.mockito.Mockito.never()).setParameter(eq("terminals"), any());
    }

    /**
     * With a row whose cashier is null, {@code rows} takes the null arm of both
     * cashier ternaries and exposes a blank badge and a blank name.
     */
    @Test
    void rowsWithNullCashier() {
        EntityManager entityManager = mock(EntityManager.class);
        LocalDateTime when = LocalDateTime.of(2026, 9, 26, 12, 30);
        Object[] row = new Object[]{when, null, "Eggs",
                new BigDecimal("2.00"), "T-0003", "SUPER3", "SIG", "CAISSE3"};
        stubQuery(entityManager, List.<Object[]>of(row));
        ReportCriteria criteria = new ReportCriteria();
        CancellationReport report = reportWith(entityManager);
        List<List<Object>> rows = report.rows(criteria);
        assertEquals(1, rows.size());
        assertEquals("", rows.get(0).get(1));
        assertEquals("", rows.get(0).get(2));
        assertEquals(new BigDecimal("2.00"), rows.get(0).get(5));
    }

    /**
     * With an empty result list, {@code rows} never enters the row loop and
     * returns an empty list.
     */
    @Test
    void rowsWithEmptyResult() {
        EntityManager entityManager = mock(EntityManager.class);
        stubQuery(entityManager, List.of());
        ReportCriteria criteria = new ReportCriteria();
        CancellationReport report = reportWith(entityManager);
        List<List<Object>> rows = report.rows(criteria);
        assertTrue(rows.isEmpty());
    }

    /**
     * With a row whose product label, ticket number, supervisor and signature
     * are all null, {@code rows} takes the null arm of each of those ternaries
     * and the null branch of {@code shorten}, substituting empty texts.
     */
    @Test
    void rowsWithNullOptionalFields() {
        EntityManager entityManager = mock(EntityManager.class);
        LocalDateTime when = LocalDateTime.of(2026, 9, 26, 13, 45);
        Object[] row = new Object[]{when, cashier("C001", "Carol", "Petit"), null,
                new BigDecimal("1.00"), null, null, null, "CAISSE4"};
        stubQuery(entityManager, List.<Object[]>of(row));
        ReportCriteria criteria = new ReportCriteria();
        CancellationReport report = reportWith(entityManager);
        List<List<Object>> rows = report.rows(criteria);
        assertEquals(1, rows.size());
        List<Object> only = rows.get(0);
        assertEquals("", only.get(4));
        assertEquals(new BigDecimal("1.00"), only.get(5));
        assertEquals("", only.get(6));
        assertEquals("", only.get(7));
        assertEquals("", only.get(8));
    }

    /**
     * With a signature longer than sixteen characters, {@code shorten} takes its
     * truncating arm and returns the first sixteen characters followed by an
     * ellipsis.
     */
    @Test
    void rowsWithLongSignature() {
        EntityManager entityManager = mock(EntityManager.class);
        LocalDateTime when = LocalDateTime.of(2026, 9, 26, 14, 0);
        Object[] row = new Object[]{when, cashier("C002", "Dan", "Roche"), "Water",
                new BigDecimal("0.99"), "T-0005", "SUPER5", "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "CAISSE5"};
        stubQuery(entityManager, List.<Object[]>of(row));
        ReportCriteria criteria = new ReportCriteria();
        CancellationReport report = reportWith(entityManager);
        List<List<Object>> rows = report.rows(criteria);
        assertEquals(1, rows.size());
        assertEquals("ABCDEFGHIJKLMNOP…", rows.get(0).get(8));
    }

    /**
     * The static descriptors of the report are the fixed values the requirement
     * enumerates: code, label, requirement, family, the nine columns and the
     * absence of a chart.
     */
    @Test
    void metadata() {
        CancellationReport report = new CancellationReport();
        assertEquals("cancellations", report.code());
        assertEquals("État annulation", report.label());
        assertEquals("BO-06-06-04", report.requirement());
        assertEquals(ReportFamily.CASHIERS, report.family());
        assertEquals(9, report.columns().size());
        assertEquals("when", report.columns().get(0).key());
        assertEquals(-1, report.chartColumn());
    }
}
