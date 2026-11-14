package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.payment.CardPayment;
import com.intermarche.edge.domain.payment.CashPayment;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.sale.Ticket;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link CashierWeeklyReport}: the period cut into weeks, the
 * first and last ones partial, each cashier's weeks grouped, the payments'
 * synthesis named by the administered tenders, and the metadata.
 */
class CashierWeeklyReportTest {

    /**
     * Every week of the period, partial at both ends, per cashier in name
     * order, the payments summed up in one cell.
     */
    @Test
    void everyWeekOfEveryCashier() {
        Ticket paid = CashierPerformanceReportTest.ticket("222", "30.00", 6);
        paid.payments = List.<TicketPayment>of(new CardPayment(new BigDecimal("20.00")),
                new CashPayment(new BigDecimal("10.00"), new BigDecimal("10.00")));
        CashierWeeklyReport report = new CashierWeeklyReport();
        report.entityManager = new QueryStub().answer("t.status = :closed",
                List.of(paid, CashierPerformanceReportTest.ticket("111", "10.00", 2))).entityManager;
        ReportCriteria criteria = new ReportCriteria();
        criteria.period = ReportPeriod.RANGE;
        criteria.rangeFrom = LocalDate.of(2026, 10, 14);
        criteria.rangeTo = LocalDate.of(2026, 10, 20);
        List<List<Object>> rows;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            CashierStateReportTest.tenders(panache);
            rows = report.rows(criteria);
        }
        assertEquals(4, rows.size());
        assertEquals("111", rows.get(0).get(0));
        assertEquals("14/10/2026 – 18/10/2026", rows.get(0).get(2));
        assertEquals("19/10/2026 – 20/10/2026", rows.get(1).get(2));
        assertEquals("", rows.get(0).get(10));
        List<Object> week = rows.get(2);
        assertEquals("222", week.get(0));
        assertEquals(1L, week.get(3));
        assertEquals(new BigDecimal("30.00"), week.get(4));
        assertEquals(6L, week.get(5));
        assertEquals(new BigDecimal("6.00"), week.get(6));
        assertEquals(new BigDecimal("30.00"), week.get(8));
        String payments = (String) week.get(10);
        assertTrue(payments.startsWith("Carte bancaire 20,00"), payments);
        assertTrue(payments.contains(" ; Espèces 10,00"), payments);
        assertEquals(BigDecimal.ZERO, week.get(11));
        criteria.language = "nl";
        com.intermarche.edge.ui.admin.BackOfficeMessages dutch = com.intermarche.edge.ui.admin.BackOfficeMessages.in("nl");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            CashierStateReportTest.tenders(panache);
            String dutchPayments = (String) report.rows(criteria).get(2).get(10);
            assertTrue(dutchPayments.startsWith(dutch.text("Carte bancaire") + " 20,00"), dutchPayments);
            assertTrue(dutchPayments.contains(" ; " + dutch.text("Espèces") + " 10,00"), dutchPayments);
            assertFalse(dutchPayments.contains("Espèces"), dutchPayments);
        }
    }

    /**
     * The metadata: identity, columns, chart, break, signature and filters.
     */
    @Test
    void metadataDescribesTheReport() {
        CashierWeeklyReport report = new CashierWeeklyReport();
        assertEquals("cashier-weekly", report.code());
        assertEquals("Synthèse hebdomadaire caissière", report.label());
        assertEquals("BO-06-06-08", report.requirement());
        assertEquals(ReportFamily.CASHIERS, report.family());
        assertEquals(12, report.columns().size());
        assertEquals(6, report.chartColumn());
        assertEquals(2, report.chartLabelColumn());
        assertEquals("name", report.breakKey());
        assertEquals("Signature de la caissière", report.signature());
        assertEquals(java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.CASHIERS),
                report.filters());
    }
}
