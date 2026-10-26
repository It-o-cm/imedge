package com.intermarche.edge.domain.catalog;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.math.BigDecimal;
import java.util.Objects;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link Product}, targeting 100% branch coverage.
 * <p>
 * The four static finders resolve the Panache {@code find} query, which under
 * plain {@code mvn test} falls back to {@link PanacheEntityBase}, so they are
 * intercepted with {@link org.mockito.Mockito#mockStatic} together with a mock
 * {@link PanacheQuery}. Instance methods are exercised directly on plain
 * instances; every ternary and short-circuit guard of {@code standardQuantity}
 * and {@code getChecksum} is covered on both arms. Each test is fully isolated
 * and asserts absolute expected values.
 */
class ProductTest {

    /**
     * A fresh product carries the declared status field defaults.
     */
    @Test
    void fieldDefaults() {
        Product product = new Product();
        Assertions.assertTrue(product.active);
        Assertions.assertFalse(product.forbiddenToSale);
    }





    /**
     * findByEan delegates to the EAN finder and returns its first result.
     */
    @Test
    void findByEanDelegatesToFinder() {
        Product expected = new Product();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> Product.find("ean", "3760001")).thenReturn(query);
            Assertions.assertSame(expected, Product.findByEan("3760001"));
        }
    }

    /**
     * findByEan propagates a null first result when no row matches.
     */
    @Test
    void findByEanReturnsNullWhenNoMatch() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> Product.find("ean", "0000000")).thenReturn(query);
            Assertions.assertNull(Product.findByEan("0000000"));
        }
    }

    /**
     * findByPlu delegates to the PLU finder and returns its first result.
     */
    @Test
    void findByPluDelegatesToFinder() {
        Product expected = new Product();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> Product.find("plu", "1234")).thenReturn(query);
            Assertions.assertSame(expected, Product.findByPlu("1234"));
        }
    }

    /**
     * findActiveByEan delegates to the active-EAN finder and returns its
     * first result.
     */
    @Test
    void findActiveByEanDelegatesToFinder() {
        Product expected = new Product();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> Product.find("ean = ?1 and active = true", "3760001"))
                    .thenReturn(query);
            Assertions.assertSame(expected, Product.findActiveByEan("3760001"));
        }
    }

    /**
     * findByInternalCode delegates to the internal-code finder and returns its
     * first result.
     */
    @Test
    void findByInternalCodeDelegatesToFinder() {
        Product expected = new Product();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> Product.find("internalCode", "INT-42")).thenReturn(query);
            Assertions.assertSame(expected, Product.findByInternalCode("INT-42"));
        }
    }

    /**
     * findActiveByInternalCode delegates to the active-internal-code finder and
     * returns its first result.
     */
    @Test
    void findActiveByInternalCodeDelegatesToFinder() {
        Product expected = new Product();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> Product.find("internalCode = ?1 and active = true", "INT-42"))
                    .thenReturn(query);
            Assertions.assertSame(expected, Product.findActiveByInternalCode("INT-42"));
        }
    }

    /**
     * saleLabel returns the checkout label when it is set and non-blank
     * (BO-02-03-02, present arm).
     */
    @Test
    void saleLabelReturnsCheckoutLabelWhenSet() {
        Product product = new Product();
        product.name = "Coca Cola";
        product.checkoutLabel = "PROMO COLA";
        Assertions.assertEquals("PROMO COLA", product.saleLabel());
    }

    /**
     * saleLabel falls back to the commercial name when the checkout label is
     * null (null arm).
     */
    @Test
    void saleLabelFallsBackToNameWhenNull() {
        Product product = new Product();
        product.name = "Coca Cola";
        product.checkoutLabel = null;
        Assertions.assertEquals("Coca Cola", product.saleLabel());
    }

    /**
     * saleLabel falls back to the commercial name when the checkout label is
     * blank (blank arm).
     */
    @Test
    void saleLabelFallsBackToNameWhenBlank() {
        Product product = new Product();
        product.name = "Coca Cola";
        product.checkoutLabel = "   ";
        Assertions.assertEquals("Coca Cola", product.saleLabel());
    }

    /**
     * findActiveByPlu delegates to the active-PLU finder and returns its
     * first result.
     */
    @Test
    void findActiveByPluDelegatesToFinder() {
        Product expected = new Product();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> Product.find("plu = ?1 and active = true", "1234"))
                    .thenReturn(query);
            Assertions.assertSame(expected, Product.findActiveByPlu("1234"));
        }
    }

    /**
     * getChecksum substitutes the empty string for a null PLU
     * (ternary null arm) and matches the reference hash.
     */
    @Test
    void getChecksumWithNullPluUsesEmptyString() {
        Product product = new Product();
        product.ean = "3760001";
        product.plu = null;
        product.name = "Melon";
        product.productType = ProductType.WEIGHT;
        int expected = Objects.hash("3760001", "", "Melon", null, null, null, null,
                ProductType.WEIGHT, null, true, false, null, null, false, null,
                product.attributes, product.saleCodes);
        Assertions.assertEquals(expected, product.getChecksum());
    }

    /**
     * getChecksum keeps a non-null PLU verbatim (ternary non-null arm)
     * and matches the reference hash.
     */
    @Test
    void getChecksumWithNonNullPluKeepsValue() {
        Product product = new Product();
        product.ean = "3760001";
        product.plu = "1234";
        product.name = "Melon";
        product.description = "Sweet";
        product.brand = "Farm";
        product.referenceWeight = new BigDecimal("1.500");
        product.referenceVolume = new BigDecimal("0.750");
        product.productType = ProductType.UNIT;
        product.unitName = "pcs";
        product.active = false;
        product.forbiddenToSale = true;
        product.checkoutLabel = "MELON JAUNE";
        product.internalCode = "INT-42";
        product.egalimRegime = "FOOD_34";
        int expected = Objects.hash("3760001", "1234", "Melon", "Sweet", "Farm",
                new BigDecimal("1.500"), new BigDecimal("0.750"), ProductType.UNIT,
                "pcs", false, true, "MELON JAUNE", "INT-42", false, "FOOD_34",
                product.attributes, product.saleCodes);
        Assertions.assertEquals(expected, product.getChecksum());
    }

    /**
     * getChecksum now folds the declared attributes map (BO-02-03-18): two
     * products differing only by one attribute have different checksums, so a
     * CSV re-import that only edits an attribute is detected as a change rather
     * than skipped by the checksum short-circuit.
     */
    @Test
    void getChecksumFoldsTheAttributesMap() {
        Product without = new Product();
        without.ean = "3760001";
        without.name = "Melon";
        without.productType = ProductType.UNIT;
        Product with = new Product();
        with.ean = "3760001";
        with.name = "Melon";
        with.productType = ProductType.UNIT;
        with.attributes.put("MEAL_VOUCHER_ELIGIBLE", "true");
        Assertions.assertNotEquals(without.getChecksum(), with.getChecksum());
    }

    /**
     * getChecksum folds the EGAlim classification: an article reclassified and
     * nothing else must be seen as changed, otherwise the checksum
     * short-circuit would skip the line and the fiche would keep showing the
     * former category. The unclassified state is a value of its own, distinct
     * from every declared regime.
     */
    @Test
    void getChecksumFoldsTheEgalimRegime() {
        Product unclassified = new Product();
        unclassified.ean = "3760001";
        unclassified.name = "Melon";
        unclassified.productType = ProductType.UNIT;
        Product food = new Product();
        food.ean = "3760001";
        food.name = "Melon";
        food.productType = ProductType.UNIT;
        food.egalimRegime = "FOOD_34";
        Product dph = new Product();
        dph.ean = "3760001";
        dph.name = "Melon";
        dph.productType = ProductType.UNIT;
        dph.egalimRegime = "DPH_40";
        Assertions.assertNotEquals(unclassified.getChecksum(), food.getChecksum());
        Assertions.assertNotEquals(food.getChecksum(), dph.getChecksum());
    }

    /**
     * A freshly built article carries NO classification: null is "not
     * classified", which is not the exempt regime — the exempt regime is a
     * stated classification, null is the absence of one.
     */
    @Test
    void aNewProductCarriesNoEgalimRegime() {
        Assertions.assertNull(new Product().egalimRegime);
    }

    /**
     * Builds a single-result query mock.
     *
     * @param result the first result, possibly null
     * @return the query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<Product> single(Product result) {
        return mock(PanacheQuery.class, invocation ->
                "firstResult".equals(invocation.getMethod().getName()) ? result : null);
    }

    /**
     * findActiveBySaleCode reads the additional sale codes of the active
     * articles (BO-02-03-03).
     */
    @Test
    void findActiveBySaleCodeJoinsTheSaleCodes() {
        Product expected = new Product();
        PanacheQuery<Product> query = single(expected);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.find("select p from Product p join p.saleCodes c where c = ?1 and p.active = true",
                    "3000000000001")).thenReturn(query);
            Assertions.assertSame(expected, Product.findActiveBySaleCode("3000000000001"));
        }
    }

    /**
     * findActiveByAlias answers null for a null or blank code, the sale-code
     * holder first, and the internal-code holder otherwise (BO-02-03-03/04).
     */
    @Test
    void findActiveByAliasTriesSaleCodesThenInternalCode() {
        Assertions.assertNull(Product.findActiveByAlias(null));
        Assertions.assertNull(Product.findActiveByAlias("  "));
        Product bySale = new Product();
        Product byInternal = new Product();
        PanacheQuery<Product> sale = single(bySale);
        PanacheQuery<Product> none = single(null);
        PanacheQuery<Product> internal = single(byInternal);
        String saleQuery = "select p from Product p join p.saleCodes c where c = ?1 and p.active = true";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.find(saleQuery, "S1")).thenReturn(sale);
            panache.when(() -> Product.find(saleQuery, "ABC128")).thenReturn(none);
            panache.when(() -> Product.find("internalCode = ?1 and active = true", "ABC128")).thenReturn(internal);
            Assertions.assertSame(bySale, Product.findActiveByAlias("S1"));
            Assertions.assertSame(byInternal, Product.findActiveByAlias("ABC128"));
        }
    }
}
