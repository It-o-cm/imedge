package com.intermarche.edge.domain.sale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link OrderStatus}: each constant carries its operator-facing
 * label (the enum has no branching logic).
 */
class OrderStatusTest {

    /**
     * Each constant carries its label.
     */
    @Test
    void eachConstantCarriesItsLabel() {
        assertEquals("Ouvert", OrderStatus.OPEN.getLabel());
        assertEquals("Acompte versé", OrderStatus.PARTIALLY_PAID.getLabel());
        assertEquals("Payé", OrderStatus.PAID.getLabel());
        assertEquals("Retiré", OrderStatus.COLLECTED.getLabel());
        assertEquals("Annulé", OrderStatus.CANCELLED.getLabel());
    }
}
