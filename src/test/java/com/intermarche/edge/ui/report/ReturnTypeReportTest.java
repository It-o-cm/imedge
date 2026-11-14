package com.intermarche.edge.ui.report;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReturnTypeReport}, the returns by type
 * (BO-03-13-01, BO-03-13-12).
 *
 * <p>Branch enumeration: both arms of the terminals filter, the null and
 * non-null arms of every rendered field, of the article label and of the
 * amount.
 */
class ReturnTypeReportTest {

    /** The refund query. */
    private TypedQuery<Object[]> refunds;

    /** The return-line query. */
    private TypedQuery<Object[]> lines;

    /**
     * Builds a report whose two queries answer the given rows.
     *
     * @param refundRows the refund rows
     * @param lineRows the return-line rows
     * @return the report
     */
    @SuppressWarnings("unchecked")
    private ReturnTypeReport report(List<Object[]> refundRows, List<Object[]> lineRows) {
        EntityManager entityManager = mock(EntityManager.class);
        refunds = mock(TypedQuery.class);
        lines = mock(TypedQuery.class);
        when(entityManager.createQuery(contains("from Refund r"), eq(Object[].class))).thenReturn(refunds);
        when(entityManager.createQuery(contains("from Ticket t"), eq(Object[].class))).thenReturn(lines);
        when(refunds.setParameter(anyString(), any())).thenReturn(refunds);
        when(lines.setParameter(anyString(), any())).thenReturn(lines);
        when(refunds.getResultList()).thenReturn(refundRows);
        when(lines.getResultList()).thenReturn(lineRows);
        ReturnTypeReport report = new ReturnTypeReport();
        report.entityManager = entityManager;
        return report;
    }

    /**
     * The report states what it is.
     */
    @Test
    void theReportDescribesItself() {
        ReturnTypeReport report = new ReturnTypeReport();
        assertEquals("returns-by-type", report.code());
        assertEquals("Retours par type", report.label());
        assertEquals("BO-03-13-12", report.requirement());
        assertEquals(ReportFamily.CASHIERS, report.family());
        assertEquals(8, report.columns().size());
    }

    /**
     * Refunds and return lines are listed together, oldest first, under the
     * terminals filter.
     */
    @Test
    void refundsAndReturnLinesAreListedByType() {
        LocalDateTime t0 = LocalDateTime.of(2026, 10, 25, 9, 0);
        ReturnTypeReport report = report(
                List.<Object[]>of(new Object[] {t0.plusHours(2), "C04", "R-1", "ECH", "Échange", new BigDecimal("12.00")}),
                List.<Object[]>of(
                        new Object[] {t0.plusHours(1), "C05", "C05-1", "TRI", "Machine de tri",
                                new BigDecimal("-3.50"), "BOUTEILLE"},
                        new Object[] {t0, null, null, "TRI", null, null, null}));
        ReportCriteria criteria = new ReportCriteria();
        criteria.terminals.add("C04");
        List<List<Object>> rows = report.rows(criteria);
        assertEquals(Arrays.asList(t0, "", "", "Retour en vente", "TRI", "", "", BigDecimal.ZERO), rows.get(0));
        assertEquals(Arrays.asList(t0.plusHours(1), "C05", "C05-1", "Retour en vente", "TRI", "Machine de tri",
                "BOUTEILLE", new BigDecimal("3.50")), rows.get(1));
        assertEquals(Arrays.asList(t0.plusHours(2), "C04", "R-1", "Ticket de retour", "ECH", "Échange",
                "", new BigDecimal("12.00")), rows.get(2));
        verify(refunds).setParameter("terminals", criteria.terminals);
        verify(lines).setParameter("terminals", criteria.terminals);
    }

    /**
     * Without a terminals filter, neither query takes one.
     */
    @Test
    void noTerminalsFilter() {
        ReturnTypeReport report = report(List.of(), List.of());
        assertEquals(0, report.rows(new ReportCriteria()).size());
        verify(refunds, never()).setParameter(eq("terminals"), any());
        verify(lines, never()).setParameter(eq("terminals"), any());
    }
}
