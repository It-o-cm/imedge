package com.intermarche.edge.service;

import com.intermarche.edge.domain.sale.CustomerOrder;
import com.intermarche.edge.domain.sale.OrderKind;
import com.intermarche.edge.domain.sale.OrderStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
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
        CustomerOrder open = order(OrderKind.COMMANDE, OrderStatus.OPEN, "10.00");
        CustomerOrder paid = order(OrderKind.COMMANDE, OrderStatus.PAID, "10.00");
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
        assertFalse(service.applyDeposit(order(OrderKind.COMMANDE, OrderStatus.PAID, "10.00"), new BigDecimal("5.00")));
        assertFalse(service.applyDeposit(order(OrderKind.BON_VENTE, OrderStatus.OPEN, "10.00"), new BigDecimal("5.00")));
    }

    /**
     * A deposit is refused for a null, non-positive or over-remaining amount (the
     * three legs of the amount guard).
     */
    @Test
    void depositRefusedByAmount() {
        CustomerOrderService service = new CustomerOrderService();
        assertFalse(service.applyDeposit(order(OrderKind.COMMANDE, OrderStatus.OPEN, "10.00"), null));
        assertFalse(service.applyDeposit(order(OrderKind.COMMANDE, OrderStatus.OPEN, "10.00"), BigDecimal.ZERO));
        assertFalse(service.applyDeposit(order(OrderKind.COMMANDE, OrderStatus.OPEN, "10.00"), new BigDecimal("11.00")));
    }

    /**
     * A partial deposit marks the order partially paid; a deposit clearing the
     * balance marks it paid (both arms of the status ternary).
     */
    @Test
    void depositRecordedPartialThenFull() {
        CustomerOrderService service = new CustomerOrderService();
        CustomerOrder order = order(OrderKind.DEVIS, OrderStatus.OPEN, "10.00");
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
        CustomerOrder open = order(OrderKind.COMMANDE, OrderStatus.PARTIALLY_PAID, "10.00");
        open.depositPaid = new BigDecimal("3.00");
        assertTrue(service.settleRemaining(open));
        assertEquals(OrderStatus.PAID, open.status);
        assertEquals(new BigDecimal("10.00"), open.depositPaid);
        assertFalse(service.settleRemaining(order(OrderKind.COMMANDE, OrderStatus.CANCELLED, "10.00")));
        assertFalse(service.settleRemaining(null));
    }

    /**
     * Settling an order with a null total leaves a zero deposit (the null-total
     * arm of the settle ternary).
     */
    @Test
    void settleRemainingToleratesANullTotal() {
        CustomerOrderService service = new CustomerOrderService();
        CustomerOrder order = order(OrderKind.COMMANDE, OrderStatus.OPEN, "0.00");
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
        CustomerOrder paid = order(OrderKind.COMMANDE, OrderStatus.PAID, "10.00");
        assertTrue(service.collect(paid));
        assertEquals(OrderStatus.COLLECTED, paid.status);
        assertFalse(service.collect(order(OrderKind.COMMANDE, OrderStatus.OPEN, "10.00")));
        assertFalse(service.collect(null));
    }

    /**
     * createFromTill refuses a blank code and a null kind (the two legs of the
     * guard), and nothing is written (LC-06-03-01).
     */
    @Test
    void createFromTillRefusesBlankCodeOrUnknownKind() {
        CustomerOrderService service = new CustomerOrderService();
        assertNull(service.createFromTill(null, OrderKind.COMMANDE,
                new BigDecimal("10.00"), null, null));
        assertNull(service.createFromTill("   ", OrderKind.COMMANDE,
                new BigDecimal("10.00"), null, null));
        assertNull(service.createFromTill("C04-CMD000001", null,
                new BigDecimal("10.00"), null, null));
    }

    /**
     * createFromTill creates the pushed order (LC-06-03-01): open, dated, the
     * pushed total, no deposit on the row (the deposit's money travelled as
     * the register's CUSTOMER_DEPOSIT movement, imputed by the order code) and
     * the deposit's per-rate VAT detail held for the period-end accounts. A
     * null total reads as zero and null lines as no line (the two tolerance
     * arms).
     */
    @Test
    void createFromTillCreatesThePushedOrder() {
        CustomerOrderService service = new CustomerOrderService();
        java.util.List<CustomerOrder.OrderLine> lines = java.util.List.of(
                new CustomerOrder.OrderLine("301", "LAIT", new BigDecimal("2"),
                        new BigDecimal("3.00"), new BigDecimal("0.20"), new BigDecimal("6.00")));
        PanacheQuery<CustomerOrder> absent = query(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
                org.mockito.MockedConstruction<CustomerOrder> created =
                        mockConstruction(CustomerOrder.class)) {
            ms.when(() -> CustomerOrder.find("code", "C04-CMD000001")).thenReturn(absent);
            CustomerOrder order = service.createFromTill("C04-CMD000001",
                    OrderKind.COMMANDE, new BigDecimal("100.00"), "0.2:1", lines);
            assertSame(created.constructed().get(0), order);
            assertEquals("C04-CMD000001", order.code);
            assertEquals(OrderKind.COMMANDE, order.kind);
            assertEquals(OrderStatus.OPEN, order.status);
            assertEquals(new BigDecimal("100.00"), order.totalAmount);
            assertEquals(BigDecimal.ZERO, order.depositPaid);
            assertEquals("0.2:1", order.depositVatDetail);
            assertEquals(1, order.lines.size());
            assertEquals("LAIT", order.lines.get(0).label);
            assertNotNull(order.orderDate);
        }
        PanacheQuery<CustomerOrder> absentAgain = query(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
                org.mockito.MockedConstruction<CustomerOrder> created =
                        mockConstruction(CustomerOrder.class)) {
            ms.when(() -> CustomerOrder.find("code", "C04-CMD000002")).thenReturn(absentAgain);
            CustomerOrder bare = service.createFromTill("C04-CMD000002",
                    OrderKind.COMMANDE, null, null, null);
            assertEquals(BigDecimal.ZERO, bare.totalAmount);
            assertTrue(bare.lines.isEmpty());
            assertNull(bare.depositVatDetail);
        }
    }

    /**
     * createFromTill REFRESHES the row a repeated push names — idempotent by
     * code — so a register whose push was answered but never acknowledged
     * lands on the same order, not a duplicate.
     */
    @Test
    void createFromTillRefreshesAnExistingOrder() {
        CustomerOrderService service = new CustomerOrderService();
        CustomerOrder existing = order(OrderKind.COMMANDE, OrderStatus.PAID, "10.00");
        existing.depositPaid = new BigDecimal("10.00");
        existing.id = 99L;
        PanacheQuery<CustomerOrder> known = query(existing);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> CustomerOrder.find("code", "CMD1")).thenReturn(known);
            CustomerOrder refreshed = service.createFromTill("CMD1", OrderKind.COMMANDE,
                    new BigDecimal("42.00"), "0.2:7", java.util.List.of());
            assertSame(existing, refreshed);
            assertEquals(99L, refreshed.id);
            assertEquals(OrderStatus.OPEN, refreshed.status);
            assertEquals(new BigDecimal("42.00"), refreshed.totalAmount);
            assertEquals(BigDecimal.ZERO, refreshed.depositPaid);
            assertEquals("0.2:7", refreshed.depositVatDetail);
        }
    }

    /**
     * settleAtTill settles the order a register closed the sale of
     * (LC-06-03-07): paid then collected, the deposit covering the total. An
     * already-paid or already-collected order answers true without writing
     * (both idempotent arms), a null order and a cancelled one answer false
     * (the two legs of the refusal).
     */
    @Test
    void settleAtTillPaysAndCollectsOrRefuses() {
        CustomerOrderService service = new CustomerOrderService();
        CustomerOrder open = order(OrderKind.COMMANDE, OrderStatus.OPEN, "10.00");
        assertTrue(service.settleAtTill(open));
        assertEquals(OrderStatus.COLLECTED, open.status);
        assertEquals(new BigDecimal("10.00"), open.depositPaid);
        assertTrue(service.settleAtTill(order(OrderKind.COMMANDE, OrderStatus.PAID, "10.00")));
        assertTrue(service.settleAtTill(order(OrderKind.COMMANDE, OrderStatus.COLLECTED, "10.00")));
        assertFalse(service.settleAtTill(order(OrderKind.COMMANDE, OrderStatus.CANCELLED, "10.00")));
        assertFalse(service.settleAtTill(null));
    }

    /**
     * settleAtTill on an order whose total is null leaves a zero deposit (the
     * null-total arm of the settle ternary).
     */
    @Test
    void settleAtTillToleratesANullTotal() {
        CustomerOrderService service = new CustomerOrderService();
        CustomerOrder order = order(OrderKind.COMMANDE, OrderStatus.OPEN, "0.00");
        order.totalAmount = null;
        assertTrue(service.settleAtTill(order));
        assertEquals(BigDecimal.ZERO, order.depositPaid);
        assertEquals(OrderStatus.COLLECTED, order.status);
    }
}
