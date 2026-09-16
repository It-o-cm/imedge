package com.intermarche.pos.graphql;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ProductResource}.
 * <p>
 * {@code ProductResource} is a GraphQL active-record facade: queries and
 * mutations delegate to Panache static finders on {@link Product}, and the
 * three mutations wrap their body in {@link GraphQLTrait#execute} which
 * re-throws {@link NoSuchElementException} unchanged, wraps
 * {@link AlreadyExistsException} in a {@link GraphQLException} carrying the same
 * message, and wraps any other {@link Exception} in a generic
 * {@link GraphQLException}. Under plain {@code mvn test} the inherited finders
 * ({@code listAll}, {@code findById}, {@code count}, {@code deleteById}) resolve
 * to {@link PanacheEntityBase} and are intercepted with
 * {@link org.mockito.Mockito#mockStatic}; the custom static
 * {@code Product.findByEan} is declared on {@link Product} and is intercepted
 * through {@code mockStatic(Product.class)}; the {@code new Product()} performed
 * by {@code createProduct} is neutralized with
 * {@link org.mockito.Mockito#mockConstruction} so its {@code persist()} is
 * observable. Every static mock lives in a try-with-resources block, no
 * database and no Quarkus context is booted, entity fields are set directly,
 * and every branch (both ternary arms, both null-guard arms, every
 * short-circuit of the compound conditions) is asserted against absolute
 * expected values.
 */
class ProductResourceTest {

    /** The exact JPQL fragment issued by {@code updateProduct} for its name conflict check. */
    private static final String UPDATE_COUNT_QUERY = "name = ?1 and id <> ?2";
    /** Product id used across query/update/delete scenarios. */
    private static final Long PRODUCT_ID = 7L;
    /** Product EAN used across scenarios. */
    private static final String EAN = "3000000000001";
    /** Product name used across scenarios. */
    private static final String NAME = "Melon";

    /** The resource under test; the trait default method is real, no collaborators are injected. */
    private final ProductResource resource = new ProductResource();

    /**
     * Builds a real {@link Product} carrying the supplied ean and name.
     *
     * @param ean  the product ean
     * @param name the product name
     * @return the product
     */
    private Product product(String ean, String name) {
        Product p = new Product();
        p.ean = ean;
        p.name = name;
        return p;
    }

    /**
     * Builds a {@link ProductResource.ProductRecord} with the supplied core fields, leaving the
     * remaining optional fields null.
     *
     * @param ean         the ean (may be null)
     * @param name        the name (may be null)
     * @param productType the product type string (may be null)
     * @param active      the active flag (may be null)
     * @return the populated record
     */
    private ProductResource.ProductRecord record(String ean, String name, String productType, Boolean active) {
        ProductResource.ProductRecord input = new ProductResource.ProductRecord();
        input.ean = ean;
        input.name = name;
        input.productType = productType;
        input.active = active;
        return input;
    }

    /**
     * {@code allProducts} returns whatever {@code Product.listAll} yields, unchanged.
     */
    @Test
    void allProductsReturnsListAll() {
        List<Product> all = List.of(new Product(), new Product());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(Product::listAll).thenReturn(all);
            List<Product> result = resource.allProducts();
            assertSame(all, result);
        }
    }

    /**
     * {@code product} returns the entity found by id when it exists.
     */
    @Test
    void productReturnsEntityWhenFound() {
        Product found = product(EAN, NAME);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(PRODUCT_ID)).thenReturn(found);
            Product result = resource.product(PRODUCT_ID);
            assertSame(found, result);
        }
    }

    /**
     * {@code product} throws {@link NoSuchElementException} when no entity has the id.
     */
    @Test
    void productThrowsWhenNotFound() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(PRODUCT_ID)).thenReturn(null);
            NoSuchElementException thrown = assertThrows(NoSuchElementException.class,
                    () -> resource.product(PRODUCT_ID));
            assertEquals("Product with id " + PRODUCT_ID + " not found", thrown.getMessage());
        }
    }

    /**
     * {@code productByEan} returns the entity found by ean when it exists.
     */
    @Test
    void productByEanReturnsEntityWhenFound() {
        Product found = product(EAN, NAME);
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findByEan(EAN)).thenReturn(found);
            Product result = resource.productByEan(EAN);
            assertSame(found, result);
        }
    }

    /**
     * {@code productByEan} throws {@link NoSuchElementException} when no entity has the ean.
     */
    @Test
    void productByEanThrowsWhenNotFound() {
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findByEan(EAN)).thenReturn(null);
            NoSuchElementException thrown = assertThrows(NoSuchElementException.class,
                    () -> resource.productByEan(EAN));
            assertEquals("Product with ean " + EAN + " not found", thrown.getMessage());
        }
    }

    /**
     * {@code createProduct} persists a new product with an explicit product type and active flag
     * (both ternary true arms) when the ean and name are unique.
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void createProductPersistsWithExplicitTypeAndActive() throws GraphQLException {
        ProductResource.ProductRecord input = record(EAN, NAME, "WEIGHT", Boolean.FALSE);
        input.description = "A melon";
        input.brand = "Farm";
        input.referenceWeight = new BigDecimal("1.500");
        input.referenceVolume = new BigDecimal("0.000");
        input.unitName = "kg";
        input.checkoutLabel = "MELON JAUNE";
        input.internalCode = "INT-42";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Product> products = mockStatic(Product.class);
             MockedConstruction<Product> created = mockConstruction(Product.class)) {
            products.when(() -> Product.findByEan(EAN)).thenReturn(null);
            panache.when(() -> Product.count("name", NAME)).thenReturn(0L);
            Product result = resource.createProduct(input);
            assertEquals(1, created.constructed().size());
            Product product = created.constructed().get(0);
            assertSame(product, result);
            assertEquals(EAN, product.ean);
            assertEquals(NAME, product.name);
            assertEquals("A melon", product.description);
            assertEquals("Farm", product.brand);
            assertEquals(new BigDecimal("1.500"), product.referenceWeight);
            assertEquals(new BigDecimal("0.000"), product.referenceVolume);
            assertEquals("kg", product.unitName);
            assertEquals(ProductType.WEIGHT, product.productType);
            assertEquals("MELON JAUNE", product.checkoutLabel);
            assertEquals("INT-42", product.internalCode);
            assertFalse(product.active);
            verify(product, times(1)).persist();
        }
    }

    /**
     * {@code createProduct} persists a new product defaulting the product type to null and the
     * active flag to true (both ternary false arms) when the ean and name are unique.
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void createProductPersistsWithDefaults() throws GraphQLException {
        ProductResource.ProductRecord input = record(EAN, NAME, null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Product> products = mockStatic(Product.class);
             MockedConstruction<Product> created = mockConstruction(Product.class)) {
            products.when(() -> Product.findByEan(EAN)).thenReturn(null);
            panache.when(() -> Product.count("name", NAME)).thenReturn(0L);
            Product result = resource.createProduct(input);
            assertEquals(1, created.constructed().size());
            Product product = created.constructed().get(0);
            assertSame(product, result);
            assertNull(product.productType);
            assertTrue(product.active);
            verify(product, times(1)).persist();
        }
    }

    /**
     * {@code createProduct} wraps the {@link AlreadyExistsException} in a {@link GraphQLException}
     * when the ean already exists (first guard true).
     */
    @Test
    void createProductThrowsWhenEanExists() {
        ProductResource.ProductRecord input = record(EAN, NAME, null, null);
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findByEan(EAN)).thenReturn(product(EAN, "Other"));
            GraphQLException thrown = assertThrows(GraphQLException.class,
                    () -> resource.createProduct(input));
            assertEquals("Product with ean '" + EAN + "' already exists.", thrown.getMessage());
            assertTrue(thrown.getCause() instanceof AlreadyExistsException);
        }
    }

    /**
     * {@code createProduct} wraps the {@link AlreadyExistsException} in a {@link GraphQLException}
     * when the ean is unique but the name already exists (first guard false, second guard true).
     */
    @Test
    void createProductThrowsWhenNameExists() {
        ProductResource.ProductRecord input = record(EAN, NAME, null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findByEan(EAN)).thenReturn(null);
            panache.when(() -> Product.count("name", NAME)).thenReturn(1L);
            GraphQLException thrown = assertThrows(GraphQLException.class,
                    () -> resource.createProduct(input));
            assertEquals("Product with name '" + NAME + "' already exists.", thrown.getMessage());
            assertTrue(thrown.getCause() instanceof AlreadyExistsException);
        }
    }

    /**
     * {@code updateProduct} re-throws {@link NoSuchElementException} unwrapped when the id resolves
     * to nothing.
     */
    @Test
    void updateProductThrowsWhenNotFound() {
        ProductResource.ProductRecord input = record(EAN, NAME, null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(PRODUCT_ID)).thenReturn(null);
            NoSuchElementException thrown = assertThrows(NoSuchElementException.class,
                    () -> resource.updateProduct(PRODUCT_ID, input));
            assertEquals("Product with id " + PRODUCT_ID + " not found", thrown.getMessage());
        }
    }

    /**
     * {@code updateProduct} is a no-op leaving the product untouched when every input field is null
     * (both compound first arms false, every apply guard false).
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void updateProductNoOpWhenAllNull() throws GraphQLException {
        Product existing = product(EAN, NAME);
        existing.description = "keep";
        existing.productType = ProductType.UNIT;
        existing.active = true;
        ProductResource.ProductRecord input = record(null, null, null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(PRODUCT_ID)).thenReturn(existing);
            Product result = resource.updateProduct(PRODUCT_ID, input);
            assertSame(existing, result);
            assertEquals(EAN, existing.ean);
            assertEquals(NAME, existing.name);
            assertEquals("keep", existing.description);
            assertEquals(ProductType.UNIT, existing.productType);
            assertTrue(existing.active);
            panache.verify(() -> Product.count(UPDATE_COUNT_QUERY, NAME, PRODUCT_ID), times(0));
        }
    }

    /**
     * {@code updateProduct} skips both conflict checks yet re-applies the ean and name when they are
     * provided but equal to the current values (both compound second arms false, both apply guards
     * true).
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void updateProductUnchangedEanAndNameSkipConflictChecks() throws GraphQLException {
        Product existing = product(EAN, NAME);
        ProductResource.ProductRecord input = record(EAN, NAME, null, null);
        input.checkoutLabel = "MELON JAUNE";
        input.internalCode = "INT-42";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Product> products = mockStatic(Product.class)) {
            panache.when(() -> Product.findById(PRODUCT_ID)).thenReturn(existing);
            Product result = resource.updateProduct(PRODUCT_ID, input);
            assertSame(existing, result);
            assertSame(input.ean, existing.ean);
            assertSame(input.name, existing.name);
            assertEquals("MELON JAUNE", existing.checkoutLabel);
            assertEquals("INT-42", existing.internalCode);
            products.verify(() -> Product.findByEan(EAN), times(0));
            panache.verify(() -> Product.count(UPDATE_COUNT_QUERY, NAME, PRODUCT_ID), times(0));
        }
    }

    /**
     * {@code updateProduct} wraps an {@link AlreadyExistsException} in a {@link GraphQLException}
     * when the changed ean conflicts with another product (ean compound both arms true, conflict
     * lookup non-null).
     */
    @Test
    void updateProductThrowsWhenEanConflicts() {
        Product existing = product(EAN, NAME);
        ProductResource.ProductRecord input = record("3000000000999", null, null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Product> products = mockStatic(Product.class)) {
            panache.when(() -> Product.findById(PRODUCT_ID)).thenReturn(existing);
            products.when(() -> Product.findByEan("3000000000999")).thenReturn(product("3000000000999", "Other"));
            GraphQLException thrown = assertThrows(GraphQLException.class,
                    () -> resource.updateProduct(PRODUCT_ID, input));
            assertEquals("Another product with ean '3000000000999' already exists.", thrown.getMessage());
            assertTrue(thrown.getCause() instanceof AlreadyExistsException);
        }
    }

    /**
     * {@code updateProduct} wraps an {@link AlreadyExistsException} in a {@link GraphQLException}
     * when the changed name conflicts with another product (ean guard first arm false, name compound
     * both arms true, conflict count positive).
     */
    @Test
    void updateProductThrowsWhenNameConflicts() {
        Product existing = product(EAN, NAME);
        ProductResource.ProductRecord input = record(null, "Watermelon", null, null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(PRODUCT_ID)).thenReturn(existing);
            panache.when(() -> Product.count(UPDATE_COUNT_QUERY, "Watermelon", PRODUCT_ID)).thenReturn(1L);
            GraphQLException thrown = assertThrows(GraphQLException.class,
                    () -> resource.updateProduct(PRODUCT_ID, input));
            assertEquals("Another product with name 'Watermelon' already exists.", thrown.getMessage());
            assertTrue(thrown.getCause() instanceof AlreadyExistsException);
        }
    }

    /**
     * {@code updateProduct} applies every field when they are all non-null, changed, and free of
     * conflicts (ean conflict lookup null, name conflict count zero, every apply guard true,
     * product-type guard true).
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void updateProductAppliesAllFields() throws GraphQLException {
        Product existing = product(EAN, NAME);
        existing.active = false;
        ProductResource.ProductRecord input = record("3000000000999", "Watermelon", "VOLUME", Boolean.TRUE);
        input.description = "big";
        input.brand = "Farm";
        input.referenceWeight = new BigDecimal("2.000");
        input.referenceVolume = new BigDecimal("3.000");
        input.unitName = "kg";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Product> products = mockStatic(Product.class)) {
            panache.when(() -> Product.findById(PRODUCT_ID)).thenReturn(existing);
            products.when(() -> Product.findByEan("3000000000999")).thenReturn(null);
            panache.when(() -> Product.count(UPDATE_COUNT_QUERY, "Watermelon", PRODUCT_ID)).thenReturn(0L);
            Product result = resource.updateProduct(PRODUCT_ID, input);
            assertSame(existing, result);
            assertEquals("3000000000999", existing.ean);
            assertEquals("Watermelon", existing.name);
            assertEquals("big", existing.description);
            assertEquals("Farm", existing.brand);
            assertEquals(new BigDecimal("2.000"), existing.referenceWeight);
            assertEquals(new BigDecimal("3.000"), existing.referenceVolume);
            assertEquals(ProductType.VOLUME, existing.productType);
            assertEquals("kg", existing.unitName);
            assertTrue(existing.active);
        }
    }

    /**
     * {@code deleteProduct} returns true when the underlying delete removed a row.
     *
     * @throws GraphQLException never in this path
     */
    @Test
    void deleteProductReturnsTrueWhenDeleted() throws GraphQLException {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.deleteById(PRODUCT_ID)).thenReturn(true);
            boolean result = resource.deleteProduct(PRODUCT_ID);
            assertTrue(result);
        }
    }

    /**
     * {@code deleteProduct} returns false when no row matched the id.
     *
     * @throws GraphQLException never in this path
     */
    @Test
    void deleteProductReturnsFalseWhenNothingDeleted() throws GraphQLException {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.deleteById(PRODUCT_ID)).thenReturn(false);
            boolean result = resource.deleteProduct(PRODUCT_ID);
            assertFalse(result);
        }
    }

    /**
     * {@code ProductRecord.toString} renders all fields of the record.
     */
    @Test
    void productRecordToStringRendersAllFields() {
        ProductResource.ProductRecord input = record(EAN, NAME, "UNIT", Boolean.TRUE);
        input.description = "d";
        input.brand = "b";
        input.referenceWeight = new BigDecimal("1.000");
        input.referenceVolume = new BigDecimal("2.000");
        input.unitName = "kg";
        input.checkoutLabel = "CL";
        input.internalCode = "IC";
        String expected = "ProductRecord [ean=" + EAN + ", name=" + NAME + ", description=d" +
                ", brand=b, referenceWeight=1.000, referenceVolume=2.000" +
                ", productType=UNIT, unitName=kg, active=true" +
                ", checkoutLabel=CL, internalCode=IC";
        assertEquals(expected, input.toString());
    }
}
