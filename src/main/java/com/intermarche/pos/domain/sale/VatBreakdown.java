package com.intermarche.pos.domain.sale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-rate VAT ventilation of a ticket.
 * <p>
 * Tax-included line totals (rounded to the cent) are accumulated per VAT rate;
 * the tax-excluded amount of each bucket is derived from the bucket total
 * ({@code HT = TTC / (1 + rate)}, rounded to the cent) — the standard French
 * receipt practice, shared by the fiscal persistence and the printed ticket so
 * both always agree.
 * <p>
 * This is the concrete form of the phase 1 unification: the ticket total is
 * the sum of the per-line tax-included totals rounded to the cent, and the
 * HT/VAT split is derived per rate bucket from those same rounded amounts —
 * so the printed ticket, the persisted totals, the refund ventilation and
 * the digital receipt can never disagree by a cent. Rates are normalized
 * with {@code stripTrailingZeros} so 0.20 and 0.2000 land in the same
 * bucket.
 */
public final class VatBreakdown {

    /** Tax-included totals accumulated per rate, sorted by rate. */
    private final Map<BigDecimal, BigDecimal> ttcByRate = new TreeMap<>();

    /**
     * A computed ventilation bucket for one VAT rate.
     */
    public static final class Bucket {
        /** The VAT rate of the bucket (e.g. 0.2000). */
        public final BigDecimal rate;
        /** The tax-included total of the bucket. */
        public final BigDecimal totalIncludingTax;
        /** The tax-excluded total of the bucket, rounded to the cent. */
        public final BigDecimal totalExcludingTax;
        /** The VAT amount of the bucket (TTC minus HT). */
        public final BigDecimal vatAmount;

        /**
         * Creates a computed bucket.
         *
         * @param rate the VAT rate
         * @param totalIncludingTax the tax-included total
         * @param totalExcludingTax the tax-excluded total
         * @param vatAmount the VAT amount
         */
        private Bucket(BigDecimal rate, BigDecimal totalIncludingTax,
                       BigDecimal totalExcludingTax, BigDecimal vatAmount) {
            this.rate = rate;
            this.totalIncludingTax = totalIncludingTax;
            this.totalExcludingTax = totalExcludingTax;
            this.vatAmount = vatAmount;
        }

        /**
         * Returns the rate formatted as a percentage for display (e.g. "20,00%").
         *
         * @return the formatted rate
         */
        public String getRateFormatted() {
            return String.format("%.2f%%", rate.multiply(BigDecimal.valueOf(100))).replace(".", ",");
        }
    }

    /**
     * Accumulates a tax-included line total under its VAT rate.
     *
     * @param rate the VAT rate of the line, or null for 0%
     * @param lineTotalIncludingTax the tax-included line total (rounded to the cent)
     */
    public void add(BigDecimal rate, BigDecimal lineTotalIncludingTax) {
        BigDecimal key = (rate != null ? rate : BigDecimal.ZERO).stripTrailingZeros();
        ttcByRate.merge(key, lineTotalIncludingTax, BigDecimal::add);
    }

    /**
     * Returns the computed buckets, sorted by ascending rate.
     *
     * @return the ventilation buckets
     */
    public List<Bucket> getBuckets() {
        List<Bucket> buckets = new ArrayList<>();
        for (Map.Entry<BigDecimal, BigDecimal> entry : ttcByRate.entrySet()) {
            BigDecimal rate = entry.getKey();
            BigDecimal ttc = entry.getValue();
            BigDecimal ht = ttc.divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
            buckets.add(new Bucket(rate, ttc, ht, ttc.subtract(ht)));
        }
        return buckets;
    }

    /**
     * Returns the tax-included total across every bucket.
     *
     * @return the ticket tax-included total
     */
    public BigDecimal getTotalIncludingTax() {
        return ttcByRate.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Returns the tax-excluded total across every bucket.
     *
     * @return the ticket tax-excluded total
     */
    public BigDecimal getTotalExcludingTax() {
        return getBuckets().stream().map(b -> b.totalExcludingTax).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Returns the VAT total across every bucket.
     *
     * @return the ticket VAT total
     */
    public BigDecimal getTotalVat() {
        return getBuckets().stream().map(b -> b.vatAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
