package com.intermarche.pos.domain.sync;

import com.intermarche.pos.domain.sync.SyncOutbox.EntityType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SyncOutbox}, targeting 100% branch coverage.
 * <p>
 * {@code SyncOutbox} is a pure Panache data entity: it declares five public
 * columns and no behaviour, hence carries no branches. Its nested
 * {@link SyncOutbox.EntityType} enum is likewise plain, exposing only the
 * compiler-synthesized {@code values()} and {@code valueOf(String)} members.
 * The tests exercise the default constructor, confirm each field is an
 * independently writable plain reference, and pin the enum constants whose
 * ordinal drives the drain order. Each test is fully isolated and asserts
 * absolute expected values.
 */
class SyncOutboxTest {

    /**
     * A fresh SyncOutbox carries null reference defaults and a zeroed attempts
     * counter, no field being pre-set.
     */
    @Test
    void fieldDefaultsAreUnset() {
        SyncOutbox outbox = new SyncOutbox();
        Assertions.assertNull(outbox.entityType);
        Assertions.assertNull(outbox.entityId);
        Assertions.assertNull(outbox.createdAt);
        Assertions.assertEquals(0, outbox.attempts);
        Assertions.assertNull(outbox.lastError);
    }

    /**
     * The five columns are independently writable and read back verbatim.
     */
    @Test
    void fieldsAreWritableAndReadBack() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 3, 10, 15, 30);
        SyncOutbox outbox = new SyncOutbox();
        outbox.entityType = EntityType.TICKET;
        outbox.entityId = 42L;
        outbox.createdAt = createdAt;
        outbox.attempts = 3;
        outbox.lastError = "connection refused";
        Assertions.assertEquals(EntityType.TICKET, outbox.entityType);
        Assertions.assertEquals(42L, outbox.entityId);
        Assertions.assertEquals(createdAt, outbox.createdAt);
        Assertions.assertEquals(3, outbox.attempts);
        Assertions.assertEquals("connection refused", outbox.lastError);
    }

    /**
     * The nullable lastError column accepts an explicit null after being set.
     */
    @Test
    void lastErrorAcceptsNull() {
        SyncOutbox outbox = new SyncOutbox();
        outbox.lastError = "boom";
        outbox.lastError = null;
        Assertions.assertNull(outbox.lastError);
    }

    /**
     * values exposes exactly the six declared constants in declaration order,
     * the ordinal being the documented drain order: MOVEMENT drains right after
     * SESSION (its sole dependency) and ahead of the tickets and refunds it is
     * independent of (lot C5a), CUSTOMER last since it references nothing
     * (LC-08-04-09).
     */
    @Test
    void entityTypeValuesHoldsSixConstantsInOrder() {
        EntityType[] values = EntityType.values();
        Assertions.assertEquals(6, values.length);
        Assertions.assertEquals(EntityType.SESSION, values[0]);
        Assertions.assertEquals(EntityType.MOVEMENT, values[1]);
        Assertions.assertEquals(EntityType.TICKET, values[2]);
        Assertions.assertEquals(EntityType.REFUND, values[3]);
        Assertions.assertEquals(EntityType.EVENT, values[4]);
        Assertions.assertEquals(EntityType.CUSTOMER, values[5]);
    }

    /**
     * Each constant reports its declared ordinal, pinning the drain order.
     */
    @Test
    void entityTypeOrdinalsAreStable() {
        Assertions.assertEquals(0, EntityType.SESSION.ordinal());
        Assertions.assertEquals(1, EntityType.MOVEMENT.ordinal());
        Assertions.assertEquals(2, EntityType.TICKET.ordinal());
        Assertions.assertEquals(3, EntityType.REFUND.ordinal());
        Assertions.assertEquals(4, EntityType.EVENT.ordinal());
        Assertions.assertEquals(5, EntityType.CUSTOMER.ordinal());
    }

    /**
     * Each constant reports its declared name.
     */
    @Test
    void entityTypeNamesMatchConstants() {
        Assertions.assertEquals("SESSION", EntityType.SESSION.name());
        Assertions.assertEquals("MOVEMENT", EntityType.MOVEMENT.name());
        Assertions.assertEquals("TICKET", EntityType.TICKET.name());
        Assertions.assertEquals("REFUND", EntityType.REFUND.name());
        Assertions.assertEquals("EVENT", EntityType.EVENT.name());
        Assertions.assertEquals("CUSTOMER", EntityType.CUSTOMER.name());
    }

    /**
     * valueOf round-trips each declared name back to its constant.
     */
    @Test
    void entityTypeValueOfResolvesEachConstant() {
        Assertions.assertSame(EntityType.SESSION, EntityType.valueOf("SESSION"));
        Assertions.assertSame(EntityType.MOVEMENT, EntityType.valueOf("MOVEMENT"));
        Assertions.assertSame(EntityType.TICKET, EntityType.valueOf("TICKET"));
        Assertions.assertSame(EntityType.REFUND, EntityType.valueOf("REFUND"));
        Assertions.assertSame(EntityType.EVENT, EntityType.valueOf("EVENT"));
        Assertions.assertSame(EntityType.CUSTOMER, EntityType.valueOf("CUSTOMER"));
    }

    /**
     * valueOf rejects an unknown name with IllegalArgumentException.
     */
    @Test
    void entityTypeValueOfRejectsUnknownName() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> EntityType.valueOf("PRODUCT"));
    }

    /**
     * valueOf rejects a null name with NullPointerException.
     */
    @Test
    void entityTypeValueOfRejectsNullName() {
        Assertions.assertThrows(NullPointerException.class,
                () -> EntityType.valueOf(null));
    }
}
