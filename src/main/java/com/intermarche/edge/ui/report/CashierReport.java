package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ÉTAT CAISSIÈRE (BO-06-06-03), summary form: per cashier, what went through
 * her register over the period — customers, takings, articles, cancellations,
 * price forcings and discounts.
 *
 * <p>The complete state — drawer openings, tenders, VAT recap, cancellations
 * and the durations the register now measures, in summary or detail per
 * cashier — is {@link CashierStateReport}; this one is its one-line-per-cashier
 * synthesis, the form the end of period edits.
 *
 * <p>Restricted to the cashiers the operator selected when they selected any:
 * « sélection d'une, plusieurs ou toutes les caissières ».
 */
@ApplicationScoped
public class CashierReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CashierReport.class);

    /** The persistence session the queries run on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "cashiers";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État caissière synthétique";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-06-03";
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
     * @return the columns of the summary form
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("badge", "N° caissière"),
                ReportColumn.text("name", "Nom de la caissière"),
                ReportColumn.count("tickets", "Nb de clients"),
                ReportColumn.amount("sales", "Ventes"),
                ReportColumn.count("items", "Nb d'articles"),
                ReportColumn.average("basket", "Panier moyen"),
                ReportColumn.decimal("itemsPerCustomer", "Articles par client"),
                ReportColumn.count("cancelCount", "Nb annulations"),
                ReportColumn.amount("cancelAmount", "Montant annulations"),
                ReportColumn.count("forcingCount", "Nb forçages prix"),
                ReportColumn.amount("forcingAmount", "Écart des forçages"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the takings column, drawn as a bar per cashier
     */
    @Override
    public int chartColumn() {
        return 3;
    }

    /**
     * {@inheritDoc}
     *
     * @return the name column, so each bar is named by its cashier
     */
    @Override
    public int chartLabelColumn() {
        return 1;
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per cashier who closed at least one ticket
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        Map<String, Object[]> byCashier = new LinkedHashMap<>();
        for (Object[] line : sales(criteria)) {
            Employee cashier = (Employee) line[0];
            Object[] row = slot(byCashier, cashier);
            row[2] = ((Number) row[2]).longValue() + ((Number) line[1]).longValue();
            row[3] = ((BigDecimal) row[3]).add(line[2] == null ? BigDecimal.ZERO : (BigDecimal) line[2]);
            row[4] = ((Number) row[4]).longValue() + ((Number) line[3]).longValue();
        }
        for (Object[] line : cancellations(criteria)) {
            Object[] row = slot(byCashier, (Employee) line[0]);
            row[7] = ((Number) row[7]).longValue() + ((Number) line[1]).longValue();
            row[8] = ((BigDecimal) row[8]).add(line[2] == null ? BigDecimal.ZERO : (BigDecimal) line[2]);
        }
        for (Object[] line : forcings(criteria)) {
            Object[] row = slot(byCashier, (Employee) line[0]);
            row[9] = ((Number) row[9]).longValue() + ((Number) line[1]).longValue();
            row[10] = ((BigDecimal) row[10]).add(line[2] == null ? BigDecimal.ZERO : (BigDecimal) line[2]);
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] row : byCashier.values()) {
            long tickets = ((Number) row[2]).longValue();
            BigDecimal sales = (BigDecimal) row[3];
            long items = ((Number) row[4]).longValue();
            row[5] = tickets == 0 ? BigDecimal.ZERO
                    : sales.divide(BigDecimal.valueOf(tickets), 2, RoundingMode.HALF_UP);
            row[6] = tickets == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(items).divide(BigDecimal.valueOf(tickets), 1, RoundingMode.HALF_UP);
            rows.add(List.of(row));
        }
        rows.sort((a, b) -> ((BigDecimal) b.get(3)).compareTo((BigDecimal) a.get(3)));
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Finds or opens a cashier's accumulator row.
     *
     * @param byCashier the accumulators, by badge
     * @param cashier the cashier the figure belongs to, possibly null
     * @return the row to add to
     */
    private Object[] slot(Map<String, Object[]> byCashier, Employee cashier) {
        String badge = cashier == null || cashier.badgeId == null ? "—" : cashier.badgeId;
        return byCashier.computeIfAbsent(badge, key -> new Object[]{
                key,
                cashier == null ? "" : cashier.getFullName(),
                0L, BigDecimal.ZERO, 0L, BigDecimal.ZERO, BigDecimal.ZERO,
                0L, BigDecimal.ZERO, 0L, BigDecimal.ZERO});
    }

    /**
     * Counts the tickets, the takings and the articles per cashier.
     *
     * @param criteria the edition parameters
     * @return one line per cashier
     */
    private List<Object[]> sales(ReportCriteria criteria) {
        var query = entityManager.createQuery(
                        "select t.cashier, count(t), sum(t.totalIncludingTax), sum(t.itemCount)"
                                + " from Ticket t"
                                + " where t.status = :closed and t.closingDate >= :from"
                                + " and t.closingDate < :to" + cashierFilter(criteria) + criteria.terminalFilter("t.terminalId")
                                + " group by t.cashier", Object[].class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        bindCashiers(query, criteria);
        criteria.bindTerminals(query);
        return query.getResultList();
    }

    /**
     * Counts the cancelled lines and their value per cashier.
     *
     * @param criteria the edition parameters
     * @return one line per cashier
     */
    private List<Object[]> cancellations(ReportCriteria criteria) {
        var query = entityManager.createQuery(
                        "select t.cashier, count(l), sum(l.totalPrice) from Ticket t join t.lines l"
                                + " where l.cancelled = true and l.cancellationDate >= :from"
                                + " and l.cancellationDate < :to" + cashierFilter(criteria) + criteria.terminalFilter("t.terminalId")
                                + " group by t.cashier", Object[].class)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        bindCashiers(query, criteria);
        criteria.bindTerminals(query);
        return query.getResultList();
    }

    /**
     * Counts the forced lines and the money the forcings moved, per cashier.
     *
     * @param criteria the edition parameters
     * @return one line per cashier
     */
    private List<Object[]> forcings(ReportCriteria criteria) {
        var query = entityManager.createQuery(
                        "select t.cashier, count(l), sum((l.originalUnitPrice - l.unitPrice) * l.quantity)"
                                + " from Ticket t join t.lines l"
                                + " where t.status = :closed and t.closingDate >= :from"
                                + " and t.closingDate < :to and l.originalUnitPrice is not null"
                                + " and l.modifierType is not null and l.cancelled = false"
                                + cashierFilter(criteria) + criteria.terminalFilter("t.terminalId") + " group by t.cashier", Object[].class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        bindCashiers(query, criteria);
        criteria.bindTerminals(query);
        return query.getResultList();
    }

    /**
     * Builds the cashier restriction, or nothing when everyone is kept.
     *
     * <p>An absent criterion contributes NO CONDITION rather than a condition
     * comparing against null: binding an empty collection to an {@code in} is
     * exactly the kind of clause a database rejects at run time and a compiler
     * never sees.
     *
     * @param criteria the edition parameters
     * @return the JPQL fragment, empty when no cashier was selected
     */
    private String cashierFilter(ReportCriteria criteria) {
        return criteria.cashiers.isEmpty() ? "" : " and t.cashier.badgeId in :badges";
    }

    /**
     * Binds the selected cashiers, when there are any.
     *
     * @param query the query being prepared
     * @param criteria the edition parameters
     */
    private void bindCashiers(jakarta.persistence.TypedQuery<Object[]> query, ReportCriteria criteria) {
        if (!criteria.cashiers.isEmpty()) {
            query.setParameter("badges", criteria.cashiers);
        }
    }
}
