package com.intermarche.edge.imports;

import com.intermarche.edge.domain.catalog.VatRate;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link VatRateCsvResource}.
 * <p>
 * The resource is the {@link VatRate}-specific concretion of the abstract
 * {@link ImporterCsvResource}: for a chunk it bulk-fetches the regimes the
 * file names, then for each line creates a regime or compares checksums on an
 * existing one. Its finders resolve to {@link PanacheEntityBase} under plain
 * {@code mvn test} and are intercepted with
 * {@link org.mockito.Mockito#mockStatic} inside a try-with-resources block;
 * {@code Panache.getEntityManager()} is stubbed the same way so
 * {@code persist} is observable. The private helpers are reached by
 * reflection so both arms of every guard carry absolute expected values. No
 * database and no Quarkus context is booted.
 */
class VatRateCsvResourceTest {

    /** Context-map key under which the pre-fetched regime map is stored. */
    private static final String CTX_VAT_RATES = "__CTX_VAT_RATES__";

    /** The resource under test. */
    private final VatRateCsvResource resource = new VatRateCsvResource();

    /**
     * Builds a parsed line over the three declared columns.
     *
     * @param number the VAT_NUMBER cell
     * @param rate the RATE cell
     * @param label the LABEL cell
     * @return the parsed line
     */
    private ImporterCsvResource.LineData line(String number, String rate, String label) {
        Map<String, Integer> header = new HashMap<>();
        header.put(VatRateCsvResource.COL_VAT_NUMBER, 0);
        header.put(VatRateCsvResource.COL_RATE, 1);
        header.put(VatRateCsvResource.COL_LABEL, 2);
        return new ImporterCsvResource.LineData(1, header,
                new String[]{number, rate, label}, VatRateCsvResource.COL_VAT_NUMBER);
    }

    /**
     * Builds a regime carrying the three given values.
     *
     * @param number the regime number
     * @param rate the rate as a fraction
     * @param label the label
     * @return the regime
     */
    private VatRate regime(Integer number, String rate, String label) {
        VatRate vat = new VatRate();
        vat.number = number;
        vat.rate = new BigDecimal(rate);
        vat.label = label;
        return vat;
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
        Method method = VatRateCsvResource.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    /**
     * {@code processChunkWithFallback} answers an empty map on an empty chunk
     * without querying (true arm of the guard).
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
     * and indexes them by their number as written (false arm of the guard).
     */
    @Test
    void processChunkWithFallbackIndexesTheFetchedRegimes() {
        VatRate one = regime(1, "0.2000", "Taux normal");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> VatRate.list(org.mockito.ArgumentMatchers.eq("number IN ?1"),
                    org.mockito.ArgumentMatchers.<Object>any())).thenReturn(List.of(one));
            Map<String, Object> context = resource.processChunkWithFallback(
                    List.of(line("1", "0.2000", "Taux normal")), Set.of("1"),
                    new int[]{0, 0}, new java.util.ArrayList<>());
            @SuppressWarnings("unchecked")
            Map<String, VatRate> rates = (Map<String, VatRate>) context.get(CTX_VAT_RATES);
            assertEquals(1, rates.size());
            assertSame(one, rates.get("1"));
        }
    }

    /**
     * The chunk fetch answers an empty map when the chunk names no key at all
     * (true arm of the emptiness guard).
     */
    @Test
    void chunkFetchAnswersEmptyWhenNoKey() throws Exception {
        Method method = privateMethod("vatRateMap", Set.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            Map<String, VatRate> rates = (Map<String, VatRate>) method.invoke(null, Set.of());
            assertTrue(rates.isEmpty());
            panache.verifyNoInteractions();
        }
    }

    /**
     * A key that is not an integer is left out of the query binding rather
     * than failing the whole chunk (false arm of the parse guard).
     */
    @Test
    void chunkFetchDropsTheKeysThatDoNotParse() throws Exception {
        Method method = privateMethod("numbers", Set.class);
        @SuppressWarnings("unchecked")
        List<Integer> parsed = (List<Integer>) method.invoke(null, Set.of("1", "X"));
        assertEquals(List.of(1), parsed);
    }

    /**
     * The number parser answers null on a null cell (first arm of the
     * disjunction).
     */
    @Test
    void parseNumberAnswersNullOnNullCell() throws Exception {
        Method method = privateMethod("parseNumber", String.class);
        assertNull(method.invoke(null, (String) null));
    }

    /**
     * The number parser answers null on an empty cell (second arm of the
     * disjunction).
     */
    @Test
    void parseNumberAnswersNullOnEmptyCell() throws Exception {
        Method method = privateMethod("parseNumber", String.class);
        assertNull(method.invoke(null, ""));
    }

    /**
     * The number parser answers null on a cell that is not an integer (the
     * catch arm).
     */
    @Test
    void parseNumberAnswersNullOnAMalformedCell() throws Exception {
        Method method = privateMethod("parseNumber", String.class);
        assertNull(method.invoke(null, "1,5"));
    }

    /**
     * The number parser trims and parses a well-formed cell (the nominal
     * arm).
     */
    @Test
    void parseNumberParsesAWellFormedCell() throws Exception {
        Method method = privateMethod("parseNumber", String.class);
        assertEquals(7, method.invoke(null, " 7 "));
    }

    /**
     * A line whose key is not an integer fails the line, which is what
     * triggers the rollback and the one-by-one fallback of the base class.
     */
    @Test
    void processLineLogicRefusesANonIntegerKey() {
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_VAT_RATES, new HashMap<String, VatRate>());
        assertThrows(IllegalArgumentException.class, () -> resource.processLineLogic(
                line("X", "0.2000", "Taux normal"), context, new int[]{0, 0}));
    }

    /**
     * A number the referential does not carry yet creates a regime, counted
     * as a creation and persisted (true arm of the novelty guard).
     */
    @Test
    void processLineLogicCreatesAnUnknownRegime() {
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_VAT_RATES, new HashMap<String, VatRate>());
        int[] counters = new int[]{0, 0};
        EntityManager em = mock(EntityManager.class);
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(line("2", "0.0550", "Taux réduit"), context, counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(em).persist(captor.capture());
            VatRate created = (VatRate) captor.getValue();
            assertEquals(2, created.number);
            assertEquals(new BigDecimal("0.0550"), created.rate);
            assertEquals("Taux réduit", created.label);
        }
    }

    /**
     * A known number whose checksum moved is reloaded and refed, counted as an
     * update (false arm of the novelty guard, true arm of the checksum guard).
     */
    @Test
    void processLineLogicUpdatesAChangedRegime() {
        VatRate existing = regime(1, "0.2000", "Taux normal");
        existing.id = 11L;
        existing.checksum = 0;
        Map<String, VatRate> rates = new HashMap<>();
        rates.put("1", existing);
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_VAT_RATES, rates);
        int[] counters = new int[]{0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> VatRate.findById(11L)).thenReturn(existing);
            resource.processLineLogic(line("1", "0.2100", "Taux normal"), context, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals(new BigDecimal("0.2100"), existing.rate);
        }
    }

    /**
     * A known number whose checksum matches is left untouched and counted
     * neither way (false arm of the checksum guard).
     */
    @Test
    void processLineLogicSkipsAnUnchangedRegime() {
        VatRate existing = regime(1, "0.2000", "Taux normal");
        existing.id = 11L;
        existing.checksum = existing.getChecksum();
        Map<String, VatRate> rates = new HashMap<>();
        rates.put("1", existing);
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_VAT_RATES, rates);
        int[] counters = new int[]{0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            resource.processLineLogic(line("1", "0.2000", "Taux normal"), context, counters);
            assertEquals(0, counters[0]);
            assertEquals(0, counters[1]);
            panache.verifyNoInteractions();
        }
    }

    /**
     * Without a context map — the one-by-one fallback of the base class — the
     * line looks its own regime up in the database (true arm of the map
     * guard), and finding one takes the update path.
     */
    @Test
    void processLineLogicFallsBackOnADatabaseLookup() {
        VatRate existing = regime(3, "0.1000", "Taux intermédiaire");
        existing.id = 12L;
        existing.checksum = 0;
        int[] counters = new int[]{0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<VatRate> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(existing);
            panache.when(() -> VatRate.find("number", 3)).thenReturn(query);
            panache.when(() -> VatRate.findById(12L)).thenReturn(existing);
            resource.processLineLogic(line("3", "0.1100", "Taux intermédiaire"),
                    new HashMap<>(), counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals(new BigDecimal("0.1100"), existing.rate);
        }
    }

    /**
     * The one-by-one fallback finding nothing takes the creation path (false
     * arm of the lookup guard).
     */
    @Test
    void processLineLogicFallbackCreatesWhenTheLookupIsEmpty() {
        int[] counters = new int[]{0, 0};
        EntityManager em = mock(EntityManager.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedStatic<Panache> statics = mockStatic(Panache.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<VatRate> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> VatRate.find("number", 4)).thenReturn(query);
            statics.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(line("4", "0.0210", null), new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            verify(em).persist(org.mockito.ArgumentMatchers.any(VatRate.class));
        }
    }

    /**
     * {@code findEntityForLine} resolves the line's regime through the
     * referential finder.
     */
    @Test
    void findEntityForLineResolvesThroughTheFinder() {
        VatRate existing = regime(5, "0.0000", "Exonéré");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<VatRate> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(existing);
            panache.when(() -> VatRate.find("number", 5)).thenReturn(query);
            assertSame(existing, resource.findEntityForLine(line("5", "0.0000", "Exonéré")));
        }
    }

    /**
     * The incoming checksum reproduces {@link VatRate#getChecksum()} exactly,
     * which is what makes the update-vs-skip comparison meaningful. The row
     * carries no country here, and one that does carries it into the incoming
     * hash as well — the import does not feed those two fields, so the line
     * reads them off the row it would rewrite, and a file repeating what the
     * row holds skips rather than rewrites.
     */
    @Test
    void incomingChecksumReproducesTheEntityChecksum() throws Exception {
        Method method = privateMethod("computeIncomingChecksum",
                Integer.class, ImporterCsvResource.LineData.class, VatRate.class);
        VatRate local = new VatRate();
        int incoming = (int) method.invoke(resource, 1,
                line("1", "0.2000", "Taux normal"), local);
        assertEquals(regime(1, "0.2000", "Taux normal").getChecksum(), incoming);
        VatRate ofFrance = new VatRate();
        ofFrance.country = "FRA";
        ofFrance.legalMention = "TVA 20 %";
        int national = (int) method.invoke(resource, 1,
                line("1", "0.2000", "Taux normal"), ofFrance);
        assertEquals(VatRate.checksumOf(1, new BigDecimal("0.2000"),
                "Taux normal", "FRA", "TVA 20 %"), national);
    }

    /**
     * A regime without a label hashes a null label on both sides, so a file
     * omitting the column never looks like a change.
     */
    @Test
    void incomingChecksumHandlesAMissingLabel() throws Exception {
        Method method = privateMethod("computeIncomingChecksum",
                Integer.class, ImporterCsvResource.LineData.class, VatRate.class);
        VatRate existing = new VatRate();
        int incoming = (int) method.invoke(resource, 5,
                line("5", "0.0000", null), existing);
        assertEquals(VatRate.checksumOf(5, new BigDecimal("0.0000"), null, null, null), incoming);
    }

    /**
     * The importer names the VAT_RATES engine feed: the raw file is retained
     * verbatim and delivered to the valuation engines, which attach every
     * price to the regime carrying its rate and price nothing without it.
     */
    @Test
    void importerNamesTheVatEngineFeed() {
        assertEquals("VAT_RATES", resource.feedCode());
    }

    /**
     * {@code importVatRates} logs and forwards the stream to the base importer
     * with the VAT_NUMBER key and the RATE required column; a header-only body
     * produces a zero-count JSON with a 200 status, exercising the entry
     * method's log-and-delegate body.
     */
    @Test
    void importVatRatesDelegatesToTheBaseImporter() {
        Response response = resource.importVatRates(new ByteArrayInputStream(
                "VAT_NUMBER|RATE|LABEL\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
    }
}
