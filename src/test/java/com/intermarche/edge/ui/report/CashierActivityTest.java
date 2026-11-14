package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.payment.CardPayment;
import com.intermarche.edge.domain.payment.ChequePayment;
import com.intermarche.edge.domain.payment.TicketRestoPayment;
import com.intermarche.edge.domain.payment.TenderDefinition;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.payment.VoucherPayment;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.SalesChannel;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.session.CashMovement;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.ui.PriceModType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CashierActivity}: the seven readings of a period, the
 * cashier selection reaching the queries, the closed tickets and their lines,
 * the cancellations, the refunds, the sessions cut to the period, the cash
 * movements, the journaled events and the active intervals, the global recap
 * and the helpers.
 */
class CashierActivityTest {

    /** The day of the period, long past so that « now » is always later. */
    private static final LocalDate DAY = LocalDate.of(2020, 1, 10);

    /** Ten o'clock of the day. */
    private static final LocalDateTime AT = DAY.atTime(10, 0);

    /**
     * Builds the criteria of the day.
     *
     * @param badges the cashiers chosen
     * @return the criteria
     */
    private ReportCriteria day(String... badges) {
        ReportCriteria criteria = new ReportCriteria();
        criteria.period = ReportPeriod.RANGE;
        criteria.rangeFrom = DAY;
        criteria.rangeTo = DAY;
        criteria.cashiers.addAll(List.of(badges));
        return criteria;
    }

    /**
     * Builds an employee.
     *
     * @param badge the badge
     * @param first the first name, possibly null
     * @param last the last name, possibly null
     * @return the employee
     */
    private Employee employee(String badge, String first, String last) {
        Employee employee = new Employee();
        employee.badgeId = badge;
        employee.firstName = first;
        employee.lastName = last;
        return employee;
    }

    /**
     * Builds a sold line.
     *
     * @param total the total, possibly null
     * @param rate the VAT rate, possibly null
     * @return the line
     */
    private TicketLine line(String total, String rate) {
        TicketLine line = new TicketLine();
        line.totalPrice = total == null ? null : new BigDecimal(total);
        line.vatRate = rate == null ? null : new BigDecimal(rate);
        line.quantity = BigDecimal.ONE;
        line.unitPrice = line.totalPrice;
        return line;
    }

    /**
     * Builds a timed event.
     *
     * @param type the type
     * @param terminal the register
     * @param badge the operator's badge, possibly null
     * @param when the moment
     * @param detail the detail, possibly null
     * @return the event
     */
    private TechnicalEvent event(TechnicalEvent.EventType type, String terminal, String badge, LocalDateTime when,
                                 String detail) {
        TechnicalEvent event = new TechnicalEvent();
        event.eventType = type;
        event.terminalId = terminal;
        event.operatorBadgeId = badge;
        event.eventDate = when;
        event.detail = detail;
        return event;
    }

    /**
     * The seven readings are issued, bound on the period; the cashier
     * selection reaches only the ticket queries.
     */
    @Test
    void theReadingsAreIssued() {
        QueryStub stub = new QueryStub();
        CashierActivity activity = CashierActivity.load(stub.entityManager, day());
        assertTrue(activity.cashiers().isEmpty());
        assertEquals(7, stub.issued.size());
        assertFalse(stub.parameters.containsKey("cashierBadges"));
        assertEquals(DAY.atStartOfDay(), stub.parameters.get("from"));
        assertEquals(Ticket.TicketStatus.CANCELLED, stub.parameters.get("cancelled"));
        QueryStub chosen = new QueryStub();
        CashierActivity.load(chosen.entityManager, day("111"));
        assertEquals(java.util.Set.of("111"), chosen.parameters.get("cashierBadges"));
        assertTrue(chosen.asked("t.cashier.badgeId in :cashierBadges order by t.closingDate"));
    }

    /**
     * A closed ticket: its basket and times, its lines — cancelled, returned,
     * money product, exempt, taxed, untaxed, keyed, scanned, forced, discounted,
     * at its price — its channel, its global discount and its tenders.
     */
    @Test
    void aClosedTicketIsMeasured() {
        Ticket ticket = new Ticket();
        ticket.cashier = employee("111", "Jean", "Dupont");
        ticket.creationDate = AT;
        ticket.paymentStartedAt = AT.plusSeconds(90);
        ticket.closingDate = AT.plusSeconds(120);
        ticket.totalIncludingTax = new BigDecimal("50.00");
        ticket.itemCount = 6;
        ticket.salesChannel = SalesChannel.ECOMMERCE;
        ticket.globalDiscountApplied = new BigDecimal("-1.50");
        TicketLine cancelled = line("99.00", "0.2");
        cancelled.cancelled = true;
        TicketLine returned = line("-12.00", "0.2");
        returned.returnLine = true;
        returned.quantity = new BigDecimal("-2");
        TicketLine gift = line("20.00", "0");
        gift.moneyProduct = true;
        gift.vatExempt = true;
        TicketLine forced = line("8.00", "0.2");
        forced.scanned = true;
        forced.originalUnitPrice = new BigDecimal("10.00");
        forced.modifierType = PriceModType.FORCE_PRICE;
        TicketLine discounted = line("9.00", "0.055");
        discounted.scanned = true;
        discounted.originalUnitPrice = BigDecimal.ZERO;
        discounted.unitPrice = new BigDecimal("10.00");
        TicketLine plain = line(null, null);
        plain.scanned = true;
        plain.unitPrice = null;
        plain.quantity = null;
        ticket.lines = List.of(cancelled, returned, gift, forced, discounted, plain);
        ticket.payments = List.<TicketPayment>of(new VoucherPayment(new BigDecimal("5.00"), "Bon", "1"),
                new VoucherPayment(null, "Bon", "2"), new ChequePayment(new BigDecimal("20.00")),
                new TicketRestoPayment(new BigDecimal("10.00")), new CardPayment(new BigDecimal("15.00")));
        CashierActivity activity = new CashierActivity(day());
        activity.addTickets(List.of(ticket));
        CashierActivity.Figures figures = activity.cashiers().get(0);
        assertEquals("Jean Dupont", figures.name());
        CashierActivity.Basket basket = figures.baskets.get(0);
        assertEquals(6, basket.items);
        assertEquals(120, basket.customerSeconds);
        assertEquals(90, basket.scanSeconds);
        assertEquals(30, basket.paySeconds);
        assertEquals(2, basket.coupons);
        assertTrue(basket.cheque);
        assertTrue(basket.foodVoucher);
        assertEquals(ticket.closingDate, basket.closedAt);
        assertEquals(1, figures.returnCount);
        assertEquals(2, figures.returnArticles);
        assertEquals(new BigDecimal("12.00"), figures.returns);
        assertEquals(new BigDecimal("2.00"), figures.returnVat);
        assertEquals(new BigDecimal("37.00"), figures.sales);
        assertEquals(new BigDecimal("20.00"), figures.nonMerchandise);
        assertEquals(1, figures.exemptCount);
        assertEquals(new BigDecimal("20.00"), figures.exempt);
        assertEquals(new BigDecimal("17.00"), figures.taxable);
        assertEquals(1, figures.keyedCount);
        assertEquals(new BigDecimal("20.00"), figures.keyed);
        assertEquals(3, figures.scannedCount);
        assertEquals(new BigDecimal("17.00"), figures.scanned);
        assertEquals(1, figures.forcings);
        assertEquals(new BigDecimal("2.00"), figures.forcingAmount);
        assertEquals(1, figures.discountCount);
        assertEquals(new BigDecimal("1.00"), figures.discounts);
        assertEquals(new BigDecimal("50.00"), figures.ecommerce);
        assertEquals(1, figures.globalDiscountCount);
        assertEquals(new BigDecimal("1.50"), figures.globalDiscounts);
        assertEquals(new BigDecimal("5.00"), figures.tenders.get("VOUCHER"));
        assertEquals(2L, figures.tenderCounts.get("VOUCHER"));
        assertEquals(new BigDecimal("50.00"), figures.takings);
        assertEquals(new BigDecimal("1.80"), figures.grossVat);
        assertEquals(6, figures.items());
        assertEquals(120, figures.customerSeconds());
        assertEquals(90, figures.scanSeconds());
        assertEquals(30, figures.paySeconds());
    }

    /**
     * A ticket out of the hours, of a cashier left out, or bare: the first two
     * are skipped, the bare ones are measured with zero times under no badge.
     */
    @Test
    void ticketsSkippedOrBare() {
        ReportCriteria criteria = day();
        criteria.hourTo = LocalTime.of(11, 0);
        Ticket late = new Ticket();
        late.closingDate = AT.withHour(12);
        Ticket bare = new Ticket();
        bare.closingDate = AT;
        bare.globalDiscountApplied = BigDecimal.ZERO;
        bare.salesChannel = SalesChannel.STORE;
        Ticket undiscounted = new Ticket();
        undiscounted.closingDate = AT;
        CashierActivity activity = new CashierActivity(criteria);
        activity.addTickets(List.of(late, bare, undiscounted));
        CashierActivity.Figures figures = activity.byBadge.get("");
        assertEquals(2, figures.baskets.size());
        assertEquals(0, figures.baskets.get(0).customerSeconds);
        assertEquals(BigDecimal.ZERO, figures.baskets.get(0).amount);
        assertFalse(figures.baskets.get(0).cheque);
        assertEquals(BigDecimal.ZERO, figures.globalDiscounts);
        assertEquals("", figures.name());
        Ticket other = new Ticket();
        other.closingDate = AT;
        other.cashier = employee("222", null, null);
        CashierActivity chosen = new CashierActivity(day("111"));
        chosen.addTickets(List.of(other));
        assertTrue(chosen.cashiers().isEmpty());
    }

    /**
     * The cashier's name is taken from the first employee read, blanks for
     * missing names, and kept once known.
     */
    @Test
    void theNameIsTakenOnce() {
        CashierActivity activity = new CashierActivity(day());
        CashierActivity.Figures nameless = activity.of("333", employee("333", null, null));
        assertEquals("", nameless.firstName);
        assertEquals("", nameless.lastName);
        CashierActivity.Figures named = activity.of("111", employee("111", "Jean", "Dupont"));
        assertSame(named, activity.of("111", employee("111", "Paul", "Martin")));
        assertEquals("Jean", named.firstName);
        assertSame(named, activity.of("111", null));
        assertNull(new CashierActivity(day("111")).of(null, null));
    }

    /**
     * The cancelled tickets, the cancelled lines — last or other — and the
     * refunds go to their cashier; a cashier left out is skipped.
     */
    @Test
    void cancellationsAndRefunds() {
        CashierActivity activity = new CashierActivity(day("111", ""));
        Ticket kept = new Ticket();
        kept.cashier = employee("111", "Jean", "Dupont");
        kept.totalIncludingTax = new BigDecimal("4.00");
        Ticket anonymous = new Ticket();
        Ticket other = new Ticket();
        other.cashier = employee("222", "Paul", "Martin");
        activity.addCancelledTickets(List.of(kept, anonymous, other));
        TicketLine last = line("2.00", "0.2");
        last.cancelledBy = "111";
        last.cancelledLast = true;
        TicketLine earlier = line(null, "0.2");
        earlier.cancelledBy = "111";
        TicketLine foreign = line("1.00", "0.2");
        foreign.cancelledBy = "222";
        activity.addCancelledLines(List.of(last, earlier, foreign));
        Refund refund = new Refund();
        refund.cashierBadgeId = "111";
        refund.totalAmount = new BigDecimal("-6.00");
        refund.totalVat = new BigDecimal("-1.00");
        Refund bare = new Refund();
        bare.cashierBadgeId = "111";
        Refund outside = new Refund();
        outside.cashierBadgeId = "222";
        activity.addRefunds(List.of(refund, bare, outside));
        CashierActivity.Figures figures = activity.byBadge.get("111");
        assertEquals(1, figures.ticketCancels);
        assertEquals(new BigDecimal("4.00"), figures.ticketCancelAmount);
        assertEquals(1, activity.byBadge.get("").ticketCancels);
        assertEquals(1, figures.lineCancels);
        assertEquals(new BigDecimal("2.00"), figures.lineCancelAmount);
        assertEquals(1, figures.articleCancels);
        assertEquals(BigDecimal.ZERO, figures.articleCancelAmount);
        assertEquals(2, figures.refunds);
        assertEquals(new BigDecimal("6.00"), figures.refundAmount);
        assertEquals(new BigDecimal("1.00"), figures.refundVat);
        assertFalse(activity.byBadge.containsKey("222"));
    }

    /**
     * The sessions: their time cut to the period goes to who opened them, the
     * float of those opened in it, the closings and discrepancies of those
     * closed in it to who closed them.
     */
    @Test
    void sessionsAreCutToThePeriod() {
        CashierActivity activity = new CashierActivity(day("111", ""));
        CashSession inside = new CashSession();
        inside.openingCashier = employee("111", "Jean", "Dupont");
        inside.closingCashier = employee("111", "Jean", "Dupont");
        inside.openingDate = AT;
        inside.closingDate = AT.plusHours(2);
        inside.openingFloat = new BigDecimal("100.00");
        inside.variance = new BigDecimal("-0.50");
        CashSession overnight = new CashSession();
        overnight.openingDate = AT.minusDays(1);
        overnight.closingDate = AT.plusDays(1);
        CashSession open = new CashSession();
        open.openingCashier = employee("111", null, null);
        open.openingDate = AT.minusDays(1);
        CashSession closedBefore = new CashSession();
        closedBefore.openingCashier = employee("222", null, null);
        closedBefore.openingDate = AT.minusDays(2);
        closedBefore.closingDate = AT.minusDays(1);
        CashSession closedByOther = new CashSession();
        closedByOther.openingCashier = employee("222", null, null);
        closedByOther.closingCashier = employee("222", null, null);
        closedByOther.openingDate = AT;
        closedByOther.closingDate = AT.plusHours(1);
        CashSession closedByNobody = new CashSession();
        closedByNobody.openingCashier = employee("222", null, null);
        closedByNobody.openingDate = AT;
        closedByNobody.closingDate = AT.plusHours(1);
        activity.addSessions(List.of(inside, overnight, open, closedBefore, closedByOther, closedByNobody));
        CashierActivity.Figures figures = activity.byBadge.get("111");
        assertEquals(7200 + 86400, figures.sessionSeconds);
        assertEquals(new BigDecimal("100.00"), figures.floats);
        assertEquals(1, figures.closings);
        assertEquals(new BigDecimal("-0.50"), figures.variance);
        CashierActivity.Figures nobody = activity.byBadge.get("");
        assertEquals(86400, nobody.sessionSeconds);
        assertEquals(BigDecimal.ZERO, nobody.floats);
        assertEquals(1, nobody.closings);
        assertEquals(BigDecimal.ZERO, nobody.variance);
        assertFalse(activity.byBadge.containsKey("222"));
    }

    /**
     * The cash movements: withdrawals by tender, contributions, expenses,
     * down-payments; a declaration counts nothing; a cashier left out is
     * skipped.
     */
    @Test
    void cashMovementsAreAdded() {
        CashierActivity activity = new CashierActivity(day("111", ""));
        CashMovement cash = movement(CashMovement.MovementType.WITHDRAWAL, "-50.00", null, "111");
        CashMovement card = movement(CashMovement.MovementType.WITHDRAWAL, "20.00", "CHEQUE", "111");
        CashMovement deposit = movement(CashMovement.MovementType.DEPOSIT, "30.00", null, "111");
        CashMovement expense = movement(CashMovement.MovementType.EXPENSE, null, null, "111");
        CashMovement down = movement(CashMovement.MovementType.CUSTOMER_DEPOSIT, "15.00", null, "111");
        CashMovement count = movement(CashMovement.MovementType.DECLARATION, "1.00", null, null);
        CashMovement foreign = movement(CashMovement.MovementType.DEPOSIT, "1.00", null, "222");
        activity.addMovements(List.of(cash, card, deposit, expense, down, count, foreign));
        CashierActivity.Figures figures = activity.byBadge.get("111");
        assertEquals(2, figures.withdrawalCount);
        assertEquals(Map.of("CASH", new BigDecimal("50.00"), "CHEQUE", new BigDecimal("20.00")), figures.withdrawals);
        assertEquals(new BigDecimal("30.00"), figures.contributions);
        assertEquals(1, figures.outflowCount);
        assertEquals(BigDecimal.ZERO, figures.outflows);
        assertEquals(1, figures.depositCount);
        assertEquals(new BigDecimal("15.00"), figures.deposits);
        assertEquals(BigDecimal.ZERO, activity.byBadge.get("").contributions);
        assertFalse(activity.byBadge.containsKey("222"));
    }

    /**
     * Builds a cash movement.
     *
     * @param type the type
     * @param amount the amount, possibly null
     * @param method the tender, possibly null
     * @param badge the cashier's badge, or null for no cashier
     * @return the movement
     */
    private CashMovement movement(CashMovement.MovementType type, String amount, String method, String badge) {
        CashMovement movement = new CashMovement();
        movement.type = type;
        movement.amount = amount == null ? null : new BigDecimal(amount);
        movement.paymentMethod = method;
        movement.cashier = badge == null ? null : employee(badge, null, null);
        return movement;
    }

    /**
     * The events: an unlock closes the previous one of its register, a lock or
     * a session closing ends the interval, the locks are told apart, the drawer
     * openings and timed operations counted, an interval left open is closed at
     * the period's end; the events of a cashier left out count nothing.
     */
    @Test
    void eventsMakeTheActiveTime() {
        CashierActivity activity = new CashierActivity(day("111"));
        TechnicalEvent.EventType unlock = TechnicalEvent.EventType.REGISTER_UNLOCKED;
        TechnicalEvent.EventType lock = TechnicalEvent.EventType.REGISTER_LOCKED;
        activity.addEvents(List.of(
                event(lock, "C01", "111", AT.minusHours(1), TechnicalEvent.LOCK_MANUAL),
                event(unlock, "C01", "111", AT, null),
                event(unlock, "C01", "111", AT.plusMinutes(10), null),
                event(lock, "C01", "111", AT.plusMinutes(20), TechnicalEvent.LOCK_MANUAL),
                event(unlock, "C01", "111", AT.plusMinutes(30), null),
                event(lock, "C01", "111", AT.plusMinutes(40), TechnicalEvent.LOCK_AUTOMATIC),
                event(lock, "C01", "222", AT.plusMinutes(41), TechnicalEvent.LOCK_MANUAL),
                event(unlock, "C02", "222", AT.plusMinutes(42), null),
                event(TechnicalEvent.EventType.SESSION_CLOSED, "C02", "111", AT.plusMinutes(50), null),
                event(TechnicalEvent.EventType.DRAWER_FORCED_OPEN, "C01", "111", AT, null),
                event(TechnicalEvent.EventType.DRAWER_FORCED_OPEN, "C01", "222", AT, null),
                event(TechnicalEvent.EventType.CASH_OPERATION_TIMED, "C01", "111", AT, "WITHDRAWAL;45"),
                event(TechnicalEvent.EventType.CASH_OPERATION_TIMED, "C01", "222", AT, "WITHDRAWAL;45"),
                event(lock, "C03", "111", AT, "OTHER"),
                event(unlock, "C04", "111", DAY.atTime(23, 0), null)));
        CashierActivity.Figures figures = activity.byBadge.get("111");
        assertEquals(600 + 600 + 600 + 3600, figures.activeSeconds);
        assertEquals(2, figures.manualLocks);
        assertEquals(1, figures.automaticLocks);
        assertEquals(1, figures.drawerOpenings);
        assertEquals(45, figures.cashOperationSeconds);
        assertEquals(1, activity.cashiers().size());
    }

    /**
     * An event naming nobody — a null or blank badge, an unlock included —
     * opens no nameless cashier, whose zeros would drag the averages down.
     */
    @Test
    void eventsNamingNobodyOpenNoCashier() {
        CashierActivity activity = new CashierActivity(day());
        activity.addEvents(List.of(
                event(TechnicalEvent.EventType.SUPERVISOR_CALLED, "C01", null, AT, "Autre"),
                event(TechnicalEvent.EventType.REGISTER_UNLOCKED, "C01", " ", AT.plusMinutes(1), null),
                event(TechnicalEvent.EventType.REGISTER_LOCKED, "C01", null, AT.plusMinutes(5), TechnicalEvent.LOCK_MANUAL)));
        assertTrue(activity.byBadge.isEmpty());
    }

    /**
     * The global recap adds up every cashier, named « Toutes les caissières ».
     */
    @Test
    void theRecapAddsEveryCashier() {
        CashierActivity activity = new CashierActivity(day());
        CashierActivity.Figures one = activity.of("111", null);
        CashierActivity.Figures two = activity.of("222", null);
        for (CashierActivity.Figures figures : List.of(one, two)) {
            figures.baskets.add(new CashierActivity.Basket());
            figures.sales = BigDecimal.ONE;
            figures.vat.add(new BigDecimal("0.2"), 1, new BigDecimal("12.00"));
            figures.tenders.put("CASH", BigDecimal.TEN);
            figures.tenderCounts.put("CASH", 1L);
            figures.withdrawals.put("CASH", BigDecimal.ONE);
            figures.manualLocks = 1;
            figures.activeSeconds = 100;
            figures.refundVat = BigDecimal.ONE;
            figures.returnArticles = 3;
        }
        CashierActivity.Figures total = activity.total();
        assertEquals(6, total.returnArticles);
        assertEquals("Toutes les caissières", total.name());
        assertEquals(2, total.baskets.size());
        assertEquals(new BigDecimal("2"), total.sales);
        assertEquals(new BigDecimal("24.00"), total.vat.getBuckets().get(0).totalIncludingTax);
        assertEquals(new BigDecimal("20"), total.tenders.get("CASH"));
        assertEquals(2L, total.tenderCounts.get("CASH"));
        assertEquals(new BigDecimal("2"), total.withdrawals.get("CASH"));
        assertEquals(2, total.manualLocks);
        assertEquals(200, total.activeSeconds);
        assertEquals(200, total.idleSeconds());
        assertEquals(new BigDecimal("2"), total.refundVat);
        CashierActivity.Basket slow = new CashierActivity.Basket();
        slow.customerSeconds = 500;
        total.baskets.add(slow);
        assertEquals(0, total.idleSeconds());
    }

    /**
     * The helpers: the timed detail read or zero, the seconds between moments
     * or zero, an amount or zero, the clock rendering.
     */
    @Test
    void theHelpers() {
        assertEquals(0, CashierActivity.timed(null));
        assertEquals(0, CashierActivity.timed("WITHDRAWAL"));
        assertEquals(0, CashierActivity.timed("WITHDRAWAL;x"));
        assertEquals(0, CashierActivity.timed("WITHDRAWAL;-4"));
        assertEquals(12, CashierActivity.timed("WITHDRAWAL; 12"));
        assertEquals(0, CashierActivity.seconds(null, AT));
        assertEquals(0, CashierActivity.seconds(AT, null));
        assertEquals(0, CashierActivity.seconds(AT, AT.minusSeconds(1)));
        assertEquals(5, CashierActivity.seconds(AT, AT.plusSeconds(5)));
        assertEquals(BigDecimal.ZERO, CashierActivity.value(null));
        assertEquals(BigDecimal.TEN, CashierActivity.value(BigDecimal.TEN));
        assertEquals("01:05:09", CashierActivity.clock(3909));
    }

    /**
     * A tender is named by its administered label; one administered without
     * label or with a blank one, or not administered, by the French name of
     * its key; a key without French name by itself.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aTenderIsNamedByItsLabel() {
        TenderDefinition card = new TenderDefinition();
        card.label = "Carte bancaire";
        TenderDefinition blank = new TenderDefinition();
        blank.label = " ";
        TenderDefinition bare = new TenderDefinition();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class))).thenAnswer(call -> {
                PanacheQuery<Object> query = mock(PanacheQuery.class);
                Object key = call.getArguments()[1];
                when(query.firstResult()).thenReturn("CARD".equals(key) ? card : "TR".equals(key) ? blank
                        : "CHEQUE".equals(key) ? bare : null);
                return query;
            });
            assertEquals("Carte bancaire", CashierActivity.tenderLabel("CARD"));
            assertEquals("Titres-restaurant", CashierActivity.tenderLabel("TR"));
            assertEquals("Chèque", CashierActivity.tenderLabel("CHEQUE"));
            assertEquals("Espèces", CashierActivity.tenderLabel("CASH"));
            assertEquals("XYZ", CashierActivity.tenderLabel("XYZ"));
        }
    }
}
