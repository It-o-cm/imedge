package com.intermarche.pos.ui.journal;

import com.intermarche.pos.domain.session.TechnicalEvent;

/**
 * Resolves the functional event-type names posted by the functional journal
 * tab to the {@link TechnicalEvent.EventType} enum, treating an unknown name
 * as absent so a stale or hand-typed value narrows nothing rather than
 * raising.
 */
public final class EventTypes {

    /**
     * Not instantiable.
     */
    private EventTypes() {
    }

    /**
     * Resolves an event-type name to its enum constant.
     *
     * @param name the event-type name, possibly unknown or null
     * @return the enum constant, or null when the name is unknown or null
     */
    public static TechnicalEvent.EventType forName(String name) {
        if (name == null) {
            return null;
        }
        for (TechnicalEvent.EventType type : TechnicalEvent.EventType.values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return null;
    }
}
