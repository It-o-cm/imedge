package com.intermarche.edge.imports;

import com.intermarche.edge.domain.sale.CustomerOrder;
import com.intermarche.edge.domain.sale.OrderKind;
import com.intermarche.edge.domain.sale.OrderStatus;
import com.intermarche.edge.domain.session.CashMovement;
import com.intermarche.edge.service.CustomerOrderService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the ORDER DATE the recall hands back to the register
 * (BO-10-03-17), which sends it on to the promotion engine where the store
 * administers it.
 *
 * <p>Branch enumeration: a dated order (ISO date-time, truncated to the
 * second) and an undated one (null).
 */
class OrderRecallOrderDateTest {

    /**
     * Recalls an order carrying the given date and returns the JSON answer.
     *
     * @param orderDate the order date, or null
     * @return the recall answer
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> recall(LocalDateTime orderDate) {
        OrderRecallResource resource = new OrderRecallResource();
        resource.role = "store";
        resource.token = Optional.empty();
        resource.customerOrderService = mock(CustomerOrderService.class);
        CustomerOrder order = new CustomerOrder();
        order.code = "CMD9";
        order.kind = OrderKind.COMMANDE;
        order.status = OrderStatus.OPEN;
        order.totalAmount = new BigDecimal("10.00");
        order.orderDate = orderDate;
        when(resource.customerOrderService.recall("CMD9")).thenReturn(order);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CashMovement.list("type = ?1 and orderReference = ?2",
                    CashMovement.MovementType.CUSTOMER_DEPOSIT, "CMD9")).thenReturn(List.of());
            return (Map<String, Object>) resource.recall("CMD9", null).getEntity();
        }
    }

    /**
     * A dated order hands its date back, ISO, to the second.
     */
    @Test
    void aDatedOrderHandsItsDateBack() {
        Map<String, Object> json = recall(LocalDateTime.of(2026, 9, 30, 10, 15, 0, 123_000_000));
        assertEquals("2026-09-30T10:15:00", json.get("orderDate"));
    }

    /**
     * An undated order hands back a null date, which the register ignores.
     */
    @Test
    void anUndatedOrderHandsBackNoDate() {
        Map<String, Object> json = recall(null);
        assertTrue(json.containsKey("orderDate"));
        assertNull(json.get("orderDate"));
    }
}
