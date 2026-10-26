package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.catalog.Promotion;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketFidelityLine;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.intermarche.edge.ui.report.QueryStub.row;
import static com.intermarche.edge.ui.report.QueryStub.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PromotionCashierReport}: the loyalty advantages and the
 * engine's offers fired per promotion and cashier, a ticket counted once in
 * the net sales of a promotion, the administered label and type, the type,
 * promotion and cashier selections, and the metadata.
 */
class PromotionCashierReportTest {

    /**
     * Builds a ticket.
     *
     * @param id the id
     * @param badge the cashier's badge, or null for no cashier
     * @param total the total, or null
     * @param advantages the loyalty advantages
     * @return the ticket
     */
    private Ticket ticket(long id, String badge, String total, TicketFidelityLine... advantages) {
        Ticket ticket = new Ticket();
        ticket.id = id;
        if (badge != null) {
            Employee cashier = new Employee();
            cashier.badgeId = "-".equals(badge) ? null : badge;
            cashier.firstName = "Jean";
            cashier.lastName = "Dupont";
            ticket.cashier = cashier;
        }
        ticket.totalIncludingTax = total == null ? null : new BigDecimal(total);
        ticket.fidelityLines = new ArrayList<>(List.of(advantages));
        return ticket;
    }

    /**
     * Runs the report, the promotion OFF1 administered in percent.
     *
     * @param stub the stub
     * @param criteria the edition parameters
     * @return the rows
     */
    @SuppressWarnings("unchecked")
    private List<List<Object>> run(QueryStub stub, ReportCriteria criteria) {
        PromotionCashierReport report = new PromotionCashierReport();
        report.entityManager = stub.entityManager;
        Promotion off1 = new Promotion();
        off1.code = "OFF1";
        off1.label = "Moins 20 %";
        off1.kind = Promotion.BY_PERCENT;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class))).thenAnswer(call -> {
                PanacheQuery<Object> query = mock(PanacheQuery.class);
                if ("OFF1".equals(call.getArguments()[1])) {
                    when(query.firstResult()).thenReturn(off1);
                }
                return query;
            });
            return report.rows(criteria);
        }
    }

    /**
     * The stub of the period: a loyalty advantage, two engine offers on one
     * ticket, an offer of a ticket without cashier, and an advantage without
     * number of a cashier without badge.
     *
     * @return the stub
     */
    private QueryStub stub() {
        return new QueryStub()
                .answer("from TicketLineValuation v", rows(
                        row(1L, "OFF1", "Remise", new BigDecimal("-2.00")),
                        row(1L, "OFF1", "Remise", new BigDecimal("1.00")),
                        row(2L, "OFFX", null, null)))
                .answer("from Ticket t", List.of(
                        ticket(1, "111", "50.00", new TicketFidelityLine("R1", "Cagnotte 2 €", new BigDecimal("2.00"))),
                        ticket(2, null, null),
                        ticket(3, "-", "10.00", new TicketFidelityLine(null, null, null))));
    }

    /**
     * Per promotion and cashier: the triggers, the net sales counted once a
     * ticket, the discount and its share; the administered label wins.
     */
    @Test
    void everyTriggerIsCountedUnderItsPromotion() {
        List<List<Object>> rows = run(stub(), new ReportCriteria());
        assertEquals(4, rows.size());
        List<Object> nameless = rows.get(0);
        assertEquals("", nameless.get(0));
        assertEquals("", nameless.get(2));
        assertEquals("Jean Dupont", nameless.get(3));
        assertEquals(new BigDecimal("10.00"), nameless.get(5));
        assertEquals(new BigDecimal("0.00"), nameless.get(7));
        List<Object> offx = rows.get(2);
        assertEquals("OFFX", offx.get(0));
        assertEquals("", offx.get(1));
        assertEquals("", offx.get(2));
        assertEquals("", offx.get(3));
        assertEquals(BigDecimal.ZERO, offx.get(5));
        assertEquals(BigDecimal.ZERO, offx.get(7));
        List<Object> off1 = rows.get(1);
        assertEquals("OFF1", off1.get(0));
        assertEquals("Moins 20 %", off1.get(1));
        assertEquals("111", off1.get(2));
        assertEquals(2L, off1.get(4));
        assertEquals(new BigDecimal("50.00"), off1.get(5));
        assertEquals(new BigDecimal("3.00"), off1.get(6));
        assertEquals(new BigDecimal("6.00"), off1.get(7));
        List<Object> r1 = rows.get(3);
        assertEquals("R1", r1.get(0));
        assertEquals("Cagnotte 2 €", r1.get(1));
        assertEquals(new BigDecimal("4.00"), r1.get(7));
    }

    /**
     * The type selection keeps the promotions of the type, the administered
     * one or the one read off the label; the promotion selection keeps the
     * ones picked; the cashier selection reaches the query.
     */
    @Test
    void theSelectionsRestrict() {
        ReportCriteria percent = new ReportCriteria();
        percent.option = Promotion.BY_PERCENT;
        List<List<Object>> rows = run(stub(), percent);
        assertEquals(1, rows.size());
        assertEquals("OFF1", rows.get(0).get(0));
        ReportCriteria value = new ReportCriteria();
        value.option = Promotion.BY_VALUE;
        assertEquals(3, run(stub(), value).size());
        ReportCriteria picked = new ReportCriteria();
        picked.picks.add("R1");
        picked.cashiers.add("111");
        QueryStub stub = stub();
        List<List<Object>> one = run(stub, picked);
        assertEquals(1, one.size());
        assertEquals("R1", one.get(0).get(0));
        assertTrue(stub.asked("t.cashier.badgeId in :cashierBadges"));
    }

    /**
     * The metadata: identity, columns, break, chart, options and filters; the
     * promotion choice lists the administered promotions.
     */
    @Test
    void theReportNamesItself() {
        PromotionCashierReport report = new PromotionCashierReport();
        assertEquals("promotion-cashiers", report.code());
        assertEquals("État des ventes promotionnelles", report.label());
        assertEquals("BO-06-08-01", report.requirement());
        assertEquals(ReportFamily.SALES, report.family());
        assertEquals(8, report.columns().size());
        assertEquals("promotion", report.breakKey());
        assertEquals(4, report.chartColumn());
        assertEquals(3, report.chartLabelColumn());
        assertEquals("Type de promotion", report.optionLabel());
        assertEquals(3, report.optionChoices().size());
        assertEquals("Promotions", report.pickLabel());
        assertEquals(java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.CASHIERS),
                report.filters());
        Promotion off1 = new Promotion();
        off1.code = "OFF1";
        off1.label = "Moins 20 %";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("order by code")).thenReturn(List.of(off1));
            assertEquals(List.of("OFF1:OFF1 — Moins 20 %"), report.pickChoices());
        }
    }
}
