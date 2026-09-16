
package com.intermarche.pos.domain.util;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Utility class to provide the current date and time.
 * <p>
 * Encapsulates {@link LocalDateTime#now()} to allow deterministic time
 * for testing purposes.
 * <p>
 * Semantic contract — who honors the fixed clock:
 * <ul>
 *   <li>{@code BaseEntity} audit timestamps (creation/update) go through this
 *       provider, so a fixed time freezes every audited row.</li>
 *   <li>{@code Price} validity resolution ({@code findCurrentPrice}) goes
 *       through it, so tests can pin "now" inside or outside a price
 *       window.</li>
 *   <li>Business document dates do NOT: {@code Ticket.creationDate} /
 *       {@code closingDate} (set by the draft persistence) and
 *       {@code Refund.creationDate} call {@code LocalDateTime.now()}
 *       directly. Fixing the clock therefore does not freeze document
 *       dates — a test asserting on them must tolerate real time.</li>
 * </ul>
 * The fixed time is a plain static field: process-wide, not thread-safe,
 * strictly a test facility — production code must never call
 * {@link #setFixedDateTime(LocalDateTime)}.
 */
public final class DateTimeProvider {

    /**
     * Private constructor preventing instantiation of the utility class.
     */
    private DateTimeProvider() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * The fixed date time currently set.
     * If null, the provider returns the real system time.
     */
    private static LocalDateTime fixedTime;

    /**
     * Returns the current LocalDateTime.
     * <p>
     * If a fixed time has been set using {@link #setFixedDateTime(LocalDateTime)},
     * that time is returned. Otherwise, it returns the system current time.
     *
     * @return The current LocalDateTime (or the fixed one).
     */
    public static LocalDateTime now() {
        return fixedTime != null ? fixedTime : LocalDateTime.now();
    }

    /**
     * Sets a fixed date and time to be returned by {@link #now()}.
     * <p>
     * This is useful for testing scenarios where you need to verify
     * timestamps independently of the actual execution time.
     *
     * @param time The fixed LocalDateTime to use. Must not be null.
     */
    public static void setFixedDateTime(LocalDateTime time) {
        Objects.requireNonNull(time, "Fixed time cannot be null");
        DateTimeProvider.fixedTime = time;
    }

    /**
     * Clears the fixed time.
     * <p>
     * After calling this method, {@link #now()} will revert to returning
     * the actual system time.
     */
    public static void clear() {
        DateTimeProvider.fixedTime = null;
    }
}
