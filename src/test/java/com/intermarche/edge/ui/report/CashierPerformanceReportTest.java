package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.Ticket;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CashierPerformanceReport}: one row per cashier then the
 * store's average flagged as a subtotal, an empty period, the ratios of a
 * cashier, the zero divisors and the metadata.
 */
class CashierPerformanceReportTest {

    /**
     * Builds a closed ticket of a cashier.
     *
     * @param badge the badge
     * @param total the total
     * @param items the articles
     * @return the ticket
     */
    static Ticket ticket(String badge, String total, int items) {
        Ticket ticket = new Ticket();
        Employee cashier = new Employee();
        cashier.badgeId = badge;
        cashier.firstName = "Caissière";
        cashier.lastName = badge;
        ticket.cashier = cashier;
        ticket.creationDate = LocalDateTime.of(2026, 10, 20, 10, 0);
        ticket.paymentStartedAt = ticket.creationDate.plusSeconds(60);
        ticket.closingDate = ticket.creationDate.plusSeconds(90);
        ticket.totalIncludingTax = new BigDecimal(total);
        ticket.itemCount = items;
        return ticket;
    }

    /**
     * One row per cashier, then the average of the store as a subtotal.
     */
    @Test
    void cashiersThenTheAverage() {
        CashierPerformanceReport report = new CashierPerformanceReport();
        report.entityManager = new QueryStub().answer("t.status = :closed",
                List.of(ticket("111", "30.00", 6), ticket("222", "10.00", 2), ticket("222", "20.00", 4)))
                .entityManager;
        List<List<Object>> rows = report.rows(new ReportCriteria());
        assertEquals(3, rows.size());
        List<Object> first = rows.get(0);
        assertEquals("111", first.get(0));
        assertEquals("Caissière 111", first.get(1));
        assertEquals(1L, first.get(6));
        assertEquals(new BigDecimal("30.00"), first.get(8));
        assertEquals(new BigDecimal("6.00"), first.get(11));
        assertEquals(new BigDecimal("6.00"), first.get(12));
        assertEquals("00:01:00", first.get(13));
        List<Object> mean = rows.get(2);
        assertTrue(ReportData.isSubtotal(mean));
        assertEquals("Moyenne magasin", mean.get(1));
        assertEquals(2L, mean.get(6));
        assertEquals("00:01:30", mean.get(13));
        report.entityManager = new QueryStub().entityManager;
        assertTrue(report.rows(new ReportCriteria()).isEmpty());
    }

    /**
     * A cashier without customer, article or active time reads zeros.
     */
    @Test
    void zeroDivisorsReadZero() {
        List<Object> row = new CashierPerformanceReport().row("1", "X", new CashierActivity.Figures());
        assertEquals(BigDecimal.ZERO, row.get(7));
        assertEquals(BigDecimal.ZERO, row.get(8));
        assertEquals(BigDecimal.ZERO, row.get(9));
        assertEquals(new BigDecimal("2.50"), CashierPerformanceReport.money(new BigDecimal("5"), 2));
    }

    /**
     * The metadata: identity, columns, chart and filters.
     */
    @Test
    void metadataDescribesTheReport() {
        CashierPerformanceReport report = new CashierPerformanceReport();
        assertEquals("cashier-performance", report.code());
        assertEquals("Profil des performances caissières", report.label());
        assertEquals("BO-06-06-02", report.requirement());
        assertEquals(ReportFamily.CASHIERS, report.family());
        assertEquals(14, report.columns().size());
        assertEquals(12, report.chartColumn());
        assertEquals(1, report.chartLabelColumn());
        assertEquals(java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.CASHIERS),
                report.filters());
    }
}
