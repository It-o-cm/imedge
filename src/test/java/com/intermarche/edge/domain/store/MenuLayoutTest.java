package com.intermarche.edge.domain.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MenuLayout}: reading, writing, the standard
 * arrangement and every arm of the sanitising.
 */
class MenuLayoutTest {

    /**
     * Builds a tab.
     *
     * @param id the tab id
     * @param fns the function names of its keys
     * @return the tab
     */
    private MenuLayout.Tab tab(String id, String... fns) {
        MenuLayout.Tab tab = new MenuLayout.Tab();
        tab.id = id;
        for (String fn : fns) {
            tab.keys.add(new MenuLayout.Key(fn));
        }
        return tab;
    }

    /** Nothing stored, a blank, an unreadable text and a JSON null read as no layout. */
    @Test
    void parseReadsNothingFromNothing() {
        assertNull(MenuLayout.parse(null));
        assertNull(MenuLayout.parse("  "));
        assertNull(MenuLayout.parse("{not json"));
        assertNull(MenuLayout.parse("null"));
    }

    /** A stored layout is read back sanitised, labels and colours kept. */
    @Test
    void parseReadsAStoredLayout() {
        String json = "{\"tabs\":[{\"id\":\"X1\",\"labels\":{\"fr\":\" PROMO \",\"de\":\"Aktion\"},"
                + "\"color\":\"Blue\",\"keys\":[{\"fn\":\"search\",\"labels\":{\"nl\":\"Zoek\"},\"color\":\"pink\"}],"
                + "\"extra\":1}]}";
        MenuLayout layout = MenuLayout.parse(json);
        assertEquals(1, layout.tabs.size());
        MenuLayout.Tab tab = layout.tabs.get(0);
        assertEquals("x1", tab.id);
        assertEquals(Map.of("fr", "PROMO"), tab.labels);
        assertEquals("blue", tab.color);
        assertEquals("SEARCH", tab.keys.get(0).fn);
        assertEquals("Zoek", tab.keys.get(0).label("nl"));
        assertNull(tab.keys.get(0).color);
    }

    /** What is written is read back the same. */
    @Test
    void toJsonRoundTrips() {
        MenuLayout layout = MenuLayout.standard();
        layout.tabs.get(0).color = "red";
        layout.tabs.get(0).labels.put("fr", "VENTE");
        MenuLayout read = MenuLayout.parse(layout.toJson());
        assertEquals(layout.toJson(), read.toJson());
        assertEquals("VENTE", read.tab("articles").label("fr"));
    }

    /**
     * A serialisation failure is turned into an {@link IllegalStateException}
     * carrying the layout and the Jackson cause. No real layout can make
     * Jackson fail, so the codec is swapped for one that throws, the defensive
     * catch of {@code toJson()} is driven, and the original codec is restored.
     */
    @Test
    void toJsonWrapsASerialisationFailure() {
        ObjectMapper original = MenuLayout.MAPPER;
        ObjectMapper failing = mock(ObjectMapper.class);
        JsonProcessingException boom = new JsonProcessingException("boom") {
        };
        try {
            when(failing.writeValueAsString(any())).thenThrow(boom);
            MenuLayout.MAPPER = failing;
            IllegalStateException error =
                    assertThrows(IllegalStateException.class, () -> new MenuLayout().toJson());
            assertEquals("Unwritable layout", error.getMessage());
            assertSame(boom, error.getCause());
        } catch (JsonProcessingException checked) {
            throw new AssertionError("stubbing never runs the checked body", checked);
        } finally {
            MenuLayout.MAPPER = original;
        }
    }

    /** The standard layout holds the seven standard tabs, the settlement one empty. */
    @Test
    void standardHoldsTheStandardTabs() {
        MenuLayout layout = MenuLayout.standard();
        assertEquals(CashMenu.MENU_KEYS, layout.standardTabIds());
        assertTrue(layout.tab("reglement").keys.isEmpty());
        assertEquals(13, layout.tab("articles").keys.size());
        assertEquals("LEVEL_ZERO", layout.tab("articles").keys.get(9).fn);
    }

    /** The standard keys of a standard tab, none for a created or unknown tab. */
    @Test
    void standardKeysOfATab() {
        assertEquals(List.of(CashFunction.SALE_TAX_ID, CashFunction.FIDELITY, CashFunction.VOUCHER_BALANCE,
                CashFunction.DISCOUNT, CashFunction.TICKET_DISCOUNT), MenuLayout.standardKeys("generosite"));
        assertTrue(MenuLayout.standardKeys("x1").isEmpty());
    }

    /** A tab is found by its id, none for an unknown id. */
    @Test
    void tabFindsById() {
        MenuLayout layout = MenuLayout.standard();
        assertSame(layout.tabs.get(1), layout.tab("caisse"));
        assertNull(layout.tab("x9"));
    }

    /** The created tabs are left out of the standard tab ids. */
    @Test
    void standardTabIdsLeaveOutCreatedTabs() {
        MenuLayout layout = new MenuLayout();
        layout.tabs.add(tab("x1"));
        layout.tabs.add(tab("ticket"));
        assertEquals(List.of("ticket"), layout.standardTabIds());
    }

    /** A null tab list sanitises to an empty layout. */
    @Test
    void sanitizedToleratesNoTabs() {
        MenuLayout layout = new MenuLayout();
        layout.tabs = null;
        assertTrue(layout.sanitized().tabs.isEmpty());
    }

    /** Null, idless, unknown and repeated tabs are dropped, the settlement tab emptied. */
    @Test
    void sanitizedDropsBadTabs() {
        MenuLayout layout = new MenuLayout();
        layout.tabs.add(null);
        layout.tabs.add(tab(null));
        layout.tabs.add(tab("nowhere"));
        layout.tabs.add(tab("Ticket", "PARK"));
        layout.tabs.add(tab("ticket", "ABANDON"));
        layout.tabs.add(tab("reglement", "SEARCH"));
        MenuLayout clean = layout.sanitized();
        assertEquals(List.of("ticket", "reglement"), clean.standardTabIds());
        assertEquals("PARK", clean.tab("ticket").keys.get(0).fn);
        assertTrue(clean.tab("reglement").keys.isEmpty());
    }

    /** No more tabs than the bar holds are kept. */
    @Test
    void sanitizedBoundsTheTabs() {
        MenuLayout layout = new MenuLayout();
        for (int i = 1; i <= MenuLayout.MAX_TABS + 2; i++) {
            layout.tabs.add(tab("x" + i));
        }
        assertEquals(MenuLayout.MAX_TABS, layout.sanitized().tabs.size());
    }

    /** Standard ids and x followed by one to three digits are tab ids, nothing else. */
    @Test
    void validTabIds() {
        assertTrue(MenuLayout.validTabId("autre"));
        assertTrue(MenuLayout.validTabId("x12"));
        assertFalse(MenuLayout.validTabId("x"));
        assertFalse(MenuLayout.validTabId("x1234"));
        assertFalse(MenuLayout.validTabId("menu"));
    }

    /** A null key list cleans to none. */
    @Test
    void cleanKeysToleratesNoKeys() {
        assertTrue(MenuLayout.cleanKeys(null).isEmpty());
    }

    /** Null, unknown and repeated keys are dropped, labels and colours cleaned. */
    @Test
    void cleanKeysDropsBadKeys() {
        List<MenuLayout.Key> keys = new ArrayList<>();
        keys.add(null);
        keys.add(new MenuLayout.Key("NOTHING"));
        MenuLayout.Key search = new MenuLayout.Key("search");
        search.color = " GREEN ";
        search.labels.put("pt", "Pesquisa");
        keys.add(search);
        keys.add(new MenuLayout.Key("SEARCH"));
        List<MenuLayout.Key> clean = MenuLayout.cleanKeys(keys);
        assertEquals(1, clean.size());
        assertEquals("SEARCH", clean.get(0).fn);
        assertEquals("green", clean.get(0).color);
        assertEquals(Map.of("pt", "Pesquisa"), clean.get(0).labels);
    }

    /** No more keys than a tab holds are kept. */
    @Test
    void cleanKeysBoundsTheKeys() {
        List<MenuLayout.Key> keys = new ArrayList<>();
        for (CashFunction function : CashFunction.values()) {
            keys.add(new MenuLayout.Key(function.name()));
        }
        assertEquals(MenuLayout.MAX_KEYS, MenuLayout.cleanKeys(keys).size());
    }

    /** Labels: none, unknown languages, null and blank texts dropped, long ones cut. */
    @Test
    void cleanLabels() {
        assertTrue(MenuLayout.cleanLabels(null).isEmpty());
        Map<String, String> labels = new HashMap<>();
        labels.put("fr", "A".repeat(50));
        labels.put("nl", null);
        labels.put("pt", " ");
        labels.put("en", "Search");
        assertEquals(Map.of("fr", "A".repeat(MenuLayout.MAX_LABEL)), MenuLayout.cleanLabels(labels));
    }

    /** Colours: none, a palette colour in any case, an unknown one. */
    @Test
    void cleanColor() {
        assertNull(MenuLayout.cleanColor(null));
        assertEquals("teal", MenuLayout.cleanColor("Teal"));
        assertNull(MenuLayout.cleanColor("pink"));
    }

    /** A label is read for a language, none without labels, language or text. */
    @Test
    void labelOfALanguage() {
        Map<String, String> labels = new HashMap<>();
        labels.put("fr", "VENTE");
        labels.put("nl", " ");
        assertNull(MenuLayout.label(null, "fr"));
        assertNull(MenuLayout.label(labels, null));
        assertNull(MenuLayout.label(labels, "pt"));
        assertNull(MenuLayout.label(labels, "nl"));
        assertEquals("VENTE", MenuLayout.label(labels, "FR"));
    }

    /** A tab tells whether it is created and whether it opens the payment, and gives its label. */
    @Test
    void tabKinds() {
        MenuLayout.Tab created = tab("x1");
        created.labels.put("fr", "PROMO");
        assertTrue(created.custom());
        assertFalse(created.direct());
        assertEquals("PROMO", created.label("fr"));
        assertFalse(tab("caisse").custom());
        assertFalse(tab(null).custom());
        assertTrue(tab("reglement").direct());
    }

    /** A key gives its function, none for an unknown name. */
    @Test
    void keyFunction() {
        assertEquals(CashFunction.RECALL, new MenuLayout.Key("RECALL").function());
        assertNull(new MenuLayout.Key().function());
    }
}
