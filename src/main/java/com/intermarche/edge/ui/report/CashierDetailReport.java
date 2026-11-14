package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * ÉTAT CAISSIÈRE DÉTAILLÉ (BO-09-01-10): the detailed form of the cashier
 * state the end of period edits beside its synthetic form — per cashier, every
 * ticket she closed over the period, with its register, its moment, its
 * articles and its amount, so the synthetic totals can be traced back ticket
 * by ticket.
 *
 * <p>Restricted to the cashiers and registers the operator selected, like the
 * synthetic state.
 */
@ApplicationScoped
public class CashierDetailReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CashierDetailReport.class);

    /** The persistence session the query runs on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "cashiers-detail";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État caissière détaillé";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-09-01-10";
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
     * @return the columns of the detailed form
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.text("badge", "N° caissière"),
                ReportColumn.text("name", "Nom de la caissière"),
                ReportColumn.text("terminal", "Caisse"),
                ReportColumn.text("ticket", "Ticket"),
                ReportColumn.dateTime("closedAt", "Clôturé le"),
                ReportColumn.count("items", "Nb d'articles"),
                ReportColumn.amount("amount", "Montant"));
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per closed ticket, cashier by cashier
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        var query = entityManager.createQuery(
                        "select c, t.terminalId, t.ticketNumber, t.closingDate, t.itemCount, t.totalIncludingTax"
                                + " from Ticket t left join t.cashier c"
                                + " where t.status = :closed and t.closingDate >= :from and t.closingDate < :to"
                                + (criteria.cashiers.isEmpty() ? "" : " and c.badgeId in :badges")
                                + criteria.terminalFilter("t.terminalId")
                                + " order by c.badgeId, t.closingDate", Object[].class)
                .setParameter("closed", Ticket.TicketStatus.CLOSED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        if (!criteria.cashiers.isEmpty()) {
            query.setParameter("badges", criteria.cashiers);
        }
        criteria.bindTerminals(query);
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] line : query.getResultList()) {
            Employee cashier = (Employee) line[0];
            rows.add(List.of(
                    cashier == null || cashier.badgeId == null ? "—" : cashier.badgeId,
                    cashier == null ? "" : cashier.getFullName(),
                    line[1] == null ? "" : line[1],
                    line[2],
                    line[3],
                    ((Number) line[4]).longValue(),
                    line[5] == null ? BigDecimal.ZERO : line[5]));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }
}
