package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ÉTAT VENTES HORAIRES (BO-06-04-07): units sold and takings per slice of time,
 * for a chosen set of articles.
 *
 * <p>Close to the productivity state, and deliberately NOT the same report: that
 * one measures the LINE — how many registers were open, what each took — and
 * this one measures ARTICLES, filtered by family or by code. Merging them would
 * give a screen whose columns mean two things at once depending on which filter
 * is filled.
 *
 * <p>« Unités vendues » counts what the lines carry, which for a weighed
 * article is a WEIGHT rounded to the unit: three hundred grams of cherries read
 * as nothing and eight hundred as one. The requirement asks for units and the
 * column says units; a shop reading this state on its fruit aisle should read
 * the turnover column beside it.
 *
 * <p>The article filter is the report's option: a family code, an EAN or the
 * head of one. The requirement also asks for a filter on a saved article
 * selection; those selections are administered on the node's article screens and
 * the filter will read them the day a report needs to be tied to one.
 */
@ApplicationScoped
public class HourlySalesReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(HourlySalesReport.class);

    /** The persistence session the query runs on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "hourly-sales";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État ventes horaires";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-04-07";
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
     * @return the three columns the requirement names
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("slot", "Tranche horaire"),
                ReportColumn.count("units", "Unités vendues"),
                ReportColumn.amount("turnover", "Chiffre d'affaires"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the turnover column, drawn as a bar per slice
     */
    @Override
    public int chartColumn() {
        return 2;
    }

    /**
     * {@inheritDoc}
     *
     * @return the label of the article filter
     */
    @Override
    public String optionLabel() {
        return "Filtre article (code famille ou début d'EAN)";
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per non-empty hour, in chronological order
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        String filter = criteria.option;
        StringBuilder jpql = new StringBuilder(
                "select t.closingDate, l.quantity, l.totalPrice from Ticket t join t.lines l"
                        + " where t.status = :closed and t.closingDate >= :from"
                        + " and t.closingDate < :to and l.cancelled = false");
        if (filter != null && !filter.isBlank()) {
            jpql.append(" and (l.familyCode = :filter or l.ean like :prefix or l.plu = :filter)");
        }
        var query = entityManager.createQuery(jpql.toString(), Object[].class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        if (filter != null && !filter.isBlank()) {
            query.setParameter("filter", filter.trim());
            query.setParameter("prefix", filter.trim() + "%");
        }
        Map<Integer, BigDecimal[]> byHour = new LinkedHashMap<>();
        for (Object[] line : query.getResultList()) {
            int hour = ((LocalDateTime) line[0]).getHour();
            BigDecimal[] slot = byHour.computeIfAbsent(hour,
                    key -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            slot[0] = slot[0].add(line[1] == null ? BigDecimal.ZERO : (BigDecimal) line[1]);
            slot[1] = slot[1].add(line[2] == null ? BigDecimal.ZERO : (BigDecimal) line[2]);
        }
        List<Integer> hours = new ArrayList<>(byHour.keySet());
        hours.sort(Integer::compareTo);
        List<List<Object>> rows = new ArrayList<>();
        for (int hour : hours) {
            BigDecimal[] slot = byHour.get(hour);
            rows.add(List.of(String.format("%02d:00 – %02d:00", hour, (hour + 1) % 24), slot[0], slot[1]));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }
}
