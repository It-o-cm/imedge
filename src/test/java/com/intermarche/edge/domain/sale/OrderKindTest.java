package com.intermarche.edge.domain.sale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OrderKind}, targeting 100% branch coverage.
 * <p>
 * Branch enumeration: {@code getLabel}; {@code isDepositAllowed} covers the
 * deposit-allowed and the full-payment kinds; {@code fromName} covers the null,
 * the unknown and the matched arm.
 */
class OrderKindTest {

    /**
     * A constant carries its label.
     */
    @Test
    void aConstantCarriesItsLabel() {
        assertEquals("Devis", OrderKind.DEVIS.getLabel());
        assertEquals("Click and collect", OrderKind.CLICK_COLLECT.getLabel());
    }

    /**
     * Deposits are allowed on a quote and an order, forbidden on a voucher and a
     * click-and-collect basket (both arms).
     */
    @Test
    void depositAllowedFollowsTheKind() {
        assertTrue(OrderKind.DEVIS.isDepositAllowed());
        assertTrue(OrderKind.COMMANDE.isDepositAllowed());
        assertFalse(OrderKind.BON_VENTE.isDepositAllowed());
        assertFalse(OrderKind.CLICK_COLLECT.isDepositAllowed());
    }

    /**
     * {@code fromName} resolves a known name (trimming), and answers null for a
     * null or an unknown one.
     */
    @Test
    void fromNameResolvesTrimsOrAnswersNull() {
        assertSame(OrderKind.BON_VENTE, OrderKind.fromName(" BON_VENTE "));
        assertNull(OrderKind.fromName(null));
        assertNull(OrderKind.fromName("NOPE"));
    }
}
