package com.intermarche.pos.ui.report;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.sale.Ticket;
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
 * ÉTAT DES TICKETS EN ATTENTE (BO-06-06-01): the carts a cashier parked and
 * that are still waiting, with the register, the cashier and the amount.
 *
 * <p>What it shows is the UN-RECALLED half of the requirement, and that is a
 * limit of the data, not a choice: a parked cart that is resumed becomes an
 * ordinary sale and keeps no trace of having been parked, so the recall's date,
 * register, cashier and new ticket number — which the requirement also asks for
 * — have nowhere to be read from. Carrying them would mean the register
 * recording the resumption as an event of its own, which is a change at the
 * till. The state says what it holds rather than half-inventing the rest.
 *
 * <p>The moment shown is the ticket's CREATION, the nearest thing the node holds
 * to the moment it was parked: a parked cart has no closing date.
 */
@ApplicationScoped
public class PendingTicketReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PendingTicketReport.class);

    /** The persistence session the query runs on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "pending-tickets";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État des tickets en attente";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-06-01";
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
     * @return the columns the requirement enumerates for a waiting cart
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.dateTime("when", "Date de mise en attente"),
                ReportColumn.text("terminal", "N° TPV"),
                ReportColumn.text("badge", "N° caissière"),
                ReportColumn.text("name", "Nom de la caissière"),
                ReportColumn.text("number", "N° du ticket"),
                ReportColumn.count("items", "Nb d'articles"),
                ReportColumn.amount("amount", "Montant du ticket"));
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per cart still waiting, oldest first
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        StringBuilder jpql = new StringBuilder(
                "select t.creationDate, t.terminalId, t.cashier, t.ticketNumber, t.itemCount,"
                        + " t.totalIncludingTax from Ticket t"
                        + " where t.status = :parked and t.creationDate >= :from"
                        + " and t.creationDate < :to");
        if (!criteria.terminals.isEmpty()) {
            jpql.append(" and t.terminalId in :terminals");
        }
        jpql.append(" order by t.creationDate");
        var query = entityManager.createQuery(jpql.toString(), Object[].class)
                .setParameter("parked", Ticket.TicketStatus.PARKED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        if (!criteria.terminals.isEmpty()) {
            query.setParameter("terminals", criteria.terminals);
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] line : query.getResultList()) {
            Employee cashier = (Employee) line[2];
            rows.add(Arrays.asList(
                    (LocalDateTime) line[0],
                    line[1] == null ? "" : line[1],
                    cashier == null ? "" : cashier.badgeId,
                    cashier == null ? "" : cashier.getFullName(),
                    line[3] == null ? "" : line[3],
                    ((Number) line[4]).longValue(),
                    line[5] == null ? BigDecimal.ZERO : line[5]));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }
}
