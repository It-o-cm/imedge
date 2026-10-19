package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * ÉTAT VENTES PAR FAMILLE (BO-06-04-01): what each article family sold over the
 * period, its share of the whole, and what was cancelled inside it.
 *
 * <p>The requirement's full column list also asks for coupons, returns and
 * e-commerce per family. Those are NOT per-family figures in this system and
 * saying so is more useful than filling the columns: a coupon settles a TICKET,
 * a refund is a document of its own with its own lines, and the e-commerce
 * channel does not exist here. Attributing a ticket-level coupon to a family
 * would require a rule nobody has stated — and whichever rule was chosen, the
 * column would be a guess presented as a measurement.
 *
 * <p>Cancelled lines are excluded from the sales and counted separately, which
 * is the only reading that makes the share column add up to a hundred.
 */
@ApplicationScoped
public class FamilySalesReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(FamilySalesReport.class);

    /** The persistence session the queries run on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "family-sales";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État ventes par famille";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-04-01";
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
     * @return the columns the data supports
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("code", "Code famille"),
                ReportColumn.text("label", "Libellé de la famille"),
                ReportColumn.amount("net", "Ventes nettes"),
                ReportColumn.percent("share", "% des ventes nettes"),
                ReportColumn.count("lines", "Nb de lignes"),
                ReportColumn.count("items", "Nb d'articles"),
                ReportColumn.amount("average", "Vente moyenne par ligne"),
                ReportColumn.count("cancelled", "Nb d'annulations"),
                ReportColumn.amount("cancelledAmount", "Montant des annulations"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the net-sales column, drawn as a bar per family
     */
    @Override
    public int chartColumn() {
        return 2;
    }

    /**
     * {@inheritDoc}
     *
     * @return the label column, so each bar is named by its family
     */
    @Override
    public int chartLabelColumn() {
        return 1;
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per family that sold something, best first
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        List<Object[]> sold = entityManager.createQuery(
                        "select l.familyCode, l.familyLabel, sum(l.totalPrice), count(l), sum(l.quantity)"
                                + " from Ticket t join t.lines l"
                                + " where t.status = :closed and t.closingDate >= :from"
                                + " and t.closingDate < :to and l.cancelled = false"
                                + " group by l.familyCode, l.familyLabel", Object[].class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to())
                .getResultList();
        List<Object[]> cancelled = entityManager.createQuery(
                        "select l.familyCode, count(l), sum(l.totalPrice)"
                                + " from Ticket t join t.lines l"
                                + " where l.cancelled = true and l.cancellationDate >= :from"
                                + " and l.cancellationDate < :to"
                                + " group by l.familyCode", Object[].class)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to())
                .getResultList();
        BigDecimal total = BigDecimal.ZERO;
        for (Object[] line : sold) {
            total = total.add(line[2] == null ? BigDecimal.ZERO : (BigDecimal) line[2]);
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] line : sold) {
            String code = line[0] == null ? "—" : String.valueOf(line[0]);
            BigDecimal net = line[2] == null ? BigDecimal.ZERO : (BigDecimal) line[2];
            long lines = ((Number) line[3]).longValue();
            Object[] undone = find(cancelled, line[0]);
            rows.add(List.of(
                    code,
                    line[1] == null ? "" : line[1],
                    net,
                    share(net, total),
                    lines,
                    line[4] == null ? 0L : ((Number) line[4]).longValue(),
                    lines == 0 ? BigDecimal.ZERO : net.divide(BigDecimal.valueOf(lines), 2, RoundingMode.HALF_UP),
                    undone == null ? 0L : ((Number) undone[1]).longValue(),
                    undone == null || undone[2] == null ? BigDecimal.ZERO : (BigDecimal) undone[2]));
        }
        rows.sort((a, b) -> ((BigDecimal) b.get(2)).compareTo((BigDecimal) a.get(2)));
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Finds a family's cancellation line.
     *
     * @param cancelled the cancellation lines
     * @param code the family code looked for, possibly null
     * @return the line, or null when the family cancelled nothing
     */
    private Object[] find(List<Object[]> cancelled, Object code) {
        for (Object[] line : cancelled) {
            if (line[0] == null ? code == null : line[0].equals(code)) {
                return line;
            }
        }
        return null;
    }

    /**
     * Computes a family's share of the period's net sales.
     *
     * @param net the family's net sales
     * @param total the period's net sales
     * @return the share in percent, zero when nothing was sold
     */
    private BigDecimal share(BigDecimal net, BigDecimal total) {
        if (total.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return net.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }
}
