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
     * The nested EventType enum exposes exactly its fifty-three declared constants
     * (the NF525 types, the operator-security events, the sync-supervision
     * relaunch events, the GS1 decoding trace, the paid-ticket cancellation of
     * EXP-49, the SOGEC compensation of EXP-58, the ticket reassignment of
     * LC-03-06-01..06 and the price check): the register vocabulary, the two
     * expert-caisse events impos appended — PAID_TICKET_CANCELLED (EXP-49) and
     * SOGEC_COMPENSATION (EXP-58) — the register's own TICKET_REASSIGNED of the
     * reprint lane (impos reassigns a ticket to another register) and its
     * administered return type, RETURN_TYPE_SELECTED (LC-05-04-01), the node's
     * own forced at-the-flow delivery pass, STREAM_DELIVERY_FORCED
     * (BO-11-02-05), the node's own X/Z financial states —
     * FINANCIAL_STATE_EMITTED and FINANCIAL_STATE_READ — the node's own
     * accepted back-office sign-in, BACK_OFFICE_SIGNED_IN (BO-04-03-04), and
     * its own automatic end-of-period editions, SCHEDULED_REPORTS_EDITED
     * (BO-09-01-10), the cashier's declaration of the vignettes she
     * handed out, VIGNETTES_DECLARED (BO-11-01-20), the eleven journal and
     * control events of BO-04 (self-scanning controls, unknown codes,
     * cancelled payments, drawer pulses, price modifications, line
     * cancellations, control alerts, justifications, watched menus and
     * handled supervisions) and the register's timed cash operations,
     * CASH_OPERATION_TIMED (BO-06-06-03).
     */
    @Test
    void eventTypeEnumHasFiftyThreeConstants() {
        Assertions.assertEquals(53, TechnicalEvent.EventType.values().length);
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
                TechnicalEvent.EventType.TICKET_REASSIGNED,
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
                TechnicalEvent.EventType.BACK_OFFICE_SIGNED_IN,
                TechnicalEvent.EventType.SCHEDULED_REPORTS_EDITED,
                TechnicalEvent.EventType.WORKSTATION_REQUEST,
                TechnicalEvent.EventType.TOUCH_USED,
                TechnicalEvent.EventType.DRAWER_FORCED_OPEN,
                TechnicalEvent.EventType.RETURN_TYPE_SELECTED,
                TechnicalEvent.EventType.VIGNETTES_DECLARED,
                TechnicalEvent.EventType.SELF_SCAN_CONTROLLED,
                TechnicalEvent.EventType.SELF_SCAN_VERIFIED,
                TechnicalEvent.EventType.UNKNOWN_ARTICLE_SCANNED,
                TechnicalEvent.EventType.PAYMENT_CANCELLED,
                TechnicalEvent.EventType.DRAWER_OPENED,
                TechnicalEvent.EventType.PRICE_MODIFIED,
                TechnicalEvent.EventType.LINE_CANCELLED,
                TechnicalEvent.EventType.CONTROL_ALERT,
                TechnicalEvent.EventType.JUSTIFICATION_CHOSEN,
                TechnicalEvent.EventType.BACK_OFFICE_MENU_OPENED,
                TechnicalEvent.EventType.SUPERVISION_HANDLED,
                TechnicalEvent.EventType.CASH_OPERATION_TIMED
        };
        Assertions.assertArrayEquals(expected, TechnicalEvent.EventType.values());
    }
}
