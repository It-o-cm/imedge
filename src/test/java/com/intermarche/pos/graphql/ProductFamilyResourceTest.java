package com.intermarche.pos.graphql;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductFamily;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ProductFamilyResource}.
 * <p>
 * {@code ProductFamilyResource} is a GraphQL active-record facade: queries and
 * mutations delegate to Panache static finders on {@link ProductFamily} and
 * {@link Product}, and the three mutations wrap their body in
 * {@link GraphQLTrait#execute} which re-throws {@link NoSuchElementException}
 * unchanged, wraps {@link AlreadyExistsException} in a {@link GraphQLException}
 * carrying the same message, and wraps any other {@link Exception} in a generic
 * {@link GraphQLException}. Under plain {@code mvn test} the inherited finders
 * ({@code listAll}, {@code findById}, {@code count}, {@code deleteById}) resolve
 * to {@link PanacheEntityBase} and are intercepted with
 * {@link org.mockito.Mockito#mockStatic}; the custom statics
 * {@code ProductFamily.findByCode} and {@code Product.findByEan} are declared on
 * their own entity classes and are intercepted through {@code mockStatic(ProductFamily.class)}
 * and {@code mockStatic(Product.class)}; the {@code new ProductFamily()} performed
 * by {@code createProductFamily} is neutralized with
 * {@link org.mockito.Mockito#mockConstruction} (its collection fields are
 * re-seeded in the construction callback) so its {@code persist()} is observable.
 * Every static mock lives in a try-with-resources block, no database and no
 * Quarkus context is booted, entity fields are set directly, and every branch
 * (both ternary arms, both null-guard arms, every short-circuit of the compound
 * conditions) is asserted against absolute expected values.
 */
class ProductFamilyResourceTest {

    /** The exact JPQL fragment issued by {@code updateProductFamily} for its conflict check. */
    private static final String UPDATE_COUNT_QUERY = "description = ?1 and id <> ?2";
    /** Family id used across query/update/delete scenarios. */
    private static final Long FAMILY_ID = 5L;
    /** Family code used across scenarios. */
    private static final String CODE = "F100";
    /** Family description used across scenarios. */
    private static final String DESC = "Fruits";
    /** Product EAN used across scenarios. */
    private static final String EAN = "3000000000001";
    /** Child family code used across scenarios. */
    private static final String CHILD_CODE = "F200";

    /** The resource under test; the trait default method is real, no collaborators are injected. */
    private final ProductFamilyResource resource = new ProductFamilyResource();

    /**
     * Builds a bare {@link Product} carrying only the supplied id.
     *
     * @param id the product id
     * @return the product
     */
    private Product product(Long id) {
        Product p = new Product();
        p.id = id;
        return p;
    }

    /**
     * Builds a real {@link ProductFamily} carrying the supplied code and description.
     *
     * @param code        the family code
     * @param description the family description
     * @return the family with real, empty collection fields
     */
    private ProductFamily family(String code, String description) {
        ProductFamily f = new ProductFamily();
        f.code = code;
        f.description = description;
        return f;
    }

    /**
     * Builds a {@link ProductFamilyResource.ProductFamilyRecord} with the supplied fields.
     *
     * @param code        the code (may be null)
     * @param description the description (may be null)
     * @param eans        the product EANs (may be null)
     * @param codes       the child family codes (may be null)
     * @return the populated record
     */
    private ProductFamilyResource.ProductFamilyRecord record(String code, String description,
                                                             List<String> eans, List<String> codes) {
        ProductFamilyResource.ProductFamilyRecord input = new ProductFamilyResource.ProductFamilyRecord();
        input.code = code;
        input.description = description;
        input.productEans = eans;
        input.productFamilyCodes = codes;
        return input;
    }

    /**
     * {@code allProductFamilies} returns whatever {@code ProductFamily.listAll} yields, unchanged.
     */
    @Test
    void allProductFamiliesReturnsListAll() {
        List<ProductFamily> all = List.of(new ProductFamily(), new ProductFamily());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(ProductFamily::listAll).thenReturn(all);
            List<ProductFamily> result = resource.allProductFamilies();
            assertSame(all, result);
        }
    }

    /**
     * {@code productFamily} returns the entity found by id when it exists.
     */
    @Test
    void productFamilyReturnsEntityWhenFound() {
        ProductFamily found = family(CODE, DESC);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(FAMILY_ID)).thenReturn(found);
            ProductFamily result = resource.productFamily(FAMILY_ID);
            assertSame(found, result);
        }
    }

    /**
     * {@code productFamily} throws {@link NoSuchElementException} when no entity has the id.
     */
    @Test
    void productFamilyThrowsWhenNotFound() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(FAMILY_ID)).thenReturn(null);
            NoSuchElementException thrown = assertThrows(NoSuchElementException.class,
                    () -> resource.productFamily(FAMILY_ID));
            assertEquals("ProductFamily with id " + FAMILY_ID + " not found", thrown.getMessage());
        }
    }

    /**
     * {@code productFamilyByCode} returns the entity found by code when it exists.
     */
    @Test
    void productFamilyByCodeReturnsEntityWhenFound() {
        ProductFamily found = family(CODE, DESC);
        try (MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            families.when(() -> ProductFamily.findByCode(CODE)).thenReturn(found);
            ProductFamily result = resource.productFamilyByCode(CODE);
            assertSame(found, result);
        }
    }

    /**
     * {@code productFamilyByCode} throws {@link NoSuchElementException} when no entity has the code.
     */
    @Test
    void productFamilyByCodeThrowsWhenNotFound() {
        try (MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            families.when(() -> ProductFamily.findByCode(CODE)).thenReturn(null);
            NoSuchElementException thrown = assertThrows(NoSuchElementException.class,
                    () -> resource.productFamilyByCode(CODE));
            assertEquals("ProductFamily with code " + CODE + " not found", thrown.getMessage());
        }
    }

    /**
     * {@code createProductFamily} persists a new family, linking both a product (by EAN) and a
     * sub-family (by code) when the code and description are unique and both children resolve.
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void createProductFamilyPersistsWithChildren() throws GraphQLException {
        Product linked = product(1L);
        ProductFamily child = family(CHILD_CODE, "Vegetables");
        ProductFamilyResource.ProductFamilyRecord input =
                record(CODE, DESC, List.of(EAN), List.of(CHILD_CODE));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class);
             MockedConstruction<ProductFamily> created = mockConstruction(ProductFamily.class,
                     (mock, ctx) -> {
                         mock.products = new HashSet<>();
                         mock.productFamilies = new HashSet<>();
                     })) {
            panache.when(() -> ProductFamily.count("code", CODE)).thenReturn(0L);
            panache.when(() -> ProductFamily.count("description", DESC)).thenReturn(0L);
            products.when(() -> Product.findByEan(EAN)).thenReturn(linked);
            families.when(() -> ProductFamily.findByCode(CHILD_CODE)).thenReturn(child);
            ProductFamily result = resource.createProductFamily(input);
            assertEquals(1, created.constructed().size());
            ProductFamily family = created.constructed().get(0);
            assertSame(family, result);
            assertEquals(CODE, family.code);
            assertEquals(DESC, family.description);
            assertTrue(family.products.contains(linked));
            assertTrue(family.productFamilies.contains(child));
            verify(family, times(1)).persist();
        }
    }

    /**
     * {@code createProductFamily} persists with no children when both code lists are null
     * (both compound guards short-circuit on their first, null arm).
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void createProductFamilyPersistsWithNullLists() throws GraphQLException {
        ProductFamilyResource.ProductFamilyRecord input = record(CODE, DESC, null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ProductFamily> created = mockConstruction(ProductFamily.class,
                     (mock, ctx) -> {
                         mock.products = new HashSet<>();
                         mock.productFamilies = new HashSet<>();
                     })) {
            panache.when(() -> ProductFamily.count("code", CODE)).thenReturn(0L);
            panache.when(() -> ProductFamily.count("description", DESC)).thenReturn(0L);
            ProductFamily result = resource.createProductFamily(input);
            assertEquals(1, created.constructed().size());
            ProductFamily family = created.constructed().get(0);
            assertSame(family, result);
            assertTrue(family.products.isEmpty());
            assertTrue(family.productFamilies.isEmpty());
            verify(family, times(1)).persist();
        }
    }

    /**
     * {@code createProductFamily} persists with no children when both code lists are empty
     * (both compound guards short-circuit on their second, {@code isEmpty} arm).
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void createProductFamilyPersistsWithEmptyLists() throws GraphQLException {
        ProductFamilyResource.ProductFamilyRecord input = record(CODE, DESC, List.of(), List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ProductFamily> created = mockConstruction(ProductFamily.class,
                     (mock, ctx) -> {
                         mock.products = new HashSet<>();
                         mock.productFamilies = new HashSet<>();
                     })) {
            panache.when(() -> ProductFamily.count("code", CODE)).thenReturn(0L);
            panache.when(() -> ProductFamily.count("description", DESC)).thenReturn(0L);
            ProductFamily result = resource.createProductFamily(input);
            assertEquals(1, created.constructed().size());
            ProductFamily family = created.constructed().get(0);
            assertSame(family, result);
            assertTrue(family.products.isEmpty());
            assertTrue(family.productFamilies.isEmpty());
            verify(family, times(1)).persist();
        }
    }

    /**
     * {@code createProductFamily} wraps the {@link AlreadyExistsException} in a
     * {@link GraphQLException} when the code already exists.
     */
    @Test
    void createProductFamilyThrowsWhenCodeExists() {
        ProductFamilyResource.ProductFamilyRecord input = record(CODE, DESC, null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.count("code", CODE)).thenReturn(1L);
            GraphQLException thrown = assertThrows(GraphQLException.class,
                    () -> resource.createProductFamily(input));
            assertEquals("ProductFamily with code '" + CODE + "' already exists.", thrown.getMessage());
            assertTrue(thrown.getCause() instanceof AlreadyExistsException);
        }
    }

    /**
     * {@code createProductFamily} wraps the {@link AlreadyExistsException} in a
     * {@link GraphQLException} when the code is unique but the description already exists.
     */
    @Test
    void createProductFamilyThrowsWhenDescriptionExists() {
        ProductFamilyResource.ProductFamilyRecord input = record(CODE, DESC, null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.count("code", CODE)).thenReturn(0L);
            panache.when(() -> ProductFamily.count("description", DESC)).thenReturn(1L);
            GraphQLException thrown = assertThrows(GraphQLException.class,
                    () -> resource.createProductFamily(input));
            assertEquals("ProductFamily with description '" + DESC + "' already exists.", thrown.getMessage());
            assertTrue(thrown.getCause() instanceof AlreadyExistsException);
        }
    }

    /**
     * {@code createProductFamily} re-throws {@link NoSuchElementException} unwrapped when a
     * referenced product EAN does not resolve.
     */
    @Test
    void createProductFamilyThrowsWhenProductMissing() {
        ProductFamilyResource.ProductFamilyRecord input = record(CODE, DESC, List.of(EAN), null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Product> products = mockStatic(Product.class)) {
            panache.when(() -> ProductFamily.count("code", CODE)).thenReturn(0L);
            panache.when(() -> ProductFamily.count("description", DESC)).thenReturn(0L);
            products.when(() -> Product.findByEan(EAN)).thenReturn(null);
            NoSuchElementException thrown = assertThrows(NoSuchElementException.class,
                    () -> resource.createProductFamily(input));
            assertEquals("Product with ean '" + EAN + "' not found.", thrown.getMessage());
        }
    }

    /**
     * {@code createProductFamily} re-throws {@link NoSuchElementException} unwrapped when a
     * referenced child family code does not resolve (the product loop is skipped via a null list).
     */
    @Test
    void createProductFamilyThrowsWhenChildMissing() {
        ProductFamilyResource.ProductFamilyRecord input = record(CODE, DESC, null, List.of(CHILD_CODE));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            panache.when(() -> ProductFamily.count("code", CODE)).thenReturn(0L);
            panache.when(() -> ProductFamily.count("description", DESC)).thenReturn(0L);
            families.when(() -> ProductFamily.findByCode(CHILD_CODE)).thenReturn(null);
            NoSuchElementException thrown = assertThrows(NoSuchElementException.class,
                    () -> resource.createProductFamily(input));
            assertEquals("ProductFamily with code '" + CHILD_CODE + "' not found.", thrown.getMessage());
        }
    }

    /**
     * {@code updateProductFamily} re-throws {@link NoSuchElementException} unwrapped when the id
     * resolves to nothing.
     */
    @Test
    void updateProductFamilyThrowsWhenNotFound() {
        ProductFamilyResource.ProductFamilyRecord input = record(CODE, DESC, null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(FAMILY_ID)).thenReturn(null);
            NoSuchElementException thrown = assertThrows(NoSuchElementException.class,
                    () -> resource.updateProductFamily(FAMILY_ID, input));
            assertEquals("ProductFamily with id " + FAMILY_ID + " not found", thrown.getMessage());
        }
    }

    /**
     * {@code updateProductFamily} is a no-op leaving the family untouched when the description is
     * null and both code lists are null (description guard first arm false, both list guards false).
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void updateProductFamilyNoOpWhenAllNull() throws GraphQLException {
        ProductFamily existing = family(CODE, DESC);
        existing.products.add(product(1L));
        existing.productFamilies.add(family(CHILD_CODE, "Vegetables"));
        ProductFamilyResource.ProductFamilyRecord input = record(null, null, null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(FAMILY_ID)).thenReturn(existing);
            ProductFamily result = resource.updateProductFamily(FAMILY_ID, input);
            assertSame(existing, result);
            assertEquals(DESC, existing.description);
            assertEquals(1, existing.products.size());
            assertEquals(1, existing.productFamilies.size());
            panache.verify(() -> ProductFamily.count(UPDATE_COUNT_QUERY, DESC, FAMILY_ID), times(0));
        }
    }

    /**
     * {@code updateProductFamily} skips the conflict check but re-applies the description when the
     * new description equals the current one (compound guard second arm false, apply guard true).
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void updateProductFamilyDescriptionUnchangedSkipsConflictCheck() throws GraphQLException {
        ProductFamily existing = family(CODE, DESC);
        ProductFamilyResource.ProductFamilyRecord input = record(null, DESC, null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(FAMILY_ID)).thenReturn(existing);
            ProductFamily result = resource.updateProductFamily(FAMILY_ID, input);
            assertSame(existing, result);
            assertSame(input.description, existing.description);
            panache.verify(() -> ProductFamily.count(UPDATE_COUNT_QUERY, DESC, FAMILY_ID), times(0));
        }
    }

    /**
     * {@code updateProductFamily} wraps an {@link AlreadyExistsException} in a
     * {@link GraphQLException} when the changed description conflicts with another family.
     */
    @Test
    void updateProductFamilyThrowsWhenDescriptionConflicts() {
        ProductFamily existing = family(CODE, DESC);
        ProductFamilyResource.ProductFamilyRecord input = record(null, "Dairy", null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(FAMILY_ID)).thenReturn(existing);
            panache.when(() -> ProductFamily.count(UPDATE_COUNT_QUERY, "Dairy", FAMILY_ID)).thenReturn(1L);
            GraphQLException thrown = assertThrows(GraphQLException.class,
                    () -> resource.updateProductFamily(FAMILY_ID, input));
            assertEquals("Another family with description 'Dairy' already exists.", thrown.getMessage());
            assertTrue(thrown.getCause() instanceof AlreadyExistsException);
        }
    }

    /**
     * {@code updateProductFamily} applies a changed description when it is unique (compound guard
     * both arms true, conflict count zero, apply guard true).
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void updateProductFamilyChangesDescriptionWithoutConflict() throws GraphQLException {
        ProductFamily existing = family(CODE, DESC);
        ProductFamilyResource.ProductFamilyRecord input = record(null, "Dairy", null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(FAMILY_ID)).thenReturn(existing);
            panache.when(() -> ProductFamily.count(UPDATE_COUNT_QUERY, "Dairy", FAMILY_ID)).thenReturn(0L);
            ProductFamily result = resource.updateProductFamily(FAMILY_ID, input);
            assertSame(existing, result);
            assertEquals("Dairy", existing.description);
        }
    }

    /**
     * {@code updateProductFamily} replaces the product set from the supplied EANs when the list is
     * non-null and every EAN resolves (product list guard true, missing guard false).
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void updateProductFamilyReplacesProducts() throws GraphQLException {
        ProductFamily existing = family(CODE, DESC);
        existing.products.add(product(99L));
        Product linked = product(1L);
        ProductFamilyResource.ProductFamilyRecord input = record(null, null, List.of(EAN), null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Product> products = mockStatic(Product.class)) {
            panache.when(() -> ProductFamily.findById(FAMILY_ID)).thenReturn(existing);
            products.when(() -> Product.findByEan(EAN)).thenReturn(linked);
            ProductFamily result = resource.updateProductFamily(FAMILY_ID, input);
            assertSame(existing, result);
            assertEquals(1, existing.products.size());
            assertTrue(existing.products.contains(linked));
        }
    }

    /**
     * {@code updateProductFamily} re-throws {@link NoSuchElementException} unwrapped when a
     * referenced product EAN does not resolve (missing guard true).
     */
    @Test
    void updateProductFamilyThrowsWhenProductMissing() {
        ProductFamily existing = family(CODE, DESC);
        ProductFamilyResource.ProductFamilyRecord input = record(null, null, List.of(EAN), null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Product> products = mockStatic(Product.class)) {
            panache.when(() -> ProductFamily.findById(FAMILY_ID)).thenReturn(existing);
            products.when(() -> Product.findByEan(EAN)).thenReturn(null);
            NoSuchElementException thrown = assertThrows(NoSuchElementException.class,
                    () -> resource.updateProductFamily(FAMILY_ID, input));
            assertEquals("Product with code '" + EAN + "' not found.", thrown.getMessage());
        }
    }

    /**
     * {@code updateProductFamily} replaces the sub-family set from the supplied codes when the list
     * is non-null, every code resolves, and no child equals the family itself (family list guard
     * true, missing guard false, self-reference guard false).
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void updateProductFamilyReplacesFamilies() throws GraphQLException {
        ProductFamily existing = family(CODE, DESC);
        existing.productFamilies.add(family("F999", "Old"));
        ProductFamily child = family(CHILD_CODE, "Vegetables");
        ProductFamilyResource.ProductFamilyRecord input = record(null, null, null, List.of(CHILD_CODE));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            panache.when(() -> ProductFamily.findById(FAMILY_ID)).thenReturn(existing);
            families.when(() -> ProductFamily.findByCode(CHILD_CODE)).thenReturn(child);
            ProductFamily result = resource.updateProductFamily(FAMILY_ID, input);
            assertSame(existing, result);
            assertEquals(1, existing.productFamilies.size());
            assertTrue(existing.productFamilies.contains(child));
        }
    }

    /**
     * {@code updateProductFamily} re-throws {@link NoSuchElementException} unwrapped when a
     * referenced child family code does not resolve (missing guard true).
     */
    @Test
    void updateProductFamilyThrowsWhenChildMissing() {
        ProductFamily existing = family(CODE, DESC);
        ProductFamilyResource.ProductFamilyRecord input = record(null, null, null, List.of(CHILD_CODE));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            panache.when(() -> ProductFamily.findById(FAMILY_ID)).thenReturn(existing);
            families.when(() -> ProductFamily.findByCode(CHILD_CODE)).thenReturn(null);
            NoSuchElementException thrown = assertThrows(NoSuchElementException.class,
                    () -> resource.updateProductFamily(FAMILY_ID, input));
            assertEquals("ProductFamily with code '" + CHILD_CODE + "' not found.", thrown.getMessage());
        }
    }

    /**
     * {@code updateProductFamily} wraps the {@link IllegalArgumentException} in a generic
     * {@link GraphQLException} when a child code resolves to the family itself (self-reference
     * guard true).
     */
    @Test
    void updateProductFamilyThrowsWhenSelfReferenced() {
        ProductFamily existing = family(CODE, DESC);
        ProductFamily self = family(CODE, DESC);
        ProductFamilyResource.ProductFamilyRecord input = record(null, null, null, List.of(CODE));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            panache.when(() -> ProductFamily.findById(FAMILY_ID)).thenReturn(existing);
            families.when(() -> ProductFamily.findByCode(CODE)).thenReturn(self);
            GraphQLException thrown = assertThrows(GraphQLException.class,
                    () -> resource.updateProductFamily(FAMILY_ID, input));
            assertEquals("An error occurred during updateProductFamily.", thrown.getMessage());
            assertTrue(thrown.getCause() instanceof IllegalArgumentException);
        }
    }

    /**
     * {@code deleteProductFamily} returns true when the underlying delete removed a row.
     *
     * @throws GraphQLException never in this path
     */
    @Test
    void deleteProductFamilyReturnsTrueWhenDeleted() throws GraphQLException {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.deleteById(FAMILY_ID)).thenReturn(true);
            boolean result = resource.deleteProductFamily(FAMILY_ID);
            assertTrue(result);
        }
    }

    /**
     * {@code deleteProductFamily} returns false when no row matched the id.
     *
     * @throws GraphQLException never in this path
     */
    @Test
    void deleteProductFamilyReturnsFalseWhenNothingDeleted() throws GraphQLException {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.deleteById(FAMILY_ID)).thenReturn(false);
            boolean result = resource.deleteProductFamily(FAMILY_ID);
            assertFalse(result);
        }
    }

    /**
     * {@code ProductFamilyRecord.toString} renders both code lists when they are non-null (both
     * ternary true arms).
     */
    @Test
    void productFamilyRecordToStringRendersLists() {
        ProductFamilyResource.ProductFamilyRecord input =
                record(CODE, DESC, List.of(EAN, "3000000000002"), List.of(CHILD_CODE));
        String expected = "ProductFamilyRecord [code=" + CODE + ", description=" + DESC +
                ", productEans=[" + EAN + ", 3000000000002]" +
                ", productFamilyCodes=[" + CHILD_CODE + "]]";
        assertEquals(expected, input.toString());
    }

    /**
     * {@code ProductFamilyRecord.toString} renders the literal {@code null} for both code lists
     * when they are null (both ternary false arms).
     */
    @Test
    void productFamilyRecordToStringRendersNullLists() {
        ProductFamilyResource.ProductFamilyRecord input = record(CODE, DESC, null, null);
        String expected = "ProductFamilyRecord [code=" + CODE + ", description=" + DESC +
                ", productEans=null, productFamilyCodes=null]";
        assertEquals(expected, input.toString());
    }
}
