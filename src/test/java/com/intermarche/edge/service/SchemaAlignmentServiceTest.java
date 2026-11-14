package com.intermarche.edge.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SchemaAlignmentService}, targeting 100% branch coverage.
 * <p>
 * The entity manager is a mock and {@link QuarkusTransaction} is intercepted so
 * the transactional runnable runs in place. Branch enumeration: a generation
 * that recreates the schema (and a null one) aligns nothing, an {@code update}
 * generation runs every statement, and a statement the database refuses is
 * skipped without failing the start.
 */
class SchemaAlignmentServiceTest {

    /**
     * A service over a mocked entity manager.
     *
     * @param generation the schema generation mode
     * @return the service
     */
    private SchemaAlignmentService service(String generation) {
        SchemaAlignmentService service = new SchemaAlignmentService();
        service.generation = generation;
        service.entityManager = mock(EntityManager.class);
        return service;
    }

    /**
     * Intercepts the transaction so its runnable runs in place.
     *
     * @param tx the static mock of the transaction API
     */
    private void runInPlace(MockedStatic<QuarkusTransaction> tx) {
        TransactionRunnerOptions runner = mock(TransactionRunnerOptions.class);
        tx.when(QuarkusTransaction::requiringNew).thenReturn(runner);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(runner).run(any(Runnable.class));
    }

    /**
     * A schema recreated at each start, or a generation left unset, has
     * nothing to align: no statement is run.
     */
    @Test
    void aRecreatedSchemaIsLeftAlone() {
        for (String generation : new String[]{"drop-and-create", "none", null}) {
            SchemaAlignmentService service = service(generation);
            assertEquals(0, service.align());
            verifyNoInteractions(service.entityManager);
        }
    }

    /**
     * A kept schema runs every alignment statement, each in its own
     * transaction, on the start event too.
     */
    @Test
    void aKeptSchemaIsAligned() {
        SchemaAlignmentService service = service(" UPDATE ");
        Query query = mock(Query.class);
        when(service.entityManager.createNativeQuery(anyString())).thenReturn(query);
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            runInPlace(tx);
            service.onStart(null);
        }
        verify(service.entityManager).createNativeQuery(
                "ALTER TABLE customer_order_lines ALTER COLUMN vat_rate SET DATA TYPE NUMERIC(6,4)");
        verify(service.entityManager).createNativeQuery(
                "ALTER TABLE products ALTER COLUMN ean SET DATA TYPE VARCHAR(38)");
        verify(service.entityManager).createNativeQuery(
                "ALTER TABLE ticket_lines ALTER COLUMN ean SET DATA TYPE VARCHAR(38)");
        verify(service.entityManager).createNativeQuery(
                "ALTER TABLE ticket_lines ALTER COLUMN linked_article_ean SET DATA TYPE VARCHAR(38)");
        verify(service.entityManager).createNativeQuery(
                "ALTER TABLE employee_profiles ALTER COLUMN echelon_scope SET DATA TYPE VARCHAR(60)");
        verify(query, org.mockito.Mockito.times(5)).executeUpdate();
        assertEquals(5, SchemaAlignmentService.STATEMENTS.size());
    }

    /**
     * A statement the database refuses is skipped: the start goes on and
     * nothing is counted as applied.
     */
    @Test
    void aRefusedStatementDoesNotStopTheStart() {
        SchemaAlignmentService service = service("update");
        when(service.entityManager.createNativeQuery(anyString()))
                .thenThrow(new jakarta.persistence.PersistenceException("no such table"));
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            runInPlace(tx);
            assertEquals(0, service.align());
        }
    }

    /**
     * The applied statements are counted.
     */
    @Test
    void appliedStatementsAreCounted() {
        SchemaAlignmentService service = service("update");
        when(service.entityManager.createNativeQuery(anyString())).thenReturn(mock(Query.class));
        try (MockedStatic<QuarkusTransaction> tx = mockStatic(QuarkusTransaction.class)) {
            runInPlace(tx);
            assertEquals(5, service.align());
        }
    }
}
