package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.ui.PriceModType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ÉTAT DES CODES RAISON (BO-06-06-07): every gesture of the period the cashier
 * JUSTIFIED with a reason code, whatever its type — the drawer opened out of a
 * sale, the line cancelled, the price reduced or forced, the article returned,
 * the cash movement, the supervisor called, the endorsement granted or refused
 * — with the date and time, the register, the cashier's code, first name and
 * last name, the reason-code TYPE (code and label) and the reason code (code
 * and label).
 *
 * <p>The TYPE is the function the reason justifies; the reason is the
 * administered justification the register recorded with it — the code and
 * label of the drawer-opening and line-cancellation justifications, the
 * discount key, the return reason, the movement's reason. Each source is read
 * where the register files it, so the state covers « l'ensemble des types de
 * codes raison » in one chronological list, restricted to the types, cashiers
 * and registers chosen.
 */
@ApplicationScoped
public class ReasonCodeReport implements ReportDefinition {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ReasonCodeReport.class);

    /** The type of a drawer opened out of a sale. */
    static final String DRAWER = "DRAWER_OPEN";

    /** The type of a cancelled line. */
    static final String CANCEL = "CANCEL_LINE";

    /** The type of a price reduction. */
    static final String DISCOUNT = "DISCOUNT";

    /** The type of a forced price. */
    static final String FORCING = "PRICE_FORCING";

    /** The type of a returned article. */
    static final String RETURN = "RETURN";

    /** The type of a cash movement. */
    static final String MOVEMENT = "CASH_MOVEMENT";

    /** The type of a supervisor call or an endorsement. */
    static final String SUPERVISION = "SUPERVISION";

    /** The labels of the types, by code. */
    /** The reason a register journals a survey answer under (BO-06-03-02). */
    static final String SURVEY = "SONDAGE";

    /** The prefix of the action labels in the bundles. */
    static final String ACTION_PREFIX = "t.s21-act-";

    /** An endorsement as the register journals it: {@code ACTION_CODE par who}. */
    private static final java.util.regex.Pattern ENDORSEMENT =
            java.util.regex.Pattern.compile("^([A-Z][A-Z0-9_]*[A-Z0-9]) par (.+)$");

    static final Map<String, String> TYPES = new java.util.LinkedHashMap<>();

    static {
        TYPES.put(DRAWER, "Ouverture de tiroir");
        TYPES.put(CANCEL, "Annulation de ligne");
        TYPES.put(DISCOUNT, "Réduction de prix");
        TYPES.put(FORCING, "Forçage de prix");
        TYPES.put(RETURN, "Motif de retour");
        TYPES.put(MOVEMENT, "Mouvement de caisse");
        TYPES.put(SUPERVISION, "Appel superviseur et aval");
    }

    /** The event types that carry a supervision justification in their detail. */
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
     * @return the columns the requirement enumerates
     */
    @Override
    public List<ReportColumn> columns() {
        return List.of(
                ReportColumn.dateTime("when", "Date et heure"),
                ReportColumn.text("terminal", "N° TPV"),
                ReportColumn.text("badge", "Code caissière"),
                ReportColumn.text("firstName", "Prénom de la caissière"),
                ReportColumn.text("lastName", "Nom de la caissière"),
                ReportColumn.text("typeCode", "Code du type de code raison"),
                ReportColumn.text("typeLabel", "Libellé du type de code raison"),
                ReportColumn.text("reasonCode", "Code raison"),
                ReportColumn.text("reasonLabel", "Libellé du code raison"));
    }

    /**
     * {@inheritDoc}
     *
     * @return what the type choice is called on the screen
     */
    @Override
    public String pickLabel() {
        return "Types de code raison";
    }

    /**
     * {@inheritDoc}
     *
     * @return every type, as {@code code:label}
     */
    @Override
    public List<String> pickChoices() {
        List<String> choices = new ArrayList<>();
        TYPES.forEach((code, label) -> choices.add(code + ":" + label));
        return choices;
    }

    /**
     * {@inheritDoc}
     *
     * @return the registers, the island and the cashiers
     */
    @Override
    public Set<ReportFilter> filters() {
        return java.util.EnumSet.of(ReportFilter.TERMINALS, ReportFilter.ISLAND, ReportFilter.CASHIERS);
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
        List<Object[]> raw = new ArrayList<>();
        if (criteria.picked(DRAWER)) {
            for (Object[] e : query(criteria, "select e.eventDate, e.terminalId, e.operatorBadgeId, e.detail"
                    + " from TechnicalEvent e where e.eventType = :type and e.eventDate >= :from and e.eventDate < :to"
                    + criteria.terminalFilter("e.terminalId"), "type", TechnicalEvent.EventType.DRAWER_FORCED_OPEN)) {
                String[] reason = drawerReason((String) e[3]);
                raw.add(new Object[]{e[0], e[1], e[2], DRAWER, reason[0], reason[1]});
            }
        }
        if (criteria.picked(CANCEL)) {
            for (Object[] l : query(criteria, "select l.cancellationDate, t.terminalId, l.cancelledBy,"
                    + " l.cancellationReasonCode, l.cancellationReasonLabel from Ticket t join t.lines l"
                    + " where l.cancelled = true and l.cancellationDate >= :from and l.cancellationDate < :to"
                    + criteria.terminalFilter("t.terminalId"), null, null)) {
                raw.add(new Object[]{l[0], l[1], l[2], CANCEL, l[3], l[4]});
            }
        }
        if (criteria.picked(DISCOUNT) || criteria.picked(FORCING)) {
            for (Object[] l : query(criteria, "select t.closingDate, t.terminalId, t.cashier.badgeId,"
                    + " l.modifierType, l.modifierLabel from Ticket t join t.lines l where t.status = :closed"
                    + " and l.cancelled = false and l.modifierType is not null and t.closingDate >= :from"
                    + " and t.closingDate < :to" + criteria.terminalFilter("t.terminalId"),
                    "closed", com.intermarche.edge.domain.sale.Ticket.TicketStatus.CLOSED)) {
                PriceModType type = (PriceModType) l[3];
                String kind = type == PriceModType.FORCE_PRICE ? FORCING : DISCOUNT;
                if (type != PriceModType.QUANTITY && criteria.picked(kind)) {
                    raw.add(new Object[]{l[0], l[1], l[2], kind, type.name(), l[4]});
                }
            }
        }
        if (criteria.picked(RETURN)) {
            for (Object[] r : query(criteria, "select r.creationDate, r.terminalId, r.cashierBadgeId,"
                    + " r.returnReasonCode, r.returnReason from Refund r where r.creationDate >= :from"
                    + " and r.creationDate < :to and r.returnReason is not null"
                    + criteria.terminalFilter("r.terminalId"), null, null)) {
                raw.add(new Object[]{r[0], r[1], r[2], RETURN, r[3], r[4]});
            }
        }
        if (criteria.picked(MOVEMENT)) {
            for (Object[] m : query(criteria, "select m.movementDate, m.terminalId, m.cashier.badgeId, m.type,"
                    + " m.reason from CashMovement m where m.reason is not null and m.reason <> ''"
                    + " and m.movementDate >= :from and m.movementDate < :to"
                    + criteria.terminalFilter("m.terminalId"), null, null)) {
                raw.add(new Object[]{m[0], m[1], m[2], MOVEMENT, String.valueOf(m[3]), m[4]});
            }
        }
        if (criteria.picked(SUPERVISION)) {
            for (Object[] e : query(criteria, "select e.eventDate, e.terminalId, e.operatorBadgeId, e.eventType,"
                    + " e.detail from TechnicalEvent e where e.eventType in :types and e.eventDate >= :from"
                    + " and e.eventDate < :to" + criteria.terminalFilter("e.terminalId"), "types", JUSTIFIED)) {
                String detail = (String) e[4];
                // A survey answer travels on the supervisor-call road but is no
                // reason code: the survey states report it (BO-06-03-02).
                if (detail != null && detail.startsWith(SURVEY)) {
                    continue;
                }
                raw.add(new Object[]{e[0], e[1], e[2], SUPERVISION, String.valueOf(e[3]),
                        supervisionLabel((TechnicalEvent.EventType) e[3], detail)});
            }
        }
        List<List<Object>> rows = render(criteria, raw);
        LOGGER.info("Exiting method rows with " + rows.size() + " row(s)");
        return rows;
    }

    /**
     * Turns the gathered gestures into rows, naming each cashier and keeping
     * only the cashiers chosen.
     *
     * @param criteria the edition parameters
     * @param raw the gestures: moment, register, badge, type, reason code, reason label
     * @return the rows, oldest first
     */
    private List<List<Object>> render(ReportCriteria criteria, List<Object[]> raw) {
        Map<String, Employee> employees = new HashMap<>();
        for (Employee employee : entityManager.createQuery(
                "select e from Employee e where e.badgeId is not null", Employee.class).getResultList()) {
            employees.put(employee.badgeId, employee);
        }
        // A supervisor's endorsement journals the supervisor's LOGIN where a
        // cashier's gesture journals a badge: both name the employee.
        for (Employee employee : new ArrayList<>(employees.values())) {
            if (employee.loginName != null) {
                employees.putIfAbsent(employee.loginName, employee);
            }
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] gesture : raw) {
            String badge = gesture[2] == null ? "" : String.valueOf(gesture[2]);
            if (!criteria.keepsCashier(badge)) {
                continue;
            }
            Employee employee = employees.get(badge);
            rows.add(Arrays.asList(
                    gesture[0],
                    gesture[1] == null ? "" : gesture[1],
                    badge,
                    employee == null || employee.firstName == null ? "" : employee.firstName,
                    employee == null || employee.lastName == null ? "" : employee.lastName,
                    gesture[3],
                    TYPES.get((String) gesture[3]),
                    gesture[4] == null ? "" : gesture[4],
                    gesture[5] == null ? "" : gesture[5]));
        }
        rows.sort(Comparator.comparing(row -> row.get(0) == null ? LocalDateTime.MIN : (LocalDateTime) row.get(0)));
        return rows;
    }

    /**
     * Runs one source query over the period.
     *
     * @param criteria the edition parameters
     * @param jpql the query, bound on {@code from} and {@code to}
     * @param name the name of one more parameter, or null
     * @param value its value
     * @return the result rows
     */
    private List<Object[]> query(ReportCriteria criteria, String jpql, String name, Object value) {
        var query = entityManager.createQuery(jpql, Object[].class)
                .setParameter("from", criteria.from())
                .setParameter("to", criteria.to());
        if (name != null) {
            query.setParameter(name, value);
        }
        criteria.bindTerminals(query);
        return query.getResultList();
    }

    /**
     * Words a supervisor call or an endorsement in French the bundles can
     * translate: the register journals an endorsement as its action CODE
     * followed by who endorsed it, which a reader cannot be shown raw.
     *
     * @param type the journaled event type
     * @param detail the event's detail, possibly null
     * @return the label, the detail itself when it is no endorsement, empty for none
     */
    static String supervisionLabel(TechnicalEvent.EventType type, String detail) {
        if (detail == null) {
            return "";
        }
        java.util.regex.Matcher matcher = ENDORSEMENT.matcher(detail.trim());
        if (type == TechnicalEvent.EventType.SUPERVISOR_CALLED || !matcher.matches()) {
            return detail;
        }
        String action = actionLabel(matcher.group(1));
        return type == TechnicalEvent.EventType.ENDORSEMENT_DENIED
                ? "Aval refusé — " + matcher.group(2) + " : " + action
                : "Aval de " + matcher.group(2) + " : " + action;
    }

    /**
     * Reads the French label of a register action code: the code whole, or
     * stripped of its trailing segments (the method, the identifier the
     * register appends: {@code REFUND_VOUCHER_2} reads as a refund).
     *
     * @param code the action code
     * @return its label, the code itself when no bundle labels it
     */
    static String actionLabel(String code) {
        com.intermarche.edge.ui.admin.BackOfficeMessages french =
                com.intermarche.edge.ui.admin.BackOfficeMessages.in(
                        com.intermarche.edge.ui.admin.BackOfficeMessages.DEFAULT_LANGUAGE);
        String candidate = code;
        while (true) {
            String label = french.code(ACTION_PREFIX, candidate);
            if (!label.equals(candidate)) {
                return label;
            }
            int cut = candidate.lastIndexOf('_');
            if (cut <= 0) {
                return code;
            }
            candidate = candidate.substring(0, cut);
        }
    }

    /**
     * Reads the reason of a drawer opening out of the event's detail, written
     * by the register as {@code code - label (aval badge)}.
     *
     * @param detail the event's detail, possibly null
     * @return the reason's code and label, the code empty when none was chosen
     */
    static String[] drawerReason(String detail) {
        if (detail == null) {
            return new String[]{"", ""};
        }
        String why = detail;
        int endorsement = why.lastIndexOf(" (aval ");
        if (endorsement >= 0) {
            why = why.substring(0, endorsement);
        }
        int separator = why.indexOf(" - ");
        return separator < 0 ? new String[]{"", why.trim()}
                : new String[]{why.substring(0, separator).trim(), why.substring(separator + 3).trim()};
    }
}
