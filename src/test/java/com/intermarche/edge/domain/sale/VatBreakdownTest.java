package com.intermarche.edge.domain.sale;

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

    /**
     * BO-10-02-18: the tax-excluded amount of a bucket follows the shop's
     * rounding rule; a null rule rounds half up like the no-argument form.
     */
    @Test
    void theAdministeredRuleRoundsTheTaxExcludedAmount() {
        VatBreakdown down = new VatBreakdown(java.math.RoundingMode.DOWN);
        down.add(TWENTY, new BigDecimal("10.03"));
        assertEquals(0, new BigDecimal("8.35").compareTo(down.getTotalExcludingTax()));
        VatBreakdown unset = new VatBreakdown(null);
        unset.add(TWENTY, new BigDecimal("10.03"));
        assertEquals(0, new BigDecimal("8.36").compareTo(unset.getTotalExcludingTax()));
    }

    /**
     * BO-02-02-01: two regimes of one percentage are two buckets, each keeping
     * its number, ordered by rate, then by number — the unnamed regime of a
     * rate first; lines of the same regime merge.
     */
    @Test
    void twoRegimesOfOnePercentageAreTwoBuckets() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(new BigDecimal("0.20"), 1, new BigDecimal("12.00"));
        breakdown.add(BigDecimal.ZERO, 11, new BigDecimal("5.00"));
        breakdown.add(new BigDecimal("0.0000"), 10, new BigDecimal("3.00"));
        breakdown.add(BigDecimal.ZERO, 11, new BigDecimal("1.00"));
        breakdown.add(BigDecimal.ZERO, new BigDecimal("2.00"));
        List<VatBreakdown.Bucket> buckets = breakdown.getBuckets();
        assertEquals(4, buckets.size());
        assertEquals(null, buckets.get(0).number);
        assertEquals(new BigDecimal("2.00"), buckets.get(0).totalIncludingTax);
        assertEquals(10, buckets.get(1).number);
        assertEquals(11, buckets.get(2).number);
        assertEquals(new BigDecimal("6.00"), buckets.get(2).totalIncludingTax);
        assertEquals(1, buckets.get(3).number);
        assertEquals(new BigDecimal("2.00"), buckets.get(3).vatAmount);
        assertEquals(new BigDecimal("23.00"), breakdown.getTotalIncludingTax());
    }

    /**
     * A named regime sorts after the unnamed one of its rate whichever was
     * added first.
     */
    @Test
    void theUnnamedRegimeOfARateComesFirst() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(BigDecimal.ZERO, 3, BigDecimal.ONE);
        breakdown.add(null, null, BigDecimal.ONE);
        assertEquals(null, breakdown.getBuckets().get(0).number);
        assertEquals(3, breakdown.getBuckets().get(1).number);
    }

    /**
     * An exempt line lands in an EXO bucket of its own, apart from the 0 %
     * one, and a third-party line in a third-party bucket of its rate; the
     * totals add every bucket up (BO-02-03-26, -42).
     */
    @Test
    void exemptAndThirdPartyLinesHaveBucketsOfTheirOwn() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(new BigDecimal("0.20"), new BigDecimal("12.00"), false, false);
        breakdown.add(BigDecimal.ZERO, new BigDecimal("5.00"), true, false);
        breakdown.add(null, null, true, true);
        breakdown.add(new BigDecimal("0.20"), new BigDecimal("6.00"), false, true);
        breakdown.add(null, new BigDecimal("1.00"), false, true);
        List<VatBreakdown.Bucket> buckets = breakdown.getBuckets();
        assertEquals(4, buckets.size());
        assertEquals("20,00%", buckets.get(0).getRateFormatted());
        assertEquals(false, buckets.get(0).thirdParty);
        assertTrue(buckets.get(1).thirdParty);
        assertEquals(0, new BigDecimal("1.00").compareTo(buckets.get(1).totalIncludingTax));
        assertTrue(buckets.get(2).thirdParty);
        assertEquals(0, new BigDecimal("1.00").compareTo(buckets.get(2).vatAmount));
        VatBreakdown.Bucket exempt = buckets.get(3);
        assertTrue(exempt.exempt);
        assertEquals("EXO", exempt.getRateFormatted());
        assertEquals(0, new BigDecimal("5.00").compareTo(exempt.totalExcludingTax));
        assertEquals(0, BigDecimal.ZERO.compareTo(exempt.vatAmount));
        assertEquals(0, new BigDecimal("24.00").compareTo(breakdown.getTotalIncludingTax()));
        assertEquals(0, new BigDecimal("3.00").compareTo(breakdown.getTotalVat()));
    }

    /**
     * BO-02-02-01 with BO-02-03-26: two EXEMPT regimes are two EXO buckets,
     * each keeping its number, after the store's own and the third party's
     * buckets; an exempt line sold for a third party stays an exemption, and a
     * third-party line keeps its regime number.
     */
    @Test
    void twoExemptRegimesAreTwoExoBucketsKeepingTheirNumbers() {
        VatBreakdown breakdown = new VatBreakdown();
        breakdown.add(BigDecimal.ZERO, 12, new BigDecimal("4.00"), true, false);
        breakdown.add(new BigDecimal("0.0000"), 11, new BigDecimal("3.00"), true, true);
        breakdown.add(TWENTY, 1, new BigDecimal("12.00"), false, true);
        breakdown.add(TWENTY, 1, new BigDecimal("6.00"), false, false);
        breakdown.add(BigDecimal.ZERO, 12, new BigDecimal("1.00"), true, false);
        List<VatBreakdown.Bucket> buckets = breakdown.getBuckets();
        assertEquals(4, buckets.size());
        assertEquals(1, buckets.get(0).number);
        assertEquals(false, buckets.get(0).thirdParty);
        assertEquals(1, buckets.get(1).number);
        assertTrue(buckets.get(1).thirdParty);
        assertEquals(11, buckets.get(2).number);
        assertTrue(buckets.get(2).exempt);
        assertEquals(false, buckets.get(2).thirdParty);
        assertEquals("EXO", buckets.get(2).getRateFormatted());
        assertEquals(12, buckets.get(3).number);
        assertEquals(0, new BigDecimal("5.00").compareTo(buckets.get(3).totalExcludingTax));
        assertEquals(0, new BigDecimal("3.00").compareTo(breakdown.getTotalVat()));
    }

    /**
     * BO-10-02-19: a ventilation built at three decimals COMPUTES its tax-excluded
     * amounts at three — 10,03 at 20 % is 8,358, not 8,36 — and its VAT is what
     * is left of the tax-included total.
     */
    @Test
    void aVentilationAtThreeDecimalsComputesItsBaseAtThree() {
        VatBreakdown breakdown = new VatBreakdown(java.math.RoundingMode.HALF_UP, 3);
        breakdown.add(TWENTY, new BigDecimal("10.03"));
        assertEquals(new BigDecimal("8.358"), breakdown.getTotalExcludingTax());
        assertEquals(new BigDecimal("1.672"), breakdown.getTotalVat());
        VatBreakdown cent = new VatBreakdown(null);
        cent.add(TWENTY, new BigDecimal("10.03"));
        assertEquals(new BigDecimal("8.36"), cent.getTotalExcludingTax());
    }
}
