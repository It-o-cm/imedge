package com.intermarche.edge.service;

import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.domain.sync.RegisterCommand;
import com.intermarche.edge.service.sync.RegisterCommandService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
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
 * orders in the book; all collaborators ({@link PosSettingsService},
 * {@link RegisterCommandService}, {@link TechnicalEventService}, and the
 * sweep-time ones) are Mockito
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
        service.salesVolumeService = mock(SalesVolumeService.class);
        service.financialStateEmissionService = mock(FinancialStateEmissionService.class);
        service.scheduledReportService = mock(ScheduledReportService.class);
        service.periodScheduleService = mock(PeriodScheduleService.class);
        service.storePeriodCloseService = mock(StorePeriodCloseService.class);
        when(service.periodScheduleService.endingOn(any(LocalDate.class)))
                .thenReturn(EnumSet.of(PeriodScheduleService.PeriodEnd.DAY));
        when(service.posSettingsService.periodAutoLaunch()).thenReturn(true);
        service.periodHookService = mock(PeriodHookService.class);
        service.parkedTicketCloseService = mock(ParkedTicketCloseService.class);
        service.registerPresenceService = mock(com.intermarche.edge.service.sync.RegisterPresenceService.class);
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
     * the shop's registers stay as they are. The retention sweep is NOT the
     * close and journals the end of period on its own once the clock has
     * reached it (BO-09-01-07) — so the journal asserted silent here is the
     * CLOSE's, the one naming a register, which the disarmed shop never
     * writes whatever the hour the test runs at.
     */
    @Test
    void aDisarmedCloseOrdersNothing() {
        PeriodCloseService service = newService(false, "23:30", 5);
        service.tick();
        verifyNoInteractions(service.registerCommandService);
        verify(service.technicalEventService, never()).log(
                eq(TechnicalEvent.EventType.SESSION_CLOSED),
                org.mockito.ArgumentMatchers.contains("demandée à la caisse"));
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
        service.order(session("CAISSE1"));
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
        when(service.registerCommandService.isPending("CAISSE1",
                RegisterCommand.CommandType.CLOSE_SESSION_IF_IDLE)).thenReturn(true);
        service.order(session("CAISSE1"));
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
        // BO-03-12-02: the consolidated close of the point of sale is built once.
        verify(service.storePeriodCloseService).consolidate(today);
        // §4: the financial states are emitted BEFORE the purge, which may carry
        // away tickets a Z still needs; the programmed editions sit in between
        // (BO-09-01-10) for the same reason.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                service.financialStateEmissionService, service.scheduledReportService,
                service.salesVolumeService, service.retentionPurgeService);
        order.verify(service.financialStateEmissionService).emitEndOfPeriod(today);
        order.verify(service.scheduledReportService).runEndOfPeriod(today);
        order.verify(service.salesVolumeService).refresh(today);
        order.verify(service.retentionPurgeService).sweep();
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
        verifyNoInteractions(service.financialStateEmissionService);
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
        verifyNoInteractions(service.financialStateEmissionService);
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

    /**
     * A spied service whose island and checkout-point lookups are stubbed:
     * register SCO1 stands on island CLS, which declares the end of period on
     * its own, attaches host 0001 and holds a self-service point.
     *
     * @return the service, the host declaration activated
     */
    private PeriodCloseService scoService() {
        PeriodCloseService service = org.mockito.Mockito.spy(newService(true, "23:30", 5));
        when(service.posSettingsService.periodScoAutoDeclaration()).thenReturn(true);
        com.intermarche.edge.domain.store.CheckoutIsland island = new com.intermarche.edge.domain.store.CheckoutIsland();
        island.code = "CLS";
        island.autoPeriodCloseDeclaration = true;
        island.terminalIds = "POS9;SCO1";
        island.hostBadges = "0001";
        com.intermarche.edge.domain.store.CheckoutPoint sco = new com.intermarche.edge.domain.store.CheckoutPoint();
        sco.tpvType = com.intermarche.edge.domain.store.TpvType.SELF_CHECKOUT_NCR;
        org.mockito.Mockito.doReturn(island).when(service).islandOf("SCO1");
        org.mockito.Mockito.doReturn(null).when(service).pointOf("POS9");
        org.mockito.Mockito.doReturn(sco).when(service).pointOf("SCO1");
        return service;
    }

    /**
     * An open session of a host.
     *
     * @param terminalId the register
     * @param badge the host's badge, or null for a session without cashier
     * @return the session
     */
    private CashSession hosted(String terminalId, String badge) {
        CashSession session = session(terminalId);
        if (badge != null) {
            session.openingCashier = new com.intermarche.edge.domain.people.Employee();
            session.openingCashier.badgeId = badge;
        }
        return session;
    }

    /**
     * BO-09-03-01: the host attached to a self-service island declaring the end
     * of period on its own is declared automatically, and the journal says so;
     * every other case takes the close-if-idle order.
     */
    @Test
    void aSelfServiceHostIsDeclaredAutomatically() {
        PeriodCloseService service = scoService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            service.order(hosted("SCO1", "0001"));
            assertEquals(RegisterCommand.CommandType.CLOSE_SESSION_IF_IDLE, service.closeOrderType(hosted("SCO1", "0002")));
            assertEquals(RegisterCommand.CommandType.CLOSE_SESSION_IF_IDLE, service.closeOrderType(hosted("SCO1", null)));
            com.intermarche.edge.domain.store.CheckoutIsland island = service.islandOf("SCO1");
            island.hostBadges = null;
            assertEquals(RegisterCommand.CommandType.CLOSE_SESSION_AUTO_DECLARE, service.closeOrderType(hosted("SCO1", null)));
        }
        verify(service.registerCommandService).issue("SCO1", RegisterCommand.CommandType.CLOSE_SESSION_AUTO_DECLARE,
                "Fin de période", "Fermeture automatique de fin de période");
        verify(service.technicalEventService).log(TechnicalEvent.EventType.SESSION_CLOSED,
                "Fermeture de fin de période demandée à la caisse SCO1 (déclaration automatique de l'hôte)");
    }

    /**
     * BO-09-03-01: no automatic declaration while the store has not activated
     * it, on a register on no island, on an island not declaring on its own,
     * or on an island holding no self-service register.
     */
    @Test
    void theAutomaticDeclarationNeedsEveryCondition() {
        PeriodCloseService service = scoService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertAllConditions(service);
        }
    }

    /**
     * Plays each missing condition of the automatic declaration.
     *
     * @param service the spied service
     */
    private void assertAllConditions(PeriodCloseService service) {
        org.mockito.Mockito.doReturn(null).when(service).islandOf("POS9");
        assertEquals(RegisterCommand.CommandType.CLOSE_SESSION_IF_IDLE, service.closeOrderType(hosted("POS9", "0001")));
        com.intermarche.edge.domain.store.CheckoutIsland island = service.islandOf("SCO1");
        island.autoPeriodCloseDeclaration = false;
        assertEquals(RegisterCommand.CommandType.CLOSE_SESSION_IF_IDLE, service.closeOrderType(hosted("SCO1", "0001")));
        island.autoPeriodCloseDeclaration = true;
        org.mockito.Mockito.doReturn(new com.intermarche.edge.domain.store.CheckoutPoint()).when(service).pointOf("SCO1");
        assertEquals(RegisterCommand.CommandType.CLOSE_SESSION_IF_IDLE, service.closeOrderType(hosted("SCO1", "0001")));
        when(service.posSettingsService.periodScoAutoDeclaration()).thenReturn(false);
        assertEquals(RegisterCommand.CommandType.CLOSE_SESSION_IF_IDLE, service.closeOrderType(hosted("SCO1", "0001")));
    }

    /**
     * The island and checkout-point lookups read the referential.
     */
    @Test
    void theLookupsReadTheReferential() {
        PeriodCloseService service = newService(true, "23:30", 5);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class))).thenReturn(List.of());
            io.quarkus.hibernate.orm.panache.PanacheQuery<?> query = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
            panache.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class))).thenReturn(query);
            assertNull(service.islandOf("SCO1"));
            assertNull(service.pointOf("SCO1"));
            assertFalse(service.deactivated("SCO1"));
            assertTrue(service.closedTickets("SCO1", LocalDate.of(2026, 1, 15)).isEmpty());
        }
    }

    /**
     * A close order of either kind already waiting holds the next one back.
     */
    @Test
    void anyCloseOrderPendingHoldsTheNextBack() {
        PeriodCloseService service = newService(true, "23:30", 5);
        assertFalse(service.closePending("SCO1"));
        when(service.registerCommandService.isPending("SCO1",
                RegisterCommand.CommandType.CLOSE_SESSION_AUTO_DECLARE)).thenReturn(true);
        assertTrue(service.closePending("SCO1"));
    }

    /**
     * BO-09-03-03: the automatic launch disarmed, the end of period journals
     * once that its treatment did not start, treats nothing — and the day
     * stays open to the manual launch, which then treats it once.
     */
    @Test
    void aDisarmedLaunchLeavesTheDayToTheButton() {
        PeriodCloseService service = newService(true, "23:30", 5);
        when(service.posSettingsService.periodAutoLaunch()).thenReturn(false);
        LocalDate today = LocalDate.of(2026, 1, 15);
        service.maybeSweep(LocalTime.of(23, 45), today);
        service.maybeSweep(LocalTime.of(23, 46), today);
        verify(service.technicalEventService).log(TechnicalEvent.EventType.SESSION_CLOSED,
                "Fin de période automatique désactivée : traitement du 2026-01-15 non lancé");
        verifyNoInteractions(service.retentionPurgeService);
        assertNull(service.lastPurgeDay);
        assertTrue(service.runEndOfPeriod(today, "Marie Curie"));
        assertFalse(service.runEndOfPeriod(today, "Marie Curie"));
        verify(service.retentionPurgeService).sweep();
        verify(service.parkedTicketCloseService).record(today, "Marie Curie");
        verify(service.parkedTicketCloseService, never()).raz(any(), any(), any());
    }

    /**
     * BO-09-02-04, BO-09-01-05, -08: the treatment runs the entry point before
     * first and the one after last, records the tickets on hold and, at the
     * automatic end of period where the store administers it, resets them.
     */
    @Test
    void theTreatmentRunsItsEntryPointsAndTheTicketsOnHold() {
        PeriodCloseService service = newService(true, "23:30", 5);
        when(service.posSettingsService.periodParkedRaz()).thenReturn(true);
        LocalDate today = LocalDate.of(2026, 1, 15);
        service.maybeSweep(LocalTime.of(23, 45), today);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(service.periodHookService,
                service.parkedTicketCloseService, service.financialStateEmissionService, service.retentionPurgeService);
        order.verify(service.periodHookService).run(com.intermarche.edge.domain.session.PeriodHookRun.Phase.BEFORE,
                today, PeriodCloseService.AUTOMATIC);
        order.verify(service.parkedTicketCloseService).record(today, PeriodCloseService.AUTOMATIC);
        order.verify(service.parkedTicketCloseService).raz(null, PeriodCloseService.AUTOMATIC, today);
        order.verify(service.financialStateEmissionService).emitEndOfPeriod(today);
        order.verify(service.retentionPurgeService).sweep();
        order.verify(service.periodHookService).run(com.intermarche.edge.domain.session.PeriodHookRun.Phase.AFTER,
                today, PeriodCloseService.AUTOMATIC);
        verifyNoInteractions(service.registerPresenceService);
    }

    /**
     * BO-09-01-08: the reset administered for the automatic end of period is
     * not the manual launch's, which leaves it to the reset button.
     */
    @Test
    void theManualLaunchDoesNotResetTheTicketsOnHold() {
        PeriodCloseService service = newService(true, "23:30", 5);
        when(service.posSettingsService.periodParkedRaz()).thenReturn(true);
        service.runEndOfPeriod(LocalDate.of(2026, 1, 15), "Marie Curie");
        verify(service.parkedTicketCloseService, never()).raz(any(), any(), any());
    }

    /**
     * BO-09-03-05: where the store activated the check, every register the
     * node knows but the deactivated ones is ordered to check its day against
     * what the server holds of it — the closed tickets and their total.
     */
    @Test
    void theRegistersAreOrderedToCheckTheirData() {
        PeriodCloseService service = org.mockito.Mockito.spy(newService(true, "23:30", 5));
        when(service.posSettingsService.periodRegisterCheck()).thenReturn(true);
        LocalDate today = LocalDate.of(2026, 1, 15);
        when(service.registerPresenceService.all()).thenReturn(List.of(
                new com.intermarche.edge.service.sync.RegisterPresenceService.Presence("POS1", null, 0L, true),
                new com.intermarche.edge.service.sync.RegisterPresenceService.Presence("OFF", null, 0L, true)));
        org.mockito.Mockito.doReturn(false).when(service).deactivated("POS1");
        org.mockito.Mockito.doReturn(true).when(service).deactivated("OFF");
        com.intermarche.edge.domain.sale.Ticket paid = new com.intermarche.edge.domain.sale.Ticket();
        paid.totalIncludingTax = new java.math.BigDecimal("12.5");
        org.mockito.Mockito.doReturn(List.of(paid, new com.intermarche.edge.domain.sale.Ticket()))
                .when(service).closedTickets("POS1", today);
        service.runEndOfPeriod(today, "Marie Curie");
        verify(service.registerCommandService).issue("POS1", RegisterCommand.CommandType.VERIFY_SYNC,
                "Fin de période", "2026-01-15;2;12.50");
        verify(service.registerCommandService, never()).issue(eq("OFF"), any(), any(), any());
        verify(service.technicalEventService).log(TechnicalEvent.EventType.SESSION_CLOSED,
                "Contrôle caisse / serveur demandé à la caisse POS1 : 2 ticket(s) reçu(s) par le serveur");
    }
}
