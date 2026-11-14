package com.intermarche.edge.service;

import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.store.StorePeriodClose;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StorePeriodCloseService} (BO-03-12-02): the closed day
 * of the calendar, the open day chaining every administered action, the
 * rewrite of a day already consolidated, the counting of the open registers
 * and the latest closes.
 */
class StorePeriodCloseServiceTest {

    /** The day consolidated. */
    private static final LocalDate DAY = LocalDate.of(2026, 10, 23);

    /**
     * Builds a service with mocked collaborators and the given calendar.
     *
     * @param weekdays the closed weekdays
     * @param dates the closed dates
     * @return the service
     */
    private StorePeriodCloseService newService(Set<DayOfWeek> weekdays, Set<LocalDate> dates) {
        StorePeriodCloseService service = new StorePeriodCloseService();
        service.posSettingsService = mock(PosSettingsService.class);
        service.safeService = mock(SafeService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        when(service.posSettingsService.financialClosedWeekdays()).thenReturn(weekdays);
        when(service.posSettingsService.financialClosedDates()).thenReturn(dates);
        return service;
    }

    /**
     * Builds a query answering one row.
     *
     * @param row the row, or null
     * @param <T> the row type
     * @return the query
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> query(T row) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        return query;
    }

    /** A closed weekday and a closed date are both closed days; another day is open. */
    @Test
    void closedDayReadsBothCalendars() {
        assertTrue(newService(Set.of(DAY.getDayOfWeek()), Set.of()).closedDay(DAY));
        assertTrue(newService(Set.of(), Set.of(DAY)).closedDay(DAY));
        assertFalse(newService(Set.of(), Set.of()).closedDay(DAY));
    }

    /** A closed day gets its row, persisted, chaining nothing. */
    @Test
    void aClosedDayChainsNothing() {
        StorePeriodCloseService service = newService(Set.of(DAY.getDayOfWeek()), Set.of());
        PanacheQuery<StorePeriodClose> absent = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<StorePeriodClose> created = mockConstruction(StorePeriodClose.class)) {
            panache.when(() -> StorePeriodClose.find("closeDate", DAY)).thenReturn(absent);
            StorePeriodClose close = service.consolidate(DAY);
            assertSame(created.constructed().get(0), close);
            assertEquals(StorePeriodClose.Status.CLOSED_DAY, close.status);
            assertEquals(DAY, close.closeDate);
            assertNull(close.actions);
            verify(close).persist();
        }
        verify(service.technicalEventService).log(eq(TechnicalEvent.EventType.SESSION_CLOSED), anyString());
    }

    /**
     * An open day consolidated again is rewritten in place: the open registers
     * counted without the deactivated ones, the day's takings summed, every
     * action chained in order.
     */
    @Test
    void anOpenDayChainsTheActions() {
        StorePeriodCloseService service = newService(Set.of(), Set.of());
        when(service.posSettingsService.periodPdvActions()).thenReturn(List.of(
                PosSettingsService.PeriodAction.CLOSE_REGISTERS, PosSettingsService.PeriodAction.SAFE_COUNT,
                PosSettingsService.PeriodAction.Z_REPORT, PosSettingsService.PeriodAction.BANK_DEPOSIT));
        when(service.safeService.balance()).thenReturn(new BigDecimal("150.00"));
        StorePeriodClose existing = mock(StorePeriodClose.class);
        CashSession open = new CashSession();
        open.terminalId = "C01";
        CashSession off = new CashSession();
        off.terminalId = "C02";
        CheckoutPoint offPoint = new CheckoutPoint();
        offPoint.active = false;
        Ticket paid = new Ticket();
        paid.totalIncludingTax = new BigDecimal("12.50");
        Ticket free = new Ticket();
        free.totalIncludingTax = null;
        PanacheQuery<StorePeriodClose> present = query(existing);
        PanacheQuery<CheckoutPoint> noPoint = query(null);
        PanacheQuery<CheckoutPoint> offQuery = query(offPoint);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> StorePeriodClose.find("closeDate", DAY)).thenReturn(present);
            panache.when(() -> CashSession.list("status", CashSession.SessionStatus.OPEN)).thenReturn(List.of(open, off));
            panache.when(() -> CheckoutPoint.find("terminalId", "C01")).thenReturn(noPoint);
            panache.when(() -> CheckoutPoint.find("terminalId", "C02")).thenReturn(offQuery);
            panache.when(() -> Ticket.list("status = ?1 and closingDate >= ?2 and closingDate < ?3",
                    Ticket.TicketStatus.CLOSED, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay()))
                    .thenReturn(List.of(paid, free));
            StorePeriodClose close = service.consolidate(DAY);
            assertSame(existing, close);
            assertEquals(StorePeriodClose.Status.CLOSED, close.status);
            assertEquals(1, close.openRegisters);
            assertEquals(2, close.ticketCount);
            assertEquals(new BigDecimal("12.50"), close.totalIncludingTax);
            assertEquals("CLOSE_REGISTERS: 1 caisse(s) encore ouverte(s), fermeture ordonnée\n"
                    + "SAFE_COUNT: solde théorique du coffre à compter 150.00\n"
                    + "Z_REPORT: états financiers de fin de période émis\n"
                    + "BANK_DEPOSIT: remise en banque à préparer", close.actions);
            verify(existing, never()).persist();
        }
    }

    /** No administered action chains nothing. */
    @Test
    void noActionWritesNothing() {
        StorePeriodCloseService service = newService(Set.of(), Set.of());
        when(service.posSettingsService.periodPdvActions()).thenReturn(List.of());
        assertNull(service.actions(0));
    }

    /** The latest closes are read from the table. */
    @Test
    @SuppressWarnings("unchecked")
    void latestReadsTheTable() {
        StorePeriodCloseService service = newService(Set.of(), Set.of());
        StorePeriodClose close = new StorePeriodClose();
        PanacheQuery<StorePeriodClose> all = mock(PanacheQuery.class);
        PanacheQuery<StorePeriodClose> page = mock(PanacheQuery.class);
        when(all.page(0, 3)).thenReturn((PanacheQuery) page);
        when(page.list()).thenReturn(List.of(close));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> StorePeriodClose.find("order by closeDate desc")).thenReturn(all);
            assertEquals(List.of(close), service.latest(3));
        }
    }
}
