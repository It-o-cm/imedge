package com.intermarche.pos.ui.report;

import com.intermarche.pos.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * ÉTAT TVA PAR NOMENCLATURE (BO-06-07-01): per family and per rate, what was
 * sold excluding tax, the tax itself, and the total.
 *
 * <p>The tax is DERIVED from the inclusive total and the rate carried by each
 * line, and not read from a stored column, because that is where it is exact: a
 * line knows the rate it was sold under even when the article's rate has changed
 * since. Recomputing from today's catalogue would restate last month's takings.
 *
 * <p>Rounded at the BUCKET and not at the line: the tax of a hundred lines at
 * 5,5 % is the tax of their sum, which is the rule a fiscal statement is read
 * against. Rounding each line first and adding them produces a figure that
 * drifts by a few cents from the ticket the customer holds.
 */
@ApplicationScoped
public class VatByFamilyReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(VatByFamilyReport.class);

    /** The persistence session the query runs on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "vat-by-family";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État TVA par nomenclature";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-07-01";
    }

    /**
     * {@inheritDoc}
     *
     * @return the tax family
     */
    @Override
    public ReportFamily family() {
        return ReportFamily.TAX;
    }

    /**
     * {@inheritDoc}
     *
     * @return the columns the requirement enumerates
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("code", "Code famille"),
                ReportColumn.text("label", "Libellé de la famille"),
                ReportColumn.percent("rate", "Taux de TVA"),
                ReportColumn.amount("excluding", "Montant HT"),
                ReportColumn.amount("vat", "Montant TVA"),
                ReportColumn.amount("including", "Montant TTC"));
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per family and rate, by family then by rate
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        List<Object[]> buckets = entityManager.createQuery(
                        "select l.familyCode, l.familyLabel, l.vatRate, sum(l.totalPrice)"
                                + " from Ticket t join t.lines l"
                                + " where t.status = :closed and t.closingDate >= :from"
                                + " and t.closingDate < :to and l.cancelled = false"
                                + " group by l.familyCode, l.familyLabel, l.vatRate"
                                + " order by l.familyCode, l.vatRate", Object[].class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to())
                .getResultList();
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] bucket : buckets) {
            BigDecimal rate = bucket[2] == null ? BigDecimal.ZERO : (BigDecimal) bucket[2];
            BigDecimal including = bucket[3] == null ? BigDecimal.ZERO : (BigDecimal) bucket[3];
            BigDecimal excluding = excluding(including, rate);
            rows.add(List.of(
                    bucket[0] == null ? "—" : bucket[0],
                    bucket[1] == null ? "" : bucket[1],
                    rate.multiply(BigDecimal.valueOf(100)),
                    excluding,
                    including.subtract(excluding),
                    including));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Removes the tax from an inclusive amount.
     *
     * @param including the amount including tax
     * @param rate the rate, as a fraction of one
     * @return the amount excluding tax, at two decimals
     */
    private BigDecimal excluding(BigDecimal including, BigDecimal rate) {
        BigDecimal divisor = BigDecimal.ONE.add(rate);
        if (divisor.signum() == 0) {
            // A rate of exactly minus one would divide by zero. It cannot come
            // from a catalogue, but a report reads what the database holds, not
            // what the catalogue should have written.
            return including;
        }
        return including.divide(divisor, 2, RoundingMode.HALF_UP);
    }
}
