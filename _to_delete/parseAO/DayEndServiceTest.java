package com.intermarche.edge.service;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.domain.sync.RegisterCommand;
import com.intermarche.edge.service.sync.RegisterCommandService;
import com.intermarche.edge.service.sync.RegisterPresenceService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DayEndService} ({@code BO-09-01-04} to {@code -08},
 * {@code BO-09-02-01} to {@code -03}).
 *
 * <p>The two listings are inherited Panache statics and are intercepted with
 * {@code mockStatic} on {@link PanacheEntityBase}; every collaborator is a
 * mock. Branch enumeration (both arms): a session and a cart carrying their
 * fields and carrying none; the countdown inside and outside the alert
 * horizon, and the countdown the clock refuses to give; an alerting state
 * with and without anything to alert about; a register already carrying an
 * order, which is not ordered twice; a register named by nothing, which is
 * not ordered at all; the operator named and unnamed; and the horizon
 * absent, zero and negative.
 */
class DayEndServiceTest {

    /**
     * Builds a service with every collaborator mocked.
     *
     * @param countdown what the clock answers
     * @param autoEnabled whether the automatic close is armed
     * @param horizon the administered alert horizon
     * @return the service under test
     */
    private DayEndService service(long countdown, boolean autoEnabled, int horizon) {
        DayEndService service = new DayEndService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.periodAutoCloseEnabled()).thenReturn(autoEnabled);
        when(service.posSettingsService.alertCountdownMinutes()).thenReturn(horizon);
        service.registerCommandService = mock(RegisterCommandService.class);
        service.registerPresenceService = mock(RegisterPresenceService.class);
        when(service.registerPresenceService.offline()).thenReturn(List.of());
        service.technicalEventService = mock(TechnicalEventService.class);
        service.periodCloseService = mock(PeriodCloseService.class);
        when(service.periodCloseService.countdownMinutes()).thenReturn(countdown);
        when(service.periodCloseService.endOfPeriodLabel()).thenReturn("23:30");
        return service;
    }

    /**
     * Builds an open session.
     *
     * @param terminalId the register, possibly null
     * @param number the session number, possibly null
     * @param cashier who opened it, possibly null
     * @return the session
     */
    private CashSession session(String terminalId, String number, Employee cashier) {
        CashSession session = new CashSession();
        session.terminalId = terminalId;
        session.sessionNumber = number;
        session.openingCashier = cashier;
        return session;
    }

    /**
     * Builds a parked cart.
     *
     * @param terminalId the register, possibly null
     * @param number the ticket number, possibly null
     * @param total the total, possibly null
     * @return the ticket
     */
    private Ticket parked(String terminalId, String number, String total) {
        Ticket ticket = new Ticket();
        ticket.terminalId = terminalId;
        ticket.ticketNumber = number;
        ticket.totalIncludingTax = total == null ? null : new BigDecimal(total);
        return ticket;
    }

    /**
     * Builds an employee.
     *
     * @param lastName the surname
     * @return the employee
     */
    private Employee employee(String lastName) {
        Employee employee = new Employee();
        employee.lastName = lastName;
        employee.firstName = "";
        return employee;
    }

    /**
     * Stubs the two listings.
     *
     * @param panache the static mock
     * @param sessions the open sessions
     * @param carts the parked carts
     */
    private void stubListings(MockedStatic<PanacheEntityBase> panache,
                              List<CashSession> sessions, List<Ticket> carts) {
        panache.when(() -> CashSession.list("status", CashSession.SessionStatus.OPEN))
                .thenReturn(sessions);
        panache.when(() -> Ticket.list("status", Ticket.TicketStatus.PARKED))
                .thenReturn(carts);
    }

    /**
     * BO-09-01-05/-06: the picture names the open lanes and the parked
     * carts, filling the columns a null leaves empty rather than printing
     * the word null.
     */
    @Test
    void thePictureNamesWhatIsOpenAndWhatIsParked() {
        DayEndService service = service(20L, true, 60);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubListings(panache,
                    List.of(session("CAISSE1", "S-1", employee("Martin")),
                            session(null, null, null)),
                    List.of(parked("CAISSE1", "C04-1", "12.50"),
                            parked(null, null, null)));
            DayEndService.DayEndState state = service.state();
            assertEquals("23:30", state.endOfPeriod());
            assertTrue(state.autoEnabled());
            assertEquals(20L, state.countdownMinutes());
            assertTrue(state.alerting());
            assertTrue(state.hasAlert());
            assertEquals("CAISSE1", state.openRegisters().get(0).terminalId());
            assertEquals("Martin", state.openRegisters().get(0).cashier());
            assertEquals("", state.openRegisters().get(1).terminalId());
            assertEquals("", state.openRegisters().get(1).cashier());
            assertEquals("12.50", state.parkedCarts().get(0).amount());
            assertEquals("", state.parkedCarts().get(1).amount());
        }
    }

    /**
     * BO-09-01-04: outside the alert horizon, and when the clock refuses to
     * give a countdown at all, nothing is announced; and a quiet shop with
     * nothing open, nothing silent and nothing parked raises no alert.
     */
    @Test
    void nothingIsAnnouncedOutsideTheHorizon() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubListings(panache, List.of(), List.of());
            DayEndService.DayEndState far = service(120L, true, 60).state();
            assertFalse(far.alerting());
            assertFalse(far.hasAlert());
            DayEndService.DayEndState silent = service(-1L, false, 60).state();
            assertFalse(silent.alerting());
            assertFalse(silent.autoEnabled());
        }
    }

    /**
     * BO-09-02-01 to -03: the manual launch orders every open lane closed,
     * journals what it found, and names the operator who pressed.
     */
    @Test
    void theManualLaunchOrdersAndJournals() {
        DayEndService service = service(10L, true, 60);
        when(service.registerPresenceService.offline()).thenReturn(List.of(
                new RegisterPresenceService.Presence("CAISSE9", LocalDateTime.now(), 12L, false)));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubListings(panache,
                    List.of(session("CAISSE1", "S-1", employee("Martin"))),
                    List.of(parked("CAISSE1", "C04-1", "12.50")));
            service.launch("Marie Curie");
            verify(service.registerCommandService).issue(eq("CAISSE1"),
                    eq(RegisterCommand.CommandType.CLOSE_SESSION_IF_IDLE),
                    eq("Marie Curie"), anyString());
            verify(service.technicalEventService).log(
                    eq(TechnicalEvent.EventType.SESSION_CLOSED), contains("Marie Curie"));
            verify(service.technicalEventService).log(
                    eq(TechnicalEvent.EventType.SESSION_CLOSED), contains("CAISSE9"));
            verify(service.technicalEventService).log(
                    eq(TechnicalEvent.EventType.SESSION_CLOSED), contains("C04-1"));
        }
    }

    /**
     * BO-09-02-03: a lane already carrying an order is not ordered twice,
     * which is what makes the button safe to press again; a lane named by
     * nothing is not ordered at all; and an unnamed operator is named
     * back-office.
     */
    @Test
    void anOrderIsNeverLeftTwice() {
        DayEndService service = service(10L, true, 60);
        when(service.registerCommandService.pendingCount("CAISSE1")).thenReturn(1L);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubListings(panache,
                    List.of(session("CAISSE1", "S-1", null), session("", "S-2", null)),
                    List.of());
            service.launch("   ");
            verify(service.registerCommandService, never()).issue(any(), any(), any(), any());
            verify(service.technicalEventService).log(
                    eq(TechnicalEvent.EventType.SESSION_CLOSED), contains("back-office"));
        }
    }

    /**
     * BO-09-01-04: an absent or absurd horizon answers an hour rather than
     * showing the alerts for one minute a day.
     */
    @Test
    void anAbsurdHorizonAnswersAnHour() {
        assertEquals(DayEndService.DEFAULT_ALERT_MINUTES, service(0L, true, 0).alertMinutes());
        assertEquals(DayEndService.DEFAULT_ALERT_MINUTES, service(0L, true, -3).alertMinutes());
        assertEquals(15, service(0L, true, 15).alertMinutes());
    }
}
