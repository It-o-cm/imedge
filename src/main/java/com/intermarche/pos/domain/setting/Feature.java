package com.intermarche.pos.domain.setting;

import com.intermarche.pos.domain.people.Menu;

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

    /** The TENDER referential screen: the administered settlement methods. */
    TENDER("tender", Menu.PARAMETRAGE, "Règlements", "/admin/tenders"),

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

    /** The PROFILES administration screen. */
    PROFILE("profile", Menu.ADMINISTRATION, "Profils", "/admin/profiles"),

    /** The USER-to-profile assignment screen. */
    USER("user", Menu.ADMINISTRATION, "Utilisateurs", "/admin/users"),

    /** The SUPERVISION dashboard. */
    SUPERVISION("supervision", Menu.SUPERVISION, "Supervision de la ligne", "/dashboard"),

    /** The JOURNAL screen: the line's events and movements. */
    JOURNAL("journal", Menu.SUPERVISION, "Journal", "/admin/journal"),

    /** The SYNCHRONIZATION supervision screen: the referential feeds. */
    SYNC("sync", Menu.SUPERVISION, "Flux", "/admin/sync");

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
