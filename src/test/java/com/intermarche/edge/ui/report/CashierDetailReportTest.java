package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.people.Employee;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
 * Unit tests for {@link CashierDetailReport} (BO-09-01-10): the detailed
 * cashier state lists every closed ticket, cashier by cashier, restricted to
 * the cashiers selected, a ticket without cashier, register or amount stated
 * with its defaults.
 */
class CashierDetailReportTest {

    /**
     * A report over a mocked query answering the given rows.
     *
     * @param rows the rows
     * @return the report
     */
    @SuppressWarnings("unchecked")
    private CashierDetailReport report(List<Object[]> rows) {
        CashierDetailReport report = new CashierDetailReport();
        report.entityManager = mock(EntityManager.class);
        TypedQuery<Object[]> query = mock(TypedQuery.class);
        when(report.entityManager.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(rows);
        return report;
    }

    /**
     * The report's identity, family and columns.
     */
    @Test
    void theReportIsIdentified() {
        CashierDetailReport report = new CashierDetailReport();
        assertEquals("cashiers-detail", report.code());
        assertEquals("État caissière détaillé", report.label());
        assertEquals("BO-09-01-10", report.requirement());
        assertEquals(ReportFamily.CASHIERS, report.family());
        assertEquals(7, report.columns().size());
    }

    /**
     * Each ticket is a row; a cashier without badge, no cashier, no register
     * and no amount read their defaults; the cashiers selected restrict the
     * query.
     */
    @Test
    void everyTicketIsARow() {
        Employee martin = new Employee();
        martin.badgeId = "0001";
        martin.firstName = "Paul";
        martin.lastName = "Martin";
        Employee nameless = new Employee();
        nameless.firstName = "Ana";
        nameless.lastName = "Silva";
        LocalDateTime at = LocalDateTime.of(2026, 10, 24, 18, 0);
        CashierDetailReport report = report(List.of(
                new Object[]{martin, "C04", "C04-1", at, 3, new BigDecimal("9.90")},
                new Object[]{nameless, null, "C04-2", at, 1, null},
                new Object[]{null, "C05", "C05-1", at, 2L, new BigDecimal("1.00")}));
        ReportCriteria criteria = new ReportCriteria();
        criteria.cashiers.add("0001");
        List<List<Object>> rows = report.rows(criteria);
        assertEquals(List.of("0001", "Paul Martin", "C04", "C04-1", at, 3L, new BigDecimal("9.90")), rows.get(0));
        assertEquals(List.of("—", "Ana Silva", "", "C04-2", at, 1L, BigDecimal.ZERO), rows.get(1));
        assertEquals(List.of("—", "", "C05", "C05-1", at, 2L, new BigDecimal("1.00")), rows.get(2));
        verify(report.entityManager).createQuery(contains("c.badgeId in :badges"), eq(Object[].class));
        CashierDetailReport everyone = report(List.of());
        everyone.rows(new ReportCriteria());
        verify(everyone.entityManager, never()).createQuery(contains("c.badgeId in :badges"), eq(Object[].class));
    }
}
