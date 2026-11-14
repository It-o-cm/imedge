package com.intermarche.edge.service.sync;

import com.intermarche.edge.domain.sale.Ticket;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link VoucherBurnService} (BO-11-01-18), targeting 100%
 * branch coverage: an absent or blank number, a voucher burned by a dated or
 * undated sale, a voucher nobody consumed, and the filter keeping the burned
 * codes among several, nulls and blanks included.
 */
class VoucherBurnServiceTest {

    /**
     * Stubs the burn query of one number.
     *
     * @param panache the Panache static mock
     * @param number the trimmed number
     * @param tickets the closed sales that settled it
     */
    @SuppressWarnings("unchecked")
    private void stubBurn(MockedStatic<PanacheEntityBase> panache, String number, List<Ticket> tickets) {
        PanacheQuery<Ticket> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(tickets.isEmpty() ? null : tickets.get(0));
        when(query.list()).thenReturn(tickets);
        panache.when(() -> Ticket.find(VoucherBurnService.BURN_QUERY, number, Ticket.TicketStatus.CLOSED))
                .thenReturn(query);
    }

    /**
     * Builds a closed sale.
     *
     * @param closedAt its closing instant, possibly null
     * @return the ticket
     */
    private Ticket sale(LocalDateTime closedAt) {
        Ticket ticket = new Ticket();
        ticket.terminalId = "C04";
        ticket.ticketNumber = "C04-000042";
        ticket.closingDate = closedAt;
        return ticket;
    }

    /** No number, no question. */
    @Test
    void anAbsentNumberIsNotBurned() {
        VoucherBurnService service = new VoucherBurnService();
        Assertions.assertNull(service.findBurn(null));
        Assertions.assertNull(service.findBurn(" "));
    }

    /** A burned voucher names its register, its ticket and the instant, which may be unknown. */
    @Test
    void aBurnedVoucherNamesWhereItWasConsumed() {
        VoucherBurnService service = new VoucherBurnService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubBurn(panache, "B1", List.of(sale(LocalDateTime.of(2026, 10, 25, 9, 0))));
            stubBurn(panache, "B2", List.of(sale(null)));
            stubBurn(panache, "B3", List.of());
            Assertions.assertEquals(new VoucherBurnService.Burn("B1", "C04", "C04-000042", "2026-10-25T09:00:00"),
                    service.findBurn(" B1 "));
            Assertions.assertEquals(new VoucherBurnService.Burn("B2", "C04", "C04-000042", null),
                    service.findBurn("B2"));
            Assertions.assertNull(service.findBurn("B3"));
        }
    }

    /** Among several codes, only the burned ones are kept, nulls and blanks skipped. */
    @Test
    void theBurnedCodesAreKept() {
        VoucherBurnService service = new VoucherBurnService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubBurn(panache, "B1", List.of(sale(null)));
            stubBurn(panache, "B3", List.of());
            Assertions.assertEquals(Set.of("B1"), service.burnedAmong(Arrays.asList("B1", null, " ", "B3")));
        }
    }
}
