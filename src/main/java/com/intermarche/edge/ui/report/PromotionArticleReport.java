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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * ÉTAT DES VENTES ARTICLES PROMOTIONNELS (BO-06-08-02): article by article and
 * promotion by promotion, the article's number and label, the promotion's
 * number and label, the quantity sold, the net sales, the discount and whether
 * the promotion is FINANCED UPSTREAM (« Financement AMONT Oui ou Non »).
 *
 * <p>Read from the valuation trace the register keeps for every line the
 * engine valued and synchronises with the sale: the offer that valued the line
 * is the promotion, the difference between the line's own total and its valued
 * total is the discount — the advantage of a discount offer as the reduction
 * an N+M or a bundle carries inside its price. The label and the upstream
 * financing are the ones the Promotions screen administers.
 *
 * <p>The articles are chosen with the frame's article criteria — a selection
 * of articles (EAN codes, a saved list) or a range of families — over the day
 * or the range of dates.
 */
@ApplicationScoped
public class PromotionArticleReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PromotionArticleReport.class);

    /** The persistence session the query runs on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "promotion-articles";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État des ventes articles promotionnels";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-08-02";
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
                ReportColumn.text("article", "N° d'article"),
                ReportColumn.text("articleLabel", "Libellé de l'article"),
                ReportColumn.text("promotion", "N° de la promotion"),
                ReportColumn.text("promotionLabel", "Libellé de la promotion"),
                new ReportColumn("quantity", "Quantité vendue", ReportColumn.Kind.DECIMAL, true),
                ReportColumn.amount("netSales", "Montant net des ventes"),
                ReportColumn.amount("discount", "Montant de la remise"),
                ReportColumn.text("upstream", "Financement AMONT"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the discount
     */
    @Override
    public int chartColumn() {
        return 6;
    }

    /**
     * {@inheritDoc}
     *
     * @return the article's label
     */
    @Override
    public int chartLabelColumn() {
        return 1;
    }

    /**
     * {@inheritDoc}
     *
     * @return the registers, the island and the article criteria
     */
    @Override
    public Set<ReportFilter> filters() {
        return java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.ARTICLES);
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per article and promotion, by article
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        var query = entityManager.createQuery(
                        "select l.ean, l.plu, l.productLabel, l.quantity, l.totalPrice, v.offerLabel,"
                                + " v.advantageLabel, v.localTotal, v.valuedTotal, t.closingDate"
                                + " from Ticket t join t.lines l, TicketLineValuation v"
                                + " where v.ticket = t and v.lineUid = l.lineUid and v.valuedTotal < v.localTotal"
                                + " and t.status = :closed and t.closingDate >= :from and t.closingDate < :to"
                                + " and l.cancelled = false" + criteria.terminalFilter("t.terminalId")
                                + criteria.lineFilter("l"), Object[].class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        criteria.bindTerminals(query);
        criteria.bindLines(query);
        PromotionLookup promotions = new PromotionLookup();
        Map<String, Object[]> buckets = new TreeMap<>();
        for (Object[] line : query.getResultList()) {
            if (!criteria.inHours((LocalDateTime) line[9])) {
                continue;
            }
            String article = line[0] != null ? (String) line[0] : line[1] == null ? "" : (String) line[1];
            String offer = line[5] == null ? "" : (String) line[5];
            Object[] bucket = buckets.computeIfAbsent(article + "\u0000" + offer, key -> new Object[]{
                    article, line[2], offer, promotions.label(offer, (String) line[6]),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    promotions.upstream(offer) ? "Oui" : "Non"});
            bucket[4] = ((BigDecimal) bucket[4]).add(line[3] == null ? BigDecimal.ZERO : (BigDecimal) line[3]);
            bucket[5] = ((BigDecimal) bucket[5]).add(line[4] == null ? BigDecimal.ZERO : (BigDecimal) line[4]);
            bucket[6] = ((BigDecimal) bucket[6]).add(((BigDecimal) line[7]).subtract((BigDecimal) line[8]));
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] bucket : buckets.values()) {
            rows.add(Arrays.asList(bucket));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }
}
