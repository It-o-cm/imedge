package com.intermarche.pos.domain.safe;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.math.BigDecimal;

/**
 * One counted denomination of a safe inventory ({@code BO-05-02-11}).
 *
 * <p>Per denomination and not one total, for the same reason the drawer count
 * is: an inventory that only stated a total could not be checked afterwards, and
 * a shop looking for a discrepancy needs to know which notes it is short of.
 */
@Embeddable
public class SafeInventoryLine {

    /** The denomination counted. */
    @Enumerated(EnumType.STRING)
    @Column(name = "denomination", nullable = false, length = 8)
    public SafeDenomination denomination;

    /** How many of them were found in the safe. */
    @Column(name = "quantity", nullable = false)
    public int quantity;

    /**
     * Builds an empty line, as JPA requires.
     */
    public SafeInventoryLine() {
    }

    /**
     * Builds a counted line.
     *
     * @param denomination the denomination counted
     * @param quantity how many were found
     */
    public SafeInventoryLine(SafeDenomination denomination, int quantity) {
        this.denomination = denomination;
        this.quantity = quantity;
    }

    /**
     * The amount this line is worth.
     *
     * @return the amount, never null
     */
    public BigDecimal amount() {
        return denomination == null ? BigDecimal.ZERO : denomination.amountOf(quantity);
    }

    /**
     * Returns the denomination's label, for a template.
     *
     * @return the French label, empty when the line names none
     */
    public String getDenominationLabel() {
        return denomination == null ? "" : denomination.getLabel();
    }

    /**
     * Returns the line's amount for a template, two decimals.
     *
     * @return the amount, two decimals
     */
    public String getAmountFormatted() {
        return amount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
