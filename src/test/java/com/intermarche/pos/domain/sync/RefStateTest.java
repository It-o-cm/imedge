package com.intermarche.pos.domain.sync;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RefState}, targeting 100% branch coverage.
 * <p>
 * {@link RefState} is a pure Panache data entity: it declares three public
 * columns and no behaviour, hence carries no branches. The tests exercise the
 * default constructor and confirm each field is an independently writable
 * plain reference. Each test is fully isolated and asserts absolute expected
 * values.
 */
class RefStateTest {

    /**
     * A fresh RefState carries null field defaults, no field being pre-set.
     */
    @Test
    void fieldDefaultsAreNull() {
        RefState state = new RefState();
        Assertions.assertNull(state.domain);
        Assertions.assertNull(state.fingerprint);
        Assertions.assertNull(state.appliedAt);
    }

    /**
     * The three columns are independently writable and read back verbatim.
     */
    @Test
    void fieldsAreWritableAndReadBack() {
        LocalDateTime appliedAt = LocalDateTime.of(2026, 8, 3, 10, 15, 30);
        RefState state = new RefState();
        state.domain = "PRODUCTS";
        state.fingerprint = "abc123";
        state.appliedAt = appliedAt;
        Assertions.assertEquals("PRODUCTS", state.domain);
        Assertions.assertEquals("abc123", state.fingerprint);
        Assertions.assertEquals(appliedAt, state.appliedAt);
    }
}
