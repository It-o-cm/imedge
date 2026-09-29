package com.intermarche.pos.domain.sale;

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
 * Unit tests for {@link CustomerOrder}, targeting 100% branch coverage.
 * <p>
 * Branch enumeration: {@code remaining} covers the null-total, the null-deposit,
 * the negative (clamped-to-zero) and the ordinary arm; {@code isRecallable}
 * covers the open, the partially-paid and a terminal arm; {@code findByCode}
 * covers the null, the blank and the found arm; {@code getChecksum} is stable for
 * equal field sets.
 */
class CustomerOrderTest {

    /**
     * Builds an order with a total and a deposit.
     *
     * @param total the total, or null
     * @param deposit the deposit, or null
     * @return the order
     */
    private CustomerOrder order(String total, String deposit) {
        CustomerOrder order = new CustomerOrder();
        order.totalAmount = total == null ? null : new BigDecimal(total);
        order.depositPaid = deposit == null ? null : new BigDecimal(deposit);
        return order;
    }

    /**
     * Remaining is total minus deposit, tolerating nulls and never going below
     * zero (all four arms).
     */
    @Test
    void remainingCoversItsArms() {
        assertEquals(new BigDecimal("7.00"), order("10.00", "3.00").remaining());
        assertEquals(BigDecimal.ZERO, order(null, "3.00").remaining());
        assertEquals(new BigDecimal("10.00"), order("10.00", null).remaining());
        assertEquals(BigDecimal.ZERO, order("3.00", "5.00").remaining());
    }

    /**
     * An order is recallable while open or partially paid, not once paid,
     * collected or cancelled.
     */
    @Test
    void isRecallableFollowsTheStatus() {
        CustomerOrder open = new CustomerOrder();
        open.status = OrderStatus.OPEN;
        assertTrue(open.isRecallable());
        CustomerOrder partial = new CustomerOrder();
        partial.status = OrderStatus.PARTIALLY_PAID;
        assertTrue(partial.isRecallable());
        CustomerOrder paid = new CustomerOrder();
        paid.status = OrderStatus.PAID;
        assertFalse(paid.isRecallable());
    }

    /**
     * {@code findByCode} resolves a code and short-circuits a null or blank one.
     */
    @Test
    void findByCodeResolvesOrShortCircuits() {
        CustomerOrder order = new CustomerOrder();
        PanacheQuery<CustomerOrder> found = mock(PanacheQuery.class);
        when(found.firstResult()).thenReturn(order);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CustomerOrder.find("code", "CMD1")).thenReturn(found);
            assertSame(order, CustomerOrder.findByCode(" CMD1 "));
        }
        assertNull(CustomerOrder.findByCode(null));
        assertNull(CustomerOrder.findByCode("   "));
    }

    /**
     * The checksum is stable for equal field sets and reflects the line
     * constructor's fields, so a fresh order describes itself.
     */
    @Test
    void checksumIsStableAndDefaultsAreSane() {
        CustomerOrder a = new CustomerOrder();
        a.code = "CMD1";
        CustomerOrder b = new CustomerOrder();
        b.code = "CMD1";
        assertEquals(a.getChecksum(), b.getChecksum());
        assertEquals(OrderKind.ORDER, a.kind);
        assertEquals(OrderStatus.OPEN, a.status);
        CustomerOrder.OrderLine line = new CustomerOrder.OrderLine("3001", "Lait", new BigDecimal("2"), new BigDecimal("3.00"));
        assertEquals("3001", line.ean);
        assertEquals("Lait", line.label);
    }
}
