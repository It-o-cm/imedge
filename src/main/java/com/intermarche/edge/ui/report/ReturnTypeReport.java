package com.intermarche.edge.ui.report;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * RETOURS PAR TYPE (BO-03-13-01, BO-03-13-12): every return of the period
 * filed under an administered return type, whichever gesture took it back —
 * the refund of a dedicated return ticket, or a return line taken back inside
 * a sale, whose type the register stores on the line.
 *
 * <p>Only CLOSED tickets and refunds are listed, and a return made under no
 * type is left out: the state answers "what was returned under which type".
 */
@ApplicationScoped
public class ReturnTypeReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ReturnTypeReport.class);

    /** The origin of a return taken on a dedicated return ticket. */
    static final String REFUND = "Ticket de retour";

    /** The origin of a return line taken back inside a sale. */
    static final String IN_SALE = "Retour en vente";

    /** The persistence session the queries run on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "returns-by-type";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "Retours par type";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-03-13-12";
    }

    /**
     * {@inheritDoc}
     *
     * @return the cashiers family, beside the refund state
     */
    @Override
    public ReportFamily family() {
        return ReportFamily.CASHIERS;
    }

    /**
     * {@inheritDoc}
     *
     * @return the columns of a typed return
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.dateTime("when", "Date et heure"),
                ReportColumn.text("terminal", "N° TPV"),
                ReportColumn.text("number", "N° de document"),
                ReportColumn.text("origin", "Origine"),
                ReportColumn.text("typeCode", "Code type de retour"),
                ReportColumn.text("typeLabel", "Type de retour"),
                ReportColumn.text("article", "Article"),
                ReportColumn.amount("amount", "Montant"));
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per typed return of the period, oldest first
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        List<List<Object>> rows = new ArrayList<>();
        StringBuilder refunds = new StringBuilder(
                "select r.creationDate, r.terminalId, r.refundNumber, r.returnTypeCode,"
                        + " r.returnTypeLabel, r.totalAmount from Refund r where r.status = :closed"
                        + " and r.creationDate >= :from and r.creationDate < :to"
                        + " and r.returnTypeCode is not null");
        StringBuilder lines = new StringBuilder(
                "select t.creationDate, t.terminalId, t.ticketNumber, l.returnTypeCode,"
                        + " l.returnTypeLabel, l.totalPrice, l.productLabel"
                        + " from Ticket t join t.lines l where t.status = :closed"
                        + " and t.creationDate >= :from and t.creationDate < :to"
                        + " and l.returnLine = true and l.cancelled = false and l.returnTypeCode is not null");
        if (!criteria.terminals.isEmpty()) {
            refunds.append(" and r.terminalId in :terminals");
            lines.append(" and t.terminalId in :terminals");
        }
        for (Object[] line : run(refunds.toString(),
                com.intermarche.edge.domain.sale.Refund.RefundStatus.CLOSED, criteria)) {
            rows.add(row(line, REFUND, ""));
        }
        for (Object[] line : run(lines.toString(),
                com.intermarche.edge.domain.sale.Ticket.TicketStatus.CLOSED, criteria)) {
            rows.add(row(line, IN_SALE, line[6] == null ? "" : line[6]));
        }
        rows.sort(Comparator.comparing(row -> (LocalDateTime) row.get(0)));
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Runs one of the two queries with the period and the terminals filter.
     *
     * @param jpql the query
     * @param closed the closed status of the document queried
     * @param criteria the edition parameters
     * @return the selected columns
     */
    private List<Object[]> run(String jpql, Object closed, ReportCriteria criteria) {
        var query = entityManager.createQuery(jpql, Object[].class)
                .setParameter("closed", closed)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        if (!criteria.terminals.isEmpty()) {
            query.setParameter("terminals", criteria.terminals);
        }
        return query.getResultList();
    }

    /**
     * Builds one row; the amount is shown as what was given back.
     *
     * @param line the selected columns
     * @param origin the gesture that took the return
     * @param article the article label, empty for a refund
     * @return the row
     */
    private static List<Object> row(Object[] line, String origin, Object article) {
        return Arrays.asList((LocalDateTime) line[0], line[1] == null ? "" : line[1],
                line[2] == null ? "" : line[2], origin, line[3],
                line[4] == null ? "" : line[4], article,
                line[5] == null ? BigDecimal.ZERO : ((BigDecimal) line[5]).abs());
    }
}
