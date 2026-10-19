package com.intermarche.edge.domain.payment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link DepositVat}, targeting 100% branch coverage.
 * <p>
 * Every leg of every compound guard has its own case: {@code compute} refuses
 * a null bucket list, an empty one, a null deposit, a non-positive deposit, a
 * null total and a non-positive total, and ventilates on the all-false arm —
 * pro-rata by bucket, the last bucket absorbing the rounding residue so the
 * shares sum to the deposit exactly. {@code rows} is tolerant: a null or blank
 * detail answers empty, and an entry that is not a tight {@code rate:amount}
 * pair of readable numbers is dropped, never refused. {@code format} answers
 * empty on null, an empty list and skips a null row. The row formatters print
 * the French comma. Each test is fully isolated and asserts absolute expected
 * values.
 */
class DepositVatTest {

    /**
     * Builds one bucket of a ventilation with its TTC total.
     *
     * @param rate the VAT rate
     * @param totalIncludingTax the tax-included total of the bucket
     * @return the bucket
     */
    private com.intermarche.edge.domain.sale.VatBreakdown.Bucket bucket(String rate, String totalIncludingTax) {
        com.intermarche.edge.domain.sale.VatBreakdown breakdown = new com.intermarche.edge.domain.sale.VatBreakdown();
        breakdown.add(new BigDecimal(rate), new BigDecimal(totalIncludingTax));
        return breakdown.getBuckets().get(0);
    }

    /**
     * compute answers an empty detail on a null bucket list (first leg).
     */
    @Test
    void computeRefusesNullBuckets() {
        Assertions.assertEquals("", DepositVat.compute(null, new BigDecimal("5.00"), new BigDecimal("10.00")));
    }

    /**
     * compute answers an empty detail on an empty bucket list (second leg).
     */
    @Test
    void computeRefusesEmptyBuckets() {
        Assertions.assertEquals("", DepositVat.compute(List.of(), new BigDecimal("5.00"), new BigDecimal("10.00")));
    }

    /**
     * compute answers an empty detail on a null deposit (third leg).
     */
    @Test
    void computeRefusesNullDeposit() {
        Assertions.assertEquals("", DepositVat.compute(
                List.of(bucket("0.20", "60.00")), null, new BigDecimal("100.00")));
    }

    /**
     * compute answers an empty detail on a zero deposit (fourth leg, zero arm).
     */
    @Test
    void computeRefusesZeroDeposit() {
        Assertions.assertEquals("", DepositVat.compute(
                List.of(bucket("0.20", "60.00")), BigDecimal.ZERO, new BigDecimal("100.00")));
    }

    /**
     * compute answers an empty detail on a negative deposit (fourth leg,
     * negative arm).
     */
    @Test
    void computeRefusesNegativeDeposit() {
        Assertions.assertEquals("", DepositVat.compute(
                List.of(bucket("0.20", "60.00")), new BigDecimal("-1.00"), new BigDecimal("100.00")));
    }

    /**
     * compute answers an empty detail on a null total (fifth leg).
     */
    @Test
    void computeRefusesNullTotal() {
        Assertions.assertEquals("", DepositVat.compute(
                List.of(bucket("0.20", "60.00")), new BigDecimal("5.00"), null));
    }

    /**
     * compute answers an empty detail on a zero total (sixth leg, zero arm).
     */
    @Test
    void computeRefusesZeroTotal() {
        Assertions.assertEquals("", DepositVat.compute(
                List.of(bucket("0.20", "60.00")), new BigDecimal("5.00"), BigDecimal.ZERO));
    }

    /**
     * compute answers an empty detail on a negative total (sixth leg, negative
     * arm).
     */
    @Test
    void computeRefusesNegativeTotal() {
        Assertions.assertEquals("", DepositVat.compute(
                List.of(bucket("0.20", "60.00")), new BigDecimal("5.00"), new BigDecimal("-10.00")));
    }

    /**
     * A single-bucket order ventilates the whole deposit onto that bucket:
     * share = deposit (last-bucket arm of the loop ternary), VAT = TTC −
     * TTC/(1 + rate).
     */
    @Test
    void computeSingleBucketVentilatesWholeDeposit() {
        String detail = DepositVat.compute(
                List.of(bucket("0.20", "100.00")), new BigDecimal("12.00"), new BigDecimal("100.00"));
        Assertions.assertEquals("0.2:2", detail);
    }

    /**
     * A multi-bucket order ventilates pro-rata: each bucket's share follows its
     * share of the order total, the LAST bucket absorbs the rounding residue so
     * the shares sum to the deposit, and each share's VAT follows the receipt
     * rule.
     */
    @Test
    void computeMultiBucketVentilatesProRataWithResidueOnLast() {
        List<com.intermarche.edge.domain.sale.VatBreakdown.Bucket> buckets = List.of(
                bucket("0.20", "60.00"), bucket("0.055", "40.00"));
        String detail = DepositVat.compute(buckets, new BigDecimal("10.00"), new BigDecimal("100.00"));
        Assertions.assertEquals("0.2:1;0.055:0.21", detail);
        // The shares sum to the deposit exactly: 6.00 + 4.00 = 10.00, and the
        // per-rate VAT of the same pro-rata read back through the detail.
        List<DepositVat.Row> rows = DepositVat.rows(detail);
        Assertions.assertEquals(2, rows.size());
        Assertions.assertEquals(0, new BigDecimal("1.00").compareTo(rows.get(0).vatAmount()));
        Assertions.assertEquals(0, new BigDecimal("0.21").compareTo(rows.get(1).vatAmount()));
    }

    /**
     * The last-bucket share is the DEPOSIT minus what was allocated, not its
     * own pro-rata: a deposit that does not divide by the bucket count still
     * sums exactly.
     */
    @Test
    void computeResidueKeepsSharesSummingToDeposit() {
        List<com.intermarche.edge.domain.sale.VatBreakdown.Bucket> buckets = List.of(
                bucket("0.20", "1.00"), bucket("0.055", "2.00"));
        String detail = DepositVat.compute(buckets, new BigDecimal("1.00"), new BigDecimal("3.00"));
        Assertions.assertEquals("0.2:0.06;0.055:0.03", detail);
    }

    /**
     * A zero-rate bucket carries a share with a zero VAT, so the ventilation
     * names every suspense account the back office reads.
     */
    @Test
    void computeZeroRateBucketCarriesZeroVat() {
        String detail = DepositVat.compute(
                List.of(bucket("0", "50.00")), new BigDecimal("10.00"), new BigDecimal("50.00"));
        Assertions.assertEquals("0:0", detail);
    }

    /**
     * rows answers an empty list on a null detail (first leg).
     */
    @Test
    void rowsOfNullAnswersEmpty() {
        Assertions.assertTrue(DepositVat.rows(null).isEmpty());
    }

    /**
     * rows answers an empty list on a blank detail (second leg).
     */
    @Test
    void rowsOfBlankAnswersEmpty() {
        Assertions.assertTrue(DepositVat.rows("   ").isEmpty());
    }

    /**
     * rows reads a well-formed detail in order.
     */
    @Test
    void rowsReadsWellFormedDetail() {
        List<DepositVat.Row> rows = DepositVat.rows("20:2;5.5:0.26");
        Assertions.assertEquals(2, rows.size());
        Assertions.assertEquals(new BigDecimal("20"), rows.get(0).rate());
        Assertions.assertEquals(new BigDecimal("2"), rows.get(0).vatAmount());
        Assertions.assertEquals(new BigDecimal("5.5"), rows.get(1).rate());
        Assertions.assertEquals(new BigDecimal("0.26"), rows.get(1).vatAmount());
    }

    /**
     * rows drops an entry carrying no colon (first leg of the entry guard).
     */
    @Test
    void rowsDropsEntryWithoutColon() {
        List<DepositVat.Row> rows = DepositVat.rows("20");
        Assertions.assertTrue(rows.isEmpty());
    }

    /**
     * rows drops an entry whose colon starts the entry (split &le; 0, second
     * leg).
     */
    @Test
    void rowsDropsEntryWithLeadingColon() {
        List<DepositVat.Row> rows = DepositVat.rows(":2");
        Assertions.assertTrue(rows.isEmpty());
    }

    /**
     * rows drops an entry whose colon ends the entry (third leg).
     */
    @Test
    void rowsDropsEntryWithTrailingColon() {
        List<DepositVat.Row> rows = DepositVat.rows("20:");
        Assertions.assertTrue(rows.isEmpty());
    }

    /**
     * rows drops an entry whose rate or amount is not a number (catch arm).
     */
    @Test
    void rowsDropsUnreadableEntry() {
        List<DepositVat.Row> rows = DepositVat.rows("abc:def");
        Assertions.assertTrue(rows.isEmpty());
    }

    /**
     * rows drops only the unreadable entries and keeps the readable ones.
     */
    @Test
    void rowsKeepsReadableEntriesAmongUnreadableOnes() {
        List<DepositVat.Row> rows = DepositVat.rows("20:2;oops;5.5:0.26");
        Assertions.assertEquals(2, rows.size());
        Assertions.assertEquals(new BigDecimal("20"), rows.get(0).rate());
        Assertions.assertEquals(new BigDecimal("5.5"), rows.get(1).rate());
    }

    /**
     * format answers an empty detail on a null list (first leg).
     */
    @Test
    void formatOfNullAnswersEmpty() {
        Assertions.assertEquals("", DepositVat.format(null));
    }

    /**
     * format answers an empty detail on an empty list (second leg).
     */
    @Test
    void formatOfEmptyAnswersEmpty() {
        Assertions.assertEquals("", DepositVat.format(List.of()));
    }

    /**
     * format skips a null row and keeps the others (null-row leg).
     */
    @Test
    void formatSkipsNullRow() {
        List<DepositVat.Row> rows = new ArrayList<>();
        rows.add(new DepositVat.Row(new BigDecimal("20"), new BigDecimal("2")));
        rows.add(null);
        Assertions.assertEquals("20:2", DepositVat.format(rows));
    }

    /**
     * format serializes the rates and amounts without their padding zeros, so
     * the detail a computation produced reads back unchanged.
     */
    @Test
    void formatRoundTripsWhatComputeProduces() {
        String detail = DepositVat.compute(
                List.of(bucket("0.20", "60.00"), bucket("0.055", "40.00")),
                new BigDecimal("10.00"), new BigDecimal("100.00"));
        Assertions.assertEquals(detail, DepositVat.format(DepositVat.rows(detail)));
    }

    /**
     * The row's rate formats as a percentage with the French comma.
     */
    @Test
    void rowRateFormatsAsPercent() {
        DepositVat.Row row = new DepositVat.Row(new BigDecimal("0.0550"), new BigDecimal("0.26"));
        Assertions.assertEquals("5,50%", row.getRateFormatted());
    }

    /**
     * The row's amount formats with the French comma at two decimals.
     */
    @Test
    void rowAmountFormatsFrench() {
        DepositVat.Row row = new DepositVat.Row(new BigDecimal("0.055"), new BigDecimal("0.2600"));
        Assertions.assertEquals("0,26", row.getAmountFormatted());
    }
}
