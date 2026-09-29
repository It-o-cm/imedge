package com.intermarche.pos.domain.payment;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link Currency}, the accepted foreign currency and its
 * administered rate.
 *
 * <p>The static finders are Panache queries that resolve to
 * {@link PanacheEntityBase} under plain {@code mvn test}, so they are mocked with
 * {@link org.mockito.Mockito#mockStatic}. The conversion and display methods are
 * exercised directly on a plain instance, both arms of every guard.
 */
class CurrencyTest {

    /**
     * Builds a Swiss-franc currency with the given rate and symbol, so each test
     * states exactly the fields it depends on.
     *
     * @param euroPerUnit the euros one unit is worth
     * @param symbol      the symbol printed beside an amount, or null
     * @return the currency under test
     */
    private Currency currency(BigDecimal euroPerUnit, String symbol) {
        Currency currency = new Currency();
        currency.code = "CHF";
        currency.label = "FRANC SUISSE";
        currency.symbol = symbol;
        currency.euroPerUnit = euroPerUnit;
        return currency;
    }

    /**
     * {@code listActive} runs the active-ordered query and returns its result
     * verbatim.
     */
    @Test
    void listActiveRunsOrderedActiveQuery() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            List<Currency> expected = List.of(new Currency());
            panache.when(() -> Currency.list("active = true order by displayOrder, code"))
                    .thenReturn(expected);
            List<Currency> result = Currency.listActive();
            assertSame(expected, result);
        }
    }

    /**
     * {@code findActiveByCode} returns the first active match for the posted code.
     */
    @Test
    void findActiveByCodeReturnsFirstActiveMatch() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            Currency expected = currency(BigDecimal.ONE, "₣");
            @SuppressWarnings("unchecked")
            PanacheQuery<Currency> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> Currency.find("code = ?1 and active = true", "CHF"))
                    .thenReturn(query);
            assertSame(expected, Currency.findActiveByCode("CHF"));
        }
    }

    /**
     * {@code findActiveByCode} returns null when no active currency matches.
     */
    @Test
    void findActiveByCodeReturnsNullWhenUnknown() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Currency> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> Currency.find("code = ?1 and active = true", "ZZZ"))
                    .thenReturn(query);
            assertNull(Currency.findActiveByCode("ZZZ"));
        }
    }

    /**
     * {@code toEuro} returns 0.00 for a null amount (the null arm of the guard).
     */
    @Test
    void toEuroReturnsZeroForNullAmount() {
        Currency currency = currency(new BigDecimal("1.05"), "₣");
        assertEquals(new BigDecimal("0.00"), currency.toEuro(null));
    }

    /**
     * {@code toEuro} multiplies by the rate and rounds half-up to the cent (the
     * non-null arm of the guard).
     */
    @Test
    void toEuroMultipliesByRateAndRoundsHalfUp() {
        Currency currency = currency(new BigDecimal("1.055"), "₣");
        assertEquals(new BigDecimal("1.06"), currency.toEuro(BigDecimal.ONE));
    }

    /**
     * {@code fromEuro} returns 0.00 when the euro amount is null (first condition
     * of the compound guard).
     */
    @Test
    void fromEuroReturnsZeroForNullEuroAmount() {
        Currency currency = currency(new BigDecimal("1.05"), "₣");
        assertEquals(new BigDecimal("0.00"), currency.fromEuro(null));
    }

    /**
     * {@code fromEuro} returns 0.00 when the rate is null (second condition of the
     * compound guard).
     */
    @Test
    void fromEuroReturnsZeroForNullRate() {
        Currency currency = currency(null, "₣");
        assertEquals(new BigDecimal("0.00"), currency.fromEuro(new BigDecimal("10.50")));
    }

    /**
     * {@code fromEuro} returns 0.00 when the rate is zero (third condition of the
     * compound guard).
     */
    @Test
    void fromEuroReturnsZeroForZeroRate() {
        Currency currency = currency(BigDecimal.ZERO, "₣");
        assertEquals(new BigDecimal("0.00"), currency.fromEuro(new BigDecimal("10.50")));
    }

    /**
     * {@code fromEuro} divides by the rate and rounds half-up (all guard conditions
     * false).
     */
    @Test
    void fromEuroDividesByRateAndRoundsHalfUp() {
        Currency currency = currency(new BigDecimal("1.05"), "₣");
        assertEquals(new BigDecimal("10.00"), currency.fromEuro(new BigDecimal("10.50")));
    }

    /**
     * {@code displayUnit} returns the symbol when it is present and non-blank (both
     * conditions true).
     */
    @Test
    void displayUnitReturnsSymbolWhenPresent() {
        Currency currency = currency(BigDecimal.ONE, "₣");
        assertEquals("₣", currency.displayUnit());
    }

    /**
     * {@code displayUnit} falls back to the code when the symbol is null (first
     * condition false).
     */
    @Test
    void displayUnitReturnsCodeWhenSymbolNull() {
        Currency currency = currency(BigDecimal.ONE, null);
        assertEquals("CHF", currency.displayUnit());
    }

    /**
     * {@code displayUnit} falls back to the code when the symbol is blank (second
     * condition false).
     */
    @Test
    void displayUnitReturnsCodeWhenSymbolBlank() {
        Currency currency = currency(BigDecimal.ONE, "   ");
        assertEquals("CHF", currency.displayUnit());
    }
}
