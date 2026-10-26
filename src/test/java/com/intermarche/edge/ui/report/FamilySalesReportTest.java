package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.sale.SalesChannel;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static com.intermarche.edge.ui.report.QueryStub.row;
import static com.intermarche.edge.ui.report.QueryStub.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link FamilySalesReport}.
 * <p>
 * The report reads the sold lines, the coupons and the cancelled lines through
 * a {@link QueryStub}, and the rayons through a stubbed nomenclature. Branches
 * covered: a line outside the hours; a return line; an e-commerce line; the
 * listed price from the original price, from the unit price when the original
 * is null or zero; a discount above and below a cent; a ticket with a coupon,
 * without, and with a zero total; a family without code; a label kept, filled
 * later, absent; a cancellation inside and outside the hours; a family known
 * only by its cancellations (no customer, zero shares); the null decimals.
 */
class FamilySalesReportTest {

    /** A moment inside the period. */
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 20, 10, 0);

    /**
     * Builds a sold line.
     *
     * @param ticket the ticket id
     * @param when the closing moment
     * @param channel the channel
     * @param family the family code
     * @param label the family label
     * @param total the line total
     * @param quantity the quantity
     * @param unit the unit price
     * @param original the original unit price
     * @param returned whether it is a return line
     * @return the line
     */
    private Object[] line(long ticket, LocalDateTime when, SalesChannel channel, String family, String label,
                          String total, String quantity, String unit, String original, boolean returned) {
        return row(ticket, when, channel, family, label, total == null ? null : new BigDecimal(total),
                new BigDecimal(quantity), new BigDecimal(unit), original == null ? null : new BigDecimal(original),
                returned, new BigDecimal("0.20"));
    }

    /**
     * Runs the report over the stub, the nomenclature labelling its nodes.
     *
     * @param stub the query stub
     * @param criteria the edition parameters
     * @return the rows
     */
    private List<List<Object>> run(QueryStub stub, ReportCriteria criteria) {
        FamilySalesReport report = new FamilySalesReport();
        report.entityManager = stub.entityManager;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            NomenclatureFixture.scheme(panache, Map.of("1002", "BOUCHERIE", "2020", "CREMERIE"));
            return report.rows(criteria);
        }
    }

    /**
     * The figures of a family: sales, returns, discounts, gross sales, coupons
     * apportioned, e-commerce, customers and the shares read against the rayon.
     */
    @Test
    void theFamilyFiguresAreReadAgainstTheirRayon() {
        QueryStub stub = new QueryStub()
                .answer("type(p) = :voucher", rows(row(1L, new BigDecimal("12.00"), new BigDecimal("120.00")),
                        row(3L, null, BigDecimal.ZERO)))
                .answer("l.cancelled = false", rows(
                        line(1, AT, SalesChannel.STORE, "10020200", "Bovin", "100.00", "2", "60.00", null, false),
                        line(1, AT, SalesChannel.STORE, "10020200", null, "20.00", "1", "20.00", "0", false),
                        line(2, AT, SalesChannel.ECOMMERCE, "10020400", "Porc", "50.00", "1", "50.00", "60.00", false),
                        line(2, AT, SalesChannel.STORE, "10020400", "Porc", "-10.00", "-1", "10.00", null, true),
                        line(3, AT, SalesChannel.STORE, "10020400", "Porc", "30.00", "1", "30.00", "30.004", false)))
                .answer("l.cancelled = true", rows());
        List<List<Object>> rows = run(stub, new ReportCriteria());
        assertEquals(2, rows.size());
        List<Object> bovin = rows.get(0);
        assertEquals("1002", bovin.get(0));
        assertEquals("BOUCHERIE", bovin.get(1));
        assertEquals("10020200", bovin.get(2));
        assertEquals("Bovin", bovin.get(3));
        assertEquals(new BigDecimal("120.00"), bovin.get(4));
        assertEquals(new BigDecimal("63.2"), bovin.get(5));
        assertEquals(2L, bovin.get(6));
        assertEquals(new BigDecimal("120.00"), bovin.get(7));
        assertEquals(new BigDecimal("20.00"), bovin.get(8));
        assertEquals(1L, bovin.get(9));
        assertEquals(new BigDecimal("120.00"), bovin.get(10));
        assertEquals(BigDecimal.ZERO, bovin.get(11));
        assertEquals(0L, bovin.get(12));
        assertEquals(new BigDecimal("140.00"), bovin.get(15));
        assertEquals(new BigDecimal("12.00"), bovin.get(16));
        assertEquals(new BigDecimal("10.00"), bovin.get(17));
        assertEquals(BigDecimal.ZERO, bovin.get(18));
        assertEquals(1L, bovin.get(19));
        assertEquals(new BigDecimal("33.3"), bovin.get(20));
        List<Object> porc = rows.get(1);
        assertEquals("Porc", porc.get(3));
        assertEquals(new BigDecimal("70.00"), porc.get(4));
        assertEquals(new BigDecimal("10.00"), porc.get(11));
        assertEquals(1L, porc.get(12));
        assertEquals(new BigDecimal("10.00"), porc.get(8));
        assertEquals(new BigDecimal("50.00"), porc.get(18));
        assertEquals(2L, porc.get(19));
        assertEquals(new BigDecimal("35.00"), porc.get(7));
        assertEquals(BigDecimal.ZERO, porc.get(16));
    }

    /**
     * The hours keep only their lines and cancellations; a family known by its
     * cancellations alone has no customer and zero shares; a line without
     * family is filed under a dash, its rayon a dash too.
     */
    @Test
    void hoursCancellationsAndUnknownFamilies() {
        QueryStub stub = new QueryStub()
                .answer("l.cancelled = false", rows(
                        line(1, AT, SalesChannel.STORE, null, null, null, "1", "5.00", null, false),
                        line(2, AT.withHour(20), SalesChannel.STORE, "20200200", "Lait", "5.00", "1", "5.00", null, false)))
                .answer("l.cancelled = true", rows(row("20200200", "Lait", AT, new BigDecimal("3.00")),
                        row("20200200", "Lait", AT.withHour(21), new BigDecimal("4.00")),
                        row("20220000", "Surgelés", AT, null)));
        ReportCriteria criteria = new ReportCriteria();
        criteria.hourTo = LocalTime.of(19, 0);
        List<List<Object>> rows = run(stub, criteria);
        assertEquals(3, rows.size());
        assertEquals("2020", rows.get(0).get(0));
        assertEquals("CREMERIE", rows.get(0).get(1));
        assertEquals(1L, rows.get(0).get(14));
        assertEquals(new BigDecimal("3.00"), rows.get(0).get(13));
        assertEquals(0L, rows.get(0).get(19));
        assertEquals(BigDecimal.ZERO, rows.get(0).get(7));
        assertEquals(BigDecimal.ZERO, rows.get(0).get(5));
        assertEquals(BigDecimal.ZERO, rows.get(0).get(20));
        assertEquals("2022", rows.get(1).get(0));
        assertEquals("", rows.get(1).get(1));
        assertEquals(BigDecimal.ZERO, rows.get(1).get(13));
        assertEquals("—", rows.get(2).get(0));
        assertEquals("—", rows.get(2).get(2));
        assertEquals("", rows.get(2).get(3));
        assertEquals(1L, rows.get(2).get(19));
        assertTrue(stub.asked("t.closingDate < :to and l.cancelled = false"));
    }

    /**
     * The metadata: code, title, requirement, family, chart, break and filters,
     * and the twenty-one columns of the requirement.
     */
    @Test
    void metadataIsConstant() {
        FamilySalesReport report = new FamilySalesReport();
        assertEquals("family-sales", report.code());
        assertEquals("État ventes par famille", report.label());
        assertEquals("BO-06-04-01", report.requirement());
        assertEquals(ReportFamily.SALES, report.family());
        assertEquals(4, report.chartColumn());
        assertEquals(3, report.chartLabelColumn());
        assertEquals("rayon", report.breakKey());
        assertEquals(java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.HOURS,
                ReportFilter.ARTICLES), report.filters());
        assertEquals(21, report.columns().size());
        assertEquals("% clients du rayon", report.columns().get(20).label());
    }
}
