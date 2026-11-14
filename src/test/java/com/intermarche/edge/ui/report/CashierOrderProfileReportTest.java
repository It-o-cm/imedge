package com.intermarche.edge.ui.report;

import com.intermarche.edge.service.PosSettingsService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CashierOrderProfileReport}: the express and normal
 * orders told apart by the administered threshold, the cashiers ranked by
 * cadence within a kind, the store's average of each kind as a subtotal, a
 * kind no cashier served, the ratios of a set of baskets and the metadata.
 */
class CashierOrderProfileReportTest {

    /**
     * Builds the report over closed tickets, express up to three articles.
     *
     * @param stub the query stub
     * @return the report
     */
    private CashierOrderProfileReport report(QueryStub stub) {
        CashierOrderProfileReport report = new CashierOrderProfileReport();
        report.entityManager = stub.entityManager;
        report.posSettingsService = mock(PosSettingsService.class);
        when(report.posSettingsService.reportExpressMaxItems()).thenReturn(3);
        return report;
    }

    /**
     * Per kind, the cashiers who served one, ranked by cadence, then the
     * store's average; the share is read against every order of the cashier.
     */
    @Test
    void theKindsAreRankedAndAveraged() {
        QueryStub stub = new QueryStub().answer("t.status = :closed", List.of(
                CashierPerformanceReportTest.ticket("111", "10.00", 2),
                CashierPerformanceReportTest.ticket("111", "50.00", 9),
                CashierPerformanceReportTest.ticket("222", "12.00", 3)));
        List<List<Object>> rows = report(stub).rows(new ReportCriteria());
        assertEquals(5, rows.size());
        assertEquals(CashierOrderProfileReport.EXPRESS, rows.get(0).get(2));
        assertEquals("222", rows.get(0).get(0));
        assertEquals(1L, rows.get(0).get(3));
        assertEquals(new BigDecimal("3.00"), rows.get(0).get(6));
        assertEquals("111", rows.get(1).get(0));
        assertEquals(2L, rows.get(1).get(3));
        assertEquals(new BigDecimal("50.0"), rows.get(1).get(5));
        assertTrue(ReportData.isSubtotal(rows.get(2)));
        assertEquals(new BigDecimal("66.7"), rows.get(2).get(5));
        assertEquals(CashierOrderProfileReport.NORMAL, rows.get(3).get(2));
        assertEquals(new BigDecimal("9.00"), rows.get(3).get(4));
        assertTrue(ReportData.isSubtotal(rows.get(4)));
        assertTrue(report(new QueryStub()).rows(new ReportCriteria()).isEmpty());
    }

    /**
     * The ratios of a set of baskets: coupons, cheques, food vouchers, the
     * times and the time left beyond scanning and payment; a share of no
     * order is zero.
     */
    @Test
    void theRatiosOfBaskets() {
        CashierActivity.Basket cheque = new CashierActivity.Basket();
        cheque.items = 2;
        cheque.coupons = 2;
        cheque.cheque = true;
        cheque.scanSeconds = 30;
        cheque.paySeconds = 20;
        cheque.customerSeconds = 80;
        CashierActivity.Basket voucher = new CashierActivity.Basket();
        voucher.foodVoucher = true;
        voucher.scanSeconds = 30;
        voucher.paySeconds = 40;
        voucher.customerSeconds = 10;
        List<Object> row = new CashierOrderProfileReport().row("1", "X", "K", List.of(cheque, voucher), 0);
        assertEquals(new BigDecimal("1.00"), row.get(4));
        assertEquals(BigDecimal.ZERO, row.get(5));
        assertEquals(new BigDecimal("2.00"), row.get(6));
        assertEquals(new BigDecimal("1.00"), row.get(7));
        assertEquals(new BigDecimal("50.0"), row.get(8));
        assertEquals(new BigDecimal("50.0"), row.get(9));
        assertEquals(new BigDecimal("30.00"), row.get(10));
        assertEquals(new BigDecimal("30.00"), row.get(11));
        assertEquals(BigDecimal.ZERO.setScale(2), row.get(12));
        assertEquals(new BigDecimal("45.00"), row.get(13));
        assertEquals(new BigDecimal("25.0"), CashierOrderProfileReport.percent(1, 4));
        assertEquals(List.of(cheque), CashierOrderProfileReport.of(List.of(cheque, voucher), 1, false));
    }

    /**
     * The metadata: identity, columns, chart and filters.
     */
    @Test
    void metadataDescribesTheReport() {
        CashierOrderProfileReport report = new CashierOrderProfileReport();
        assertEquals("cashier-orders", report.code());
        assertEquals("Profil des performances caissières — commandes express et normales", report.label());
        assertEquals("BO-06-06-02", report.requirement());
        assertEquals(ReportFamily.CASHIERS, report.family());
        assertEquals(14, report.columns().size());
        assertEquals(6, report.chartColumn());
        assertEquals(1, report.chartLabelColumn());
        assertEquals(java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.CASHIERS),
                report.filters());
    }
}
