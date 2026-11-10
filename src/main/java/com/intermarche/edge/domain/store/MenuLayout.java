package com.intermarche.edge.domain.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * THE KEY LAYOUT of a cash menu model: the tabs of the register's bottom bar in
 * their order, and for each tab the function keys of its grid in their order,
 * each tab and key with an optional colour and an optional label per language.
 *
 * <p>A tab is either one of the standard tabs ({@link CashMenu#MENU_KEYS}) or a
 * tab created by the back office ({@code x1}, {@code x2}…). The settlement tab
 * ({@link #DIRECT_TAB}) opens the payment screen and carries no key. A function
 * may sit in several tabs, once per tab.
 *
 * <p>The layout travels as JSON in {@link CashMenu#layout}; the store node and
 * the registers hold the SAME class. Whatever is read is sanitised: unknown
 * functions, colours, languages and tabs are dropped, labels are trimmed and
 * cut, the numbers of tabs and keys are bounded — a layout the register reads
 * can never draw a broken bar.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class MenuLayout {

    /** The colours a tab or a key may take, each drawn for the light and the dark theme. */
    public static final List<String> COLORS =
            List.of("blue", "green", "red", "orange", "yellow", "purple", "teal", "grey");

    /** The languages a label may be given in. */
    public static final List<String> LANGUAGES = List.of("fr", "nl", "pt");

    /** The tab that opens the payment screen instead of a grid of keys. */
    public static final String DIRECT_TAB = "reglement";

    /** The prefix of the tabs the back office creates. */
    public static final String CUSTOM_PREFIX = "x";

    /** The largest number of tabs the bottom bar holds next to its MAINTENANCE key. */
    public static final int MAX_TABS = 9;

    /** The largest number of keys a tab holds. */
    public static final int MAX_KEYS = 24;

    /** The longest label, in characters. */
    public static final int MAX_LABEL = 40;

    /**
     * The JSON codec, tolerant of unknown properties. Package-private and
     * non-final so a unit test can swap in a mapper that fails, exercising the
     * defensive {@code toJson()} catch which no real layout can reach.
     */
    static ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** The standard arrangement: each standard tab with its keys, in bar order. */
    private static final Map<String, List<CashFunction>> STANDARD = standardArrangement();

    /** The tabs, in bar order. */
    public List<Tab> tabs = new ArrayList<>();

    /**
     * One tab of the bottom bar.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class Tab {

        /** The tab id: a standard menu key or a created tab ({@code x1}…). */
        public String id;

        /** The label per language, empty for the standard label. */
        public Map<String, String> labels = new LinkedHashMap<>();

        /** The colour, one of {@link #COLORS}, or null for the standard one. */
        public String color;

        /** The keys of the tab's grid, in order. */
        public List<Key> keys = new ArrayList<>();

        /**
         * Tells whether the tab is one the back office created.
         *
         * @return true for a created tab
         */
        public boolean custom() {
            return id != null && id.startsWith(CUSTOM_PREFIX);
        }

        /**
         * Tells whether the tab opens the payment screen rather than a grid.
         *
         * @return true for the settlement tab
         */
        public boolean direct() {
            return DIRECT_TAB.equals(id);
        }

        /**
         * Returns the label given for a language.
         *
         * @param language the language code
         * @return the label, or null when none is given
         */
        public String label(String language) {
            return MenuLayout.label(labels, language);
        }
    }

    /**
     * One function key of a tab's grid.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class Key {

        /** The function, a {@link CashFunction} name. */
        public String fn;

        /** The label per language, empty for the standard label. */
        public Map<String, String> labels = new LinkedHashMap<>();

        /** The colour, one of {@link #COLORS}, or null for the standard one. */
        public String color;

        /**
         * Builds an empty key, for the JSON codec.
         */
        public Key() {
        }

        /**
         * Builds a key with the standard label and colour.
         *
         * @param fn the function name
         */
        public Key(String fn) {
            this.fn = fn;
        }

        /**
         * Returns the function of the key.
         *
         * @return the function, or null when the name is unknown
         */
        public CashFunction function() {
            return CashFunction.fromName(fn);
        }

        /**
         * Returns the label given for a language.
         *
         * @param language the language code
         * @return the label, or null when none is given
         */
        public String label(String language) {
            return MenuLayout.label(labels, language);
        }
    }

    /**
     * Reads a stored layout.
     *
     * @param json the stored JSON, possibly null
     * @return the sanitised layout, or null when none is stored or it is unreadable
     */
    public static MenuLayout parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            MenuLayout layout = MAPPER.readValue(json, MenuLayout.class);
            return layout == null ? null : layout.sanitized();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Writes the layout as stored.
     *
     * @return the JSON
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("Unwritable layout", e);
        }
    }

    /**
     * The standard layout: the standard tabs and keys, no colour, no label —
     * what a register draws when its model gives no layout.
     *
     * @return a new standard layout
     */
    public static MenuLayout standard() {
        MenuLayout layout = new MenuLayout();
        for (Map.Entry<String, List<CashFunction>> entry : STANDARD.entrySet()) {
            Tab tab = new Tab();
            tab.id = entry.getKey();
            for (CashFunction function : entry.getValue()) {
                tab.keys.add(new Key(function.name()));
            }
            layout.tabs.add(tab);
        }
        return layout;
    }

    /**
     * The standard keys of a standard tab.
     *
     * @param id the tab id
     * @return the functions, empty for an unknown or created tab
     */
    public static List<CashFunction> standardKeys(String id) {
        return STANDARD.getOrDefault(id, List.of());
    }

    /**
     * Finds a tab by its id.
     *
     * @param id the tab id
     * @return the tab, or null when the layout has none of that id
     */
    public Tab tab(String id) {
        for (Tab tab : tabs) {
            if (tab.id.equals(id)) {
                return tab;
            }
        }
        return null;
    }

    /**
     * Returns the standard tabs the layout shows, in its order — what an older
     * register, which knows only those, still draws.
     *
     * @return the standard tab ids
     */
    public List<String> standardTabIds() {
        List<String> ids = new ArrayList<>();
        for (Tab tab : tabs) {
            if (!tab.custom()) {
                ids.add(tab.id);
            }
        }
        return ids;
    }

    /**
     * Returns a clean copy: unknown tabs, functions, colours and languages
     * dropped, repeated tabs and keys dropped, labels trimmed and cut, the
     * settlement tab emptied, the numbers of tabs and keys bounded.
     *
     * @return the sanitised layout
     */
    public MenuLayout sanitized() {
        MenuLayout clean = new MenuLayout();
        List<String> seen = new ArrayList<>();
        for (Tab tab : tabs == null ? List.<Tab>of() : tabs) {
            if (tab == null || clean.tabs.size() >= MAX_TABS) {
                continue;
            }
            String id = tab.id == null ? "" : tab.id.trim().toLowerCase(Locale.ROOT);
            if (!validTabId(id) || seen.contains(id)) {
                continue;
            }
            seen.add(id);
            Tab kept = new Tab();
            kept.id = id;
            kept.labels = cleanLabels(tab.labels);
            kept.color = cleanColor(tab.color);
            if (!kept.direct()) {
                kept.keys = cleanKeys(tab.keys);
            }
            clean.tabs.add(kept);
        }
        return clean;
    }

    /**
     * Tells whether a tab id is a standard menu key or a created tab.
     *
     * @param id the lower-cased id
     * @return true when the id is acceptable
     */
    static boolean validTabId(String id) {
        return CashMenu.MENU_KEYS.contains(id) || id.matches(CUSTOM_PREFIX + "[0-9]{1,3}");
    }

    /**
     * Cleans the keys of a tab.
     *
     * @param keys the keys read, possibly null
     * @return the known functions, each once, at most {@link #MAX_KEYS}
     */
    static List<Key> cleanKeys(List<Key> keys) {
        List<Key> clean = new ArrayList<>();
        List<CashFunction> seen = new ArrayList<>();
        for (Key key : keys == null ? List.<Key>of() : keys) {
            CashFunction function = key == null ? null : key.function();
            if (function == null || seen.contains(function) || clean.size() >= MAX_KEYS) {
                continue;
            }
            seen.add(function);
            Key kept = new Key(function.name());
            kept.labels = cleanLabels(key.labels);
            kept.color = cleanColor(key.color);
            clean.add(kept);
        }
        return clean;
    }

    /**
     * Cleans labels: known languages only, trimmed, cut, blanks dropped.
     *
     * @param labels the labels read, possibly null
     * @return the labels kept
     */
    static Map<String, String> cleanLabels(Map<String, String> labels) {
        Map<String, String> clean = new LinkedHashMap<>();
        if (labels == null) {
            return clean;
        }
        for (String language : LANGUAGES) {
            String text = labels.get(language);
            if (text != null && !text.isBlank()) {
                String trimmed = text.trim();
                clean.put(language, trimmed.length() > MAX_LABEL ? trimmed.substring(0, MAX_LABEL) : trimmed);
            }
        }
        return clean;
    }

    /**
     * Cleans a colour.
     *
     * @param color the colour read, possibly null
     * @return the colour when it is one of {@link #COLORS}, null otherwise
     */
    static String cleanColor(String color) {
        if (color == null) {
            return null;
        }
        String value = color.trim().toLowerCase(Locale.ROOT);
        return COLORS.contains(value) ? value : null;
    }

    /**
     * Reads the label of a language from a label map.
     *
     * @param labels the labels, possibly null
     * @param language the language code, possibly null
     * @return the label, or null when none is given
     */
    static String label(Map<String, String> labels, String language) {
        if (labels == null || language == null) {
            return null;
        }
        String text = labels.get(language.toLowerCase(Locale.ROOT));
        return text == null || text.isBlank() ? null : text;
    }

    /**
     * Builds the standard arrangement, in bar order.
     *
     * @return each standard tab with its functions
     */
    private static Map<String, List<CashFunction>> standardArrangement() {
        Map<String, List<CashFunction>> standard = new LinkedHashMap<>();
        standard.put("articles", List.of(CashFunction.CANCEL_LINE, CashFunction.CANCEL_BY_SCAN,
                CashFunction.CANCEL_GROUP, CashFunction.QUANTITY, CashFunction.MANUAL_WEIGHT,
                CashFunction.COLLECT, CashFunction.REPEAT, CashFunction.FRUITS, CashFunction.MANUAL,
                CashFunction.LEVEL_ZERO, CashFunction.SEARCH, CashFunction.SUBTOTAL, CashFunction.ARTICLE_INFO));
        standard.put("caisse", List.of(CashFunction.LOCK, CashFunction.CLOSE, CashFunction.SESSION,
                CashFunction.CASH_CALCULATOR, CashFunction.TRAINING, CashFunction.VIGNETTES));
        standard.put("reglement", List.of());
        standard.put("ticket", List.of(CashFunction.PARK, CashFunction.ABANDON, CashFunction.RETURN_MODE,
                CashFunction.RETURN, CashFunction.INVOICE, CashFunction.REPRINT, CashFunction.PRINT_LAST,
                CashFunction.PRINT_LAST_BARCODE, CashFunction.PRINT_LAST_CARD, CashFunction.TICKET_EMAIL,
                CashFunction.CUSTOMER_DOCUMENTS));
        standard.put("generosite", List.of(CashFunction.SALE_TAX_ID, CashFunction.FIDELITY,
                CashFunction.VOUCHER_BALANCE, CashFunction.DISCOUNT, CashFunction.TICKET_DISCOUNT));
        standard.put("tiroir", List.of(CashFunction.CASH_MOVEMENT, CashFunction.WITHDRAWAL,
                CashFunction.FLOAT_SUPPLY, CashFunction.TRANSFER, CashFunction.FINANCIAL_MOVEMENT,
                CashFunction.CHANGE_ORDER, CashFunction.DEPOSIT_RETURN, CashFunction.CASH_CHANGER,
                CashFunction.DRAWER_OPEN));
        standard.put("autre", List.of(CashFunction.SUPERVISOR, CashFunction.MONETICS_DEGRADED,
                CashFunction.RECALL, CashFunction.BO_MESSAGE, CashFunction.ORDER, CashFunction.BADGE_PRINT,
                CashFunction.PIN_CHANGE, CashFunction.THEME, CashFunction.LANGUAGE,
                CashFunction.EXTERNAL_PROGRAM, CashFunction.EXTERNAL_APPS, CashFunction.SINGLE_QUEUE));
        return standard;
    }
}
