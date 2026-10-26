package com.intermarche.edge.domain.sale;

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
 * <p>
 * A bucket is a REGIME when the lines name theirs (BO-02-02-01): two regimes
 * of the same percentage — two Portuguese exemptions on two legal grounds —
 * are two buckets, each with its own code and its own mention, where a
 * ventilation by rate alone would have merged them under whichever regime the
 * table returns first. Lines that name no regime keep the historical bucket of
 * their rate.
 * <p>
 * An EXEMPT line (BO-02-03-26) is never merged into the 0&nbsp;% bucket: it is
 * ventilated under the EXO heading of its regime, and a line sold on behalf of
 * a THIRD PARTY (BO-02-03-42) under a third-party bucket of its rate, so the
 * documents state that VAT apart and the store's ventilations can leave it out.
 */
public final class VatBreakdown {

    /**
     * Tax-included totals accumulated per bucket identity: the store's own
     * regimes first, then those sold on behalf of a third party, then the
     * exemptions, each family sorted by rate then regime.
     */
    private final Map<Key, BigDecimal> ttcByRate = new TreeMap<>();

    /**
     * The identity of a bucket: its rate, the number of its regime when the
     * lines name one (BO-02-02-01), and the heading it is printed under — an
     * EXEMPTION (BO-02-03-26) or a sale on behalf of a THIRD PARTY
     * (BO-02-03-42).
     *
     * @param rate the normalised rate, zero for an exemption
     * @param number the regime number, or null when the lines name none
     * @param exempt whether the bucket gathers exempt lines
     * @param thirdParty whether the bucket gathers lines sold for a third party
     */
    private record Key(BigDecimal rate, Integer number, boolean exempt, boolean thirdParty)
            implements Comparable<Key> {

        /**
         * Orders the buckets by family (the store's own, the third party's, the
         * exemptions), then by rate, then by regime number, the unnamed regime
         * of a rate first.
         *
         * @param other the other key
         * @return the comparison
         */
        @Override
        public int compareTo(Key other) {
            int byFamily = Integer.compare(family(), other.family());
            if (byFamily != 0) {
                return byFamily;
            }
            int byRate = rate.compareTo(other.rate);
            if (byRate != 0) {
                return byRate;
            }
            return Integer.compare(number == null ? Integer.MIN_VALUE : number,
                    other.number == null ? Integer.MIN_VALUE : other.number);
        }

        /**
         * The rank of the bucket's family in the ventilation.
         *
         * @return 0 for the store's own rates, 1 for a third party's, 2 for an exemption
         */
        private int family() {
            if (exempt) {
                return 2;
            }
            return thirdParty ? 1 : 0;
        }
    }

    /**
     * The rule the tax-excluded amount of each bucket is rounded to the cent
     * with (BO-10-02-18): the shop's, where the caller knows it.
     */
    private final RoundingMode roundingMode;

    /**
     * The number of decimals the tax-excluded amount of each bucket is computed
     * with: the cent, or the scale a commercial document administers for its
     * taxable bases and its total excluding tax (BO-10-02-19).
     */
    private final int scale;

    /**
     * Builds a ventilation rounding half up — the rule every ventilation
     * followed before the shop could administer one.
     */
    public VatBreakdown() {
        this(RoundingMode.HALF_UP);
    }

    /**
     * Builds a ventilation rounding with the shop's rule (BO-10-02-18).
     *
     * @param roundingMode the administered rule, null meaning half up
     */
    public VatBreakdown(RoundingMode roundingMode) {
        this(roundingMode, 2);
    }

    /**
     * Builds a ventilation rounding with the shop's rule (BO-10-02-18) at the
     * scale a commercial document administers (BO-10-02-19): a base computed
     * on three decimals is a different figure from a base rounded to the cent
     * and padded with a zero.
     *
     * @param roundingMode the administered rule, null meaning half up
     * @param scale the decimals of the tax-excluded amounts
     */
    public VatBreakdown(RoundingMode roundingMode, int scale) {
        this.roundingMode = roundingMode == null ? RoundingMode.HALF_UP : roundingMode;
        this.scale = scale;
    }

    /**
     * A computed ventilation bucket for one VAT rate.
     */
    public static final class Bucket {
        /** The VAT rate of the bucket (e.g. 0.2000). */
        public final BigDecimal rate;
        /** The tax-included total of the bucket. */
        public final BigDecimal totalIncludingTax;
        /** The tax-excluded total of the bucket, rounded to the ventilation's scale. */
        public final BigDecimal totalExcludingTax;
        /** The VAT amount of the bucket (TTC minus HT). */
        public final BigDecimal vatAmount;
        /** The number of the regime of the bucket, or null when its lines name none (BO-02-02-01). */
        public final Integer number;
        /** Whether the bucket gathers the EXEMPT lines (BO-02-03-26). */
        public final boolean exempt;
        /** Whether the bucket gathers lines sold on behalf of a third party (BO-02-03-42). */
        public final boolean thirdParty;

        /**
         * Creates a computed bucket.
         *
         * @param rate the VAT rate
         * @param totalIncludingTax the tax-included total
         * @param totalExcludingTax the tax-excluded total
         * @param vatAmount the VAT amount
         * @param number the regime number, or null
         * @param exempt whether the bucket is an exemption one
         * @param thirdParty whether the bucket is a third-party one
         */
        private Bucket(BigDecimal rate, BigDecimal totalIncludingTax,
                       BigDecimal totalExcludingTax, BigDecimal vatAmount, Integer number,
                       boolean exempt, boolean thirdParty) {
            this.rate = rate;
            this.totalIncludingTax = totalIncludingTax;
            this.totalExcludingTax = totalExcludingTax;
            this.vatAmount = vatAmount;
            this.number = number;
            this.exempt = exempt;
            this.thirdParty = thirdParty;
        }

        /**
         * Returns the rate formatted as a percentage for display (e.g. "20,00%"),
         * or {@code EXO} for an exemption bucket, which has no rate to state.
         *
         * @return the formatted rate
         */
        public String getRateFormatted() {
            if (exempt) {
                return "EXO";
            }
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
        add(rate, null, lineTotalIncludingTax, false, false);
    }

    /**
     * Accumulates a tax-included line total under its VAT rate AND its regime
     * (BO-02-02-01), so two regimes of one percentage stay two buckets.
     *
     * @param rate the VAT rate of the line, or null for 0%
     * @param number the number of the line's regime, or null when it names none
     * @param lineTotalIncludingTax the tax-included line total (rounded to the cent)
     */
    public void add(BigDecimal rate, Integer number, BigDecimal lineTotalIncludingTax) {
        add(rate, number, lineTotalIncludingTax, false, false);
    }

    /**
     * Accumulates a tax-included line total that names no regime, kept apart
     * when the line is EXEMPT (BO-02-03-26) or sold on behalf of a THIRD PARTY
     * (BO-02-03-42).
     *
     * @param rate the VAT rate of the line, or null for 0%
     * @param lineTotalIncludingTax the tax-included line total
     * @param exempt whether the line is exempt of VAT
     * @param thirdParty whether the line is sold on behalf of a third party
     */
    public void add(BigDecimal rate, BigDecimal lineTotalIncludingTax, boolean exempt, boolean thirdParty) {
        add(rate, null, lineTotalIncludingTax, exempt, thirdParty);
    }

    /**
     * Accumulates a tax-included line total under its rate and its regime
     * (BO-02-02-01), under the EXO heading when the line is exempt
     * (BO-02-03-26) and under the third-party heading when it is sold on
     * behalf of a third party (BO-02-03-42). An exemption outranks the third
     * party — an exempt line carries no VAT to attribute to anybody — and is
     * never merged into the 0&nbsp;% bucket; two exempt regimes stay two
     * buckets, each with its own code and mention.
     *
     * @param rate the VAT rate of the line, or null for 0%
     * @param number the number of the line's regime, or null when it names none
     * @param lineTotalIncludingTax the tax-included line total (rounded to the cent)
     * @param exempt whether the line is exempt of VAT
     * @param thirdParty whether the line is sold on behalf of a third party
     */
    public void add(BigDecimal rate, Integer number, BigDecimal lineTotalIncludingTax,
                    boolean exempt, boolean thirdParty) {
        BigDecimal normalised = exempt ? BigDecimal.ZERO
                : (rate != null ? rate : BigDecimal.ZERO).stripTrailingZeros();
        Key key = new Key(normalised, number, exempt, !exempt && thirdParty);
        // The AMOUNT is guarded like the rate: Map.merge throws on a null value
        // rather than treating it as nothing, so one line without a total used
        // to take the whole ventilation down — in the middle of a ticket.
        BigDecimal amount = lineTotalIncludingTax != null ? lineTotalIncludingTax : BigDecimal.ZERO;
        ttcByRate.merge(key, amount, BigDecimal::add);
    }

    /**
     * Returns the computed buckets: the store's own rates, then the third
     * party's, then the exemptions, each sorted by ascending rate then regime.
     *
     * @return the ventilation buckets
     */
    public List<Bucket> getBuckets() {
        List<Bucket> buckets = new ArrayList<>();
        for (Map.Entry<Key, BigDecimal> entry : ttcByRate.entrySet()) {
            Key key = entry.getKey();
            BigDecimal ttc = entry.getValue();
            BigDecimal ht = key.exempt() ? ttc : ttc.divide(BigDecimal.ONE.add(key.rate()), scale, roundingMode);
            buckets.add(new Bucket(key.rate(), ttc, ht, ttc.subtract(ht), key.number(),
                    key.exempt(), key.thirdParty()));
        }
        return buckets;
    }

    /**
     * Returns the tax-included total across every bucket.
     *
     * @return the ticket tax-included total
     */
    public BigDecimal getTotalIncludingTax() {
        return getBuckets().stream().map(b -> b.totalIncludingTax).reduce(BigDecimal.ZERO, BigDecimal::add);
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
