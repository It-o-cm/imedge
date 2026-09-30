package com.intermarche.pos.imports;

import com.intermarche.pos.domain.sale.CustomerOrder;
import com.intermarche.pos.domain.sale.OrderKind;
import com.intermarche.pos.domain.sale.OrderStatus;
import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.service.CustomerOrderService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
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
     * With no till acompte imputed to the order (empty movement list), the
     * acompte-already-paid is the back-office deposit alone and the remaining is
     * the total minus it (non-null deposit/total arms, non-negative remaining arm,
     * non-null ean/label arms).
     */
    @Test
    @SuppressWarnings("unchecked")
    void aRecallableOrderIsReturned() {
        OrderRecallResource resource = storeResource(Optional.of("secret"));
        when(resource.customerOrderService.recall("CMD1")).thenReturn(order());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CashMovement.list("type = ?1 and orderReference = ?2",
                    CashMovement.MovementType.CUSTOMER_DEPOSIT, "CMD1")).thenReturn(List.of());
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

    /**
     * A till down-payment imputed to the order by its reference (EXP-85) is folded
     * into the acompte-already-paid on top of the back-office deposit, and an
     * acompte that exceeds the total clamps the remaining to zero (signum &lt; 0 arm).
     */
    @Test
    @SuppressWarnings("unchecked")
    void tillAcomptesAreFoldedInAndRemainingClampsToZero() {
        OrderRecallResource resource = storeResource(Optional.empty());
        when(resource.customerOrderService.recall("CMD1")).thenReturn(order());
        CashMovement acompte = new CashMovement();
        acompte.amount = new BigDecimal("20.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CashMovement.list("type = ?1 and orderReference = ?2",
                    CashMovement.MovementType.CUSTOMER_DEPOSIT, "CMD1")).thenReturn(List.of(acompte));
            Response response = resource.recall("CMD1", null);
            Map<String, Object> json = (Map<String, Object>) response.getEntity();
            assertEquals(new BigDecimal("23.00"), json.get("acompteAlreadyPaid"));
            assertEquals(BigDecimal.ZERO, json.get("remaining"));
        }
    }

    /**
     * A recalled order with a null deposit and a null total defaults both to zero
     * (deposit-null and total-null arms), and a line with a null EAN and label
     * renders as empty strings (ean-null and label-null arms).
     */
    @Test
    @SuppressWarnings("unchecked")
    void nullDepositTotalAndLineFieldsDefaultSafely() {
        OrderRecallResource resource = storeResource(Optional.empty());
        CustomerOrder order = new CustomerOrder();
        order.code = "CMD2";
        order.kind = OrderKind.DEVIS;
        order.status = OrderStatus.OPEN;
        order.totalAmount = null;
        order.depositPaid = null;
        order.lines.add(new CustomerOrder.OrderLine(null, null, new BigDecimal("1"),
                new BigDecimal("2.00"), new BigDecimal("5.50"), new BigDecimal("2.00")));
        when(resource.customerOrderService.recall("CMD2")).thenReturn(order);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CashMovement.list("type = ?1 and orderReference = ?2",
                    CashMovement.MovementType.CUSTOMER_DEPOSIT, "CMD2")).thenReturn(List.of());
            Response response = resource.recall("CMD2", null);
            Map<String, Object> json = (Map<String, Object>) response.getEntity();
            assertEquals(BigDecimal.ZERO, json.get("acompteAlreadyPaid"));
            assertEquals(BigDecimal.ZERO, json.get("remaining"));
            java.util.List<Map<String, Object>> lines = (java.util.List<Map<String, Object>>) json.get("lines");
            assertEquals("", lines.get(0).get("ean"));
            assertEquals("", lines.get(0).get("label"));
        }
    }
}
