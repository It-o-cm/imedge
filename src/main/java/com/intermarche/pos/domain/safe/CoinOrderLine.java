package com.intermarche.pos.domain.safe;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.math.BigDecimal;

/**
 * One denomination of a coin order ({@code BO-05-02-04}, {@code BO-05-02-05}).
 *
 * <p>The line carries WHAT WAS ASKED FOR and WHAT CAME BACK, side by side and
 * both kept. The requirement asks the order to be recalled when the delivery
 * arrives so the delivery can be checked against it; a line that overwrote the
 * request with the delivery would destroy the only thing the check is made of.
 */
@Embeddable
public class CoinOrderLine {

    /** The denomination ordered. */
    @Enumerated(EnumType.STRING)
    @Column(name = "denomination", nullable = false, length = 8)
    public SafeDenomination denomination;

    /** How many were asked for. */
    @Column(name = "requested_quantity", nullable = false)
    public int requestedQuantity;

    /** How many came back, null until the delivery is recorded. */
    @Column(name = "received_quantity")
    public Integer receivedQuantity;

    /**
     * Builds an empty line, as JPA requires.
     */
    public CoinOrderLine() {
    }

    /**
     * Builds a line of an order.
     *
     * @param denomination the denomination ordered
     * @param requestedQuantity how many were asked for
     */
    public CoinOrderLine(SafeDenomination denomination, int requestedQuantity) {
        this.denomination = denomination;
        this.requestedQuantity = requestedQuantity;
    }

    /**
     * The amount asked for on this line.
     *
     * @return the amount, never null
     */
    public BigDecimal requestedAmount() {
        return denomination == null ? BigDecimal.ZERO : denomination.amountOf(requestedQuantity);
    }

    /**
     * The amount that came back on this line.
     *
     * @return the amount, zero while nothing has been received
     */
    public BigDecimal receivedAmount() {
        return denomination == null ? BigDecimal.ZERO : denomination.amountOf(receivedQuantity);
    }

    /**
     * Tells whether the delivery matches the request on this line.
     *
     * @return true when as many came back as were asked for
     */
    public boolean isFulfilled() {
        return receivedQuantity != null && receivedQuantity == requestedQuantity;
    }

    /**
     * Returns the denomination's label, for a template.
     *
     * @return the French label, empty when the line names none
     */
    public String getDenominationLabel() {
        return denomination == null ? "" : denomination.getLabel();
    }
}
