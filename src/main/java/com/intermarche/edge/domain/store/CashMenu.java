package com.intermarche.edge.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A CASH MENU MODEL: one named configuration of the register's sale screen
 * (BO-03-10-16, BO-03-10-17) — the front lane, the till with a self-service
 * option, the till with a coin machine, the self-service bank.
 *
 * <p>A model states which keys of the menu bar the register shows, in which
 * order, which function keys of the grid it hides, how many key columns it
 * draws (zero meaning proportional to the size of the administered screen),
 * the model of the cashier screen, of the customer screen and of the virtual
 * ticket. A register finds its model through the {@code menu.code} parameter
 * and its checkout point: the code posed on the point wins over the island's,
 * which wins over the PDV's, the enseigne's and the country's (BO-03-09-06,
 * BO-03-10-09, BO-03-10-18). The number of models is not limited: the
 * requirement asks for ninety-nine at least, a table holds as many as needed.
 *
 * <p>The store node and the registers hold the SAME class; a register writes
 * a model only through the referential apply ({@code CASH_MENUS}).
 */
@Entity
@Table(name = "cash_menu")
public class CashMenu extends PanacheEntity {

    /** The cashier screen models (BO-03-10-17). */
    public static final List<String> CASHIER_SCREENS = List.of("STANDARD", "COMPACT", "LARGE");

    /** The customer screen models (BO-03-10-17). */
    public static final List<String> CUSTOMER_SCREENS = List.of("STANDARD", "TOTAL_ONLY", "LARGE");

    /** The virtual ticket models (BO-03-10-17). */
    public static final List<String> TICKET_MODELS = List.of("DETAILED", "COMPACT");

    /** The menu-bar keys a model may show, in their default order. */
    public static final List<String> MENU_KEYS =
            List.of("articles", "caisse", "reglement", "ticket", "generosite", "tiroir", "autre");

    /** The largest number of key columns a model may ask for. */
    public static final int MAX_KEY_COLUMNS = 4;

    /** The stable code of the model, the upsert key. */
    @Column(name = "code", unique = true, nullable = false, length = 40)
    public String code;

    /** The human-readable label shown in the back office. */
    @Column(name = "label", nullable = false, length = 100)
    public String label;

    /** Whether the model is in service; an inactive model is never drawn. */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /** The menu-bar keys shown, semicolon separated, in order; null or blank for all. */
    @Column(name = "menu_keys", length = 200)
    public String menuKeys;

    /** The function keys hidden, {@link CashFunction} names semicolon separated, or null. */
    @Column(name = "hidden_functions", length = 2000)
    public String hiddenFunctions;

    /** The number of key columns, zero for proportional to the screen. */
    @Column(name = "key_columns", nullable = false)
    public int keyColumns;

    /** The cashier screen model, one of {@link #CASHIER_SCREENS}. */
    @Column(name = "cashier_screen", length = 16)
    public String cashierScreen = "STANDARD";

    /** The customer screen model, one of {@link #CUSTOMER_SCREENS}. */
    @Column(name = "customer_screen", length = 16)
    public String customerScreen = "STANDARD";

    /** The virtual ticket model, one of {@link #TICKET_MODELS}. */
    @Column(name = "ticket_model", length = 16)
    public String ticketModel = "DETAILED";

    /**
     * The key layout — tabs, keys, colours and labels — as {@link MenuLayout}
     * JSON, or null for the standard layout reduced to {@link #menuKeys}.
     */
    @Column(name = "layout", length = 20000)
    public String layout;

    /**
     * Finds a model by its code.
     *
     * @param code the code, possibly null
     * @return the model, or null when the code is blank or unknown
     */
    public static CashMenu findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return find("code", code.trim()).firstResult();
    }

    /**
     * Lists every model in code order, for the back-office screen.
     *
     * @return the models, never null
     */
    public static List<CashMenu> listAllOrdered() {
        return list("order by code");
    }

    /**
     * Lists the models in service, in code order — the choice offered to the
     * points of contact and the islands.
     *
     * @return the active models, never null
     */
    public static List<CashMenu> listActive() {
        return list("active = true order by code");
    }

    /**
     * Returns the menu-bar keys this model shows, in its order, the unknown
     * and repeated keys dropped.
     *
     * @return the keys, empty when the model names none (every key is shown)
     */
    public List<String> menuKeyList() {
        List<String> keys = new ArrayList<>();
        if (menuKeys == null) {
            return keys;
        }
        for (String part : menuKeys.split(";")) {
            String key = part.trim().toLowerCase(Locale.ROOT);
            if (MENU_KEYS.contains(key) && !keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * Returns the function keys this model hides.
     *
     * @return the functions, empty when the model hides none
     */
    public List<CashFunction> hiddenFunctionList() {
        return CashFunction.parseList(hiddenFunctions);
    }

    /**
     * Returns the key layout the registers draw: the administered one,
     * otherwise the standard one keeping the bar keys {@link #menuKeys} names,
     * in its order.
     *
     * @return the layout, never null
     */
    public MenuLayout effectiveLayout() {
        MenuLayout administered = MenuLayout.parse(layout);
        if (administered != null) {
            return administered;
        }
        MenuLayout standard = MenuLayout.standard();
        List<String> keys = menuKeyList();
        if (keys.isEmpty()) {
            return standard;
        }
        MenuLayout shown = new MenuLayout();
        for (String key : keys) {
            shown.tabs.add(standard.tab(key));
        }
        return shown;
    }

    /**
     * Normalises a model value against its list of allowed values.
     *
     * @param raw the stored value, possibly null
     * @param allowed the allowed values, the first being the default
     * @return the value upper-cased when allowed, the default otherwise
     */
    public static String normalized(String raw, List<String> allowed) {
        if (raw == null) {
            return allowed.get(0);
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(value) ? value : allowed.get(0);
    }
}
