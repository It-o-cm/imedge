package com.intermarche.edge.ui.dashboard;

import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.jboss.logging.Logger;

import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The REAL-TIME watch of the registers (BO-04-03-07): the operations the
 * lanes make — sales, cancellations, drawer openings, remises, refunds,
 * cancelled settlements, avals, alerts — one by one as the registers push
 * them, the newest first, for the member, the manager or the supervisor who
 * follows the shop live and looks for anomalies.
 */
@ApplicationScoped
public class LiveActivityService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(LiveActivityService.class);

    /** How many operations one read returns at most. */
    static final int LIMIT = 50;

    /** The time of an operation. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Inject
    EntityManager entityManager;

    @Inject
    PosSettingsService posSettingsService;

    /**
     * The operations recorded after a given one, of the administered types,
     * newest first.
     *
     * @param after the id of the last operation the page shows, or null for the latest ones
     * @param terminal the register watched, or null for every register
     * @return the operations: id, register, operator, type, time, amount, ticket, detail
     */
    public List<Map<String, Object>> after(Long after, String terminal) {
        LOGGER.info("Entering method after with after: " + after);
        List<TechnicalEvent.EventType> types = new ArrayList<>();
        for (String name : posSettingsService.liveEventTypes()) {
            for (TechnicalEvent.EventType type : TechnicalEvent.EventType.values()) {
                if (type.name().equalsIgnoreCase(name)) {
                    types.add(type);
                }
            }
        }
        StringBuilder jpql = new StringBuilder("select e from TechnicalEvent e where e.id > :after");
        if (!types.isEmpty()) {
            jpql.append(" and e.eventType in :types");
        }
        if (terminal != null && !terminal.isBlank()) {
            jpql.append(" and e.terminalId = :terminal");
        }
        jpql.append(" order by e.id desc");
        TypedQuery<TechnicalEvent> query = entityManager.createQuery(jpql.toString(), TechnicalEvent.class)
                .setParameter("after", after == null ? 0L : after)
                .setMaxResults(LIMIT);
        if (!types.isEmpty()) {
            query.setParameter("types", types);
        }
        if (terminal != null && !terminal.isBlank()) {
            query.setParameter("terminal", terminal.trim());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TechnicalEvent event : query.getResultList()) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", event.id);
            row.put("terminal", event.terminalId);
            row.put("operator", event.operatorBadgeId == null ? "" : event.operatorBadgeId);
            row.put("type", event.eventType.name());
            row.put("time", event.eventDate.format(TIME));
            row.put("amount", event.amount == null ? ""
                    : event.amount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ','));
            row.put("ticket", event.ticketNumber == null ? "" : event.ticketNumber);
            row.put("detail", event.detail == null ? "" : event.detail);
            rows.add(row);
        }
        LOGGER.info("Exiting method after");
        return rows;
    }
}
