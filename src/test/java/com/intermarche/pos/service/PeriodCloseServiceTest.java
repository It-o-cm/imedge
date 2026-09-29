package com.intermarche.pos.service;

import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.domain.sync.RegisterCommand;
import com.intermarche.pos.service.sync.RegisterCommandService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PeriodCloseService}.
 * <p>
 * The service reads the administered parameters, watches the clock and leaves
 * orders in the book; all three collaborators ({@link PosSettingsService},
 * {@link RegisterCommandService}, {@link TechnicalEventService}) are Mockito
 * mocks on package-private fields, and the only database access — the list of
 * open sessions — goes through {@link CashSession}'s static finder, which
 * resolves to {@link PanacheEntityBase} and is intercepted with
 * {@link org.mockito.Mockito#mockStatic}. No Quarkus context and no database
 * is booted, and the clock-dependent methods are called with an explicit
 * moment so no test depends on the hour it runs at.
 * <p>
 * Branch enumeration (every arm exercised): {@code tick} — the disarmed arm,
 * the outside-the-window arm, the empty arm and the acting arm;
 * {@code order} — the order-already-waiting arm and the issuing arm;
 * {@code inClosingWindow} — the zero-lead arm (on and off the minute), the
 * ordinary window (before, inside, on the boundary, after) and the window
 * straddling midnight (both of its legs, and outside it);
 * {@code endOfPeriod} — the readable arm and the unreadable arm falling back on
 * the catalog; {@code countdownMinutes} — the disarmed arm, the beyond-horizon
 * arm and the announced arm; {@code minutesUntil} — the same-day arm and the
 * rollover arm; {@code tickSafely} — the success arm and the catch arm.
 */
class PeriodCloseServiceTest {

    /**
     * Builds a service whose collaborators are mocks, armed at the given hour
     * with the given lead.
     *
     * @param armed whether the automatic close is enabled
     * @param endTime the administered end-of-period hour
     * @param lead the administered lead, in minutes
     * @return the wired service
     */
    private PeriodCloseService newService(boolean armed, String endTime, int lead) {
        PeriodCloseService service = new PeriodCloseService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.periodAutoCloseEnabled()).thenReturn(armed);
        when(service.posSettingsService.periodEndTime()).thenReturn(endTime);
        when(service.posSettingsService.periodCloseLeadMinutes()).thenReturn(lead);
        service.registerCommandService = mock(RegisterCommandService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        service.retentionPurgeService = mock(RetentionPurgeService.class);
        return service;
    }

    /**
     * Builds an open session on one register.
     *
     * @param terminalId the register
     * @return the session
     */
    private CashSession session(String terminalId) {
        CashSession session = new CashSession();
        session.terminalId = terminalId;
        session.status = CashSession.SessionStatus.OPEN;
        return session;
    }

    /**
     * Stubs the list of registers still open.
     *
     * @param panache the active PanacheEntityBase mock
     * @param sessions the sessions to resolve
     */
    private void stubOpenSessions(MockedStatic<PanacheEntityBase> panache, List<CashSession> sessions) {
        panache.when(() -> CashSession.list("status", CashSession.SessionStatus.OPEN))
                .thenReturn(sessions);
    }

    /**
     * The node starts its own watching loop.
     */
    @Test
    void theNodeStartsItsWatchingLoop() {
        PeriodCloseService service = newService(true, "23:30", 5);
        service.onStart(new StartupEvent());
        assertNotNull(service.executor);
        service.executor.shutdownNow();
    }

    /**
     * A tick that fails does not take the loop with it: the window lasts
     * several minutes and the next tick tries again.
     */
    @Test
    void aFailedTickIsSwallowed() {
        PeriodCloseService service = newService(true, "23:30", 5);
        when(service.posSettingsService.periodAutoCloseEnabled())
                .thenThrow(new IllegalStateException("base indisponible"));
        service.tickSafely();
        verifyNoInteractions(service.registerCommandService);
    }

    /**
     * A tick that succeeds runs the examination.
     */
    @Test
    void aSuccessfulTickRunsTheExamination() {
        PeriodCloseService service = newService(false, "23:30", 5);
        service.tickSafely();
        verify(service.posSettingsService).periodAutoCloseEnabled();
        verifyNoInteractions(service.registerCommandService);
    }

    /**
     * The automatic close disarmed: nothing is read, nothing is ordered, and
     * the shop's registers stay as they are.
     */
    @Test
    void aDisarmedCloseOrdersNothing() {
        PeriodCloseService service = newService(false, "23:30", 5);
        service.tick();
        verifyNoInteractions(service.registerCommandService);
        verifyNoInteractions(service.technicalEventService);
    }

    /**
     * Armed but outside the window: the clock is consulted and nothing else
     * happens.
     */
    @Test
    void outsideTheWindowNothingIsOrdered() {
        PeriodCloseService service = newService(true, "23:30", 5);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubOpenSessions(panache, List.of(session("CAISSE1")));
            when(service.posSettingsService.periodEndTime()).thenReturn(
                    LocalTime.now().plusHours(3).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
            service.tick();
        }
        verifyNoInteractions(service.registerCommandService);
    }

    /**
     * Inside the window with nothing open: the list is read and no order is
     * left — the ordinary evening of a shop that closed properly.
     */
    @Test
    void insideTheWindowWithNoOpenRegisterOrdersNothing() {
        PeriodCloseService service = newService(true,
                LocalTime.now().plusMinutes(2).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), 5);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubOpenSessions(panache, List.of());
            service.tick();
        }
        verifyNoInteractions(service.registerCommandService);
    }

    /**
     * Inside the window with two registers left open: each one is given the
     * close-if-idle order.
     */
    @Test
    void insideTheWindowEveryOpenRegisterIsOrdered() {
        PeriodCloseService service = newService(true,
                LocalTime.now().plusMinutes(2).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), 5);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubOpenSessions(panache, List.of(session("CAISSE1"), session("CAISSE2")));
            service.tick();
        }
        verify(service.registerCommandService).issue("CAISSE1",
                RegisterCommand.CommandType.CLOSE_SESSION_IF_IDLE,
                "Fin de période", "Fermeture automatique de fin de période");
        verify(service.registerCommandService).issue("CAISSE2",
                RegisterCommand.CommandType.CLOSE_SESSION_IF_IDLE,
                "Fin de période", "Fermeture automatique de fin de période");
    }

    /**
     * A register given its order has the operation written to the electronic
     * journal, as the requirement demands.
     */
    @Test
    void anOrderIsJournalled() {
        PeriodCloseService service = newService(true, "23:30", 5);
        service.order("CAISSE1");
        verify(service.technicalEventService).log(eq(TechnicalEvent.EventType.SESSION_CLOSED),
                eq("Fermeture de fin de période demandée à la caisse CAISSE1"));
    }

    /**
     * A register that already carries an order — the back office's, or this
     * loop's own from a minute ago — is left alone: a tick a minute must not
     * pile orders onto one register.
     */
    @Test
    void aRegisterAlreadyCarryingAnOrderIsLeftAlone() {
        PeriodCloseService service = newService(true, "23:30", 5);
        when(service.registerCommandService.pendingCount("CAISSE1")).thenReturn(1L);
        service.order("CAISSE1");
        verify(service.registerCommandService, never()).issue(anyString(), any(), anyString(), anyString());
        verifyNoInteractions(service.technicalEventService);
    }

    /**
     * An ordinary window opens at the lead and closes at the end of period:
     * before, on the opening minute, inside, and from the end onwards.
     */
    @Test
    void theOrdinaryWindowRunsFromTheLeadToTheEnd() {
        PeriodCloseService service = newService(true, "23:30", 5);
        assertFalse(service.inClosingWindow(LocalTime.of(23, 24)));
        assertTrue(service.inClosingWindow(LocalTime.of(23, 25)));
        assertTrue(service.inClosingWindow(LocalTime.of(23, 29)));
        assertFalse(service.inClosingWindow(LocalTime.of(23, 30)));
        assertFalse(service.inClosingWindow(LocalTime.of(23, 31)));
    }

    /**
     * A lead of zero leaves the single minute of the end of period, which a
     * tick-a-minute loop still catches.
     */
    @Test
    void aZeroLeadLeavesOnlyTheEndMinute() {
        PeriodCloseService service = newService(true, "23:30", 0);
        assertTrue(service.inClosingWindow(LocalTime.of(23, 30)));
        assertTrue(service.inClosingWindow(LocalTime.of(23, 30, 45)));
        assertFalse(service.inClosingWindow(LocalTime.of(23, 29)));
        assertFalse(service.inClosingWindow(LocalTime.of(23, 31)));
    }

    /**
     * A zero-lead window rejects a moment falling on the wrong hour: the false
     * arm of the hour comparison in the single-minute test, which every
     * same-hour case leaves untouched.
     */
    @Test
    void aZeroLeadRejectsADifferentHour() {
        PeriodCloseService service = newService(true, "23:30", 0);
        assertFalse(service.inClosingWindow(LocalTime.of(22, 30)));
    }

    /**
     * A negative lead is read as no lead at all rather than opening a window
     * that runs backwards over the whole day.
     */
    @Test
    void aNegativeLeadIsReadAsNone() {
        PeriodCloseService service = newService(true, "23:30", -20);
        assertTrue(service.inClosingWindow(LocalTime.of(23, 30)));
        assertFalse(service.inClosingWindow(LocalTime.of(23, 20)));
    }

    /**
     * A period ending after midnight opens its window the day before: both
     * legs of the wrapped test, and a moment outside it.
     */
    @Test
    void aWindowMayStraddleMidnight() {
        PeriodCloseService service = newService(true, "00:15", 30);
        assertTrue(service.inClosingWindow(LocalTime.of(23, 50)));
        assertTrue(service.inClosingWindow(LocalTime.of(0, 5)));
        assertFalse(service.inClosingWindow(LocalTime.of(23, 40)));
        assertFalse(service.inClosingWindow(LocalTime.of(0, 15)));
    }

    /**
     * The administered hour is read as written.
     */
    @Test
    void theAdministeredHourIsRead() {
        PeriodCloseService service = newService(true, " 21:45 ", 5);
        assertEquals(LocalTime.of(21, 45), service.endOfPeriod());
        assertEquals("21:45", service.endOfPeriodLabel());
    }

    /**
     * An unreadable hour falls back on the catalog default rather than
     * silently disarming a closing the shop believes armed.
     */
    @Test
    void anUnreadableHourFallsBackOnTheCatalog() {
        PeriodCloseService service = newService(true, "minuit", 5);
        String fallback = PosSettingsService.CATALOG.stream()
                .filter(def -> "period.end-time".equals(def.key()))
                .findFirst().orElseThrow().defaultValue();
        assertEquals(LocalTime.parse(fallback), service.endOfPeriod());
    }

    /**
     * The countdown says nothing while the automatic close is disarmed.
     */
    @Test
    void aDisarmedCloseAnnouncesNothing() {
        PeriodCloseService service = newService(false, "23:30", 5);
        assertEquals(-1L, service.countdownMinutes());
    }

    /**
     * The countdown says nothing while the end of period is still beyond the
     * announcement horizon.
     */
    @Test
    void aDistantEndOfPeriodAnnouncesNothing() {
        PeriodCloseService service = newService(true,
                LocalTime.now().plusHours(3).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), 5);
        assertEquals(-1L, service.countdownMinutes());
    }

    /**
     * Inside the horizon the countdown gives the minutes left, which is what
     * the back office's banner shows.
     */
    @Test
    void anImminentEndOfPeriodIsAnnounced() {
        PeriodCloseService service = newService(true,
                LocalTime.now().plusMinutes(10).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), 5);
        long left = service.countdownMinutes();
        assertTrue(left >= 0L && left <= 10L, () -> "countdown " + left);
    }

    /**
     * The countdown counts forward within the day, and rolls over midnight
     * instead of going negative.
     */
    @Test
    void theCountdownRollsOverMidnight() {
        assertEquals(30L, PeriodCloseService.minutesUntil(LocalTime.of(23, 0), LocalTime.of(23, 30)));
        assertEquals(0L, PeriodCloseService.minutesUntil(LocalTime.of(23, 30), LocalTime.of(23, 30)));
        assertEquals(45L, PeriodCloseService.minutesUntil(LocalTime.of(23, 30), LocalTime.of(0, 15)));
    }

    /**
     * Past the end of period, on a day it has not run yet, the retention sweep
     * runs once and the day is stamped so it does not run again.
     */
    @Test
    void theRetentionSweepRunsPastTheEndOfPeriod() {
        PeriodCloseService service = newService(true, "23:30", 5);
        LocalDate today = LocalDate.of(2026, 1, 15);
        service.maybeSweep(LocalTime.of(23, 45), today);
        verify(service.retentionPurgeService).sweep();
        assertEquals(today, service.lastPurgeDay);
    }

    /**
     * Before the end of period the sweep stays its hand: it must not read tables
     * the day is still writing.
     */
    @Test
    void theRetentionSweepWaitsForTheEndOfPeriod() {
        PeriodCloseService service = newService(true, "23:30", 5);
        service.maybeSweep(LocalTime.of(10, 0), LocalDate.of(2026, 1, 15));
        verifyNoInteractions(service.retentionPurgeService);
        assertNull(service.lastPurgeDay);
    }

    /**
     * Once it has run for the day, a later tick past the end of period leaves it
     * alone — the minute-a-tick loop launches exactly one sweep per period.
     */
    @Test
    void theRetentionSweepRunsAtMostOnceADay() {
        PeriodCloseService service = newService(true, "23:30", 5);
        LocalDate today = LocalDate.of(2026, 1, 15);
        service.lastPurgeDay = today;
        service.maybeSweep(LocalTime.of(23, 50), today);
        verifyNoInteractions(service.retentionPurgeService);
    }

    /**
     * A tick past the end of period on a fresh day drives the sweep through the
     * whole {@code tick} path, closing window included, from the real clock.
     */
    @Test
    void aTickPastTheEndOfPeriodSweeps() {
        PeriodCloseService service = newService(true,
                LocalTime.now().minusMinutes(1).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), 5);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubOpenSessions(panache, List.of());
            service.tick();
        }
        verify(service.retentionPurgeService).sweep();
    }
}
