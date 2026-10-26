package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * <p>The article sample is chosen with the frame's article criteria — an EAN
 * range, EAN codes, families, a saved article list, which combine — and the
 * slice width is the report's option: the hour range of the criteria narrows
 * the day and the quarter details it, which is the « zoom au 1/4 d'heure ».
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
     * @return the label of the slice width, the zoom of the requirement
     */
    @Override
    public String optionLabel() {
        return TimeSlices.LABEL;
    }

    /**
     * {@inheritDoc}
     *
     * @return the hour, the half-hour and the quarter
     */
    @Override
    public List<String> optionChoices() {
        return TimeSlices.CHOICES;
    }

    /**
     * {@inheritDoc}
     *
     * @return the registers, the island, the hour range and the article criteria
     */
    @Override
    public java.util.Set<ReportFilter> filters() {
        return java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.HOURS,
                ReportFilter.ARTICLES);
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
        int minutes = TimeSlices.width(criteria.optionOr("60"));
        var query = entityManager.createQuery(
                        "select t.closingDate, l.quantity, l.totalPrice from Ticket t join t.lines l"
                                + " where t.status = :closed and t.closingDate >= :from"
                                + " and t.closingDate < :to and l.cancelled = false"
                                + criteria.terminalFilter("t.terminalId") + criteria.lineFilter("l"), Object[].class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        criteria.bindTerminals(query);
        criteria.bindLines(query);
        Map<Integer, BigDecimal[]> bySlot = new java.util.TreeMap<>();
        for (Object[] line : query.getResultList()) {
            LocalDateTime when = (LocalDateTime) line[0];
            if (!criteria.inHours(when)) {
                continue;
            }
            BigDecimal[] slot = bySlot.computeIfAbsent(TimeSlices.start(when, minutes),
                    key -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            slot[0] = slot[0].add(line[1] == null ? BigDecimal.ZERO : (BigDecimal) line[1]);
            slot[1] = slot[1].add(line[2] == null ? BigDecimal.ZERO : (BigDecimal) line[2]);
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Map.Entry<Integer, BigDecimal[]> slot : bySlot.entrySet()) {
            rows.add(List.of(TimeSlices.name(slot.getKey(), minutes), slot.getValue()[0], slot.getValue()[1]));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }
}
