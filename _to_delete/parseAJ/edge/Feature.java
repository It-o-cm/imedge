package com.intermarche.edge.domain.setting;

import com.intermarche.edge.domain.people.Menu;

/**
 * A back-office FUNCTIONALITY, unitarily addressable to a profile
 * (BO-01-04-01).
 * <p>
 * Each feature is the smallest thing a grant can point at: a profile holds a
 * set of ({@code feature}, {@link CrudOption option}) pairs, so a right can
 * be given on one screen without giving it on the next. Features declare the
 * {@link Menu} they belong to, which is the aggregate the profile grid groups
 * them under (BO-01-04-06) — the menu carries no right of its own.
 * <p>
 * The catalog is intentionally an enum, not a table: a functionality is a
 * seam in the CODE (a resource, a gesture), so it appears when the code that
 * serves it appears, and a profile can never grant a right on a screen that
 * does not exist. The {@code key} is the stable token stored in a grant and
 * posted by the form; renaming an enum constant never rewrites stored grants
 * as long as its key is kept.
 */
public enum Feature {

    /** The register PARAMETERS screen. */
    SETTINGS("settings", Menu.PARAMETRAGE, "Paramètres caisse", "/admin/settings"),

    /** The point-of-sale INFORMATION screen. */
    STORE("store", Menu.PARAMETRAGE, "Point de vente", "/admin/store"),

    /** The ECHELON parameters screen: country, enseigne, point of sale. */
    ECHELON("echelon", Menu.PARAMETRAGE, "Échelons", "/admin/echelons"),

    /** The CHECKOUT POINT screen: the registers as administered objects (BO-03-09). */
    CHECKOUT_POINT("checkout_point", Menu.PARAMETRAGE, "Points de caisse", "/admin/checkout-points"),

    /** The CHECKOUT ISLAND screen: the register groups as settings-bearing objects (BO-03-10). */
    CHECKOUT_ISLAND("checkout_island", Menu.PARAMETRAGE, "Îlots de caisse", "/admin/checkout-islands"),

    /** The TENDER referential screen: the administered settlement methods. */
    TENDER("tender", Menu.PARAMETRAGE, "Règlements", "/admin/tenders"),

    /** The CURRENCY referential screen: foreign and legacy currency rates (EXP-22, LC-02-05-02). */
    CURRENCY("currency", Menu.PARAMETRAGE, "Devises", "/admin/currencies"),

    /** The DOCUMENT TEMPLATE screen: the administered layouts. */
    TEMPLATE("template", Menu.PARAMETRAGE, "Gabarits", "/admin/templates"),

    /** The BARCODE screen: coupon patterns and article ranges. */
    COUPON("coupon", Menu.PARAMETRAGE, "Codes-barres", "/admin/coupons"),

    /** The ARTICLE screen: the catalog and the article sheet. */
    ARTICLE("article", Menu.ARTICLES, "Articles", "/admin/articles"),

    /** The ARTICLE GROUP screen: the group tree and its memberships. */
    ARTICLE_GROUP("article_group", Menu.ARTICLES, "Groupes", "/admin/article-groups"),

    /** The NOMENCLATURE screen: the classification, level by level. */
    NOMENCLATURE("nomenclature", Menu.ARTICLES, "Nomenclatures", "/admin/nomenclatures"),

    /** The TOUCH screen: how the groups are drawn on the direct-entry grid. */
    TOUCH("touch", Menu.ARTICLES, "Touches", "/admin/touches"),

    /** The VAT screen: the regimes every price points at (BO-02-03-14). */
    VAT_RATE("vat_rate", Menu.ARTICLES, "TVA", "/admin/vat-rates"),

    /** The EGALIM screen: the legal categories that cap promotional generosity. */
    EGALIM_REGIME("egalim_regime", Menu.ARTICLES, "EGAlim", "/admin/egalim-regimes"),

    /** The CAMPAIGN screen: generosity campaigns and their budgets (GB-01-06-15). */
    CAMPAIGN("campaign", Menu.ARTICLES, "Campagnes", "/admin/campaigns"),

    /** The CUSTOMERS-IN-ACCOUNT referential screen (BO-02-04-22/23). */
    CUSTOMER("customer", Menu.PARAMETRAGE, "Clients en compte", "/admin/customers"),

    /** The POSTAL CODES referential screen (BO-02-04-16). */
    POSTAL_CODE("postal_code", Menu.PARAMETRAGE, "Codes postaux", "/admin/postal-codes"),

    /** The DOCUMENTS issued at the registers (BO-02-04-19). */
    DOCUMENT("document", Menu.SUPERVISION, "Documents de caisse", "/admin/documents"),

    /** The CUSTOMER ORDERS recalled at the till by code: quotes, orders, vouchers, click&collect (EXP-90/91/92). */
    CUSTOMER_ORDER("customer_order", Menu.SUPERVISION, "Commandes & devis", "/admin/orders"),

    /** The PROFILES administration screen. */
    PROFILE("profile", Menu.ADMINISTRATION, "Profils", "/admin/profiles"),

    /** The USER-to-profile assignment screen. */
    USER("user", Menu.ADMINISTRATION, "Utilisateurs", "/admin/users"),

    /**
     * The ACCOUNT screen: who exists at all (BO-01-01-01, -02, -06). Distinct
     * from {@link #USER}, which binds profiles to an account that already
     * exists, and from {@link #EMPLOYEE}, which settles its payroll side.
     */
    ACCOUNT("account", Menu.ADMINISTRATION, "Comptes", "/admin/accounts"),

    /**
     * The ADMINISTERED LISTS screen: the lists the cashier taps
     * (BO-04-02-07, BO-04-03-12), which used to be semicolon-separated
     * settings.
     */
    POS_LIST("pos_list", Menu.ADMINISTRATION, "Listes", "/admin/lists"),

    /** The SUPERVISION dashboard. */
    SUPERVISION("supervision", Menu.SUPERVISION, "Supervision de la ligne", "/dashboard"),

    /** The JOURNAL screen: the line's events and movements. */
    JOURNAL("journal", Menu.SUPERVISION, "Journal", "/admin/journal"),

    /** The REPORTS surface: the catalogue of states and their exports. */
    REPORT("report", Menu.SUPERVISION, "Rapports", "/admin/reports"),

    /** The SYNCHRONIZATION supervision screen: the referential feeds. */
    SYNC("sync", Menu.SUPERVISION, "Flux", "/admin/sync"),

    /** The SAFE MOVEMENTS screen: expenses, receipts and their pointing (BO-05-02-01/02/06). */
    SAFE_MOVEMENT("safe_movement", Menu.FINANCIER, "Mouvements du coffre",
            "/admin/safe/movements"),

    /** The COIN ORDER screen: the orders and their deliveries (BO-05-02-04/05). */
    SAFE_COIN_ORDER("safe_coin_order", Menu.FINANCIER, "Demandes de monnaie",
            "/admin/safe/coin-orders"),

    /** The POUCH screen: the registers' takings brought to the safe (BO-05-02-10). */
    SAFE_POUCH("safe_pouch", Menu.FINANCIER, "Remises au coffre", "/admin/safe/pouches"),

    /** The BANK DEPOSIT screen and its accounting pieces (BO-05-02-07). */
    SAFE_BANK_DEPOSIT("safe_bank_deposit", Menu.FINANCIER, "Remises en banque",
            "/admin/safe/bank-deposits"),

    /** The INVENTORY screen: the physical count against the ledger (BO-05-02-11). */
    SAFE_INVENTORY("safe_inventory", Menu.FINANCIER, "Inventaire du coffre",
            "/admin/safe/inventories"),

    /** The ACCOUNTING PERIOD screen: opening and closing the safe (BO-05-02-12). */
    SAFE_PERIOD("safe_period", Menu.FINANCIER, "Clôture du coffre", "/admin/safe/periods"),

    /** The SAFE JOURNAL: the debits and credits of a period (BO-05-02-13). */
    SAFE_JOURNAL("safe_journal", Menu.FINANCIER, "Journal du coffre", "/admin/safe/journal"),

    /** The DAY HISTORY: the registers' takings by tender, day by day (BO-05-02-08/09). */
    SAFE_DAY("safe_day", Menu.FINANCIER, "Historique des journées", "/admin/safe/days"),

    /** The PAYROLL screen: who may receive an advance or a reimbursement (BO-05-02-15). */
    EMPLOYEE("employee", Menu.FINANCIER, "Salariés", "/admin/employees"),

    /** The COIN MACHINE screen: what each machine holds (BO-05-02-16). */
    COIN_MACHINE("coin_machine", Menu.PARAMETRAGE, "Monnayeurs", "/admin/coin-machines"),

    /**
     * The SERVICE PROVIDER screen: which third parties the store is connected to,
     * how they are reached and when they are called (BO-03-11-01 to 06).
     */
    SERVICE_PROVIDER("service_provider", Menu.PARAMETRAGE, "Fournisseurs de service",
            "/admin/providers"),

    /**
     * The STORE MESSAGES screen: what the store says to its registers, to whom,
     * when, and who has read it (BO-03-08-01, -02, -04 and -08).
     */
    STORE_MESSAGE("store_message", Menu.PARAMETRAGE, "Messages aux caisses", "/admin/messages"),

    /** The FINANCIAL STATES screen: the X readings and Z documents of the node (X/Z campaign). */
    FINANCIAL_STATE("financial_state", Menu.FINANCIER, "États financiers X/Z", "/admin/financial-states");

    /** The stable storage/form token of this feature. */
    private final String key;

    /** The aggregate this feature is grouped under. */
    private final Menu menu;

    /** The operator-facing label (French). */
    private final String label;

    /**
     * The path of the screen serving this feature.
     * <p>
     * Held here so the back-office navigation is BUILT from this catalog
     * instead of being a second, hand-written list beside it. Before, the
     * header carried fourteen links and this enum knew five features: nine
     * screens were reachable by everyone and grantable to no one, and nothing
     * could tell.
     */
    private final String path;

    /**
     * Builds a feature with its stable key, its menu, its label and its path.
     *
     * @param key the stable storage/form token
     * @param menu the aggregate this feature belongs to
     * @param label the French label shown in the profile grid
     * @param path the path of the screen serving it
     */
    Feature(String key, Menu menu, String label, String path) {
        this.key = key;
        this.menu = menu;
        this.label = label;
        this.path = path;
    }

    /**
     * Returns the stable storage/form token of this feature.
     *
     * @return the key stored in a grant
     */
    public String getKey() {
        return key;
    }

    /**
     * Returns the aggregate this feature is grouped under.
     *
     * @return the menu
     */
    public Menu getMenu() {
        return menu;
    }

    /**
     * Returns the operator-facing label of this feature.
     *
     * @return the French label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Returns the path of the screen serving this feature.
     *
     * @return the back-office path
     */
    public String getPath() {
        return path;
    }

    /**
     * Indicates whether this feature belongs to a given menu.
     *
     * @param candidate the menu to test
     * @return true when this feature declares the given menu
     */
    public boolean inMenu(Menu candidate) {
        return menu == candidate;
    }

    /**
     * Resolves a feature from its stable key.
     *
     * @param key the stored/posted key, possibly null or unknown
     * @return the matching feature, or null when the key is null or unknown
     */
    public static Feature fromKey(String key) {
        if (key == null) {
            return null;
        }
        for (Feature feature : values()) {
            if (feature.key.equals(key)) {
                return feature;
            }
        }
        return null;
    }
}
