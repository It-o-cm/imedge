package com.intermarche.edge.service;

import com.intermarche.edge.domain.sale.ParkedTicketRecord;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.sync.SyncOutbox;
import com.intermarche.edge.service.sync.register.SyncOutboxService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Unit tests for {@link ParkedTicketArchiveService} (LC-04-01-03), targeting
 * 100% branch coverage.
 *
 * <p>The record's construction is intercepted so its persist is a no-op, the
 * chain's anchor is found through a mocked static finder, the outbox is a mock.
 * Branch enumeration: the first record of a register and a following one; a
 * cancelled line, a line without total, quantities and prices with and without
 * trailing zeros, and a null price.
 */
class ParkedTicketArchiveServiceTest {

    /**
     * A line of the ticket on hold.
     *
     * @param label the label
     * @param quantity the quantity
     * @param price the unit price, or null
     * @param total the line total, or null
     * @param cancelled whether the line was cancelled
     * @return the line
     */
    private TicketLine line(String label, String quantity, String price, String total, boolean cancelled) {
        TicketLine line = new TicketLine();
        line.productLabel = label;
        line.quantity = new BigDecimal(quantity);
        line.unitPrice = price == null ? null : new BigDecimal(price);
        line.totalPrice = total == null ? null : new BigDecimal(total);
        line.cancelled = cancelled;
        return line;
    }

    /**
     * Mocks the finder of the register's last record.
     *
     * @param panache the static mock
     * @param last the last record, or null
     */
    @SuppressWarnings("unchecked")
    private void lastRecord(MockedStatic<PanacheEntityBase> panache, ParkedTicketRecord last) {
        PanacheQuery<ParkedTicketRecord> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(last);
        panache.when(() -> PanacheEntityBase.find("terminalId = ?1 and sourceId is null order by id desc", "POS01"))
                .thenReturn(query);
    }

    /**
     * The first record of a register freezes the live lines, counts and totals
     * them, anchors on GENESIS, signs and is pushed; the next one chains on it.
     */
    @Test
    void recordsAreFrozenSignedChainedAndPushed() {
        ParkedTicketArchiveService service = new ParkedTicketArchiveService();
        service.syncOutboxService = mock(SyncOutboxService.class);
        Ticket ticket = new Ticket();
        ticket.terminalId = "POS01";
        ticket.ticketNumber = "POS01-00000004";
        ticket.lines.add(line("LAIT UHT 1L", "2.000", "1.1500", "2.30", false));
        ticket.lines.add(line("CAFE", "1", null, null, false));
        ticket.lines.add(line("ANNULE", "1", "9", "9", true));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ParkedTicketRecord> rows = mockConstruction(ParkedTicketRecord.class,
                     withSettings().defaultAnswer(CALLS_REAL_METHODS),
                     (row, context) -> doNothing().when(row).persist())) {
            lastRecord(panache, null);
            ParkedTicketRecord first = service.record(ticket, ParkedTicketRecord.Event.PARKED, "12341234");
            assertSame(rows.constructed().get(0), first);
            assertEquals("POS01", first.terminalId);
            assertEquals("POS01-00000004", first.ticketNumber);
            assertEquals(ParkedTicketRecord.Event.PARKED, first.event);
            assertEquals(first.eventDate.truncatedTo(ChronoUnit.SECONDS), first.eventDate);
            assertEquals("12341234", first.operator);
            assertEquals(3, first.itemCount);
            assertEquals(new BigDecimal("2.30"), first.totalAmount);
            assertEquals("2 x LAIT UHT 1L @ 1.15 = 2.3\n1 x CAFE @ 0 = 0\n", first.content);
            assertEquals(ParkedTicketRecord.GENESIS, first.previousSignature);
            assertEquals(first.computeSignature(), first.signature);
            verify(first).persist();
            verify(service.syncOutboxService).enqueue(SyncOutbox.EntityType.PARKING, first.id);
            lastRecord(panache, first);
            ParkedTicketRecord second = service.record(ticket, ParkedTicketRecord.Event.RESUMED, "12341234");
            assertEquals(first.signature, second.previousSignature);
            assertEquals(second.computeSignature(), second.signature);
        }
    }

    /**
     * LS-03-04-04: the record counts the ARTICLES on hold — a returned line is
     * not one — while its content still lists every live line.
     */
    @Test
    void aReturnedLineIsNotAnArticleOnHold() {
        ParkedTicketArchiveService service = new ParkedTicketArchiveService();
        service.syncOutboxService = mock(SyncOutboxService.class);
        Ticket ticket = new Ticket();
        ticket.terminalId = "POS01";
        ticket.lines.add(line("BAGUETTE", "2", "1.08", "2.16", false));
        TicketLine returned = line("CAFE", "-1", "4.00", "-4.00", false);
        returned.returnLine = true;
        ticket.lines.add(returned);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ParkedTicketRecord> rows = mockConstruction(ParkedTicketRecord.class,
                     withSettings().defaultAnswer(CALLS_REAL_METHODS),
                     (row, context) -> doNothing().when(row).persist())) {
            lastRecord(panache, null);
            ParkedTicketRecord record = service.record(ticket, ParkedTicketRecord.Event.PARKED, "12341234");
            assertEquals(2, record.itemCount);
            assertEquals(new BigDecimal("-1.84"), record.totalAmount);
        }
    }
}
