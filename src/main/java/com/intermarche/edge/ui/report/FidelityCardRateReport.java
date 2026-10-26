package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ÉTAT DU TAUX D'ENCARTEMENT ({@code BO-06-06-11}): per cashier and per day,
 * how many customers were served, how many of them presented a loyalty card,
 * and what share that makes.
 *
 * <p>A CUSTOMER IS A CLOSED TICKET, which is the only count a register can
 * make honestly: nothing at the till distinguishes two people paying one
 * basket, and inventing a head count from the articles would make the rate
 * depend on how people shop rather than on whether the cashier asked for the
 * card. The parked, cancelled and open tickets are left out for the same
 * reason — they are not a customer served.
 *
 * <p>The detail level is an EDITION OPTION rather than two reports: "par
 * caissière" breaks the day down cashier by cashier, "total point de vente"
 * keeps one line per day. Both read the same rows; only the grouping key
 * changes, so the two levels can never disagree.
 */
@ApplicationScoped
public class FidelityCardRateReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(FidelityCardRateReport.class);

    /** The detail level that breaks the day down cashier by cashier. */
    static final String BY_CASHIER = "CASHIER";

    /** The detail level that keeps one line per day for the whole store. */
    static final String BY_STORE = "STORE";

    /** The persistence session the query runs on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "fidelity-card-rate";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "Taux d'encartement par caissière";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-06-11";
    }

    /**
     * {@inheritDoc}
     *
     * @return the cashiers family
     */
    @Override
    public ReportFamily family() {
        return ReportFamily.CASHIERS;
    }

    /**
     * {@inheritDoc}
     *
     * @return what the detail-level option is called on the screen
     */
    @Override
    public String optionLabel() {
        return "Niveau de détail";
    }

    /**
     * {@inheritDoc}
     *
     * @return the two detail levels the requirement names
     */
    @Override
    public List<String> optionChoices() {
        return List.of(BY_CASHIER + ":Par caissière", BY_STORE + ":Total point de vente");
    }

    /**
     * {@inheritDoc}
     *
     * @return the column the chart draws (BO-06-01-04)
     */
    @Override
    public int chartColumn() {
        return 5;
    }

    /**
     * {@inheritDoc}
     *
     * @return the column naming each bar
     */
    @Override
    public int chartLabelColumn() {
        return 2;
    }

    /**
     * {@inheritDoc}
     *
     * @return the columns the requirement enumerates
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                new ReportColumn("date", "Date", ReportColumn.Kind.DATE, false),
                ReportColumn.text("badge", "N° caissière"),
                ReportColumn.text("name", "Nom de la caissière"),
                ReportColumn.count("customers", "Nb total de clients"),
                ReportColumn.count("carried", "Nb clients fidélité"),
                ReportColumn.percent("share", "% de clients fidélité"));
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per day and per cashier, or per day alone at store level
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        boolean byCashier = !BY_STORE.equals(criteria.optionOr(BY_CASHIER));
        StringBuilder jpql = new StringBuilder(
                "select t.closingDate, t.cashier, t.fidelityCard from Ticket t"
                        + " where t.status = :closed and t.closingDate >= :from"
                        + " and t.closingDate < :to");
        // BO-03-09-01: the registers chosen, and the deactivated ones left out.
        jpql.append(criteria.terminalFilter("t.terminalId"));
        jpql.append(" order by t.closingDate");
        var query = entityManager.createQuery(jpql.toString(), Object[].class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        criteria.bindTerminals(query);
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, Employee> holders = new LinkedHashMap<>();
        Map<String, LocalDate> days = new LinkedHashMap<>();
        for (Object[] line : query.getResultList()) {
            LocalDate day = ((LocalDateTime) line[0]).toLocalDate();
            Employee cashier = (Employee) line[1];
            if (!selected(criteria, cashier)) {
                continue;
            }
            String key = byCashier
                    ? day + "|" + (cashier == null ? "" : String.valueOf(cashier.id))
                    : day.toString();
            counts.computeIfAbsent(key, k -> new long[2])[0]++;
            if (line[2] != null && !String.valueOf(line[2]).isBlank()) {
                counts.get(key)[1]++;
            }
            holders.putIfAbsent(key, byCashier ? cashier : null);
            days.putIfAbsent(key, day);
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : counts.entrySet()) {
            Employee cashier = holders.get(entry.getKey());
            long total = entry.getValue()[0];
            long carried = entry.getValue()[1];
            rows.add(Arrays.asList(
                    days.get(entry.getKey()),
                    cashier == null ? "" : cashier.badgeId,
                    cashier == null ? "Tous" : cashier.getFullName(),
                    total,
                    carried,
                    share(carried, total)));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Whether the edition asked for this cashier; an empty selection asks for
     * all of them, and a ticket with no cashier is kept at store level only.
     *
     * @param criteria the edition parameters
     * @param cashier the ticket's cashier, possibly null
     * @return true when the row belongs in the edition
     */
    private boolean selected(ReportCriteria criteria, Employee cashier) {
        if (criteria.cashiers.isEmpty()) {
            return true;
        }
        return cashier != null && cashier.badgeId != null
                && criteria.cashiers.contains(cashier.badgeId);
    }

    /**
     * The share of carded customers, to two decimals; a day with no customer
     * answers zero rather than a division by nothing.
     *
     * @param carried how many presented a card
     * @param total how many were served
     * @return the percentage
     */
    private BigDecimal share(long carried, long total) {
        if (total == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(carried * 100L)
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }
}
