package com.intermarche.edge.service;

import com.intermarche.edge.domain.sale.*;
import com.intermarche.edge.domain.session.*;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.ui.PriceModType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** LC-12-07-02: persisted scope, mixed returns, operation counts and configured presentation. */
class RevenueReportServiceTest {
    /** Builds a signed fiscal line; quantities distinguish zero-value returns from free sales.
     * @param amount signed line total
     * @param quantity signed quantity
     * @return persisted line fixture
     */
    private TicketLine line(String amount, String quantity) {
        var line = new TicketLine();
        line.totalPrice = new BigDecimal(amount);
        line.quantity = new BigDecimal(quantity);
        return line;
    }

    /** Builds one persisted ticket in a given state.
     * @param status lifecycle state
     * @param amount signed fiscal amount
     * @param lines recorded lines
     * @return ticket fixture
     */
    private Ticket ticket(Ticket.TicketStatus status, String amount, TicketLine... lines) {
        var ticket = new Ticket();
        ticket.status = status;
        ticket.totalIncludingTax = new BigDecimal(amount);
        ticket.lines = List.of(lines);
        return ticket;
    }

    /** Builds an applied payment, excluding tendered cash and change by construction.
     * @param key persisted tender code
     * @param amount signed applied amount
     * @return settlement fixture
     */
    private TicketPayment payment(String key, String amount) {
        var payment = mock(TicketPayment.class);
        when(payment.getMethodKey()).thenReturn(key);
        payment.amount = new BigDecimal(amount);
        return payment;
    }

    /** Finds a rubric and verifies both count and rounded amount.
     * @param rows report data
     * @param key rubric suffix
     * @param count expected operation count
     * @param amount expected TTC amount
     */
    private void assertRow(List<RevenueReportService.Row> rows, String key, int count, String amount) {
        var row = rows.stream().filter(r -> r.key().equals("report.section." + key)).findFirst().orElseThrow();
        assertEquals(count, row.count());
        assertEquals(amount, row.amountFormatted());
    }

    /** LC-12-07-02: mixed and pure returns, free sales, all movements and every refund tender remain distinct. */
    @Test
    void computesEveryRubricWithoutDoubleCountingMixedSales() {
        var service = new RevenueReportService();
        service.settings = mock(PosSettingsService.class);
        when(service.settings.reportSectionVisible(anyString())).thenReturn(true);
        var session = new CashSession();
        var forced = line("20.00", "2");
        forced.modifierType = PriceModType.FORCE_PRICE;
        var cancelled = line("999.00", "1");
        cancelled.cancelled = true;
        var mixed = ticket(Ticket.TicketStatus.CLOSED, "15.00", forced, line("-5.00", "-1"), cancelled);
        mixed.payments = List.of(payment("CASH", "10.00"), payment("CARD", "5.00"));
        var forcedReturn = line("-4.00", "-1");
        forcedReturn.modifierType = PriceModType.FORCE_PRICE;
        var returned = ticket(Ticket.TicketStatus.CLOSED, "-4.00", forcedReturn);
        returned.payments = List.of(payment("CASH", "-4.00"));
        var legacy = ticket(Ticket.TicketStatus.CLOSED, "3.00");
        legacy.payments = List.of(payment("CASH", "3.00"));
        var tickets = List.of(mixed, returned, legacy,
                ticket(Ticket.TicketStatus.CLOSED, "0.00", line("0.00", "1")),
                ticket(Ticket.TicketStatus.CLOSED, "0.00", line("0.00", "-1")),
                ticket(Ticket.TicketStatus.CLOSED, "-2.00"),
                ticket(Ticket.TicketStatus.CANCELLED, "7.00"),
                ticket(Ticket.TicketStatus.OPEN, "100.00"),
                ticket(Ticket.TicketStatus.PARKED, "100.00"));
        var refunds = new ArrayList<Refund>();
        int value = 1;
        for (var method : Refund.RefundMethod.values()) {
            var refund = new Refund();
            refund.refundMethod = method;
            refund.totalAmount = BigDecimal.valueOf(value++);
            refunds.add(refund);
        }
        var legacyRefund = new Refund();
        legacyRefund.totalAmount = new BigDecimal("6.00");
        refunds.add(legacyRefund);
        var movements = new ArrayList<CashMovement>();
        for (var type : CashMovement.MovementType.values()) {
            var movement = new CashMovement();
            movement.type = type;
            movement.amount = new BigDecimal("10.005");
            movements.add(movement);
        }
        var absentAmount = new CashMovement();
        absentAmount.type = CashMovement.MovementType.EXPENSE;
        movements.add(absentAmount);
        try (var entities = mockStatic(PanacheEntityBase.class)) {
            entities.when(() -> Ticket.list("session = ?1 and cashier.id = ?2 order by id", session, 7L)).thenReturn(tickets);
            entities.when(() -> Refund.list("session = ?1 and status = ?2 and cashierBadgeId in (select e.badgeId from Employee e where e.id = ?3)", session, Refund.RefundStatus.CLOSED, 7L)).thenReturn(refunds);
            entities.when(() -> TicketLine.list("select l from Ticket t join t.lines l where t.session = ?1 and l.cancelled = true and (l.cancelledBy in (select e.badgeId from Employee e where e.id = ?2) or (l.cancelledBy is null and t.cashier.id = ?2))", session, 7L)).thenReturn(List.of(line("-8.00", "-2")));
            entities.when(() -> CashMovement.list("session = ?1 and cashier.id = ?2", session, 7L)).thenReturn(movements);
            var rows = service.rows(session, 7L);
            assertRow(rows, "sales", 3, "23,00");
            assertRow(rows, "returns", 10, "32,00");
            assertRow(rows, "cancellations", 1, "8,00");
            assertRow(rows, "abandoned", 1, "7,00");
            assertRow(rows, "price-overrides", 2, "24,00");
            for (String key : List.of("expenses", "deposits", "withdrawals")) assertRow(rows, key, 1, "10,01");
            var cash = rows.stream().filter(r -> r.tender().equals("CASH")).findFirst().orElseThrow();
            assertEquals(4, cash.count());
            assertEquals("8,00", cash.amountFormatted());
            assertEquals(13, rows.size());
            assertTrue(service.rows(session, 99L).stream().allMatch(r -> r.count() == 0));
        }
    }

    /** LC-12-07-02: register scope has no employee filter; hiding every rubric also hides the tender detail. */
    @Test
    void registerScopeAndHiddenRubricsUseNoOperatorFilter() {
        var service = new RevenueReportService();
        service.settings = mock(PosSettingsService.class);
        var session = new CashSession();
        try (var entities = mockStatic(PanacheEntityBase.class)) {
            entities.when(() -> Ticket.list("session = ?1 order by id", session)).thenReturn(List.of());
            entities.when(() -> Refund.list("session = ?1 and status = ?2", session, Refund.RefundStatus.CLOSED)).thenReturn(List.of());
            entities.when(() -> TicketLine.list("select l from Ticket t join t.lines l where t.session = ?1 and l.cancelled = true", session)).thenReturn(List.of());
            entities.when(() -> CashMovement.list("session = ?1", session)).thenReturn(List.of());
            assertTrue(service.rows(session, null).isEmpty());
            entities.verify(() -> Ticket.list("session = ?1 order by id", session));
            entities.verify(() -> Refund.list("session = ?1 and status = ?2", session, Refund.RefundStatus.CLOSED));
            when(service.settings.reportSectionVisible("sales")).thenReturn(true);
            var rows = service.rows(session, null);
            assertEquals(1, rows.size());
            assertRow(rows, "sales", 0, "0,00");
        }
    }
}
