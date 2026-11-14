package com.intermarche.pos.ui.payment;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cash key calculations for LC-07-02-02/-09 and LC-07-03-04. */
class CashAmountSuggestionsTest {

    /** LC-07-02-02: all four different upper increments produce five keys. */
    @Test
    void distinctUpperAmounts() {
        assertEquals(List.of(new BigDecimal("21.42"), new BigDecimal("22.00"),
                new BigDecimal("25.00"), new BigDecimal("30.00"), new BigDecimal("40.00")),
                CashAmountSuggestions.amounts(new BigDecimal("21.42"), 0));
    }

    /** LC-07-02-02: repeated suggestions are omitted, covering the duplicate arm. */
    @Test
    void duplicateUpperAmountsDisappear() {
        assertEquals(List.of(new BigDecimal("9.42"), new BigDecimal("10.00"), new BigDecimal("20.00")),
                CashAmountSuggestions.amounts(new BigDecimal("9.42"), 0));
    }

    /** LC-07-03-04: rounding down changes the dedicated exact key. */
    @Test
    void exactKeyRoundsDown() {
        assertEquals(new BigDecimal("23.40"),
                CashAmountSuggestions.amounts(new BigDecimal("23.42"), 5).get(0));
    }

    /** LC-07-03-04: rounding up changes the dedicated exact key. */
    @Test
    void exactKeyRoundsUp() {
        assertEquals(new BigDecimal("23.45"),
                CashAmountSuggestions.amounts(new BigDecimal("23.43"), 5).get(0));
    }

    /** LC-07-02-03: a null remainder retains a zero exact key. */
    @Test
    void nullRemainder() {
        assertEquals(new BigDecimal("0.00"), CashAmountSuggestions.amounts(null, 0).get(0));
    }

    /** LC-07-02-03: zero remains an available dedicated exact key. */
    @Test
    void zeroRemainder() {
        assertEquals(new BigDecimal("0.00"), CashAmountSuggestions.amounts(BigDecimal.ZERO, 0).get(0));
    }

    /** LC-07-02-03: refunds do not suggest a negative cash tender. */
    @Test
    void negativeRemainder() {
        assertEquals(new BigDecimal("0.00"),
                CashAmountSuggestions.amounts(new BigDecimal("-1.00"), 0).get(0));
    }

    /** LC-07-02-04/-07: a whole increment suggests the strictly greater increment. */
    @Test
    void wholeAmount() {
        assertEquals(List.of(new BigDecimal("20.00"), new BigDecimal("21.00"),
                new BigDecimal("25.00"), new BigDecimal("30.00"), new BigDecimal("40.00")),
                CashAmountSuggestions.amounts(new BigDecimal("20.00"), 0));
    }

    /** LC-07-03-04: an incompatible upper suggestion is omitted. */
    @Test
    void incompatibleUpperAmountsDisappear() {
        assertEquals(List.of(new BigDecimal("0.00")),
                CashAmountSuggestions.amounts(BigDecimal.ZERO, 3000));
    }

    /** LC-07-02-09: all euro denominations remain available without rounding. */
    @Test
    void denominationsWithoutRounding() {
        List<BigDecimal> amounts = CashAmountSuggestions.denominations(0);
        assertEquals(15, amounts.size());
        assertEquals(new BigDecimal("0.01"), amounts.get(0));
        assertEquals(new BigDecimal("500.00"), amounts.get(14));
    }

    /** LC-07-03-04: forbidden small coins disappear, covering both filter arms. */
    @Test
    void denominationsWithRounding() {
        List<BigDecimal> amounts = CashAmountSuggestions.denominations(5);
        assertEquals(13, amounts.size());
        assertFalse(amounts.contains(new BigDecimal("0.01")));
        assertFalse(amounts.contains(new BigDecimal("0.02")));
        assertTrue(amounts.contains(new BigDecimal("0.05")));
    }
}
