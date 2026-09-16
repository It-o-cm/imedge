package com.intermarche.pos.graphql;

import jakarta.persistence.PersistenceException;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link GraphQLTrait}.
 * <p>
 * {@code GraphQLTrait} is an interface whose single default method {@code execute}
 * dispatches on the exception type thrown by the supplied business logic. The five
 * observable paths are: success (no exception), {@link AlreadyExistsException}
 * wrapped in a {@link GraphQLException}, {@link PersistenceException} wrapped in a
 * {@link GraphQLException} with a generic database message, {@link NoSuchElementException}
 * re-thrown unchanged, and any other {@link Exception} wrapped in a generic
 * {@link GraphQLException}. Each test drives one path through a bare implementation of
 * the interface, with no collaborators and no Quarkus context.
 */
class GraphQLTraitTest {

    /**
     * Bare implementation of the trait so the default {@code execute} method can be
     * exercised on a concrete instance.
     */
    private static final class TraitFixture implements GraphQLTrait {
    }

    /** Shared instance under test. */
    private final GraphQLTrait trait = new TraitFixture();

    /**
     * When the supplier completes normally, {@code execute} must return its result
     * unchanged and throw nothing.
     */
    @Test
    void executeReturnsSupplierResultOnSuccess() throws GraphQLException {
        String result = trait.execute(() -> "ok", GraphQLTraitTest.class, "createProduct");
        assertEquals("ok", result);
    }

    /**
     * An {@link AlreadyExistsException} thrown by the supplier must be wrapped in a
     * {@link GraphQLException} that forwards the original message and keeps the
     * original exception as its cause.
     */
    @Test
    void executeWrapsAlreadyExistsException() {
        AlreadyExistsException aee = new AlreadyExistsException("EAN 123 already exists");
        Supplier<String> supplier = () -> {
            throw aee;
        };
        GraphQLException thrown = assertThrows(GraphQLException.class,
                () -> trait.execute(supplier, GraphQLTraitTest.class, "createProduct"));
        assertEquals("EAN 123 already exists", thrown.getMessage());
        assertSame(aee, thrown.getCause());
    }

    /**
     * A {@link PersistenceException} thrown by the supplier must be wrapped in a
     * {@link GraphQLException} carrying the generic database message built from the
     * operation name, with the original exception as its cause.
     */
    @Test
    void executeWrapsPersistenceException() {
        PersistenceException pe = new PersistenceException("constraint violation");
        Supplier<String> supplier = () -> {
            throw pe;
        };
        GraphQLException thrown = assertThrows(GraphQLException.class,
                () -> trait.execute(supplier, GraphQLTraitTest.class, "updateProduct"));
        assertEquals("Database error while performing updateProduct. Please check your data.", thrown.getMessage());
        assertSame(pe, thrown.getCause());
    }

    /**
     * A {@link NoSuchElementException} thrown by the supplier must be re-thrown
     * unchanged, never wrapped in a {@link GraphQLException}.
     */
    @Test
    void executeRethrowsNoSuchElementException() {
        NoSuchElementException nsee = new NoSuchElementException("product not found");
        Supplier<String> supplier = () -> {
            throw nsee;
        };
        NoSuchElementException thrown = assertThrows(NoSuchElementException.class,
                () -> trait.execute(supplier, GraphQLTraitTest.class, "findProduct"));
        assertSame(nsee, thrown);
    }

    /**
     * Any other {@link Exception} thrown by the supplier must be wrapped in a
     * {@link GraphQLException} carrying the generic operation message, with the
     * original exception as its cause.
     */
    @Test
    void executeWrapsUnexpectedException() {
        RuntimeException boom = new IllegalStateException("boom");
        Supplier<String> supplier = () -> {
            throw boom;
        };
        GraphQLException thrown = assertThrows(GraphQLException.class,
                () -> trait.execute(supplier, GraphQLTraitTest.class, "deleteProduct"));
        assertEquals("An error occurred during deleteProduct.", thrown.getMessage());
        assertSame(boom, thrown.getCause());
    }
}
