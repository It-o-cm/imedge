package com.intermarche.pos.domain.catalog;

import com.intermarche.pos.domain.util.DateTimeProvider;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link Price}, targeting 100% branch coverage.
 * <p>
 * The static finders resolve the Panache {@code find} query, which under plain
 * {@code mvn test} falls back to {@link PanacheEntityBase}, so they are
 * intercepted with {@link org.mockito.Mockito#mockStatic} together with a mock
 * {@link PanacheQuery}. Time-sensitive logic is pinned by mocking
 * {@link DateTimeProvider#now()}. Every ternary and short-circuit guard of
 * {@code isActive} is covered on both arms. Each test is fully isolated and
 * asserts absolute expected values.
 */
class PriceTest {

    /**
     * The exact JPQL query string built by {@link Price#findActivePriceAtDate}.
     */
    private static final String QUERY =
            "product.id = ?1 "
                    + "and (startDateTime is null or startDateTime <= ?2) "
                    + "and (endDateTime is null or endDateTime > ?2) "
                    + "order by priority DESC";

    /**
     * The exact JPQL query string built by {@link Price#findByProduct}.
     */
    private static final String HISTORY_QUERY =
            "product.id = ?1 order by startDateTime desc, priority desc";

    /**
     * Builds a price with the given validity bounds, leaving other fields at
     * their declared defaults.
     *
     * @param start the start bound, possibly null
     * @param end   the end bound, possibly null
     * @return the configured price
     */
    private Price withWindow(LocalDateTime start, LocalDateTime end) {
        Price price = new Price();
        price.startDateTime = start;
        price.endDateTime = end;
        return price;
    }

    /**
     * A fresh price carries the declared priority default of zero.
     */
    @Test
    void fieldDefaults() {
        Price price = new Price();
        Assertions.assertEquals(0, price.priority);
    }

    /**
     * findActivePriceAtDate delegates to the priority-ordered validity finder
     * and returns its first result.
     */
    @Test
    void findActivePriceAtDateDelegatesToFinder() {
        LocalDateTime date = LocalDateTime.of(2026, 8, 3, 10, 0);
        Price expected = new Price();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Price> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> Price.find(QUERY, 42L, date)).thenReturn(query);
            Assertions.assertSame(expected, Price.findActivePriceAtDate(42L, date));
        }
    }

    /**
     * findActivePriceAtDate propagates a null first result when no row matches.
     */
    @Test
    void findActivePriceAtDateReturnsNullWhenNoMatch() {
        LocalDateTime date = LocalDateTime.of(2026, 8, 3, 10, 0);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Price> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> Price.find(QUERY, 7L, date)).thenReturn(query);
            Assertions.assertNull(Price.findActivePriceAtDate(7L, date));
        }
    }

    /**
     * findCurrentPrice resolves "now" through the DateTimeProvider and forwards
     * it to the validity finder.
     */
    @Test
    void findCurrentPriceUsesProviderNow() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 3, 12, 0);
        Price expected = new Price();
        try (MockedStatic<DateTimeProvider> time = mockStatic(DateTimeProvider.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            time.when(DateTimeProvider::now).thenReturn(now);
            @SuppressWarnings("unchecked")
            PanacheQuery<Price> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> Price.find(QUERY, 99L, now)).thenReturn(query);
            Assertions.assertSame(expected, Price.findCurrentPrice(99L));
        }
    }

    /**
     * findByProduct delegates to the most-recent-first history list finder and
     * returns its rows verbatim.
     */
    @Test
    void findByProductListsHistoryMostRecentFirst() {
        Price price = new Price();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.list(HISTORY_QUERY, 42L))
                    .thenReturn(java.util.List.of(price));
            Assertions.assertEquals(java.util.List.of(price), Price.findByProduct(42L));
        }
    }

    /**
     * isActive is true when both bounds are null (both null-guard true arms).
     */
    @Test
    void isActiveTrueWhenBothBoundsNull() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 3, 12, 0);
        try (MockedStatic<DateTimeProvider> time = mockStatic(DateTimeProvider.class)) {
            time.when(DateTimeProvider::now).thenReturn(now);
            Assertions.assertTrue(withWindow(null, null).isActive());
        }
    }

    /**
     * isActive is true when the start is strictly before now (isBefore arm) and
     * the end is strictly after now (isAfter true arm).
     */
    @Test
    void isActiveTrueWhenStartBeforeAndEndAfter() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 3, 12, 0);
        try (MockedStatic<DateTimeProvider> time = mockStatic(DateTimeProvider.class)) {
            time.when(DateTimeProvider::now).thenReturn(now);
            Price price = withWindow(now.minusHours(1), now.plusHours(1));
            Assertions.assertTrue(price.isActive());
        }
    }

    /**
     * isActive is true when the start equals now (isBefore false, isEqual true
     * arm) and the end bound is null.
     */
    @Test
    void isActiveTrueWhenStartEqualsNowAndEndNull() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 3, 12, 0);
        try (MockedStatic<DateTimeProvider> time = mockStatic(DateTimeProvider.class)) {
            time.when(DateTimeProvider::now).thenReturn(now);
            Price price = withWindow(now, null);
            Assertions.assertTrue(price.isActive());
        }
    }

    /**
     * isActive is false when the start is after now: startValid is false
     * (isBefore false, isEqual false) and the AND short-circuits.
     */
    @Test
    void isActiveFalseWhenStartAfterNow() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 3, 12, 0);
        try (MockedStatic<DateTimeProvider> time = mockStatic(DateTimeProvider.class)) {
            time.when(DateTimeProvider::now).thenReturn(now);
            Price price = withWindow(now.plusHours(1), null);
            Assertions.assertFalse(price.isActive());
        }
    }

    /**
     * isActive is false when the start is null (startValid true) but the end is
     * not after now (isAfter false arm), exercising the endValid false path.
     */
    @Test
    void isActiveFalseWhenEndNotAfterNow() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 3, 12, 0);
        try (MockedStatic<DateTimeProvider> time = mockStatic(DateTimeProvider.class)) {
            time.when(DateTimeProvider::now).thenReturn(now);
            Price price = withWindow(null, now);
            Assertions.assertFalse(price.isActive());
        }
    }

    /**
     * getChecksum combines the product EAN, pricing amounts, priority and
     * validity bounds into the expected Objects.hash value.
     */
    @Test
    void getChecksumHashesSignificantFields() {
        Product product = new Product();
        product.ean = "3760091729318";
        Price price = new Price();
        price.product = product;
        price.priceExcludingTax = new BigDecimal("1.0000");
        price.priceIncludingTax = new BigDecimal("1.2000");
        price.vatRate = new BigDecimal("0.2000");
        price.priority = 5;
        price.startDateTime = LocalDateTime.of(2026, 8, 1, 0, 0);
        price.endDateTime = LocalDateTime.of(2026, 8, 31, 0, 0);
        int expected = Objects.hash(product.ean, price.priceExcludingTax,
                price.priceIncludingTax, price.vatRate, price.priority,
                price.startDateTime, price.endDateTime);
        Assertions.assertEquals(expected, price.getChecksum());
    }
}
