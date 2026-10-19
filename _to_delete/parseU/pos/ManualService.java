package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductFamily;
import com.intermarche.pos.domain.setting.TouchGroupSetting;
import com.intermarche.pos.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;

/**
 * Feeds the SAISIE DIRECTE screen: a drill-down of the family tree showing
 * only the branches that lead to EAN-only products (PLU products are
 * excluded — their home is the weighing screen), so the cashier never
 * opens an empty category. Selection ends on an EAN, added through the
 * normal ticket path.
 * <p>
 * The group grid honours the back-office touch configuration (Lot 5F),
 * administered on the families and the SETTINGS domain and reaching the
 * register at the tirage:
 * <ul>
 *   <li>the display ORDER (BO-03-01-10/11/13) — alphabetical, custom rank or
 *       sales volume — set by {@code touch.display-order};</li>
 *   <li>the number of group touches PER PAGE (BO-03-01-06) set by
 *       {@code touch.groups-per-page}, with a previous/next pager;</li>
 *   <li>the PINNED groups (BO-03-01-07) shown permanently, on every page and
 *       every drilled-down level, whatever the navigation;</li>
 *   <li>the button SIZE (BO-03-01-08) carried as a CSS class on each tile.</li>
 * </ul>
 */
@ApplicationScoped
public class ManualService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ManualService.class);

    /** The page size used when no settings service is wired (unit context). */
    static final int DEFAULT_PER_PAGE = 12;

    /** Tiles the grid shows at once, three columns by four rows. */
    private static final int GRID_TILES = 12;

    /**
     * Every database read this screen needs.
     *
     * <p>Package-private so a unit test can put a fake in its place: the queries are
     * the only part of this screen that needs a database, and keeping them behind one
     * collaborator is what keeps the paging and ordering logic testable without one.
     */
    @Inject
    ManualRepository repository;

    /**
     * The back-office parameters: the display order and page size. Null on a
     * bare instance built without CDI (a unit test that does not exercise the
     * configuration), in which case the defaults apply.
     */
    @Inject
    PosSettingsService posSettings;

    /**
     * The register's own identifier, for the scope of a group (BO-03-01-22).
     * Null on a bare instance built without CDI, in which case the register
     * says nothing about itself and only the unscoped groups are drawn.
     */
    @Inject
    com.intermarche.pos.service.TicketNumberService ticketNumberService;

    /**
     * The operator messages, consulted here for ONE thing: the language the
     * till is set to, which decides the wording of the group touches
     * (BO-03-01-19).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    // DTOs internes
    /**
     * One tile of the drill-down: a category to open or a product to add.
     */
    public static class ManualItem {
        public String label; public boolean isCategory; public String url; public String ean;
        /** The size CSS class of the tile (BO-03-01-08). */
        public String sizeClass;
        /** Whether this tile is a pinned group shown permanently (BO-03-01-07). */
        public boolean pinned;

        /**
         * Creates a tile.
         *
         * @param label the tile label
         * @param isCategory true for a category
         * @param url the drill-down url, or null
         * @param ean the product EAN, or null
         * @param sizeClass the size CSS class of the tile
         * @param pinned whether the tile is a pinned group
         */
        public ManualItem(String label, boolean isCategory, String url, String ean,
                          String sizeClass, boolean pinned) {
            this.label = label; this.isCategory = isCategory; this.url = url; this.ean = ean;
            this.sizeClass = sizeClass; this.pinned = pinned;
        }
    }
    /**
     * One rendered level of the drill-down: tiles, breadcrumb, the way back up
     * and the pager of the group grid.
     */
    public static class ManualViewData {
        public List<ManualItem> items; public String breadcrumb; public boolean isRoot; public String parentUrl;
        /** The 1-based current page of the group grid. */
        public int page;
        /** The total number of pages of the group grid. */
        public int totalPages;
        /** The previous-page url, or null on the first page. */
        public String prevUrl;
        /** The next-page url, or null on the last page. */
        public String nextUrl;

        /**
         * Creates a rendered level.
         *
         * @param items the tiles
         * @param breadcrumb the breadcrumb text
         * @param isRoot true at the root
         * @param parentUrl the parent url, or null
         * @param page the 1-based current page
         * @param totalPages the total page count
         * @param prevUrl the previous-page url, or null
         * @param nextUrl the next-page url, or null
         */
        public ManualViewData(List<ManualItem> items, String breadcrumb, boolean isRoot, String parentUrl,
                              int page, int totalPages, String prevUrl, String nextUrl) {
            this.items = items; this.breadcrumb = breadcrumb; this.isRoot = isRoot; this.parentUrl = parentUrl;
            this.page = page; this.totalPages = totalPages; this.prevUrl = prevUrl; this.nextUrl = nextUrl;
        }
    }

    /**
     * Builds the root level: the TOP families of the tree (those that are
     * not a sub-family of any other) leading to at least one EAN-only
     * product. Pinned qualifying groups are shown first, permanently, then the
     * requested page of the remaining groups, both ordered by the configured
     * mode and each carrying its size class (Lot 5F).
     *
     * @param page the 1-based page requested for the non-pinned groups
     * @return the root level
     */
    public ManualViewData getManualRootData(int page) {
        LOGGER.info("Entering method getManualRootData with page: " + page);
        Map<String, TouchGroupSetting> settings = TouchGroupSetting.byFamilyCode();
        List<ProductFamily> allFamilies = drawnHere(ProductFamily.listAll(), settings);
        Tree tree = readTree();
        Comparator<ProductFamily> order = orderComparator(mode(), settings);
        List<ProductFamily> pinned = new ArrayList<>();
        List<ProductFamily> rest = new ArrayList<>();
        for (ProductFamily f : allFamilies) {
            if (tree.childIds().contains(f.id) || !tree.qualifying().contains(f.id)) {
                continue;
            }
            if (TouchGroupSetting.orDefaults(settings, f.code).pinned) {
                pinned.add(f);
            } else {
                rest.add(f);
            }
        }
        pinned.sort(order);
        rest.sort(order);
        int perPage = pageBudget(pinned.size());
        int totalPages = rest.isEmpty() ? 1 : (rest.size() + perPage - 1) / perPage;
        int current = clamp(page, totalPages);
        List<ManualItem> items = new ArrayList<>();
        for (ProductFamily f : pinned) {
            items.add(categoryItem(f, settings));
        }
        int from = (current - 1) * perPage;
        int to = Math.min(from + perPage, rest.size());
        for (int i = from; i < to; i++) {
            items.add(categoryItem(rest.get(i), settings));
        }
        String prevUrl = current > 1 ? "/manual?page=" + (current - 1) : null;
        String nextUrl = current < totalPages ? "/manual?page=" + (current + 1) : null;
        LOGGER.info("Exiting method getManualRootData");
        return new ManualViewData(items, "Accueil", true, null, current, totalPages, prevUrl, nextUrl);
    }

    /**
     * Builds one category level: the permanently pinned groups first (Lot 5F),
     * then the requested page of the qualifying sub-families ordered by the configured
     * mode, followed by the category's own EAN-only products.
     *
     * <p>Sub-families and products are paged as ONE sequence, sub-families first: a
     * category that holds both must not show its groups on one page and jump to its
     * articles on the next by a rule of its own.
     *
     * <p>Only the page is read from the database. The obvious version iterates
     * {@code family.products} to build the tiles, which loads every article of the
     * category -- 4 174 for the largest of this park -- to display eleven.
     *
     * @param code the family code
     * @param page the 1-based page requested
     * @return the category level
     */
    public ManualViewData getManualCategoryData(String code, int page) {
        LOGGER.info("Entering method getManualCategoryData with code: " + code + ", page: " + page);
        List<ManualItem> items = new ArrayList<>();
        String breadcrumb = "Accueil";
        String parentUrl = "/manual";
        Map<String, TouchGroupSetting> settings = TouchGroupSetting.byFamilyCode();
        List<ProductFamily> allFamilies = drawnHere(ProductFamily.listAll(), settings);
        Tree tree = readTree();
        Comparator<ProductFamily> order = orderComparator(mode(), settings);
        List<ProductFamily> pinned = pinnedTopFamilies(allFamilies, tree, order, settings);
        for (ProductFamily f : pinned) {
            items.add(categoryItem(f, settings));
        }
        ProductFamily family = ProductFamily.findByCode(code);
        if (family == null) {
            LOGGER.info("Exiting method getManualCategoryData");
            return new ManualViewData(items, breadcrumb, false, parentUrl, 1, 1, null, null);
        }
        breadcrumb = "Accueil > " + family.description;
        List<ProductFamily> children = new ArrayList<>();
        if (family.productFamilies != null) {
            // BO-03-01-21/-22/-23: a sub-group scoped elsewhere is no more drawn
            // than a top one — the scope is on the group, not on its rank.
            for (ProductFamily child : drawnHere(family.productFamilies, settings)) {
                if (tree.qualifying().contains(child.id)) {
                    children.add(child);
                }
            }
            children.sort(order);
        }
        long productCount = repository.countProducts(family.id);
        int perPage = pageBudget(pinned.size());
        long total = children.size() + productCount;
        int totalPages = total == 0 ? 1 : (int) ((total + perPage - 1) / perPage);
        int current = clamp(page, totalPages);
        int from = (current - 1) * perPage;
        int to = from + perPage;
        for (int i = Math.min(from, children.size()); i < Math.min(to, children.size()); i++) {
            items.add(categoryItem(children.get(i), settings));
        }
        int productFrom = Math.max(0, from - children.size());
        int productTo = Math.max(0, to - children.size());
        if (productTo > productFrom) {
            for (Product p : repository.products(family.id, productFrom, productTo - productFrom)) {
                items.add(new ManualItem(p.name, false, null, p.ean, "size-normal", false));
            }
        }
        String base = "/manual/cat/" + code + "?page=";
        String prevUrl = current > 1 ? base + (current - 1) : null;
        String nextUrl = current < totalPages ? base + (current + 1) : null;
        LOGGER.info("Exiting method getManualCategoryData");
        return new ManualViewData(items, breadcrumb, false, parentUrl, current, totalPages, prevUrl, nextUrl);
    }

    /**
     * How many tiles one page may hold, once the permanent ones are deducted.
     *
     * <p>The grid is twelve tiles and its first one is always taken -- NON RECONNU at
     * the root, RETOUR below it -- so eleven remain, and the pinned groups shown on
     * every page eat into those. Deducting them is what makes the overflow impossible:
     * before, the configured page size was added ON TOP of the pinned groups, and the
     * surplus tiles fell into rows the grid clips away, invisible and unreachable.
     * At least one tile is always granted, so a till pinned to saturation still turns
     * pages instead of showing nothing.
     *
     * @param pinnedCount how many pinned groups are shown on every page
     * @return the number of tiles the page may hold
     */
    private int pageBudget(int pinnedCount) {
        return Math.max(1, Math.min(perPage(), GRID_TILES - 1 - pinnedCount));
    }


    /**
     * What the screen needs to know about the family tree, read in two queries.
     *
     * @param childIds   the ids of every family that is a sub-family of another, so a
     *                   child is never shown at the root
     * @param qualifying the ids of every family leading, directly or through its
     *                   descendants, to at least one EAN-only product
     */
    private record Tree(Set<Long> childIds, Set<Long> qualifying) {
    }

    /**
     * Keeps only the groups THIS register draws (BO-03-01-21, -22 and -23).
     *
     * <p>A group nothing scoped is drawn, which is how the grid behaved before
     * the scope existed; a group scoped elsewhere is not drawn at all, here or
     * under a parent.
     *
     * @param families the groups to consider
     * @param settings the touch configuration by group code
     * @return the groups this register draws, in the order given
     */
    private List<ProductFamily> drawnHere(List<ProductFamily> families,
                                          Map<String, TouchGroupSetting> settings) {
        String[] scope = registerScope();
        List<ProductFamily> kept = new ArrayList<>();
        for (ProductFamily family : families) {
            if (TouchGroupSetting.orDefaults(settings, family.code)
                    .isDrawnOn(scope[0], scope[1], scope[2])) {
                kept.add(family);
            }
        }
        return kept;
    }

    /**
     * This register's identity as the scope of a group reads it: its terminal,
     * its island and its kind.
     *
     * <p>The island and the kind are READ FROM THE DISTRIBUTED checkout point,
     * which is the back office's own description of this register — the till
     * does not decide what island it is in.
     *
     * @return the terminal, the island code and the kind name, any of them null
     *         when the register cannot say
     */
    private String[] registerScope() {
        String terminalId = ticketNumberService == null
                ? null : ticketNumberService.getTerminalId();
        com.intermarche.pos.domain.store.CheckoutPoint point =
                com.intermarche.pos.domain.store.CheckoutPoint.findByTerminal(terminalId);
        return new String[] {
                terminalId,
                point == null ? null : point.islandCode,
                point == null || point.tpvType == null ? null : point.tpvType.name()};
    }

    /**
     * Reads the shape of the family tree.
     *
     * <p>TWO QUERIES, whatever the size of the catalogue, and not one product entity
     * loaded. The obvious version -- walk the tree in Java and ask each family whether
     * it holds a product -- costs a lazy load per family and, worse, MATERIALISES the
     * whole product collection of a family just to test that it is not empty. On a real
     * store that is thousands of queries and tens of thousands of entities to draw
     * eleven tiles; the demo catalogue was too small to show it.
     *
     * <p>The qualifying set is computed the other way round: the database names the
     * families that DIRECTLY hold an EAN-only product, and the answer is then carried
     * up the parent chain in memory. Walking up stops as soon as an ancestor is already
     * known, which also makes a malformed cycle terminate instead of hanging.
     *
     * @return the child ids and the qualifying ids
     */
    private Tree readTree() {
        Map<Long, Long> parentOf = repository.parentByChild();
        Set<Long> qualifying = new HashSet<>();
        for (Long holder : repository.familiesHoldingProducts()) {
            Long current = holder;
            while (current != null && qualifying.add(current)) {
                current = parentOf.get(current);
            }
        }
        return new Tree(new HashSet<>(parentOf.keySet()), qualifying);
    }

    /**
     * Returns the pinned qualifying top families, ordered — the groups shown
     * permanently whatever the navigation (BO-03-01-07).
     *
     * @param allFamilies every family
     * @param tree the child ids and the qualifying families
     * @param order the configured ordering
     * @param settings the touch configuration by group code
     * @return the ordered pinned top families
     */
    private List<ProductFamily> pinnedTopFamilies(List<ProductFamily> allFamilies, Tree tree,
                                                  Comparator<ProductFamily> order,
                                                  Map<String, TouchGroupSetting> settings) {
        List<ProductFamily> pinned = new ArrayList<>();
        for (ProductFamily f : allFamilies) {
            if (TouchGroupSetting.orDefaults(settings, f.code).pinned
                    && !tree.childIds().contains(f.id) && tree.qualifying().contains(f.id)) {
                pinned.add(f);
            }
        }
        pinned.sort(order);
        return pinned;
    }

    /**
     * Builds a category tile for a family, carrying its size class.
     *
     * @param family the family
     * @param settings the touch configuration by group code
     * @return the category tile
     */
    private ManualItem categoryItem(ProductFamily family,
                                    Map<String, TouchGroupSetting> settings) {
        TouchGroupSetting setting = TouchGroupSetting.orDefaults(settings, family.code);
        return new ManualItem(groupLabel(family, setting), true,
                "/manual/cat/" + family.code, null,
                sizeClass(setting.buttonSize), setting.pinned);
    }

    /**
     * The wording a group touch carries in the language the till is set to
     * (BO-03-01-19).
     *
     * <p>The administered translation wins; the nomenclature's own description
     * is the fallback, which is what every touch carried before translations
     * existed. A service built outside CDI has no language and therefore no
     * translation, and falls back the same way.
     *
     * @param family the group
     * @param setting its touch configuration
     * @return the wording to draw
     */
    private String groupLabel(ProductFamily family, TouchGroupSetting setting) {
        String translated = setting == null || i18n == null
                ? null : setting.translatedLabel(i18n.getLang());
        return translated == null ? family.description : translated;
    }

    /**
     * Maps a family button size to its grid CSS class (BO-03-01-08): a large or
     * small size, or the normal size for anything else.
     *
     * @param buttonSize the administered size, or null
     * @return the size CSS class
     */
    private String sizeClass(String buttonSize) {
        if ("LARGE".equalsIgnoreCase(buttonSize)) {
            return "size-large";
        }
        if ("SMALL".equalsIgnoreCase(buttonSize)) {
            return "size-small";
        }
        return "size-normal";
    }

    /**
     * The configured display-order mode, defaulting to alphabetical when no
     * settings service is wired.
     *
     * @return the order mode
     */
    private String mode() {
        return posSettings == null ? "ALPHA" : posSettings.touchDisplayOrder();
    }

    /**
     * The configured page size, defaulting to {@link #DEFAULT_PER_PAGE} when no
     * settings service is wired.
     *
     * @return the strictly positive page size
     */
    private int perPage() {
        return posSettings == null ? DEFAULT_PER_PAGE : posSettings.touchGroupsPerPage();
    }

    /**
     * Builds the family ordering for a mode: custom rank, sales volume
     * (descending) or alphabetical, each falling back to a case-insensitive
     * description order for ties and for the unknown mode.
     *
     * @param mode the order mode
     * @param settings the touch configuration by group code
     * @return the comparator
     */
    private Comparator<ProductFamily> orderComparator(String mode,
                                                      Map<String, TouchGroupSetting> settings) {
        Comparator<ProductFamily> byDescription = Comparator.comparing(
                f -> f.description == null ? "" : f.description, String.CASE_INSENSITIVE_ORDER);
        if ("CUSTOM".equals(mode)) {
            return Comparator.comparingInt(
                    (ProductFamily f) -> TouchGroupSetting.orDefaults(settings, f.code).displayOrder)
                    .thenComparing(byDescription);
        }
        if ("VOLUME".equals(mode)) {
            return Comparator.comparingLong(
                    (ProductFamily f) -> TouchGroupSetting.orDefaults(settings, f.code).salesVolume)
                    .reversed().thenComparing(byDescription);
        }
        return byDescription;
    }

    /**
     * Clamps a requested page into the valid range for the total page count.
     *
     * @param page the requested 1-based page
     * @param totalPages the total page count
     * @return the page bounded to [1, totalPages]
     */
    private int clamp(int page, int totalPages) {
        if (page < 1) {
            return 1;
        }
        if (page > totalPages) {
            return totalPages;
        }
        return page;
    }

}
