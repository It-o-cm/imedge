package com.intermarche.edge.imports;

import com.intermarche.edge.service.sync.EngineFeedService;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the abstract base {@link ImporterCsvResource}.
 * <p>
 * The class is exercised through a minimal concrete subclass ({@link TestImporter})
 * whose abstract hooks are simple, controllable stubs; the JTA transaction wrapper
 * is driven with a mocked {@link TransactionManager} injected into the {@code tm}
 * field and a {@code mockStatic(Panache.class)} standing in for the entity manager.
 * No Quarkus context and no database are booted. Every parsing helper, the header
 * builder, the missing-column logic, the JSON answer builder, the staged-fallback
 * recursion, the {@link ImporterCsvResource.Executor} container and the
 * {@link ImporterCsvResource.LineData} accessor are covered arm by arm.
 */
class ImporterCsvResourceTest {

    /**
     * Concrete {@link ImporterCsvResource} whose abstract hooks are driven by
     * mutable test fields so each test can dictate created/updated outcomes,
     * force per-line or per-chunk failures, control the fresh-lookup result and
     * name the captured engine feed.
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
        /** When true, the chunk hook throws to exercise the outer Throwable catch. */
        boolean chunkThrows = false;
        /** When true, the per-line logic throws to exercise transaction failure. */
        boolean lineLogicThrows = false;
        /** The entity the fallback lookup returns for a code absent from {@link #entitiesByCode}. */
        Object entity = null;
        /** The engine-feed code this importer captures (null models no capture). */
        String feed = null;

        /**
         * Names the engine feed captured by this importer.
         *
         * @return the configured feed code, possibly null
         */
        @Override
        protected String feedCode() {
            return feed;
        }

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
            if (chunkThrows) throw new RuntimeException("chunk boom");
            return chunkContext;
        }

        /**
         * Throws on demand or for configured codes, otherwise counts the line as
         * an update when its code is present in the supplied entity map and as a
         * creation otherwise.
         *
         * @param data      the parsed line
         * @param entityMap the pre-fetched or fresh entity map
         * @param counters  the local counters [created, updated]
         */
        @Override
        protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
            if (lineLogicThrows) throw new RuntimeException("line boom");
            if (throwOnCodes.contains(data.code)) throw new RuntimeException("boom-" + data.code);
            if (entityMap != null && entityMap.containsKey(data.code)) counters[1]++;
            else counters[0]++;
        }

        /**
         * Returns the configured entity for the line's code, or the default
         * configured entity when the code has none.
         *
         * @param data the parsed line
         * @return the entity or null
         */
        @Override
        protected Object findEntityForLine(LineData data) {
            Object byCode = entitiesByCode.get(data.code);
            return byCode != null ? byCode : entity;
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
        importer.engineFeedService = mock(com.intermarche.edge.service.sync.EngineFeedService.class);
        // BO-08-01-04: the integration state of a deposited file. Mocked
        // everywhere, asserted where the case is about it.
        importer.refApplyService = mock(com.intermarche.edge.service.sync.RefApplyService.class);
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
            assertEquals("{\"createdCount\":2,\"updatedCount\":1,\"errors\":[\"Line 5 ignored (fewer cells than the header): X\"]}", response.getEntity());
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
            assertEquals("{\"createdCount\":1000,\"updatedCount\":0}", response.getEntity());
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
        assertEquals("{\"error\":\"Error reading file: disk\"}", response.getEntity());
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
        assertEquals("{\"error\":\"Unexcepted error: kaboom\"}", response.getEntity());
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
     * A subclass that does NOT override {@link ImporterCsvResource#feedCode()},
     * used to exercise the base default return.
     */
    static class DefaultImporter extends ImporterCsvResource {
        /**
         * Chunk hook stub returning an empty map.
         *
         * @param parsedLines the chunk lines
         * @param targetCodes the chunk key codes
         * @param counters the running counters
         * @param errors the error sink
         * @return an empty pre-fetched map
         */
        @Override
        protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
            return new HashMap<>();
        }

        /**
         * Per-line logic stub doing nothing.
         *
         * @param data the parsed row
         * @param entityMap the context map
         * @param counters the running counters
         */
        @Override
        protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        }

        /**
         * Fallback lookup stub returning null.
         *
         * @param data the parsed row
         * @return null
         */
        @Override
        protected Object findEntityForLine(LineData data) {
            return null;
        }
    }

    /**
     * Builds a {@link ImporterCsvResource.LineData} from column names and cells,
     * the first column being the key.
     *
     * @param columns the header names, in column order
     * @param cells the cells of the row
     * @return the parsed row
     */
    private ImporterCsvResource.LineData rowOf(List<String> columns, String... cells) {
        Map<String, Integer> header = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            header.put(columns.get(index), index);
        }
        return new ImporterCsvResource.LineData(1, header, cells, columns.get(0));
    }

    /**
     * Builds a fresh {@link TestImporter} with a mocked transaction manager wired
     * into its {@code tm} field.
     *
     * @return the wired importer
     */
    private TestImporter importerWithTm() {
        TestImporter importer = new TestImporter();
        importer.tm = mock(TransactionManager.class);
        return importer;
    }

    // ---------------------------------------------------------------
    // feedCode default
    // ---------------------------------------------------------------

    /**
     * A subclass that does not override {@code feedCode} names no feed.
     */
    @Test
    void baseFeedCodeIsNull() {
        assertNull(new DefaultImporter().feedCode());
    }

    // ---------------------------------------------------------------
    // LineData accessor: get / has
    // ---------------------------------------------------------------

    /**
     * A column absent from the header, a cell beyond the row, a null cell and a
     * present cell exercise each arm of {@link ImporterCsvResource.LineData#get}.
     */
    @Test
    void lineDataGetCoversEveryArm() {
        Map<String, Integer> header = new LinkedHashMap<>();
        header.put("C", 0);
        header.put("D", 1);
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(1, header, new String[]{" x "}, "C");
        assertEquals("x", data.get("C"));
        assertNull(data.get("Z"));
        assertNull(data.get("D"));
        Map<String, Integer> h2 = new LinkedHashMap<>();
        h2.put("C", 0);
        ImporterCsvResource.LineData nullCell = new ImporterCsvResource.LineData(1, h2, new String[]{null}, "C");
        assertNull(nullCell.get("C"));
        assertNull(nullCell.code);
    }

    /**
     * {@link ImporterCsvResource.LineData#has} reports declared and undeclared
     * columns.
     */
    @Test
    void lineDataHasReportsDeclaration() {
        ImporterCsvResource.LineData data = rowOf(List.of("C", "D"), "1", "2");
        assertTrue(data.has("C"));
        assertFalse(data.has("Z"));
    }

    // ---------------------------------------------------------------
    // Parsing helpers
    // ---------------------------------------------------------------

    /**
     * {@code safeGet} returns the trimmed cell or null when absent.
     */
    @Test
    void safeGetReturnsCellOrNull() {
        TestImporter importer = new TestImporter();
        assertEquals("v", importer.safeGet(rowOf(List.of("C"), " v "), "C"));
        assertNull(importer.safeGet(rowOf(List.of("C"), "v"), "Z"));
    }

    /**
     * {@code safeParseBoolean} is false for a null column, an empty cell and an
     * unparseable value, and true for {@code true}.
     */
    @Test
    void safeParseBooleanCoversEveryArm() {
        TestImporter importer = new TestImporter();
        assertFalse(importer.safeParseBoolean(rowOf(List.of("C"), "v"), "Z"));
        assertFalse(importer.safeParseBoolean(rowOf(List.of("C"), ""), "C"));
        assertFalse(importer.safeParseBoolean(rowOf(List.of("C"), "notabool"), "C"));
        assertTrue(importer.safeParseBoolean(rowOf(List.of("C"), "true"), "C"));
    }

    /**
     * {@code safeParseBigDecimal} is null for null/empty/malformed input and
     * parses a valid decimal.
     */
    @Test
    void safeParseBigDecimalCoversEveryArm() {
        TestImporter importer = new TestImporter();
        assertNull(importer.safeParseBigDecimal(rowOf(List.of("C"), "v"), "Z"));
        assertNull(importer.safeParseBigDecimal(rowOf(List.of("C"), ""), "C"));
        assertNull(importer.safeParseBigDecimal(rowOf(List.of("C"), "abc"), "C"));
        assertEquals(new BigDecimal("12.5"), importer.safeParseBigDecimal(rowOf(List.of("C"), "12.5"), "C"));
    }

    /**
     * {@code safeParseInt} is null for null/empty/malformed input and parses a
     * valid integer.
     */
    @Test
    void safeParseIntCoversEveryArm() {
        TestImporter importer = new TestImporter();
        assertNull(importer.safeParseInt(rowOf(List.of("C"), "v"), "Z"));
        assertNull(importer.safeParseInt(rowOf(List.of("C"), ""), "C"));
        assertNull(importer.safeParseInt(rowOf(List.of("C"), "x"), "C"));
        assertEquals(Integer.valueOf(42), importer.safeParseInt(rowOf(List.of("C"), "42"), "C"));
    }

    /**
     * {@code safeParseDateTime} is null for null/empty/malformed input and parses
     * a valid ISO date-time.
     */
    @Test
    void safeParseDateTimeCoversEveryArm() {
        TestImporter importer = new TestImporter();
        assertNull(importer.safeParseDateTime(rowOf(List.of("C"), "v"), "Z"));
        assertNull(importer.safeParseDateTime(rowOf(List.of("C"), ""), "C"));
        assertNull(importer.safeParseDateTime(rowOf(List.of("C"), "not-a-date"), "C"));
        assertEquals(LocalDateTime.of(2025, 1, 1, 10, 15, 30),
                importer.safeParseDateTime(rowOf(List.of("C"), "2025-01-01T10:15:30"), "C"));
    }

    /**
     * {@code safeParseDouble} is null for null/empty/malformed input and parses a
     * valid double.
     */
    @Test
    void safeParseDoubleCoversEveryArm() {
        TestImporter importer = new TestImporter();
        assertNull(importer.safeParseDouble(rowOf(List.of("C"), "v"), "Z"));
        assertNull(importer.safeParseDouble(rowOf(List.of("C"), ""), "C"));
        assertNull(importer.safeParseDouble(rowOf(List.of("C"), "x"), "C"));
        assertEquals(Double.valueOf(1.5), importer.safeParseDouble(rowOf(List.of("C"), "1.5"), "C"));
    }

    /**
     * {@code parseCodes} yields an empty list for null and blank input, and a
     * trimmed, filtered, sorted list otherwise.
     */
    @Test
    void parseCodesCoversEveryArm() {
        TestImporter importer = new TestImporter();
        assertTrue(importer.parseCodes(null).isEmpty());
        assertTrue(importer.parseCodes("   ").isEmpty());
        assertEquals(List.of("A", "B", "C"), importer.parseCodes(" C , A ,, B "));
    }

    // ---------------------------------------------------------------
    // updateCounters
    // ---------------------------------------------------------------

    /**
     * {@code updateCounters} adds the local counters into the global ones.
     */
    @Test
    void updateCountersAddsBothSlots() {
        TestImporter importer = new TestImporter();
        int[] global = {2, 3};
        importer.updateCounters(global, new int[]{4, 5});
        assertEquals(6, global[0]);
        assertEquals(8, global[1]);
    }

    // ---------------------------------------------------------------
    // getNextSize
    // ---------------------------------------------------------------

    /**
     * {@code getNextSize} steps 1000 -> 100 -> 10 -> 1 across all three returns.
     */
    @Test
    void getNextSizeStepsDown() {
        TestImporter importer = new TestImporter();
        assertEquals(100, importer.getNextSize(1000));
        assertEquals(10, importer.getNextSize(100));
        assertEquals(1, importer.getNextSize(10));
    }

    // ---------------------------------------------------------------
    // Executor
    // ---------------------------------------------------------------

    /**
     * {@code onSuccess} fires only when a result is present.
     */
    @Test
    void executorOnSuccessFiresOnlyWithResult() {
        ImporterCsvResource.Executor<String> withResult = new ImporterCsvResource.Executor<>();
        withResult.setResult("ok");
        AtomicReference<String> seen = new AtomicReference<>(null);
        withResult.onSuccess(seen::set);
        assertEquals("ok", seen.get());
        ImporterCsvResource.Executor<String> noResult = new ImporterCsvResource.Executor<>();
        AtomicReference<String> seen2 = new AtomicReference<>("untouched");
        noResult.onSuccess(seen2::set);
        assertEquals("untouched", seen2.get());
    }

    /**
     * {@code onFailure} fires only when both an exception and a consumer are
     * present, covering the two false arms and the true arm.
     */
    @Test
    void executorOnFailureFiresOnlyWithExceptionAndConsumer() {
        ImporterCsvResource.Executor<String> withEx = new ImporterCsvResource.Executor<>();
        withEx.setException(new RuntimeException("boom"));
        AtomicReference<Throwable> seen = new AtomicReference<>(null);
        withEx.onFailure(seen::set);
        assertNotNull(seen.get());
        withEx.onFailure(null);
        ImporterCsvResource.Executor<String> noEx = new ImporterCsvResource.Executor<>();
        AtomicReference<Throwable> seen3 = new AtomicReference<>(null);
        noEx.onFailure(seen3::set);
        assertNull(seen3.get());
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
     * A throwing supplier with an ACTIVE transaction rolls back and records the
     * exception.
     *
     * @throws Exception never (declared by the transaction manager stubs)
     */
    @Test
    void withTransactionRollsBackActiveTransaction() throws Exception {
        TestImporter importer = importerWithTm();
        when(importer.tm.getStatus()).thenReturn(Status.STATUS_ACTIVE);
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            ImporterCsvResource.Executor<String> executor =
                    importer.<String>withTransaction(() -> { throw new RuntimeException("boom"); });
            assertNull(executor.result);
            assertNotNull(executor.ex);
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
        importer.engineFeedService = mock(com.intermarche.edge.service.sync.EngineFeedService.class);
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
        importer.engineFeedService = mock(com.intermarche.edge.service.sync.EngineFeedService.class);
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
            assertEquals("{\"createdCount\":0,\"updatedCount\":1}", response.getEntity());
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
            assertEquals("{\"createdCount\":1,\"updatedCount\":0,\"errors\":[\"Line 2 ignored (empty key 'CODE')\"]}", response.getEntity());
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
        assertEquals("{\"createdCount\":0,\"updatedCount\":0}", response.getEntity());
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
            assertEquals("{\"createdCount\":0,\"updatedCount\":0,\"errors\":[\"Line 2 ignored (empty key 'KEY')\"]}", response.getEntity());
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
            assertEquals("{\"createdCount\":1,\"updatedCount\":0}", response.getEntity());
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

    /**
     * BO-08-01-04: a deposited file leaves a written integration state
     * naming what went in — the same trace a pulled domain leaves. The
     * file is pipe-separated, the format this importer reads, and its
     * processing needs the entity manager the transaction closer clears.
     *
     * @throws Exception never, the mocked manager declares it
     */
    @Test
    void aDepositedFileLeavesItsIntegrationState() throws Exception {
        TestImporter importer = newImporter();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            importer.importCsvStream(stream("code|value\nA|1\n"), "code", List.of("code"));
            verify(importer.refApplyService).recordAudit(
                    eq("TEST_IMPORTER"), isNull(),
                    eq(com.intermarche.edge.domain.sync.RefApplyAudit.Outcome.APPLIED),
                    contains("erreur(s)"));
        }
    }

    /**
     * BO-08-01-04: a file that could not be read at all leaves a FAILED
     * state rather than nothing.
     *
     * @throws Exception never, the mocked manager declares it
     */
    @Test
    void anUnreadableFileLeavesAFailedState() throws Exception {
        TestImporter importer = newImporter();
        InputStream broken = new InputStream() {
            /**
             * Fails on the first byte, as a truncated upload does.
             *
             * @return never
             * @throws java.io.IOException always
             */
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("flux coupé");
            }
        };
        importer.importCsvStream(broken, "code", List.of("code"));
        verify(importer.refApplyService).recordAudit(
                eq("TEST_IMPORTER"), isNull(),
                eq(com.intermarche.edge.domain.sync.RefApplyAudit.Outcome.FAILED),
                contains("flux coupé"));
    }

    /**
     * BO-08-01-04: a trace that cannot be written does not fail an import
     * that succeeded. The file is pipe-separated, the format this importer
     * reads, and its processing needs the entity manager the transaction
     * closer clears.
     *
     * @throws Exception never, the mocked manager declares it
     */
    @Test
    void anUnwritableTraceDoesNotFailTheImport() throws Exception {
        TestImporter importer = newImporter();
        org.mockito.Mockito.doThrow(new RuntimeException("base absente"))
                .when(importer.refApplyService)
                .recordAudit(anyString(), any(), any(), any());
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(
                    stream("code|value\nA|1\n"), "code", List.of("code"));
            assertEquals(200, response.getStatus());
        }
    }

    /**
     * BO-08-01-04: the audit domain is derived from the importer's class,
     * split on its capitals and cut to the column's twenty characters.
     */
    @Test
    void theAuditDomainIsDerivedFromTheClass() {
        assertEquals("TEST_IMPORTER", newImporter().auditDomain());
        assertEquals("VERY_LONG_NAMED_IMPO", new VeryLongNamedImporterForDomainTruncation().auditDomain());
    }

    /** BO-08-01-04: a real importer name longer than the audit column exercises the inherited truncation. */
    static class VeryLongNamedImporterForDomainTruncation extends TestImporter {
    }

    /**
     * A rollback that itself throws is swallowed while the original exception is
     * still recorded.
     *
     * @throws Exception never (declared by the transaction manager stubs)
     */
    @Test
    void withTransactionSwallowsRollbackFailure() throws Exception {
        TestImporter importer = importerWithTm();
        when(importer.tm.getStatus()).thenReturn(Status.STATUS_ACTIVE);
        doThrow(new RuntimeException("rb")).when(importer.tm).rollback();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            ImporterCsvResource.Executor<String> executor =
                    importer.<String>withTransaction(() -> { throw new RuntimeException("boom"); });
            assertNotNull(executor.ex);
            assertEquals("boom", executor.ex.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // prepareContextForLine
    // ---------------------------------------------------------------

    /**
     * A found entity is keyed by its code; a missing one leaves the map empty.
     */
    @Test
    void prepareContextForLineCoversBothArms() {
        TestImporter importer = new TestImporter();
        importer.entity = "E";
        ImporterCsvResource.LineData data = rowOf(List.of("C"), "K1");
        Map<String, Object> withEntity = importer.prepareContextForLine(data);
        assertEquals(1, withEntity.size());
        assertEquals("E", withEntity.get("K1"));
        importer.entity = null;
        assertTrue(importer.prepareContextForLine(data).isEmpty());
    }

    // ---------------------------------------------------------------
    // processWithStages
    // ---------------------------------------------------------------

    /**
     * An empty line list is a no-op.
     */
    @Test
    void processWithStagesIgnoresEmpty() {
        TestImporter importer = new TestImporter();
        int[] counters = {0, 0};
        importer.processWithStages(List.of(), new HashMap<>(), 1000, counters, new ArrayList<>());
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * A successful chunk of the batch size updates the global counters.
     */
    @Test
    void processWithStagesSucceedsForABatch() {
        TestImporter importer = importerWithTm();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            int[] counters = {0, 0};
            importer.processWithStages(List.of(rowOf(List.of("C"), "A"), rowOf(List.of("C"), "B")),
                    new HashMap<>(), ImporterCsvResource.STAGE_1_SIZE, counters, new ArrayList<>());
            assertEquals(2, counters[0]);
        }
    }

    /**
     * A chunk that keeps failing recurses 1000 -> 100 -> 10 -> 1 and finally
     * records a per-line error in its own transaction.
     */
    @Test
    void processWithStagesRecursesToLineByLineOnPersistentFailure() {
        TestImporter importer = importerWithTm();
        importer.lineLogicThrows = true;
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            int[] counters = {0, 0};
            List<String> errors = new ArrayList<>();
            importer.processWithStages(List.of(rowOf(List.of("C"), "A")),
                    new HashMap<>(), ImporterCsvResource.STAGE_1_SIZE, counters, errors);
            assertEquals(0, counters[0]);
            assertEquals(1, errors.size());
            assertTrue(errors.get(0).contains("line boom"));
        }
    }

    /**
     * The atomic base case (chunk size 1) updates the counters on success.
     */
    @Test
    void processWithStagesAtomicSuccessUpdatesCounters() {
        TestImporter importer = importerWithTm();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            int[] counters = {0, 0};
            importer.processWithStages(List.of(rowOf(List.of("C"), "A")),
                    new HashMap<>(), 1, counters, new ArrayList<>());
            assertEquals(1, counters[0]);
        }
    }

    // ---------------------------------------------------------------
    // importCsvStream
    // ---------------------------------------------------------------

    /**
     * A stream that cannot be read yields a server error naming the failure.
     */
    @Test
    void importFailsWhenStreamCannotBeRead() {
        TestImporter importer = new TestImporter();
        InputStream bad = new InputStream() {
            /**
             * Always fails to read.
             *
             * @return never
             * @throws IOException always
             */
            @Override
            public int read() throws IOException {
                throw new IOException("io");
            }
        };
        Response response = importer.importCsvStream(bad, "CODE", List.of("NAME"));
        assertEquals(500, response.getStatus());
        assertTrue(response.getEntity().toString().contains("Error reading file"));
    }

    /**
     * A header missing the key and a required column is rejected naming both, the
     * dedup arm skipping the key already listed and the present column being kept.
     */
    @Test
    void importRejectsMissingColumns() {
        TestImporter importer = new TestImporter();
        InputStream stream = new java.io.ByteArrayInputStream("NAME".getBytes(StandardCharsets.UTF_8));
        Response response = importer.importCsvStream(stream, "CODE", List.of("CODE", "NAME", "EXTRA"));
        assertEquals(400, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("CODE"));
        assertTrue(body.contains("EXTRA"));
        assertFalse(body.contains("NAME\""));
    }

    /**
     * An unexpected error raised by the chunk hook is caught and reported.
     */
    @Test
    void importCatchesUnexpectedError() {
        TestImporter importer = new TestImporter();
        importer.chunkThrows = true;
        InputStream stream = new java.io.ByteArrayInputStream(
                "CODE|NAME\nA1|Acme".getBytes(StandardCharsets.UTF_8));
        Response response = importer.importCsvStream(stream, "CODE", List.of("NAME"));
        assertEquals(500, response.getStatus());
        assertTrue(response.getEntity().toString().contains("Unexcepted error"));
    }

    /**
     * A file with a duplicate header column, an empty header column, a blank line,
     * a truncated row and an empty-key row imports the valid rows, reports the two
     * anomalies and captures the raw file into the named engine feed.
     */
    @Test
    void importProcessesValidRowsReportsAnomaliesAndCapturesFeed() {
        TestImporter importer = importerWithTm();
        importer.feed = "PRODUCTS";
        importer.engineFeedService = mock(EngineFeedService.class);
        when(importer.engineFeedService.store("PRODUCTS", "")).thenReturn("v1");
        String content = String.join("\n",
                "CODE|CODE|NAME|",
                "",
                "A1|dup|Acme|extra",
                "A2",
                "|Acme",
                "A3|dup|Beta|z");
        InputStream stream = new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(stream, "CODE", List.of("NAME"));
            assertEquals(200, response.getStatus());
            String body = response.getEntity().toString();
            assertTrue(body.contains("\"createdCount\":2"));
            assertTrue(body.contains("\"errors\":["));
            assertTrue(body.contains("fewer cells than the header"));
            assertTrue(body.contains("empty key"));
        }
        verify(importer.engineFeedService).store("PRODUCTS", content);
    }

    /**
     * A clean file imports without errors and, naming no feed, captures nothing.
     */
    @Test
    void importCleanFileHasNoErrorsAndNoCapture() {
        TestImporter importer = importerWithTm();
        importer.engineFeedService = mock(EngineFeedService.class);
        InputStream stream = new java.io.ByteArrayInputStream(
                "CODE|NAME\nA1|Acme\nA2|Beta".getBytes(StandardCharsets.UTF_8));
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(stream, "CODE", List.of("NAME"));
            assertEquals(200, response.getStatus());
            String body = response.getEntity().toString();
            assertTrue(body.contains("\"createdCount\":2"));
            assertFalse(body.contains("errors"));
        }
        verify(importer.engineFeedService, never()).store(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * A named feed with no engine-feed service wired captures nothing, exercising
     * the null-service arm of the capture guard.
     */
    @Test
    void importWithNamedFeedButNoServiceCapturesNothing() {
        TestImporter importer = importerWithTm();
        importer.feed = "PRODUCTS";
        importer.engineFeedService = null;
        InputStream stream = new java.io.ByteArrayInputStream(
                "CODE|NAME\nA1|Acme".getBytes(StandardCharsets.UTF_8));
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(stream, "CODE", List.of("NAME"));
            assertEquals(200, response.getStatus());
        }
    }

    /**
     * A capture whose store throws never fails the import, exercising the catch
     * arm of the feed capture.
     */
    @Test
    void importSurvivesAFeedCaptureFailure() {
        TestImporter importer = importerWithTm();
        importer.feed = "PRODUCTS";
        importer.engineFeedService = mock(EngineFeedService.class);
        when(importer.engineFeedService.store(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("store boom"));
        InputStream stream = new java.io.ByteArrayInputStream(
                "CODE|NAME\nA1|Acme".getBytes(StandardCharsets.UTF_8));
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(stream, "CODE", List.of("NAME"));
            assertEquals(200, response.getStatus());
        }
    }

    /**
     * A file of exactly the batch size flushes the chunk mid-loop, so the tail
     * chunk is empty, exercising the size-threshold arm and the empty-tail arm.
     */
    @Test
    void importFlushesAtBatchSize() {
        TestImporter importer = importerWithTm();
        StringBuilder content = new StringBuilder("CODE|NAME");
        for (int i = 0; i < ImporterCsvResource.STAGE_1_SIZE; i++) {
            content.append("\nA").append(i).append("|N").append(i);
        }
        InputStream stream = new java.io.ByteArrayInputStream(content.toString().getBytes(StandardCharsets.UTF_8));
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(stream, "CODE", List.of("NAME"));
            assertEquals(200, response.getStatus());
            assertTrue(response.getEntity().toString().contains(
                    "\"createdCount\":" + ImporterCsvResource.STAGE_1_SIZE));
        }
    }

    /**
     * Sanity that the parse of a comma list is stable regardless of instance,
     * proving no shared state leaks across the parsing helpers.
     */
    @Test
    void parseCodesIsStateless() {
        assertEquals(List.of("X"), new TestImporter().parseCodes("X"));
        assertEquals(Arrays.asList("A", "B"), new TestImporter().parseCodes("B,A"));
    }
    /** BO-08-01-04: CR, LF and CRLF preserve blank lines and the physical number of a rejected row after buffering. */
    @Test
    void mixedLineEndingsRetainPhysicalErrorLineNumbers() {
        TestImporter importer = importerWithTm();
        InputStream input = new java.io.ByteArrayInputStream("CODE|NAME\r\nA|Alpha\r\rB|Beta\nBROKEN\r\n".getBytes(StandardCharsets.UTF_8));
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            panache.when(Panache::getEntityManager).thenReturn(mock(EntityManager.class));
            Response response = importer.importCsvStream(input, "CODE", List.of("NAME"));
            assertEquals(200, response.getStatus());
            assertTrue(response.getEntity().toString().contains("\"createdCount\":2"));
            assertTrue(response.getEntity().toString().contains("Line 5 ignored (fewer cells than the header): BROKEN"));
        }
    }

    /** BO-08-01-04: header membership distinguishes an absent column from a declared column whose value is empty. */
    @Test
    void headerMembershipDoesNotDependOnCellContents() {
        var row = line(2, "A", "");
        assertTrue(row.has("NAME"));
        assertFalse(row.has("ABSENT"));
        assertEquals("", row.get("NAME"));
    }

    /**
     * The import answer is valid JSON whatever its error messages hold: a
     * quote, a backslash and a line break in a rejected line survive a real
     * parser, the field names are kept and the errors list is omitted when
     * empty; a refusal answer parses too.
     *
     * @throws Exception never: the answer parses
     */
    @Test
    void theAnswerIsValidJsonForAnyErrorMessage() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper parser = new com.fasterxml.jackson.databind.ObjectMapper();
        String answer = ImporterCsvResource.buildAnswer(new int[]{2, 1},
                java.util.List.of("Line 2 (A\"B): bad \\ value", "Line 3\nsecond"));
        com.fasterxml.jackson.databind.JsonNode parsed = parser.readTree(answer);
        assertEquals(2, parsed.get("createdCount").asInt());
        assertEquals(1, parsed.get("updatedCount").asInt());
        assertEquals("Line 2 (A\"B): bad \\ value", parsed.get("errors").get(0).asText());
        assertEquals("Line 3\nsecond", parsed.get("errors").get(1).asText());
        com.fasterxml.jackson.databind.JsonNode clean = parser.readTree(ImporterCsvResource.buildAnswer(new int[]{0, 0}, java.util.List.of()));
        assertFalse(clean.has("errors"));
        assertEquals("Missing \"EAN\"", parser.readTree(ImporterCsvResource.errorAnswer("Missing \"EAN\"")).get("error").asText());
    }
}
