package com.intermarche.pos.ui.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Cash amount keys for LC-07-02-02 through LC-07-02-09 and LC-07-03-04. */
public final class CashAmountSuggestions {

    /** Euro increments used by the four optional round-up keys. */
    private static final int[] INCREMENTS = {1, 5, 10, 20};

    /** Euro coins and notes, expressed in cents (LC-07-02-09). */
    private static final int[] DENOMINATIONS = {
            1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000};

    /** Prevents instantiation of this pure calculation helper. */
    private CashAmountSuggestions() {
    }

    /**
     * Returns the exact cash amount followed by distinct upper round amounts.
     * Duplicate keys disappear so LC-07-02-02 always offers one to five keys.
     *
     * @param remaining the unpaid amount
     * @param stepCents the administered cash rounding step
     * @return the suggestions in increasing order, with the exact amount first
     */
    public static List<BigDecimal> amounts(BigDecimal remaining, int stepCents) {
        BigDecimal exact = CashRounding.round(remaining, stepCents).max(BigDecimal.ZERO).setScale(2);
        List<BigDecimal> amounts = new ArrayList<>();
        amounts.add(exact);
        for (int increment : INCREMENTS) {
            BigDecimal unit = BigDecimal.valueOf(increment);
            BigDecimal upper = exact.divide(unit, 0, RoundingMode.FLOOR)
                    .add(BigDecimal.ONE).multiply(unit).setScale(2);
            if (!CashRounding.isTenderable(upper, stepCents)) {
                continue;
            }
            if (!amounts.contains(upper)) {
                amounts.add(upper);
            }
        }
        return amounts;
    }

    /**
     * Returns denomination keys which can be tendered under the cash rule.
     * LC-07-03-04 excludes coins which the administered rounding removes.
     *
     * @param stepCents the administered cash rounding step
     * @return the available denominations in increasing order
     */
    public static List<BigDecimal> denominations(int stepCents) {
        List<BigDecimal> amounts = new ArrayList<>();
        for (int cents : DENOMINATIONS) {
            BigDecimal amount = BigDecimal.valueOf(cents, 2);
            if (CashRounding.isTenderable(amount, stepCents)) {
                amounts.add(amount);
            }
        }
        return amounts;
    }
}
