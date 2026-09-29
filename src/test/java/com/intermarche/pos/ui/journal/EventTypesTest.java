package com.intermarche.pos.ui.journal;

import com.intermarche.pos.domain.session.TechnicalEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link EventTypes}.
 * <p>
 * Branch enumeration (100%): {@code forName} covers the null-name arm, the
 * matched-name arm (an enum constant is returned) and the unknown-name arm
 * (loop exhausts, null).
 */
class EventTypesTest {

    /**
     * A null name resolves to null (null arm).
     */
    @Test
    void nullNameResolvesToNull() {
        assertNull(EventTypes.forName(null));
    }

    /**
     * A known name resolves to its enum constant (matched arm).
     */
    @Test
    void knownNameResolves() {
        assertEquals(TechnicalEvent.EventType.SESSION_CLOSED, EventTypes.forName("SESSION_CLOSED"));
    }

    /**
     * An unknown name resolves to null (loop-exhausted arm).
     */
    @Test
    void unknownNameResolvesToNull() {
        assertNull(EventTypes.forName("NOPE"));
    }

    /**
     * The expert-caisse event types (chantier B) resolve by name, so the journal
     * filters and searches them like any other (EXP-14/17/33/35/82/96, §3).
     */
    @Test
    void expertCaisseEventTypesResolve() {
        assertEquals(TechnicalEvent.EventType.REGISTER_UNLOCK_FORCED, EventTypes.forName("REGISTER_UNLOCK_FORCED"));
        assertEquals(TechnicalEvent.EventType.SESSION_FORCE_CLOSED, EventTypes.forName("SESSION_FORCE_CLOSED"));
        assertEquals(TechnicalEvent.EventType.TILL_CLOSED, EventTypes.forName("TILL_CLOSED"));
        assertEquals(TechnicalEvent.EventType.TILL_REOPENED, EventTypes.forName("TILL_REOPENED"));
        assertEquals(TechnicalEvent.EventType.SUPERVISOR_SESSION_OPENED, EventTypes.forName("SUPERVISOR_SESSION_OPENED"));
        assertEquals(TechnicalEvent.EventType.SUPERVISOR_SESSION_CLOSED, EventTypes.forName("SUPERVISOR_SESSION_CLOSED"));
        assertEquals(TechnicalEvent.EventType.DRAWER_CEILING_REACHED, EventTypes.forName("DRAWER_CEILING_REACHED"));
        assertEquals(TechnicalEvent.EventType.DISCREPANCY_VALIDATED, EventTypes.forName("DISCREPANCY_VALIDATED"));
        assertEquals(TechnicalEvent.EventType.LAST_ITEM_CANCEL_REFUSED, EventTypes.forName("LAST_ITEM_CANCEL_REFUSED"));
    }
}
