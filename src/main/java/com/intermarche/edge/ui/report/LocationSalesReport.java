package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * ÉTAT VENTES PAR EMPLACEMENT (BO-06-04-02): per ARTICLE, its internal number,
 * its EAN, its description, its family code, its price, the quantity sold and
 * the sales — the list of the EANs a family holds and what each of them sold —
 * closed by the grand total of the quantities and of the sales.
 *
 * <p>The frame's criteria choose the articles — an EAN range, EAN codes, the
 * families (a code keeps everything below it), a saved selection — and the
 * period, narrowed by a time-of-day range. The option details the list by
 * SLICE of time: with the range zoomed on, say, a rush hour, the quarter-hour
 * option states each article's sales quarter by quarter over it.
 *
 * <p>The price shown is the unit price the article was last sold at over the
 * period: a price that changed during it is shown at its latest value, the
 * sales column carrying what was actually paid.
 */
@ApplicationScoped
public class LocationSalesReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(LocationSalesReport.class);

    /** The option value listing the articles without slicing. */
    static final String WHOLE = "0";

    /** The persistence session the query runs on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "location-sales";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État ventes par emplacement";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-04-02";
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
     * @return the columns the requirement enumerates, the slice first
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("slot", "Tranche horaire"),
                ReportColumn.text("internal", "N° interne de l'article"),
                ReportColumn.text("ean", "Code EAN de l'article"),
                ReportColumn.text("label", "Description de l'article"),
                ReportColumn.text("family", "Code famille"),
                ReportColumn.average("price", "Prix de l'article"),
                new ReportColumn("quantity", "Quantité vendue", ReportColumn.Kind.DECIMAL, true),
                ReportColumn.amount("sales", "Total des ventes"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the sales column
     */
    @Override
    public int chartColumn() {
        return 7;
    }

    /**
     * {@inheritDoc}
     *
     * @return the article's description
     */
    @Override
    public int chartLabelColumn() {
        return 3;
    }

    /**
     * {@inheritDoc}
     *
     * @return what the detail option is called on the screen
     */
    @Override
    public String optionLabel() {
        return "Détail horaire";
    }

    /**
     * {@inheritDoc}
     *
     * @return the whole period, and the three slice widths
     */
    @Override
    public List<String> optionChoices() {
        List<String> choices = new ArrayList<>();
        choices.add(WHOLE + ":Sans détail horaire");
        choices.addAll(TimeSlices.CHOICES);
        return choices;
    }

    /**
     * {@inheritDoc}
     *
     * @return the registers, the island, the hour range and the article criteria
     */
    @Override
    public Set<ReportFilter> filters() {
        return java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.HOURS,
                ReportFilter.ARTICLES);
    }

    /** The running figures of one article in one slice. */
    static final class Article {

        /** The EAN. */
        String ean;

        /** The description. */
        String label;

        /** The family code. */
        String family;

        /** The latest unit price. */
        BigDecimal price;

        /** The quantity sold. */
        BigDecimal quantity = BigDecimal.ZERO;

        /** The sales. */
        BigDecimal sales = BigDecimal.ZERO;
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per article, or per slice and article, in order
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        String option = criteria.optionOr(WHOLE);
        boolean sliced = !WHOLE.equals(option);
        int minutes = TimeSlices.width(option);
        var query = entityManager.createQuery(
                        "select t.closingDate, l.ean, l.plu, l.productLabel, l.familyCode, l.unitPrice,"
                                + " l.quantity, l.totalPrice from Ticket t join t.lines l"
                                + " where t.status = :closed and t.closingDate >= :from and t.closingDate < :to"
                                + " and l.cancelled = false" + criteria.terminalFilter("t.terminalId")
                                + criteria.lineFilter("l") + " order by t.closingDate", Object[].class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        criteria.bindTerminals(query);
        criteria.bindLines(query);
        Map<String, Article> articles = new TreeMap<>();
        Map<String, Integer> slots = new HashMap<>();
        for (Object[] line : query.getResultList()) {
            LocalDateTime when = (LocalDateTime) line[0];
            if (!criteria.inHours(when)) {
                continue;
            }
            String code = line[1] != null ? (String) line[1] : line[2] == null ? "" : (String) line[2];
            int slot = sliced ? TimeSlices.start(when, minutes) : -1;
            String key = String.format("%05d", slot + 1) + "\u0000" + code;
            slots.put(key, slot);
            Article article = articles.computeIfAbsent(key, k -> new Article());
            article.ean = code;
            article.label = (String) line[3];
            article.family = line[4] == null ? "" : (String) line[4];
            article.price = (BigDecimal) line[5];
            article.quantity = article.quantity.add(line[6] == null ? BigDecimal.ZERO : (BigDecimal) line[6]);
            article.sales = article.sales.add(line[7] == null ? BigDecimal.ZERO : (BigDecimal) line[7]);
        }
        Map<String, String> internal = internalCodes(articles);
        List<List<Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Article> entry : articles.entrySet()) {
            Article article = entry.getValue();
            int slot = slots.get(entry.getKey());
            rows.add(Arrays.asList(
                    slot < 0 ? "" : TimeSlices.name(slot, minutes),
                    internal.getOrDefault(article.ean, ""),
                    article.ean,
                    article.label,
                    article.family,
                    article.price,
                    article.quantity,
                    article.sales));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Reads the internal numbers of the articles listed.
     *
     * @param articles the articles listed
     * @return the internal number by EAN, for the articles the catalogue knows
     */
    private Map<String, String> internalCodes(Map<String, Article> articles) {
        Set<String> eans = new java.util.HashSet<>();
        for (Article article : articles.values()) {
            eans.add(article.ean);
        }
        Map<String, String> codes = new HashMap<>();
        if (eans.isEmpty()) {
            return codes;
        }
        for (Object[] product : entityManager.createQuery(
                        "select p.ean, p.internalCode from Product p where p.ean in :eans", Object[].class)
                .setParameter("eans", eans).getResultList()) {
            codes.put((String) product[0], product[1] == null ? "" : (String) product[1]);
        }
        return codes;
    }
}
