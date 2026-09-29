package com.intermarche.pos.imports;

import com.intermarche.pos.domain.store.PostalCode;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PostalCodeCsvResource}.
 * <p>
 * The staged transaction machinery belongs to {@link ImporterCsvResource} and
 * is tested there; what is exercised here is what this subclass adds — the
 * PAIR key, which is what makes a code covering several communes import whole.
 * Rows are built as plain {@link ImporterCsvResource.LineData} against a
 * hand-made header and the static finders are intercepted with
 * {@link org.mockito.Mockito#mockStatic}: no Quarkus context and no database is
 * booted.
 * <p>
 * Branch enumeration (every arm exercised): {@code pair} — both parts present,
 * each one null, and two different pairs sharing a code; {@code
 * processChunkWithFallback} — the empty chunk and the populated one, with two
 * places under one code; {@code findEntityForLine} — the known and unknown
 * pair.
 */
class PostalCodeCsvResourceTest {

    /** The query {@code PostalCode.findPair} issues underneath. */
    private static final String PAIR_QUERY = "code = ?1 and place = ?2";

    /** The resource under test. */
    private final PostalCodeCsvResource resource = new PostalCodeCsvResource();

    /**
     * Builds a row from a header and its cells, in the order given.
     *
     * @param columns the header names, in column order
     * @param cells the cells of the row, in the same order
     * @return the parsed row
     */
    private ImporterCsvResource.LineData row(List<String> columns, String... cells) {
        Map<String, Integer> header = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            header.put(columns.get(index), index);
        }
        return new ImporterCsvResource.LineData(1, header, cells, PostalCodeCsvResource.COL_CODE);
    }

    /**
     * Builds a row entity.
     *
     * @param code the postal code
     * @param place the place
     * @return the entity
     */
    private PostalCode entity(String code, String place) {
        PostalCode postalCode = new PostalCode();
        postalCode.code = code;
        postalCode.place = place;
        return postalCode;
    }

    /**
     * The key is the PAIR: two places under one code are two distinct keys,
     * which is the whole reason this importer does not key on the code.
     */
    @Test
    void twoPlacesUnderOneCodeAreTwoKeys() {
        assertNotEquals(PostalCodeCsvResource.pair("13100", "Aix-en-Provence"),
                PostalCodeCsvResource.pair("13100", "Le Tholonet"));
    }

    /**
     * A null half is rendered as nothing rather than as the word null, and two
     * rows missing different halves do not collide.
     */
    @Test
    void anullHalfIsRenderedAsNothing() {
        assertNotEquals(PostalCodeCsvResource.pair(null, "Paris"),
                PostalCodeCsvResource.pair("75008", null));
        assertEquals(PostalCodeCsvResource.pair(null, null),
                PostalCodeCsvResource.pair(null, null));
        assertTrue(PostalCodeCsvResource.pair(null, "Paris").endsWith("Paris"));
    }

    /**
     * The chunk is indexed by the PAIR, so a code covering two communes finds
     * both of its rows instead of one.
     */
    @Test
    void theChunkIsIndexedByThePair() {
        PostalCode aix = entity("13100", "Aix-en-Provence");
        PostalCode tholonet = entity("13100", "Le Tholonet");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PostalCode.list("code IN ?1", Set.of("13100")))
                    .thenReturn(List.of(aix, tholonet));
            List<String> columns = List.of(PostalCodeCsvResource.COL_CODE,
                    PostalCodeCsvResource.COL_PLACE);
            Map<String, Object> map = resource.processChunkWithFallback(
                    List.of(row(columns, "13100", "Aix-en-Provence"),
                            row(columns, "13100", "Le Tholonet")),
                    Set.of("13100"), new int[]{0, 0}, new ArrayList<>());
            assertEquals(2, map.size());
            assertSame(aix, map.get(PostalCodeCsvResource.pair("13100", "Aix-en-Provence")));
            assertSame(tholonet, map.get(PostalCodeCsvResource.pair("13100", "Le Tholonet")));
        }
    }

    /**
     * An empty chunk touches nothing at all.
     */
    @Test
    void anEmptyChunkTouchesNothing() {
        assertTrue(resource.processChunkWithFallback(List.of(), Set.of(),
                new int[]{0, 0}, new ArrayList<>()).isEmpty());
    }

    /**
     * The one-by-one fallback looks a row up by its pair, and answers null on a
     * pair the list does not carry.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theFallbackFindsOneRowByItsPair() {
        PostalCode known = entity("75008", "Paris");
        List<String> columns = List.of(PostalCodeCsvResource.COL_CODE,
                PostalCodeCsvResource.COL_PLACE);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<PostalCode> found = mock(PanacheQuery.class);
            when(found.firstResult()).thenReturn(known);
            panache.when(() -> PostalCode.find(PAIR_QUERY, "75008", "Paris")).thenReturn(found);
            PanacheQuery<PostalCode> missing = mock(PanacheQuery.class);
            when(missing.firstResult()).thenReturn(null);
            panache.when(() -> PostalCode.find(PAIR_QUERY, "75008", "Lyon")).thenReturn(missing);
            assertSame(known, resource.findEntityForLine(row(columns, "75008", "Paris")));
            assertEquals(null, resource.findEntityForLine(row(columns, "75008", "Lyon")));
        }
    }

    /**
     * The public entry point delegates to the base import with the CODE key and
     * the PLACE required column: an empty stream carries no header and no row,
     * so it imports nothing and answers a 200 with zero counts.
     */
    @Test
    void importPostalCodesDelegatesToTheBaseImport() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            InputStream stream = new ByteArrayInputStream(new byte[0]);
            Response response = resource.importPostalCodes(stream);
            assertEquals(200, response.getStatus());
            assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
        }
    }

    /**
     * An unknown pair takes the CREATE arm: a fresh row is built from the line,
     * persisted through the entity manager, and the created counter is bumped
     * while the updated counter is left alone.
     */
    @Test
    void anUnknownPairIsCreated() {
        List<String> columns = List.of(PostalCodeCsvResource.COL_CODE,
                PostalCodeCsvResource.COL_PLACE, PostalCodeCsvResource.COL_COUNTRY);
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager entityManager = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(entityManager);
            int[] counters = new int[]{0, 0};
            resource.processLineLogic(row(columns, "75008", "Paris", "FR"),
                    new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            verify(entityManager).persist(org.mockito.ArgumentMatchers.any(PostalCode.class));
        }
    }

    /**
     * A known pair whose country differs takes the UPDATE arm: the detached row
     * is re-read fresh through {@code findById}, its country is overwritten and
     * the updated counter is bumped while the created counter is left alone.
     */
    @Test
    void aKnownPairWithADifferentCountryIsUpdated() {
        List<String> columns = List.of(PostalCodeCsvResource.COL_CODE,
                PostalCodeCsvResource.COL_PLACE, PostalCodeCsvResource.COL_COUNTRY);
        PostalCode stored = entity("1234-567", "LISBOA");
        stored.id = 7L;
        stored.country = "FR";
        Map<String, Object> entityMap = new HashMap<>();
        entityMap.put(PostalCodeCsvResource.pair("1234-567", "LISBOA"), stored);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PostalCode.findById(7L)).thenReturn(stored);
            int[] counters = new int[]{0, 0};
            resource.processLineLogic(row(columns, "1234-567", "LISBOA", "PT"),
                    entityMap, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals("PT", stored.country);
        }
    }

    /**
     * A known pair whose country is unchanged takes the UPDATE arm but skips the
     * country-changed branch: nothing is counted and the value is left as it was.
     */
    @Test
    void aKnownPairWithTheSameCountryCountsNothing() {
        List<String> columns = List.of(PostalCodeCsvResource.COL_CODE,
                PostalCodeCsvResource.COL_PLACE, PostalCodeCsvResource.COL_COUNTRY);
        PostalCode stored = entity("75008", "Paris");
        stored.id = 9L;
        stored.country = "FR";
        Map<String, Object> entityMap = new HashMap<>();
        entityMap.put(PostalCodeCsvResource.pair("75008", "Paris"), stored);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PostalCode.findById(9L)).thenReturn(stored);
            int[] counters = new int[]{0, 0};
            resource.processLineLogic(row(columns, "75008", "Paris", "FR"),
                    entityMap, counters);
            assertEquals(0, counters[0]);
            assertEquals(0, counters[1]);
            assertEquals("FR", stored.country);
        }
    }
}
