package com.intermarche.edge.ui.journal;

import com.intermarche.edge.domain.session.TechnicalEvent;
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
     * The expert-caisse event types impos appended (aligned to impos) resolve by
     * name, so the journal filters and searches them like any other (EXP-49/58).
     */
    @Test
    void expertCaisseEventTypesResolve() {
        assertEquals(TechnicalEvent.EventType.PAID_TICKET_CANCELLED, EventTypes.forName("PAID_TICKET_CANCELLED"));
        assertEquals(TechnicalEvent.EventType.SOGEC_COMPENSATION, EventTypes.forName("SOGEC_COMPENSATION"));
    }
}
