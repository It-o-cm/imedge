package com.intermarche.edge.domain.sale;

/**
 * The lifecycle of a customer order recalled at the till by its code (onglet G).
 *
 * <p>An order is {@link #OPEN} when the back office hands out its code, becomes
 * {@link #PARTIALLY_PAID} when a deposit is taken (EXP-85), {@link #PAID} when
 * the remainder is settled, {@link #COLLECTED} once the goods are handed over,
 * and {@link #CANCELLED} if the store voids it.
 */
public enum OrderStatus {

    /** The code is live and nothing has been paid yet. */
    OPEN("Ouvert"),

    /** A deposit has settled part of the order (EXP-85). */
    PARTIALLY_PAID("Acompte versé"),

    /** The order is fully paid. */
    PAID("Payé"),

    /** The goods have been handed over. */
    COLLECTED("Retiré"),

    /** The store voided the order. */
    CANCELLED("Annulé");

    /** The operator-facing label (French). */
    private final String label;

    /**
     * Builds a status with its label.
     *
     * @param label the French label
     */
    OrderStatus(String label) {
        this.label = label;
    }

    /**
     * Returns the operator-facing label.
     *
     * @return the French label
     */
    public String getLabel() {
        return label;
    }
}
