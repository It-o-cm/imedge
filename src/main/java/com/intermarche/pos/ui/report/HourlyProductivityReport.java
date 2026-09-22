package com.intermarche.pos.ui.report;

import com.intermarche.pos.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ÉTAT PRODUCTIVITÉ HORAIRE (BO-06-04-06): what the line did, slice of time by
 * slice of time — takings, share, customers, registers open, and the averages
 * that turn the three into a measure of a shift.
 *
 * <p>The slice is CHOSEN — an hour, a half-hour or a quarter (BO-06-04-06,
 * « possibilité d'afficher le détail par tranche d'une heure, d'une demi-heure,
 * d'un quart d'heure ») — because the question is not the same: the hour reads a
 * day, the quarter reads a rush.
 *
 * <p>Slices with nothing in them are NOT drawn. A shop open from eight to eight
 * would otherwise carry thirty-odd empty quarters at each end, and the chart
 * would read as a collapse rather than as a closed door.
 */
@ApplicationScoped
public class HourlyProductivityReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(HourlyProductivityReport.class);

    /** The persistence session the query runs on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "hourly-productivity";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État productivité horaire";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-04-06";
    }

    /**
     * {@inheritDoc}
     *
     * @return the sales family
     */
    @Override
    public ReportFamily family() {
        return ReportFamily.SALES;
    }

    /**
     * {@inheritDoc}
     *
     * @return the columns the requirement enumerates
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("slot", "Tranche horaire"),
                ReportColumn.amount("sales", "Montant des ventes"),
                ReportColumn.percent("share", "% des ventes"),
                ReportColumn.count("customers", "Nombre de clients"),
                ReportColumn.count("terminals", "TPV ouverts"),
                ReportColumn.amount("perTerminal", "Montant moyen par TPV"),
                ReportColumn.amount("perCustomer", "Montant moyen par client"),
                ReportColumn.count("items", "Nombre d'articles"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the takings column, the one the chart draws
     */
    @Override
    public int chartColumn() {
        return 1;
    }

    /**
     * {@inheritDoc}
     *
     * @return the label of the slice selector
     */
    @Override
    public String optionLabel() {
        return "Largeur de tranche";
    }

    /**
     * {@inheritDoc}
     *
     * @return the three slice widths the requirement names
     */
    @Override
    public List<String> optionChoices() {
        return List.of("60:Une heure", "30:Une demi-heure", "15:Un quart d'heure");
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per non-empty slice, in chronological order
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        int minutes = slice(criteria.optionOr("60"));
        List<Object[]> tickets = entityManager.createQuery(
                        "select t.closingDate, t.terminalId, t.totalIncludingTax, t.itemCount"
                                + " from Ticket t"
                                + " where t.status = :closed and t.closingDate >= :from and t.closingDate < :to"
                                + " order by t.closingDate", Object[].class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to())
                .getResultList();
        Map<Integer, BigDecimal> sales = new LinkedHashMap<>();
        Map<Integer, Long> customers = new LinkedHashMap<>();
        Map<Integer, Long> items = new LinkedHashMap<>();
        Map<Integer, Set<String>> terminals = new LinkedHashMap<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (Object[] ticket : tickets) {
            LocalDateTime when = (LocalDateTime) ticket[0];
            int slot = when.getHour() * 60 + (when.getMinute() / minutes) * minutes;
            BigDecimal amount = ticket[2] == null ? BigDecimal.ZERO : (BigDecimal) ticket[2];
            sales.merge(slot, amount, BigDecimal::add);
            customers.merge(slot, 1L, Long::sum);
            items.merge(slot, ((Number) ticket[3]).longValue(), Long::sum);
            terminals.computeIfAbsent(slot, key -> new LinkedHashSet<>())
                    .add(ticket[1] == null ? "" : String.valueOf(ticket[1]));
            grandTotal = grandTotal.add(amount);
        }
        List<Integer> slots = new ArrayList<>(sales.keySet());
        slots.sort(Integer::compareTo);
        List<List<Object>> rows = new ArrayList<>();
        for (int slot : slots) {
            BigDecimal amount = sales.get(slot);
            long people = customers.get(slot);
            long open = terminals.get(slot).size();
            rows.add(List.of(
                    name(slot, minutes),
                    amount,
                    share(amount, grandTotal),
                    people,
                    open,
                    divide(amount, BigDecimal.valueOf(open)),
                    divide(amount, BigDecimal.valueOf(people)),
                    items.get(slot)));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Reads the chosen slice width, falling back to the hour.
     *
     * @param option the submitted width in minutes
     * @return 15, 30 or 60
     */
    private int slice(String option) {
        if ("15".equals(option)) {
            return 15;
        }
        if ("30".equals(option)) {
            return 30;
        }
        return 60;
    }

    /**
     * Names a slice by its bounds.
     *
     * @param slot the slice's first minute of the day
     * @param minutes the slice width
     * @return the label, for example {@code 14:30 – 14:45}
     */
    private String name(int slot, int minutes) {
        int end = slot + minutes;
        return String.format("%02d:%02d – %02d:%02d", slot / 60, slot % 60, (end / 60) % 24, end % 60);
    }

    /**
     * Computes a slice's share of the period's takings.
     *
     * @param amount the slice's takings
     * @param total the period's takings
     * @return the share in percent, zero when the period took nothing
     */
    private BigDecimal share(BigDecimal amount, BigDecimal total) {
        if (total.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return amount.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }

    /**
     * Divides an amount, answering zero rather than failing on a zero divisor.
     *
     * <p>A slice always has at least one customer and one register — it exists
     * because a ticket landed in it — but the guard stays: an average is not
     * worth an exception, and a report that dies on one empty slice shows
     * nothing at all.
     *
     * @param amount the amount to divide
     * @param by the divisor
     * @return the quotient at two decimals, zero when the divisor is zero
     */
    private BigDecimal divide(BigDecimal amount, BigDecimal by) {
        if (by.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return amount.divide(by, 2, RoundingMode.HALF_UP);
    }
}
