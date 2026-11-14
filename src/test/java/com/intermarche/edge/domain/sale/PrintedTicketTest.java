package com.intermarche.edge.domain.sale;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PrintedTicket}: its two finders, over a static Panache
 * mock, and its audiences.
 */
class PrintedTicketTest {

    /**
     * The copy of a register's row is found by its register and the register's id.
     */
    @Test
    @SuppressWarnings("unchecked")
    void findBySourceReadsTheRegistersRow() {
        PrintedTicket printout = new PrintedTicket();
        PanacheQuery<PrintedTicket> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(printout);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PrintedTicket.find("terminalId = ?1 and sourceId = ?2", "C04", 7L)).thenReturn(query);
            assertSame(printout, PrintedTicket.findBySource("C04", 7L));
        }
    }

    /**
     * The printed tickets after an id are read in their order, one page at a time.
     */
    @Test
    @SuppressWarnings("unchecked")
    void listAfterReadsOnePage() {
        PrintedTicket printout = new PrintedTicket();
        PanacheQuery<PrintedTicket> query = mock(PanacheQuery.class);
        PanacheQuery<PrintedTicket> page = mock(PanacheQuery.class);
        when(query.page(0, 50)).thenReturn(page);
        when(page.list()).thenReturn(List.of(printout));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PrintedTicket.find("id > ?1 and audience = ?2 order by id", 3L,
                    PrintedTicket.Audience.STORE)).thenReturn(query);
            assertEquals(List.of(printout), PrintedTicket.listAfter(3L, PrintedTicket.Audience.STORE, 50));
        }
    }

    /**
     * The three audiences, in their order.
     */
    @Test
    void theAudiences() {
        assertEquals(List.of(PrintedTicket.Audience.CUSTOMER, PrintedTicket.Audience.STORE,
                PrintedTicket.Audience.TRAINING), List.of(PrintedTicket.Audience.values()));
    }
}
