package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.payment.PaymentTypes;
import com.intermarche.edge.domain.payment.TenderDefinition;
import com.intermarche.edge.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * ÉTAT DES RÈGLEMENTS (BO-06-06-09): per settlement method, its functional
 * identifier, its administered label, the number of settlements and their net
 * value, with the report's total.
 *
 * <p>Counted on CLOSED tickets only. A parked cart carries no settlement and an
 * abandoned one carries settlements that were undone: adding either would state
 * a takings figure the safe will not match.
 *
 * <p>The method's LABEL comes from the administered tender table when the store
 * declared it (BO-03-02), and falls back to the technical key otherwise —
 * a settlement taken through a method nobody administered still has to appear,
 * or the total would silently drop it.
 */
@ApplicationScoped
public class SettlementReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SettlementReport.class);

    /** The persistence session the aggregate query runs on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "settlements";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État des règlements";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-06-09";
    }

    /**
     * {@inheritDoc}
     *
     * @return the settlements family
     */
    @Override
    public ReportFamily family() {
        return ReportFamily.SETTLEMENTS;
    }

    /**
     * {@inheritDoc}
     *
     * @return the four columns of the state
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("group", "Groupe"),
                ReportColumn.text("key", "Type"),
                ReportColumn.text("id", "Identifiant"),
                ReportColumn.text("label", "Libellé du mode"),
                ReportColumn.count("count", "Nombre"),
                ReportColumn.amount("amount", "Valeur nette"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the amount column, the one worth drawing
     */
    @Override
    public int chartColumn() {
        return 5;
    }

    /**
     * {@inheritDoc}
     *
     * @return the label column, so each bar is named by its method
     */
    @Override
    public int chartLabelColumn() {
        return 3;
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per settlement method used over the period, then the
     *         total of the state
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        StringBuilder jpql = new StringBuilder(
                "select type(p), count(p), sum(p.amount) from Ticket t join t.payments p"
                        + " where t.status = :closed and t.closingDate >= :from and t.closingDate < :to");
        // BO-03-09-01: the registers chosen, and the deactivated ones left out.
        jpql.append(criteria.terminalFilter("t.terminalId"));
        jpql.append(" group by type(p)");
        var query = entityManager.createQuery(jpql.toString(), Object[].class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        criteria.bindTerminals(query);
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] line : query.getResultList()) {
            String key = keyOf(line[0]);
            TenderDefinition tender = TenderDefinition.find("code", key).firstResult();
            // BO-06-06-09: a method with no administered group stands in a
            // group of its own, named by the method. Grouping it under an
            // empty heading would pile every ungrouped method into one
            // meaningless sub-total.
            // A method nobody administered reads by its French name, never by
            // its bare technical key (CASH, CARD, VOUCHER).
            String label = tender == null || tender.label == null || tender.label.isBlank()
                    ? CashierActivity.TENDER_NAMES.getOrDefault(key, key) : tender.label;
            String groupLabel = tender == null || tender.groupLabel == null || tender.groupLabel.isBlank()
                    ? label : tender.groupLabel;
            rows.add(List.of(
                    groupLabel,
                    key,
                    tender == null || tender.functionalId == null ? "" : tender.functionalId,
                    label,
                    ((Number) line[1]).longValue(),
                    line[2] == null ? BigDecimal.ZERO : (BigDecimal) line[2]));
        }
        // Sorted on the value, descending: the reader of a settlement state is
        // looking for what the day was made of, and that is the order that
        // answers them without reading every line. Within a group, because
        // BO-06-06-09 totals by group and a sub-total under scattered lines
        // would be unreadable.
        rows.sort((a, b) -> {
            int byGroup = String.valueOf(a.get(0)).compareToIgnoreCase(String.valueOf(b.get(0)));
            return byGroup != 0 ? byGroup : ((BigDecimal) b.get(5)).compareTo((BigDecimal) a.get(5));
        });
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return withTotals(rows);
    }

    /**
     * Inserts the sub-total of each group ({@code BO-06-06-09}).
     *
     * <p>Produced here and not by the screen, because an export carries the
     * rows — an accountant reading the XLSX must find the same sub-totals as
     * the person reading the page. A group holding a single method gets NO
     * sub-total: it would repeat the line above it word for word. Each
     * sub-total is a {@link ReportData.Subtotal}, so the frame's TOTAL row —
     * the total of the state, on the page and in every export — and the chart
     * never count it a second time.
     *
     * @param rows the method rows, already sorted by group
     * @return the rows with their sub-totals, empty for empty
     */
    private List<List<Object>> withTotals(List<List<Object>> rows) {
        List<List<Object>> totalled = new ArrayList<>();
        String group = null;
        long groupCount = 0L;
        BigDecimal groupAmount = BigDecimal.ZERO;
        int groupLines = 0;
        for (List<Object> row : rows) {
            String current = String.valueOf(row.get(0));
            if (group != null && !group.equals(current)) {
                addSubtotal(totalled, group, groupLines, groupCount, groupAmount);
                groupCount = 0L;
                groupAmount = BigDecimal.ZERO;
                groupLines = 0;
            }
            group = current;
            totalled.add(row);
            groupLines++;
            groupCount += ((Number) row.get(4)).longValue();
            groupAmount = groupAmount.add((BigDecimal) row.get(5));
        }
        addSubtotal(totalled, group, groupLines, groupCount, groupAmount);
        return totalled;
    }

    /**
     * Closes a group with its sub-total row, when it holds more than one method.
     *
     * @param totalled the rows produced so far
     * @param group the group's label, null before the first row
     * @param lines the number of methods in the group
     * @param count the group's settlement count
     * @param amount the group's net value
     */
    private static void addSubtotal(List<List<Object>> totalled, String group, int lines, long count,
                                    BigDecimal amount) {
        if (lines > 1) {
            ReportData.Subtotal subtotal = new ReportData.Subtotal();
            subtotal.addAll(java.util.Arrays.asList(group, "", "", "Total " + group, count, amount));
            totalled.add(subtotal);
        }
    }

    /**
     * Resolves the discriminator key of a payment entity class.
     *
     * <p>Read back from {@link PaymentTypes}, the registry the whole system
     * names its methods by, rather than from the class's simple name: the key is
     * what the session report, the sync payloads and the tender table all use,
     * and deriving it from a class name would silently diverge the day a class is
     * renamed.
     *
     * @param type the entity class the query returned
     * @return the discriminator key, or the class's simple name when unknown
     */
    private String keyOf(Object type) {
        for (String key : PaymentTypes.keys()) {
            if (PaymentTypes.forKey(key) == type) {
                return key;
            }
        }
        return type instanceof Class<?> clazz ? clazz.getSimpleName() : String.valueOf(type);
    }
}
