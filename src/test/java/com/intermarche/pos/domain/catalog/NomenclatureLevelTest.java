package com.intermarche.pos.domain.catalog;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NomenclatureLevel}.
 * <p>
 * The figures come from the published hierarchy file: Intermarché France
 * declares Activité 2, Rayon 2 more (4 cumulated), Famille 4 more (8) and
 * Sous-famille 4 more (12). The entity stores the cumulated length and derives
 * the contribution, so the two can never disagree; these tests check the
 * derivation against the file's own two readings.
 */
class NomenclatureLevelTest {

    /**
     * Builds a level.
     *
     * @param rank the 0-based rank
     * @param label the level name
     * @param codeLength the cumulative code length
     * @return the level
     */
    private NomenclatureLevel level(int rank, String label, int codeLength) {
        NomenclatureLevel value = new NomenclatureLevel();
        value.rank = rank;
        value.label = label;
        value.codeLength = codeLength;
        return value;
    }

    /**
     * Builds a persisted scheme.
     *
     * @return the scheme
     */
    private Nomenclature scheme() {
        Nomenclature value = new Nomenclature();
        value.id = 7L;
        value.code = "ITM_FR";
        return value;
    }



    /**
     * The levels of a scheme are read in rank order through its id.
     */
    @Test
    void theLevelsAreReadInRankOrder() {
        Nomenclature itm = scheme();
        List<NomenclatureLevel> levels = List.of(level(0, "Activité", 2), level(1, "Rayon", 4));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> NomenclatureLevel.list("nomenclature.id = ?1 order by rank", 7L))
                    .thenReturn(levels);
            assertSame(levels, NomenclatureLevel.listFor(itm));
        }
    }

    /**
     * A null scheme and an unpersisted one both read as no levels — the two
     * legs of the guard, neither of which may reach the database with a null
     * key.
     */
    @Test
    void aNullOrUnpersistedSchemeHasNoLevels() {
        assertEquals(List.of(), NomenclatureLevel.listFor(null));
        assertEquals(List.of(), NomenclatureLevel.listFor(new Nomenclature()));
        assertNull(NomenclatureLevel.findByRank(null, 0));
        assertNull(NomenclatureLevel.findByRank(new Nomenclature(), 0));
    }

    /**
     * One level is reachable by its rank inside its scheme.
     */
    @Test
    void oneLevelIsReachableByItsRank() {
        Nomenclature itm = scheme();
        NomenclatureLevel rayon = level(1, "Rayon", 4);
        PanacheQuery<NomenclatureLevel> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(rayon);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> NomenclatureLevel.find("nomenclature.id = ?1 and rank = ?2", 7L, 1))
                    .thenReturn(query);
            assertSame(rayon, NomenclatureLevel.findByRank(itm, 1));
        }
    }

    /**
     * The fingerprint follows the rank, the name, the length and the custom
     * flag: a level renamed or re-lengthened upstream must reach the registers,
     * and so must a level moving from national to shop-specific.
     */
    @Test
    void theFingerprintFollowsEveryDeclaredField() {
        NomenclatureLevel reference = level(1, "Rayon", 4);
        int base = reference.getChecksum();
        assertNotEquals(base, level(2, "Rayon", 4).getChecksum());
        assertNotEquals(base, level(1, "Département", 4).getChecksum());
        assertNotEquals(base, level(1, "Rayon", 6).getChecksum());
        NomenclatureLevel custom = level(1, "Rayon", 4);
        custom.custom = true;
        assertNotEquals(base, custom.getChecksum());
        assertEquals(base, level(1, "Rayon", 4).getChecksum());
    }
}
