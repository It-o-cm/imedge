package com.intermarche.pos.imports;

import com.intermarche.pos.domain.sale.CustomerOrder;
import com.intermarche.pos.domain.sale.OrderKind;
import com.intermarche.pos.domain.sale.OrderStatus;
import com.intermarche.pos.service.CustomerOrderService;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderRecallResource}.
 * <p>
 * Plain JUnit 5 + Mockito: the role and token gate fields are set directly and
 * the recall mechanism is mocked. Every gate arm (off-role, bad token, open
 * token) and every outcome (recallable, nothing recallable) is exercised.
 */
class OrderRecallResourceTest {

    /**
     * Builds a store-role resource with the given token and a mocked service.
     *
     * @param token the configured shared token
     * @return the resource
     */
    private OrderRecallResource storeResource(Optional<String> token) {
        OrderRecallResource resource = new OrderRecallResource();
        resource.role = "store";
        resource.token = token;
        resource.customerOrderService = mock(CustomerOrderService.class);
        return resource;
    }

    /**
     * Builds a recallable order carrying one line.
     *
     * @return the order
     */
    private CustomerOrder order() {
        CustomerOrder order = new CustomerOrder();
        order.code = "CMD1";
        order.kind = OrderKind.COMMANDE;
        order.status = OrderStatus.OPEN;
        order.totalAmount = new BigDecimal("10.00");
        order.depositPaid = new BigDecimal("3.00");
        order.lines.add(new CustomerOrder.OrderLine("3001", "Lait", new BigDecimal("2"),
                new BigDecimal("3.00"), new BigDecimal("5.50"), new BigDecimal("6.00")));
        return order;
    }

    /**
     * A node that is not a store refuses the recall (403).
     */
    @Test
    void offRoleIsForbidden() {
        OrderRecallResource resource = new OrderRecallResource();
        resource.role = "register";
        resource.token = Optional.empty();
        resource.customerOrderService = mock(CustomerOrderService.class);
        assertEquals(403, resource.recall("CMD1", null).getStatus());
    }

    /**
     * A configured token that the caller does not present is rejected (401).
     */
    @Test
    void badTokenIsUnauthorized() {
        OrderRecallResource resource = storeResource(Optional.of("secret"));
        assertEquals(401, resource.recall("CMD1", "wrong").getStatus());
    }

    /**
     * An open node (no token) recalling nothing recallable answers 404.
     */
    @Test
    void nothingRecallableIsNotFound() {
        OrderRecallResource resource = storeResource(Optional.empty());
        when(resource.customerOrderService.recall("CMD1")).thenReturn(null);
        assertEquals(404, resource.recall("CMD1", null).getStatus());
    }

    /**
     * A recallable order is returned as JSON, lines included, with the good token.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aRecallableOrderIsReturned() {
        OrderRecallResource resource = storeResource(Optional.of("secret"));
        when(resource.customerOrderService.recall("CMD1")).thenReturn(order());
        Response response = resource.recall("CMD1", "secret");
        assertEquals(200, response.getStatus());
        Map<String, Object> json = (Map<String, Object>) response.getEntity();
        assertEquals("CMD1", json.get("code"));
        assertEquals("COMMANDE", json.get("kind"));
        assertEquals(new BigDecimal("3.00"), json.get("acompteAlreadyPaid"));
        assertEquals(new BigDecimal("7.00"), json.get("remaining"));
        java.util.List<Map<String, Object>> lines = (java.util.List<Map<String, Object>>) json.get("lines");
        assertEquals(1, lines.size());
        assertEquals("3001", lines.get(0).get("ean"));
        assertEquals(new BigDecimal("3.00"), lines.get(0).get("unitPrice"));
        assertEquals(new BigDecimal("5.50"), lines.get(0).get("vatRate"));
    }
}
