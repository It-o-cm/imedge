package com.intermarche.pos.domain.sale;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link VatBreakdown}.
 * <p>
 * Branch enumeration: {@code add} has both arms of the rate guard (null and
 * given) and both arms of the amount guard (null and given), plus the
 * first-value and merge paths of the accumulator. {@code getBuckets} is read on
 * an empty ventilation and on several rates, and {@code getRateFormatted} on a
 * rate whose percentage carries decimals. The three totals are read on an empty
 * ventilation (their reduce identity) and on a filled one.
 */
class VatBreakdownTest {

    /** The standard French rate. */
    private static final BigDecimal TWENTY = new BigDecimal("0.2000");

    /** The reduced French rate. */
    private static final BigDecimal FIVE_FIVE = new BigDecimal("0.0550");

    /**
     * An empty ventilation has no bucket and totals of zero.
     */
    @Test
    void emptyVentilationTotalsZero() {
        VatBreakdown breakdown = new VatBreakdown();
        assertTrue(breakdown.getBuckets().isEmpty());
        assertEquals(BigDecimal.ZERO, breakdown.getTotalIncludingTax());
        assertEquals(BigDecimal.ZERO, breakdown.getTotalExcludingTax());
        assertEquals(BigDecimal.ZERO, breakdown.getTotalVat());
    }

    /**
     * Two lines at the same rate land in one bucket, and the tax is derived from
     * their SUM rather than line by line.
     */
    @Test
    void linesAtTheSameRateShareOneBucket() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(TWENTY, new BigDecimal("10.00"));
        breakdown.add(TWENTY, new BigDecimal("14.00"));
        List<VatBreakdown.Bucket> buckets = breakdown.getBuckets();
        assertEquals(1, buckets.size());
        assertEquals(0, new BigDecimal("24.00").compareTo(buckets.get(0).totalIncludingTax));
        assertEquals(0, new BigDecimal("20.00").compareTo(buckets.get(0).totalExcludingTax));
        assertEquals(0, new BigDecimal("4.00").compareTo(buckets.get(0).vatAmount));
    }

    /**
     * A rate written with more decimals lands in the same bucket, because the
     * key is normalized.
     */
    @Test
    void equivalentRatesShareOneBucket() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(new BigDecimal("0.20"), new BigDecimal("12.00"));
        breakdown.add(new BigDecimal("0.2000"), new BigDecimal("12.00"));
        assertEquals(1, breakdown.getBuckets().size());
    }

    /**
     * Buckets come back sorted by ascending rate, whatever the order they were
     * accumulated in.
     */
    @Test
    void bucketsComeBackSortedByRate() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(TWENTY, new BigDecimal("12.00"));
        breakdown.add(FIVE_FIVE, new BigDecimal("10.55"));
        List<VatBreakdown.Bucket> buckets = breakdown.getBuckets();
        assertEquals(2, buckets.size());
        assertTrue(buckets.get(0).rate.compareTo(buckets.get(1).rate) < 0);
    }

    /**
     * A null rate is accumulated at zero percent, where the tax-excluded total
     * equals the tax-included one.
     */
    @Test
    void nullRateIsAccumulatedAtZeroPercent() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(null, new BigDecimal("7.30"));
        VatBreakdown.Bucket bucket = breakdown.getBuckets().get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(bucket.rate));
        assertEquals(0, new BigDecimal("7.30").compareTo(bucket.totalExcludingTax));
        assertEquals(0, BigDecimal.ZERO.compareTo(bucket.vatAmount));
    }

    /**
     * A NULL AMOUNT contributes nothing instead of taking the ventilation down.
     * <p>
     * This is the defect the guard was added for: {@code Map.merge} throws on a
     * null value, so one line without a total used to raise a
     * {@code NullPointerException} in the middle of a ticket.
     */
    @Test
    void nullAmountContributesNothing() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(TWENTY, null);
        assertEquals(1, breakdown.getBuckets().size());
        assertEquals(0, BigDecimal.ZERO.compareTo(breakdown.getTotalIncludingTax()));
    }

    /**
     * A null amount arriving on a rate that already carries lines leaves the
     * bucket untouched (the merge path of the guard, not just the first-value
     * path).
     */
    @Test
    void nullAmountOnAnExistingBucketChangesNothing() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(TWENTY, new BigDecimal("18.00"));
        breakdown.add(TWENTY, null);
        assertEquals(0, new BigDecimal("18.00").compareTo(breakdown.getTotalIncludingTax()));
    }

    /**
     * A null rate AND a null amount together are accumulated as a zero line at
     * zero percent.
     */
    @Test
    void nullRateAndNullAmountAreBothAbsorbed() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(null, null);
        assertEquals(1, breakdown.getBuckets().size());
        assertEquals(0, BigDecimal.ZERO.compareTo(breakdown.getTotalVat()));
    }

    /**
     * The three totals add their buckets up.
     */
    @Test
    void totalsAddTheBucketsUp() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(TWENTY, new BigDecimal("24.00"));
        breakdown.add(FIVE_FIVE, new BigDecimal("10.55"));
        assertEquals(0, new BigDecimal("34.55").compareTo(breakdown.getTotalIncludingTax()));
        assertEquals(0, new BigDecimal("30.00").compareTo(breakdown.getTotalExcludingTax()));
        assertEquals(0, new BigDecimal("4.55").compareTo(breakdown.getTotalVat()));
    }

    /**
     * A rate is shown as a French percentage with two decimals.
     */
    @Test
    void rateIsShownAsAFrenchPercentage() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(FIVE_FIVE, new BigDecimal("10.55"));
        assertEquals("5,50%", breakdown.getBuckets().get(0).getRateFormatted());
    }
}
