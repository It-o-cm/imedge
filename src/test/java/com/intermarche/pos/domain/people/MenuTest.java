package com.intermarche.pos.domain.people;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import com.intermarche.pos.domain.setting.Feature;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit tests for {@link Menu}.
 * <p>
 * A pure label-carrying enum with no branches: the coverage target is met by
 * instantiating every constant (class load) and exercising the single getter.
 */
class MenuTest {

    /**
     * Every constant exposes its French label.
     */
    @Test
    void labelsAreExposed() {
        assertEquals("Paramétrage", Menu.PARAMETRAGE.getLabel());
        assertEquals("Articles", Menu.ARTICLES.getLabel());
        assertEquals("Administration", Menu.ADMINISTRATION.getLabel());
        assertEquals("Supervision", Menu.SUPERVISION.getLabel());
    }

    /**
     * The declared constants are the four expected menus.
     */
    @Test
    void declaresFourMenus() {
        assertEquals(4, Menu.values().length);
        assertNotNull(Menu.valueOf("ADMINISTRATION"));
        assertNotNull(Menu.valueOf("ARTICLES"));
    }

    /**
     * Every menu groups at least one feature, and every feature is reachable
     * through exactly one menu.
     * <p>
     * This is what makes the navigation complete: a feature declaring no menu
     * would name a screen no heading opens on, and a menu grouping nothing
     * would be a heading opening on nothing. Both are silent defects on a
     * screen nobody re-reads.
     */
    @Test
    void everyFeatureIsReachedThroughExactlyOneMenu() {
        int grouped = 0;
        for (Menu menu : Menu.values()) {
            assertFalse(menu.features().isEmpty(), menu.name() + " groups nothing");
            grouped += menu.features().size();
        }
        assertEquals(Feature.values().length, grouped);
    }

    /**
     * The features of a menu are those declaring it, in declaration order —
     * the order the navigation and the profile grid both show.
     */
    @Test
    void theFeaturesOfAMenuAreThoseDeclaringIt() {
        for (Menu menu : Menu.values()) {
            for (Feature feature : menu.features()) {
                assertEquals(menu, feature.getMenu());
            }
        }
        assertEquals(List.of(Feature.PROFILE, Feature.USER), Menu.ADMINISTRATION.features());
    }
}
