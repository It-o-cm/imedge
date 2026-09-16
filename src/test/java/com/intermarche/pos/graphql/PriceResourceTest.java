package com.intermarche.pos.graphql;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
 * Unit tests for {@link PriceResource}.
 * <p>
 * {@code PriceResource} is a GraphQL active-record facade: queries and mutations
 * delegate to Panache static finders on {@link Price} and {@link Product}, and the
 * three mutations wrap their body in {@link GraphQLTrait#execute} which re-throws
 * {@link NoSuchElementException} unchanged but wraps {@link AlreadyExistsException}
 * (and any other error) in a {@link GraphQLException}. Under plain {@code mvn test}
 * the inherited finders ({@code listAll}, {@code findById}, {@code count},
 * {@code deleteById}) resolve to {@link PanacheEntityBase} and are intercepted with
 * {@link org.mockito.Mockito#mockStatic}; the custom static {@code Price.findCurrentPrice}
 * is declared on {@link Price} and is mocked through {@code mockStatic(Price.class)};
 * the {@code new Price()} performed by {@code createPrice} is neutralized with
 * {@link org.mockito.Mockito#mockConstruction} so its {@code persist()} is observable.
 * Every static mock lives in a try-with-resources block, no database and no Quarkus
 * context is booted, entity fields are set directly, and every branch (both ternary
 * arms, both null-guard arms, every short-circuit of the key-change disjunction) is
 * asserted against absolute expected values.
 */
class PriceResourceTest {

    /** The exact JPQL fragment issued by {@code createPrice} for its uniqueness check. */
    private static final String CREATE_COUNT_QUERY =
            "product.id = ?1 and priority = ?3 and startDateTime = ?4 and priceUsage = ?5";
    /** The exact JPQL fragment issued by {@code updatePrice} for its conflict check. */
    private static final String UPDATE_COUNT_QUERY =
            "product.id = ?1 and priority = ?2 and startDateTime = ?3 and id <> ?4";
    /** Product id used across create/update scenarios. */
    private static final Long PRODUCT_ID = 42L;
    /** Price id used across update/delete scenarios. */
    private static final Long PRICE_ID = 7L;
    /** Priority used across scenarios. */
    private static final Integer PRIORITY = 0;
    /** Validity start used across scenarios. */
    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 0, 0);
    /** Validity end used across scenarios. */
    private static final LocalDateTime END = LocalDateTime.of(2026, 12, 31, 0, 0);
    /** Price excluding tax used across scenarios. */
    private static final BigDecimal HT = new BigDecimal("10.0000");
    /** Price including tax used across scenarios. */
    private static final BigDecimal TTC = new BigDecimal("12.0000");
    /** VAT rate used across scenarios. */
    private static final BigDecimal VAT = new BigDecimal("0.2000");

    /** The resource under test; the trait default method is real, no collaborators are injected. */
    private final PriceResource resource = new PriceResource();

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
     * Builds a fully-valued {@link PriceRecord} for the create scenarios.
     *
     * @param productId the product id (may be null)
     * @return the populated record
     */
    private PriceResource.PriceRecord createInput(Long productId) {
        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.productId = productId;
        input.priceExcludingTax = HT;
        input.priceIncludingTax = TTC;
        input.vatRate = VAT;
        input.priority = PRIORITY;
        input.startDateTime = START;
        input.endDateTime = END;
        return input;
    }

    /**
     * {@code allPrices} returns whatever {@code Price.listAll} yields, unchanged.
     */
    @Test
    void allPricesReturnsListAll() {
        Price a = new Price();
        Price b = new Price();
        List<Price> all = List.of(a, b);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(Price::listAll).thenReturn(all);
            List<Price> result = resource.allPrices();
            assertSame(all, result);
        }
    }

    /**
     * {@code price} returns the entity found by id when it exists.
     */
    @Test
    void priceReturnsEntityWhenFound() {
        Price found = new Price();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.findById(PRICE_ID)).thenReturn(found);
            Price result = resource.price(PRICE_ID);
            assertSame(found, result);
        }
    }

    /**
     * {@code price} throws {@link NoSuchElementException} when no entity has the id.
     */
    @Test
    void priceThrowsWhenNotFound() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.findById(PRICE_ID)).thenReturn(null);
            NoSuchElementException thrown = assertThrows(NoSuchElementException.class,
                    () -> resource.price(PRICE_ID));
            assertEquals("Price with id " + PRICE_ID + " not found", thrown.getMessage());
        }
    }

    /**
     * {@code currentPrice} returns the active price when one is resolved.
     */
    @Test
    void currentPriceReturnsActivePrice() {
        Price active = new Price();
        try (MockedStatic<Price> prices = mockStatic(Price.class)) {
            prices.when(() -> Price.findCurrentPrice(PRODUCT_ID)).thenReturn(active);
            Price result = resource.currentPrice(PRODUCT_ID);
            assertSame(active, result);
        }
    }

    /**
     * {@code currentPrice} returns null (after logging a warning) when no active price exists.
     */
    @Test
    void currentPriceReturnsNullWhenNoneActive() {
        try (MockedStatic<Price> prices = mockStatic(Price.class)) {
            prices.when(() -> Price.findCurrentPrice(PRODUCT_ID)).thenReturn(null);
            Price result = resource.currentPrice(PRODUCT_ID);
            assertNull(result);
        }
    }

    /**
     * {@code createPrice} persists a new price when the product exists and no duplicate is present,
     * copying every input field onto the constructed entity.
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void createPricePersistsWhenValid() throws GraphQLException {
        Product product = product(PRODUCT_ID);
        PriceResource.PriceRecord input = createInput(PRODUCT_ID);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Price> created = mockConstruction(Price.class)) {
            panache.when(() -> Product.findById(PRODUCT_ID)).thenReturn(product);
            panache.when(() -> Price.count(CREATE_COUNT_QUERY, PRODUCT_ID, PRIORITY, START)).thenReturn(0L);
            Price result = resource.createPrice(input);
            assertEquals(1, created.constructed().size());
            Price price = created.constructed().get(0);
            assertSame(price, result);
            assertSame(product, price.product);
            assertSame(HT, price.priceExcludingTax);
            assertSame(TTC, price.priceIncludingTax);
            assertSame(VAT, price.vatRate);
            assertSame(PRIORITY, price.priority);
            assertSame(START, price.startDateTime);
            assertSame(END, price.endDateTime);
            verify(price, times(1)).persist();
        }
    }

    /**
     * {@code createPrice} throws {@link NoSuchElementException} (re-thrown unwrapped by the trait)
     * when the referenced product does not exist.
     */
    @Test
    void createPriceThrowsWhenProductMissing() {
        PriceResource.PriceRecord input = createInput(PRODUCT_ID);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(PRODUCT_ID)).thenReturn(null);
            NoSuchElementException thrown = assertThrows(NoSuchElementException.class,
                    () -> resource.createPrice(input));
            assertEquals("Product with id '" + PRODUCT_ID + "' not found.", thrown.getMessage());
        }
    }

    /**
     * {@code createPrice} wraps the {@link AlreadyExistsException} in a {@link GraphQLException}
     * when an identical (product, priority, start) combination already exists.
     */
    @Test
    void createPriceThrowsWhenDuplicate() {
        Product product = product(PRODUCT_ID);
        PriceResource.PriceRecord input = createInput(PRODUCT_ID);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(PRODUCT_ID)).thenReturn(product);
            panache.when(() -> Price.count(CREATE_COUNT_QUERY, PRODUCT_ID, PRIORITY, START)).thenReturn(1L);
            GraphQLException thrown = assertThrows(GraphQLException.class,
                    () -> resource.createPrice(input));
            assertEquals("A price with the same priority, start date, and usage already exists for this product.",
                    thrown.getMessage());
            assertTrue(thrown.getCause() instanceof AlreadyExistsException);
        }
    }

    /**
     * {@code updatePrice} throws {@link NoSuchElementException} when the id resolves to nothing.
     */
    @Test
    void updatePriceThrowsWhenNotFound() {
        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.findById(PRICE_ID)).thenReturn(null);
            NoSuchElementException thrown = assertThrows(NoSuchElementException.class,
                    () -> resource.updatePrice(PRICE_ID, input));
            assertEquals("Price with id " + PRICE_ID + " not found", thrown.getMessage());
        }
    }

    /**
     * {@code updatePrice} throws {@link NoSuchElementException} when the input names a different
     * product id that does not exist (enters the changed-product validation, new product null).
     */
    @Test
    void updatePriceThrowsWhenNewProductMissing() {
        Price existing = new Price();
        existing.product = product(10L);
        existing.priority = 0;
        existing.startDateTime = START;
        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.productId = 20L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.findById(PRICE_ID)).thenReturn(existing);
            panache.when(() -> Product.findById(20L)).thenReturn(null);
            NoSuchElementException thrown = assertThrows(NoSuchElementException.class,
                    () -> resource.updatePrice(PRICE_ID, input));
            assertEquals("Product with id '20' not found.", thrown.getMessage());
        }
    }

    /**
     * {@code updatePrice} wraps an {@link AlreadyExistsException} in a {@link GraphQLException}
     * when only the priority changes (first key condition false, second true) and a conflict exists.
     */
    @Test
    void updatePriceThrowsWhenPriorityConflicts() {
        Price existing = new Price();
        existing.product = product(10L);
        existing.priority = 0;
        existing.startDateTime = START;
        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.priority = 5;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.findById(PRICE_ID)).thenReturn(existing);
            panache.when(() -> Price.count(UPDATE_COUNT_QUERY, 10L, 5, START, PRICE_ID)).thenReturn(1L);
            GraphQLException thrown = assertThrows(GraphQLException.class,
                    () -> resource.updatePrice(PRICE_ID, input));
            assertEquals("Another price with this priority, start date already exists.", thrown.getMessage());
            assertTrue(thrown.getCause() instanceof AlreadyExistsException);
        }
    }

    /**
     * {@code updatePrice} succeeds when the product changes to an existing one with no conflict
     * (first key condition true), re-reading the new product and applying the supplied fields only.
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void updatePriceChangesProductWithoutConflict() throws GraphQLException {
        Price existing = new Price();
        existing.product = product(10L);
        existing.priceExcludingTax = HT;
        existing.priceIncludingTax = TTC;
        existing.priority = 0;
        existing.startDateTime = START;
        Product newProduct = product(20L);
        BigDecimal newHt = new BigDecimal("99.0000");
        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.productId = 20L;
        input.priceExcludingTax = newHt;
        input.startDateTime = END;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.findById(PRICE_ID)).thenReturn(existing);
            panache.when(() -> Product.findById(20L)).thenReturn(newProduct);
            panache.when(() -> Price.count(UPDATE_COUNT_QUERY, 20L, 0, END, PRICE_ID)).thenReturn(0L);
            Price result = resource.updatePrice(PRICE_ID, input);
            assertSame(existing, result);
            assertSame(newProduct, existing.product);
            assertSame(newHt, existing.priceExcludingTax);
            assertSame(TTC, existing.priceIncludingTax);
            assertEquals(0, existing.priority);
            assertSame(END, existing.startDateTime);
        }
    }

    /**
     * {@code updatePrice} succeeds when only the start date changes (first and second key
     * conditions false, third true) with no conflict, applying every non-null field.
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void updatePriceChangesStartAppliesAllFields() throws GraphQLException {
        Price existing = new Price();
        existing.product = product(10L);
        existing.priceExcludingTax = new BigDecimal("1.0000");
        existing.priceIncludingTax = new BigDecimal("2.0000");
        existing.vatRate = new BigDecimal("0.0550");
        existing.priority = 0;
        existing.startDateTime = START;
        existing.endDateTime = null;
        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.priceExcludingTax = HT;
        input.priceIncludingTax = TTC;
        input.vatRate = VAT;
        input.priority = 0;
        input.startDateTime = END;
        input.endDateTime = END;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.findById(PRICE_ID)).thenReturn(existing);
            panache.when(() -> Price.count(UPDATE_COUNT_QUERY, 10L, 0, END, PRICE_ID)).thenReturn(0L);
            Price result = resource.updatePrice(PRICE_ID, input);
            assertSame(existing, result);
            assertSame(HT, existing.priceExcludingTax);
            assertSame(TTC, existing.priceIncludingTax);
            assertSame(VAT, existing.vatRate);
            assertEquals(0, existing.priority);
            assertSame(END, existing.startDateTime);
            assertSame(END, existing.endDateTime);
        }
    }

    /**
     * {@code updatePrice} skips the conflict check entirely when the input repeats the current
     * product id and leaves all key fields unchanged (all three key conditions false), still
     * re-reading the unchanged product for assignment.
     *
     * @throws GraphQLException never in this success path
     */
    @Test
    void updatePriceNoKeyChangeSkipsConflictCheck() throws GraphQLException {
        Product current = product(10L);
        BigDecimal keptHt = new BigDecimal("1.0000");
        Price existing = new Price();
        existing.product = current;
        existing.priceExcludingTax = keptHt;
        existing.priceIncludingTax = new BigDecimal("2.0000");
        existing.priority = 0;
        existing.startDateTime = START;
        PriceResource.PriceRecord input = new PriceResource.PriceRecord();
        input.productId = 10L;
        input.priceIncludingTax = TTC;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.findById(PRICE_ID)).thenReturn(existing);
            panache.when(() -> Product.findById(10L)).thenReturn(current);
            Price result = resource.updatePrice(PRICE_ID, input);
            assertSame(existing, result);
            assertSame(current, existing.product);
            assertSame(keptHt, existing.priceExcludingTax);
            assertSame(TTC, existing.priceIncludingTax);
            assertEquals(0, existing.priority);
            assertSame(START, existing.startDateTime);
            panache.verify(() -> Price.count(UPDATE_COUNT_QUERY, 10L, 0, START, PRICE_ID), times(0));
        }
    }

    /**
     * {@code deletePrice} returns true when the underlying delete removed a row.
     *
     * @throws GraphQLException never in this path
     */
    @Test
    void deletePriceReturnsTrueWhenDeleted() throws GraphQLException {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.deleteById(PRICE_ID)).thenReturn(true);
            boolean result = resource.deletePrice(PRICE_ID);
            assertTrue(result);
        }
    }

    /**
     * {@code deletePrice} returns false when no row matched the id.
     *
     * @throws GraphQLException never in this path
     */
    @Test
    void deletePriceReturnsFalseWhenNothingDeleted() throws GraphQLException {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.deleteById(PRICE_ID)).thenReturn(false);
            boolean result = resource.deletePrice(PRICE_ID);
            assertFalse(result);
        }
    }

    /**
     * {@code PriceRecord.toString} renders every field in the documented order.
     */
    @Test
    void priceRecordToStringRendersAllFields() {
        PriceResource.PriceRecord input = createInput(PRODUCT_ID);
        String expected = "PriceRecord [productId=" + PRODUCT_ID +
                ", priceExcludingTax=" + HT +
                ", priceIncludingTax=" + TTC +
                ", vatRate=" + VAT +
                ", priority=" + PRIORITY +
                ", startDateTime=" + START +
                ", endDateTime=" + END + "]";
        assertEquals(expected, input.toString());
    }
}
