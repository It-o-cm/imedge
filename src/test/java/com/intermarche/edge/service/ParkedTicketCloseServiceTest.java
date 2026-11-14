package com.intermarche.edge.service;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.session.DayEndParkedTicket;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.sync.RegisterCommand;
import com.intermarche.edge.service.sync.RegisterCommandService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ParkedTicketCloseService} (BO-09-01-05, -08,
 * BO-09-02-01): the end of day records every ticket on hold once, with what it
 * carried, and journals it; the reset orders each register holding tickets to
 * cancel them, records them as reset and journals each.
 */
class ParkedTicketCloseServiceTest {

    /** The day of the tests. */
    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);

    /**
     * A spied service, recording on, its lookups and persistence stubbed.
     *
     * @param parked the tickets on hold
     * @return the service
     */
    private ParkedTicketCloseService service(List<Ticket> parked) {
        ParkedTicketCloseService service = spy(new ParkedTicketCloseService());
        service.posSettingsService = mock(PosSettingsService.class);
        service.registerCommandService = mock(RegisterCommandService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        when(service.posSettingsService.periodParkedRecord()).thenReturn(true);
        doReturn(parked).when(service).parked(any());
        doReturn(List.of()).when(service).pendingRecords();
        doReturn(null).when(service).recordOf(any(), anyString());
        doNothing().when(service).save(any());
        return service;
    }

    /**
     * A ticket on hold.
     *
     * @param terminal the register
     * @param number the number
     * @param total the total, or null
     * @return the ticket
     */
    private Ticket ticket(String terminal, String number, String total) {
        Ticket ticket = new Ticket();
        ticket.terminalId = terminal;
        ticket.ticketNumber = number;
        ticket.status = Ticket.TicketStatus.PARKED;
        ticket.totalIncludingTax = total == null ? null : new BigDecimal(total);
        return ticket;
    }

    /**
     * Each ticket on hold not yet recorded for the day is recorded with what
     * it carried and journalled, then the count; one already recorded is not
     * recorded again.
     */
    @Test
    void theTicketsOnHoldAreRecordedOnce() {
        Ticket carried = ticket("C04", "C04-1", "3.50");
        Employee martin = new Employee();
        martin.firstName = "Paul";
        martin.lastName = "Martin";
        carried.cashier = martin;
        TicketLine milk = new TicketLine();
        milk.quantity = new BigDecimal("2.000");
        milk.productLabel = "LAIT";
        milk.totalPrice = new BigDecimal("2.40");
        TicketLine gone = new TicketLine();
        gone.cancelled = true;
        TicketLine bare = new TicketLine();
        bare.productLabel = "SAC";
        carried.lines.add(milk);
        carried.lines.add(gone);
        carried.lines.add(bare);
        Ticket known = ticket("C05", "C05-1", null);
        ParkedTicketCloseService service = service(List.of(carried, known));
        doReturn(new DayEndParkedTicket()).when(service).recordOf(DAY, "C05-1");
        assertEquals(1, service.record(DAY, "Fin de période automatique"));
        ArgumentCaptor<DayEndParkedTicket> row = ArgumentCaptor.forClass(DayEndParkedTicket.class);
        verify(service).save(row.capture());
        assertEquals("C04", row.getValue().terminalId);
        assertEquals("C04-1", row.getValue().ticketNumber);
        assertEquals("Paul Martin", row.getValue().cashier);
        assertEquals(2, row.getValue().itemCount);
        assertEquals(new BigDecimal("3.50"), row.getValue().amount);
        assertEquals("2 x LAIT = 2.4\n0 x SAC = 0\n", row.getValue().content);
        assertEquals(DayEndParkedTicket.Fate.RECORDED, row.getValue().fate);
        assertEquals(DAY, row.getValue().closeDay);
        assertEquals("Fin de période automatique", row.getValue().recordedBy);
        verify(service.technicalEventService).log(TechnicalEvent.EventType.TICKET_PARKED,
                "Clôture avec ticket en attente C04-1 sur la caisse C04 : 2 article(s), 3.50");
        verify(service.technicalEventService).log(TechnicalEvent.EventType.SESSION_CLOSED,
                "Fin de journée du 2026-10-24 : 1 ticket(s) en attente enregistré(s)");
    }

    /**
     * Recording off, or nothing new to record, nothing is written.
     */
    @Test
    void nothingIsRecordedWhenOffOrEmpty() {
        ParkedTicketCloseService off = service(List.of(ticket("C04", "C04-1", "1")));
        when(off.posSettingsService.periodParkedRecord()).thenReturn(false);
        assertEquals(0, off.record(DAY, "a"));
        verifyNoInteractions(off.technicalEventService);
        ParkedTicketCloseService empty = service(List.of());
        assertEquals(0, empty.record(DAY, "a"));
        verifyNoInteractions(empty.technicalEventService);
    }

    /**
     * The reset of one register's tickets — or of all — orders each register
     * once, marks every ticket reset, recording it first when the day had not,
     * and journals each.
     */
    @Test
    void theResetOrdersEachRegisterOnce() {
        Ticket first = ticket("C04", "C04-1", "1.00");
        Ticket second = ticket("C04", "C04-2", "2.00");
        Ticket other = ticket(null, "X-1", "3.00");
        ParkedTicketCloseService service = service(List.of(first, second, other));
        DayEndParkedTicket recorded = new DayEndParkedTicket();
        recorded.terminalId = "C04";
        recorded.ticketNumber = "C04-1";
        doReturn(recorded).when(service).recordOf(DAY, "C04-1");
        assertEquals(3, service.raz(null, "Marie Curie", DAY));
        assertEquals(DayEndParkedTicket.Fate.RAZ_ORDERED, recorded.fate);
        verify(service, times(2)).save(any());
        verify(service.registerCommandService).issue("C04", RegisterCommand.CommandType.PURGE_PARKED_TICKETS,
                "Marie Curie", "Remise à zéro des tickets en attente");
        verify(service.registerCommandService).issue("", RegisterCommand.CommandType.PURGE_PARKED_TICKETS,
                "Marie Curie", "Remise à zéro des tickets en attente");
        verify(service.technicalEventService).log(TechnicalEvent.EventType.TICKET_CANCELLED,
                "Remise à zéro du ticket en attente C04-2 demandée à la caisse C04 par Marie Curie");
        ParkedTicketCloseService none = service(List.of());
        assertEquals(0, none.raz("C09", "Marie Curie", DAY));
        verify(none.registerCommandService, never()).issue(any(), any(), any(), any());
    }

    /**
     * A record built for a ticket without register, cashier, total or author
     * fills each with its default.
     */
    @Test
    void aRecordFillsItsDefaults() {
        DayEndParkedTicket row = ParkedTicketCloseService.row(DAY, ParkedTicketCloseService.of(ticket(null, "N-1", null)), " ",
                DayEndParkedTicket.Fate.RAZ_ORDERED);
        assertEquals("", row.terminalId);
        assertEquals("", row.cashier);
        assertEquals(BigDecimal.ZERO, row.amount);
        assertEquals("back-office", row.recordedBy);
        assertEquals("", row.content);
        assertEquals("back-office", ParkedTicketCloseService.row(DAY, ParkedTicketCloseService.of(ticket(null, "N-1", null)), null,
                DayEndParkedTicket.Fate.RECORDED).recordedBy);
    }

    /**
     * The tickets on hold are listed without the deactivated registers, for
     * one register or all; the records are read and written through Panache.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theTicketsOnHoldAreListed() {
        ParkedTicketCloseService service = new ParkedTicketCloseService();
        Ticket c04 = ticket("C04", "C04-1", "1");
        Ticket c05 = ticket("C05", "C05-1", "1");
        Ticket off = ticket("OFF", "OFF-1", "1");
        CheckoutPoint deactivated = new CheckoutPoint();
        deactivated.active = false;
        PanacheQuery<PanacheEntityBase> none = mock(PanacheQuery.class);
        PanacheQuery<PanacheEntityBase> offPoint = mock(PanacheQuery.class);
        when(offPoint.firstResult()).thenReturn(deactivated);
        PanacheQuery<PanacheEntityBase> latest = mock(PanacheQuery.class);
        when(latest.page(0, 20)).thenReturn(latest);
        when(latest.list()).thenReturn(List.of());
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Ticket.list("status", Ticket.TicketStatus.PARKED)).thenReturn(List.of(c04, c05, off));
            ms.when(() -> CheckoutPoint.find(eq("terminalId"), any(Object[].class))).thenReturn(none);
            ms.when(() -> CheckoutPoint.find("terminalId", "OFF")).thenReturn(offPoint);
            ms.when(() -> PanacheEntityBase.find("closeDay = ?1 and ticketNumber = ?2", DAY, "C04-1")).thenReturn(none);
            ms.when(() -> PanacheEntityBase.find("order by recordedAt desc, id desc")).thenReturn(latest);
            assertEquals(List.of(c04, c05), service.parked(null));
            assertEquals(List.of(c05), service.parked("C05"));
            assertNull(service.recordOf(DAY, "C04-1"));
            assertTrue(service.latest(20).isEmpty());
        }
        DayEndParkedTicket row = mock(DayEndParkedTicket.class);
        service.save(row);
        verify(row).persist();
    }

    /**
     * A chain record of a ticket put on hold.
     *
     * @param terminal the register
     * @param number the ticket's number
     * @param operator the operator's badge, or null
     * @param content its content, or null
     * @return the record
     */
    private com.intermarche.edge.domain.sale.ParkedTicketRecord record(String terminal, String number,
                                                                      String operator, String content) {
        com.intermarche.edge.domain.sale.ParkedTicketRecord record = new com.intermarche.edge.domain.sale.ParkedTicketRecord();
        record.terminalId = terminal;
        record.ticketNumber = number;
        record.operator = operator;
        record.itemCount = 2;
        record.totalAmount = new BigDecimal("4.20");
        record.content = content;
        return record;
    }

    /**
     * BO-09-01-05, -08: the tickets on hold the node knows from the chain
     * records join the parked rows — a ticket known both ways once, the
     * register asked for only, the deactivated ones left out — their cashier
     * named from the badge.
     */
    @Test
    void theChainRecordsJoinTheParkedRows() {
        Ticket row = ticket("C04", "C04-1", "1.00");
        ParkedTicketCloseService service = service(List.of(row));
        doReturn(List.of(record("C04", "C04-1", null, null), record("C04", "C04-2", "0001", "1 x LAIT = 1.2\n"),
                record("C05", "C05-1", null, null), record("OFF", "OFF-1", null, null))).when(service).pendingRecords();
        doReturn(true).when(service).kept(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull());
        doReturn(false).when(service).kept("OFF", null);
        doReturn("Paul Martin").when(service).cashierOf("0001");
        doReturn("").when(service).cashierOf(null);
        List<ParkedTicketCloseService.Pending> pending = service.pending(null);
        assertEquals(3, pending.size());
        assertEquals("C04-1", pending.get(0).ticketNumber());
        assertEquals(new ParkedTicketCloseService.Pending("C04", "C04-2", "Paul Martin", 2, new BigDecimal("4.20"),
                "1 x LAIT = 1.2\n"), pending.get(1));
        assertEquals("", pending.get(2).content());
        assertEquals("C05", pending.get(2).terminalId());
    }

    /**
     * A register is kept when in service and asked for, every register when
     * none is asked for; a badge names its cashier, itself when unknown,
     * nothing when absent; the chain records are read from their finder.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theRegistersAndCashiersAreResolved() {
        ParkedTicketCloseService service = new ParkedTicketCloseService();
        CheckoutPoint deactivated = new CheckoutPoint();
        deactivated.active = false;
        PanacheQuery<PanacheEntityBase> none = mock(PanacheQuery.class);
        PanacheQuery<PanacheEntityBase> offPoint = mock(PanacheQuery.class);
        when(offPoint.firstResult()).thenReturn(deactivated);
        Employee martin = new Employee();
        martin.firstName = "Paul";
        martin.lastName = "Martin";
        PanacheQuery<PanacheEntityBase> known = mock(PanacheQuery.class);
        when(known.firstResult()).thenReturn(martin);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CheckoutPoint.find(eq("terminalId"), any(Object[].class))).thenReturn(none);
            ms.when(() -> CheckoutPoint.find("terminalId", "OFF")).thenReturn(offPoint);
            ms.when(() -> Employee.find("badgeId", "0001")).thenReturn(known);
            ms.when(() -> Employee.find("badgeId", "0009")).thenReturn(none);
            assertTrue(service.kept("C04", null));
            assertTrue(service.kept("C04", "C04"));
            org.junit.jupiter.api.Assertions.assertFalse(service.kept("C04", "C05"));
            org.junit.jupiter.api.Assertions.assertFalse(service.kept("OFF", null));
            assertEquals("Paul Martin", service.cashierOf(" 0001 "));
            assertEquals("0009", service.cashierOf("0009"));
            assertEquals("", service.cashierOf(null));
            assertEquals("", service.cashierOf(" "));
            assertTrue(service.pendingRecords().isEmpty());
        }
    }
}
