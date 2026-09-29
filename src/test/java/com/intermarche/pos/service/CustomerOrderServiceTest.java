package com.intermarche.pos.service;

import com.intermarche.pos.domain.sale.CustomerOrder;
import com.intermarche.pos.domain.sale.OrderKind;
import com.intermarche.pos.domain.sale.OrderStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomerOrderService}, targeting 100% branch coverage.
 * <p>
 * Plain JUnit 5 + Mockito, no Quarkus and no database: the recall's static
 * finder resolves to {@link PanacheEntityBase} and is intercepted with
 * {@code mockStatic}; the money transitions run on hand-built orders. Every leg
 * of every compound guard has its own case.
 */
class CustomerOrderServiceTest {

    /**
     * Builds an order of the given kind, status and total.
     *
     * @param kind the kind
     * @param status the status
     * @param total the total amount
     * @return the order
     */
    private CustomerOrder order(OrderKind kind, OrderStatus status, String total) {
        CustomerOrder order = new CustomerOrder();
        order.code = "CMD1";
        order.kind = kind;
        order.status = status;
        order.totalAmount = new BigDecimal(total);
        order.depositPaid = BigDecimal.ZERO;
        return order;
    }

    /**
     * Wraps a first-result in a Panache query mock.
     *
     * @param row the order the query resolves, or null
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<CustomerOrder> query(CustomerOrder row) {
        PanacheQuery<CustomerOrder> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        return query;
    }

    /**
     * Recall returns a recallable order, and null both when the code matches
     * nothing and when the matched order is no longer recallable (three arms).
     */
    @Test
    void recallReturnsOnlyRecallableOrders() {
        CustomerOrderService service = new CustomerOrderService();
        CustomerOrder open = order(OrderKind.ORDER, OrderStatus.OPEN, "10.00");
        CustomerOrder paid = order(OrderKind.ORDER, OrderStatus.PAID, "10.00");
        PanacheQuery<CustomerOrder> openQ = query(open);
        PanacheQuery<CustomerOrder> paidQ = query(paid);
        PanacheQuery<CustomerOrder> absentQ = query(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CustomerOrder.find("code", "OPEN")).thenReturn(openQ);
            assertSame(open, service.recall("OPEN"));
            ms.when(() -> CustomerOrder.find("code", "PAID")).thenReturn(paidQ);
            assertNull(service.recall("PAID"));
            ms.when(() -> CustomerOrder.find("code", "GHOST")).thenReturn(absentQ);
            assertNull(service.recall("GHOST"));
        }
    }

    /**
     * A deposit is refused on a null order, a non-recallable order and a kind
     * that forbids deposits (the three legs of the first guard).
     */
    @Test
    void depositRefusedByStateOrKind() {
        CustomerOrderService service = new CustomerOrderService();
        assertFalse(service.applyDeposit(null, new BigDecimal("5.00")));
        assertFalse(service.applyDeposit(order(OrderKind.ORDER, OrderStatus.PAID, "10.00"), new BigDecimal("5.00")));
        assertFalse(service.applyDeposit(order(OrderKind.SALE_VOUCHER, OrderStatus.OPEN, "10.00"), new BigDecimal("5.00")));
    }

    /**
     * A deposit is refused for a null, non-positive or over-remaining amount (the
     * three legs of the amount guard).
     */
    @Test
    void depositRefusedByAmount() {
        CustomerOrderService service = new CustomerOrderService();
        assertFalse(service.applyDeposit(order(OrderKind.ORDER, OrderStatus.OPEN, "10.00"), null));
        assertFalse(service.applyDeposit(order(OrderKind.ORDER, OrderStatus.OPEN, "10.00"), BigDecimal.ZERO));
        assertFalse(service.applyDeposit(order(OrderKind.ORDER, OrderStatus.OPEN, "10.00"), new BigDecimal("11.00")));
    }

    /**
     * A partial deposit marks the order partially paid; a deposit clearing the
     * balance marks it paid (both arms of the status ternary).
     */
    @Test
    void depositRecordedPartialThenFull() {
        CustomerOrderService service = new CustomerOrderService();
        CustomerOrder order = order(OrderKind.QUOTE, OrderStatus.OPEN, "10.00");
        assertTrue(service.applyDeposit(order, new BigDecimal("4.00")));
        assertEquals(OrderStatus.PARTIALLY_PAID, order.status);
        assertEquals(new BigDecimal("6.00"), order.remaining());
        assertTrue(service.applyDeposit(order, new BigDecimal("6.00")));
        assertEquals(OrderStatus.PAID, order.status);
        assertEquals(BigDecimal.ZERO.setScale(2), order.remaining().setScale(2));
    }

    /**
     * Settling the remainder marks the order paid; it is refused on a
     * non-recallable order (both arms).
     */
    @Test
    void settleRemainingPaysOrRefuses() {
        CustomerOrderService service = new CustomerOrderService();
        CustomerOrder open = order(OrderKind.ORDER, OrderStatus.PARTIALLY_PAID, "10.00");
        open.depositPaid = new BigDecimal("3.00");
        assertTrue(service.settleRemaining(open));
        assertEquals(OrderStatus.PAID, open.status);
        assertEquals(new BigDecimal("10.00"), open.depositPaid);
        assertFalse(service.settleRemaining(order(OrderKind.ORDER, OrderStatus.CANCELLED, "10.00")));
        assertFalse(service.settleRemaining(null));
    }

    /**
     * Settling an order with a null total leaves a zero deposit (the null-total
     * arm of the settle ternary).
     */
    @Test
    void settleRemainingToleratesANullTotal() {
        CustomerOrderService service = new CustomerOrderService();
        CustomerOrder order = order(OrderKind.ORDER, OrderStatus.OPEN, "0.00");
        order.totalAmount = null;
        assertTrue(service.settleRemaining(order));
        assertEquals(BigDecimal.ZERO, order.depositPaid);
        assertEquals(OrderStatus.PAID, order.status);
    }

    /**
     * Collection succeeds only on a paid order, and is refused on a null order
     * and any other status (the two legs of the guard).
     */
    @Test
    void collectOnlyWhenPaid() {
        CustomerOrderService service = new CustomerOrderService();
        CustomerOrder paid = order(OrderKind.ORDER, OrderStatus.PAID, "10.00");
        assertTrue(service.collect(paid));
        assertEquals(OrderStatus.COLLECTED, paid.status);
        assertFalse(service.collect(order(OrderKind.ORDER, OrderStatus.OPEN, "10.00")));
        assertFalse(service.collect(null));
    }
}
