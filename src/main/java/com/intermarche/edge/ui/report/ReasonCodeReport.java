package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.session.TechnicalEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * ÉTAT DES CODES RAISON (BO-06-06-07): every gesture of the period that was
 * justified — a cash movement with its reason, a supervisor called, an
 * endorsement granted or refused.
 *
 * <p>The requirement asks for a CODE and a LABEL from an administered
 * reason-code table, grouped by reason-code TYPE. That table does not exist yet
 * — nothing in BO-03 administers it — so the state names what the system
 * actually records: the TYPE is the kind of gesture (a movement type, an event
 * type), and the LABEL is the reason the operator gave. The day the table is
 * administered, this report gains two columns and loses none of the rows it
 * already shows.
 *
 * <p>Two sources, one list, in one chronological order: a reason typed on a
 * withdrawal and a supervisor called two minutes later answer the same question
 * — what happened on that register, and why — and splitting them across two
 * screens is what makes an audit take an afternoon.
 */
@ApplicationScoped
public class ReasonCodeReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ReasonCodeReport.class);

    /** The event types that carry a justification in their detail. */
    private static final List<TechnicalEvent.EventType> JUSTIFIED = List.of(
            TechnicalEvent.EventType.SUPERVISOR_CALLED,
            TechnicalEvent.EventType.ENDORSEMENT_GRANTED,
            TechnicalEvent.EventType.ENDORSEMENT_DENIED);

    /** The persistence session the queries run on. */
    @Inject EntityManager entityManager;

    /**
     * {@inheritDoc}
     *
     * @return the report's code
     */
    @Override
    public String code() {
        return "reason-codes";
    }

    /**
     * {@inheritDoc}
     *
     * @return the report's title
     */
    @Override
    public String label() {
        return "État des codes raison";
    }

    /**
     * {@inheritDoc}
     *
     * @return the requirement this report answers
     */
    @Override
    public String requirement() {
        return "BO-06-06-07";
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
     * @return the columns the data can carry today
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.dateTime("when", "Date et heure"),
                ReportColumn.text("terminal", "N° TPV"),
                ReportColumn.text("badge", "Code caissière"),
                ReportColumn.text("kind", "Type de code raison"),
                ReportColumn.text("reason", "Libellé de la raison"));
    }

    /**
     * {@inheritDoc}
     *
     * @param criteria the edition parameters
     * @return one row per justified gesture, oldest first
     */
    @Override
    public List<List<Object>> rows(ReportCriteria criteria) {
        LOGGER.info("Entering method rows");
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] line : movements(criteria)) {
            rows.add(Arrays.asList(
                    (LocalDateTime) line[0],
                    line[1] == null ? "" : line[1],
                    badge(line[2]),
                    "Mouvement de caisse — " + line[3],
                    line[4]));
        }
        for (Object[] line : events(criteria)) {
            rows.add(Arrays.asList(
                    (LocalDateTime) line[0],
                    line[1] == null ? "" : line[1],
                    line[2] == null ? "" : line[2],
                    "Événement — " + line[3],
                    line[4] == null ? "" : line[4]));
        }
        rows.sort(Comparator.comparing(row -> (LocalDateTime) row.get(0)));
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Reads the cash movements that carry a reason.
     *
     * @param criteria the edition parameters
     * @return one line per justified movement
     */
    private List<Object[]> movements(ReportCriteria criteria) {
        return entityManager.createQuery(
                        "select m.movementDate, m.terminalId, m.cashier, m.type, m.reason"
                                + " from CashMovement m"
                                + " where m.reason is not null and m.reason <> ''"
                                + " and m.movementDate >= :from and m.movementDate < :to", Object[].class)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to())
                .getResultList();
    }

    /**
     * Reads the technical events that carry a justification.
     *
     * @param criteria the edition parameters
     * @return one line per justified event
     */
    private List<Object[]> events(ReportCriteria criteria) {
        return entityManager.createQuery(
                        "select e.eventDate, e.terminalId, e.operatorBadgeId, e.eventType, e.detail"
                                + " from TechnicalEvent e"
                                + " where e.eventType in :types and e.eventDate >= :from"
                                + " and e.eventDate < :to", Object[].class)
                .setParameter("types", JUSTIFIED)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to())
                .getResultList();
    }

    /**
     * Reads a movement's cashier badge.
     *
     * @param cashier the employee the movement carries, possibly null
     * @return the badge, or an empty text
     */
    private String badge(Object cashier) {
        if (cashier instanceof com.intermarche.edge.domain.people.Employee employee) {
            return employee.badgeId == null ? "" : employee.badgeId;
        }
        return "";
    }
}
