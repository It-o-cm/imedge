package com.intermarche.pos.imports;

import com.intermarche.pos.domain.store.PostalCode;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
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
}
