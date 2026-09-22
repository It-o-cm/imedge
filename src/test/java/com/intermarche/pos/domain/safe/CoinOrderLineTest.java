package com.intermarche.pos.domain.safe;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link CoinOrderLine}.
 *
 * <p>The line keeps what was ASKED FOR and what CAME BACK side by side, which is
 * the only thing the delivery check is made of. Both null arms of the
 * denomination guard are covered, and the fulfilment test is asserted on its
 * three cases: not yet delivered, delivered short, delivered in full.
 */
class CoinOrderLineTest {

    /**
     * The empty constructor JPA needs leaves everything unset.
     */
    @Test
    void theEmptyConstructorLeavesEverythingUnset() {
        CoinOrderLine line = new CoinOrderLine();
        assertNull(line.denomination);
        assertEquals(0, line.requestedQuantity);
        assertNull(line.receivedQuantity);
        assertEquals("", line.getDenominationLabel());
    }

    /**
     * The requested amount is the denomination times the quantity asked for.
     */
    @Test
    void theRequestedAmountIsTheDenominationTimesTheQuantity() {
        CoinOrderLine line = new CoinOrderLine(SafeDenomination.C1, 25);
        assertEquals(new BigDecimal("25.00"), line.requestedAmount());
        assertEquals("Pièce 1 €", line.getDenominationLabel());
    }

    /**
     * The received amount is zero while nothing has come back, and the delivery
     * once it has — both arms of the received guard.
     */
    @Test
    void theReceivedAmountFollowsTheDelivery() {
        CoinOrderLine line = new CoinOrderLine(SafeDenomination.N10, 5);
        assertEquals(BigDecimal.ZERO, line.receivedAmount());
        line.receivedQuantity = 3;
        assertEquals(new BigDecimal("30.00"), line.receivedAmount());
    }

    /**
     * A line naming NO denomination is worth nothing on either side — the null
     * arms of the two guards, which a malformed row must not make explode.
     */
    @Test
    void alineWithoutADenominationIsWorthNothing() {
        CoinOrderLine line = new CoinOrderLine(null, 5);
        line.receivedQuantity = 5;
        assertEquals(BigDecimal.ZERO, line.requestedAmount());
        assertEquals(BigDecimal.ZERO, line.receivedAmount());
    }

    /**
     * A line is fulfilled only when as many came back as were asked for: the
     * three cases of the test, undelivered included.
     */
    @Test
    void alineIsFulfilledOnlyWhenTheDeliveryMatches() {
        CoinOrderLine line = new CoinOrderLine(SafeDenomination.C2, 10);
        assertFalse(line.isFulfilled());
        line.receivedQuantity = 8;
        assertFalse(line.isFulfilled());
        line.receivedQuantity = 10;
        assertTrue(line.isFulfilled());
    }
}
