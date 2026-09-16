package com.intermarche.pos.domain.setting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.intermarche.pos.domain.people.Menu;

/**
 * Unit tests for {@link Feature}.
 * <p>
 * Branch enumeration (every arm exercised): {@code inMenu} covers the
 * matching and non-matching arms; {@code fromKey} covers the null-key arm,
 * the found arm (loop hit) and the unknown-key arm (loop exhausted).
 */
class FeatureTest {

    /**
     * Each feature exposes its stable key, menu and label.
     */
    @Test
    void exposesKeyMenuAndLabel() {
        assertEquals("settings", Feature.SETTINGS.getKey());
        assertEquals(Menu.PARAMETRAGE, Feature.SETTINGS.getMenu());
        assertEquals("Paramètres caisse", Feature.SETTINGS.getLabel());
        assertEquals(Menu.ADMINISTRATION, Feature.PROFILE.getMenu());
        assertEquals(Menu.SUPERVISION, Feature.SUPERVISION.getMenu());
    }

    /**
     * {@code inMenu} is true for the feature's own menu (matching arm).
     */
    @Test
    void inMenuTrueForOwnMenu() {
        assertTrue(Feature.PROFILE.inMenu(Menu.ADMINISTRATION));
    }

    /**
     * {@code inMenu} is false for any other menu (non-matching arm).
     */
    @Test
    void inMenuFalseForOtherMenu() {
        assertFalse(Feature.PROFILE.inMenu(Menu.SUPERVISION));
    }

    /**
     * {@code fromKey} returns null on a null key (null arm).
     */
    @Test
    void fromKeyReturnsNullOnNull() {
        assertNull(Feature.fromKey(null));
    }

    /**
     * {@code fromKey} resolves a known key (loop-hit arm).
     */
    @Test
    void fromKeyResolvesKnownKey() {
        assertSame(Feature.USER, Feature.fromKey("user"));
    }

    /**
     * {@code fromKey} returns null on an unknown key (loop-exhausted arm).
     */
    @Test
    void fromKeyReturnsNullOnUnknownKey() {
        assertNull(Feature.fromKey("nope"));
    }
}
