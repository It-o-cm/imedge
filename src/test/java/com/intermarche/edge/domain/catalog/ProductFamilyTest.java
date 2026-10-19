package com.intermarche.edge.domain.catalog;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProductFamily}, targeting 100% branch coverage.
 * <p>
 * The four static finders resolve the Panache {@code find} query, which under
 * plain {@code mvn test} falls back to {@link PanacheEntityBase}, so they are
 * intercepted with {@link org.mockito.Mockito#mockStatic} together with a mock
 * {@link PanacheQuery}. Instance methods (flag management, checksum) and the
 * package-private recursion helper are exercised directly on plain instances;
 * every compound guard, ternary and short-circuit is covered on both arms.
 * Each test is fully isolated and asserts absolute expected values.
 */
class ProductFamilyTest {

    /**
     * Builds a ProductFamily with the given id and flags string, leaving the
     * remaining fields at their declared defaults.
     *
     * @param id    the entity id, possibly null
     * @param flags the comma-separated flags string, possibly null
     * @return the configured family
     */
    private ProductFamily family(Long id, String flags) {
        ProductFamily f = new ProductFamily();
        f.id = id;
        f.flags = flags;
        return f;
    }

    /**
     * Builds a plain Product with the given id.
     *
     * @param id the product id, possibly null
     * @return the configured product
     */
    private Product product(Long id) {
        Product p = new Product();
        p.id = id;
        return p;
    }

    /**
     * A fresh family exposes empty, non-null child collections.
     */
    @Test
    void fieldDefaults() {
        ProductFamily f = new ProductFamily();
        Assertions.assertNotNull(f.products);
        Assertions.assertTrue(f.products.isEmpty());
        Assertions.assertNotNull(f.productFamilies);
        Assertions.assertTrue(f.productFamilies.isEmpty());
    }

    /**
     * findByCode returns the single result of the Panache code query.
     */
    @Test
    void findByCodeReturnsFirstResult() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ProductFamily expected = family(1L, null);
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> ProductFamily.find("code", "FRUITS")).thenReturn(query);
            Assertions.assertSame(expected, ProductFamily.findByCode("FRUITS"));
        }
    }

    /**
     * findDirectFamily returns null for a null product (first OR arm true),
     * without hitting the database.
     */
    /**
     * {@code addFlag} on a freshly created family (null flags string) creates
     * the flag instead of faulting: the null-flags arm of {@code getFlagsSet}
     * parses as the empty set. This is the seed's first gesture on the
     * Fruits &amp; Légumes aisle roots.
     */
    @Test
    void addFlagOnNullFlagsCreatesTheFlag() {
        ProductFamily family = new ProductFamily();
        family.addFlag("FRUITS_VEGETABLES");
        Assertions.assertEquals("FRUITS_VEGETABLES", family.flags);
        Assertions.assertTrue(family.hasFlag("FRUITS_VEGETABLES"));
    }

    @Test
    void findDirectFamilyNullProduct() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            Assertions.assertNull(ProductFamily.findDirectFamily(null));
            panache.verifyNoInteractions();
        }
    }

    /**
     * findDirectFamily returns null for an unpersisted product (first OR arm
     * false, second OR arm true), without hitting the database.
     */
    @Test
    void findDirectFamilyNullId() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            Assertions.assertNull(ProductFamily.findDirectFamily(product(null)));
            panache.verifyNoInteractions();
        }
    }

    /**
     * findDirectFamily returns the direct family when the product has exactly
     * one (both guard arms false, single-family arm).
     */
    @Test
    void findDirectFamilyReturnsTheSingleFamily() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ProductFamily expected = family(10L, null);
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of(expected));
            panache.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 7L))
                    .thenReturn(query);
            Assertions.assertSame(expected, ProductFamily.findDirectFamily(product(7L)));
        }
    }

    /**
     * findDirectFamily returns null when the product is attached to SEVERAL
     * direct families: no rule says which one names the sale, so the line
     * snapshot is left blank rather than frozen on an invented choice.
     */
    @Test
    void findDirectFamilyReturnsNullWhenAmbiguous() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of(family(10L, null), family(11L, null)));
            panache.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 7L))
                    .thenReturn(query);
            Assertions.assertNull(ProductFamily.findDirectFamily(product(7L)));
        }
    }

    /**
     * findDirectFamily returns null when the product is attached to no family
     * (empty list): the snapshot leaves the line's family blank.
     */
    @Test
    void findDirectFamilyNoFamily() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of());
            panache.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 7L))
                    .thenReturn(query);
            Assertions.assertNull(ProductFamily.findDirectFamily(product(7L)));
        }
    }

    /**
     * findAllFamiliesForProduct returns an empty set for a null product
     * (first OR arm true).
     */
    @Test
    void findAllFamiliesForProductNull() {
        Set<ProductFamily> result = ProductFamily.findAllFamiliesForProduct(null);
        Assertions.assertTrue(result.isEmpty());
    }

    /**
     * findAllFamiliesForProduct returns an empty set for a product without an
     * id (first OR arm false, second OR arm true).
     */
    @Test
    void findAllFamiliesForProductNullId() {
        Set<ProductFamily> result = ProductFamily.findAllFamiliesForProduct(product(null));
        Assertions.assertTrue(result.isEmpty());
    }

    /**
     * findAllFamiliesForProduct returns an empty set when the product has no
     * direct parents (both guard arms false, for loop not entered).
     */
    @Test
    void findAllFamiliesForProductNoDirectParents() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of());
            panache.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 1L))
                    .thenReturn(query);
            Set<ProductFamily> result = ProductFamily.findAllFamiliesForProduct(product(1L));
            Assertions.assertTrue(result.isEmpty());
        }
    }

    /**
     * findAllFamiliesForProduct walks up the hierarchy, collecting direct
     * parents and their ancestors (for loop entered, recursion followed).
     */
    @Test
    void findAllFamiliesForProductWithHierarchy() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ProductFamily direct = family(10L, null);
            ProductFamily grandParent = family(20L, null);
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> directQuery = mock(PanacheQuery.class);
            when(directQuery.list()).thenReturn(List.of(direct));
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> parentsOfDirect = mock(PanacheQuery.class);
            when(parentsOfDirect.list()).thenReturn(List.of(grandParent));
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> parentsOfGrand = mock(PanacheQuery.class);
            when(parentsOfGrand.list()).thenReturn(List.of());
            panache.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 1L))
                    .thenReturn(directQuery);
            panache.when(() -> ProductFamily.find(
                    "select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1", 10L))
                    .thenReturn(parentsOfDirect);
            panache.when(() -> ProductFamily.find(
                    "select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1", 20L))
                    .thenReturn(parentsOfGrand);
            Set<ProductFamily> result = ProductFamily.findAllFamiliesForProduct(product(1L));
            Assertions.assertEquals(Set.of(direct, grandParent), result);
        }
    }

    /**
     * findAncestorsRecursive returns immediately and leaves the accumulator
     * untouched when the family is already present (contains arm true).
     */
    @Test
    void findAncestorsRecursiveAlreadyVisited() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ProductFamily f = family(10L, null);
            Set<ProductFamily> ancestors = new HashSet<>();
            ancestors.add(f);
            ProductFamily.findAncestorsRecursive(f, ancestors);
            Assertions.assertEquals(Set.of(f), ancestors);
            panache.verifyNoInteractions();
        }
    }

    /**
     * findAncestorsRecursive adds a family with no parents (contains arm
     * false, for loop not entered).
     */
    @Test
    void findAncestorsRecursiveNoParents() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ProductFamily f = family(10L, null);
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of());
            panache.when(() -> ProductFamily.find(
                    "select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1", 10L))
                    .thenReturn(query);
            Set<ProductFamily> ancestors = new HashSet<>();
            ProductFamily.findAncestorsRecursive(f, ancestors);
            Assertions.assertEquals(Set.of(f), ancestors);
        }
    }

    /**
     * findAncestorsRecursive recurses into each parent (contains arm false,
     * for loop entered).
     */
    @Test
    void findAncestorsRecursiveWithParents() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ProductFamily child = family(10L, null);
            ProductFamily parent = family(20L, null);
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> parentsOfChild = mock(PanacheQuery.class);
            when(parentsOfChild.list()).thenReturn(List.of(parent));
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> parentsOfParent = mock(PanacheQuery.class);
            when(parentsOfParent.list()).thenReturn(List.of());
            panache.when(() -> ProductFamily.find(
                    "select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1", 10L))
                    .thenReturn(parentsOfChild);
            panache.when(() -> ProductFamily.find(
                    "select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1", 20L))
                    .thenReturn(parentsOfParent);
            Set<ProductFamily> ancestors = new HashSet<>();
            ProductFamily.findAncestorsRecursive(child, ancestors);
            Assertions.assertEquals(Set.of(child, parent), ancestors);
        }
    }

    /**
     * productHasFlag returns false for a null product (first OR arm true).
     */
    @Test
    void productHasFlagNullProduct() {
        Assertions.assertFalse(ProductFamily.productHasFlag(null, "ORGANIC"));
    }

    /**
     * productHasFlag returns false for a null flag (second OR arm true).
     */
    @Test
    void productHasFlagNullFlag() {
        Assertions.assertFalse(ProductFamily.productHasFlag(product(1L), null));
    }

    /**
     * productHasFlag returns false for a blank flag (third OR arm true).
     */
    @Test
    void productHasFlagBlankFlag() {
        Assertions.assertFalse(ProductFamily.productHasFlag(product(1L), "   "));
    }

    /**
     * productHasFlag returns true when a family in the hierarchy carries the
     * flag (guard arms false, inner if arm true).
     */
    @Test
    void productHasFlagFound() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ProductFamily direct = family(10L, "ORGANIC");
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> directQuery = mock(PanacheQuery.class);
            when(directQuery.list()).thenReturn(List.of(direct));
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> parentsQuery = mock(PanacheQuery.class);
            when(parentsQuery.list()).thenReturn(List.of());
            panache.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 1L))
                    .thenReturn(directQuery);
            panache.when(() -> ProductFamily.find(
                    "select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1", 10L))
                    .thenReturn(parentsQuery);
            Assertions.assertTrue(ProductFamily.productHasFlag(product(1L), "ORGANIC"));
        }
    }

    /**
     * productHasFlag returns false when no family in the hierarchy carries the
     * flag (inner if arm false, loop exhausted).
     */
    @Test
    void productHasFlagNotFound() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ProductFamily direct = family(10L, "SEASONAL");
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> directQuery = mock(PanacheQuery.class);
            when(directQuery.list()).thenReturn(List.of(direct));
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> parentsQuery = mock(PanacheQuery.class);
            when(parentsQuery.list()).thenReturn(List.of());
            panache.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 1L))
                    .thenReturn(directQuery);
            panache.when(() -> ProductFamily.find(
                    "select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1", 10L))
                    .thenReturn(parentsQuery);
            Assertions.assertFalse(ProductFamily.productHasFlag(product(1L), "ORGANIC"));
        }
    }

    /**
     * addFlag ignores a null token (first OR arm true).
     */
    @Test
    void addFlagNullToken() {
        ProductFamily f = family(1L, "A");
        f.addFlag(null);
        Assertions.assertEquals("A", f.flags);
    }

    /**
     * addFlag ignores a blank token (second OR arm true).
     */
    @Test
    void addFlagBlankToken() {
        ProductFamily f = family(1L, "A");
        f.addFlag("   ");
        Assertions.assertEquals("A", f.flags);
    }

    /**
     * addFlag appends a new, trimmed token (guard false, add arm true).
     */
    @Test
    void addFlagNewToken() {
        ProductFamily f = family(1L, "EXISTING");
        f.addFlag("  NEW  ");
        Assertions.assertEquals(Set.of("EXISTING", "NEW"),
                Set.of(f.flags.split(",")));
    }

    /**
     * addFlag on a family whose flags string is present but blank parses it as
     * the empty set: this reaches the {@code isBlank()} arm of getFlagsSet
     * (flags non-null, {@code isBlank()} true), the arm that hasFlag's own guard
     * short-circuits before ever calling getFlagsSet.
     */
    @Test
    void addFlagOnBlankFlagsCreatesTheFlag() {
        ProductFamily f = family(1L, "   ");
        f.addFlag("NEW");
        Assertions.assertEquals("NEW", f.flags);
    }

    /**
     * addFlag is a no-op when the token is already present (add arm false).
     */
    @Test
    void addFlagExistingToken() {
        ProductFamily f = family(1L, "EXISTING");
        f.addFlag("EXISTING");
        Assertions.assertEquals("EXISTING", f.flags);
    }





    /**
     * hasFlag returns false for a null token (first OR arm true).
     */
    @Test
    void hasFlagNullToken() {
        Assertions.assertFalse(family(1L, "A").hasFlag(null));
    }

    /**
     * hasFlag returns false for a blank token (second OR arm true).
     */
    @Test
    void hasFlagBlankToken() {
        Assertions.assertFalse(family(1L, "A").hasFlag("   "));
    }

    /**
     * hasFlag returns false when the flags string is null (third OR arm true).
     */
    @Test
    void hasFlagNullFlags() {
        Assertions.assertFalse(family(1L, null).hasFlag("A"));
    }

    /**
     * hasFlag returns false when the flags string is blank (fourth OR arm true).
     */
    @Test
    void hasFlagBlankFlags() {
        Assertions.assertFalse(family(1L, "   ").hasFlag("A"));
    }

    /**
     * hasFlag returns true for a present token, ignoring empty tokens in the
     * stored string (all guard arms false, filter both arms, contains true).
     */
    @Test
    void hasFlagPresent() {
        Assertions.assertTrue(family(1L, "A,,B").hasFlag(" A "));
    }

    /**
     * hasFlag returns false for an absent token (contains false).
     */
    @Test
    void hasFlagAbsent() {
        Assertions.assertFalse(family(1L, "A,B").hasFlag("C"));
    }

    /**
     * getChecksum hashes code, description and flags, excluding children.
     */
    @Test
    void getChecksum() {
        ProductFamily f = new ProductFamily();
        f.code = "FRUITS";
        f.description = "Fruits and vegetables";
        f.flags = "ORGANIC";
        Assertions.assertEquals(Objects.hash("FRUITS", "Fruits and vegetables", "ORGANIC"),
                f.getChecksum());
    }
    // --------------------------------------------------
    // Nomenclature placement (BO-02-01-01, BO-02-03-14)
    // --------------------------------------------------

    /**
     * Builds a scheme carrying the real Intermarché levels, served through the
     * given static mock.
     *
     * @param mocked the active PanacheEntityBase static mock
     * @return the scheme
     */
    private Nomenclature intermarche(MockedStatic<PanacheEntityBase> mocked) {
        Nomenclature scheme = new Nomenclature();
        scheme.id = 1L;
        scheme.code = "ITM_FR";
        NomenclatureLevel activite = new NomenclatureLevel();
        activite.rank = 0;
        activite.label = "Activité";
        activite.codeLength = 2;
        NomenclatureLevel rayon = new NomenclatureLevel();
        rayon.rank = 1;
        rayon.label = "Rayon";
        rayon.codeLength = 4;
        NomenclatureLevel famille = new NomenclatureLevel();
        famille.rank = 2;
        famille.label = "Famille";
        famille.codeLength = 8;
        mocked.when(() -> NomenclatureLevel.list("nomenclature.id = ?1 order by rank", 1L))
                .thenReturn(List.of(activite, rayon, famille));
        return scheme;
    }

    /**
     * Builds a node of a scheme.
     *
     * @param scheme the scheme, possibly null
     * @param code the node code
     * @param level the declared level, possibly null
     * @return the node
     */
    private ProductFamily node(Nomenclature scheme, String code, Integer level) {
        ProductFamily family = new ProductFamily();
        family.code = code;
        family.nomenclature = scheme;
        family.level = level;
        return family;
    }

    /**
     * A node of a scheme names its parent by truncating its own code, at every
     * depth of the real Intermarché coding.
     */
    @Test
    void aNodeNamesItsParentByTruncatingItsCode() {
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            Nomenclature itm = intermarche(mocked);
            assertNull(node(itm, "10", 0).parentCode());
            assertEquals("10", node(itm, "1002", 1).parentCode());
            assertEquals("1002", node(itm, "10020200", 2).parentCode());
        }
    }

    /**
     * A group belonging to no scheme names no parent that way: its edges are
     * the navigation join table, not a code prefix. This is the null arm, and
     * it is a normal case — a shop's own touch group classifies nothing.
     */
    @Test
    void aGroupOutsideAnySchemeNamesNoParentByCode() {
        ProductFamily loose = new ProductFamily();
        loose.code = "PROMO";
        assertNull(loose.parentCode());
        assertTrue(loose.isConsistentWithNomenclature());
    }

    /**
     * A node is consistent when its code length AND its declared level agree.
     * Both legs are exercised apart: a right length at the wrong level would
     * file a famille among the rayons, a wrong length belongs nowhere, and a
     * missing level says nothing at all.
     */
    @Test
    void consistencyNeedsTheLengthAndTheLevelToAgree() {
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            Nomenclature itm = intermarche(mocked);
            assertTrue(node(itm, "1002", 1).isConsistentWithNomenclature());
            assertFalse(node(itm, "1002", 2).isConsistentWithNomenclature());
            assertFalse(node(itm, "100", 1).isConsistentWithNomenclature());
            assertFalse(node(itm, "1002", null).isConsistentWithNomenclature());
        }
    }

    /**
     * The level-by-level readers refuse a null or unpersisted scheme rather
     * than querying on a null key — the display of BO-02-01-08 is built from
     * them and must not fail on a shop that has no scheme yet.
     */
    @Test
    void theLevelReadersRefuseASchemeThatCannotBeQueried() {
        assertEquals(List.of(), ProductFamily.listAtLevel(null, 0));
        assertEquals(List.of(), ProductFamily.listAtLevel(new Nomenclature(), 0));
        assertEquals(List.of(), ProductFamily.listInNomenclature(null));
        assertEquals(List.of(), ProductFamily.listInNomenclature(new Nomenclature()));
    }

    /**
     * The nodes of one level, and the whole scheme top-down, are read through
     * the scheme's id.
     */
    @Test
    void theNodesOfASchemeAreReadThroughItsId() {
        Nomenclature scheme = new Nomenclature();
        scheme.id = 1L;
        List<ProductFamily> atLevel = List.of(node(scheme, "1002", 1));
        List<ProductFamily> whole = List.of(node(scheme, "10", 0), node(scheme, "1002", 1));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.list(
                    "nomenclature.id = ?1 and level = ?2 order by code", 1L, 1))
                    .thenReturn(atLevel);
            mocked.when(() -> ProductFamily.list(
                    "nomenclature.id = ?1 order by level, code", 1L)).thenReturn(whole);
            assertSame(atLevel, ProductFamily.listAtLevel(scheme, 1));
            assertSame(whole, ProductFamily.listInNomenclature(scheme));
        }
    }

    /**
     * Builds a nomenclature-classified family carrying the given level, so that
     * {@link ProductFamily#findDirectFamily(Product)} treats it as a scheme node
     * and {@code deeper} reads its level.
     *
     * @param level the declared level, possibly null
     * @return the classified family
     */
    private ProductFamily classified(Integer level) {
        ProductFamily f = new ProductFamily();
        f.nomenclature = new Nomenclature();
        f.level = level;
        return f;
    }

    /**
     * Builds a spied family whose {@code persist()} is neutralized, for the paths
     * of {@link ProductFamily#fileUnderNomenclature(Product, String)} that write.
     *
     * @param scheme the scheme, possibly null
     * @param code the node code
     * @return the persist-safe spied family
     */
    private ProductFamily heldNode(Nomenclature scheme, String code) {
        ProductFamily f = spy(new ProductFamily());
        f.code = code;
        f.nomenclature = scheme;
        doNothing().when(f).persist();
        return f;
    }

    /**
     * findDirectFamily returns the sole classified node when the product hangs
     * under one scheme node (nomenclature non-null, classified was null, and the
     * classified != null return arm is taken).
     */
    @Test
    void findDirectFamilyReturnsTheSingleClassifiedNode() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ProductFamily scheme = classified(1);
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of(scheme));
            panache.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 7L))
                    .thenReturn(query);
            Assertions.assertSame(scheme, ProductFamily.findDirectFamily(product(7L)));
        }
    }

    /**
     * findDirectFamily keeps the DEEPER of two classified nodes of a scheme
     * (classified != null on the second candidate, deeper returns true).
     */
    @Test
    void findDirectFamilyKeepsTheDeeperClassifiedNode() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ProductFamily rayon = classified(1);
            ProductFamily sousFamille = classified(2);
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of(rayon, sousFamille));
            panache.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 7L))
                    .thenReturn(query);
            Assertions.assertSame(sousFamille, ProductFamily.findDirectFamily(product(7L)));
        }
    }

    /**
     * findDirectFamily keeps the incumbent when the next classified node is not
     * deeper (deeper returns false, both level ternaries on their non-null arm).
     */
    @Test
    void findDirectFamilyKeepsTheIncumbentWhenNotDeeper() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ProductFamily sousFamille = classified(2);
            ProductFamily rayon = classified(1);
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of(sousFamille, rayon));
            panache.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 7L))
                    .thenReturn(query);
            Assertions.assertSame(sousFamille, ProductFamily.findDirectFamily(product(7L)));
        }
    }

    /**
     * findDirectFamily treats a null level as the top: two classified nodes both
     * lacking a level tie, so the incumbent stays (both level ternaries on their
     * null arm, deeper returns false on -1 &gt; -1).
     */
    @Test
    void findDirectFamilyTreatsNullLevelAsTop() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ProductFamily first = classified(null);
            ProductFamily second = classified(null);
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of(first, second));
            panache.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 7L))
                    .thenReturn(query);
            Assertions.assertSame(first, ProductFamily.findDirectFamily(product(7L)));
        }
    }

    /**
     * fileUnderNomenclature refuses a null product (first OR arm true), touching
     * no query.
     */
    @Test
    void fileUnderNomenclatureNullProduct() {
        Assertions.assertFalse(ProductFamily.fileUnderNomenclature(null, "N1"));
    }

    /**
     * fileUnderNomenclature refuses a null node code (second OR arm true).
     */
    @Test
    void fileUnderNomenclatureNullNodeCode() {
        Assertions.assertFalse(ProductFamily.fileUnderNomenclature(product(1L), null));
    }

    /**
     * fileUnderNomenclature refuses a blank node code (third OR arm true).
     */
    @Test
    void fileUnderNomenclatureBlankNodeCode() {
        Assertions.assertFalse(ProductFamily.fileUnderNomenclature(product(1L), "   "));
    }

    /**
     * fileUnderNomenclature refuses an unknown node code (node == null true arm),
     * the trimmed code being used for the lookup.
     */
    @Test
    void fileUnderNomenclatureNodeNotFound() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> codeQuery = mock(PanacheQuery.class);
            when(codeQuery.firstResult()).thenReturn(null);
            panache.when(() -> ProductFamily.find("code", "N1")).thenReturn(codeQuery);
            Assertions.assertFalse(ProductFamily.fileUnderNomenclature(product(1L), " N1 "));
        }
    }

    /**
     * fileUnderNomenclature refuses a node that belongs to no scheme (node !=
     * null false, node.nomenclature == null true), returning before any write.
     */
    @Test
    void fileUnderNomenclatureNodeHasNoScheme() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ProductFamily node = new ProductFamily();
            node.code = "N1";
            node.nomenclature = null;
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> codeQuery = mock(PanacheQuery.class);
            when(codeQuery.firstResult()).thenReturn(node);
            panache.when(() -> ProductFamily.find("code", "N1")).thenReturn(codeQuery);
            Assertions.assertFalse(ProductFamily.fileUnderNomenclature(product(1L), "N1"));
        }
    }

    /**
     * fileUnderNomenclature files an article not yet held under the node: the
     * holding is empty (loop not entered) and the contains guard is false, so the
     * article is added and the node persisted.
     */
    @Test
    void fileUnderNomenclatureFilesWhenNotYetHeld() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            Nomenclature scheme = new Nomenclature();
            scheme.id = 1L;
            ProductFamily node = heldNode(scheme, "N1");
            Product article = product(7L);
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> codeQuery = mock(PanacheQuery.class);
            when(codeQuery.firstResult()).thenReturn(node);
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> holdingQuery = mock(PanacheQuery.class);
            when(holdingQuery.list()).thenReturn(List.of());
            panache.when(() -> ProductFamily.find("code", "N1")).thenReturn(codeQuery);
            panache.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 7L))
                    .thenReturn(holdingQuery);
            Assertions.assertTrue(ProductFamily.fileUnderNomenclature(article, "N1"));
            Assertions.assertTrue(node.products.contains(article));
            verify(node).persist();
        }
    }

    /**
     * fileUnderNomenclature is a no-op on the node when the article is already
     * held there (contains guard true, negated to false): the node is not
     * persisted again but the call still reports success.
     */
    @Test
    void fileUnderNomenclatureAlreadyHeld() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            Nomenclature scheme = new Nomenclature();
            scheme.id = 1L;
            ProductFamily node = heldNode(scheme, "N1");
            Product article = product(7L);
            node.products.add(article);
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> codeQuery = mock(PanacheQuery.class);
            when(codeQuery.firstResult()).thenReturn(node);
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> holdingQuery = mock(PanacheQuery.class);
            when(holdingQuery.list()).thenReturn(List.of());
            panache.when(() -> ProductFamily.find("code", "N1")).thenReturn(codeQuery);
            panache.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 7L))
                    .thenReturn(holdingQuery);
            Assertions.assertTrue(ProductFamily.fileUnderNomenclature(article, "N1"));
            Assertions.assertTrue(node.products.contains(article));
            verify(node, never()).persist();
        }
    }

    /**
     * fileUnderNomenclature detaches the article from the ONLY other node of the
     * same scheme, exercising each arm of the four-part detach guard: a touch
     * group (nomenclature null), the node itself (same code), a node of an
     * unpersisted scheme (id null), a node of a DIFFERENT scheme (id mismatch),
     * and finally the true match that is removed and persisted.
     */
    @Test
    void fileUnderNomenclatureDetachesFromConflictingNode() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            Nomenclature scheme = new Nomenclature();
            scheme.id = 1L;
            ProductFamily node = heldNode(scheme, "N1");
            Product article = product(7L);
            ProductFamily touch = node(null, "T", null);
            ProductFamily self = node(scheme, "N1", null);
            Nomenclature unpersisted = new Nomenclature();
            ProductFamily orphanScheme = node(unpersisted, "X", null);
            Nomenclature otherScheme = new Nomenclature();
            otherScheme.id = 2L;
            ProductFamily differentScheme = node(otherScheme, "Y", null);
            ProductFamily match = heldNode(scheme, "Z");
            match.products.add(article);
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> codeQuery = mock(PanacheQuery.class);
            when(codeQuery.firstResult()).thenReturn(node);
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> holdingQuery = mock(PanacheQuery.class);
            when(holdingQuery.list()).thenReturn(
                    List.of(touch, self, orphanScheme, differentScheme, match));
            panache.when(() -> ProductFamily.find("code", "N1")).thenReturn(codeQuery);
            panache.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 7L))
                    .thenReturn(holdingQuery);
            Assertions.assertTrue(ProductFamily.fileUnderNomenclature(article, "N1"));
            Assertions.assertFalse(match.products.contains(article));
            Assertions.assertTrue(node.products.contains(article));
            verify(match).persist();
            verify(node).persist();
        }
    }
}
