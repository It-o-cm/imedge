package com.intermarche.pos.domain;

import com.intermarche.pos.domain.util.DateTimeProvider;
import java.time.LocalDateTime;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BaseEntity}, targeting 100% branch coverage.
 * <p>
 * {@link BaseEntity} is abstract, so a minimal concrete {@link TestEntity}
 * drives the lifecycle callbacks, {@code equals}, {@code hashCode} and the
 * abstract {@code getChecksum}. Time is frozen through {@link DateTimeProvider}
 * so the audit timestamps are deterministic. A second subclass
 * {@link OtherEntity} exercises the same-hierarchy class-mismatch arm.
 */
class BaseEntityTest {

    /**
     * Concrete probe entity whose checksum tracks a mutable business field,
     * proving that the lifecycle callbacks recompute it on every write.
     */
    static class TestEntity extends BaseEntity {

        /**
         * The single business field feeding the checksum.
         */
        int value;

        /**
         * Builds a probe entity with the given business value.
         *
         * @param value the business value driving the checksum
         */
        TestEntity(int value) {
            this.value = value;
        }

        /**
         * Returns a checksum derived from the mutable business value.
         *
         * @return the current business value used as checksum
         */
        @Override
        public int getChecksum() {
            return value;
        }
    }

    /**
     * A distinct {@link BaseEntity} subclass used to trigger the class-mismatch
     * arm of {@code equals} between two persistent entities sharing an id.
     */
    static class OtherEntity extends BaseEntity {

        /**
         * Returns a constant checksum; the value is irrelevant to these tests.
         *
         * @return the constant zero checksum
         */
        @Override
        public int getChecksum() {
            return 0;
        }
    }

    /**
     * Restores the real clock after each test so no fixed time leaks across cases.
     */
    @AfterEach
    void clearClock() {
        DateTimeProvider.clear();
    }

    /**
     * onCreate stamps createdAt and updatedAt with the frozen time and stores
     * the initial checksum from getChecksum.
     */
    @Test
    void onCreateStampsTimestampsAndChecksum() {
        LocalDateTime frozen = LocalDateTime.of(2026, 8, 3, 10, 0, 0);
        DateTimeProvider.setFixedDateTime(frozen);
        TestEntity e = new TestEntity(42);
        e.onCreate();
        Assertions.assertEquals(frozen, e.createdAt);
        Assertions.assertEquals(frozen, e.updatedAt);
        Assertions.assertEquals(Integer.valueOf(42), e.checksum);
    }

    /**
     * onUpdate refreshes updatedAt and recomputes the checksum while leaving
     * createdAt untouched.
     */
    @Test
    void onUpdateRefreshesUpdatedAtAndChecksumOnly() {
        LocalDateTime created = LocalDateTime.of(2026, 8, 3, 10, 0, 0);
        DateTimeProvider.setFixedDateTime(created);
        TestEntity e = new TestEntity(42);
        e.onCreate();
        LocalDateTime updated = LocalDateTime.of(2026, 8, 3, 11, 30, 0);
        DateTimeProvider.setFixedDateTime(updated);
        e.value = 99;
        e.onUpdate();
        Assertions.assertEquals(created, e.createdAt);
        Assertions.assertEquals(updated, e.updatedAt);
        Assertions.assertEquals(Integer.valueOf(99), e.checksum);
    }

    /**
     * equals returns true for the same reference (this == o arm true).
     */
    @Test
    void equalsSameReferenceIsTrue() {
        TestEntity e = new TestEntity(1);
        e.id = 5L;
        Assertions.assertTrue(e.equals(e));
    }

    /**
     * equals returns false against null (o == null arm true).
     */
    @Test
    void equalsNullIsFalse() {
        TestEntity e = new TestEntity(1);
        e.id = 5L;
        Assertions.assertFalse(e.equals(null));
    }

    /**
     * equals returns false against a foreign class (getClass mismatch arm true,
     * o == null arm false).
     */
    @Test
    void equalsForeignClassIsFalse() {
        TestEntity e = new TestEntity(1);
        e.id = 5L;
        Assertions.assertFalse(e.equals("not an entity"));
    }

    /**
     * equals returns false between two BaseEntity subclasses sharing an id
     * (getClass mismatch arm true within the hierarchy).
     */
    @Test
    void equalsDifferentSubclassSameIdIsFalse() {
        TestEntity a = new TestEntity(1);
        a.id = 5L;
        OtherEntity b = new OtherEntity();
        b.id = 5L;
        Assertions.assertFalse(a.equals(b));
    }

    /**
     * equals returns false when this entity is transient (id != null arm false).
     */
    @Test
    void equalsTransientThisIsFalse() {
        TestEntity a = new TestEntity(1);
        TestEntity b = new TestEntity(1);
        b.id = 5L;
        Assertions.assertFalse(a.equals(b));
    }

    /**
     * equals returns false for two persistent entities with different ids
     * (id.equals arm false).
     */
    @Test
    void equalsDifferentIdsIsFalse() {
        TestEntity a = new TestEntity(1);
        a.id = 5L;
        TestEntity b = new TestEntity(1);
        b.id = 6L;
        Assertions.assertFalse(a.equals(b));
    }

    /**
     * equals returns true for two distinct persistent entities of the same class
     * sharing the same non-null id (all arms passing).
     */
    @Test
    void equalsSameClassSameIdIsTrue() {
        TestEntity a = new TestEntity(1);
        a.id = 5L;
        TestEntity b = new TestEntity(999);
        b.id = 5L;
        Assertions.assertNotSame(a, b);
        Assertions.assertTrue(a.equals(b));
    }

    /**
     * hashCode matches the JDK Objects.hash of the runtime class and the id.
     */
    @Test
    void hashCodeMatchesObjectsHash() {
        TestEntity e = new TestEntity(1);
        e.id = 5L;
        int expected = Objects.hash(TestEntity.class, 5L);
        Assertions.assertEquals(expected, e.hashCode());
    }

    /**
     * hashCode is equal for two entities of the same class sharing an id.
     */
    @Test
    void hashCodeEqualForSameClassSameId() {
        TestEntity a = new TestEntity(1);
        a.id = 5L;
        TestEntity b = new TestEntity(2);
        b.id = 5L;
        Assertions.assertEquals(a.hashCode(), b.hashCode());
    }

    /**
     * getChecksum returns the concrete subclass's business value.
     */
    @Test
    void getChecksumReturnsSubclassValue() {
        TestEntity e = new TestEntity(77);
        Assertions.assertEquals(77, e.getChecksum());
    }
}
