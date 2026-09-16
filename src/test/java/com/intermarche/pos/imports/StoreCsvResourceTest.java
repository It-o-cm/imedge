package com.intermarche.pos.imports;

import com.intermarche.pos.domain.store.Address;
import com.intermarche.pos.domain.store.Store;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StoreCsvResource}.
 * <p>
 * The resource is the {@link Store}-specific concretion of the abstract
 * {@link ImporterCsvResource}: for a chunk it bulk-fetches existing stores
 * (keyed by code) into a context map, then for each line either persists a new
 * store — initializing its embedded {@link Address} — or, on a checksum
 * mismatch, re-feeds an existing store re-read from the database. Every
 * collaborator is a Panache active-record static finder which, under plain
 * {@code mvn test}, resolves to {@link PanacheEntityBase} ({@code list} /
 * {@code find} / {@code findById}) and is intercepted with
 * {@link org.mockito.Mockito#mockStatic} inside a try-with-resources block;
 * {@code Panache.getEntityManager()} is stubbed the same way so {@code persist}
 * is observable. The private {@code computeIncomingChecksum} helper is reached
 * by reflection so the update-vs-skip checksum arms carry byte-exact expected
 * values. No database and no Quarkus context is booted, every entity field is
 * set directly, and every branch is asserted against absolute expected values.
 */
class StoreCsvResourceTest {

    /** The exact JPQL fragment issued by the bulk pre-fetch. */
    private static final String CODE_QUERY = "code IN ?1";
    /** The exact JPQL fragment issued by {@code findEntityForLine}. */
    private static final String FIND_QUERY = "code";

    /**
     * Wraps a string as a UTF-8 input stream.
     *
     * @param content the CSV content
     * @return the input stream
     */
    private InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Builds a 9-column store CSV row (code, name, 5 address fields, lat, long).
     *
     * @param code        the store code (column 0)
     * @param name        the store name (column 1)
     * @param streetLine1 the first street line (column 2)
     * @param streetLine2 the second street line (column 3)
     * @param postalCode  the postal code (column 4)
     * @param city        the city (column 5)
     * @param country     the country (column 6)
     * @param latitude    the GPS latitude (column 7)
     * @param longitude   the GPS longitude (column 8)
     * @return a fresh parts array of the nine columns
     */
    private String[] parts(String code, String name, String streetLine1, String streetLine2,
                           String postalCode, String city, String country, String latitude, String longitude) {
        return new String[]{code, name, streetLine1, streetLine2, postalCode, city, country, latitude, longitude};
    }

    /**
     * Builds a {@link Store} carrying the given code.
     *
     * @param code the store code
     * @return the store
     */
    private Store store(String code) {
        Store s = new Store();
        s.code = code;
        return s;
    }

    /**
     * Invokes the private {@code computeIncomingChecksum} helper by reflection.
     *
     * @param resource the resource under test
     * @param data     the parsed line
     * @return the checksum of the incoming CSV data
     * @throws Exception on reflection failure
     */
    private static int incomingChecksum(StoreCsvResource resource, ImporterCsvResource.LineData data) throws Exception {
        Method method = StoreCsvResource.class.getDeclaredMethod("computeIncomingChecksum", ImporterCsvResource.LineData.class);
        method.setAccessible(true);
        return (Integer) method.invoke(resource, data);
    }

    /**
     * {@code importStores} forwards the stream to the base importer with the
     * 7-column contract; a header-only body produces a zero-count JSON with a
     * 200 status.
     */
    @Test
    void importStoresDelegatesToBaseImporterWithSevenColumns() {
        StoreCsvResource resource = new StoreCsvResource();
        Response response = resource.importStores(stream("CODE|NAME|STREET_LINE1|STREET_LINE2|POSTAL_CODE|CITY|COUNTRY\n"));
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
    }

    /**
     * {@code processChunkWithFallback} short-circuits to an empty map when the
     * chunk holds no lines (the {@code parsedLines.isEmpty} true arm), never
     * touching the database.
     */
    @Test
    void processChunkWithFallbackReturnsEmptyMapForEmptyChunk() {
        StoreCsvResource resource = new StoreCsvResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            Map<String, Object> context = resource.processChunkWithFallback(new ArrayList<>(), new HashSet<>(), new int[]{0, 0}, new ArrayList<>());
            assertTrue(context.isEmpty());
            panache.verifyNoInteractions();
        }
    }

    /**
     * {@code processChunkWithFallback} bulk-fetches and indexes existing stores
     * for a populated chunk (parsedLines non-empty arm, fetch loop entered).
     */
    @Test
    void processChunkWithFallbackIndexesExistingStores() {
        StoreCsvResource resource = new StoreCsvResource();
        List<ImporterCsvResource.LineData> lines = new ArrayList<>();
        lines.add(line(2, parts("S1", "Lyon", "", "", "", "", "", "", "")));
        Set<String> targetCodes = new HashSet<>();
        targetCodes.add("S1");
        Store s1 = store("S1");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Store.list(CODE_QUERY, targetCodes)).thenReturn(List.of(s1));
            Map<String, Object> context = resource.processChunkWithFallback(lines, targetCodes, new int[]{0, 0}, new ArrayList<>());
            assertEquals(1, context.size());
            assertSame(s1, context.get("S1"));
        }
    }

    /**
     * {@code processChunkWithFallback} returns an empty map for a populated
     * chunk whose codes match no stored row (parsedLines non-empty arm, fetch
     * loop NOT entered).
     */
    @Test
    void processChunkWithFallbackReturnsEmptyMapWhenNoStoreMatches() {
        StoreCsvResource resource = new StoreCsvResource();
        List<ImporterCsvResource.LineData> lines = new ArrayList<>();
        lines.add(line(2, parts("S9", "Ghost", "", "", "", "", "", "", "")));
        Set<String> targetCodes = new HashSet<>();
        targetCodes.add("S9");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Store.list(CODE_QUERY, targetCodes)).thenReturn(List.of());
            Map<String, Object> context = resource.processChunkWithFallback(lines, targetCodes, new int[]{0, 0}, new ArrayList<>());
            assertTrue(context.isEmpty());
        }
    }

    /**
     * {@code processLineLogic} creates a fresh store when the context lacks the
     * line's code ({@code store == null} true arm): {@code feedStore}
     * initializes the embedded address ({@code address == null} true arm) from
     * the CSV row, the created counter is incremented and the store is persisted
     * through the entity manager.
     */
    @Test
    void processLineLogicCreatesStoreAndInitializesAddress() {
        StoreCsvResource resource = new StoreCsvResource();
        ImporterCsvResource.LineData data = line(2, parts("S1", "Lyon Centre", "10 rue A", "Bat B", "69000", "Lyon", "France", "45.5", "4.8"));
        Map<String, Object> context = new HashMap<>();
        int[] counters = {0, 0};
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(data, context, counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            ArgumentCaptor<Store> captor = ArgumentCaptor.forClass(Store.class);
            verify(em).persist(captor.capture());
            Store persisted = captor.getValue();
            assertEquals("S1", persisted.code);
            assertEquals("Lyon Centre", persisted.name);
            assertNotNull(persisted.address);
            assertEquals("10 rue A", persisted.address.streetLine1);
            assertEquals("Bat B", persisted.address.streetLine2);
            assertEquals("69000", persisted.address.postalCode);
            assertEquals("Lyon", persisted.address.city);
            assertEquals("France", persisted.address.country);
            assertEquals(45.5, persisted.address.latitude);
            assertEquals(4.8, persisted.address.longitude);
        }
    }

    /**
     * {@code processLineLogic} updates an existing store when the freshly re-read
     * row's checksum differs from the incoming one ({@code store == null} false
     * arm, {@code checksum != incoming} true arm). The re-read store already
     * carries an address, so {@code feedStore} reuses it ({@code address == null}
     * false arm); the updated counter is incremented and no persist call is made.
     */
    @Test
    void processLineLogicUpdatesExistingStoreWhenChecksumDiffers() throws Exception {
        StoreCsvResource resource = new StoreCsvResource();
        ImporterCsvResource.LineData data = line(2, parts("S1", "New Name", "1 street", "", "75000", "Paris", "France", "48.8", "2.3"));
        Store mapped = store("S1");
        mapped.id = 5L;
        Store fresh = store("S1");
        fresh.id = 5L;
        fresh.name = "Old Name";
        fresh.address = new Address();
        fresh.address.city = "Marseille";
        fresh.checksum = incomingChecksum(resource, data) + 1;
        Address existingAddress = fresh.address;
        Map<String, Object> context = new HashMap<>();
        context.put("S1", mapped);
        int[] counters = {0, 0};
        try (MockedStatic<Panache> panache = mockStatic(Panache.class);
             MockedStatic<PanacheEntityBase> base = mockStatic(PanacheEntityBase.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            base.when(() -> Store.findById(5L)).thenReturn(fresh);
            resource.processLineLogic(data, context, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals("New Name", fresh.name);
            assertSame(existingAddress, fresh.address);
            assertEquals("1 street", fresh.address.streetLine1);
            assertEquals("Paris", fresh.address.city);
            assertEquals(48.8, fresh.address.latitude);
            assertEquals(2.3, fresh.address.longitude);
            verifyNoInteractions(em);
        }
    }

    /**
     * {@code processLineLogic} leaves an existing store untouched when the
     * re-read row already matches the incoming checksum ({@code store == null}
     * false arm, {@code checksum != incoming} false arm): the counters stay at
     * zero and no field is overwritten.
     */
    @Test
    void processLineLogicSkipsUpdateWhenChecksumMatches() throws Exception {
        StoreCsvResource resource = new StoreCsvResource();
        ImporterCsvResource.LineData data = line(2, parts("S1", "New Name", "1 street", "", "75000", "Paris", "France", "48.8", "2.3"));
        Store mapped = store("S1");
        mapped.id = 7L;
        Store fresh = store("S1");
        fresh.id = 7L;
        fresh.name = "Untouched";
        fresh.checksum = incomingChecksum(resource, data);
        Map<String, Object> context = new HashMap<>();
        context.put("S1", mapped);
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> Store.findById(7L)).thenReturn(fresh);
            resource.processLineLogic(data, context, counters);
            assertEquals(0, counters[0]);
            assertEquals(0, counters[1]);
            assertEquals("Untouched", fresh.name);
            assertFalse(context.isEmpty());
        }
    }

    /**
     * {@code findEntityForLine} performs the code lookup used by the 1-by-1
     * fallback and returns the first matching store.
     */
    @Test
    void findEntityForLineLooksUpStoreByCode() {
        StoreCsvResource resource = new StoreCsvResource();
        ImporterCsvResource.LineData data = line(2, parts("S1", "Lyon", "", "", "", "", "", "", ""));
        Store s1 = store("S1");
        @SuppressWarnings("unchecked")
        PanacheQuery<Store> query = mock(PanacheQuery.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Store.find(FIND_QUERY, "S1")).thenReturn(query);
            when(query.firstResult()).thenReturn(s1);
            assertSame(s1, resource.findEntityForLine(data));
        }
    }
    /** Header names of the imported feed, in the cell order of the fixtures. */
    private static final String[] TEST_HEADER = {"CODE", "NAME", "STREET_LINE1", "STREET_LINE2", "POSTAL_CODE", "CITY", "COUNTRY", "LATITUDE", "LONGITUDE"};

    /**
     * Builds a header-bound row from positional fixture cells: the header
     * maps TEST_HEADER onto the cell positions and the first name is the
     * key column.
     *
     * @param lineNumber the 1-based line number
     * @param cells the raw cells of the row
     * @return the header-bound line
     */
    private static ImporterCsvResource.LineData line(int lineNumber, String[] cells) {
        java.util.Map<String, Integer> header = new java.util.LinkedHashMap<>();
        for (int i = 0; i < TEST_HEADER.length; i++) header.put(TEST_HEADER[i], i);
        return new ImporterCsvResource.LineData(lineNumber, header, cells, TEST_HEADER[0]);
    }

}
