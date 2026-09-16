package com.intermarche.pos.graphql;

/**
 * Exception thrown when an attempt is made to create or update an entity
 * that already exists (e.g., a unique constraint violation in business logic).
 * <p>
 * This is the logical counterpart of {@link java.util.NoSuchElementException}.
 */
public class AlreadyExistsException extends RuntimeException {

    /**
     * Creates the exception with a business message (the violated unique
     * key: EAN, code or name depending on the resource).
     *
     * @param message the business conflict message shown to the API caller
     */
    public AlreadyExistsException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a business message and its cause.
     *
     * @param message the business conflict message shown to the API caller
     * @param cause the underlying cause
     */
    public AlreadyExistsException(String message, Throwable cause) {
        super(message, cause);
    }
}