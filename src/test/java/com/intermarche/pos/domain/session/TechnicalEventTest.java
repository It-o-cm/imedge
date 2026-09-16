package com.intermarche.pos.domain.session;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TechnicalEvent}, targeting 100% branch coverage.
 * <p>
 * The class is a pure append-only journal entity: five public columns, a
 * nested {@link TechnicalEvent.EventType} enum and no methods, hence no
 * ternaries, null guards or short-circuits to split. It extends
 * {@link io.quarkus.hibernate.orm.panache.PanacheEntity} but declares no
 * static finder and never calls {@code persist()}, so no Panache mocking is
 * required. Coverage is complete once the field defaults, the read/write of
 * every public field and the enum's constant set and {@code valueOf}
 * round-trip are asserted. Each test is fully isolated and asserts absolute
 * expected values.
 */
class TechnicalEventTest {

    /**
     * A freshly constructed event leaves every field null (no defaults set).
     */
    @Test
    void defaultsAreNull() {
        TechnicalEvent event = new TechnicalEvent();
        Assertions.assertNull(event.eventType);
        Assertions.assertNull(event.terminalId);
        Assertions.assertNull(event.eventDate);
        Assertions.assertNull(event.detail);
        Assertions.assertNull(event.eventUid);
    }

    /**
     * All public fields accept and return their assigned values.
     */
    @Test
    void fieldsAreReadWrite() {
        TechnicalEvent event = new TechnicalEvent();
        LocalDateTime when = LocalDateTime.of(2026, 8, 3, 14, 30, 0);
        event.eventType = TechnicalEvent.EventType.TICKET_CLOSED;
        event.terminalId = "CAISSE-01";
        event.eventDate = when;
        event.detail = "ticket #4207";
        event.eventUid = "0f9a2b3c-4d5e-6f70-8192-a3b4c5d6e7f8";
        Assertions.assertEquals(TechnicalEvent.EventType.TICKET_CLOSED, event.eventType);
        Assertions.assertEquals("CAISSE-01", event.terminalId);
        Assertions.assertEquals(when, event.eventDate);
        Assertions.assertEquals("ticket #4207", event.detail);
        Assertions.assertEquals("0f9a2b3c-4d5e-6f70-8192-a3b4c5d6e7f8", event.eventUid);
    }

    /**
     * The nested EventType enum exposes exactly its twenty-seven declared
     * constants (twenty NF525 types, the four operator-security events, the two
     * sync-supervision relaunch events of lot C2, and the GS1 decoding trace of
     * LC-11-03-02).
     */
    @Test
    void eventTypeEnumHasTwentySevenConstants() {
        Assertions.assertEquals(27, TechnicalEvent.EventType.values().length);
    }

    /**
     * Every EventType constant round-trips through valueOf under its own name.
     */
    @Test
    void eventTypeValueOfRoundTrips() {
        for (TechnicalEvent.EventType type : TechnicalEvent.EventType.values()) {
            Assertions.assertSame(type, TechnicalEvent.EventType.valueOf(type.name()));
        }
    }

    /**
     * The declared constants match the expected NF525 journal vocabulary and order.
     */
    @Test
    void eventTypeConstantsAreTheExpectedVocabulary() {
        TechnicalEvent.EventType[] expected = {
                TechnicalEvent.EventType.TICKET_CLOSED,
                TechnicalEvent.EventType.TICKET_CANCELLED,
                TechnicalEvent.EventType.DUPLICATA_PRINTED,
                TechnicalEvent.EventType.PAYMENTS_CLEARED,
                TechnicalEvent.EventType.DRAFT_RECOVERED,
                TechnicalEvent.EventType.AGE_CHECK_CONFIRMED,
                TechnicalEvent.EventType.AGE_CHECK_REFUSED,
                TechnicalEvent.EventType.SESSION_OPENED,
                TechnicalEvent.EventType.SESSION_CLOSED,
                TechnicalEvent.EventType.X_REPORT_PRINTED,
                TechnicalEvent.EventType.ENDORSEMENT_GRANTED,
                TechnicalEvent.EventType.ENDORSEMENT_DENIED,
                TechnicalEvent.EventType.AUTH_LOCKED,
                TechnicalEvent.EventType.TICKET_PARKED,
                TechnicalEvent.EventType.TICKET_RESUMED,
                TechnicalEvent.EventType.REFUND_CREATED,
                TechnicalEvent.EventType.DIGITAL_TICKET_SENT,
                TechnicalEvent.EventType.SUPERVISOR_CALLED,
                TechnicalEvent.EventType.TRAINING_STARTED,
                TechnicalEvent.EventType.TRAINING_ENDED,
                TechnicalEvent.EventType.REGISTER_LOCKED,
                TechnicalEvent.EventType.REGISTER_UNLOCKED,
                TechnicalEvent.EventType.PASSWORD_CHANGED,
                TechnicalEvent.EventType.PASSWORD_FAILED,
                TechnicalEvent.EventType.REFERENTIAL_PULL_FORCED,
                TechnicalEvent.EventType.ENGINE_FEED_DELIVERY_FORCED,
                TechnicalEvent.EventType.GS1_DECODED
        };
        Assertions.assertArrayEquals(expected, TechnicalEvent.EventType.values());
    }
}
