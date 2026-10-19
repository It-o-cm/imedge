package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketFidelityLine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ÉTAT DES VENTES PROMOTIONNELLES PAR CAISSIÈRE ({@code BO-06-08-01}):
 * broken by promotion, how many times each one fired under each cashier,
 * what those tickets sold, and what share of it the promotion gave back.
 *
 * <p>A PROMOTION IS AN ADVANTAGE LINE of the valuation: the engine freezes
 * one per advantage it granted, naming its rule and its amount, and that is
 * the only place a promotion exists as a countable thing. Its rule code is
 * the number the break is made on, and its label is the one the engine
 * stated — not a second referential the back office would have to keep in
 * step.
 *
 * <p>The NET SALES of a line are the ticket's, not the promotion's: a
 * promotion fires on a basket and a basket has one total. Two promotions on
 * one ticket therefore each show that ticket's total, and the share column
 * is read per promotion rather than summed down the page — which is what
 * "% de la remise par rapport au montant total net des ventes" asks for.
 *
 * <p>What is NOT here: the promotion-TYPE filter (by value, in percent).
 * The engine freezes an amount and a label on the advantage line and never
 * says which of the two shapes computed it, so the filter would have to
 * guess. It is named rather than approximated.
 */
@ApplicationScoped
public class PromotionCashierReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PromotionCashierReport.class);

    /** The persistence session the query runs on. */
    @Inject EntityManager entityManager;

    /**
     * One promotion-and-cashier pair's running totals.
     */
    private static final class Row {

        /** The promotion's rule code. */
        private String promotion = "";

        /** What the engine called it. */
        private String label = "";

        /** The cashier's badge, or an empty string. */
        private String badge = "";

        /** The cashier's name, or an empty string. */
        private String name = "";

        /** How many times the promotion fired. */
        private long fired;

        /** The net sales of the tickets it fired on. */
        private BigDecimal netSales = BigDecimal.ZERO;

        /** What the promotion gave back. */
        private BigDecimal discount = BigDecimal.ZERO;

        /**
         * Builds an empty row.
         */
        private Row() {
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "promotion-cashiers";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "Ventes promotionnelles par caissière";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-08-01";
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
     * @return the columns the requirement enumerates, the break first
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("promotion", "N° de la promotion"),
                ReportColumn.text("promotionLabel", "Libellé de la promotion"),
                ReportColumn.text("badge", "N° de la caissière"),
                ReportColumn.text("name", "Nom de la caissière"),
                ReportColumn.count("fired", "Nombre de déclenchements"),
                ReportColumn.amount("netSales", "Montant net des ventes"),
                ReportColumn.amount("discount", "Montant de la remise"),
                ReportColumn.percent("share", "% de la remise"));
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per promotion and cashier, promotion by promotion
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        StringBuilder jpql = new StringBuilder(
                "select t from Ticket t where t.status = :closed"
                        + " and t.closingDate >= :from and t.closingDate < :to");
        if (!criteria.terminals.isEmpty()) {
            jpql.append(" and t.terminalId in :terminals");
        }
        jpql.append(" order by t.closingDate");
        var query = entityManager.createQuery(jpql.toString(), Ticket.class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        if (!criteria.terminals.isEmpty()) {
            query.setParameter("terminals", criteria.terminals);
        }
        Map<String, Row> rows = new LinkedHashMap<>();
        for (Ticket ticket : query.getResultList()) {
            Employee cashier = ticket.cashier;
            if (!selected(criteria, cashier)) {
                continue;
            }
            for (TicketFidelityLine advantage : advantages(ticket)) {
                String promotion = advantage.ruleCode == null ? "" : advantage.ruleCode;
                String key = promotion + "|" + (cashier == null ? "" : cashier.id);
                Row row = rows.computeIfAbsent(key, k -> new Row());
                row.promotion = promotion;
                row.label = advantage.label == null ? "" : advantage.label;
                row.badge = cashier == null ? "" : orEmpty(cashier.badgeId);
                row.name = cashier == null ? "" : cashier.getFullName();
                row.fired++;
                row.netSales = row.netSales.add(
                        ticket.totalIncludingTax == null ? BigDecimal.ZERO
                                : ticket.totalIncludingTax);
                row.discount = row.discount.add(
                        advantage.amount == null ? BigDecimal.ZERO : advantage.amount);
            }
        }
        List<List<Object>> result = new ArrayList<>();
        for (Row row : rows.values()) {
            result.add(Arrays.asList(row.promotion, row.label, row.badge, row.name,
                    row.fired, row.netSales, row.discount, share(row)));
        }
        result.sort((left, right) -> String.valueOf(left.get(0))
                .compareTo(String.valueOf(right.get(0))));
        LOGGER.info("Exiting method rows with " + result.size() + " row(s)");
        return result;
    }

    /**
     * The frozen advantage lines of a ticket, empty when the engine granted
     * none or the ticket was never valued.
     *
     * @param ticket the closed ticket
     * @return the advantage lines
     */
    private List<TicketFidelityLine> advantages(Ticket ticket) {
        return ticket.fidelityLines == null ? List.of() : ticket.fidelityLines;
    }

    /**
     * Whether the edition asked for this cashier; an empty selection asks for
     * all of them.
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
     * What share of the net sales the promotion gave back, to two decimals;
     * no sales answers zero rather than a division by nothing.
     *
     * @param row the accumulated row
     * @return the percentage
     */
    private BigDecimal share(Row row) {
        if (row.netSales.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return row.discount.multiply(BigDecimal.valueOf(100))
                .divide(row.netSales, 2, RoundingMode.HALF_UP);
    }

    /**
     * Turns a null into the empty string, for a column that prints it.
     *
     * @param value the value, possibly null
     * @return the value, or an empty string
     */
    private String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
