package com.intermarche.pos.domain.catalog;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.util.*;
import java.util.stream.Collectors;
import com.intermarche.pos.domain.BaseEntity;

/**
 * Entity representing a logical grouping of Products (ProductFamily).
 * <p>
 * (Historical note: modeled after the valuation engine's store grouping —
 * the stale cross-project link is gone, the two projects are separate.)
 * In this POS, families primarily drive the FRUITS &amp; LÉGUMES weighing
 * screen; {@code code} is the referential-sync upsert key and {@code flags}
 * carries display hints.
 * Supports a hierarchical (or graph) structure where a ProductFamily can contain:
 * <ul>
 *   <li>A list of {@link Product} entities (Leaves).</li>
 *   <li>A list of other {@link ProductFamily} entities (Sub-families).</li>
 * </ul>
 * <p>
 * <b>Multiple Parents:</b> A family (or a product) can belong to multiple parent families.
 * This requires traversing the structure as a Directed Acyclic Graph (DAG).
 * <p>
 * This entity extends {@link BaseEntity} to inherit ID, versioning, and audit fields.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "product_families",
        uniqueConstraints = @UniqueConstraint(columnNames = "code")
)
@Cacheable
public class ProductFamily extends BaseEntity {

    @Column(name = "code", nullable = false, length = 50, unique = true)
    @NotBlank(message = "Family code is mandatory")
    public String code;

    @Column(name = "description", length = 255)
    public String description;

    /**
     * A comma-separated string of tokens (flags) associated with this family.
     * Example: "ORGANIC,SEASONAL,FRUIT".
     */
    @Column(name = "flags", length = 1000)
    public String flags;

    // --------------------------------------------------
    // Nomenclature placement (BO-02-01-01, BO-02-03-14)
    // --------------------------------------------------

    /**
     * The classification scheme this node belongs to, or null when the group
     * is not part of one.
     * <p>
     * Null is a real case and not a defect: the direct-entry grid lets a shop
     * build its own groups, which classify nothing and come from no commercial
     * system. A node WITH a scheme is a nomenclature node and answers to its
     * rules — a code of a declared length, one parent, a named level.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "nomenclature_id")
    public Nomenclature nomenclature;

    /**
     * The 0-based level of this node inside its scheme — 0 for an activité,
     * 3 for a sous-famille in the Intermarché scheme — or null when the node
     * belongs to no scheme.
     * <p>
     * Held on the node rather than derived at every read, because it is what
     * the published hierarchy file states: its {@code niveau} column is the
     * source, the code length only confirms it. Reports select a level by it
     * ("rupture par rayon"), and an importer validates the code length against
     * it.
     */
    @Column(name = "nomenclature_level")
    public Integer level;

    // --------------------------------------------------
    // Relations: Direct Products
    // --------------------------------------------------

    /**
     * The list of products directly belonging to this family.
     * Unidirectional relationship via a foreign key in the 'products' table.
     */
    @ManyToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    public Set<Product> products = new HashSet<>();

    // --------------------------------------------------
    // Relations: Child Families (Hierarchy)
    // --------------------------------------------------

    /**
     * The sub-families contained within this family — the NAVIGATION edges.
     * <p>
     * A JOIN TABLE, not a foreign key on the child, because a group may hang
     * under several: the direct-entry grid lets a shop show one group under
     * two others, and {@code RefPayloads.FamilyDto} has always carried
     * {@code parentCodes} as a list. With a single
     * {@code parent_product_family_id} column the second parent silently
     * overwrote the first at every pull.
     * <p>
     * These edges are NOT what places a node in a nomenclature. Inside a
     * scheme the parent is read off the code — see {@link #parentCode()} —
     * and is therefore unique by construction. The two structures coexist on
     * the same rows: {@link #nomenclature} says which one applies.
     * <p>
     * No cascade: a child reachable from two parents must not be deleted with
     * either of them.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "product_family_children",
            joinColumns = @JoinColumn(name = "parent_id"),
            inverseJoinColumns = @JoinColumn(name = "child_id"))
    public Set<ProductFamily> productFamilies = new HashSet<>();

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds a ProductFamily by its unique code.
     *
     * @param code The family code.
     * @return The ProductFamily entity or null if not found.
     */
    public static ProductFamily findByCode(String code) {
        return find("code", code).firstResult();
    }

    /**
     * The code of this node's parent inside its own scheme, truncated off its
     * own code.
     *
     * @return the parent's code, or null for a top node or a node in no scheme
     */
    public String parentCode() {
        return nomenclature == null ? null : nomenclature.parentCodeOf(code);
    }

    /**
     * Lists the nodes of one scheme sitting at one level, in code order.
     *
     * @param nomenclature the scheme, possibly null
     * @param level the 0-based level
     * @return the nodes at that level, empty when the scheme is null
     */
    public static List<ProductFamily> listAtLevel(Nomenclature nomenclature, int level) {
        if (nomenclature == null || nomenclature.id == null) {
            return List.of();
        }
        return list("nomenclature.id = ?1 and level = ?2 order by code",
                nomenclature.id, level);
    }

    /**
     * Lists every node of one scheme, top level first then by code — the order
     * the level-by-level display needs (BO-02-01-08).
     *
     * @param nomenclature the scheme, possibly null
     * @return the nodes of the scheme, empty when the scheme is null
     */
    public static List<ProductFamily> listInNomenclature(Nomenclature nomenclature) {
        if (nomenclature == null || nomenclature.id == null) {
            return List.of();
        }
        return list("nomenclature.id = ?1 order by level, code", nomenclature.id);
    }

    /**
     * Whether this node's code is well formed for its scheme AND matches the
     * level it claims.
     * <p>
     * Both halves matter and neither implies the other: a code of the right
     * length placed at the wrong level would put a famille among the rayons,
     * and a code of the wrong length belongs nowhere. A node in no scheme is
     * consistent by definition — nothing constrains it.
     *
     * @return true when the node is consistent with its scheme
     */
    public boolean isConsistentWithNomenclature() {
        if (nomenclature == null) {
            return true;
        }
        NomenclatureLevel placed = nomenclature.levelOfCode(code);
        return placed != null && level != null && placed.rank == level;
    }

    /**
     * Returns the family whose code the sale line keeps — the article's place
     * in the NOMENCLATURE when it has one.
     * <p>
     * A product hangs under several groups at once: the nomenclature node the
     * gestion commerciale filed it under, and any number of touch groups a
     * shop built for its own grid. Those are not the same kind of thing, and
     * the arbitration is now written rather than declined: a node belonging to
     * a scheme wins, because that is the one the VAT and sales reports break
     * on (BO-06-07-01, BO-06-04-01). Between two nodes of ONE scheme the
     * deepest wins — the sous-famille says more than the rayon it hangs under,
     * and the rayon is its own prefix anyway, so nothing is lost.
     * <p>
     * Outside any scheme the old rule stands: a single group is the answer,
     * several is silence. Picking among touch groups would be inventing a
     * rule, and the line snapshot is permanent, so the invented rule would be
     * frozen into every consolidated line.
     * <p>
     * Returns null when the product is null, unpersisted, or attached to
     * nothing (campaign lot C3 / BO-04-01-11).
     *
     * @param product the sold product
     * @return the family the line records, or null when there is none
     */
    public static ProductFamily findDirectFamily(Product product) {
        if (product == null || product.id == null) {
            return null;
        }
        List<ProductFamily> direct = ProductFamily.<ProductFamily>find(
                "select pf from ProductFamily pf join pf.products p where p.id = ?1",
                product.id
        ).list();
        ProductFamily classified = null;
        for (ProductFamily candidate : direct) {
            if (candidate.nomenclature == null) {
                continue;
            }
            if (classified == null || deeper(candidate, classified)) {
                classified = candidate;
            }
        }
        if (classified != null) {
            return classified;
        }
        return direct.size() == 1 ? direct.get(0) : null;
    }

    /**
     * Whether a node sits deeper than another, a null level counting as the
     * top so an unplaced node never wins over a placed one.
     *
     * @param candidate the node being considered
     * @param incumbent the node currently held
     * @return true when the candidate is the deeper of the two
     */
    private static boolean deeper(ProductFamily candidate, ProductFamily incumbent) {
        int candidateLevel = candidate.level == null ? -1 : candidate.level;
        int incumbentLevel = incumbent.level == null ? -1 : incumbent.level;
        return candidateLevel > incumbentLevel;
    }

    /**
     * Files an article under its place in a nomenclature (BO-02-03-14).
     * <p>
     * The article feed names the node, and the membership is what makes the
     * classification REACH the register: the sale line keeps the family of the
     * article it sold, and {@link #findDirectFamily(Product)} reads it off this
     * collection. Administering a nomenclature that no article belongs to
     * changes nothing at the till.
     * <p>
     * The article is detached from every OTHER node of the same scheme first.
     * A scheme is a partition — one place per article — and an article moved
     * from one sous-famille to another must not end up in both, or the VAT
     * state would count it twice. Touch groups are left alone: they are not a
     * classification and an article may sit in as many as the shop wants.
     *
     * @param product the article being filed, possibly null
     * @param nodeCode the code of the node it belongs to, possibly blank
     * @return true when the article is now filed under that node
     */
    public static boolean fileUnderNomenclature(Product product, String nodeCode) {
        if (product == null || nodeCode == null || nodeCode.isBlank()) {
            return false;
        }
        ProductFamily node = findByCode(nodeCode.trim());
        if (node == null || node.nomenclature == null) {
            return false;
        }
        List<ProductFamily> holding = ProductFamily.<ProductFamily>find(
                "select pf from ProductFamily pf join pf.products p where p.id = ?1",
                product.id).list();
        for (ProductFamily other : holding) {
            if (other.nomenclature != null && !other.code.equals(node.code)
                    && other.nomenclature.id != null
                    && other.nomenclature.id.equals(node.nomenclature.id)) {
                other.products.remove(product);
                other.persist();
            }
        }
        if (!node.products.contains(product)) {
            node.products.add(product);
            node.persist();
        }
        return true;
    }

    /**
     * Retrieves all parent ProductFamilies for a given Product, traversing up the hierarchy.
     * <p>
     * This method supports graphs where a family can have multiple parents.
     * It performs a Depth-First Search (DFS) to find all ancestors.
     *
     * @param product The product to search for.
     * @return A Set of unique {@link ProductFamily}.
     *         Returns an empty Set if the product belongs to no family or if the product is null.
     */
    public static Set<ProductFamily> findAllFamiliesForProduct(Product product) {
        Set<ProductFamily> hierarchy = new HashSet<>();
        if (product == null || product.id == null) {
            return hierarchy;
        }

        // 1. Find all direct parents of the Product
        // Query: Select ProductFamily where the collection 'products' contains the specific product ID
        List<ProductFamily> directParents = find(
                "select pf from ProductFamily pf join pf.products p where p.id = ?1",
                product.id
        ).list();

        // 2. Recursively find all parent families for each direct parent
        for (ProductFamily parent : directParents) {
            findAncestorsRecursive(parent, hierarchy);
        }

        return hierarchy;
    }

    /**
     * Checks if a specific flag token is present in any of the families (direct or ancestor)
     * associated with the given product.
     *
     * @param product The product to check.
     * @param flag    The flag token to search for (case-sensitive).
     * @return true if the flag is found in the hierarchy of the product, false otherwise.
     */
    public static boolean productHasFlag(Product product, String flag) {
        if (product == null || flag == null || flag.isBlank()) {
            return false;
        }

        // Retrieve all families in the hierarchy
        Set<ProductFamily> families = findAllFamiliesForProduct(product);

        // Iterate through families to find the flag
        for (ProductFamily family : families) {
            if (family.hasFlag(flag)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Recursive helper to find all ancestors of a specific family.
     * Handles cycles and multiple parents.
     *
     * @param family    The family whose parents we are looking for.
     * @param ancestors The accumulating set of ancestor families to avoid duplicates.
     */
    static void findAncestorsRecursive(ProductFamily family, Set<ProductFamily> ancestors) {
        // Stop if null, already visited, or missing ID
        if (ancestors.contains(family)) {
            return;
        }

        // Add current family to ancestors
        ancestors.add(family);

        // Find all parents of the current family
        // Query: Select Parent ProductFamily where the collection 'productFamilies' contains the current family
        List<ProductFamily> parents = find(
                "select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1",
                family.id
        ).list();

        // Recurse for each parent found (handling multiple branches)
        for (ProductFamily parent : parents) {
            findAncestorsRecursive(parent, ancestors);
        }
    }

    // --------------------------------------------------
    // Flag Management Methods
    // --------------------------------------------------

    /**
     * Adds a token to the flags string if it is not already present.
     * <p>
     * The token is trimmed before being added. The flags string is updated automatically.
     *
     * @param token The token to add.
     */
    public void addFlag(String token) {
        if (token == null || token.isBlank()) {
            return;
        }

        Set<String> currentFlags = getFlagsSet();
        String trimmedToken = token.trim();

        // Set.add returns true if the set did not already contain the element
        if (currentFlags.add(trimmedToken)) {
            updateFlagsFromSet(currentFlags);
        }
    }


    /**
     * Checks if a specific token is present in the flags string.
     * <p>
     * The check is case-sensitive and ignores leading/trailing whitespace in both the stored flags and the query token.
     *
     * @param token The token to check for.
     * @return true if the token is present, false otherwise.
     */
    public boolean hasFlag(String token) {
        if (token == null || token.isBlank() || this.flags == null || this.flags.isBlank()) {
            return false;
        }
        return getFlagsSet().contains(token.trim());
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Helper method to parse the comma-separated flags string into a Set.
     * A null or blank flags string parses as the empty set, so the first
     * {@link #addFlag(String)} on a freshly created family works instead of
     * faulting on the null field.
     *
     * @return A mutable Set of trimmed flag strings.
     */
    private Set<String> getFlagsSet() {
        if (this.flags == null || this.flags.isBlank()) {
            return new java.util.HashSet<>();
        }
        return Arrays.stream(this.flags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * Helper method to update the 'flags' string from a Set.
     *
     * @param flagSet The set of flags to serialize.
     */
    private void updateFlagsFromSet(Set<String> flagSet) {
        this.flags = String.join(",", flagSet);
    }

    @Override
    public int getChecksum() {
        // Excluding children from checksum for performance and stability.
        return Objects.hash(code, description, flags);
    }
}