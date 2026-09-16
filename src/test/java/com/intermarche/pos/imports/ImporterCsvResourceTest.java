package com.intermarche.pos.imports;

import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ImporterCsvResource}.
 * <p>
 * {@code ImporterCsvResource} is abstract, so a concrete {@link TestImporter}
 * subclass supplies deterministic implementations of the three abstract hooks
 * ({@code processChunkWithFallback}, {@code processLineLogic},
 * {@code findEntityForLine}). The JTA {@link TransactionManager} is a Mockito
 * mock wired onto the package-private {@code tm} field, and the static
 * {@link Panache#getEntityManager()} call performed in every
 * {@code withTransaction} finally block is neutralized with
 * {@link org.mockito.Mockito#mockStatic(Class)} in try-with-resources blocks.
 * Every branch is exercised: the streaming reader (empty lines, header skip,
 * short lines, the 1000-line chunk flush and the leftover flush), the staged
 * fallback recursion (1000 -&gt; 100 -&gt; 10 -&gt; 1), both transaction
 * outcomes with all three rollback sub-branches, the {@code Executor}
 * success/failure guards, and every arm of the safe-parsing helpers.
 */
class ImporterCsvResourceTest {

    /**
     * Concrete {@link ImporterCsvResource} whose abstract hooks are driven by
     * mutable test fields so each test can dictate created/updated outcomes,
     * force per-line failures, and control the fresh-lookup result.
     */
    static class TestImporter extends ImporterCsvResource {
        /** Context map returned by {@link #processChunkWithFallback}. */
        Map<String, Object> chunkContext = new HashMap<>();
        /** Codes for which {@link #processLineLogic} throws. */
        List<String> throwOnCodes = new ArrayList<>();
        /** Entities returned by {@link #findEntityForLine}, keyed by code. */
        Map<String, Object> entitiesByCode = new HashMap<>();
        /** When set, {@link #processChunkWithFallback} throws it. */
        RuntimeException chunkException;
        /** Number of times {@link #processChunkWithFallback} was invoked. */
        int chunkCalls = 0;

        /**
         * Records the invocation and either throws the configured exception or
         * returns the configured pre-fetch context map.
         *
         * @param parsedLines the chunk lines
         * @param targetCodes the unique codes in the chunk
         * @param counters    the global counters
         * @param errors      the error accumulator
         * @return the configured context map
         */
        @Override
        protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
            chunkCalls++;
            if (chunkException != null) throw chunkException;
            return chunkContext;
        }

        /**
         * Throws for configured codes, otherwise counts the line as an update
         * when its code is present in the supplied entity map and as a creation
         * otherwise.
         *
         * @param data      the parsed line
         * @param entityMap the pre-fetched or fresh entity map
         * @param counters  the local counters [created, updated]
         */
        @Override
        protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
            if (throwOnCodes.contains(data.code)) throw new RuntimeException("boom-" + data.code);
            if (entityMap != null && entityMap.containsKey(data.code)) counters[1]++;
            else counters[0]++;
        }

        /**
         * Returns the configured entity for the line's code, or null.
         *
         * @param data the parsed line
         * @return the entity or null
         */
        @Override
        protected Object findEntityForLine(LineData data) {
            return entitiesByCode.get(data.code);
        }
    }

    /**
     * Builds a {@link TestImporter} with a mocked {@link TransactionManager}
     * on its package-private {@code tm} field.
     *
     * @return a ready-to-use importer
     */
    private TestImporter newImporter() {
        TestImporter importer = new TestImporter();
        importer.tm = mock(TransactionManager.class);
        importer.engineFeedService = mock(com.intermarche.pos.service.sync.EngineFeedService.class);
        return importer;
    }

    /**
     * Wraps a string as a UTF-8 input stream.
     *
     * @param content the CSV content
     * @return the input stream
     */
    private InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    /** Header names of the test rows, in cell order. */
    private static final String[] TEST_HEADER = {"CODE", "NAME"};

    /**
     * Builds a header-bound row for the test importer: the header maps the
     * TEST_HEADER names onto the cell positions and CODE is the key column.
     *
     * @param lineNumber the 1-based line number
     * @param cells the raw cells of the row
     * @return the header-bound line
     */
    private static ImporterCsvResource.LineData line(int lineNumber, String... cells) {
        Map<String, Integer> header = new LinkedHashMap<>();
        for (int i = 0; i < TEST_HEADER.length; i++) header.put(TEST_HEADER[i], i);
        return new ImporterCsvResource.LineData(lineNumber, header, cells, TEST_HEADER[0]);
    }

    /**
     * {@code importCsvStream} reads the header, ignores empty and truncated lines,
     * classifies one update and two creations, and emits the (intentionally
     * quote-asymmetric) JSON built by {@code buildAnswer} when errors exist.
     */
    @Test
    void importCsvStreamProcessesMixedLinesWithErrors() {
        TestImporter importer = newImporter();
        importer.chunkContext.put("A", new Object());
        String csv = "CODE|NAME\n\nA|foo\nB|bar\nX\nC|baz\n";
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(stream(csv), "CODE", List.of("NAME"));
            assertEquals(200, response.getStatus());
            assertEquals("{\"createdCount\":2, \"updatedCount\":1, \"errors\":[Line 5 ignored (fewer cells than the header): X\"]}", response.getEntity());
            assertEquals(1, importer.chunkCalls);
            verify(em, atLeastOnce()).clear();
        }
    }

    /**
     * A body of exactly {@link ImporterCsvResource#STAGE_1_SIZE} data lines
     * flushes inside the read loop (size-threshold true arm) and leaves the
     * post-loop leftover flush empty (false arm); with no errors the JSON omits
     * the errors array.
     */
    @Test
    void importCsvStreamFlushesFullChunkAndSkipsEmptyLeftover() {
        TestImporter importer = newImporter();
        StringBuilder csv = new StringBuilder("CODE|NAME\n");
        for (int i = 0; i < 1000; i++) csv.append("c").append(i).append("|v").append(i).append("\n");
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(stream(csv.toString()), "CODE", List.of("NAME"));
            assertEquals(200, response.getStatus());
            assertEquals("{\"createdCount\":1000, \"updatedCount\":0}", response.getEntity());
            assertEquals(1, importer.chunkCalls);
        }
    }

    /**
     * An {@link InputStream} that fails on read drives the {@code IOException}
     * catch, yielding a 500 response prefixed with "Error reading file:".
     */
    @Test
    void importCsvStreamReturnsServerErrorOnIOException() {
        TestImporter importer = newImporter();
        InputStream failing = new InputStream() {
            /**
             * Always fails to simulate an unreadable stream.
             *
             * @return never returns normally
             * @throws IOException always
             */
            @Override
            public int read() throws IOException {
                throw new IOException("disk");
            }
        };
        Response response = importer.importCsvStream(failing, "CODE", List.of("NAME"));
        assertEquals(500, response.getStatus());
        assertEquals("Error reading file: disk", response.getEntity());
        assertEquals(0, importer.chunkCalls);
    }

    /**
     * A hook throwing a non-IO {@link Throwable} drives the generic catch,
     * yielding a 500 response prefixed with "Unexcepted error:".
     */
    @Test
    void importCsvStreamReturnsServerErrorOnUnexpectedThrowable() {
        TestImporter importer = newImporter();
        importer.chunkException = new IllegalStateException("kaboom");
        Response response = importer.importCsvStream(stream("CODE|NAME\nA|foo\n"), "CODE", List.of("NAME"));
        assertEquals(500, response.getStatus());
        assertEquals("Unexcepted error: kaboom", response.getEntity());
        assertEquals(1, importer.chunkCalls);
    }

    /**
     * {@code processWithStages} returns immediately for an empty list, touching
     * neither the transaction manager nor the entity map.
     */
    @Test
    void processWithStagesReturnsOnEmptyList() {
        TestImporter importer = newImporter();
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        importer.processWithStages(new ArrayList<>(), new HashMap<>(), ImporterCsvResource.STAGE_1_SIZE, counters, errors);
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
        assertTrue(errors.isEmpty());
        assertEquals(0, importer.chunkCalls);
    }

    /**
     * A failing batch recurses through every stage (1000 -&gt; 100 -&gt; 10
     * -&gt; 1) down to line-by-line processing, where each poison line is
     * isolated into its own error entry; the fresh-lookup returns an entity for
     * one line and null for the other, covering both
     * {@code prepareContextForLine} arms.
     */
    @Test
    void processWithStagesFallsBackToLineByLineAndIsolatesErrors() {
        TestImporter importer = newImporter();
        importer.throwOnCodes.add("a");
        importer.throwOnCodes.add("b");
        importer.entitiesByCode.put("a", new Object());
        List<ImporterCsvResource.LineData> lines = new ArrayList<>();
        lines.add(line(10, "a", "x"));
        lines.add(line(11, "b", "y"));
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            importer.processWithStages(lines, new HashMap<>(), ImporterCsvResource.STAGE_1_SIZE, counters, errors);
            assertEquals(0, counters[0]);
            assertEquals(0, counters[1]);
            assertEquals(2, errors.size());
            assertEquals("Line 10 (a): boom-a", errors.get(0));
            assertEquals("Line 11 (b): boom-b", errors.get(1));
        }
    }

    /**
     * With {@code chunkSize == 1} the base case processes each line in its own
     * transaction; a successful line drives the line-by-line {@code onSuccess}
     * arm, merging its fresh-lookup update into the global counters.
     */
    @Test
    void processWithStagesLineByLineCommitsSuccessfulLine() {
        TestImporter importer = newImporter();
        importer.entitiesByCode.put("k", new Object());
        List<ImporterCsvResource.LineData> lines = new ArrayList<>();
        lines.add(line(7, "k", "v"));
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            importer.processWithStages(lines, new HashMap<>(), 1, counters, errors);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertTrue(errors.isEmpty());
        }
    }

    /**
     * {@code prepareContextForLine} maps the code to the freshly found entity
     * when the lookup succeeds (non-null arm).
     */
    @Test
    void prepareContextForLineStoresFoundEntity() {
        TestImporter importer = newImporter();
        Object entity = new Object();
        importer.entitiesByCode.put("k", entity);
        Map<String, Object> map = importer.prepareContextForLine(line(1, "k"));
        assertEquals(1, map.size());
        assertSame(entity, map.get("k"));
    }

    /**
     * {@code prepareContextForLine} yields an empty map when the fresh lookup
     * returns null (null arm).
     */
    @Test
    void prepareContextForLineReturnsEmptyMapWhenNotFound() {
        TestImporter importer = newImporter();
        Map<String, Object> map = importer.prepareContextForLine(line(1, "missing"));
        assertTrue(map.isEmpty());
    }

    /**
     * {@code getNextSize} steps 1000 -&gt; 100 (first guard true arm).
     */
    @Test
    void getNextSizeFromStageOne() {
        assertEquals(ImporterCsvResource.STAGE_2_SIZE, newImporter().getNextSize(ImporterCsvResource.STAGE_1_SIZE));
    }

    /**
     * {@code getNextSize} steps 100 -&gt; 10 (first guard false, second true).
     */
    @Test
    void getNextSizeFromStageTwo() {
        assertEquals(ImporterCsvResource.STAGE_3_SIZE, newImporter().getNextSize(ImporterCsvResource.STAGE_2_SIZE));
    }

    /**
     * {@code getNextSize} steps 10 -&gt; 1 (both guards false).
     */
    @Test
    void getNextSizeFromStageThree() {
        assertEquals(1, newImporter().getNextSize(ImporterCsvResource.STAGE_3_SIZE));
    }

    /**
     * {@code Executor.onSuccess} runs the consumer when a result is present and
     * {@code onFailure} skips its consumer when no exception occurred.
     */
    @Test
    void executorRunsSuccessConsumerAndSkipsFailure() {
        ImporterCsvResource.Executor<String> executor = new ImporterCsvResource.Executor<>();
        executor.setResult("value");
        List<String> seen = new ArrayList<>();
        executor.onSuccess(seen::add).onFailure(t -> seen.add("fail"));
        assertEquals(1, seen.size());
        assertEquals("value", seen.get(0));
    }

    /**
     * {@code Executor.onFailure} runs the consumer when an exception is present
     * and {@code onSuccess} skips its consumer when no result was set.
     */
    @Test
    void executorRunsFailureConsumerAndSkipsSuccess() {
        ImporterCsvResource.Executor<String> executor = new ImporterCsvResource.Executor<>();
        executor.setException(new RuntimeException("oops"));
        List<String> seen = new ArrayList<>();
        executor.onSuccess(seen::add).onFailure(t -> seen.add(t.getMessage()));
        assertEquals(1, seen.size());
        assertEquals("oops", seen.get(0));
    }

    /**
     * {@code Executor.onFailure} tolerates a null consumer even when an
     * exception is present (the {@code failure != null} false arm).
     */
    @Test
    void executorOnFailureIgnoresNullConsumer() {
        ImporterCsvResource.Executor<String> executor = new ImporterCsvResource.Executor<>();
        executor.setException(new RuntimeException("oops"));
        assertSame(executor, executor.onFailure(null));
    }

    /**
     * {@code withTransaction} commits and captures the result on the happy
     * path, clearing the entity manager afterwards.
     */
    @Test
    void withTransactionCommitsOnSuccess() throws Exception {
        TestImporter importer = newImporter();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            ImporterCsvResource.Executor<String> executor = importer.withTransaction(() -> "ok");
            assertEquals("ok", executor.result);
            assertNull(executor.ex);
            verify(importer.tm).begin();
            verify(importer.tm).commit();
            verify(em).clear();
        }
    }

    /**
     * {@code withTransaction} rolls back when the body fails and a transaction
     * is still active (status != NO_TRANSACTION true arm).
     */
    @Test
    void withTransactionRollsBackOnFailureWhenActive() throws Exception {
        TestImporter importer = newImporter();
        when(importer.tm.getStatus()).thenReturn(Status.STATUS_ACTIVE);
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            RuntimeException failure = new RuntimeException("body");
            ImporterCsvResource.Executor<String> executor = importer.withTransaction(() -> {
                throw failure;
            });
            assertNull(executor.result);
            assertSame(failure, executor.ex);
            verify(importer.tm).rollback();
            verify(em).clear();
        }
    }

    /**
     * {@code withTransaction} skips rollback when no transaction remains active
     * (status == NO_TRANSACTION false arm).
     */
    @Test
    void withTransactionSkipsRollbackWhenNoTransaction() throws Exception {
        TestImporter importer = newImporter();
        when(importer.tm.getStatus()).thenReturn(Status.STATUS_NO_TRANSACTION);
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            ImporterCsvResource.Executor<String> executor = importer.withTransaction(() -> {
                throw new RuntimeException("body");
            });
            assertNull(executor.result);
            assertEquals("body", executor.ex.getMessage());
            verify(importer.tm, never()).rollback();
        }
    }

    /**
     * {@code withTransaction} swallows and logs a rollback failure while still
     * retaining the original body exception and clearing the entity manager.
     */
    @Test
    void withTransactionHandlesRollbackFailure() throws Exception {
        TestImporter importer = newImporter();
        when(importer.tm.getStatus()).thenReturn(Status.STATUS_ACTIVE);
        doThrow(new RuntimeException("rollback-failed")).when(importer.tm).rollback();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            RuntimeException failure = new RuntimeException("body");
            ImporterCsvResource.Executor<String> executor = importer.withTransaction(() -> {
                throw failure;
            });
            assertSame(failure, executor.ex);
            verify(importer.tm).rollback();
            verify(em).clear();
        }
    }

    /**
     * {@code safeGet} returns null for a column absent from the header
     * (unknown-column arm).
     */
    @Test
    void safeGetReturnsNullForUnknownColumn() {
        assertNull(newImporter().safeGet(line(1, "a"), "NOPE"));
    }

    /**
     * {@code safeGet} returns null when the column exists in the header but
     * the line has no cell at its index (short-line arm).
     */
    @Test
    void safeGetReturnsNullForMissingCell() {
        assertNull(newImporter().safeGet(line(1, "a"), "NAME"));
    }

    /**
     * {@code safeGet} returns null when the targeted cell is null (inner
     * null arm).
     */
    @Test
    void safeGetReturnsNullForNullElement() {
        assertNull(newImporter().safeGet(line(1, null, "b"), "CODE"));
    }

    /**
     * {@code safeGet} trims and returns a present non-null cell (inner
     * non-null arm).
     */
    @Test
    void safeGetTrimsPresentElement() {
        assertEquals("x", newImporter().safeGet(line(1, "  x  "), "CODE"));
    }

    /**
     * {@code safeParseBoolean} returns false for an unknown column.
     */
    @Test
    void safeParseBooleanFalseWhenUnknownColumn() {
        assertFalse(newImporter().safeParseBoolean(line(1, "true"), "NOPE"));
    }

    /**
     * {@code safeParseBoolean} returns false for an empty cell.
     */
    @Test
    void safeParseBooleanFalseWhenEmpty() {
        assertFalse(newImporter().safeParseBoolean(line(1, "  "), "CODE"));
    }

    /**
     * {@code safeParseBoolean} parses a non-empty cell.
     */
    @Test
    void safeParseBooleanParsesValue() {
        assertTrue(newImporter().safeParseBoolean(line(1, " true "), "CODE"));
    }

    /**
     * {@code safeParseBigDecimal} returns null for an unknown column.
     */
    @Test
    void safeParseBigDecimalNullWhenUnknownColumn() {
        assertNull(newImporter().safeParseBigDecimal(line(1, "1"), "NOPE"));
    }

    /**
     * {@code safeParseBigDecimal} returns null for an empty cell.
     */
    @Test
    void safeParseBigDecimalNullWhenEmpty() {
        assertNull(newImporter().safeParseBigDecimal(line(1, "  "), "CODE"));
    }

    /**
     * {@code safeParseBigDecimal} returns null for an unparseable cell.
     */
    @Test
    void safeParseBigDecimalNullWhenInvalid() {
        assertNull(newImporter().safeParseBigDecimal(line(1, "abc"), "CODE"));
    }

    /**
     * {@code safeParseBigDecimal} parses a trimmed decimal cell.
     */
    @Test
    void safeParseBigDecimalParsesValue() {
        assertEquals(new BigDecimal("12.50"), newImporter().safeParseBigDecimal(line(1, " 12.50 "), "CODE"));
    }

    /**
     * {@code safeParseInt} returns null for an unknown column.
     */
    @Test
    void safeParseIntNullWhenUnknownColumn() {
        assertNull(newImporter().safeParseInt(line(1, "1"), "NOPE"));
    }

    /**
     * {@code safeParseInt} returns null for an empty cell.
     */
    @Test
    void safeParseIntNullWhenEmpty() {
        assertNull(newImporter().safeParseInt(line(1, "  "), "CODE"));
    }

    /**
     * {@code safeParseInt} returns null for an unparseable cell.
     */
    @Test
    void safeParseIntNullWhenInvalid() {
        assertNull(newImporter().safeParseInt(line(1, "x"), "CODE"));
    }

    /**
     * {@code safeParseInt} parses a trimmed integer cell.
     */
    @Test
    void safeParseIntParsesValue() {
        assertEquals(Integer.valueOf(42), newImporter().safeParseInt(line(1, " 42 "), "CODE"));
    }

    /**
     * {@code safeParseDouble} returns null for an unknown column.
     */
    @Test
    void safeParseDoubleNullWhenUnknownColumn() {
        assertNull(newImporter().safeParseDouble(line(1, "1"), "NOPE"));
    }

    /**
     * {@code safeParseDouble} returns null for an empty cell.
     */
    @Test
    void safeParseDoubleNullWhenEmpty() {
        assertNull(newImporter().safeParseDouble(line(1, "  "), "CODE"));
    }

    /**
     * {@code safeParseDouble} returns null for an unparseable cell.
     */
    @Test
    void safeParseDoubleNullWhenInvalid() {
        assertNull(newImporter().safeParseDouble(line(1, "x"), "CODE"));
    }

    /**
     * {@code safeParseDouble} parses a trimmed double cell.
     */
    @Test
    void safeParseDoubleParsesValue() {
        assertEquals(Double.valueOf(3.14), newImporter().safeParseDouble(line(1, " 3.14 "), "CODE"));
    }

    /**
     * {@code safeParseDateTime} returns null for an unknown column.
     */
    @Test
    void safeParseDateTimeNullWhenUnknownColumn() {
        assertNull(newImporter().safeParseDateTime(line(1, "x"), "NOPE"));
    }

    /**
     * {@code safeParseDateTime} returns null for an empty cell.
     */
    @Test
    void safeParseDateTimeNullWhenEmpty() {
        assertNull(newImporter().safeParseDateTime(line(1, "  "), "CODE"));
    }

    /**
     * {@code safeParseDateTime} returns null for an unparseable cell.
     */
    @Test
    void safeParseDateTimeNullWhenInvalid() {
        assertNull(newImporter().safeParseDateTime(line(1, "not-a-date"), "CODE"));
    }

    /**
     * {@code safeParseDateTime} parses a trimmed ISO cell.
     */
    @Test
    void safeParseDateTimeParsesValue() {
        assertEquals(LocalDateTime.of(2020, 1, 2, 3, 4, 5), newImporter().safeParseDateTime(line(1, " 2020-01-02T03:04:05 "), "CODE"));
    }

    /**
     * An importer naming no engine feed (default {@code feedCode} null)
     * never touches the feed keeper (capture null arm).
     */
    @Test
    void importCsvStreamWithoutFeedCodeStoresNothing() {
        TestImporter importer = newImporter();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            importer.importCsvStream(stream("CODE|NAME\nA|foo\n"), "CODE", List.of("NAME"));
        }
        org.mockito.Mockito.verifyNoInteractions(importer.engineFeedService);
    }

    /**
     * An importer naming an engine feed stores the file VERBATIM after the
     * import — row-level errors included, the file is the truth of the
     * feed (capture non-null arm).
     */
    @Test
    void importCsvStreamWithFeedCodeCapturesVerbatim() {
        TestImporter importer = new TestImporter() {
            /**
             * Names the captured feed for this test.
             *
             * @return the OFFERS feed code
             */
            @Override
            protected String feedCode() {
                return "OFFERS";
            }
        };
        importer.tm = mock(TransactionManager.class);
        importer.engineFeedService = mock(com.intermarche.pos.service.sync.EngineFeedService.class);
        String csv = "CODE|NAME\nA|foo\nX\n";
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            importer.importCsvStream(stream(csv), "CODE", List.of("NAME"));
        }
        verify(importer.engineFeedService).store("OFFERS", csv);
    }

    /**
     * A capture failure never fails the import: the response stays 200 and
     * the error is only logged (catch arm of {@code captureFeed}).
     */
    @Test
    void importCsvStreamSurvivesCaptureFailure() {
        TestImporter importer = new TestImporter() {
            /**
             * Names the captured feed for this test.
             *
             * @return the OFFERS feed code
             */
            @Override
            protected String feedCode() {
                return "OFFERS";
            }
        };
        importer.tm = mock(TransactionManager.class);
        importer.engineFeedService = mock(com.intermarche.pos.service.sync.EngineFeedService.class);
        org.mockito.Mockito.when(importer.engineFeedService.store(any(), any()))
                .thenThrow(new IllegalStateException("db down"));
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(stream("CODE|NAME\nA|foo\n"), "CODE", List.of("NAME"));
            assertEquals(200, response.getStatus());
        }
    }

    /**
     * An importer that NAMES a feed but whose {@code engineFeedService} is null
     * captures nothing, driving the {@code engineFeedService == null} TRUE arm
     * of {@code captureFeed} (line 205): the guard short-circuits on the null
     * keeper and the import still answers 200.
     */
    @Test
    void importCsvStreamWithFeedCodeButNullServiceStoresNothing() {
        TestImporter importer = new TestImporter() {
            /**
             * Names the captured feed for this test.
             *
             * @return the OFFERS feed code
             */
            @Override
            protected String feedCode() {
                return "OFFERS";
            }
        };
        importer.tm = mock(TransactionManager.class);
        importer.engineFeedService = null;
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(stream("CODE|NAME\nA|foo\n"), "CODE", List.of("NAME"));
            assertEquals(200, response.getStatus());
        }
    }

    /**
     * A header missing the key column or a required column rejects the file
     * with a 400 naming the missing names, before any processing.
     */
    @Test
    void importCsvStreamRejectsMissingRequiredColumns() {
        TestImporter importer = newImporter();
        Response response = importer.importCsvStream(stream("OTHER|NAME\nA|foo\n"), "CODE", List.of("NAME", "EXTRA"));
        assertEquals(400, response.getStatus());
        assertEquals("{\"error\":\"Missing required columns: CODE, EXTRA\"}", response.getEntity());
        assertEquals(0, importer.chunkCalls);
    }

    /**
     * A duplicate header name keeps its first index (first-wins arm): the
     * value is read from the first occurrence.
     */
    @Test
    void importCsvStreamDuplicateHeaderFirstWins() {
        TestImporter importer = newImporter();
        importer.chunkContext.put("A", new Object());
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(stream("CODE|CODE\nA|B\n"), "CODE", List.of());
            assertEquals(200, response.getStatus());
            assertEquals("{\"createdCount\":0, \"updatedCount\":1}", response.getEntity());
        }
    }

    /**
     * A data line whose key cell is empty is reported and skipped (empty-key
     * arm), without stopping the import.
     */
    @Test
    void importCsvStreamReportsEmptyKeyLine() {
        TestImporter importer = newImporter();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(stream("CODE|NAME\n|foo\nB|bar\n"), "CODE", List.of("NAME"));
            assertEquals(200, response.getStatus());
            assertEquals("{\"createdCount\":1, \"updatedCount\":0, \"errors\":[Line 2 ignored (empty key 'CODE')\"]}", response.getEntity());
        }
    }

    /**
     * {@code parseCodes} returns an empty list for a null input (first guard
     * true arm).
     */
    @Test
    void parseCodesEmptyWhenNull() {
        assertTrue(newImporter().parseCodes(null).isEmpty());
    }

    /**
     * {@code parseCodes} returns an empty list for a blank input (second guard
     * true arm).
     */
    @Test
    void parseCodesEmptyWhenBlank() {
        assertTrue(newImporter().parseCodes("   ").isEmpty());
    }

    /**
     * {@code parseCodes} trims, drops empty tokens, and sorts non-empty codes.
     */
    @Test
    void parseCodesTrimsFiltersAndSorts() {
        List<String> result = newImporter().parseCodes("b, a, , c");
        assertEquals(List.of("a", "b", "c"), result);
    }

    /**
     * {@code updateCounters} adds local created/updated tallies into the global
     * accumulator.
     */
    @Test
    void updateCountersMergesTallies() {
        TestImporter importer = newImporter();
        int[] global = {2, 3};
        importer.updateCounters(global, new int[]{4, 5});
        assertEquals(6, global[0]);
        assertEquals(8, global[1]);
    }

    /**
     * The unused {@code targetCodes} set contract is honoured: an empty stream
     * (header only) produces a zero-count JSON with no chunk processing.
     */
    @Test
    void importCsvStreamHeaderOnlyProducesZeroCounts() {
        TestImporter importer = newImporter();
        Set<String> unused = new HashSet<>();
        assertTrue(unused.isEmpty());
        Response response = importer.importCsvStream(stream("CODE|NAME\n"), "CODE", List.of("NAME"));
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
        assertEquals(0, importer.chunkCalls);
    }

    /**
     * A data line whose key-column index falls BEYOND the row's cells yields a
     * null code, driving the {@code code == null} TRUE arm of the empty-key
     * guard (line 165). A duplicate header name ({@code CODE0}) collapses the
     * header to size 2 while pushing the key column {@code KEY} to index 2, so
     * a 2-cell data row clears the short-line guard yet resolves the key to
     * null; the line is reported and skipped without stopping the import.
     */
    @Test
    void importCsvStreamReportsNullKeyLine() {
        TestImporter importer = newImporter();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(stream("CODE0|CODE0|KEY\na|b\n"), "KEY", List.of());
            assertEquals(200, response.getStatus());
            assertEquals("{\"createdCount\":0, \"updatedCount\":0, \"errors\":[Line 2 ignored (empty key 'KEY')\"]}", response.getEntity());
            assertEquals(0, importer.chunkCalls);
        }
    }

    /**
     * An empty header cell is skipped by {@code parseHeader}, driving the
     * {@code name.isEmpty()} TRUE arm (line 343): the header {@code CODE||NAME}
     * declares two usable columns (CODE at 0, NAME at 2) and the blank middle
     * column is dropped, so the file still imports its single creation.
     */
    @Test
    void importCsvStreamSkipsEmptyHeaderColumn() {
        TestImporter importer = newImporter();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(stream("CODE||NAME\nA|x|y\n"), "CODE", List.of("NAME"));
            assertEquals(200, response.getStatus());
            assertEquals("{\"createdCount\":1, \"updatedCount\":0}", response.getEntity());
            assertEquals(1, importer.chunkCalls);
        }
    }

    /**
     * A required column equal to the (also missing) key column is not listed
     * twice: {@code missingColumns} adds the key first, then the required-column
     * loop finds it already present, driving the {@code !missing.contains(column)}
     * FALSE arm (line 367). The 400 response names the missing column once.
     */
    @Test
    void importCsvStreamDoesNotDuplicateMissingKeyColumn() {
        TestImporter importer = newImporter();
        Response response = importer.importCsvStream(stream("OTHER|NAME\nA|foo\n"), "CODE", List.of("CODE"));
        assertEquals(400, response.getStatus());
        assertEquals("{\"error\":\"Missing required columns: CODE\"}", response.getEntity());
        assertEquals(0, importer.chunkCalls);
    }
}
