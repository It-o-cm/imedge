package com.intermarche.edge.ui.dashboard;

import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.sync.RegisterPresenceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Computes the administrable row of dashboard tiles ({@code BO-04-03-01},
 * {@code BO-04-03-03}, {@code BO-04-03-15}).
 * <p>
 * The store names the indicators it wants in {@code dashboard.indicators},
 * semicolon-separated, IN DISPLAY ORDER; this service reads that list,
 * computes each one over the day and hands the dashboard pre-formatted
 * values, exactly as {@link DashboardService} does for the fixed tiles — the
 * page stays a dumb renderer.
 * <p>
 * Semantics: "today" is the node's calendar day (midnight boundary), the
 * same boundary the fixed tiles use. An unknown code is IGNORED rather than
 * refused: a parameter typed by hand must not be able to take the
 * supervision screen down, and a missing tile says more plainly than an
 * error page that the code is not one we know. A code named twice appears
 * once. A blank list means no complementary tile at all, which is how a
 * store switches the row off.
 * <p>
 * Reading the parameters is itself guarded: a settings table that cannot be
 * read leaves the row empty instead of breaking the page a manager watches
 * all day.
 */
@ApplicationScoped
public class DashboardIndicatorService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(DashboardIndicatorService.class);

    /** The aggregation channel: these counts are beyond Panache finders. */
    @Inject
    EntityManager entityManager;

    /** The parameters, read for the administered indicator list. */
    @Inject
    PosSettingsService posSettingsService;

    /** The presence book, read by the disconnected-registers indicator. */
    @Inject
    RegisterPresenceService registerPresenceService;

    /**
     * The indicators the store asked for, in display order.
     *
     * @return the chosen indicators, empty when the parameter is blank,
     *         names nothing we know, or cannot be read at all
     */
    public List<DashboardIndicator> selected() {
        LOGGER.info("Entering method selected");
        String raw;
        try {
            raw = posSettingsService.dashboardIndicators();
        } catch (Exception unreadable) {
            LOGGER.debugf("Liste des indicateurs illisible (%s)", unreadable.getMessage());
            LOGGER.info("Exiting method selected");
            return List.of();
        }
        List<DashboardIndicator> chosen = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            LOGGER.info("Exiting method selected");
            return chosen;
        }
        for (String token : raw.split(";")) {
            DashboardIndicator indicator = DashboardIndicator.byCode(token);
            if (indicator != null && !chosen.contains(indicator)) {
                chosen.add(indicator);
            }
        }
        LOGGER.info("Exiting method selected");
        return chosen;
    }

    /**
     * Computes the administrable tiles of the day, JSON-ready.
     *
     * @return one map per chosen indicator, carrying its code, its label,
     *         its pre-formatted value and the suffix to print after it
     */
    public List<Map<String, Object>> indicators() {
        LOGGER.info("Entering method indicators");
        LocalDateTime from = LocalDate.now().atStartOfDay();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DashboardIndicator indicator : selected()) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("code", indicator.code());
            entry.put("label", indicator.label());
            entry.put("unit", indicator.unit().suffix());
            entry.put("value", valueOf(indicator, from));
            rows.add(entry);
        }
        LOGGER.info("Exiting method indicators");
        return rows;
    }

    /**
     * Computes one indicator over the day.
     *
     * <p>Deliberately EXHAUSTIVE and without a default arm: a new constant
     * added to {@link DashboardIndicator} must not compile until somebody
     * has said how it is computed. A silent fallback would have shipped a
     * tile reading zero for ever, which is worse than a build failure.
     *
     * @param indicator the indicator to compute
     * @param from the start of the day
     * @return its pre-formatted value
     */
    private String valueOf(DashboardIndicator indicator, LocalDateTime from) {
        return switch (indicator) {
            case CASH_MOVEMENTS -> movedCash(from);
            case OFFLINE_REGISTERS -> Integer.toString(registerPresenceService.offline().size());
            case ENDORSEMENTS, ENDORSEMENTS_DENIED, AGE_CHECKS, SUPERVISOR_CALLS,
                    PARKED_TICKETS, PAID_CANCELLED, DUPLICATES, PRICE_CHECKS, TRAINING ->
                    Long.toString(countEvents(indicator.eventTypes(), from));
        };
    }

    /**
     * Counts the journal events of the day carrying one of the given types.
     *
     * @param types the event types counted together
     * @param from the start of the day
     * @return the count, zero when the query answers nothing
     */
    private long countEvents(List<TechnicalEvent.EventType> types, LocalDateTime from) {
        Long count = entityManager.createQuery(
                        "select count(e) from TechnicalEvent e"
                                + " where e.eventType in :types and e.eventDate >= :from", Long.class)
                .setParameter("types", types)
                .setParameter("from", from)
                .getSingleResult();
        return count == null ? 0L : count;
    }

    /**
     * The net amount moved in and out of the drawers today.
     *
     * @param from the start of the day
     * @return the amount, formatted with two decimals and a French comma
     */
    private String movedCash(LocalDateTime from) {
        BigDecimal total = entityManager.createQuery(
                        "select coalesce(sum(m.amount), 0) from CashMovement m"
                                + " where m.movementDate >= :from", BigDecimal.class)
                .setParameter("from", from)
                .getSingleResult();
        BigDecimal moved = total == null ? BigDecimal.ZERO : total;
        return moved.setScale(2, RoundingMode.HALF_UP).toPlainString().replace(".", ",");
    }
}
