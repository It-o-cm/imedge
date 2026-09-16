package com.intermarche.pos.domain.catalog;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link Nomenclature}.
 * <p>
 * The fixtures are the REAL schemes, taken from the hierarchy file the group
 * publishes: Intermarché France 2 / 4 / 8 / 12 cumulated characters, and
 * Bricomarché France 3 / 6 / 8 / 12. Both are used deliberately — a test
 * written on one scheme only would pass on code that hard-codes its lengths,
 * which is exactly the defect the entity exists to prevent.
 * <p>
 * The level table is reached through a Panache static and intercepted with
 * {@link org.mockito.Mockito#mockStatic}.
 */
class NomenclatureTest {

    /**
     * Builds a level.
     *
     * @param rank the 0-based rank
     * @param label the level name
     * @param codeLength the cumulative code length at this level
     * @param custom whether a point of sale may redefine it
     * @return the level
     */
    private NomenclatureLevel level(int rank, String label, int codeLength, boolean custom) {
        NomenclatureLevel value = new NomenclatureLevel();
        value.rank = rank;
        value.label = label;
        value.codeLength = codeLength;
        value.custom = custom;
        return value;
    }

    /**
     * Builds a scheme carrying an id, so the level finder can be stubbed on it.
     *
     * @param code the scheme code
     * @return the scheme
     */
    private Nomenclature scheme(String code) {
        Nomenclature value = new Nomenclature();
        value.id = 1L;
        value.code = code;
        value.label = code;
        return value;
    }

    /** The Intermarché France levels, exactly as the published file states them. */
    private List<NomenclatureLevel> intermarcheLevels() {
        return List.of(level(0, "Activité", 2, false), level(1, "Rayon", 4, false),
                level(2, "Famille", 8, false), level(3, "Sous-famille", 12, true));
    }

    /** The Bricomarché France levels, whose lengths differ at every rank. */
    private List<NomenclatureLevel> bricomarcheLevels() {
        return List.of(level(0, "Rayon", 3, false), level(1, "Famille", 6, false),
                level(2, "Sous-famille", 8, true), level(3, "Segment", 12, true));
    }

    /**
     * Opens a static mock serving the given levels for any scheme.
     *
     * @param levels the levels to serve
     * @return the active static mock, to be closed by the caller
     */
    private MockedStatic<PanacheEntityBase> withLevels(List<NomenclatureLevel> levels) {
        MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
        mocked.when(() -> NomenclatureLevel.list("nomenclature.id = ?1 order by rank", 1L))
                .thenReturn(levels);
        return mocked;
    }

    /**
     * A code is placed at the level whose CUMULATIVE length it has. All four
     * Intermarché levels are exercised, on the real codes of the file.
     */
    @Test
    void aCodeIsPlacedByItsLength() {
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = withLevels(intermarcheLevels())) {
            assertEquals("Activité", itm.levelOfCode("10").label);
            assertEquals("Rayon", itm.levelOfCode("1002").label);
            assertEquals("Famille", itm.levelOfCode("10020200").label);
            assertEquals("Sous-famille", itm.levelOfCode("100202000001").label);
        }
    }

    /**
     * The same reading on the OTHER scheme lands on different levels for the
     * same lengths — the proof that nothing assumes 2/4/8/12.
     */
    @Test
    void anotherSchemePlacesTheSameLengthsDifferently() {
        Nomenclature brico = scheme("BRICO_FR");
        try (MockedStatic<PanacheEntityBase> mocked = withLevels(bricomarcheLevels())) {
            assertEquals("Rayon", brico.levelOfCode("100").label);
            assertEquals("Famille", brico.levelOfCode("100200").label);
            assertEquals("Sous-famille", brico.levelOfCode("10020001").label);
            assertEquals("Segment", brico.levelOfCode("100200010001").label);
            assertNull(brico.levelOfCode("1002"));
        }
    }


    /**
     * The parent is the code truncated to the level above — the one property
     * the whole model rests on, checked at every depth of the real scheme.
     */
    @Test
    void theParentIsTheCodeTruncatedToTheLevelAbove() {
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = withLevels(intermarcheLevels())) {
            assertEquals("10", itm.parentCodeOf("1002"));
            assertEquals("1002", itm.parentCodeOf("10020200"));
            assertEquals("10020200", itm.parentCodeOf("100202000001"));
        }
    }

    /**
     * A top node has no parent, and neither has a code the scheme cannot
     * place: truncating it would invent an ancestry.
     */
    @Test
    void aTopNodeAndAnUnplaceableCodeHaveNoParent() {
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = withLevels(intermarcheLevels())) {
            assertNull(itm.parentCodeOf("10"));
            assertNull(itm.parentCodeOf("100"));
            assertNull(itm.parentCodeOf(null));
        }
    }

    /**
     * A scheme whose level above is missing yields no parent rather than a
     * truncation to a length nobody declared — the gap arm of the walk up.
     */
    @Test
    void aMissingLevelAboveYieldsNoParent() {
        Nomenclature itm = scheme("ITM_FR");
        List<NomenclatureLevel> holed = List.of(level(0, "Activité", 2, false),
                level(2, "Famille", 8, false));
        try (MockedStatic<PanacheEntityBase> mocked = withLevels(holed)) {
            assertNull(itm.parentCodeOf("10020200"));
        }
    }

    /**
     * A level above declared no shorter than the code itself yields no parent:
     * a scheme whose lengths do not grow is broken, and the answer is nothing,
     * not an empty string or the code itself.
     */
    @Test
    void aLevelAboveThatIsNotShorterYieldsNoParent() {
        Nomenclature itm = scheme("ITM_FR");
        List<NomenclatureLevel> flat = List.of(level(0, "Activité", 4, false),
                level(1, "Rayon", 4, false));
        try (MockedStatic<PanacheEntityBase> mocked = withLevels(flat)) {
            assertNull(itm.parentCodeOf("1002"));
        }
    }

    /**
     * The rank lookup answers the declared ranks and nothing beyond, and the
     * depth is the number of levels.
     */
    @Test
    void theRanksAndTheDepthAreThoseDeclared() {
        Nomenclature itm = scheme("ITM_FR");
        try (MockedStatic<PanacheEntityBase> mocked = withLevels(intermarcheLevels())) {
            assertEquals("Activité", itm.levelAt(0).label);
            assertEquals("Sous-famille", itm.levelAt(3).label);
            assertNull(itm.levelAt(4));
            assertNull(itm.levelAt(-1));
            assertEquals(4, itm.depth());
        }
    }


    /**
     * The levels are read ONCE: the second call returns the same list without
     * going back to the database, which is what makes an import of two and a
     * half thousand nodes bearable. {@code reloadLevels} takes that back.
     */
    @Test
    void theLevelsAreReadOnceAndCanBeForgotten() {
        Nomenclature itm = scheme("ITM_FR");
        List<NomenclatureLevel> first = intermarcheLevels();
        List<NomenclatureLevel> second = bricomarcheLevels();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> NomenclatureLevel.list("nomenclature.id = ?1 order by rank", 1L))
                    .thenReturn(first, second);
            assertSame(itm.levels(), itm.levels());
            assertEquals("Activité", itm.levelAt(0).label);
            itm.reloadLevels();
            assertEquals("Rayon", itm.levelAt(0).label);
        }
    }

    /**
     * A scheme that has never been persisted has no levels to read — the
     * null-id arm of the finder, which a hand-built instance would otherwise
     * hit as a query on a null key.
     */
    @Test
    void anUnpersistedSchemeHasNoLevels() {
        Nomenclature fresh = new Nomenclature();
        fresh.code = "NEW";
        assertEquals(List.of(), fresh.levels());
        assertEquals(0, fresh.depth());
        assertEquals(List.of(), NomenclatureLevel.listFor(null));
    }

    /**
     * The fingerprint follows every published field, so a scheme renamed,
     * re-tagged or deactivated upstream reaches the registers.
     */
    @Test
    void theFingerprintFollowsEveryPublishedField() {
        Nomenclature base = scheme("ITM_FR");
        base.enseigneCode = "ITM";
        int reference = base.getChecksum();
        Nomenclature renamed = scheme("ITM_FR");
        renamed.enseigneCode = "ITM";
        renamed.label = "Autre";
        assertNotEquals(reference, renamed.getChecksum());
        Nomenclature moved = scheme("ITM_FR");
        moved.enseigneCode = "BRICO";
        assertNotEquals(reference, moved.getChecksum());
        Nomenclature stopped = scheme("ITM_FR");
        stopped.enseigneCode = "ITM";
        stopped.active = false;
        assertNotEquals(reference, stopped.getChecksum());
        Nomenclature other = scheme("BRICO_FR");
        other.enseigneCode = "ITM";
        assertNotEquals(reference, other.getChecksum());
    }
}
