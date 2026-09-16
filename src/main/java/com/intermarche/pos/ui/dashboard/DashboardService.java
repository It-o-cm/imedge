package com.intermarche.pos.ui.dashboard;

import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Aggregations of the supervisor dashboard (phase 5 lot 2), computed over
 * the node's database — the consolidated store data when running on the
 * store node. Everything is "today": revenue, ticket count and average
 * basket, per-register revenue, active sessions, top sales, cancellation
 * rate and refunds.
 * <p>
 * Semantics worth knowing: "today" is the node's calendar day (midnight
 * boundary); revenue counts by CLOSING date while the cancellation rate
 * counts cancelled drafts by CREATION date — a deliberate approximation
 * (cancelled drafts have no closing date), so the rate can marginally mix
 * days around midnight. Aggregations use JPQL through the EntityManager
 * (grouping and coalesced sums are beyond Panache finders), amounts leave
 * pre-formatted (French comma) so the dashboard page stays a dumb renderer,
 * and real shrinkage (démarque) has no data source — the cancellation rate
 * is its only proxy until a loss-entry module exists.
 */
@ApplicationScoped
public class DashboardService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(DashboardService.class);

    @Inject
    EntityManager entityManager;

    /**
     * Builds the dashboard data for today.
     *
     * @return the aggregated indicators, JSON-ready
     */
    public Map<String, Object> buildData() {
        LOGGER.info("Entering method buildData");
        LocalDateTime from = LocalDate.now().atStartOfDay();
        Map<String, Object> data = new HashMap<>();

        // Revenue and ticket count of the day
        Object[] revenueRow = (Object[]) entityManager.createQuery(
                        "select coalesce(sum(t.totalIncludingTax), 0), count(t)"
                                + " from Ticket t where t.status = :status and t.closingDate >= :from")
                .setParameter("status", Ticket.TicketStatus.CLOSED)
                .setParameter("from", from)
                .getSingleResult();
        BigDecimal revenue = (BigDecimal) revenueRow[0];
        long ticketCount = (Long) revenueRow[1];
        data.put("revenue", formatAmount(revenue));
        data.put("ticketCount", ticketCount);
        data.put("averageBasket", ticketCount > 0
                ? formatAmount(revenue.divide(BigDecimal.valueOf(ticketCount), 2, RoundingMode.HALF_UP))
                : "0,00");

        // Per-register revenue
        List<Map<String, Object>> perRegister = new ArrayList<>();
        List<Object[]> registerRows = entityManager.createQuery(
                        "select t.terminalId, coalesce(sum(t.totalIncludingTax), 0), count(t)"
                                + " from Ticket t where t.status = :status and t.closingDate >= :from"
                                + " group by t.terminalId order by t.terminalId", Object[].class)
                .setParameter("status", Ticket.TicketStatus.CLOSED)
                .setParameter("from", from)
                .getResultList();
        for (Object[] row : registerRows) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("terminal", row[0]);
            entry.put("revenue", formatAmount((BigDecimal) row[1]));
            entry.put("tickets", row[2]);
            perRegister.add(entry);
        }
        data.put("perRegister", perRegister);

        // Cancellation rate of the day (cancelled over finished)
        long cancelled = entityManager.createQuery(
                        "select count(t) from Ticket t where t.status = :status and t.creationDate >= :from", Long.class)
                .setParameter("status", Ticket.TicketStatus.CANCELLED)
                .setParameter("from", from)
                .getSingleResult();
        long finished = cancelled + ticketCount;
        data.put("cancellationRate", finished > 0
                ? BigDecimal.valueOf(cancelled * 100.0 / finished).setScale(1, RoundingMode.HALF_UP).toPlainString().replace(".", ",")
                : "0,0");

        // Refunds of the day
        Object[] refundRow = (Object[]) entityManager.createQuery(
                        "select coalesce(sum(r.totalAmount), 0), count(r)"
                                + " from Refund r where r.creationDate >= :from")
                .setParameter("from", from)
                .getSingleResult();
        data.put("refundTotal", formatAmount((BigDecimal) refundRow[0]));
        data.put("refundCount", refundRow[1]);

        // Top sales of the day (positive lines only: no deposits, no round-ups)
        List<Map<String, Object>> topSales = new ArrayList<>();
        List<Object[]> topRows = entityManager.createQuery(
                        "select l.productLabel, sum(l.quantity)"
                                + " from Ticket t join t.lines l"
                                + " where t.status = :status and t.closingDate >= :from and l.totalPrice > 0"
                                + " and l.cancelled = false"
                                + " group by l.productLabel order by sum(l.quantity) desc", Object[].class)
                .setParameter("status", Ticket.TicketStatus.CLOSED)
                .setParameter("from", from)
                .setMaxResults(10)
                .getResultList();
        for (Object[] row : topRows) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("label", row[0]);
            entry.put("quantity", ((BigDecimal) row[1]).stripTrailingZeros().toPlainString().replace(".", ","));
            topSales.add(entry);
        }
        data.put("topSales", topSales);

        // Active cash sessions
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (CashSession session : CashSession.<CashSession>list("status", CashSession.SessionStatus.OPEN)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("terminal", session.terminalId);
            entry.put("number", session.sessionNumber);
            entry.put("operator", session.openingCashier != null ? session.openingCashier.getFullName() : "");
            entry.put("since", session.openingDate.format(DateTimeFormatter.ofPattern("HH:mm")));
            sessions.add(entry);
        }
        data.put("sessions", sessions);

        LOGGER.info("Exiting method buildData");
        return data;
    }

    /**
     * Formats an amount for the dashboard (2 decimals, French comma).
     *
     * @param amount the amount
     * @return the formatted amount
     */
    private String formatAmount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace(".", ",");
    }
}
