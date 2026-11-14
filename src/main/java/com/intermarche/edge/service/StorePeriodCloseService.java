package com.intermarche.edge.service;

import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.store.StorePeriodClose;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the CONSOLIDATED CLOSE of the point of sale at the end of its period
 * (BO-03-12-02): the actions the back office administers in
 * {@code period.pdv-actions}, chained in their order, under the opening
 * calendar of {@code financial.closed-weekdays} and
 * {@code financial.closed-dates}.
 *
 * <p>The automation is the end-of-period loop's: when the period ends, the
 * loop asks this service to consolidate the day. On a day the calendar closes,
 * nothing is chained and the row says so. On an open day, each action leaves
 * one line of outcome: the registers still to close (their orders are left by
 * the closing window that precedes), the theoretical balance of the safe to
 * count, the financial states emitted, the bank deposit to prepare.
 */
@ApplicationScoped
public class StorePeriodCloseService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(StorePeriodCloseService.class);

    /** The administered actions and calendar. */
    @Inject
    PosSettingsService posSettingsService;

    /** The safe, whose balance the count starts from. */
    @Inject
    SafeService safeService;

    /** The electronic journal. */
    @Inject
    TechnicalEventService technicalEventService;

    /**
     * Consolidates one day, creating its row or rewriting it when the day is
     * consolidated again.
     *
     * @param day the day whose period ends
     * @return the consolidated close
     */
    @Transactional
    public StorePeriodClose consolidate(LocalDate day) {
        LOGGER.info("Entering method consolidate with day: " + day);
        StorePeriodClose close = StorePeriodClose.findByDate(day);
        boolean created = close == null;
        if (created) {
            close = new StorePeriodClose();
            close.closeDate = day;
        }
        close.closedAt = LocalDateTime.now();
        if (closedDay(day)) {
            close.status = StorePeriodClose.Status.CLOSED_DAY;
            close.openRegisters = 0;
            close.ticketCount = 0;
            close.totalIncludingTax = BigDecimal.ZERO;
            close.actions = null;
        } else {
            close.status = StorePeriodClose.Status.CLOSED;
            close.openRegisters = openRegisters();
            List<Ticket> tickets = Ticket.list("status = ?1 and closingDate >= ?2 and closingDate < ?3",
                    Ticket.TicketStatus.CLOSED, day.atStartOfDay(), day.plusDays(1).atStartOfDay());
            BigDecimal total = BigDecimal.ZERO;
            for (Ticket ticket : tickets) {
                total = total.add(ticket.totalIncludingTax == null ? BigDecimal.ZERO : ticket.totalIncludingTax);
            }
            close.ticketCount = tickets.size();
            close.totalIncludingTax = total;
            close.actions = actions(close.openRegisters);
        }
        if (created) {
            close.persist();
        }
        technicalEventService.log(TechnicalEvent.EventType.SESSION_CLOSED,
                "Clôture consolidée du point de vente du " + day + " : " + close.status);
        LOGGER.info("Exiting method consolidate");
        return close;
    }

    /**
     * Chains the administered actions and writes their outcome.
     *
     * @param openRegisters the registers still open
     * @return one {@code ACTION: outcome} per line, null when none is administered
     */
    String actions(int openRegisters) {
        List<String> lines = new ArrayList<>();
        for (PosSettingsService.PeriodAction action : posSettingsService.periodPdvActions()) {
            String outcome = switch (action) {
                case CLOSE_REGISTERS -> openRegisters + " caisse(s) encore ouverte(s), fermeture ordonnée";
                case SAFE_COUNT -> "solde théorique du coffre à compter " + safeService.balance().toPlainString();
                case Z_REPORT -> "états financiers de fin de période émis";
                case BANK_DEPOSIT -> "remise en banque à préparer";
            };
            lines.add(action.name() + ": " + outcome);
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    /**
     * Counts the registers still open, the deactivated ones left out
     * (BO-03-09-01).
     *
     * @return the open registers
     */
    int openRegisters() {
        int open = 0;
        for (CashSession session : CashSession.<CashSession>list("status", CashSession.SessionStatus.OPEN)) {
            if (!CheckoutPoint.isDeactivated(session.terminalId)) {
                open++;
            }
        }
        return open;
    }

    /**
     * Tells whether the opening calendar closes the day.
     *
     * @param day the day
     * @return true on a closed weekday or a closed date
     */
    boolean closedDay(LocalDate day) {
        return posSettingsService.financialClosedWeekdays().contains(day.getDayOfWeek())
                || posSettingsService.financialClosedDates().contains(day);
    }

    /**
     * The latest consolidated closes, for the end-of-day screen.
     *
     * @param count how many
     * @return the closes, the most recent first
     */
    public List<StorePeriodClose> latest(int count) {
        LOGGER.info("Entering method latest with count: " + count);
        LOGGER.info("Exiting method latest");
        return StorePeriodClose.latest(count);
    }
}
