package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.sale.SalesChannel;
import com.intermarche.edge.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * CHIFFRE D'AFFAIRES PAR CANAL (BO-07-01-02): the revenue of the point of sale
 * split between its own lanes and the third-party systems it integrates — the
 * e-commerce platform, the mobility tools — so the e-commerce turnover is
 * distinguished inside the revenue it is part of.
 *
 * <p>Counted on CLOSED tickets, one row per channel that sold over the period,
 * the store's lanes standing for every ticket carrying no channel.
 */
@ApplicationScoped
public class ChannelRevenueReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ChannelRevenueReport.class);

    /** The persistence session the aggregate query runs on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "channel-revenue";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "Chiffre d'affaires par canal";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-07-01-02";
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
     * @return the columns of the state
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("channel", "Canal de vente"),
                ReportColumn.count("tickets", "Nb de tickets"),
                ReportColumn.amount("ttc", "CA TTC"),
                ReportColumn.amount("ht", "CA HT"),
                ReportColumn.amount("vat", "TVA"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the tax-included revenue column
     */
    @Override
    public int chartColumn() {
        return 2;
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per channel that sold, in the channels' order
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        var query = entityManager.createQuery(
                        "select t.salesChannel, count(t), sum(t.totalIncludingTax), sum(t.totalExcludingTax),"
                                + " sum(t.totalVat) from Ticket t"
                                + " where t.status = :closed and t.closingDate >= :from and t.closingDate < :to"
                                + criteria.terminalFilter("t.terminalId") + " group by t.salesChannel", Object[].class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        criteria.bindTerminals(query);
        Map<SalesChannel, Object[]> byChannel = new EnumMap<>(SalesChannel.class);
        for (Object[] line : query.getResultList()) {
            Object[] row = byChannel.computeIfAbsent(SalesChannel.of((SalesChannel) line[0]),
                    channel -> new Object[]{0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            row[0] = (Long) row[0] + ((Number) line[1]).longValue();
            row[1] = ((BigDecimal) row[1]).add(amount(line[2]));
            row[2] = ((BigDecimal) row[2]).add(amount(line[3]));
            row[3] = ((BigDecimal) row[3]).add(amount(line[4]));
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Map.Entry<SalesChannel, Object[]> entry : byChannel.entrySet()) {
            Object[] row = entry.getValue();
            rows.add(List.of(entry.getKey().getLabel(), row[0], cents(row[1]), cents(row[2]), cents(row[3])));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * An amount in cents, as the revenue states it.
     *
     * @param amount the summed amount
     * @return the amount with two decimals
     */
    private static BigDecimal cents(Object amount) {
        return ((BigDecimal) amount).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * An aggregated amount, zero when the column summed nothing.
     *
     * @param value the summed value, or null
     * @return the amount
     */
    private static BigDecimal amount(Object value) {
        return value == null ? BigDecimal.ZERO : (BigDecimal) value;
    }
}
