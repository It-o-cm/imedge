package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.ParkedTicketRecord;
import com.intermarche.edge.domain.sale.TrainingTicket;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static com.intermarche.edge.ui.report.QueryStub.row;
import static com.intermarche.edge.ui.report.QueryStub.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PendingTicketReport}: the carts waiting and recalled,
 * the recall's ticket and cashier read off the signed trace, the training
 * carts marked « mode école », the three options, the register and cashier
 * criteria, and the metadata.
 */
class PendingTicketReportTest {

    /** The parking moment. */
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 20, 10, 0);

    /**
     * Builds an employee.
     *
     * @param badge the badge
     * @return the employee
     */
    private Employee cashier(String badge) {
        Employee employee = new Employee();
        employee.badgeId = badge;
        employee.firstName = "Jean";
        employee.lastName = "Dupont";
        return employee;
    }

    /**
     * Builds a training cart put on hold.
     *
     * @param source the register's id
     * @param resumed whether it was recalled
     * @param login the cashier's login, or null
     * @return the training row
     */
    private TrainingTicket training(long source, boolean resumed, String login) {
        TrainingTicket parked = new TrainingTicket();
        parked.sourceId = source;
        parked.terminalId = "C01";
        parked.parkedAt = AT.plusMinutes(source);
        parked.parkedBy = "111";
        parked.cashierLogin = login;
        parked.itemCount = 2;
        parked.totalIncludingTax = source == 2 ? null : new BigDecimal("8.00");
        if (resumed) {
            parked.resumedAt = AT.plusHours(1);
            parked.resumedTerminalId = "C02";
            parked.resumedBy = "222";
        }
        return parked;
    }

    /**
     * Runs the report.
     *
     * @param stub the query stub
     * @param criteria the edition parameters
     * @return the rows
     */
    @SuppressWarnings("unchecked")
    private List<List<Object>> run(QueryStub stub, ReportCriteria criteria) {
        PendingTicketReport report = new PendingTicketReport();
        report.entityManager = stub.entityManager;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Object> found = mock(PanacheQuery.class);
            when(found.firstResult()).thenReturn(cashier("111"));
            panache.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class))).thenReturn(found);
            return report.rows(criteria);
        }
    }

    /**
     * Both halves and the training carts: a waiting cart, a recalled one with
     * its recall's ticket and cashier, a cart without cashier, and the two
     * training carts marked « O », in parking order.
     */
    @Test
    void bothHalvesAndTheTrainingCarts() {
        QueryStub stub = new QueryStub()
                .answer("from Ticket t", rows(
                        row(AT, "C01", cashier("111"), "T1", 3, new BigDecimal("12.00"), AT, null, null),
                        row(AT.minusHours(1), null, null, null, 1, null, null, AT.plusHours(2), "C02")))
                .answer("r.ticketNumber, r.operator from ParkedTicketRecord", rows(row("null", "333"), row("T9", null)))
                .answer("from TrainingTicket", List.of(training(1, true, "jdupont"), training(2, false, null)));
        ReportCriteria criteria = new ReportCriteria();
        criteria.option = "ALL";
        List<List<Object>> rows = run(stub, criteria);
        assertEquals(4, rows.size());
        List<Object> recalled = rows.get(0);
        assertEquals(AT.minusHours(1), recalled.get(0));
        assertEquals("", recalled.get(1));
        assertEquals("", recalled.get(2));
        assertEquals("", recalled.get(4));
        assertEquals(BigDecimal.ZERO, recalled.get(6));
        assertEquals(AT.plusHours(2), recalled.get(7));
        assertEquals("", recalled.get(9));
        assertEquals("333", recalled.get(10));
        assertEquals("Non", recalled.get(11));
        List<Object> waiting = rows.get(1);
        assertEquals("T1", waiting.get(4));
        assertEquals("Jean Dupont", waiting.get(3));
        assertEquals("", waiting.get(9));
        assertEquals("", waiting.get(10));
        List<Object> trained = rows.get(2);
        assertEquals("FORMATION-1", trained.get(4));
        assertEquals("Jean Dupont", trained.get(3));
        assertEquals("FORMATION-1", trained.get(9));
        assertEquals("222", trained.get(10));
        assertEquals("C02", trained.get(8));
        assertEquals("Oui", trained.get(11));
        assertEquals(1L, trained.get(12));
        List<Object> trainedWaiting = rows.get(3);
        assertEquals("", trainedWaiting.get(3));
        assertEquals(BigDecimal.ZERO, trainedWaiting.get(6));
        assertEquals("", trainedWaiting.get(7));
        assertEquals("", trainedWaiting.get(9));
        assertTrue(stub.asked("or t.resumedAt is not null) and t.creationDate"));
    }

    /**
     * The waiting option filters the tickets and keeps the training carts not
     * recalled; the recalled option keeps the recalled ones; the registers and
     * cashiers chosen leave the others out.
     */
    @Test
    void theOptionsAndTheCriteriaSelect() {
        QueryStub waitingStub = new QueryStub()
                .answer("from TrainingTicket", List.of(training(1, true, null), training(2, false, null)));
        List<List<Object>> waiting = run(waitingStub, new ReportCriteria());
        assertEquals(1, waiting.size());
        assertEquals("FORMATION-2", waiting.get(0).get(4));
        assertTrue(waitingStub.asked("and t.resumedAt is null"));
        QueryStub recalledStub = new QueryStub()
                .answer("from TrainingTicket", List.of(training(1, true, null), training(2, false, null)));
        ReportCriteria recalled = new ReportCriteria();
        recalled.option = "RECALLED";
        List<List<Object>> rows = run(recalledStub, recalled);
        assertEquals(1, rows.size());
        assertEquals("FORMATION-1", rows.get(0).get(4));
        assertTrue(recalledStub.asked("and t.resumedAt is not null order by"));
        ReportCriteria otherRegister = new ReportCriteria();
        otherRegister.option = "ALL";
        otherRegister.terminals.add("C09");
        assertTrue(run(new QueryStub().answer("from TrainingTicket", List.of(training(1, false, null))),
                otherRegister).isEmpty());
        ReportCriteria otherCashier = new ReportCriteria();
        otherCashier.option = "ALL";
        otherCashier.cashiers.add("999");
        QueryStub cashierStub = new QueryStub().answer("from TrainingTicket", List.of(training(1, false, null)));
        assertTrue(run(cashierStub, otherCashier).isEmpty());
        assertTrue(cashierStub.asked("t.cashier.badgeId in :cashierBadges"));
    }

    /**
     * A recalled ticket carries its own number as the recall's ticket and the
     * cashier who recalled it.
     */
    @Test
    void aRecalledTicketNamesItsRecall() {
        QueryStub stub = new QueryStub()
                .answer("from Ticket t", rows(row(AT, "C01", null, "T2", 1, null, AT, AT.plusHours(1), "C02")))
                .answer("r.ticketNumber, r.operator from ParkedTicketRecord", rows(row("T2", "444")));
        ReportCriteria criteria = new ReportCriteria();
        criteria.option = "RECALLED";
        List<Object> row = run(stub, criteria).get(0);
        assertEquals("T2", row.get(9));
        assertEquals("444", row.get(10));
    }

    /**
     * Builds a signed trace record.
     *
     * @param terminal the register
     * @param number the ticket's number
     * @param event what happened
     * @param minutes the minutes after the parking moment
     * @param operator the operator's badge, possibly null
     * @return the record
     */
    private ParkedTicketRecord record(String terminal, String number, ParkedTicketRecord.Event event, int minutes,
                                      String operator) {
        ParkedTicketRecord record = new ParkedTicketRecord();
        record.terminalId = terminal;
        record.ticketNumber = number;
        record.event = event;
        record.eventDate = AT.plusMinutes(minutes);
        record.operator = operator;
        record.itemCount = 2;
        record.totalAmount = "P2".equals(number) ? null : new BigDecimal("6.00");
        return record;
    }

    /**
     * The tickets still waiting are read off the signed trace: a PARKED record
     * no RESUMED or PURGED record follows, of a register and cashier kept, in
     * the period, not already listed; the recalled option does not read them.
     */
    @Test
    void theWaitingTicketsAreReadOffTheTrace() {
        java.util.List<ParkedTicketRecord> trace = List.of(
                record("C01", "P1", ParkedTicketRecord.Event.PARKED, 0, "111"),
                record("C01", "P2", ParkedTicketRecord.Event.PARKED, 1, null),
                record(null, null, ParkedTicketRecord.Event.PARKED, 2, "111"),
                record("C01", "P3", ParkedTicketRecord.Event.PARKED, 3, "111"),
                record("C01", "P3", ParkedTicketRecord.Event.RESUMED, 4, "222"),
                record("C01", "P4", ParkedTicketRecord.Event.PARKED, 5, "111"),
                record("C01", "P4", ParkedTicketRecord.Event.PURGED, 6, null),
                record("C01", "P5", ParkedTicketRecord.Event.PARKED, 60 * 24, "111"),
                record("C01", "T1", ParkedTicketRecord.Event.PARKED, 7, "111"),
                record("C09", "P6", ParkedTicketRecord.Event.PARKED, 8, "111"),
                record("C01", "P7", ParkedTicketRecord.Event.PARKED, 9, "999"));
        QueryStub stub = new QueryStub()
                .answer("from Ticket t", rows(row(AT, "C01", null, "T1", 1, null, null, null, null)))
                .answer("select r from ParkedTicketRecord r", trace);
        ReportCriteria criteria = new ReportCriteria();
        criteria.period = ReportPeriod.RANGE;
        criteria.rangeFrom = AT.toLocalDate();
        criteria.rangeTo = AT.toLocalDate();
        criteria.cashiers.addAll(List.of("111", ""));
        List<List<Object>> waiting = run(stub, criteria);
        assertEquals(5, waiting.size());
        assertEquals("T1", waiting.get(0).get(4));
        List<Object> p1 = waiting.get(1);
        assertEquals("P1", p1.get(4));
        assertEquals("C01", p1.get(1));
        assertEquals("111", p1.get(2));
        assertEquals("Jean Dupont", p1.get(3));
        assertEquals(2L, p1.get(5));
        assertEquals(new BigDecimal("6.00"), p1.get(6));
        assertEquals("Non", p1.get(11));
        assertEquals(1L, p1.get(12));
        List<Object> p2 = waiting.get(2);
        assertEquals("", p2.get(2));
        assertEquals("", p2.get(3));
        assertEquals(BigDecimal.ZERO, p2.get(6));
        List<Object> bare = waiting.get(3);
        assertEquals("", bare.get(1));
        assertEquals("", bare.get(4));
        assertEquals("P6", waiting.get(4).get(4));
        criteria.terminals.add("C01");
        QueryStub restricted = new QueryStub()
                .answer("from Ticket t", rows(row(AT, "C01", null, "T1", 1, null, null, null, null)))
                .answer("select r from ParkedTicketRecord r", trace);
        assertEquals(3, run(restricted, criteria).size());
        ReportCriteria recalled = new ReportCriteria();
        recalled.option = "RECALLED";
        QueryStub recallStub = new QueryStub().answer("select r from ParkedTicketRecord r", trace);
        assertTrue(run(recallStub, recalled).isEmpty());
        assertFalse(recallStub.asked("select r from ParkedTicketRecord r"));
    }

    /**
     * A training cart without register, cashier or recall register reads blank.
     */
    @Test
    void aTrainingCartWithBlanks() {
        TrainingTicket bare = training(3, true, null);
        bare.terminalId = null;
        bare.parkedBy = null;
        bare.resumedTerminalId = null;
        bare.resumedBy = null;
        ReportCriteria criteria = new ReportCriteria();
        criteria.option = "ALL";
        List<List<Object>> rows = run(new QueryStub().answer("from TrainingTicket", List.of(bare)), criteria);
        assertEquals("", rows.get(0).get(1));
        assertEquals("", rows.get(0).get(2));
        assertEquals("", rows.get(0).get(8));
        assertEquals("", rows.get(0).get(10));
    }

    /**
     * The metadata: identity, the thirteen columns, the options and the filters.
     */
    @Test
    void metadataDescribesTheReport() {
        PendingTicketReport report = new PendingTicketReport();
        assertEquals("pending-tickets", report.code());
        assertEquals(ReportFamily.CASHIERS, report.family());
        assertEquals("BO-06-06-01", report.requirement());
        assertEquals(13, report.columns().size());
        assertEquals("Nombre de tickets", report.columns().get(12).label());
        assertEquals("Mode école", report.columns().get(11).label());
        assertEquals("Tickets édités", report.optionLabel());
        assertEquals(List.of("WAITING:En attente, non rappelés", "RECALLED:Rappelés", "ALL:Tous les tickets"),
                report.optionChoices());
        assertEquals(java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.CASHIERS),
                report.filters());
        assertFalse(report.label().isEmpty());
        assertEquals(ParkedTicketRecord.Event.RESUMED, ParkedTicketRecord.Event.valueOf("RESUMED"));
    }
}
