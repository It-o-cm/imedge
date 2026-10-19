package com.intermarche.edge.domain.setting;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StoreMessage}, and above all for {@code appliesTo},
 * which is the whole rule of the channel (BO-03-08-04).
 *
 * <p>Branch enumeration: the deactivated leg; the period with no start, no end,
 * both, and a moment before, inside and after it, each boundary at its exact
 * value; and the four audiences, each with a matching value, a mismatching one
 * and a null one. The entity carries no finder worth mocking — the rule is a
 * pure function of the row and the moment, which is why it lives here and not
 * in the resource.
 */
class StoreMessageTest {

    /** A moment the tests reason around. */
    private static final LocalDateTime NOON = LocalDateTime.of(2026, 10, 12, 12, 0);

    /**
     * A message addressed to every register, switched on, with no period.
     *
     * @return the message
     */
    private StoreMessage everyone() {
        StoreMessage message = new StoreMessage();
        message.text = "FERMETURE A 19H";
        message.audience = StoreMessage.Audience.ALL;
        message.active = true;
        return message;
    }

    /**
     * A switched-off message is served to nobody, whatever else it says — the
     * first guard, and the one a store uses to take a notice down.
     */
    @Test
    void aDeactivatedMessageIsServedToNobody() {
        StoreMessage message = everyone();
        message.active = false;
        assertFalse(message.appliesTo("C04", "ILOT1", NOON));
    }

    /**
     * No period at all: the message is served from the moment it is written and
     * until it is switched off — a store writing "until further notice" does
     * not have to invent a date.
     */
    @Test
    void aMessageWithNoPeriodIsAlwaysServed() {
        assertTrue(everyone().appliesTo("C04", "ILOT1", NOON));
    }

    /**
     * A period that has not begun, one that has ended, and the two boundaries:
     * the start is INCLUSIVE and so is the end, because a notice posted for
     * noon must be served at noon.
     */
    @Test
    void aPeriodIsHonouredAtBothEnds() {
        StoreMessage future = everyone();
        future.startsAt = NOON.plusMinutes(1);
        assertFalse(future.appliesTo("C04", null, NOON));
        StoreMessage starting = everyone();
        starting.startsAt = NOON;
        assertTrue(starting.appliesTo("C04", null, NOON));
        StoreMessage past = everyone();
        past.endsAt = NOON.minusMinutes(1);
        assertFalse(past.appliesTo("C04", null, NOON));
        StoreMessage ending = everyone();
        ending.endsAt = NOON;
        assertTrue(ending.appliesTo("C04", null, NOON));
        StoreMessage inside = everyone();
        inside.startsAt = NOON.minusHours(1);
        inside.endsAt = NOON.plusHours(1);
        assertTrue(inside.appliesTo("C04", null, NOON));
    }

    /**
     * ONE REGISTER: served to the register it names and to no other, and to
     * none at all when the store named no register — the null-value leg, which
     * the screen refuses but a row written before it could carry.
     */
    @Test
    void aMessageForOneRegisterReachesThatRegisterOnly() {
        StoreMessage message = everyone();
        message.audience = StoreMessage.Audience.TERMINAL;
        message.audienceValue = "C04";
        assertTrue(message.appliesTo("C04", "ILOT1", NOON));
        assertFalse(message.appliesTo("C05", "ILOT1", NOON));
        message.audienceValue = null;
        assertFalse(message.appliesTo("C04", "ILOT1", NOON));
    }

    /**
     * ONE ISLAND: served to the registers of that island, and not to a register
     * the store has not attached to one.
     */
    @Test
    void aMessageForOneIslandReachesItsRegistersOnly() {
        StoreMessage message = everyone();
        message.audience = StoreMessage.Audience.ISLAND;
        message.audienceValue = "CLS";
        assertTrue(message.appliesTo("C04", "CLS", NOON));
        assertFalse(message.appliesTo("C04", "LIGNE", NOON));
        assertFalse(message.appliesTo("C04", null, NOON));
        message.audienceValue = null;
        assertFalse(message.appliesTo("C04", "CLS", NOON));
    }

    /**
     * ONE OPERATOR: served to EVERY register, because the node does not know
     * who is signed in where. The filtering is the register's, and this is the
     * only audience of the four that works that way — stated as a test so the
     * asymmetry is deliberate and not an oversight.
     */
    @Test
    void aMessageForOneOperatorIsServedEverywhere() {
        StoreMessage message = everyone();
        message.audience = StoreMessage.Audience.EMPLOYEE;
        message.audienceValue = "0042";
        assertTrue(message.appliesTo("C04", "CLS", NOON));
        assertTrue(message.appliesTo("C09", null, NOON));
    }

    /**
     * The period is checked BEFORE the audience: a message addressed to this
     * register but out of its period is not served, which is what lets a store
     * prepare tomorrow's notice today.
     */
    @Test
    void thePeriodOutranksTheAudience() {
        StoreMessage message = everyone();
        message.audience = StoreMessage.Audience.TERMINAL;
        message.audienceValue = "C04";
        message.startsAt = NOON.plusDays(1);
        assertFalse(message.appliesTo("C04", "CLS", NOON));
    }

    /**
     * The three enumerations name themselves to the administration screen: a
     * value with no label would show an empty option.
     */
    @Test
    void everyChoiceNamesItselfToTheScreen() {
        for (StoreMessage.Audience audience : StoreMessage.Audience.values()) {
            assertFalse(audience.getLabel().isBlank(), audience.name());
        }
        for (StoreMessage.Trigger trigger : StoreMessage.Trigger.values()) {
            assertFalse(trigger.getLabel().isBlank(), trigger.name());
        }
        for (StoreMessage.Confirmation confirmation : StoreMessage.Confirmation.values()) {
            assertFalse(confirmation.getLabel().isBlank(), confirmation.name());
        }
    }
}
