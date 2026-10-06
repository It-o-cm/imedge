package com.intermarche.edge.service;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.Refund;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.session.FinancialState;
import com.intermarche.edge.domain.session.FinancialState.PeriodKind;
import com.intermarche.edge.domain.session.FinancialState.StateScope;
import com.intermarche.edge.domain.session.FinancialState.StateType;
import com.intermarche.edge.domain.util.DateTimeProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Emits the store node's financial states (§4): it allocates the Z sequence under
 * a pessimistic lock so two emissions can never take the same number, keeps the
 * emission idempotent (one Z per chain and bound, a second request reprinting the
 * first), chains each Z to the previous by signature, sweeps in the tickets that
 * arrived late, fills in the R2 control totals, and reads a throw-away X on the
 * open interval.
 *
 * <p>There is NO counter entity: the counter IS the chain of Z documents,
 * identified by the triple (scope, period, cashier). Everything R3 and R4 ask for
 * falls out of that chain without a special case.
 */
@ApplicationScoped
public class FinancialStateEmissionService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(FinancialStateEmissionService.class);

    /** The pure totals calculator. */
    @Inject FinancialStateCalculator calculator;

    /** The administered parameters. */
    @Inject PosSettingsService settings;

    /**
     * Emits — or reprints — the Z of a chain at a period bound (§4).
     *
     * <p>The chain's last Z is read under a pessimistic write lock so the sequence
     * is allocated without a race. When that bound has already been emitted the
     * existing Z is returned with its print count raised, never a second document.
     *
     * @param scope the counter holder
     * @param periodKind the period bound
     * @param cashier the cashier of a cashier-scope chain, or null
     * @param bound the exclusive upper bound of the interval
     * @param automatic whether the emission is driven by the end of period
     * @param emittedBy the operator of a manual emission, or null
     * @return the emitted or reprinted Z
     */
    @Transactional
    public FinancialState emitZ(StateScope scope, PeriodKind periodKind, Employee cashier,
                                LocalDateTime bound, boolean automatic, Employee emittedBy) {
        LOGGER.info("Entering method emitZ");
        FinancialState last = lockedLastZ(scope, periodKind, cashier);
        if (last != null && !last.toInstant.isBefore(bound)) {
            last.printCount++;
            LOGGER.info("Exiting method emitZ: idempotent reprint");
            return last;
        }
        LocalDateTime from = last != null ? last.toInstant : firstTicketInstant();
        if (from == null) {
            from = bound;
        }
        FinancialState state = new FinancialState();
        state.stateType = StateType.Z;
        state.scope = scope;
        state.periodKind = periodKind;
        state.cashier = cashier;
        state.sequence = last != null ? last.sequence + 1 : 1;
        state.previousSignature = last != null && last.signature != null ? last.signature : "";
        state.fromInstant = from;
        state.toInstant = bound;
        state.emittedAt = DateTimeProvider.now();
        state.emittedBy = emittedBy;
        state.automatic = automatic;
        state.floatMode = settings.floatMode();
        LocalDateTime lastEmittedAt = last != null ? last.emittedAt : null;
        populate(state, cashier, from, bound, lastEmittedAt);
        applyControl(state);
        state.signature = state.computeSignature();
        state.persist();
        LOGGER.info("Exiting method emitZ: emitted sequence " + state.sequence);
        return state;
    }

    /**
     * Reads a throw-away X over the chain's open interval (§1, R18): from where the
     * last Z of the chain closed (the opening, the Monday, the first of the month)
     * to now. Never persisted.
     *
     * @param scope the counter holder
     * @param periodKind the period bound
     * @param cashier the cashier of a cashier-scope chain, or null
     * @return the computed X
     */
    public FinancialState readX(StateScope scope, PeriodKind periodKind, Employee cashier) {
        LOGGER.info("Entering method readX");
        FinancialState last = lastZ(scope, periodKind, cashier);
        LocalDateTime now = DateTimeProvider.now();
        LocalDateTime from = last != null ? last.toInstant : firstTicketInstant();
        if (from == null) {
            from = now;
        }
        FinancialState state = new FinancialState();
        state.stateType = StateType.X;
        state.scope = scope;
        state.periodKind = periodKind;
        state.cashier = cashier;
        state.sequence = 0;
        state.previousSignature = last != null && last.signature != null ? last.signature : "";
        state.fromInstant = from;
        state.toInstant = now;
        state.emittedAt = now;
        state.automatic = false;
        state.floatMode = settings.floatMode();
        LocalDateTime lastEmittedAt = last != null ? last.emittedAt : null;
        populate(state, cashier, from, now, lastEmittedAt);
        state.signature = state.computeSignature();
        LOGGER.info("Exiting method readX");
        return state;
    }

    /**
     * Runs the end-of-period emission for a day (§4, R4), before the retention
     * sweep: the store day, week and month Z when the day carries them, then the
     * cashiers' own Z at the bound their float mode gives (R3). A no-op when the
     * automatic emission is disarmed.
     *
     * @param day the day whose period just ended
     */
    @Transactional
    public void emitEndOfPeriod(LocalDate day) {
        LOGGER.info("Entering method emitEndOfPeriod with day: " + day);
        if (!settings.financialAutoEmitEnabled()) {
            LOGGER.info("Exiting method emitEndOfPeriod: disarmed");
            return;
        }
        LocalDateTime bound = day.plusDays(1).atStartOfDay();
        boolean endOfWeek = isLastWorkedDayOfWeek(day);
        boolean endOfMonth = isLastWorkedDayOfMonth(day);
        if (isWorked(day)) {
            emitZ(StateScope.STORE, PeriodKind.DAY, null, bound, true, null);
        }
        if (endOfWeek) {
            emitZ(StateScope.STORE, PeriodKind.WEEK, null, bound, true, null);
        }
        if (endOfMonth) {
            emitZ(StateScope.STORE, PeriodKind.MONTH, null, bound, true, null);
        }
        emitCashierStates(day, bound, endOfWeek, endOfMonth);
        LOGGER.info("Exiting method emitEndOfPeriod");
    }

    /**
     * Emits the cashiers' Z at the bound R3 gives: the day in a fixed float, the
     * week and the month in a floating one. Only cashiers who sold in the window
     * get one.
     *
     * @param day the day whose period just ended
     * @param bound the exclusive interval bound
     * @param endOfWeek whether the day is the last worked day of its week
     * @param endOfMonth whether the day is the last worked day of its month
     */
    private void emitCashierStates(LocalDate day, LocalDateTime bound, boolean endOfWeek, boolean endOfMonth) {
        if (!settings.floatingFloat()) {
            if (isWorked(day)) {
                for (Employee cashier : cashiersBetween(day.atStartOfDay(), bound)) {
                    emitZ(StateScope.CASHIER, PeriodKind.DAY, cashier, bound, true, null);
                }
            }
            return;
        }
        if (endOfWeek) {
            LocalDateTime weekStart = mondayOf(day).atStartOfDay();
            for (Employee cashier : cashiersBetween(weekStart, bound)) {
                emitZ(StateScope.CASHIER, PeriodKind.WEEK, cashier, bound, true, null);
            }
        }
        if (endOfMonth) {
            LocalDateTime monthStart = day.withDayOfMonth(1).atStartOfDay();
            for (Employee cashier : cashiersBetween(monthStart, bound)) {
                emitZ(StateScope.CASHIER, PeriodKind.MONTH, cashier, bound, true, null);
            }
        }
    }

    /**
     * Gathers the interval's tickets and refunds — the closed, the abandoned and
     * the late arrivals — and lets the calculator fill the state, then records how
     * many of them arrived late.
     *
     * @param state the state being filled
     * @param cashier the cashier filter, or null for the whole store
     * @param from the inclusive interval start
     * @param to the exclusive interval end
     * @param since the previous Z's emission moment, or null for the first Z
     */
    private void populate(FinancialState state, Employee cashier, LocalDateTime from, LocalDateTime to, LocalDateTime since) {
        List<Ticket> tickets = new ArrayList<>();
        tickets.addAll(closedTicketsBetween(from, to, cashier));
        tickets.addAll(abandonedTicketsBetween(from, to, cashier));
        List<Ticket> late = since == null ? List.of() : lateTicketsBefore(from, since, cashier);
        tickets.addAll(late);
        List<Refund> refunds = refundsBetween(from, to);
        boolean cashierBreakdown = state.scope == StateScope.STORE
                && !(state.periodKind == PeriodKind.DAY && settings.floatingFloat());
        FinancialStateCalculator.Config config = new FinancialStateCalculator.Config(
                settings.financialHourlySlotMinutes(), settings.financialNomenclatureLevel(),
                settings.financialSectors(), settings.financialBestSellersCount(), cashierBreakdown);
        calculator.fill(state, tickets, refunds, config);
        state.lateTicketCount = late.size();
    }

    /**
     * Fills the R2 control total and variance on a store week or month Z: the sum
     * of the net turnovers of the lower-rank Z the period contains, and the gap
     * with this Z's own net turnover. A no-op on any other state.
     *
     * @param state the state being filled
     */
    private void applyControl(FinancialState state) {
        if (state.scope != StateScope.STORE) {
            return;
        }
        BigDecimal control;
        if (state.periodKind == PeriodKind.WEEK) {
            control = sumNet(lowerRankZ(PeriodKind.DAY, state.fromInstant, state.toInstant));
        } else if (state.periodKind == PeriodKind.MONTH) {
            control = monthControl(state.fromInstant, state.toInstant);
        } else {
            return;
        }
        state.controlTotal = control;
        state.controlVariance = state.netTurnover.subtract(control);
    }

    /**
     * The month's control total: the weeks it contains whole, plus the days of the
     * weeks the month boundary cuts (R2, R4).
     *
     * @param from the month interval start
     * @param to the month interval end
     * @return the control total
     */
    private BigDecimal monthControl(LocalDateTime from, LocalDateTime to) {
        List<FinancialState> weeks = lowerRankZ(PeriodKind.WEEK, from, to);
        BigDecimal control = sumNet(weeks);
        for (FinancialState day : lowerRankZ(PeriodKind.DAY, from, to)) {
            if (!coveredByAWeek(day, weeks)) {
                control = control.add(day.netTurnover == null ? BigDecimal.ZERO : day.netTurnover);
            }
        }
        return control;
    }

    /**
     * Whether a day Z's interval sits inside one of the whole weeks.
     *
     * @param day the day Z
     * @param weeks the whole weeks of the month
     * @return true when a week covers the day
     */
    private boolean coveredByAWeek(FinancialState day, List<FinancialState> weeks) {
        for (FinancialState week : weeks) {
            if (!day.fromInstant.isBefore(week.fromInstant) && !day.toInstant.isAfter(week.toInstant)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sums the net turnovers of a list of states, a null turnover counting zero.
     *
     * @param states the states
     * @return the summed net turnover
     */
    private BigDecimal sumNet(List<FinancialState> states) {
        BigDecimal sum = BigDecimal.ZERO;
        for (FinancialState state : states) {
            sum = sum.add(state.netTurnover == null ? BigDecimal.ZERO : state.netTurnover);
        }
        return sum;
    }

    /**
     * Whether a day is a worked day (R6): not a closed weekday, not a closed date,
     * and not a day whose sales net to nothing.
     *
     * @param day the day
     * @return true when the shop traded that day
     */
    boolean isWorked(LocalDate day) {
        if (settings.financialClosedWeekdays().contains(day.getDayOfWeek())) {
            return false;
        }
        if (settings.financialClosedDates().contains(day)) {
            return false;
        }
        return hasPositiveSales(day);
    }

    /**
     * Whether a day is the last worked day of its week (R6): worked itself, with no
     * worked day left between it and the Sunday.
     *
     * @param day the day
     * @return true when the week has no later worked day
     */
    boolean isLastWorkedDayOfWeek(LocalDate day) {
        if (!isWorked(day)) {
            return false;
        }
        LocalDate sunday = mondayOf(day).plusDays(6);
        for (LocalDate d = day.plusDays(1); !d.isAfter(sunday); d = d.plusDays(1)) {
            if (isWorked(d)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a day is the last worked day of its month (R6).
     *
     * @param day the day
     * @return true when the month has no later worked day
     */
    boolean isLastWorkedDayOfMonth(LocalDate day) {
        if (!isWorked(day)) {
            return false;
        }
        LocalDate lastOfMonth = day.withDayOfMonth(day.lengthOfMonth());
        for (LocalDate d = day.plusDays(1); !d.isAfter(lastOfMonth); d = d.plusDays(1)) {
            if (isWorked(d)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The Monday of a day's week (R5).
     *
     * @param day the day
     * @return the Monday of its week
     */
    private LocalDate mondayOf(LocalDate day) {
        return day.with(DayOfWeek.MONDAY);
    }

    /**
     * Reads the last Z of a chain under a pessimistic write lock, so its sequence
     * can be allocated without a race.
     *
     * @param scope the counter holder
     * @param periodKind the period bound
     * @param cashier the chain's cashier, or null
     * @return the last Z of the chain, or null when the chain is empty
     */
    FinancialState lockedLastZ(StateScope scope, PeriodKind periodKind, Employee cashier) {
        if (cashier == null) {
            return FinancialState.<FinancialState>find(
                    "stateType = ?1 and scope = ?2 and periodKind = ?3 and cashier is null order by sequence desc",
                    StateType.Z, scope, periodKind).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        }
        return FinancialState.<FinancialState>find(
                "stateType = ?1 and scope = ?2 and periodKind = ?3 and cashier = ?4 order by sequence desc",
                StateType.Z, scope, periodKind, cashier).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
    }

    /**
     * Reads the last Z of a chain without a lock, for a read-only X.
     *
     * @param scope the counter holder
     * @param periodKind the period bound
     * @param cashier the chain's cashier, or null
     * @return the last Z of the chain, or null when the chain is empty
     */
    FinancialState lastZ(StateScope scope, PeriodKind periodKind, Employee cashier) {
        if (cashier == null) {
            return FinancialState.<FinancialState>find(
                    "stateType = ?1 and scope = ?2 and periodKind = ?3 and cashier is null order by sequence desc",
                    StateType.Z, scope, periodKind).firstResult();
        }
        return FinancialState.<FinancialState>find(
                "stateType = ?1 and scope = ?2 and periodKind = ?3 and cashier = ?4 order by sequence desc",
                StateType.Z, scope, periodKind, cashier).firstResult();
    }

    /**
     * The lower-rank Z documents whose interval sits inside a period (R2).
     *
     * @param periodKind the lower rank
     * @param from the period start
     * @param to the period end
     * @return the matching store Z documents
     */
    List<FinancialState> lowerRankZ(PeriodKind periodKind, LocalDateTime from, LocalDateTime to) {
        return FinancialState.list(
                "stateType = ?1 and scope = ?2 and periodKind = ?3 and fromInstant >= ?4 and toInstant <= ?5",
                StateType.Z, StateScope.STORE, periodKind, from, to);
    }

    /**
     * The instant of the first closed ticket the store knows, or null when it has
     * none (§4).
     *
     * @return the earliest closing instant, or null
     */
    LocalDateTime firstTicketInstant() {
        Ticket first = Ticket.<Ticket>find(
                "status = ?1 and closingDate is not null order by closingDate asc",
                Ticket.TicketStatus.CLOSED).firstResult();
        return first == null ? null : first.closingDate;
    }

    /**
     * Whether a day carries at least one positive closed ticket (R6).
     *
     * @param day the day
     * @return true when something positive was sold
     */
    boolean hasPositiveSales(LocalDate day) {
        return Ticket.count(
                "status = ?1 and closingDate >= ?2 and closingDate < ?3 and grandTotal > 0",
                Ticket.TicketStatus.CLOSED, day.atStartOfDay(), day.plusDays(1).atStartOfDay()) > 0;
    }

    /**
     * The closed tickets of an interval, optionally of one cashier.
     *
     * @param from the interval start
     * @param to the interval end
     * @param cashier the cashier filter, or null
     * @return the closed tickets
     */
    List<Ticket> closedTicketsBetween(LocalDateTime from, LocalDateTime to, Employee cashier) {
        if (cashier == null) {
            return Ticket.list("status = ?1 and closingDate >= ?2 and closingDate < ?3",
                    Ticket.TicketStatus.CLOSED, from, to);
        }
        return Ticket.list("status = ?1 and closingDate >= ?2 and closingDate < ?3 and cashier = ?4",
                Ticket.TicketStatus.CLOSED, from, to, cashier);
    }

    /**
     * The abandoned tickets of an interval, taken on their creation date, optionally
     * of one cashier (R9).
     *
     * @param from the interval start
     * @param to the interval end
     * @param cashier the cashier filter, or null
     * @return the abandoned tickets
     */
    List<Ticket> abandonedTicketsBetween(LocalDateTime from, LocalDateTime to, Employee cashier) {
        if (cashier == null) {
            return Ticket.list("status = ?1 and creationDate >= ?2 and creationDate < ?3",
                    Ticket.TicketStatus.CANCELLED, from, to);
        }
        return Ticket.list("status = ?1 and creationDate >= ?2 and creationDate < ?3 and cashier = ?4",
                Ticket.TicketStatus.CANCELLED, from, to, cashier);
    }

    /**
     * The late arrivals (§4): closed tickets of a prior interval that reached the
     * node after the previous Z was emitted, optionally of one cashier.
     *
     * @param from the current interval start (the prior interval's end)
     * @param since the previous Z's emission moment
     * @param cashier the cashier filter, or null
     * @return the late tickets
     */
    List<Ticket> lateTicketsBefore(LocalDateTime from, LocalDateTime since, Employee cashier) {
        if (cashier == null) {
            return Ticket.list("status = ?1 and closingDate < ?2 and createdAt > ?3",
                    Ticket.TicketStatus.CLOSED, from, since);
        }
        return Ticket.list("status = ?1 and closingDate < ?2 and createdAt > ?3 and cashier = ?4",
                Ticket.TicketStatus.CLOSED, from, since, cashier);
    }

    /**
     * The refunds of an interval, taken on their creation date.
     *
     * @param from the interval start
     * @param to the interval end
     * @return the refunds
     */
    List<Refund> refundsBetween(LocalDateTime from, LocalDateTime to) {
        return Refund.list("status = ?1 and creationDate >= ?2 and creationDate < ?3",
                Refund.RefundStatus.CLOSED, from, to);
    }

    /**
     * The distinct cashiers who closed a ticket in an interval (R4.4).
     *
     * @param from the interval start
     * @param to the interval end
     * @return the cashiers who sold
     */
    List<Employee> cashiersBetween(LocalDateTime from, LocalDateTime to) {
        return Ticket.getEntityManager().createQuery(
                        "select distinct t.cashier from Ticket t where t.status = :status"
                                + " and t.closingDate >= :from and t.closingDate < :to and t.cashier is not null",
                        Employee.class)
                .setParameter("status", Ticket.TicketStatus.CLOSED)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }
}
