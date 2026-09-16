package com.intermarche.pos.domain.catalog;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
     * Builds a weight-sold product with the given reference weight, leaving
     * other fields at their declared defaults.
     *
     * @param referenceWeight the per-unit reference weight, possibly null
     * @return the configured product
     */
    private Product weightProduct(BigDecimal referenceWeight) {
        Product product = new Product();
        product.productType = ProductType.WEIGHT;
        product.referenceWeight = referenceWeight;
        return product;
    }

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
     * standardQuantity returns the raw quantity for UNIT products
     * (first if arm true).
     */
    @Test
    void standardQuantityUnitReturnsRawQuantity() {
        Product product = new Product();
        product.productType = ProductType.UNIT;
        Assertions.assertEquals(BigDecimal.valueOf(3.0), product.standardQuantity(3.0));
    }

    /**
     * standardQuantity returns zero for a non-UNIT product whose reference
     * weight is null (first if arm false, first OR arm true).
     */
    @Test
    void standardQuantityNullReferenceReturnsZero() {
        Product product = weightProduct(null);
        Assertions.assertEquals(BigDecimal.ZERO, product.standardQuantity(2.0));
    }

    /**
     * standardQuantity returns zero for a non-UNIT product whose reference
     * weight is zero (first if arm false, second OR arm true).
     */
    @Test
    void standardQuantityZeroReferenceReturnsZero() {
        Product product = weightProduct(BigDecimal.ZERO);
        Assertions.assertEquals(BigDecimal.ZERO, product.standardQuantity(2.0));
    }

    /**
     * standardQuantity divides the quantity by a positive reference weight,
     * scaling to six decimals (first if arm false, both OR arms false).
     */
    @Test
    void standardQuantityDividesByPositiveReference() {
        Product product = weightProduct(new BigDecimal("0.250"));
        BigDecimal expected = BigDecimal.valueOf(1.0)
                .divide(new BigDecimal("0.250"), 6, RoundingMode.HALF_UP);
        Assertions.assertEquals(expected, product.standardQuantity(1.0));
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
                ProductType.WEIGHT, null, true, false, null, null, false, product.attributes);
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
        int expected = Objects.hash("3760001", "1234", "Melon", "Sweet", "Farm",
                new BigDecimal("1.500"), new BigDecimal("0.750"), ProductType.UNIT,
                "pcs", false, true, "MELON JAUNE", "INT-42", false, product.attributes);
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
}
