package com.intermarche.edge.ui.dashboard;

import com.intermarche.edge.domain.session.TechnicalEvent;

import java.util.List;

/**
 * The complementary indicators a back office may put on the supervision
 * dashboard ({@code BO-04-03-01}, {@code BO-04-03-03},
 * {@code BO-04-03-15}).
 * <p>
 * The dashboard's first row of tiles — revenue, tickets, average basket,
 * cancellation rate, refunds — is written in JPQL inside
 * {@link DashboardService} and cannot be chosen: every store sees the same
 * five. This catalog is the administrable second row. A store names the
 * indicators it wants, in the order it wants them, in the
 * {@code dashboard.indicators} parameter, and gets exactly those.
 * <p>
 * Most of them are a count of journal events of the day, so the catalog
 * carries the event types rather than a query: adding an indicator over the
 * journal is one constant here plus its name in the service's exhaustive
 * switch, which is what makes the compiler refuse a constant nobody taught
 * how to compute. The two that are not a journal count — the net cash moved
 * and the registers gone quiet — carry no event type and are computed by
 * name in {@link DashboardIndicatorService}.
 * <p>
 * An enum rather than CDI-discovered classes, unlike the report catalog:
 * an indicator is a label and a count, not a page of columns, and eleven
 * one-line constants beat eleven one-method beans.
 */
public enum DashboardIndicator {

    /** Manager endorsements granted today. */
    ENDORSEMENTS("endorsements", "Avals manager", Unit.COUNT,
            TechnicalEvent.EventType.ENDORSEMENT_GRANTED),

    /** Manager endorsements refused today. */
    ENDORSEMENTS_DENIED("endorsements-denied", "Avals refusés", Unit.COUNT,
            TechnicalEvent.EventType.ENDORSEMENT_DENIED),

    /** Customer ID checks made today, confirmed and refused alike. */
    AGE_CHECKS("age-checks", "Contrôles d'âge", Unit.COUNT,
            TechnicalEvent.EventType.AGE_CHECK_CONFIRMED,
            TechnicalEvent.EventType.AGE_CHECK_REFUSED),

    /** Supervisor calls raised from the lanes today. */
    SUPERVISOR_CALLS("supervisor-calls", "Appels superviseur", Unit.COUNT,
            TechnicalEvent.EventType.SUPERVISOR_CALLED),

    /** Carts parked today, whether resumed since or not. */
    PARKED_TICKETS("parked-tickets", "Tickets mis en attente", Unit.COUNT,
            TechnicalEvent.EventType.TICKET_PARKED),

    /** Already-paid tickets reversed today under a supervisor endorsement. */
    PAID_CANCELLED("paid-cancelled", "Tickets payés annulés", Unit.COUNT,
            TechnicalEvent.EventType.PAID_TICKET_CANCELLED),

    /** Duplicata receipts printed today. */
    DUPLICATES("duplicates", "Duplicatas imprimés", Unit.COUNT,
            TechnicalEvent.EventType.DUPLICATA_PRINTED),

    /** Prices looked up at a register today without selling anything. */
    PRICE_CHECKS("price-checks", "Consultations de prix", Unit.COUNT,
            TechnicalEvent.EventType.PRICE_CHECKED),

    /** Registers put into training mode today. */
    TRAINING("training", "Passages en formation", Unit.COUNT,
            TechnicalEvent.EventType.TRAINING_STARTED),

    /**
     * The NET amount moved in and out of the drawers today: deposits count
     * positive and withdrawals negative, exactly as they are recorded, so
     * the tile reads as the day's balance of hand movements and not as their
     * volume.
     */
    CASH_MOVEMENTS("cash-movements", "Mouvements de caisse", Unit.AMOUNT),

    /**
     * How many registers are currently declared disconnected — the same
     * count the red banners above the tiles show, as a number a manager can
     * watch at a glance.
     */
    OFFLINE_REGISTERS("offline-registers", "Caisses déconnectées", Unit.COUNT);

    /** What an indicator's value is measured in. */
    public enum Unit {

        /** A plain count: no suffix. */
        COUNT(""),

        /** A monetary amount, formatted with the French comma. */
        AMOUNT("€");

        /** The suffix the dashboard prints after the value. */
        private final String suffix;

        /**
         * Builds a unit.
         *
         * @param suffix the suffix printed after the value, possibly empty
         */
        Unit(String suffix) {
            this.suffix = suffix;
        }

        /**
         * The suffix the dashboard prints after the value.
         *
         * @return the suffix, empty for a count
         */
        public String suffix() {
            return suffix;
        }
    }

    /** The code naming this indicator in the administered list. */
    private final String code;

    /** The label shown on the tile. */
    private final String label;

    /** What the value is measured in. */
    private final Unit unit;

    /** The journal event types counted, empty when the value is computed. */
    private final List<TechnicalEvent.EventType> eventTypes;

    /**
     * Builds an indicator.
     *
     * @param code the code naming it in the administered list
     * @param label the label shown on the tile
     * @param unit what its value is measured in
     * @param eventTypes the journal event types to count, none when the
     *        value is computed by name instead
     */
    DashboardIndicator(String code, String label, Unit unit,
                       TechnicalEvent.EventType... eventTypes) {
        this.code = code;
        this.label = label;
        this.unit = unit;
        this.eventTypes = List.of(eventTypes);
    }

    /**
     * The code naming this indicator in the administered list.
     *
     * @return the code
     */
    public String code() {
        return code;
    }

    /**
     * The label shown on the tile.
     *
     * @return the label
     */
    public String label() {
        return label;
    }

    /**
     * What this indicator's value is measured in.
     *
     * @return the unit
     */
    public Unit unit() {
        return unit;
    }

    /**
     * The journal event types this indicator counts.
     *
     * @return the event types, empty when the value is computed by name
     */
    public List<TechnicalEvent.EventType> eventTypes() {
        return eventTypes;
    }

    /**
     * Finds an indicator by the code an administrator typed.
     *
     * @param code the code, possibly null, blank or unknown
     * @return the matching indicator, or null when there is none
     */
    public static DashboardIndicator byCode(String code) {
        if (code == null) {
            return null;
        }
        String wanted = code.trim();
        for (DashboardIndicator indicator : values()) {
            if (indicator.code.equals(wanted)) {
                return indicator;
            }
        }
        return null;
    }
}
