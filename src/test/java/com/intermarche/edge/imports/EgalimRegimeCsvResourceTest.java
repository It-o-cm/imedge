package com.intermarche.edge.imports;

import com.intermarche.edge.domain.catalog.EgalimRegime;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EgalimRegimeCsvResource}.
 * <p>
 * The resource is the {@link EgalimRegime}-specific concretion of the abstract
 * {@link ImporterCsvResource}: for a chunk it bulk-fetches the regimes the file
 * names, then for each line it creates a regime, updates a changed one or skips
 * an identical one. Its finders resolve to {@link PanacheEntityBase} under plain
 * {@code mvn test} and are intercepted with {@link org.mockito.Mockito#mockStatic}
 * inside try-with-resources blocks; {@code Panache.getEntityManager()} is stubbed
 * the same way so {@code persist} is observable. Private helpers are reached by
 * reflection so both arms of every guard carry absolute expected values. No
 * database and no Quarkus context is booted.
 */
class EgalimRegimeCsvResourceTest {

    /** Context-map key under which the pre-fetched regime map is stored. */
    private static final String CTX_EGALIM_REGIMES = "__CTX_EGALIM_REGIMES__";

    /** The resource under test. */
    private final EgalimRegimeCsvResource resource = new EgalimRegimeCsvResource();

    /**
     * Builds a parsed line over the three declared columns.
     *
     * @param code the CODE cell
     * @param label the LABEL cell
     * @param cap the CAP_RATE cell
     * @return the parsed line
     */
    private ImporterCsvResource.LineData line(String code, String label, String cap) {
        Map<String, Integer> header = new HashMap<>();
        header.put(EgalimRegimeCsvResource.COL_CODE, 0);
        header.put(EgalimRegimeCsvResource.COL_LABEL, 1);
        header.put(EgalimRegimeCsvResource.COL_CAP_RATE, 2);
        return new ImporterCsvResource.LineData(1, header,
                new String[]{code, label, cap}, EgalimRegimeCsvResource.COL_CODE);
    }

    /**
     * Builds a regime carrying the three given values.
     *
     * @param code the regime code
     * @param label the label
     * @param cap the ceiling as a fraction, or null
     * @return the regime
     */
    private EgalimRegime regime(String code, String label, String cap) {
        EgalimRegime egalim = new EgalimRegime();
        egalim.code = code;
        egalim.label = label;
        egalim.capRate = cap == null ? null : new BigDecimal(cap);
        return egalim;
    }

    /**
     * Invokes a private method of the resource by reflection.
     *
     * @param name the method name
     * @param types the parameter types
     * @return the accessible method
     * @throws Exception when the method does not exist
     */
    private Method privateMethod(String name, Class<?>... types) throws Exception {
        Method method = EgalimRegimeCsvResource.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    /**
     * The importer names the EGALIM_REGIMES engine feed: the raw file is
     * retained verbatim and delivered to the valuation engines, which alone
     * compute the EGAlim correction from the ceiling this referential carries.
     */
    @Test
    void feedCodeNamesTheEgalimEngineFeed() {
        assertEquals("EGALIM_REGIMES", resource.feedCode());
    }

    /**
     * {@code processChunkWithFallback} answers an empty map on an empty chunk
     * without querying (true arm of the emptiness guard).
     */
    @Test
    void processChunkWithFallbackAnswersEmptyOnEmptyChunk() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            Map<String, Object> context = resource.processChunkWithFallback(
                    List.of(), Set.of(), new int[]{0, 0}, new java.util.ArrayList<>());
            assertTrue(context.isEmpty());
            panache.verifyNoInteractions();
        }
    }

    /**
     * {@code processChunkWithFallback} bulk-fetches the regimes of the chunk
     * and indexes them by their stored code (false arm of the emptiness guard,
     * true iteration of the fetch loop).
     */
    @Test
    void processChunkWithFallbackIndexesTheFetchedRegimes() {
        EgalimRegime food = regime("FOOD", "Alimentaire", "0.3400");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EgalimRegime.list(eq("code IN ?1"),
                    org.mockito.ArgumentMatchers.<Object>any())).thenReturn(List.of(food));
            Map<String, Object> context = resource.processChunkWithFallback(
                    List.of(line("FOOD", "Alimentaire", "0.3400")), Set.of("FOOD"),
                    new int[]{0, 0}, new java.util.ArrayList<>());
            @SuppressWarnings("unchecked")
            Map<String, EgalimRegime> regimes =
                    (Map<String, EgalimRegime>) context.get(CTX_EGALIM_REGIMES);
            assertEquals(1, regimes.size());
            assertSame(food, regimes.get("FOOD"));
        }
    }

    /**
     * The chunk fetch answers an empty map when the chunk names no code at all,
     * querying nothing (true arm of the fetch emptiness guard).
     */
    @Test
    void regimeMapAnswersEmptyWhenNoCode() throws Exception {
        Method method = privateMethod("regimeMap", Set.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            Map<String, EgalimRegime> regimes =
                    (Map<String, EgalimRegime>) method.invoke(null, Set.of());
            assertTrue(regimes.isEmpty());
            panache.verifyNoInteractions();
        }
    }

    /**
     * The key normalizer upper-cases the codes it keeps and drops the empty
     * cell (both arms of the null guard inside the loop).
     */
    @Test
    void codesNormalizesKeptCodesAndDropsTheEmptyCell() throws Exception {
        Method method = privateMethod("codes", Set.class);
        @SuppressWarnings("unchecked")
        List<String> parsed = (List<String>) method.invoke(null, Set.of("food", ""));
        assertEquals(List.of("FOOD"), parsed);
    }

    /**
     * The code normalizer answers null on a null cell (true arm of the null
     * guard).
     */
    @Test
    void normalizeAnswersNullOnNullCode() throws Exception {
        Method method = privateMethod("normalize", String.class);
        assertNull(method.invoke(null, (String) null));
    }

    /**
     * The code normalizer answers null on a blank cell (false arm of the null
     * guard, true arm of the emptiness ternary).
     */
    @Test
    void normalizeAnswersNullOnBlankCode() throws Exception {
        Method method = privateMethod("normalize", String.class);
        assertNull(method.invoke(null, "   "));
    }

    /**
     * The code normalizer trims and upper-cases a real cell (false arm of the
     * emptiness ternary).
     */
    @Test
    void normalizeTrimsAndUpperCasesARealCode() throws Exception {
        Method method = privateMethod("normalize", String.class);
        assertEquals("FOOD", method.invoke(null, " food "));
    }

    /**
     * A line whose code is empty is refused, which is what triggers the base
     * class rollback and one-by-one fallback (true arm of the mandatory-code
     * guard).
     */
    @Test
    void processLineLogicRefusesAnEmptyCode() {
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_EGALIM_REGIMES, new HashMap<String, EgalimRegime>());
        assertThrows(IllegalArgumentException.class, () -> resource.processLineLogic(
                line("", "Alimentaire", "0.3400"), context, new int[]{0, 0}));
    }

    /**
     * A negative ceiling is rejected (cap present, negative signum: true arm of
     * the negativity leg of the disjunction).
     */
    @Test
    void processLineLogicRefusesANegativeCap() {
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_EGALIM_REGIMES, new HashMap<String, EgalimRegime>());
        assertThrows(IllegalArgumentException.class, () -> resource.processLineLogic(
                line("FOOD", "Alimentaire", "-0.1000"), context, new int[]{0, 0}));
    }

    /**
     * A ceiling above one is rejected (cap present, non-negative, greater than
     * one: false negativity leg, true above-one leg of the disjunction).
     */
    @Test
    void processLineLogicRefusesACapAboveOne() {
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_EGALIM_REGIMES, new HashMap<String, EgalimRegime>());
        assertThrows(IllegalArgumentException.class, () -> resource.processLineLogic(
                line("FOOD", "Alimentaire", "1.5000"), context, new int[]{0, 0}));
    }

    /**
     * A code the referential does not carry yet creates a regime, counted as a
     * creation and persisted (valid cap: both disjunction legs false; regime
     * absent: true arm of the novelty guard).
     */
    @Test
    void processLineLogicCreatesAnUnknownRegime() {
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_EGALIM_REGIMES, new HashMap<String, EgalimRegime>());
        int[] counters = new int[]{0, 0};
        EntityManager em = mock(EntityManager.class);
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(line("FOOD", "Alimentaire", "0.3400"), context, counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(em).persist(captor.capture());
            EgalimRegime created = (EgalimRegime) captor.getValue();
            assertEquals("FOOD", created.code);
            assertEquals("Alimentaire", created.label);
            assertEquals(new BigDecimal("0.3400"), created.capRate);
        }
    }

    /**
     * A known code whose checksum moved is reloaded and refed, counted as an
     * update (regime present: false novelty arm; checksum differs: true arm of
     * the change guard).
     */
    @Test
    void processLineLogicUpdatesAChangedRegime() {
        EgalimRegime existing = regime("FOOD", "Alimentaire", "0.3400");
        existing.id = 11L;
        existing.checksum = 0;
        Map<String, EgalimRegime> regimes = new HashMap<>();
        regimes.put("FOOD", existing);
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_EGALIM_REGIMES, regimes);
        int[] counters = new int[]{0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EgalimRegime.findById(11L)).thenReturn(existing);
            resource.processLineLogic(line("FOOD", "Alimentaire", "0.4000"), context, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals(new BigDecimal("0.4000"), existing.capRate);
        }
    }

    /**
     * A known regime whose stored checksum is null is treated as changed and
     * rewritten, never skipped (null-checksum arm of {@code changedFrom}, which
     * a schema-added column leaves null on pre-existing rows).
     */
    @Test
    void processLineLogicUpdatesARegimeWithoutChecksum() {
        EgalimRegime existing = regime("FOOD", "Alimentaire", "0.3400");
        existing.id = 12L;
        existing.checksum = null;
        Map<String, EgalimRegime> regimes = new HashMap<>();
        regimes.put("FOOD", existing);
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_EGALIM_REGIMES, regimes);
        int[] counters = new int[]{0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> EgalimRegime.findById(12L)).thenReturn(existing);
            resource.processLineLogic(line("FOOD", "Alimentaire", "0.4000"), context, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals(new BigDecimal("0.4000"), existing.capRate);
        }
    }

    /**
     * A known code whose checksum matches is left untouched and counted neither
     * way (empty cap: false arm of the cap-presence guard; matching checksum:
     * false arm of the change guard).
     */
    @Test
    void processLineLogicSkipsAnUnchangedRegime() {
        EgalimRegime existing = regime("FOOD", "Alimentaire", null);
        existing.id = 13L;
        existing.checksum = Objects.hash("FOOD", "Alimentaire", null);
        Map<String, EgalimRegime> regimes = new HashMap<>();
        regimes.put("FOOD", existing);
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_EGALIM_REGIMES, regimes);
        int[] counters = new int[]{0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            resource.processLineLogic(line("FOOD", "Alimentaire", ""), context, counters);
            assertEquals(0, counters[0]);
            assertEquals(0, counters[1]);
            panache.verifyNoInteractions();
        }
    }

    /**
     * Without a context map — the one-by-one fallback of the base class — the
     * line looks its own regime up in the database (true arm of the map guard),
     * and finding one (true arm of the found guard) takes the update path.
     */
    @Test
    void processLineLogicFallsBackOnADatabaseLookupAndUpdates() {
        EgalimRegime existing = regime("FOOD", "Alimentaire", "0.3400");
        existing.id = 14L;
        existing.checksum = 0;
        int[] counters = new int[]{0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<EgalimRegime> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(existing);
            panache.when(() -> EgalimRegime.find("code", "FOOD")).thenReturn(query);
            panache.when(() -> EgalimRegime.findById(14L)).thenReturn(existing);
            resource.processLineLogic(line("FOOD", "Alimentaire", "0.4000"),
                    new HashMap<>(), counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals(new BigDecimal("0.4000"), existing.capRate);
        }
    }

    /**
     * The one-by-one fallback finding nothing (false arm of the found guard)
     * takes the creation path.
     */
    @Test
    void processLineLogicFallbackCreatesWhenTheLookupIsEmpty() {
        int[] counters = new int[]{0, 0};
        EntityManager em = mock(EntityManager.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedStatic<Panache> statics = mockStatic(Panache.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<EgalimRegime> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> EgalimRegime.find("code", "DPH")).thenReturn(query);
            statics.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(line("DPH", "Droguerie", "0.4000"),
                    new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            verify(em).persist(org.mockito.ArgumentMatchers.any(EgalimRegime.class));
        }
    }

    /**
     * {@code findEntityForLine} resolves the line's regime through the
     * referential finder on the normalized code.
     */
    @Test
    void findEntityForLineResolvesThroughTheFinder() {
        EgalimRegime existing = regime("FOOD", "Alimentaire", "0.3400");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<EgalimRegime> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(existing);
            panache.when(() -> EgalimRegime.find("code", "FOOD")).thenReturn(query);
            assertSame(existing, resource.findEntityForLine(line(" food ", "Alimentaire", "0.3400")));
        }
    }

    /**
     * The incoming checksum reproduces {@link EgalimRegime#getChecksum()}
     * exactly, which is what makes the update-vs-skip comparison meaningful.
     */
    @Test
    void incomingChecksumReproducesTheEntityChecksum() throws Exception {
        Method method = privateMethod("computeIncomingChecksum",
                String.class, ImporterCsvResource.LineData.class, BigDecimal.class);
        int incoming = (int) method.invoke(resource, "FOOD",
                line("FOOD", "Alimentaire", "0.3400"), new BigDecimal("0.3400"));
        assertEquals(regime("FOOD", "Alimentaire", "0.3400").getChecksum(), incoming);
    }

    /**
     * A regime without a ceiling hashes a null cap on both sides, so the exempt
     * category — declared with no ceiling — never looks like a change.
     */
    @Test
    void incomingChecksumHandlesAnAbsentCap() throws Exception {
        Method method = privateMethod("computeIncomingChecksum",
                String.class, ImporterCsvResource.LineData.class, BigDecimal.class);
        int incoming = (int) method.invoke(resource, "EXEMPT",
                line("EXEMPT", "Exonéré", ""), (BigDecimal) null);
        assertEquals(Objects.hash("EXEMPT", "Exonéré", null), incoming);
    }

    /**
     * Guards that the mandatory-code refusal carries the documented message,
     * unwrapping the reflection-free direct call rather than an
     * {@link InvocationTargetException}.
     */
    @Test
    void processLineLogicMandatoryCodeMessageIsStated() {
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_EGALIM_REGIMES, new HashMap<String, EgalimRegime>());
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(line(null, "Alimentaire", "0.3400"),
                        context, new int[]{0, 0}));
        assertEquals("EGAlim regime code is mandatory.", thrown.getMessage());
        assertInstanceOf(IllegalArgumentException.class, thrown);
    }

    /**
     * {@code importEgalimRegimes} logs and forwards the stream to the base
     * importer with the CODE key and no required column; a header-only body
     * produces a zero-count JSON with a 200 status, exercising the entry
     * method's log-and-delegate body.
     */
    @Test
    void importEgalimRegimesDelegatesToTheBaseImporter() {
        Response response = resource.importEgalimRegimes(new ByteArrayInputStream(
                "CODE|LABEL|CAP_RATE\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
    }
}
