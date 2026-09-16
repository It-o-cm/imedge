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
}
