package com.intermarche.pos.domain.setting;

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
    }
}
