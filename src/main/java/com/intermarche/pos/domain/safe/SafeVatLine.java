package com.intermarche.pos.domain.safe;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One VAT rate of a safe movement ({@code BO-05-02-01}, {@code BO-05-02-02}).
 *
 * <p>Rows and not columns, because the requirement is explicit: a movement bears
 * "no VAT, one VAT or SEVERAL VATs". A vehicle rental invoice paid out of the
 * safe carries one rate; a supermarket receipt for the staff room carries three.
 * Three columns would cap the case at three rates and leave a fourth unrecordable.
 *
 * <p>The line states the BASE and the VAT amount as the piece states them, and
 * never recomputes one from the other: an invoice rounds its own VAT, and a safe
 * whose entry disagreed with the paper it was entered from would be wrong in the
 * only place anybody checks.
 */
@Embeddable
public class SafeVatLine {

    /** The rate as a percentage (20.00 for twenty per cent). */
    @Column(name = "vat_rate", precision = 5, scale = 2)
    public BigDecimal rate;

    /** The taxable base of this rate, as the piece states it. */
    @Column(name = "vat_base", precision = 19, scale = 4)
    public BigDecimal base;

    /** The VAT of this rate, as the piece states it. */
    @Column(name = "vat_amount", precision = 19, scale = 4)
    public BigDecimal amount;

    /**
     * Builds an empty line, as JPA requires.
     */
    public SafeVatLine() {
    }

    /**
     * Builds a line stating one rate.
     *
     * @param rate the rate as a percentage, null when the shop entered none
     * @param base the taxable base, null counting as zero
     * @param amount the VAT amount, null counting as zero
     */
    public SafeVatLine(BigDecimal rate, BigDecimal base, BigDecimal amount) {
        this.rate = rate;
        this.base = base;
        this.amount = amount;
    }

    /**
     * The tax-included total of this line: the base plus its VAT.
     *
     * @return the total, never null
     */
    public BigDecimal getTotal() {
        return zeroIfNull(base).add(zeroIfNull(amount));
    }

    /**
     * Returns the rate for a template, two decimals.
     *
     * @return the rate, or an empty string when the line states none
     */
    public String getRateFormatted() {
        return rate == null ? "" : rate.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Tells whether the line states anything at all.
     *
     * <p>A line whose base AND VAT are both empty was left blank on the screen
     * and is dropped rather than stored: an empty rate row would make a movement
     * look as though it bore a VAT it does not.
     *
     * @return true when the line carries a base or a VAT amount
     */
    public boolean isStated() {
        return zeroIfNull(base).signum() != 0 || zeroIfNull(amount).signum() != 0;
    }

    /**
     * Turns a missing amount into zero.
     *
     * @param value the amount, possibly null
     * @return the amount, or zero
     */
    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
