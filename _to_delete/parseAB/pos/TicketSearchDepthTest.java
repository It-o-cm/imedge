package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.service.PosSettingsService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the ADMINISTERED SEARCH DEPTH of {@link TicketSearchService}
 * (BO-10-07-16).
 *
 * <p>The three legs of the resolution are exercised directly: no settings
 * service at all (a harness that does not drive this rule), an administered
 * depth, and an administered value that is not a depth — zero or negative,
 * which is the absence of one rather than a register that finds nothing.
 */
class TicketSearchDepthTest {

    /**
     * No settings service: the built-in thirty days.
     */
    @Test
    void anUnwiredRegisterKeepsTheBuiltInDepth() {
        assertEquals(TicketSearchService.RETENTION_DAYS, new TicketSearchService().searchDays());
    }

    /**
     * An administered depth is the depth.
     */
    @Test
    void anAdministeredDepthIsUsed() {
        TicketSearchService service = new TicketSearchService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.ticketSearchDays()).thenReturn(90);
        assertEquals(90, service.searchDays());
    }

    /**
     * Zero and a negative value are the ABSENCE of a depth, not a depth of
     * nothing: the register falls back rather than answering every search with
     * an empty list.
     */
    @Test
    void anImpossibleDepthFallsBack() {
        TicketSearchService zero = new TicketSearchService();
        zero.posSettingsService = mock(PosSettingsService.class);
        when(zero.posSettingsService.ticketSearchDays()).thenReturn(0);
        assertEquals(TicketSearchService.RETENTION_DAYS, zero.searchDays());

        TicketSearchService negative = new TicketSearchService();
        negative.posSettingsService = mock(PosSettingsService.class);
        when(negative.posSettingsService.ticketSearchDays()).thenReturn(-5);
        assertEquals(TicketSearchService.RETENTION_DAYS, negative.searchDays());
    }
}
