package com.intermarche.pos.domain.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link DateTimeProvider}.
 * <p>
 * The provider carries two branches: the {@code fixedTime != null} ternary in
 * {@link DateTimeProvider#now()} and the {@code Objects.requireNonNull} guard in
 * {@link DateTimeProvider#setFixedDateTime(LocalDateTime)}. Both arms of each are
 * exercised. The system-time arm of {@code now()} is pinned by mocking
 * {@link LocalDateTime} statics so the assertion is on an absolute value. The
 * private constructor is reached by reflection to assert the utility-class guard.
 * The global fixed-time field is reset before and after every test to guarantee
 * isolation.
 */
class DateTimeProviderTest {

    /**
     * Clears any process-wide fixed time before each test so no ordering
     * dependency can leak the previous test's state.
     */
    @BeforeEach
    void resetBefore() {
        DateTimeProvider.clear();
    }

    /**
     * Clears any process-wide fixed time after each test to leave the global
     * field pristine for other test classes.
     */
    @AfterEach
    void resetAfter() {
        DateTimeProvider.clear();
    }

    /**
     * {@code now()} must return the pinned instance when a fixed time is set
     * (non-null arm of the ternary).
     */
    @Test
    void nowReturnsFixedTimeWhenSet() {
        LocalDateTime fixed = LocalDateTime.of(2026, 8, 3, 10, 15, 30);
        DateTimeProvider.setFixedDateTime(fixed);
        assertEquals(fixed, DateTimeProvider.now());
    }

    /**
     * {@code now()} must fall back to the real system time when no fixed time is
     * set (null arm of the ternary); the system clock is mocked to make the
     * expected value absolute.
     */
    @Test
    void nowReturnsSystemTimeWhenNotSet() {
        LocalDateTime systemTime = LocalDateTime.of(2001, 1, 2, 3, 4, 5);
        try (MockedStatic<LocalDateTime> mocked = mockStatic(LocalDateTime.class)) {
            mocked.when(LocalDateTime::now).thenReturn(systemTime);
            assertEquals(systemTime, DateTimeProvider.now());
        }
    }

    /**
     * {@code setFixedDateTime(null)} must throw {@link NullPointerException} with
     * the documented message (null arm of the requireNonNull guard).
     */
    @Test
    void setFixedDateTimeThrowsOnNull() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> DateTimeProvider.setFixedDateTime(null));
        assertEquals("Fixed time cannot be null", ex.getMessage());
    }

    /**
     * {@code setFixedDateTime} with a non-null value must store it so a later
     * {@code now()} returns it (non-null arm of the requireNonNull guard).
     */
    @Test
    void setFixedDateTimeStoresNonNullValue() {
        LocalDateTime fixed = LocalDateTime.of(1999, 12, 31, 23, 59, 59);
        DateTimeProvider.setFixedDateTime(fixed);
        assertEquals(fixed, DateTimeProvider.now());
    }

    /**
     * {@code clear()} must drop the fixed time so {@code now()} reverts to the
     * mocked system clock.
     */
    @Test
    void clearRevertsToSystemTime() {
        LocalDateTime systemTime = LocalDateTime.of(2010, 6, 15, 12, 0, 0);
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 8, 3, 10, 0, 0));
        DateTimeProvider.clear();
        try (MockedStatic<LocalDateTime> mocked = mockStatic(LocalDateTime.class)) {
            mocked.when(LocalDateTime::now).thenReturn(systemTime);
            assertEquals(systemTime, DateTimeProvider.now());
        }
    }

    /**
     * The private constructor must reject instantiation with
     * {@link UnsupportedOperationException}, enforcing the utility-class contract.
     *
     * @throws NoSuchMethodException never — the no-arg constructor exists.
     */
    @Test
    void constructorThrowsUnsupportedOperation() throws NoSuchMethodException {
        Constructor<DateTimeProvider> constructor = DateTimeProvider.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        InvocationTargetException wrapper = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertTrue(wrapper.getCause() instanceof UnsupportedOperationException);
        assertEquals("This is a utility class and cannot be instantiated", wrapper.getCause().getMessage());
    }
}
