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
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String token : tokens()) {
            if (!seen.add(token.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            DashboardIndicator indicator = DashboardIndicator.byCode(token);
            Map<String, Object> entry = indicator == null ? generic(token, from) : new HashMap<>();
            if (indicator != null) {
                entry.put("code", indicator.code());
                entry.put("label", indicator.label());
                entry.put("unit", indicator.unit().suffix());
                entry.put("value", valueOf(indicator, from));
            }
            if (entry != null) {
                rows.add(entry);
            }
        }
        LOGGER.info("Exiting method indicators");
        return rows;
    }

    /**
     * The administered indicator codes, trimmed, blanks dropped; none when the
     * list cannot be read.
     *
     * @return the codes in their administered order
     */
    List<String> tokens() {
        List<String> tokens = new ArrayList<>();
        String raw;
        try {
            raw = posSettingsService.dashboardIndicators();
        } catch (Exception unreadable) {
            return tokens;
        }
        if (raw == null) {
            return tokens;
        }
        for (String token : raw.split(";")) {
            if (!token.isBlank()) {
                tokens.add(token.trim());
            }
        }
        return tokens;
    }

    /**
     * Names the code a tile's label carries, for the screen to show it by its
     * label in the page's language (BO-04-03-03): an event type, a payment
     * method, a movement type.
     *
     * @param entry the tile
     * @param prefix the prefix of the code's labels in the bundles
     * @param code the code the label carries
     */
    static void subject(Map<String, Object> entry, String prefix, String code) {
        entry.put("subjectPrefix", prefix);
        entry.put("subject", code);
    }

    /**
     * An OPEN indicator (BO-04-03-03/-15): any datum of the system the store
     * names — {@code event:TYPE} counts the day's journal events of a type,
     * so every use of a control is addressable; {@code payment:KEY} totals the
     * day's settlements of a method; {@code family:CODE} the day's revenue of
     * a branch of the nomenclature; {@code movement:TYPE} the day's cash
     * movements of a type.
     *
     * @param token the administered code
     * @param from the start of the day
     * @return the tile, or null when the code names nothing
     */
    Map<String, Object> generic(String token, LocalDateTime from) {
        int colon = token.indexOf(':');
        if (colon <= 0 || colon == token.length() - 1) {
            return null;
        }
        String kind = token.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
        String value = token.substring(colon + 1).trim();
        Map<String, Object> entry = new HashMap<>();
        entry.put("code", token);
        switch (kind) {
            case "event" -> {
                TechnicalEvent.EventType type = eventType(value);
                if (type == null) {
                    return null;
                }
                entry.put("label", "Événements " + type.name());
                subject(entry, "t.s21-ev-", type.name());
                entry.put("unit", DashboardIndicator.Unit.COUNT.suffix());
                entry.put("value", Long.toString(countEvents(List.of(type), from)));
            }
            case "payment" -> {
                Class<?> type = com.intermarche.edge.domain.payment.PaymentTypes.forKey(value.toUpperCase(java.util.Locale.ROOT));
                if (type == null) {
                    return null;
                }
                entry.put("label", "Règlements " + value.toUpperCase(java.util.Locale.ROOT));
                subject(entry, "t.s21-pm-", value.toUpperCase(java.util.Locale.ROOT));
                entry.put("unit", DashboardIndicator.Unit.AMOUNT.suffix());
                entry.put("value", amount(entityManager.createQuery(
                                "select coalesce(sum(p.amount), 0) from Ticket t join t.payments p"
                                        + " where t.status = :status and t.closingDate >= :from and type(p) = :type",
                                BigDecimal.class)
                        .setParameter("status", com.intermarche.edge.domain.sale.Ticket.TicketStatus.CLOSED)
                        .setParameter("from", from)
                        .setParameter("type", type)
                        .getSingleResult()));
            }
            case "family" -> {
                entry.put("label", "CA famille " + value);
                entry.put("unit", DashboardIndicator.Unit.AMOUNT.suffix());
                entry.put("value", amount(entityManager.createQuery(
                                "select coalesce(sum(l.totalPrice), 0) from Ticket t join t.lines l"
                                        + " where t.status = :status and t.closingDate >= :from"
                                        + " and l.cancelled = false and l.familyCode like :family", BigDecimal.class)
                        .setParameter("status", com.intermarche.edge.domain.sale.Ticket.TicketStatus.CLOSED)
                        .setParameter("from", from)
                        .setParameter("family", value + "%")
                        .getSingleResult()));
            }
            case "movement" -> {
                com.intermarche.edge.domain.session.CashMovement.MovementType type = movementType(value);
                if (type == null) {
                    return null;
                }
                entry.put("label", "Mouvements " + type.name());
                subject(entry, "t.s21-mv-", type.name());
                entry.put("unit", DashboardIndicator.Unit.AMOUNT.suffix());
                entry.put("value", amount(entityManager.createQuery(
                                "select coalesce(sum(m.amount), 0) from CashMovement m"
                                        + " where m.movementDate >= :from and m.type = :type", BigDecimal.class)
                        .setParameter("from", from)
                        .setParameter("type", type)
                        .getSingleResult()));
            }
            default -> {
                return null;
            }
        }
        return entry;
    }

    /**
     * Reads a journal event type, ignoring case.
     *
     * @param name the typed name
     * @return the type, or null
     */
    static TechnicalEvent.EventType eventType(String name) {
        for (TechnicalEvent.EventType type : TechnicalEvent.EventType.values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Reads a cash-movement type, ignoring case.
     *
     * @param name the typed name
     * @return the type, or null
     */
    static com.intermarche.edge.domain.session.CashMovement.MovementType movementType(String name) {
        for (com.intermarche.edge.domain.session.CashMovement.MovementType type
                : com.intermarche.edge.domain.session.CashMovement.MovementType.values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Writes an amount the French way, a null read as zero.
     *
     * @param amount the amount, possibly null
     * @return two decimals, a decimal comma
     */
    static String amount(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString().replace(".", ",");
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
