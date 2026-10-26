package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.catalog.Promotion;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * ÉTAT DES VENTES PROMOTIONNELLES ({@code BO-06-08-01}): broken by promotion —
 * its number and label — how many times each one fired under each cashier
 * (number and name), what those tickets sold, and what share of it the
 * promotion gave back.
 *
 * <p>A promotion FIRES where the register recorded it: on a line the valuation
 * engine valued with an offer (the offer's identifier is the promotion's
 * number, the advantage on the line its discount), or as a loyalty advantage
 * of the ticket (its rule code). The label and the TYPE — by value or in
 * percent — are the ones the back office administers on the Promotions screen,
 * so the type filter of the requirement selects on an administered fact; a
 * promotion nobody described keeps the label the line carried.
 *
 * <p>The NET SALES are the tickets' the promotion fired on, each ticket counted
 * once per promotion, and the share is the discount over them. The edition can
 * be restricted to cashiers, to promotions and to a type.
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

        /** The promotion's number. */
        private String promotion = "";

        /** Its label. */
        private String label = "";

        /** The cashier's badge, or an empty string. */
        private String badge = "";

        /** The cashier's name, or an empty string. */
        private String name = "";

        /** How many times the promotion fired. */
        private long fired;

        /** The tickets counted in the net sales. */
        private final Set<Object> tickets = new HashSet<>();

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
        return "État des ventes promotionnelles";
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
     * @return the columns the requirement enumerates
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
     * @return the promotion's number: a subtotal closes each promotion
     */
    @Override
    public String breakKey() {
        return "promotion";
    }

    /**
     * {@inheritDoc}
     *
     * @return the number of triggers
     */
    @Override
    public int chartColumn() {
        return 4;
    }

    /**
     * {@inheritDoc}
     *
     * @return the cashier's name
     */
    @Override
    public int chartLabelColumn() {
        return 3;
    }

    /**
     * {@inheritDoc}
     *
     * @return what the type option is called on the screen
     */
    @Override
    public String optionLabel() {
        return "Type de promotion";
    }

    /**
     * {@inheritDoc}
     *
     * @return all, by value, in percent
     */
    @Override
    public List<String> optionChoices() {
        return List.of("ALL:Tous les types", Promotion.BY_VALUE + ":Par valeur", Promotion.BY_PERCENT + ":En %");
    }

    /**
     * {@inheritDoc}
     *
     * @return what the promotion choice is called on the screen
     */
    @Override
    public String pickLabel() {
        return "Promotions";
    }

    /**
     * {@inheritDoc}
     *
     * @return the administered promotions
     */
    @Override
    public List<String> pickChoices() {
        List<String> choices = new ArrayList<>();
        for (Promotion promotion : Promotion.listOrdered()) {
            choices.add(promotion.code + ":" + promotion.code + " — " + promotion.label);
        }
        return choices;
    }

    /**
     * {@inheritDoc}
     *
     * @return the registers, the island and the cashiers
     */
    @Override
    public Set<ReportFilter> filters() {
        return java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.CASHIERS);
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
        String kind = criteria.optionOr("ALL");
        PromotionLookup promotions = new PromotionLookup();
        Map<Object, List<Object[]>> valuations = valuations(criteria);
        var query = entityManager.createQuery(
                        "select t from Ticket t where t.status = :closed and t.closingDate >= :from"
                                + " and t.closingDate < :to" + criteria.terminalFilter("t.terminalId")
                                + criteria.cashierFilter("t.cashier.badgeId") + " order by t.closingDate", Ticket.class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        criteria.bindTerminals(query);
        criteria.bindCashiers(query);
        Map<String, Row> rows = new TreeMap<>();
        for (Ticket ticket : query.getResultList()) {
            List<Object[]> fired = new ArrayList<>();
            for (TicketFidelityLine advantage : ticket.fidelityLines) {
                fired.add(new Object[]{advantage.ruleCode, advantage.label, advantage.amount});
            }
            fired.addAll(valuations.getOrDefault(ticket.id, List.of()));
            for (Object[] trigger : fired) {
                String code = trigger[0] == null ? "" : String.valueOf(trigger[0]);
                String label = promotions.label(code, (String) trigger[1]);
                if (!criteria.picked(code) || !"ALL".equals(kind) && !kind.equals(promotions.kind(code, label))) {
                    continue;
                }
                add(rows, ticket, code, label, (BigDecimal) trigger[2]);
            }
        }
        List<List<Object>> result = new ArrayList<>();
        for (Row row : rows.values()) {
            result.add(Arrays.asList(row.promotion, row.label, row.badge, row.name,
                    row.fired, row.netSales, row.discount, share(row)));
        }
        LOGGER.info("Exiting method rows with " + result.size() + " row(s)");
        return result;
    }

    /**
     * Adds one trigger to its promotion-and-cashier row.
     *
     * @param rows the rows, by promotion then cashier
     * @param ticket the ticket it fired on
     * @param code the promotion's number
     * @param label the promotion's label
     * @param amount the discount, possibly null
     */
    private void add(Map<String, Row> rows, Ticket ticket, String code, String label, BigDecimal amount) {
        Employee cashier = ticket.cashier;
        String badge = cashier == null || cashier.badgeId == null ? "" : cashier.badgeId;
        Row row = rows.computeIfAbsent(code + "\u0000" + badge, k -> new Row());
        row.promotion = code;
        row.label = label;
        row.badge = badge;
        row.name = cashier == null ? "" : cashier.getFullName();
        row.fired++;
        if (row.tickets.add(ticket.id)) {
            row.netSales = row.netSales.add(ticket.totalIncludingTax == null ? BigDecimal.ZERO
                    : ticket.totalIncludingTax);
        }
        row.discount = row.discount.add(amount == null ? BigDecimal.ZERO : amount.abs());
    }

    /**
     * Reads the offers the valuation engine granted on the lines of the period.
     *
     * @param criteria the edition parameters
     * @return per ticket id: the offer, its advantage's label and amount, per line
     */
    private Map<Object, List<Object[]>> valuations(ReportCriteria criteria) {
        var query = entityManager.createQuery(
                        "select v.ticket.id, v.offerLabel, v.advantageLabel, v.advantageAmount"
                                + " from TicketLineValuation v where v.advantageAmount is not null"
                                + " and v.ticket.status = :closed and v.ticket.closingDate >= :from"
                                + " and v.ticket.closingDate < :to", Object[].class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        Map<Object, List<Object[]>> byTicket = new HashMap<>();
        for (Object[] row : query.getResultList()) {
            byTicket.computeIfAbsent(row[0], key -> new ArrayList<>()).add(new Object[]{row[1], row[2], row[3]});
        }
        return byTicket;
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
}
