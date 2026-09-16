package com.intermarche.pos.graphql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AlreadyExistsException}.
 * <p>
 * {@code AlreadyExistsException} is a thin {@link RuntimeException} subclass with
 * two straight-line constructors and no conditional logic, so it has zero
 * executable branches. The observable contract is delegation to the superclass:
 * the message-only constructor forwards its message and leaves the cause null,
 * and the message-plus-cause constructor forwards both. These tests assert that
 * contract directly with no collaborators and no Quarkus context.
 */
class AlreadyExistsExceptionTest {

    /**
     * The message-only constructor must forward its message to the superclass
     * and leave the cause unset.
     */
    @Test
    void messageConstructorForwardsMessageAndHasNoCause() {
        AlreadyExistsException exception = new AlreadyExistsException("EAN 123 already exists");
        assertEquals("EAN 123 already exists", exception.getMessage());
        assertNull(exception.getCause());
    }

    /**
     * A null message passed to the message-only constructor must be forwarded
     * verbatim, yielding a null message and a null cause.
     */
    @Test
    void messageConstructorAcceptsNullMessage() {
        AlreadyExistsException exception = new AlreadyExistsException(null);
        assertNull(exception.getMessage());
        assertNull(exception.getCause());
    }

    /**
     * The message-plus-cause constructor must forward both the message and the
     * cause to the superclass.
     */
    @Test
    void messageAndCauseConstructorForwardsBoth() {
        Throwable cause = new IllegalStateException("duplicate key");
        AlreadyExistsException exception = new AlreadyExistsException("code C1 already exists", cause);
        assertEquals("code C1 already exists", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    /**
     * The message-plus-cause constructor must accept a null message and a null
     * cause, forwarding both unchanged.
     */
    @Test
    void messageAndCauseConstructorAcceptsNulls() {
        AlreadyExistsException exception = new AlreadyExistsException(null, null);
        assertNull(exception.getMessage());
        assertNull(exception.getCause());
    }

    /**
     * The type must remain an unchecked exception so callers are not forced to
     * declare or catch it, matching its use as a business conflict signal.
     */
    @Test
    void isRuntimeException() {
        assertTrue(RuntimeException.class.isAssignableFrom(AlreadyExistsException.class));
    }
}
