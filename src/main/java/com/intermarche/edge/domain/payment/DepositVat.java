package com.intermarche.edge.domain.payment;

import com.intermarche.edge.domain.sale.VatBreakdown;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The per-rate VAT of a down-payment collected on an order
 * ({@code LC-06-03-05}).
 *
 * <p>THE RULE THE QUESTIONNAIRE STATES: an acompte is perceived TTC, and its
 * VAT is "ventilée par taux de TVA au prorata du montant total de la
 * commande". The deposit is therefore split over the order's VAT buckets in
 * proportion to each bucket's share of the order total, and the VAT of each
 * split is derived exactly the way the receipt derives it
 * ({@code VAT = TTC − TTC/(1 + rate)}), so the deposit slip, the final ticket
 * and the suspense accounts the back office reads at period end all carry
 * the same figures.
 *
 * <p>THE COMPACT DETAIL STRING is the wire and storage form of that
 * ventilation: {@code rate:amount;rate:amount} (for instance
 * {@code 20:8.26;5.5:0.26}). It travels unchanged from the register that
 * took the deposit to the {@code DepositPayment} row of the final ticket, so
 * the final receipt restates the ventilation as it was computed at
 * collection time — never recomputed on a basket the customer may since have
 * edited.
 */
public final class DepositVat {

    /** The separator between the rate and the amount of one detail entry. */
    private static final String RATE_AMOUNT = ":";

    /** The separator between the entries of a detail string. */
    private static final String ENTRY = ";";

    /** The scale every monetary figure of a ventilation is rounded to. */
    private static final int SCALE = 2;

    /**
     * One ventilated rate of a deposit: the VAT rate and the VAT amount of
     * the deposit under that rate.
     */
    public record Row(BigDecimal rate, BigDecimal vatAmount) {

        /**
         * Returns the rate formatted as a percentage for display
         * (e.g. "5,50%").
         *
         * @return the formatted rate
         */
        public String getRateFormatted() {
            return String.format("%.2f%%", rate.multiply(BigDecimal.valueOf(100)))
                    .replace(".", ",");
        }

        /**
         * Returns the VAT amount formatted for display (French comma).
         *
         * @return the formatted amount
         */
        public String getAmountFormatted() {
            return String.format("%.2f", vatAmount.setScale(SCALE, RoundingMode.HALF_UP))
                    .replace(".", ",");
        }
    }

    /**
     * Not instantiable: two computations and two readers, nothing to hold.
     */
    private DepositVat() {
    }

    /**
     * Ventilates a deposit over the VAT buckets of the order it is taken on
     * ({@code LC-06-03-05}).
     *
     * <p>Each bucket receives its pro-rata share of the deposit
     * ({@code deposit × bucket TTC / order total}, rounded to the cent), the
     * LAST bucket absorbing the rounding residue so the shares sum to the
     * deposit exactly, and the VAT of each share follows the receipt rule.
     *
     * <p>Answers an empty detail string when there is nothing to ventilate —
     * a non-positive deposit, a non-positive total or no bucket — because a
     * deposit taken on nothing is not a deposit this calculation can name.
     *
     * @param buckets the order's VAT buckets, in rate order
     * @param deposit the deposit amount, TTC
     * @param totalAmount the order total, TTC
     * @return the compact detail string, empty when the input ventilates to
     *         nothing
     */
    public static String compute(List<VatBreakdown.Bucket> buckets,
                                 BigDecimal deposit, BigDecimal totalAmount) {
        if (buckets == null || buckets.isEmpty()
                || deposit == null || deposit.signum() <= 0
                || totalAmount == null || totalAmount.signum() <= 0) {
            return "";
        }
        List<Row> rows = new ArrayList<>();
        for (Share share : shares(buckets, deposit, totalAmount)) {
            rows.add(new Row(share.rate(), share.vatAmount()));
        }
        return format(rows);
    }

    /**
     * One rate's share of a deposit: the part of the deposit taken on that rate,
     * TTC, and the VAT it carries (LC-08-04-15).
     *
     * @param rate the VAT rate
     * @param includingTax the share of the deposit, TTC
     * @param vatAmount the VAT of the share
     */
    public record Share(BigDecimal rate, BigDecimal includingTax, BigDecimal vatAmount) {

        /**
         * The share without its VAT.
         *
         * @return the share, HT
         */
        public BigDecimal excludingTax() {
            return includingTax.subtract(vatAmount);
        }
    }

    /**
     * Shares a deposit over the VAT buckets of its order, the rule
     * {@link #compute} states (LC-06-03-05, LC-08-04-15): each bucket its
     * pro-rata share, the last one absorbing the rounding residue.
     *
     * @param buckets the order's VAT buckets, in rate order, never empty
     * @param deposit the deposit amount, TTC, strictly positive
     * @param totalAmount the order total, TTC, strictly positive
     * @return the shares, one per bucket
     */
    public static List<Share> shares(List<VatBreakdown.Bucket> buckets,
                                     BigDecimal deposit, BigDecimal totalAmount) {
        BigDecimal allocated = BigDecimal.ZERO;
        List<Share> shares = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i++) {
            VatBreakdown.Bucket bucket = buckets.get(i);
            BigDecimal share;
            if (i == buckets.size() - 1) {
                share = deposit.subtract(allocated).setScale(SCALE, RoundingMode.HALF_UP);
            } else {
                share = deposit.multiply(bucket.totalIncludingTax)
                        .divide(totalAmount, SCALE, RoundingMode.HALF_UP);
            }
            allocated = allocated.add(share);
            shares.add(new Share(bucket.rate, share, vatOf(share, bucket.rate)));
        }
        return shares;
    }

    /**
     * Adds two deposit ventilations rate by rate (LC-06-02-04, -07): several
     * deposits may be taken on one document, and the VAT already collected on it
     * is the sum of theirs.
     *
     * @param first a compact detail string, possibly null or blank
     * @param second another compact detail string, possibly null or blank
     * @return the summed detail, in rate order, empty when both are empty
     */
    public static String merge(String first, String second) {
        java.util.Map<BigDecimal, BigDecimal> byRate = new java.util.TreeMap<>();
        for (Row row : rows(first)) {
            byRate.merge(row.rate.stripTrailingZeros(), row.vatAmount, BigDecimal::add);
        }
        for (Row row : rows(second)) {
            byRate.merge(row.rate.stripTrailingZeros(), row.vatAmount, BigDecimal::add);
        }
        List<Row> merged = new ArrayList<>();
        for (java.util.Map.Entry<BigDecimal, BigDecimal> entry : byRate.entrySet()) {
            merged.add(new Row(entry.getKey(), entry.getValue()));
        }
        return format(merged);
    }

    /**
     * Reads the entries of a compact detail string.
     *
     * <p>TOLERANT BY CONSTRUCTION: a null or blank detail answers an empty
     * list, and an entry that is not a rate/amount pair of readable numbers
     * is dropped rather than refused — the detail is a witness carried from
     * another register and another day, and one unreadable figure must not
     * cost the receipt every other figure.
     *
     * @param detail the compact detail string, or null
     * @return the ventilated rows, in detail order, never null
     */
    public static List<Row> rows(String detail) {
        List<Row> rows = new ArrayList<>();
        if (detail == null || detail.isBlank()) {
            return rows;
        }
        for (String entry : detail.split(ENTRY)) {
            String trimmed = entry.trim();
            int split = trimmed.indexOf(RATE_AMOUNT);
            if (split <= 0 || split == trimmed.length() - 1) {
                continue;
            }
            try {
                rows.add(new Row(new BigDecimal(trimmed.substring(0, split)),
                        new BigDecimal(trimmed.substring(split + 1))));
            } catch (NumberFormatException e) {
                continue;
            }
        }
        return rows;
    }

    /**
     * Serializes ventilated rows into the compact detail string.
     *
     * @param rows the ventilated rows
     * @return the compact detail string, empty when there is no row
     */
    public static String format(List<Row> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Row row : rows) {
            if (row == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(ENTRY);
            }
            sb.append(row.rate.stripTrailingZeros().toPlainString())
                    .append(RATE_AMOUNT)
                    .append(row.vatAmount.setScale(SCALE, RoundingMode.HALF_UP)
                            .stripTrailingZeros().toPlainString());
        }
        return sb.toString();
    }

    /**
     * Derives the VAT of one ventilated share the way the receipt does:
     * {@code VAT = TTC − TTC/(1 + rate)}, rounded to the cent.
     *
     * @param shareIncludingTax the ventilated share, TTC
     * @param rate the VAT rate of the share
     * @return the VAT of the share
     */
    private static BigDecimal vatOf(BigDecimal shareIncludingTax, BigDecimal rate) {
        BigDecimal divisor = BigDecimal.ONE.add(rate);
        return shareIncludingTax.subtract(shareIncludingTax
                .divide(divisor, SCALE + 2, RoundingMode.HALF_UP))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }
}
