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
 * ÉTAT DES TICKETS EN ATTENTE (BO-06-06-01): the carts a cashier parked and
 * that are still waiting, with the register, the cashier and the amount.
 *
 * <p>It shows BOTH halves of the requirement: the carts still waiting and the
 * ones that came back. That took a change at the till — a parked cart that is
 * resumed becomes an ordinary sale, so the register now dates the parking and
 * the recall on the ticket itself and names the lane that recalled it. Without
 * those three columns a resumed cart keeps no trace of ever having waited, and
 * half the requirement has nowhere to be read from.
 *
 * <p>The option says which half to edit: the pending carts alone — the
 * conduct of before and the default — the recalled ones alone, or both.
 *
 * <p>The moment shown is the PARKING, read from the ticket; a ticket parked
 * before that column existed carries none, and is dated by its creation, which
 * is the nearest thing the node holds.
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
                ReportColumn.amount("amount", "Montant du ticket"),
                ReportColumn.dateTime("recalledAt", "Date de rappel"),
                ReportColumn.text("recalledOn", "TPV de rappel"));
    }

    /** The option value editing the carts that are still waiting. */
    static final String PENDING = "WAITING";

    /** The option value editing the carts that were recalled. */
    static final String RECALLED = "RECALLED";

    /** The option value editing both. */
    static final String BOTH = "ALL";

    /**
     * {@inheritDoc}
     *
     * @return what the recall option is called on the screen
     */
    @Override
    public String optionLabel() {
        return "Tickets édités";
    }

    /**
     * {@inheritDoc}
     *
     * @return the three halves the requirement allows to edit
     */
    @Override
    public List<String> optionChoices() {
        return List.of(PENDING, RECALLED, BOTH);
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per parked cart, oldest first
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        String option = criteria.optionOr(PENDING);
        StringBuilder jpql = new StringBuilder(
                "select t.creationDate, t.terminalId, t.cashier, t.ticketNumber, t.itemCount,"
                        + " t.totalIncludingTax, t.parkedAt, t.resumedAt, t.resumedTerminalId"
                        + " from Ticket t"
                        + " where (t.status = :parked or t.resumedAt is not null)"
                        + " and t.creationDate >= :from and t.creationDate < :to");
        // BO-06-06-01: the recalled / not-recalled filter. A cart still waiting
        // carries no recall date, and one that came back carries one — the
        // column IS the filter, so neither arm needs a second query.
        if (RECALLED.equals(option)) {
            jpql.append(" and t.resumedAt is not null");
        } else if (!BOTH.equals(option)) {
            jpql.append(" and t.resumedAt is null");
        }
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
                    line[6] == null ? (LocalDateTime) line[0] : (LocalDateTime) line[6],
                    line[1] == null ? "" : line[1],
                    cashier == null ? "" : cashier.badgeId,
                    cashier == null ? "" : cashier.getFullName(),
                    line[3] == null ? "" : line[3],
                    ((Number) line[4]).longValue(),
                    line[5] == null ? BigDecimal.ZERO : line[5],
                    line[7] == null ? "" : line[7],
                    line[8] == null ? "" : line[8]));
        }
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }
}
