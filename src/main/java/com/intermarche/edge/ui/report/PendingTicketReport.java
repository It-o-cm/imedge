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
 * <p>The recall names the ticket of the recall — a recalled cart keeps its
 * number — and the cashier who recalled it, read off the register's signed
 * trace of the recall. The carts parked in SCHOOL MODE are listed too, marked
 * « O » in the school-mode column: the register keeps a training row for each
 * since school mode writes no ticket.
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
                ReportColumn.text("recalledOn", "TPV de rappel"),
                ReportColumn.text("recallNumber", "N° du ticket de rappel"),
                ReportColumn.text("recalledBy", "Caissière du rappel"),
                ReportColumn.text("school", "Mode école"),
                ReportColumn.count("tickets", "Nombre de tickets"));
    }

    /**
     * {@inheritDoc}
     *
     * @return the registers, the island and the cashiers
     */
    @Override
    public java.util.Set<ReportFilter> filters() {
        return java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.CASHIERS);
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
        return List.of(PENDING + ":En attente, non rappelés", RECALLED + ":Rappelés", BOTH + ":Tous les tickets");
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
        // BO-03-09-01: the registers chosen, and the deactivated ones left out.
        jpql.append(criteria.terminalFilter("t.terminalId"));
        jpql.append(criteria.cashierFilter("t.cashier.badgeId"));
        jpql.append(" order by t.creationDate");
        var query = entityManager.createQuery(jpql.toString(), Object[].class)
                .setParameter("parked", Ticket.TicketStatus.PARKED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        criteria.bindTerminals(query);
        criteria.bindCashiers(query);
        java.util.Map<String, String> recallers = recallers(criteria);
        List<List<Object>> rows = new ArrayList<>();
        java.util.Set<String> known = new java.util.HashSet<>();
        for (Object[] line : query.getResultList()) {
            known.add(String.valueOf(line[3]));
            Employee cashier = (Employee) line[2];
            boolean recalled = line[7] != null;
            rows.add(Arrays.asList(
                    line[6] == null ? (LocalDateTime) line[0] : (LocalDateTime) line[6],
                    line[1] == null ? "" : line[1],
                    cashier == null ? "" : cashier.badgeId,
                    cashier == null ? "" : cashier.getFullName(),
                    line[3] == null ? "" : line[3],
                    ((Number) line[4]).longValue(),
                    line[5] == null ? BigDecimal.ZERO : line[5],
                    line[7] == null ? "" : line[7],
                    line[8] == null ? "" : line[8],
                    recalled && line[3] != null ? line[3] : "",
                    recalled ? recallers.getOrDefault(String.valueOf(line[3]), "") : "",
                    "Non", 1L));
        }
        if (!RECALLED.equals(option)) {
            rows.addAll(waiting(criteria, known));
        }
        rows.addAll(training(criteria, option));
        rows.sort(java.util.Comparator.comparing(row -> (LocalDateTime) row.get(0),
                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Reads the tickets still WAITING off the signed trace of the putting on
     * hold (BO-06-06-01, LC-04-01-03): the register pushes the trace of each
     * parking, never the waiting draft itself, so a ticket put on hold and not
     * taken back is known to the node by its PARKED record alone — one that no
     * later RESUMED or PURGED record of the same register and number follows.
     *
     * @param criteria the edition parameters
     * @param known the numbers the tickets already listed carry
     * @return one row per ticket still waiting, kept by the criteria
     */
    private List<List<Object>> waiting(ReportCriteria criteria, java.util.Set<String> known) {
        var query = entityManager.createQuery(
                        "select r from ParkedTicketRecord r where r.eventDate >= :from order by r.eventDate, r.id",
                        com.intermarche.edge.domain.sale.ParkedTicketRecord.class)
                .setParameter("from", criteria.from());
        java.util.Map<String, com.intermarche.edge.domain.sale.ParkedTicketRecord> parked =
                new java.util.LinkedHashMap<>();
        for (com.intermarche.edge.domain.sale.ParkedTicketRecord record : query.getResultList()) {
            String key = record.terminalId + "\u0000" + record.ticketNumber;
            if (record.event == com.intermarche.edge.domain.sale.ParkedTicketRecord.Event.PARKED) {
                parked.put(key, record);
            } else {
                parked.remove(key);
            }
        }
        List<List<Object>> rows = new ArrayList<>();
        for (com.intermarche.edge.domain.sale.ParkedTicketRecord record : parked.values()) {
            String badge = record.operator == null ? "" : record.operator;
            if (!record.eventDate.isBefore(criteria.to()) || known.contains(record.ticketNumber)
                    || !criteria.keepsTerminal(record.terminalId) || !criteria.keepsCashier(badge)) {
                continue;
            }
            Employee cashier = badge.isEmpty() ? null : Employee.find("badgeId", badge).firstResult();
            rows.add(Arrays.asList(
                    record.eventDate,
                    record.terminalId == null ? "" : record.terminalId,
                    badge,
                    cashier == null ? "" : cashier.getFullName(),
                    record.ticketNumber == null ? "" : record.ticketNumber,
                    (long) record.itemCount,
                    record.totalAmount == null ? BigDecimal.ZERO : record.totalAmount,
                    "", "", "", "", "Non", 1L));
        }
        return rows;
    }

    /**
     * Reads the TRAINING carts put on hold over the period (BO-06-06-01): the
     * register keeps a training row for each, with its parking and its recall,
     * and the state lists them marked « mode école ».
     *
     * @param criteria the edition parameters
     * @param option the recall option
     * @return one row per training cart kept by the criteria
     */
    private List<List<Object>> training(ReportCriteria criteria, String option) {
        var query = entityManager.createQuery(
                        "select t from TrainingTicket t where t.parkedAt is not null and t.parkedAt >= :from"
                                + " and t.parkedAt < :to", com.intermarche.edge.domain.sale.TrainingTicket.class)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        List<List<Object>> rows = new ArrayList<>();
        for (com.intermarche.edge.domain.sale.TrainingTicket parked : query.getResultList()) {
            boolean recalled = parked.resumedAt != null;
            if (RECALLED.equals(option) && !recalled || PENDING.equals(option) && recalled
                    || !criteria.keepsTerminal(parked.terminalId) || !criteria.keepsCashier(parked.parkedBy)) {
                continue;
            }
            Employee cashier = parked.cashierLogin == null ? null : Employee.findLogin(parked.cashierLogin);
            String number = "FORMATION-" + parked.sourceId;
            rows.add(Arrays.asList(
                    parked.parkedAt,
                    parked.terminalId == null ? "" : parked.terminalId,
                    parked.parkedBy == null ? "" : parked.parkedBy,
                    cashier == null ? "" : cashier.getFullName(),
                    number,
                    (long) parked.itemCount,
                    parked.totalIncludingTax == null ? BigDecimal.ZERO : parked.totalIncludingTax,
                    parked.resumedAt == null ? "" : parked.resumedAt,
                    parked.resumedTerminalId == null ? "" : parked.resumedTerminalId,
                    recalled ? number : "",
                    parked.resumedBy == null ? "" : parked.resumedBy,
                    "Oui", 1L));
        }
        return rows;
    }

    /**
     * Reads who recalled each ticket: the operator the register's signed trace
     * of the RESUMED event names (LC-04-01-03), the latest one winning.
     *
     * @param criteria the edition parameters
     * @return the recalling cashier's badge, by ticket number
     */
    private java.util.Map<String, String> recallers(ReportCriteria criteria) {
        var query = entityManager.createQuery(
                        "select r.ticketNumber, r.operator from ParkedTicketRecord r"
                                + " where r.event = :resumed and r.eventDate >= :from order by r.eventDate",
                        Object[].class)
                .setParameter("resumed", com.intermarche.edge.domain.sale.ParkedTicketRecord.Event.RESUMED)
                .setParameter("from", criteria.from());
        java.util.Map<String, String> recallers = new java.util.HashMap<>();
        for (Object[] record : query.getResultList()) {
            recallers.put(String.valueOf(record[0]), record[1] == null ? "" : String.valueOf(record[1]));
        }
        return recallers;
    }
}
