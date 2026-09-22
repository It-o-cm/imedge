package com.intermarche.pos.domain.safe;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link CoinOrder}.
 *
 * <p>The order and its delivery are one row, because the delivery is checked
 * against the order. The two totals are asserted on their real and their null
 * arms, and {@code isFullyFulfilled} on every leg: no line at all, a line short,
 * a line undelivered, and every line matching.
 */
class CoinOrderTest {

    /**
     * Builds an order of two denominations.
     *
     * @return the order, nothing delivered yet
     */
    private CoinOrder order() {
        CoinOrder order = new CoinOrder();
        order.orderNumber = "DM-2026-09";
        order.label = "Monnaie de la semaine";
        order.requestedOn = LocalDate.of(2026, 9, 14);
        order.lines = new ArrayList<>(List.of(
                new CoinOrderLine(SafeDenomination.C1, 50),
                new CoinOrderLine(SafeDenomination.N10, 20)));
        return order;
    }

    /**
     * An order opens REQUESTED and awaits its delivery.
     */
    @Test
    void anOrderOpensRequested() {
        CoinOrder order = order();
        assertEquals(CoinOrder.OrderStatus.REQUESTED, order.status);
        assertTrue(order.isAwaitingDelivery());
    }

    /**
     * An order once received, or cancelled, awaits nothing — the two other arms.
     */
    @Test
    void areceivedOrCancelledOrderAwaitsNothing() {
        CoinOrder received = order();
        received.status = CoinOrder.OrderStatus.RECEIVED;
        assertFalse(received.isAwaitingDelivery());
        CoinOrder cancelled = order();
        cancelled.status = CoinOrder.OrderStatus.CANCELLED;
        assertFalse(cancelled.isAwaitingDelivery());
    }

    /**
     * The requested total is the sum of the lines.
     */
    @Test
    void therequestedTotalIsTheSumOfTheLines() {
        assertEquals(new BigDecimal("250.00"), order().requestedAmount());
        assertEquals("250.00", order().getRequestedAmountFormatted());
    }

    /**
     * The received total is zero while nothing has come back, and the delivery
     * once it has.
     */
    @Test
    void thereceivedTotalFollowsTheDelivery() {
        CoinOrder order = order();
        assertEquals(BigDecimal.ZERO, order.receivedAmount());
        assertEquals("0.00", order.getReceivedAmountFormatted());
        order.lines.get(0).receivedQuantity = 50;
        order.lines.get(1).receivedQuantity = 18;
        assertEquals(new BigDecimal("230.00"), order.receivedAmount());
        assertEquals("230.00", order.getReceivedAmountFormatted());
    }

    /**
     * An order with NO lines totals nothing on either side — the null arms of
     * both sums.
     */
    @Test
    void anOrderWithoutLinesTotalsNothing() {
        CoinOrder order = order();
        order.lines = null;
        assertEquals(BigDecimal.ZERO, order.requestedAmount());
        assertEquals(BigDecimal.ZERO, order.receivedAmount());
    }

    /**
     * An order is fully fulfilled only when EVERY line came back in full: the
     * empty, the null, the short and the complete cases.
     */
    @Test
    void anOrderIsFullyFulfilledOnlyWhenEveryLineMatches() {
        CoinOrder empty = order();
        empty.lines = new ArrayList<>();
        assertFalse(empty.isFullyFulfilled());
        empty.lines = null;
        assertFalse(empty.isFullyFulfilled());
        CoinOrder order = order();
        assertFalse(order.isFullyFulfilled());
        order.lines.get(0).receivedQuantity = 50;
        assertFalse(order.isFullyFulfilled());
        order.lines.get(1).receivedQuantity = 19;
        assertFalse(order.isFullyFulfilled());
        order.lines.get(1).receivedQuantity = 20;
        assertTrue(order.isFullyFulfilled());
    }

    /**
     * The pending finder asks for the orders still waiting, oldest first.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thependingFinderAsksForTheOrdersStillWaiting() {
        CoinOrder waiting = order();
        PanacheQuery<CoinOrder> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(waiting));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CoinOrder.find("status = ?1 order by requestedOn",
                    CoinOrder.OrderStatus.REQUESTED)).thenReturn(query);
            assertEquals(List.of(waiting), CoinOrder.awaitingDelivery());
        }
    }

    /**
     * The history finder asks for every order, newest first.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thehistoryFinderAsksForEveryOrderNewestFirst() {
        CoinOrder order = order();
        PanacheQuery<CoinOrder> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(List.of(order));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CoinOrder.find("order by requestedOn desc, id desc"))
                    .thenReturn(query);
            assertEquals(List.of(order), CoinOrder.listNewestFirst());
        }
    }

    /**
     * The checksum hashes the identifying fields, the lines counted rather than
     * hashed — both arms of that guard.
     */
    @Test
    void thechecksumHashesTheSalientFields() {
        CoinOrder order = order();
        int expected = Objects.hash("DM-2026-09", "Monnaie de la semaine",
                LocalDate.of(2026, 9, 14), CoinOrder.OrderStatus.REQUESTED, null, 2);
        assertEquals(expected, order.getChecksum());
        order.lines = null;
        int withoutLines = Objects.hash("DM-2026-09", "Monnaie de la semaine",
                LocalDate.of(2026, 9, 14), CoinOrder.OrderStatus.REQUESTED, null, 0);
        assertEquals(withoutLines, order.getChecksum());
    }
}
