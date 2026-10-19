package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.session.TechnicalEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;

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
 * Unit tests for {@link ReasonCodeReport}.
 * <p>
 * The report merges two JPQL result sets read on a mocked {@link EntityManager}
 * — the justified cash movements and the justified technical events — into one
 * chronological list of rows. Every collaborator is a Mockito mock, the two
 * queries are routed by a distinctive fragment of their JPQL and fed raw
 * {@code Object[]} rows, and every assertion bears an absolute expected value.
 * The tests cover both arms of each null-guard ternary on both sources
 * ({@code terminal}, {@code cashier}/{@code badge}, {@code detail}), both arms
 * of the {@code instanceof} and {@code badgeId} guards inside {@code badge},
 * the entered and not-entered arms of both source loops, the chronological
 * sort, and the empty-result path.
 */
class ReasonCodeReportTest {

    /**
     * Creates a fresh {@link TypedQuery} mock, confining the unchecked cast to
     * a single suppression point.
     *
     * @param <T> the query result type
     * @return a bare typed-query mock
     */
    @SuppressWarnings("unchecked")
    private <T> TypedQuery<T> typedQueryMock() {
        return mock(TypedQuery.class);
    }

    /**
     * Builds a {@link ReasonCodeReport} wired to the given entity manager, and
     * routes its two queries to two typed-query mocks returning the supplied
     * rows. Each typed query returns itself from any {@code setParameter} call.
     *
     * @param entityManager the mocked entity manager
     * @param movementRows the rows the cash-movement query returns
     * @param eventRows the rows the technical-event query returns
     * @return the report ready under test
     */
    private ReasonCodeReport reportWith(EntityManager entityManager,
            List<Object[]> movementRows, List<Object[]> eventRows) {
        TypedQuery<Object[]> movementQuery = typedQueryMock();
        when(entityManager.createQuery(contains("from CashMovement m"), eq(Object[].class)))
                .thenReturn(movementQuery);
        when(movementQuery.setParameter(anyString(), any())).thenReturn(movementQuery);
        when(movementQuery.getResultList()).thenReturn(movementRows);
        TypedQuery<Object[]> eventQuery = typedQueryMock();
        when(entityManager.createQuery(contains("from TechnicalEvent e"), eq(Object[].class)))
                .thenReturn(eventQuery);
        when(eventQuery.setParameter(anyString(), any())).thenReturn(eventQuery);
        when(eventQuery.getResultList()).thenReturn(eventRows);
        ReasonCodeReport report = new ReasonCodeReport();
        report.entityManager = entityManager;
        return report;
    }

    /**
     * Builds an {@link Employee} carrying the given badge.
     *
     * @param badgeId the badge identifier, possibly null
     * @return the populated employee
     */
    private Employee employee(String badgeId) {
        Employee employee = new Employee();
        employee.badgeId = badgeId;
        return employee;
    }

    /**
     * Verifies the report metadata: code, label, requirement, family and the
     * five declared columns.
     */
    @Test
    void metadataDescribesTheReport() {
        ReasonCodeReport report = new ReasonCodeReport();
        assertEquals("reason-codes", report.code());
        assertEquals("État des codes raison", report.label());
        assertEquals("BO-06-06-07", report.requirement());
        assertEquals(ReportFamily.CASHIERS, report.family());
        List<ReportColumn> columns = report.columns();
        assertEquals(5, columns.size());
        assertEquals("when", columns.get(0).key());
        assertEquals("terminal", columns.get(1).key());
        assertEquals("badge", columns.get(2).key());
        assertEquals("kind", columns.get(3).key());
        assertEquals("reason", columns.get(4).key());
    }

    /**
     * With one movement and one event, both fully populated and given out of
     * chronological order, {@code rows} enters both loops, takes the non-null
     * arm of every ternary, resolves the movement cashier badge through the
     * {@code instanceof}/non-null path and returns the two rows oldest first.
     */
    @Test
    void rowsMergesBothSourcesChronologically() {
        EntityManager entityManager = mock(EntityManager.class);
        LocalDateTime later = LocalDateTime.of(2026, 9, 1, 15, 0);
        LocalDateTime earlier = LocalDateTime.of(2026, 9, 1, 9, 0);
        List<Object[]> movements = List.<Object[]>of(new Object[]{
                later, "CAISSE1", employee("C001"), "WITHDRAWAL", "Retrait coffre"});
        List<Object[]> events = List.<Object[]>of(new Object[]{
                earlier, "CAISSE2", "C002",
                TechnicalEvent.EventType.SUPERVISOR_CALLED, "Appel superviseur"});
        ReasonCodeReport report = reportWith(entityManager, movements, events);
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(2, rows.size());
        assertEquals(earlier, rows.get(0).get(0));
        assertEquals("CAISSE2", rows.get(0).get(1));
        assertEquals("C002", rows.get(0).get(2));
        assertEquals("Événement — SUPERVISOR_CALLED", rows.get(0).get(3));
        assertEquals("Appel superviseur", rows.get(0).get(4));
        assertEquals(later, rows.get(1).get(0));
        assertEquals("CAISSE1", rows.get(1).get(1));
        assertEquals("C001", rows.get(1).get(2));
        assertEquals("Mouvement de caisse — WITHDRAWAL", rows.get(1).get(3));
        assertEquals("Retrait coffre", rows.get(1).get(4));
    }

    /**
     * A movement with a null terminal and a cashier that is not an
     * {@link Employee} (null) takes the null arm of the terminal ternary and
     * the {@code false} arm of the {@code instanceof} guard in {@code badge},
     * yielding empty texts; the event source is empty so its loop is skipped.
     */
    @Test
    void rowsMovementWithNullTerminalAndNonEmployeeCashier() {
        EntityManager entityManager = mock(EntityManager.class);
        LocalDateTime when = LocalDateTime.of(2026, 9, 2, 10, 0);
        List<Object[]> movements = List.<Object[]>of(new Object[]{
                when, null, null, "DEPOSIT", "Apport de fond"});
        ReasonCodeReport report = reportWith(entityManager, movements, List.of());
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        assertEquals(when, rows.get(0).get(0));
        assertEquals("", rows.get(0).get(1));
        assertEquals("", rows.get(0).get(2));
        assertEquals("Mouvement de caisse — DEPOSIT", rows.get(0).get(3));
        assertEquals("Apport de fond", rows.get(0).get(4));
    }

    /**
     * A movement whose cashier is an {@link Employee} with a null badge takes
     * the {@code true} arm of the {@code instanceof} guard and the null arm of
     * the {@code badgeId} ternary, yielding an empty badge text.
     */
    @Test
    void rowsMovementCashierWithNullBadge() {
        EntityManager entityManager = mock(EntityManager.class);
        LocalDateTime when = LocalDateTime.of(2026, 9, 3, 11, 30);
        List<Object[]> movements = List.<Object[]>of(new Object[]{
                when, "CAISSE3", employee(null), "WITHDRAWAL", "Retrait"});
        ReasonCodeReport report = reportWith(entityManager, movements, List.of());
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        assertEquals("CAISSE3", rows.get(0).get(1));
        assertEquals("", rows.get(0).get(2));
        assertEquals("Mouvement de caisse — WITHDRAWAL", rows.get(0).get(3));
        assertEquals("Retrait", rows.get(0).get(4));
    }

    /**
     * An event with a null terminal, a null operator badge and a null detail
     * takes the null arm of all three event ternaries, yielding empty texts;
     * the movement source is empty so its loop is skipped.
     */
    @Test
    void rowsEventWithNullTerminalBadgeAndDetail() {
        EntityManager entityManager = mock(EntityManager.class);
        LocalDateTime when = LocalDateTime.of(2026, 9, 4, 8, 15);
        List<Object[]> events = List.<Object[]>of(new Object[]{
                when, null, null, TechnicalEvent.EventType.ENDORSEMENT_DENIED, null});
        ReasonCodeReport report = reportWith(entityManager, List.of(), events);
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(1, rows.size());
        assertEquals(when, rows.get(0).get(0));
        assertEquals("", rows.get(0).get(1));
        assertEquals("", rows.get(0).get(2));
        assertEquals("Événement — ENDORSEMENT_DENIED", rows.get(0).get(3));
        assertEquals("", rows.get(0).get(4));
    }

    /**
     * With both sources empty, {@code rows} enters neither loop and returns an
     * empty list — the zero-sale, empty-grouping day.
     */
    @Test
    void rowsEmptyWhenNoJustifiedGesture() {
        EntityManager entityManager = mock(EntityManager.class);
        ReasonCodeReport report = reportWith(entityManager, List.of(), List.of());
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertTrue(rows.isEmpty());
    }
}
