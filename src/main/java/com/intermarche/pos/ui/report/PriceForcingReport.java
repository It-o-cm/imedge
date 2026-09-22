package com.intermarche.pos.ui.report;

import com.intermarche.pos.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ÉTAT DES FORÇAGES PRIX (BO-06-06-10): every line sold at a price the cashier
 * changed, with what the change cost or earned.
 *
 * <p>The report the questionnaire describes in two halves — one for markdowns,
 * one for markups — is ONE report with a sign. The columns are the same, and the
 * last two change name rather than meaning: a negative difference is a loss and
 * a markdown percentage, a positive one is a gain and a markup percentage. The
 * operator chooses which half to look at, or both, through the report's option.
 *
 * <p>A line qualifies when it carries a modifier AND the price it was originally
 * offered at: without the original, the difference has no term to be measured
 * against, and a line whose price was never forced would be counted as a forcing
 * of zero.
 */
@ApplicationScoped
public class PriceForcingReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PriceForcingReport.class);

    /** The option value keeping only the markdowns. */
    static final String DOWN = "down";

    /** The option value keeping only the markups. */
    static final String UP = "up";

    /** The persistence session the query runs on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "price-forcings";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État des forçages prix";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-06-10";
    }

    /**
     * {@inheritDoc}
     *
     * @return the cashiers family
     */
    @Override
    public ReportFamily family() {
        return ReportFamily.CASHIERS;
    }

    /**
     * {@inheritDoc}
     *
     * @return the columns the requirement enumerates
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.dateTime("when", "Date et heure"),
                ReportColumn.text("terminal", "N° TPV"),
                ReportColumn.text("ticket", "N° ticket"),
                ReportColumn.text("cashier", "N° caissière"),
                ReportColumn.text("cashierName", "Nom de la caissière"),
                ReportColumn.text("kind", "Type de réduction"),
                ReportColumn.text("article", "N° article"),
                ReportColumn.text("family", "N° famille"),
                ReportColumn.text("articleLabel", "Libellé de l'article"),
                ReportColumn.amount("retail", "Prix au détail"),
                ReportColumn.amount("forced", "Prix forcé"),
                ReportColumn.count("quantity", "Quantité"),
                ReportColumn.amount("delta", "Écart en espèce"),
                ReportColumn.percent("rate", "% démarque / majoration"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the label of the half-selector
     */
    @Override
    public String optionLabel() {
        return "Sens du forçage";
    }

    /**
     * {@inheritDoc}
     *
     * @return the three halves the operator can ask for
     */
    @Override
    public List<String> optionChoices() {
        return List.of("all:Toutes", DOWN + ":Baisses de prix", UP + ":Hausses de prix");
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per forced line
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        String option = criteria.optionOr("all");
        List<Object[]> lines = entityManager.createQuery(
                        "select t.closingDate, t.terminalId, t.ticketNumber, t.cashier, l.modifierLabel,"
                                + " l.ean, l.plu, l.familyCode, l.productLabel, l.originalUnitPrice,"
                                + " l.unitPrice, l.quantity"
                                + " from Ticket t join t.lines l"
                                + " where t.status = :closed and t.closingDate >= :from and t.closingDate < :to"
                                + " and l.originalUnitPrice is not null and l.modifierType is not null"
                                + " and l.cancelled = false"
                                + " order by t.closingDate", Object[].class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to())
                .getResultList();
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] line : lines) {
            BigDecimal retail = (BigDecimal) line[9];
            BigDecimal forced = (BigDecimal) line[10];
            BigDecimal quantity = line[11] == null ? BigDecimal.ONE : (BigDecimal) line[11];
            BigDecimal delta = forced.subtract(retail).multiply(quantity);
            if (!kept(option, delta)) {
                continue;
            }
            var cashier = (com.intermarche.pos.domain.people.Employee) line[3];
            rows.add(java.util.Arrays.asList(
                    (LocalDateTime) line[0],
                    line[1],
                    line[2],
                    cashier == null ? "" : cashier.badgeId,
                    cashier == null ? "" : cashier.getFullName(),
                    line[4] == null ? "" : line[4],
                    line[5] == null || String.valueOf(line[5]).isBlank() ? line[6] : line[5],
                    line[7] == null ? "" : line[7],
                    line[8] == null ? "" : line[8],
                    retail,
                    forced,
                    quantity,
                    delta,
                    rate(retail, forced)));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Whether a forcing belongs to the half the operator asked for.
     *
     * <p>A difference of exactly zero is kept by neither half and only appears
     * under « Toutes »: a modifier that changed nothing is neither a markdown
     * nor a markup, and counting it in both would double it in the total.
     *
     * @param option the chosen half
     * @param delta the signed difference in money
     * @return true when the row is kept
     */
    private boolean kept(String option, BigDecimal delta) {
        if (DOWN.equals(option)) {
            return delta.signum() < 0;
        }
        if (UP.equals(option)) {
            return delta.signum() > 0;
        }
        return true;
    }

    /**
     * Computes the markdown or markup rate against the retail price.
     *
     * @param retail the price the article was offered at
     * @param forced the price it was sold at
     * @return the signed rate in percent, zero when the retail price is zero
     */
    private BigDecimal rate(BigDecimal retail, BigDecimal forced) {
        if (retail == null || retail.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return forced.subtract(retail)
                .multiply(BigDecimal.valueOf(100))
                .divide(retail, 1, RoundingMode.HALF_UP);
    }
}
