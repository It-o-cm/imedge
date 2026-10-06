package com.intermarche.edge.service;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.session.FinancialState;
import com.intermarche.edge.domain.session.FinancialState.PeriodKind;
import com.intermarche.edge.domain.session.FinancialState.StateScope;
import com.intermarche.edge.domain.session.FinancialState.StateType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FinancialStateEmissionService}.
 *
 * <p>Plain JUnit 5 + Mockito. The service is spied so the thin Panache-access
 * wrappers can be stubbed and the chaining/idempotence/control logic tested on
 * its own; {@link org.mockito.Mockito#mockConstruction} neutralizes the
 * {@code persist()} of the Z it builds and stubs its signature. The wrappers
 * themselves are tested against {@link PanacheEntityBase} statics.
 */
class FinancialStateEmissionServiceTest {

    /**
     * Builds a spied service with mocked collaborators.
     *
     * @return the spied service
     */
    private FinancialStateEmissionService newService() {
        FinancialStateEmissionService service = spy(new FinancialStateEmissionService());
        service.calculator = mock(FinancialStateCalculator.class);
        service.settings = mock(PosSettingsService.class);
        when(service.settings.floatMode()).thenReturn("FIXED");
        when(service.settings.financialHourlySlotMinutes()).thenReturn(60);
        when(service.settings.financialNomenclatureLevel()).thenReturn(1);
        when(service.settings.financialSectors()).thenReturn(java.util.Map.of());
        when(service.settings.financialBestSellersCount()).thenReturn(20);
        return service;
    }

    /**
     * Builds a persisted Z of a chain carrying the given sequence, bounds and
     * signature.
     *
     * @param sequence the sequence
     * @param to the upper bound and emission anchor
     * @param signature the signature
     * @return the Z
     */
    private FinancialState existingZ(long sequence, LocalDateTime to, String signature) {
        FinancialState z = new FinancialState();
        z.stateType = StateType.Z;
        z.scope = StateScope.STORE;
        z.periodKind = PeriodKind.DAY;
        z.sequence = sequence;
        z.fromInstant = to.minusDays(1);
        z.toInstant = to;
        z.emittedAt = to;
        z.signature = signature;
        z.netTurnover = new BigDecimal("50.0000");
        return z;
    }

    /**
     * A first Z opens its chain at the first ticket instant, takes sequence one and
     * an empty previous signature, sweeps in no late ticket, and is signed and
     * persisted.
     */
    @Test
    void emitZOpensTheChainOnTheFirstEmission() {
        FinancialStateEmissionService service = newService();
        LocalDateTime first = LocalDateTime.of(2026, 1, 1, 8, 0);
        LocalDateTime bound = LocalDateTime.of(2026, 1, 2, 0, 0);
        doReturn(null).when(service).lockedLastZ(StateScope.STORE, PeriodKind.DAY, null);
        doReturn(first).when(service).firstTicketInstant();
        doReturn(List.of()).when(service).closedTicketsBetween(any(), any(), isNull());
        doReturn(List.of()).when(service).abandonedTicketsBetween(any(), any(), isNull());
        doReturn(List.of()).when(service).refundsBetween(any(), any());
        try (MockedConstruction<FinancialState> built = mockConstruction(FinancialState.class,
                (mockState, ctx) -> when(mockState.computeSignature()).thenReturn("sig1"))) {
            FinancialState z = service.emitZ(StateScope.STORE, PeriodKind.DAY, null, bound, true, null);
            assertEquals(1L, z.sequence);
            assertEquals(first, z.fromInstant);
            assertEquals(bound, z.toInstant);
            assertEquals("", z.previousSignature);
            assertEquals("sig1", z.signature);
            assertTrue(z.automatic);
            assertEquals(0, z.lateTicketCount);
            verify(z, times(1)).persist();
            verify(service, never()).lateTicketsBefore(any(), any(), any());
        }
    }

    /**
     * A chained Z opens where the previous closed, takes the next sequence and the
     * previous signature, and sweeps in the tickets that arrived late.
     */
    @Test
    void emitZChainsOnThePreviousZ() {
        FinancialStateEmissionService service = newService();
        LocalDateTime prevTo = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime bound = LocalDateTime.of(2026, 1, 2, 0, 0);
        FinancialState last = existingZ(4, prevTo, "prevSig");
        doReturn(last).when(service).lockedLastZ(StateScope.STORE, PeriodKind.DAY, null);
        doReturn(List.of()).when(service).closedTicketsBetween(any(), any(), isNull());
        doReturn(List.of()).when(service).abandonedTicketsBetween(any(), any(), isNull());
        doReturn(List.of(new Ticket(), new Ticket())).when(service).lateTicketsBefore(eq(prevTo), eq(prevTo), isNull());
        doReturn(List.of()).when(service).refundsBetween(any(), any());
        try (MockedConstruction<FinancialState> built = mockConstruction(FinancialState.class,
                (mockState, ctx) -> when(mockState.computeSignature()).thenReturn("sig5"))) {
            FinancialState z = service.emitZ(StateScope.STORE, PeriodKind.DAY, null, bound, true, null);
            assertEquals(5L, z.sequence);
            assertEquals(prevTo, z.fromInstant);
            assertEquals("prevSig", z.previousSignature);
            assertEquals(2, z.lateTicketCount);
            verify(z, times(1)).persist();
        }
    }

    /**
     * A second request on a bound already emitted returns the existing Z with its
     * print count raised, never a new document (idempotence).
     */
    @Test
    void emitZReprintsAnAlreadyEmittedBound() {
        FinancialStateEmissionService service = newService();
        LocalDateTime bound = LocalDateTime.of(2026, 1, 2, 0, 0);
        FinancialState last = existingZ(5, bound, "sig5");
        last.printCount = 1;
        doReturn(last).when(service).lockedLastZ(StateScope.STORE, PeriodKind.DAY, null);
        FinancialState z = service.emitZ(StateScope.STORE, PeriodKind.DAY, null, bound, false, null);
        assertSame(last, z);
        assertEquals(2, z.printCount);
        verify(service, never()).closedTicketsBetween(any(), any(), any());
    }

    /**
     * A store week Z carries the R2 control: the sum of the net turnovers of the
     * day Z the week contains, and the variance against its own net turnover.
     */
    @Test
    void emitZFillsTheWeekControl() {
        FinancialStateEmissionService service = newService();
        LocalDateTime bound = LocalDateTime.of(2026, 1, 5, 0, 0);
        doReturn(null).when(service).lockedLastZ(StateScope.STORE, PeriodKind.WEEK, null);
        doReturn(LocalDateTime.of(2026, 1, 1, 0, 0)).when(service).firstTicketInstant();
        doReturn(List.of()).when(service).closedTicketsBetween(any(), any(), isNull());
        doReturn(List.of()).when(service).abandonedTicketsBetween(any(), any(), isNull());
        doReturn(List.of()).when(service).refundsBetween(any(), any());
        FinancialState d1 = new FinancialState();
        d1.netTurnover = new BigDecimal("30.0000");
        FinancialState d2 = new FinancialState();
        d2.netTurnover = new BigDecimal("20.0000");
        doReturn(List.of(d1, d2)).when(service).lowerRankZ(eq(PeriodKind.DAY), any(), any());
        doAnswer(inv -> {
            FinancialState s = inv.getArgument(0);
            s.netTurnover = new BigDecimal("55.0000");
            return null;
        }).when(service.calculator).fill(any(), any(), any(), any());
        try (MockedConstruction<FinancialState> built = mockConstruction(FinancialState.class,
                (mockState, ctx) -> when(mockState.computeSignature()).thenReturn("w"))) {
            FinancialState z = service.emitZ(StateScope.STORE, PeriodKind.WEEK, null, bound, true, null);
            assertEquals(new BigDecimal("50.0000"), z.controlTotal);
            assertEquals(new BigDecimal("5.0000"), z.controlVariance);
        }
    }

    /**
     * A store month Z's control sums the whole weeks it contains and adds the days
     * of the week its boundary cuts, a day inside a whole week counting once (R2).
     */
    @Test
    void emitZFillsTheMonthControlWithCutWeeks() {
        FinancialStateEmissionService service = newService();
        LocalDateTime bound = LocalDateTime.of(2026, 2, 1, 0, 0);
        doReturn(null).when(service).lockedLastZ(StateScope.STORE, PeriodKind.MONTH, null);
        doReturn(LocalDateTime.of(2026, 1, 1, 0, 0)).when(service).firstTicketInstant();
        doReturn(List.of()).when(service).closedTicketsBetween(any(), any(), isNull());
        doReturn(List.of()).when(service).abandonedTicketsBetween(any(), any(), isNull());
        doReturn(List.of()).when(service).refundsBetween(any(), any());
        FinancialState week = new FinancialState();
        week.fromInstant = LocalDateTime.of(2026, 1, 5, 0, 0);
        week.toInstant = LocalDateTime.of(2026, 1, 12, 0, 0);
        week.netTurnover = new BigDecimal("100.0000");
        FinancialState emptyWeek = new FinancialState();
        emptyWeek.fromInstant = LocalDateTime.of(2026, 1, 12, 0, 0);
        emptyWeek.toInstant = LocalDateTime.of(2026, 1, 19, 0, 0);
        emptyWeek.netTurnover = null;
        FinancialState dayInWeek = new FinancialState();
        dayInWeek.fromInstant = LocalDateTime.of(2026, 1, 6, 0, 0);
        dayInWeek.toInstant = LocalDateTime.of(2026, 1, 7, 0, 0);
        dayInWeek.netTurnover = new BigDecimal("15.0000");
        FinancialState dayStraddle = new FinancialState();
        dayStraddle.fromInstant = LocalDateTime.of(2026, 1, 11, 0, 0);
        dayStraddle.toInstant = LocalDateTime.of(2026, 1, 13, 0, 0);
        dayStraddle.netTurnover = new BigDecimal("5.0000");
        FinancialState dayNull = new FinancialState();
        dayNull.fromInstant = LocalDateTime.of(2026, 1, 1, 0, 0);
        dayNull.toInstant = LocalDateTime.of(2026, 1, 2, 0, 0);
        dayNull.netTurnover = null;
        doReturn(List.of(week, emptyWeek)).when(service).lowerRankZ(eq(PeriodKind.WEEK), any(), any());
        doReturn(List.of(dayInWeek, dayStraddle, dayNull)).when(service).lowerRankZ(eq(PeriodKind.DAY), any(), any());
        doAnswer(inv -> {
            FinancialState s = inv.getArgument(0);
            s.netTurnover = new BigDecimal("105.0000");
            return null;
        }).when(service.calculator).fill(any(), any(), any(), any());
        try (MockedConstruction<FinancialState> built = mockConstruction(FinancialState.class,
                (mockState, ctx) -> when(mockState.computeSignature()).thenReturn("m"))) {
            FinancialState z = service.emitZ(StateScope.STORE, PeriodKind.MONTH, null, bound, true, null);
            assertEquals(new BigDecimal("105.0000"), z.controlTotal);
            assertEquals(new BigDecimal("0.0000"), z.controlVariance);
        }
    }

    /**
     * An X reads the open interval from the last Z's bound to now, sequence zero,
     * and is never persisted.
     */
    @Test
    void readXComputesTheOpenIntervalWithoutPersisting() {
        FinancialStateEmissionService service = newService();
        LocalDateTime prevTo = LocalDateTime.of(2026, 1, 1, 0, 0);
        FinancialState last = existingZ(3, prevTo, "sig3");
        doReturn(last).when(service).lastZ(StateScope.STORE, PeriodKind.DAY, null);
        doReturn(List.of()).when(service).closedTicketsBetween(any(), any(), isNull());
        doReturn(List.of()).when(service).abandonedTicketsBetween(any(), any(), isNull());
        doReturn(List.of()).when(service).lateTicketsBefore(any(), any(), isNull());
        doReturn(List.of()).when(service).refundsBetween(any(), any());
        try (MockedConstruction<FinancialState> built = mockConstruction(FinancialState.class,
                (mockState, ctx) -> when(mockState.computeSignature()).thenReturn("x"))) {
            FinancialState x = service.readX(StateScope.STORE, PeriodKind.DAY, null);
            assertEquals(StateType.X, x.stateType);
            assertEquals(0L, x.sequence);
            assertEquals(prevTo, x.fromInstant);
            assertEquals("sig3", x.previousSignature);
            verify(x, never()).persist();
        }
    }

    /**
     * An X on an empty chain opens at the first ticket instant, and at now when the
     * store has no ticket at all.
     */
    @Test
    void readXFallsBackToFirstTicketThenNow() {
        FinancialStateEmissionService service = newService();
        doReturn(null).when(service).lastZ(StateScope.STORE, PeriodKind.DAY, null);
        doReturn(null).when(service).firstTicketInstant();
        doReturn(List.of()).when(service).closedTicketsBetween(any(), any(), isNull());
        doReturn(List.of()).when(service).abandonedTicketsBetween(any(), any(), isNull());
        doReturn(List.of()).when(service).refundsBetween(any(), any());
        try (MockedConstruction<FinancialState> built = mockConstruction(FinancialState.class,
                (mockState, ctx) -> when(mockState.computeSignature()).thenReturn("x"))) {
            FinancialState x = service.readX(StateScope.STORE, PeriodKind.DAY, null);
            assertEquals("", x.previousSignature);
            assertEquals(x.fromInstant, x.toInstant);
        }
    }

    /**
     * The end-of-period emission is a no-op when the automatic emission is
     * disarmed.
     */
    @Test
    void emitEndOfPeriodDoesNothingWhenDisarmed() {
        FinancialStateEmissionService service = newService();
        when(service.settings.financialAutoEmitEnabled()).thenReturn(false);
        service.emitEndOfPeriod(LocalDate.of(2026, 1, 1));
        verify(service, never()).emitZ(any(), any(), any(), any(), anyBoolean(), any());
    }

    /**
     * On a fixed float, a worked day emits the store day Z and one day Z per
     * cashier who sold, and no week or month Z unless the day closes them.
     */
    @Test
    void emitEndOfPeriodFixedFloatEmitsDayAndCashierDay() {
        FinancialStateEmissionService service = newService();
        LocalDate day = LocalDate.of(2026, 1, 6);
        LocalDateTime bound = LocalDateTime.of(2026, 1, 7, 0, 0);
        when(service.settings.financialAutoEmitEnabled()).thenReturn(true);
        when(service.settings.floatingFloat()).thenReturn(false);
        doReturn(true).when(service).isWorked(day);
        doReturn(false).when(service).isLastWorkedDayOfWeek(day);
        doReturn(false).when(service).isLastWorkedDayOfMonth(day);
        Employee alice = new Employee();
        doReturn(List.of(alice)).when(service).cashiersBetween(day.atStartOfDay(), bound);
        doReturn(null).when(service).emitZ(any(), any(), any(), any(), anyBoolean(), any());
        service.emitEndOfPeriod(day);
        verify(service).emitZ(StateScope.STORE, PeriodKind.DAY, null, bound, true, null);
        verify(service).emitZ(StateScope.CASHIER, PeriodKind.DAY, alice, bound, true, null);
        verify(service, never()).emitZ(eq(StateScope.STORE), eq(PeriodKind.WEEK), any(), any(), anyBoolean(), any());
    }

    /**
     * On a floating float, the last worked day of the week emits the store week Z
     * and one week Z per cashier, and the day additionally closes the month when it
     * is the month's last worked day.
     */
    @Test
    void emitEndOfPeriodFloatingEmitsWeekAndMonth() {
        FinancialStateEmissionService service = newService();
        LocalDate day = LocalDate.of(2026, 1, 31);
        LocalDateTime bound = LocalDateTime.of(2026, 2, 1, 0, 0);
        when(service.settings.financialAutoEmitEnabled()).thenReturn(true);
        when(service.settings.floatingFloat()).thenReturn(true);
        doReturn(true).when(service).isWorked(day);
        doReturn(true).when(service).isLastWorkedDayOfWeek(day);
        doReturn(true).when(service).isLastWorkedDayOfMonth(day);
        Employee bob = new Employee();
        doReturn(List.of(bob)).when(service).cashiersBetween(any(), any());
        doReturn(null).when(service).emitZ(any(), any(), any(), any(), anyBoolean(), any());
        service.emitEndOfPeriod(day);
        verify(service).emitZ(StateScope.STORE, PeriodKind.DAY, null, bound, true, null);
        verify(service).emitZ(StateScope.STORE, PeriodKind.WEEK, null, bound, true, null);
        verify(service).emitZ(StateScope.STORE, PeriodKind.MONTH, null, bound, true, null);
        verify(service).emitZ(StateScope.CASHIER, PeriodKind.WEEK, bob, bound, true, null);
        verify(service).emitZ(StateScope.CASHIER, PeriodKind.MONTH, bob, bound, true, null);
        verify(service, never()).emitZ(eq(StateScope.CASHIER), eq(PeriodKind.DAY), any(), any(), anyBoolean(), any());
    }

    /**
     * {@code isWorked} is false on a closed weekday, false on a closed date, false
     * on a day whose sales net to nothing, and true otherwise (R6).
     */
    @Test
    void isWorkedCoversItsThreeReasonsToClose() {
        FinancialStateEmissionService service = newService();
        when(service.settings.financialClosedWeekdays()).thenReturn(Set.of(DayOfWeek.SUNDAY));
        when(service.settings.financialClosedDates()).thenReturn(Set.of(LocalDate.of(2026, 7, 14)));
        assertFalse(service.isWorked(LocalDate.of(2026, 1, 4)));
        assertFalse(service.isWorked(LocalDate.of(2026, 7, 14)));
        doReturn(false).when(service).hasPositiveSales(LocalDate.of(2026, 1, 5));
        assertFalse(service.isWorked(LocalDate.of(2026, 1, 5)));
        doReturn(true).when(service).hasPositiveSales(LocalDate.of(2026, 1, 6));
        assertTrue(service.isWorked(LocalDate.of(2026, 1, 6)));
    }

    /**
     * {@code isLastWorkedDayOfWeek} is false when the day is not worked, false when
     * a later day of the week is worked, and true when none is (R6).
     */
    @Test
    void isLastWorkedDayOfWeekScansToSunday() {
        FinancialStateEmissionService service = newService();
        doReturn(false).when(service).isWorked(LocalDate.of(2026, 1, 7));
        assertFalse(service.isLastWorkedDayOfWeek(LocalDate.of(2026, 1, 7)));
        FinancialStateEmissionService laterWorked = newService();
        doReturn(true).when(laterWorked).isWorked(LocalDate.of(2026, 1, 7));
        doReturn(false).when(laterWorked).isWorked(LocalDate.of(2026, 1, 8));
        doReturn(true).when(laterWorked).isWorked(LocalDate.of(2026, 1, 9));
        doReturn(false).when(laterWorked).isWorked(LocalDate.of(2026, 1, 10));
        doReturn(false).when(laterWorked).isWorked(LocalDate.of(2026, 1, 11));
        assertFalse(laterWorked.isLastWorkedDayOfWeek(LocalDate.of(2026, 1, 7)));
        FinancialStateEmissionService lastWorked = newService();
        doReturn(true).when(lastWorked).isWorked(LocalDate.of(2026, 1, 9));
        doReturn(false).when(lastWorked).isWorked(LocalDate.of(2026, 1, 10));
        doReturn(false).when(lastWorked).isWorked(LocalDate.of(2026, 1, 11));
        assertTrue(lastWorked.isLastWorkedDayOfWeek(LocalDate.of(2026, 1, 9)));
    }

    /**
     * {@code isLastWorkedDayOfMonth} is false when the day is not worked, false when
     * a later day of the month is worked, and true when none is (R6).
     */
    @Test
    void isLastWorkedDayOfMonthScansToMonthEnd() {
        FinancialStateEmissionService service = newService();
        doReturn(false).when(service).isWorked(LocalDate.of(2026, 1, 15));
        assertFalse(service.isLastWorkedDayOfMonth(LocalDate.of(2026, 1, 15)));
        FinancialStateEmissionService laterWorked = newService();
        doReturn(true).when(laterWorked).isWorked(LocalDate.of(2026, 1, 30));
        doReturn(true).when(laterWorked).isWorked(LocalDate.of(2026, 1, 31));
        assertFalse(laterWorked.isLastWorkedDayOfMonth(LocalDate.of(2026, 1, 30)));
        FinancialStateEmissionService lastWorked = newService();
        doReturn(true).when(lastWorked).isWorked(LocalDate.of(2026, 1, 30));
        doReturn(false).when(lastWorked).isWorked(LocalDate.of(2026, 1, 31));
        assertTrue(lastWorked.isLastWorkedDayOfMonth(LocalDate.of(2026, 1, 30)));
    }

    /**
     * A cashier-scope Z carries no control (the scope guard) and no cashier
     * breakdown (the scope guard again); its chain is the cashier's own.
     */
    @Test
    void emitZForACashierCarriesNoControl() {
        FinancialStateEmissionService service = newService();
        Employee cashier = new Employee();
        LocalDateTime bound = LocalDateTime.of(2026, 1, 2, 0, 0);
        doReturn(null).when(service).lockedLastZ(StateScope.CASHIER, PeriodKind.DAY, cashier);
        doReturn(LocalDateTime.of(2026, 1, 1, 0, 0)).when(service).firstTicketInstant();
        doReturn(List.of()).when(service).closedTicketsBetween(any(), any(), any());
        doReturn(List.of()).when(service).abandonedTicketsBetween(any(), any(), any());
        doReturn(List.of()).when(service).refundsBetween(any(), any());
        try (MockedConstruction<FinancialState> built = mockConstruction(FinancialState.class,
                (mockState, ctx) -> when(mockState.computeSignature()).thenReturn("c"))) {
            FinancialState z = service.emitZ(StateScope.CASHIER, PeriodKind.DAY, cashier, bound, true, null);
            assertNull(z.controlTotal);
        }
    }

    /**
     * A store day Z on a floating float drops the cashier breakdown (R3's floating
     * exception), and a first emission without any ticket opens at the bound.
     */
    @Test
    void emitZStoreDayFloatingOpensAtBoundWithoutTickets() {
        FinancialStateEmissionService service = newService();
        when(service.settings.floatingFloat()).thenReturn(true);
        LocalDateTime bound = LocalDateTime.of(2026, 1, 2, 0, 0);
        doReturn(null).when(service).lockedLastZ(StateScope.STORE, PeriodKind.DAY, null);
        doReturn(null).when(service).firstTicketInstant();
        doReturn(List.of()).when(service).closedTicketsBetween(any(), any(), isNull());
        doReturn(List.of()).when(service).abandonedTicketsBetween(any(), any(), isNull());
        doReturn(List.of()).when(service).refundsBetween(any(), any());
        try (MockedConstruction<FinancialState> built = mockConstruction(FinancialState.class,
                (mockState, ctx) -> when(mockState.computeSignature()).thenReturn("d"))) {
            FinancialState z = service.emitZ(StateScope.STORE, PeriodKind.DAY, null, bound, true, null);
            assertEquals(bound, z.fromInstant);
        }
    }

    /**
     * A previous Z with no signature chains to an empty previous signature, for
     * both the emitted Z and the read X.
     */
    @Test
    void emitZAndReadXTreatNullPreviousSignatureAsEmpty() {
        FinancialStateEmissionService service = newService();
        LocalDateTime prevTo = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime bound = LocalDateTime.of(2026, 1, 2, 0, 0);
        FinancialState last = existingZ(2, prevTo, null);
        doReturn(last).when(service).lockedLastZ(StateScope.STORE, PeriodKind.DAY, null);
        doReturn(last).when(service).lastZ(StateScope.STORE, PeriodKind.DAY, null);
        doReturn(List.of()).when(service).closedTicketsBetween(any(), any(), isNull());
        doReturn(List.of()).when(service).abandonedTicketsBetween(any(), any(), isNull());
        doReturn(List.of()).when(service).lateTicketsBefore(any(), any(), isNull());
        doReturn(List.of()).when(service).refundsBetween(any(), any());
        try (MockedConstruction<FinancialState> built = mockConstruction(FinancialState.class,
                (mockState, ctx) -> when(mockState.computeSignature()).thenReturn("s"))) {
            FinancialState z = service.emitZ(StateScope.STORE, PeriodKind.DAY, null, bound, true, null);
            assertEquals("", z.previousSignature);
            FinancialState x = service.readX(StateScope.STORE, PeriodKind.DAY, null);
            assertEquals("", x.previousSignature);
        }
    }

    /**
     * A non-worked day emits nothing, neither the store day nor a cashier day, even
     * with automatic emission armed (R6).
     */
    @Test
    void emitEndOfPeriodEmitsNothingOnANonWorkedDay() {
        FinancialStateEmissionService service = newService();
        LocalDate day = LocalDate.of(2026, 1, 4);
        when(service.settings.financialAutoEmitEnabled()).thenReturn(true);
        when(service.settings.floatingFloat()).thenReturn(false);
        doReturn(false).when(service).isWorked(day);
        doReturn(false).when(service).isLastWorkedDayOfWeek(day);
        doReturn(false).when(service).isLastWorkedDayOfMonth(day);
        doReturn(null).when(service).emitZ(any(), any(), any(), any(), anyBoolean(), any());
        service.emitEndOfPeriod(day);
        verify(service, never()).emitZ(any(), any(), any(), any(), anyBoolean(), any());
    }

    /**
     * A floating float on a day that closes neither week nor month emits only the
     * store day Z, no week or month and no cashier Z.
     */
    @Test
    void emitEndOfPeriodFloatingMidWeekEmitsOnlyTheStoreDay() {
        FinancialStateEmissionService service = newService();
        LocalDate day = LocalDate.of(2026, 1, 7);
        LocalDateTime bound = LocalDateTime.of(2026, 1, 8, 0, 0);
        when(service.settings.financialAutoEmitEnabled()).thenReturn(true);
        when(service.settings.floatingFloat()).thenReturn(true);
        doReturn(true).when(service).isWorked(day);
        doReturn(false).when(service).isLastWorkedDayOfWeek(day);
        doReturn(false).when(service).isLastWorkedDayOfMonth(day);
        doReturn(null).when(service).emitZ(any(), any(), any(), any(), anyBoolean(), any());
        service.emitEndOfPeriod(day);
        verify(service).emitZ(StateScope.STORE, PeriodKind.DAY, null, bound, true, null);
        verify(service, never()).emitZ(eq(StateScope.CASHIER), any(), any(), any(), anyBoolean(), any());
        verify(service, never()).emitZ(eq(StateScope.STORE), eq(PeriodKind.WEEK), any(), any(), anyBoolean(), any());
    }

    /**
     * Builds a Panache query whose {@code withLock} chains back to itself and whose
     * {@code firstResult} resolves to a value.
     *
     * @param result the value
     * @param <T> the queried type
     * @return the configured query mock
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> lockableQuery(T result) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.withLock(any())).thenReturn(query);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * The chain finders build the right query for a null cashier (store) and for a
     * named cashier, under a lock for the emission and without one for the read.
     */
    @Test
    void chainFindersBranchOnTheCashier() {
        FinancialStateEmissionService service = newService();
        Employee cashier = new Employee();
        FinancialState locked = new FinancialState();
        FinancialState read = new FinancialState();
        PanacheQuery<FinancialState> lockedQuery = lockableQuery(locked);
        PanacheQuery<FinancialState> readQuery = lockableQuery(read);
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            statics.when(() -> PanacheEntityBase.find(
                    org.mockito.ArgumentMatchers.contains("cashier is null"), any(Object[].class)))
                    .thenReturn(lockedQuery);
            statics.when(() -> PanacheEntityBase.find(
                    org.mockito.ArgumentMatchers.contains("cashier = ?4"), any(Object[].class)))
                    .thenReturn(readQuery);
            assertSame(locked, service.lockedLastZ(StateScope.STORE, PeriodKind.DAY, null));
            assertSame(read, service.lockedLastZ(StateScope.STORE, PeriodKind.DAY, cashier));
            assertSame(locked, service.lastZ(StateScope.STORE, PeriodKind.DAY, null));
            assertSame(read, service.lastZ(StateScope.STORE, PeriodKind.DAY, cashier));
        }
    }

    /**
     * The ticket and refund finders branch on the cashier and delegate to Panache
     * list/count/find, and the first-ticket finder returns its closing instant or
     * null.
     */
    @Test
    void panacheWrappersDelegateAndBranch() {
        FinancialStateEmissionService service = newService();
        Employee cashier = new Employee();
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 1, 2, 0, 0);
        Ticket closedTicket = new Ticket();
        closedTicket.closingDate = from;
        PanacheQuery<Ticket> firstQuery = lockableQuery(closedTicket);
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            statics.when(() -> PanacheEntityBase.list(any(String.class), any(Object[].class))).thenReturn(List.of());
            statics.when(() -> PanacheEntityBase.count(any(String.class), any(Object[].class))).thenReturn(1L);
            statics.when(() -> PanacheEntityBase.find(any(String.class), any(Object[].class)))
                    .thenReturn(firstQuery);
            assertTrue(service.closedTicketsBetween(from, to, null).isEmpty());
            assertTrue(service.closedTicketsBetween(from, to, cashier).isEmpty());
            assertTrue(service.abandonedTicketsBetween(from, to, null).isEmpty());
            assertTrue(service.abandonedTicketsBetween(from, to, cashier).isEmpty());
            assertTrue(service.lateTicketsBefore(from, from, null).isEmpty());
            assertTrue(service.lateTicketsBefore(from, from, cashier).isEmpty());
            assertTrue(service.refundsBetween(from, to).isEmpty());
            assertTrue(service.lowerRankZ(PeriodKind.DAY, from, to).isEmpty());
            assertTrue(service.hasPositiveSales(LocalDate.of(2026, 1, 1)));
            assertEquals(from, service.firstTicketInstant());
        }
    }

    /**
     * {@code hasPositiveSales} is false when the day closed no positive ticket.
     */
    @Test
    void hasPositiveSalesIsFalseWhenNoPositiveTicket() {
        FinancialStateEmissionService service = newService();
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            statics.when(() -> PanacheEntityBase.count(any(String.class), any(Object[].class))).thenReturn(0L);
            assertFalse(service.hasPositiveSales(LocalDate.of(2026, 1, 1)));
        }
    }

    /**
     * The first-ticket finder returns null when the store has closed no ticket.
     */
    @Test
    void firstTicketInstantIsNullWhenNoTicket() {
        FinancialStateEmissionService service = newService();
        PanacheQuery<Ticket> emptyQuery = lockableQuery(null);
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            statics.when(() -> PanacheEntityBase.find(any(String.class), any(Object[].class)))
                    .thenReturn(emptyQuery);
            assertNull(service.firstTicketInstant());
        }
    }

    /**
     * The cashier finder delegates to a JPQL distinct query through the entity
     * manager.
     */
    @Test
    @SuppressWarnings("unchecked")
    void cashiersBetweenRunsADistinctQuery() {
        FinancialStateEmissionService service = newService();
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 1, 2, 0, 0);
        Employee alice = new Employee();
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Employee> query = mock(TypedQuery.class);
        when(em.createQuery(any(String.class), eq(Employee.class))).thenReturn(query);
        when(query.setParameter(any(String.class), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(alice));
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            statics.when(PanacheEntityBase::getEntityManager).thenReturn(em);
            assertEquals(List.of(alice), service.cashiersBetween(from, to));
        }
    }
}
