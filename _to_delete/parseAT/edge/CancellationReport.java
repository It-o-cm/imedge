package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.people.Employee;
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

/**
 * ÉTAT ANNULATION (BO-06-06-04): every line a cashier cancelled, who authorized
 * it and what it was worth.
 *
 * <p>Read on the LINES and not on the tickets: a cancelled line survives on its
 * ticket, marked, with the moment and the operator who withdrew it — which is
 * what makes the state possible at all. A register that deleted the line would
 * leave nothing to report, and that is the difference between a journal and a
 * total.
 *
 * <p>The « signature » the requirement lists is the ticket's fiscal signature,
 * printed here so a cancellation can be traced back to the sealed document it
 * belongs to.
 */
@ApplicationScoped
public class CancellationReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CancellationReport.class);

    /** The persistence session the query runs on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "cancellations";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État annulation";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-06-04";
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
                ReportColumn.text("cashier", "N° caissière"),
                ReportColumn.text("cashierName", "Hôte(sse) de caisse"),
                ReportColumn.text("kind", "Type de transaction"),
                ReportColumn.text("article", "Libellé de l'article"),
                ReportColumn.amount("amount", "Montant"),
                ReportColumn.text("ticket", "N° ticket"),
                ReportColumn.text("reasonCode", "Code de la raison"),
                ReportColumn.text("reasonLabel", "Libellé de la raison"),
                ReportColumn.text("supervisor", "Superviseur"),
                ReportColumn.text("signature", "Signature"));
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per cancelled line, oldest first
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        StringBuilder jpql = new StringBuilder(
                "select l.cancellationDate, t.cashier, l.productLabel, l.totalPrice, t.ticketNumber,"
                        + " l.cancelledBy, t.signature, t.terminalId,"
                        + " l.cancellationReasonCode, l.cancellationReasonLabel"
                        + " from Ticket t join t.lines l"
                        + " where l.cancelled = true and l.cancellationDate >= :from"
                        + " and l.cancellationDate < :to");
        if (!criteria.terminals.isEmpty()) {
            jpql.append(" and t.terminalId in :terminals");
        }
        jpql.append(" order by l.cancellationDate");
        var query = entityManager.createQuery(jpql.toString(), Object[].class)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        if (!criteria.terminals.isEmpty()) {
            query.setParameter("terminals", criteria.terminals);
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] line : query.getResultList()) {
            Employee cashier = (Employee) line[1];
            rows.add(Arrays.asList(
                    (LocalDateTime) line[0],
                    cashier == null ? "" : cashier.badgeId,
                    cashier == null ? "" : cashier.getFullName(),
                    "Annulation de ligne",
                    line[2] == null ? "" : line[2],
                    line[3] == null ? BigDecimal.ZERO : line[3],
                    line[4] == null ? "" : line[4],
                    line[8] == null ? "" : line[8],
                    line[9] == null ? "" : line[9],
                    line[5] == null ? "" : line[5],
                    shorten(line[6])));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Shows the head of a fiscal signature.
     *
     * <p>A signature is a long base-64 block; whole, it pushes every other
     * column off the page and nobody reads it. Its first characters are enough
     * to pair a row with the sealed ticket, which is what the column is for.
     *
     * @param signature the stored signature, possibly null
     * @return the first sixteen characters, or an empty text
     */
    private String shorten(Object signature) {
        if (signature == null) {
            return "";
        }
        String value = String.valueOf(signature);
        return value.length() <= 16 ? value : value.substring(0, 16) + "…";
    }

    /**
     * {@inheritDoc}
     *
     * @return no chart: a list of cancellations is not a series
     */
    @Override
    public int chartColumn() {
        return -1;
    }
}
