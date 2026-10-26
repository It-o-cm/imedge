package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * ÉTAT PRODUCTIVITÉ HORAIRE (BO-06-04-06): what the line did, slice of time by
 * slice of time — takings, share, customers, registers open, and the averages
 * that turn the three into a measure of a shift — closed by the mean of a slice
 * over the edited period, beside the frame's total row.
 *
 * <p>The slice is CHOSEN — an hour, a half-hour or a quarter (« possibilité
 * d'afficher le détail par tranche d'une heure, d'une demi-heure, d'un quart
 * d'heure ») — because the question is not the same: the hour reads a day, the
 * quarter reads a rush.
 *
 * <p>« Nb TPV ouverts » counts the registers whose cash session was OPEN during
 * the slice, read off the sessions, not off the tickets: a register open with
 * nobody at it is a register the shop staffed, which is exactly what this state
 * is used to plan. A register that closed a ticket without a session known to
 * the node is counted too.
 *
 * <p>The two charts of the requirement — sales or customers per slice — are the
 * frame's chart with its series chosen. The extraction can be PLANNED over a
 * day, a range of dates or a specific week from the report screen.
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
                ReportColumn.figure("terminals", "TPV ouverts"),
                ReportColumn.average("perTerminal", "Montant moyen par TPV"),
                ReportColumn.decimal("customersPerTerminal", "Nombre de clients moyen par TPV"),
                ReportColumn.average("perCustomer", "Montant moyen par client"),
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
     * @return what the slice-width option is called on the screen
     */
    @Override
    public String optionLabel() {
        return TimeSlices.LABEL;
    }

    /**
     * {@inheritDoc}
     *
     * @return the three slice widths the requirement names
     */
    @Override
    public List<String> optionChoices() {
        return TimeSlices.CHOICES;
    }

    /**
     * {@inheritDoc}
     *
     * @return the registers, the island and the hour range
     */
    @Override
    public Set<ReportFilter> filters() {
        return java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.HOURS);
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per non-empty slice, in chronological order, then the
     *         mean of a slice
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        int minutes = TimeSlices.width(criteria.optionOr("60"));
        var query = entityManager.createQuery(
                        "select t.closingDate, t.terminalId, t.totalIncludingTax, t.itemCount"
                                + " from Ticket t"
                                + " where t.status = :closed and t.closingDate >= :from and t.closingDate < :to"
                                + criteria.terminalFilter("t.terminalId")
                                + " order by t.closingDate", Object[].class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        criteria.bindTerminals(query);
        Map<Integer, BigDecimal> sales = new TreeMap<>();
        Map<Integer, Long> customers = new TreeMap<>();
        Map<Integer, Long> items = new TreeMap<>();
        Map<Integer, Set<String>> terminals = openRegisters(criteria, minutes);
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (Object[] ticket : query.getResultList()) {
            LocalDateTime when = (LocalDateTime) ticket[0];
            if (!criteria.inHours(when)) {
                continue;
            }
            int slot = TimeSlices.start(when, minutes);
            BigDecimal amount = ticket[2] == null ? BigDecimal.ZERO : (BigDecimal) ticket[2];
            sales.merge(slot, amount, BigDecimal::add);
            customers.merge(slot, 1L, Long::sum);
            items.merge(slot, ((Number) ticket[3]).longValue(), Long::sum);
            terminals.computeIfAbsent(slot, key -> new LinkedHashSet<>())
                    .add(ticket[1] == null ? "" : String.valueOf(ticket[1]));
            grandTotal = grandTotal.add(amount);
        }
        List<List<Object>> rows = new ArrayList<>();
        long registerSum = 0;
        for (Map.Entry<Integer, BigDecimal> entry : sales.entrySet()) {
            int slot = entry.getKey();
            BigDecimal amount = entry.getValue();
            long people = customers.get(slot);
            long open = terminals.get(slot).size();
            registerSum += open;
            rows.add(Arrays.asList(
                    TimeSlices.name(slot, minutes),
                    amount,
                    share(amount, grandTotal),
                    people,
                    open,
                    divide(amount, BigDecimal.valueOf(open)),
                    divide(BigDecimal.valueOf(people), BigDecimal.valueOf(open)),
                    divide(amount, BigDecimal.valueOf(people)),
                    items.get(slot)));
        }
        if (!rows.isEmpty()) {
            rows.add(mean(rows, registerSum));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Builds the MEAN of a slice over the edited period (« moyenne des
     * différentes colonnes pour l'ensemble de la journée »), as a row the frame
     * neither totals nor draws.
     *
     * @param rows the slice rows
     * @param registerSum the sum of the open registers over the slices
     * @return the mean row
     */
    private List<Object> mean(List<List<Object>> rows, long registerSum) {
        BigDecimal count = BigDecimal.valueOf(rows.size());
        BigDecimal sales = BigDecimal.ZERO;
        long customers = 0;
        long items = 0;
        for (List<Object> row : rows) {
            sales = sales.add((BigDecimal) row.get(1));
            customers += (Long) row.get(3);
            items += (Long) row.get(8);
        }
        BigDecimal registers = BigDecimal.valueOf(registerSum);
        ReportData.Subtotal mean = new ReportData.Subtotal();
        mean.addAll(Arrays.asList(
                "Moyenne par tranche",
                divide(sales, count),
                null,
                Math.round((double) customers / rows.size()),
                Math.round((double) registerSum / rows.size()),
                divide(sales, registers),
                divide(BigDecimal.valueOf(customers), registers),
                divide(sales, BigDecimal.valueOf(customers)),
                Math.round((double) items / rows.size())));
        return mean;
    }

    /**
     * Reads, slice by slice, the registers whose cash session was open.
     *
     * @param criteria the edition parameters
     * @param minutes the slice width
     * @return the registers per slice's first minute of the day
     */
    private Map<Integer, Set<String>> openRegisters(ReportCriteria criteria, int minutes) {
        var query = entityManager.createQuery(
                        "select s.terminalId, s.openingDate, s.closingDate from CashSession s"
                                + " where s.openingDate < :to and (s.closingDate is null or s.closingDate > :from)"
                                + criteria.terminalFilter("s.terminalId"), Object[].class)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        criteria.bindTerminals(query);
        Map<Integer, Set<String>> open = new TreeMap<>();
        LocalDateTime now = LocalDateTime.now();
        for (Object[] session : query.getResultList()) {
            LocalDateTime start = max((LocalDateTime) session[1], criteria.from());
            LocalDateTime end = min(session[2] == null ? now : (LocalDateTime) session[2], criteria.to());
            LocalDateTime cursor = start.withSecond(0).withNano(0)
                    .withMinute(start.getMinute() / minutes * minutes);
            while (cursor.isBefore(end)) {
                if (criteria.inHours(cursor)) {
                    open.computeIfAbsent(TimeSlices.start(cursor, minutes), key -> new LinkedHashSet<>())
                            .add(String.valueOf(session[0]));
                }
                cursor = cursor.plusMinutes(minutes);
            }
        }
        return open;
    }

    /**
     * The later of two moments.
     *
     * @param a a moment
     * @param b another moment
     * @return the later one
     */
    private static LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    /**
     * The earlier of two moments.
     *
     * @param a a moment
     * @param b another moment
     * @return the earlier one
     */
    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
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
     * Divides an amount at two decimals. Every divisor here counts at least
     * one: a slice exists only for a ticket, which has a customer and a
     * register.
     *
     * @param amount the amount to divide
     * @param by the divisor, never zero
     * @return the quotient at two decimals
     */
    private BigDecimal divide(BigDecimal amount, BigDecimal by) {
        return amount.divide(by, 2, RoundingMode.HALF_UP);
    }
}
