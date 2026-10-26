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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
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
                "Taux normal", "FRA", "TVA 20 %", false, null), national);
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
        assertEquals(VatRate.checksumOf(5, new BigDecimal("0.0000"), null, null, null, false, null), incoming);
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
        assertEquals("{\"createdCount\":0,\"updatedCount\":0}", response.getEntity());
    }

    /**
     * The public entry point delegates to the base import with the VAT-NUMBER key
     * and the required columns: an empty stream carries no header and no row, so
     * it imports nothing and answers a 200 with zero counts.
     *
     * <p>One line, and still the only door the VAT feed goes through. What it
     * hands the base import — which column is the key, which ones are required —
     * is a contract: swapping the two arguments compiles and breaks every import
     * of the rates the whole ticket is computed with.
     */
    @Test
    void importVatRatesDelegatesToTheBaseImport() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            java.io.InputStream stream = new java.io.ByteArrayInputStream(new byte[0]);
            jakarta.ws.rs.core.Response response = resource.importVatRates(stream);
            assertEquals(200, response.getStatus());
            assertEquals("{\"createdCount\":0,\"updatedCount\":0}", response.getEntity());
        }
    }

    /**
     * Builds a parsed line over the full header of the country-aware feed
     * (BO-02-02-01): number, rate, label, country, legal mention, exemption
     * flag and exemption code.
     *
     * @param cells the seven cells, in that order
     * @return the parsed line
     */
    private ImporterCsvResource.LineData fullLine(String... cells) {
        Map<String, Integer> header = new HashMap<>();
        header.put(VatRateCsvResource.COL_VAT_NUMBER, 0);
        header.put(VatRateCsvResource.COL_RATE, 1);
        header.put(VatRateCsvResource.COL_LABEL, 2);
        header.put(VatRateCsvResource.COL_COUNTRY, 3);
        header.put(VatRateCsvResource.COL_LEGAL_MENTION, 4);
        header.put(VatRateCsvResource.COL_EXEMPT, 5);
        header.put(VatRateCsvResource.COL_EXEMPTION_CODE, 6);
        return new ImporterCsvResource.LineData(1, header, cells, VatRateCsvResource.COL_VAT_NUMBER);
    }

    /**
     * A line of the full feed creates a Portuguese exemption with its country,
     * its mention, its flag and its code (BO-02-02-01), the country normalised
     * to upper case.
     */
    @Test
    void processLineLogicFeedsTheCountryAndTheExemption() {
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_VAT_RATES, new HashMap<String, VatRate>());
        int[] counters = new int[]{0, 0};
        EntityManager em = mock(EntityManager.class);
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(fullLine("10", "0", "Isento", "pt", "Isento artigo 9.º do CIVA", "S", "M07"),
                    context, counters);
            org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(em).persist(captor.capture());
            VatRate created = (VatRate) captor.getValue();
            assertEquals("PT", created.country);
            assertEquals("Isento artigo 9.º do CIVA", created.legalMention);
            assertTrue(created.exempt);
            assertEquals("M07", created.exemptionCode);
        }
    }

    /**
     * {@code optional} keeps the held value when the header lacks the column,
     * reads an empty cell as null and a filled one as itself.
     *
     * @throws Exception when reflection fails
     */
    @Test
    void optionalReadsAbsentEmptyAndFilledColumns() throws Exception {
        Method method = privateMethod("optional", ImporterCsvResource.LineData.class, String.class, String.class);
        assertEquals("held", method.invoke(resource, line("1", "0.2", "x"),
                VatRateCsvResource.COL_LEGAL_MENTION, "held"));
        assertNull(method.invoke(resource, fullLine("1", "0.2", "x", "FR", "", "", ""),
                VatRateCsvResource.COL_LEGAL_MENTION, "held"));
        assertEquals("TVA", method.invoke(resource, fullLine("1", "0.2", "x", "FR", "TVA", "", ""),
                VatRateCsvResource.COL_LEGAL_MENTION, "held"));
        assertNull(method.invoke(resource, new ImporterCsvResource.LineData(1,
                Map.of(VatRateCsvResource.COL_VAT_NUMBER, 0, VatRateCsvResource.COL_LEGAL_MENTION, 5),
                new String[]{"1"}, VatRateCsvResource.COL_VAT_NUMBER), VatRateCsvResource.COL_LEGAL_MENTION, "held"));
    }

    /**
     * {@code country} keeps a null held country when the column is absent,
     * raises the case and cuts a too long code to three characters.
     *
     * @throws Exception when reflection fails
     */
    @Test
    void countryNormalisesTheCell() throws Exception {
        Method method = privateMethod("country", ImporterCsvResource.LineData.class, String.class);
        assertNull(method.invoke(resource, line("1", "0.2", "x"), null));
        assertEquals("BEL", method.invoke(resource, line("1", "0.2", "x"), "BEL"));
        assertEquals("BE", method.invoke(resource, fullLine("1", "0.2", "x", " be ", "", "", ""), null));
        assertEquals("POR", method.invoke(resource, fullLine("1", "0.2", "x", "PORT", "", "", ""), null));
    }

    /**
     * {@code exempt} keeps the held flag when the column is absent, reads the
     * five affirmative spellings as true and anything else as false.
     *
     * @throws Exception when reflection fails
     */
    @Test
    void exemptReadsTheAffirmativeSpellings() throws Exception {
        Method method = privateMethod("exempt", ImporterCsvResource.LineData.class, boolean.class);
        assertEquals(true, method.invoke(resource, line("1", "0", "x"), true));
        for (String yes : List.of("true", "1", "o", "Y", "s")) {
            assertEquals(true, method.invoke(resource, fullLine("1", "0", "x", "", "", yes, ""), false), yes);
        }
        assertEquals(false, method.invoke(resource, fullLine("1", "0", "x", "", "", "non", ""), true));
        assertEquals(false, method.invoke(resource, new ImporterCsvResource.LineData(1,
                Map.of(VatRateCsvResource.COL_VAT_NUMBER, 0, VatRateCsvResource.COL_EXEMPT, 5),
                new String[]{"1"}, VatRateCsvResource.COL_VAT_NUMBER), true));
    }

    /**
     * The EXEMPT column poses the exemption and the incoming checksum reads it;
     * without the column the row's own flag and code are kept and hashed
     * (BO-02-03-26/27 with BO-02-02-01).
     *
     * @throws Exception when reflection fails
     */
    @Test
    void theExemptColumnPosesTheExemption() throws Exception {
        Method feed = privateMethod("feedVatRate", ImporterCsvResource.LineData.class, VatRate.class);
        VatRate regime = new VatRate();
        feed.invoke(resource, fullLine("9", "0.0000", "Exonéré", "", "", "true", ""), regime);
        assertTrue(regime.exempt);
        VatRate kept = new VatRate();
        kept.exempt = true;
        kept.exemptionCode = "M07";
        feed.invoke(resource, line("9", "0.0000", "Exonéré"), kept);
        assertTrue(kept.exempt);
        assertEquals("M07", kept.exemptionCode);
        Method checksum = privateMethod("computeIncomingChecksum",
                Integer.class, ImporterCsvResource.LineData.class, VatRate.class);
        VatRate existing = new VatRate();
        assertEquals(VatRate.checksumOf(9, new BigDecimal("0.0000"), "Exonéré", null, null, true, null),
                (int) checksum.invoke(resource, 9, fullLine("9", "0.0000", "Exonéré", "", "", "true", ""), existing));
        existing.exempt = true;
        existing.exemptionCode = "M07";
        assertEquals(VatRate.checksumOf(9, new BigDecimal("0.0000"), "Exonéré", null, null, true, "M07"),
                (int) checksum.invoke(resource, 9, line("9", "0.0000", "Exonéré"), existing));
    }
}
