package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.sale.Refund;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
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
 * Unit tests for {@link RefundReport}.
 * <p>
 * The report reads CLOSED refunds of the period through a single JPQL query on a
 * mocked {@link EntityManager}; the {@code terminals} filter conditionally
 * appends a clause and binds a parameter, and each result row maps its columns
 * through a chain of null-guarding ternaries. Every collaborator is a Mockito
 * mock, rows are fed as raw {@code Object[]} and assertions bear absolute
 * expected values. The tests cover both arms of the two {@code terminals.isEmpty()}
 * guards, the entered/empty arms of the row loop and the null/non-null arms of
 * every field ternary, plus the null-amount row and the empty-result day.
 */
class RefundReportTest {

    /**
     * Builds a {@link RefundReport} whose package-private {@link EntityManager}
     * field is wired to the given mock.
     *
     * @param entityManager the mocked entity manager
     * @return a report ready under test
     */
    private RefundReport reportWith(EntityManager entityManager) {
        RefundReport report = new RefundReport();
        report.entityManager = entityManager;
        return report;
    }

    /**
     * Creates a fresh {@link TypedQuery} mock, erasing the unchecked cast to a
     * single suppression point.
     *
     * @return a bare typed-query mock over {@code Object[]}
     */
    @SuppressWarnings("unchecked")
    private TypedQuery<Object[]> typedQueryMock() {
        return mock(TypedQuery.class);
    }

    /**
     * Wires the refund query on the given entity-manager mock so that it returns
     * the supplied rows and returns itself from any {@code setParameter} call.
     *
     * @param entityManager the mocked entity manager
     * @param query the typed-query mock to return
     * @param rows the pre-baked result rows
     */
    private void stubQuery(EntityManager entityManager, TypedQuery<Object[]> query, List<Object[]> rows) {
        when(entityManager.createQuery(contains("from Refund r"), eq(Object[].class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(rows);
    }

    /**
     * With a terminals filter and a fully-populated row, {@code rows()} appends
     * the {@code in :terminals} clause, binds the terminals parameter and maps
     * every column through the non-null arm of its ternary.
     */
    @Test
    void rowsWithTerminalsFilterAndFullRow() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = typedQueryMock();
        LocalDateTime when = LocalDateTime.of(2026, 9, 26, 10, 15);
        stubQuery(entityManager, query, Collections.singletonList(new Object[]{
                when, "CAISSE1", "C04-R000012", Refund.RefundMethod.CASH, 42L, 3, new BigDecimal("12.50"),
                "C123", "S900", "ECH", "Échange", "PERIME", "Article périmé"}));
        ReportCriteria criteria = new ReportCriteria();
        criteria.terminals.add("CAISSE1");
        List<List<Object>> rows = reportWith(entityManager).rows(criteria);
        assertEquals(1, rows.size());
        assertEquals(Arrays.asList(when, "CAISSE1", "C04-R000012", "C123", "S900", "ECH", "Échange",
                        "CASH", "PERIME", "Article périmé", "42", 3L, new BigDecimal("12.50")),
                rows.get(0));
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createQuery(jpql.capture(), eq(Object[].class));
        assertTrue(jpql.getValue().contains("and r.terminalId in :terminals"));
        verify(query).setParameter("terminals", criteria.terminals);
    }

    /**
     * With no terminals filter, {@code rows()} omits the {@code in :terminals}
     * clause and never binds the terminals parameter, taking the false arm of
     * both {@code terminals.isEmpty()} guards.
     */
    @Test
    void rowsWithoutTerminalsFilter() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = typedQueryMock();
        LocalDateTime when = LocalDateTime.of(2026, 9, 26, 8, 0);
        stubQuery(entityManager, query, Collections.singletonList(new Object[]{
                when, "CAISSE2", "C04-R000013", Refund.RefundMethod.VOUCHER, 7L, 1, new BigDecimal("4.00"),
                "C200", null, "SIMPLE", "Retour simple", null, null}));
        List<List<Object>> rows = reportWith(entityManager).rows(new ReportCriteria());
        assertEquals(1, rows.size());
        assertEquals(Arrays.asList(when, "CAISSE2", "C04-R000013", "C200", "", "SIMPLE", "Retour simple",
                        "VOUCHER", "", "", "7", 1L, new BigDecimal("4.00")),
                rows.get(0));
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createQuery(jpql.capture(), eq(Object[].class));
        assertFalse(jpql.getValue().contains("in :terminals"));
        verify(query, never()).setParameter(eq("terminals"), any());
    }

    /**
     * A row whose terminal, number, cashier, supervisor, return type, method,
     * reason, ticket and amount are all null takes the null arm of every field
     * ternary, blanking the texts and defaulting the amount to
     * {@link BigDecimal#ZERO} while the count still resolves.
     */
    @Test
    void rowsWithNullFields() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = typedQueryMock();
        LocalDateTime when = LocalDateTime.of(2026, 9, 26, 12, 30);
        stubQuery(entityManager, query, Collections.singletonList(new Object[]{
                when, null, null, null, null, 0, null, null, null, null, null, null, null}));
        List<List<Object>> rows = reportWith(entityManager).rows(new ReportCriteria());
        assertEquals(1, rows.size());
        assertEquals(Arrays.asList(when, "", "", "", "", "", "", "", "", "", "", 0L, BigDecimal.ZERO),
                rows.get(0));
    }

    /**
     * A day with no closed refund yields an empty result list, so the row loop is
     * never entered and {@code rows()} returns an empty list.
     */
    @Test
    void rowsEmptyResult() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = typedQueryMock();
        stubQuery(entityManager, query, List.of());
        List<List<Object>> rows = reportWith(entityManager).rows(new ReportCriteria());
        assertTrue(rows.isEmpty());
    }

    /**
     * The report's code is its stable technical identifier.
     */
    @Test
    void codeIsStable() {
        assertEquals("refunds", new RefundReport().code());
    }

    /**
     * The report's label is its French title.
     */
    @Test
    void labelIsFrenchTitle() {
        assertEquals("État remboursement", new RefundReport().label());
    }

    /**
     * The report answers its questionnaire requirement.
     */
    @Test
    void requirementIsBoCode() {
        assertEquals("BO-06-06-05", new RefundReport().requirement());
    }

    /**
     * The report belongs to the cashiers family.
     */
    @Test
    void familyIsCashiers() {
        assertEquals(ReportFamily.CASHIERS, new RefundReport().family());
    }

    /**
     * The report exposes the thirteen columns the requirement enumerates, in
     * order — the seven the original BO-06-06-05 asked for, and the six the
     * refund document of its own carries (who refunded it, under whose
     * supervision, of what type and for what reason).
     */
    @Test
    void columnsMatchRequirement() {
        List<ReportColumn> columns = new RefundReport().columns();
        assertEquals(13, columns.size());
        assertEquals("when", columns.get(0).key());
        assertEquals(ReportColumn.Kind.DATETIME, columns.get(0).kind());
        assertEquals("terminal", columns.get(1).key());
        assertEquals("number", columns.get(2).key());
        assertEquals("cashier", columns.get(3).key());
        assertEquals("supervisor", columns.get(4).key());
        assertEquals("typeCode", columns.get(5).key());
        assertEquals("typeLabel", columns.get(6).key());
        assertEquals("method", columns.get(7).key());
        assertEquals("reasonCode", columns.get(8).key());
        assertEquals("reasonLabel", columns.get(9).key());
        assertEquals("ticket", columns.get(10).key());
        assertEquals("lines", columns.get(11).key());
        assertEquals(ReportColumn.Kind.INTEGER, columns.get(11).kind());
        assertEquals("amount", columns.get(12).key());
        assertEquals(ReportColumn.Kind.AMOUNT, columns.get(12).kind());
    }
}
