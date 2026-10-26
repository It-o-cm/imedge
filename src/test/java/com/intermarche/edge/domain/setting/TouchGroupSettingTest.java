package com.intermarche.edge.domain.setting;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TouchGroupSetting}, the touch configuration of an
 * article group now that it no longer rides on the group itself.
 * <p>
 * The Panache statics are intercepted with
 * {@link org.mockito.Mockito#mockStatic}; the rest is pure logic. Branch
 * enumeration: {@code orDefaults} has a null map, a missing key and a present
 * key, and {@code defaults} is what every unconfigured group falls back to.
 */
class TouchGroupSettingTest {

    /**
     * Builds an administered row.
     *
     * @param familyCode the group code
     * @param pinned whether the group is pinned
     * @return the row
     */
    private TouchGroupSetting row(String familyCode, boolean pinned) {
        TouchGroupSetting setting = new TouchGroupSetting();
        setting.familyCode = familyCode;
        setting.pinned = pinned;
        return setting;
    }

    /**
     * An unconfigured group draws exactly as it drew before the split: not
     * pinned, normal size, rank zero, no volume. This is the fallback the whole
     * design rests on — a nomenclature of forty thousand families carries no
     * touch row at all until someone configures one.
     */
    @Test
    void theDefaultsAreHowAnUnconfiguredGroupDraws() {
        TouchGroupSetting setting = TouchGroupSetting.defaults("RAYON");
        assertEquals("RAYON", setting.familyCode);
        assertFalse(setting.pinned);
        assertEquals("NORMAL", setting.buttonSize);
        assertEquals(TouchGroupSetting.DEFAULT_BUTTON_SIZE, setting.buttonSize);
        assertEquals(0, setting.displayOrder);
        assertEquals(0L, setting.salesVolume);
        assertNull(setting.id);
    }

    /**
     * The null-map leg of {@code orDefaults}: a caller that has not loaded the
     * configuration at all still gets a usable row rather than an exception.
     */
    @Test
    void aNullConfigurationYieldsTheDefaults() {
        TouchGroupSetting setting = TouchGroupSetting.orDefaults(null, "RAYON");
        assertEquals("RAYON", setting.familyCode);
        assertFalse(setting.pinned);
    }

    /**
     * The missing-key leg: the map is there, the group is not in it.
     */
    @Test
    void anUnknownGroupYieldsTheDefaults() {
        Map<String, TouchGroupSetting> byCode = new HashMap<>();
        byCode.put("AUTRE", row("AUTRE", true));
        TouchGroupSetting setting = TouchGroupSetting.orDefaults(byCode, "RAYON");
        assertEquals("RAYON", setting.familyCode);
        assertFalse(setting.pinned);
    }

    /**
     * The present-key leg: the administered row is returned as it stands, the
     * same instance, so a caller writing into it writes into the referential
     * row and not into a copy.
     */
    @Test
    void anAdministeredGroupYieldsItsOwnRow() {
        TouchGroupSetting administered = row("RAYON", true);
        Map<String, TouchGroupSetting> byCode = new HashMap<>();
        byCode.put("RAYON", administered);
        assertSame(administered, TouchGroupSetting.orDefaults(byCode, "RAYON"));
    }

    /**
     * A null group code behaves like an unknown one — a family whose code was
     * never set must not blow up the grid.
     */
    @Test
    void aNullGroupCodeYieldsTheDefaults() {
        Map<String, TouchGroupSetting> byCode = new HashMap<>();
        byCode.put("RAYON", row("RAYON", true));
        TouchGroupSetting setting = TouchGroupSetting.orDefaults(byCode, null);
        assertNull(setting.familyCode);
        assertFalse(setting.pinned);
    }

    /**
     * {@code byFamilyCode} indexes the whole configuration in one read, which is
     * the point: the grid draws a dozen tiles and must not issue a dozen
     * queries.
     */
    @Test
    void theWholeConfigurationIsIndexedByGroupCode() {
        TouchGroupSetting first = row("A", true);
        TouchGroupSetting second = row("B", false);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> TouchGroupSetting.list("order by familyCode"))
                    .thenReturn(List.of(first, second));
            Map<String, TouchGroupSetting> byCode = TouchGroupSetting.byFamilyCode();
            assertEquals(2, byCode.size());
            assertSame(first, byCode.get("A"));
            assertSame(second, byCode.get("B"));
        }
    }

    /**
     * An empty referential indexes to an empty map, never to null — every group
     * then falls back to the defaults.
     */
    @Test
    void anEmptyConfigurationIndexesToAnEmptyMap() {
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> TouchGroupSetting.list("order by familyCode"))
                    .thenReturn(List.of());
            assertTrue(TouchGroupSetting.byFamilyCode().isEmpty());
        }
    }

    /**
     * The lookup by group code reaches the row through its natural key.
     */
    @Test
    void theLookupGoesThroughTheGroupCode() {
        TouchGroupSetting administered = row("RAYON", true);
        io.quarkus.hibernate.orm.panache.PanacheQuery<TouchGroupSetting> query = mock(
                io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(query.firstResult()).thenReturn(administered);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> TouchGroupSetting.find("familyCode", "RAYON")).thenReturn(query);
            assertSame(administered, TouchGroupSetting.findByFamilyCode("RAYON"));
        }
    }

    /**
     * The pinned count is the bound the back office opposes, and it is counted
     * on the touch rows — not on the families, which no longer know.
     */
    @Test
    void thePinnedCountIsCountedOnTheTouchRows() {
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> TouchGroupSetting.count("pinned", true)).thenReturn(3L);
            assertEquals(3L, TouchGroupSetting.countPinned());
        }
    }

    /**
     * The fingerprint folds the four administered values, so the referential
     * notices a group being re-pinned, resized, re-ranked or re-weighted.
     */
    @Test
    void theFingerprintFollowsEveryAdministeredValue() {
        TouchGroupSetting reference = row("RAYON", false);
        int base = reference.getChecksum();
        TouchGroupSetting pinnedRow = row("RAYON", true);
        assertNotEquals(base, pinnedRow.getChecksum());
        TouchGroupSetting resized = row("RAYON", false);
        resized.buttonSize = "LARGE";
        assertNotEquals(base, resized.getChecksum());
        TouchGroupSetting reranked = row("RAYON", false);
        reranked.displayOrder = 4;
        assertNotEquals(base, reranked.getChecksum());
        TouchGroupSetting reweighted = row("RAYON", false);
        reweighted.salesVolume = 12L;
        assertNotEquals(base, reweighted.getChecksum());
        TouchGroupSetting renamed = row("AUTRE", false);
        assertNotEquals(base, renamed.getChecksum());
        assertEquals(base, row("RAYON", false).getChecksum());
        // BO-03-01-21/-22/-23: a group MOVED is a group drawn somewhere else.
        TouchGroupSetting scopedToAnIsland = row("RAYON", false);
        scopedToAnIsland.islandCodes = "AVANT";
        assertNotEquals(base, scopedToAnIsland.getChecksum());
        TouchGroupSetting scopedToARegister = row("RAYON", false);
        scopedToARegister.terminalIds = "C04";
        assertNotEquals(base, scopedToARegister.getChecksum());
        TouchGroupSetting scopedToAKind = row("RAYON", false);
        scopedToAKind.tpvTypes = "CASH_REGISTER";
        assertNotEquals(base, scopedToAKind.getChecksum());
    }

    /**
     * A group NOTHING scoped is drawn on every register (BO-03-01-21/-22/-23):
     * the null arm and the blank arm of each of the three scopes, which is what
     * keeps an unadministered grid behaving as it always did.
     */
    @Test
    void anUnscopedGroupIsDrawnEverywhere() {
        TouchGroupSetting setting = row("RAYON", false);
        assertTrue(setting.isDrawnOn("C04", "AVANT", "CASH_REGISTER"));
        assertTrue(setting.isDrawnOn(null, null, null));
        setting.islandCodes = "   ";
        setting.terminalIds = "";
        setting.tpvTypes = "  ";
        assertTrue(setting.isDrawnOn("C04", "AVANT", "CASH_REGISTER"));
        assertTrue(setting.isDrawnOn(null, null, null));
    }

    /**
     * Each scope on its own: the named value is drawn, another is not, and a
     * register that cannot say what it is is NOT drawn — a group restricted to
     * one island must not appear on a register attached to none.
     */
    @Test
    void eachScopeIsHonouredOnItsOwn() {
        TouchGroupSetting byIsland = row("RAYON", false);
        byIsland.islandCodes = "AVANT;ARRIERE";
        assertTrue(byIsland.isDrawnOn("C04", "ARRIERE", "CASH_REGISTER"));
        assertFalse(byIsland.isDrawnOn("C04", "DRIVE", "CASH_REGISTER"));
        assertFalse(byIsland.isDrawnOn("C04", null, "CASH_REGISTER"));

        TouchGroupSetting byRegister = row("RAYON", false);
        byRegister.terminalIds = "C04;C05";
        assertTrue(byRegister.isDrawnOn("C05", null, null));
        assertFalse(byRegister.isDrawnOn("C09", null, null));
        assertFalse(byRegister.isDrawnOn(null, null, null));

        TouchGroupSetting byKind = row("RAYON", false);
        byKind.tpvTypes = "SELF_CHECKOUT_DN";
        assertTrue(byKind.isDrawnOn(null, null, "SELF_CHECKOUT_DN"));
        assertFalse(byKind.isDrawnOn(null, null, "CASH_REGISTER"));
        assertFalse(byKind.isDrawnOn(null, null, "  "));
    }

    /**
     * The three scopes CUMULATE: a group scoped to an island and to a kind is
     * drawn only where both hold — and the administered values are matched
     * trimmed, because a paramétreur separates with "; " as often as with ";".
     */
    @Test
    void theThreeScopesCumulateAndAreMatchedTrimmed() {
        TouchGroupSetting setting = row("RAYON", false);
        setting.islandCodes = "AVANT ; ARRIERE";
        setting.tpvTypes = " SELF_CHECKOUT_DN ";
        assertTrue(setting.isDrawnOn("C04", "ARRIERE", "SELF_CHECKOUT_DN"));
        // The island holds, the kind does not.
        assertFalse(setting.isDrawnOn("C04", "ARRIERE", "CASH_REGISTER"));
        // The kind holds, the island does not.
        assertFalse(setting.isDrawnOn("C04", "DRIVE", "SELF_CHECKOUT_DN"));
    }

    /**
     * {@code translatedLabel} answers the wording administered for a language
     * and null for every other case (BO-03-01-19): a language nobody
     * administered, a null or blank tag, a configuration carrying no wording at
     * all, a pair with no language, a pair with no wording, and an empty
     * wording. The tag match ignores the case and the spaces.
     */
    @Test
    void translatedLabelAnswersTheAdministeredWording() {
        TouchGroupSetting setting = new TouchGroupSetting();
        setting.labels = " NL = Zuivel ;pt=Lacteos;=sans langue;de=;   ";
        assertEquals("Zuivel", setting.translatedLabel("nl"));
        assertEquals("Zuivel", setting.translatedLabel(" NL "));
        assertEquals("Lacteos", setting.translatedLabel("pt"));
        assertNull(setting.translatedLabel("de"));
        assertNull(setting.translatedLabel("es"));
        assertNull(setting.translatedLabel(null));
        assertNull(setting.translatedLabel("   "));

        TouchGroupSetting bare = new TouchGroupSetting();
        assertNull(bare.translatedLabel("nl"));
        bare.labels = "   ";
        assertNull(bare.translatedLabel("nl"));
    }

    /**
     * The translated wordings are part of what the row IS: changing one changes
     * the checksum, or a wording administered upstream would never reach the
     * registers (BO-03-01-19).
     */
    @Test
    void theChecksumSeesTheTranslatedWordings() {
        TouchGroupSetting setting = new TouchGroupSetting();
        setting.familyCode = "LAIT";
        int before = setting.getChecksum();
        setting.labels = "nl=Zuivel";
        assertNotEquals(before, setting.getChecksum());
    }

    /** BO-03-01-19: a matching language with whitespace-only wording still falls back to the base label. */
    @org.junit.jupiter.api.Test
    void coverageTranslatedLabelWithEmptyMatchingWording() {
        TouchGroupSetting group = new TouchGroupSetting();
        group.labels = "nl=   ;pt=Leite";
        org.junit.jupiter.api.Assertions.assertNull(group.translatedLabel("nl"));
    }
}
