package com.intermarche.edge.domain.session;

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
     * The nested EventType enum exposes exactly its thirty-four declared constants:
     * the register vocabulary, the two expert-caisse events impos appended
     * (aligned to impos, source of truth) — PAID_TICKET_CANCELLED (EXP-49) and
     * SOGEC_COMPENSATION (EXP-58) — the node's own forced at-the-flow delivery
     * pass, STREAM_DELIVERY_FORCED (BO-11-02-05), the node's own X/Z financial
     * states — FINANCIAL_STATE_EMITTED and FINANCIAL_STATE_READ — and the
     * node's own accepted back-office sign-in, BACK_OFFICE_SIGNED_IN
     * (BO-04-03-04).
     */
    @Test
    void eventTypeEnumHasThirtyFourConstants() {
        Assertions.assertEquals(34, TechnicalEvent.EventType.values().length);
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
                TechnicalEvent.EventType.PRICE_CHECKED,
                TechnicalEvent.EventType.REGISTER_LOCKED,
                TechnicalEvent.EventType.REGISTER_UNLOCKED,
                TechnicalEvent.EventType.PASSWORD_CHANGED,
                TechnicalEvent.EventType.PASSWORD_FAILED,
                TechnicalEvent.EventType.REFERENTIAL_PULL_FORCED,
                TechnicalEvent.EventType.ENGINE_FEED_DELIVERY_FORCED,
                TechnicalEvent.EventType.STREAM_DELIVERY_FORCED,
                TechnicalEvent.EventType.GS1_DECODED,
                TechnicalEvent.EventType.PAID_TICKET_CANCELLED,
                TechnicalEvent.EventType.SOGEC_COMPENSATION,
                TechnicalEvent.EventType.FINANCIAL_STATE_EMITTED,
                TechnicalEvent.EventType.FINANCIAL_STATE_READ,
                TechnicalEvent.EventType.BACK_OFFICE_SIGNED_IN
        };
        Assertions.assertArrayEquals(expected, TechnicalEvent.EventType.values());
    }
}
