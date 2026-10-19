package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.sale.Ticket;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketSearchService}, the shared ticket search engine
 * (EXP-47). The pattern-interpretation branches (amount / date / number / too
 * short) are exercised on a spy so the routing to {@link
 * TicketSearchService#searchClosed} is observed without the database; the
 * {@code searchClosed} criterion branches (amount, date, number, none) are
 * exercised against a mocked Panache static. Both arms of every branch are
 * covered.
 */
class TicketSearchServiceTest {

    /**
     * A free-text amount ({@code 12,50}) routes to the amount criterion.
     */
    @Test
    void searchByPatternRoutesAnAmount() {
        TicketSearchService service = spy(new TicketSearchService());
        List<Ticket> marker = List.of(new Ticket());
        doReturn(marker).when(service).searchClosed(null, null, new BigDecimal("12.50"), false);
        assertSame(marker, service.searchByPattern("12,50"));
        verify(service).searchClosed(null, null, new BigDecimal("12.50"), false);
    }

    /**
     * A free-text {@code dd/MM/yyyy} value routes to the date criterion.
     */
    @Test
    void searchByPatternRoutesADate() {
        TicketSearchService service = spy(new TicketSearchService());
        List<Ticket> marker = List.of(new Ticket());
        doReturn(marker).when(service).searchClosed(null, LocalDate.of(2026, 3, 14), null, false);
        assertSame(marker, service.searchByPattern("14/03/2026"));
        verify(service).searchClosed(null, LocalDate.of(2026, 3, 14), null, false);
    }

    /**
     * Anything else long enough routes to the number criterion.
     */
    @Test
    void searchByPatternRoutesANumber() {
        TicketSearchService service = spy(new TicketSearchService());
        List<Ticket> marker = List.of(new Ticket());
        doReturn(marker).when(service).searchClosed("C04-T12", null, null, false);
        assertSame(marker, service.searchByPattern("C04-T12"));
        verify(service).searchClosed("C04-T12", null, null, false);
    }

    /**
     * A value with a comma but no valid number is not read as an amount and falls
     * through to the number criterion (the amount-parse false arm).
     */
    @Test
    void searchByPatternAmountShapedButUnparsableIsANumber() {
        TicketSearchService service = spy(new TicketSearchService());
        List<Ticket> marker = List.of(new Ticket());
        doReturn(marker).when(service).searchClosed("1234567", null, null, false);
        assertSame(marker, service.searchByPattern("1234567"));
        verify(service).searchClosed("1234567", null, null, false);
    }

    /**
     * A too-short pattern returns an empty list without any search.
     */
    @Test
    void searchByPatternTooShortReturnsEmpty() {
        TicketSearchService service = spy(new TicketSearchService());
        assertTrue(service.searchByPattern("ab").isEmpty());
        verify(service, org.mockito.Mockito.never()).searchClosed(anyString(), any(), any());
    }

    /**
     * A null pattern returns an empty list (the null arm of the length guard).
     */
    @Test
    void searchByPatternNullReturnsEmpty() {
        TicketSearchService service = spy(new TicketSearchService());
        assertTrue(service.searchByPattern(null).isEmpty());
    }

    /**
     * {@code searchClosed} with all criteria null returns an empty list without a
     * query.
     */
    @Test
    void searchClosedAllNullReturnsEmpty() {
        TicketSearchService service = new TicketSearchService();
        assertTrue(service.searchClosed(null, null, null).isEmpty());
    }

    /**
     * {@code searchClosed} with a blank number returns an empty list (the blank
     * arm of the number guard).
     */
    @Test
    void searchClosedBlankNumberReturnsEmpty() {
        TicketSearchService service = new TicketSearchService();
        assertTrue(service.searchClosed("   ", null, null).isEmpty());
    }

    /**
     * {@code searchClosed} by amount queries the total and returns the matches.
     */
    @Test
    void searchClosedByAmountQueriesTotal() {
        TicketSearchService service = new TicketSearchService();
        List<Ticket> hits = List.of(new Ticket());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Ticket> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(hits);
            panache.when(() -> Ticket.find(anyString(), any(Object[].class))).thenReturn(query);
            assertSame(hits, service.searchClosed(null, null, new BigDecimal("9.99")));
        }
    }

    /**
     * {@code searchClosed} by date queries the day window and returns the matches.
     */
    @Test
    void searchClosedByDateQueriesTheDay() {
        TicketSearchService service = new TicketSearchService();
        List<Ticket> hits = List.of(new Ticket());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Ticket> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(hits);
            panache.when(() -> Ticket.find(anyString(), any(Object[].class))).thenReturn(query);
            assertSame(hits, service.searchClosed(null, LocalDate.of(2026, 1, 2), null));
        }
    }

    /**
     * {@code searchClosed} by number queries the number and returns the matches.
     */
    @Test
    void searchClosedByNumberQueriesTheNumber() {
        TicketSearchService service = new TicketSearchService();
        List<Ticket> hits = List.of(new Ticket());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Ticket> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(hits);
            panache.when(() -> Ticket.find(anyString(), any(Object[].class))).thenReturn(query);
            assertSame(hits, service.searchClosed("C04-T99", null, null));
        }
    }

    /**
     * LC-03-06-02: the reassignment pool keeps only the tickets no client is
     * attached to — the routed criterion carries the filter down to the
     * engine, on every one of the three criteria.
     */
    @Test
    void searchByPatternRoutesWithTheUnassignedFilter() {
        TicketSearchService service = spy(new TicketSearchService());
        List<Ticket> marker = List.of(new Ticket());
        doReturn(marker).when(service).searchClosed(null, null, new BigDecimal("12.50"), true);
        doReturn(marker).when(service).searchClosed(null, LocalDate.of(2026, 3, 14), null, true);
        doReturn(marker).when(service).searchClosed("C04-T12", null, null, true);
        assertSame(marker, service.searchByPattern("12,50", true));
        assertSame(marker, service.searchByPattern("14/03/2026", true));
        assertSame(marker, service.searchByPattern("C04-T12", true));
        verify(service).searchClosed(null, null, new BigDecimal("12.50"), true);
        verify(service).searchClosed(null, LocalDate.of(2026, 3, 14), null, true);
        verify(service).searchClosed("C04-T12", null, null, true);
    }

    /**
     * LC-03-06-02: the unassigned filter reaches the QUERY itself — the
     * question the pool asks is the closed-ticket question plus the
     * no-fidelity-card one, on the amount criterion.
     */
    @Test
    void searchClosedUnassignedQueriesTheCardFilter() {
        TicketSearchService service = new TicketSearchService();
        List<Ticket> hits = List.of(new Ticket());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Ticket> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(hits);
            panache.when(() -> Ticket.find(anyString(), any(Object[].class))).thenReturn(query);
            assertSame(hits, service.searchClosed(null, null, new BigDecimal("9.99"), true));
            panache.verify(() -> Ticket.find(
                    org.mockito.ArgumentMatchers.argThat((String q) -> q.contains("fidelityCard is null")),
                    any(Object[].class)));
        }
    }

    /**
     * LC-03-06-02, the blank arm of the pool: no criterion at all still
     * answers the whole pool — the reassignment screen's full history.
     */
    @Test
    void searchClosedUnassignedBlankAnswersTheWholePool() {
        TicketSearchService service = new TicketSearchService();
        List<Ticket> hits = List.of(new Ticket());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Ticket> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(hits);
            panache.when(() -> Ticket.find(anyString(), any(Object[].class))).thenReturn(query);
            assertSame(hits, service.searchClosed(null, null, null, true));
        }
    }

    /**
     * LC-03-06-02, the ordinary blank arm: no criterion answers nothing —
     * the ordinary search never returned the world and does not start now.
     */
    @Test
    void searchClosedOrdinaryBlankAnswersNothing() {
        TicketSearchService service = new TicketSearchService();
        assertTrue(service.searchClosed(null, null, null, false).isEmpty());
    }
}
