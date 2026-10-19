package com.intermarche.edge.imports;

import com.intermarche.edge.domain.sale.CustomerOrder;
import com.intermarche.edge.domain.sale.OrderKind;
import com.intermarche.edge.domain.sale.OrderStatus;
import com.intermarche.edge.domain.session.CashMovement;
import com.intermarche.edge.service.CustomerOrderService;
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
import static org.mockito.Mockito.verify;
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

    /**
     * LC-06-03-08: the recall hands back the deposit's per-rate VAT detail the
     * creating till ventilated, and the order's total — the figures the final
     * ticket restates — and answers null for both when the order carries none.
     */
    @Test
    @SuppressWarnings("unchecked")
    void recallCarriesTheDepositVatDetailAndTheTotal() {
        OrderRecallResource resource = storeResource(Optional.empty());
        CustomerOrder order = order();
        order.depositVatDetail = "0.2:1;0.055:0.21";
        when(resource.customerOrderService.recall("CMD1")).thenReturn(order);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CashMovement.list("type = ?1 and orderReference = ?2",
                    CashMovement.MovementType.CUSTOMER_DEPOSIT, "CMD1")).thenReturn(List.of());
            Response response = resource.recall("CMD1", null);
            Map<String, Object> json = (Map<String, Object>) response.getEntity();
            assertEquals("0.2:1;0.055:0.21", json.get("depositVatDetail"));
            assertEquals(new BigDecimal("10.00"), json.get("totalAmount"));
        }
        CustomerOrder bare = order();
        when(resource.customerOrderService.recall("CMD1")).thenReturn(bare);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CashMovement.list("type = ?1 and orderReference = ?2",
                    CashMovement.MovementType.CUSTOMER_DEPOSIT, "CMD1")).thenReturn(List.of());
            Response response = resource.recall("CMD1", null);
            Map<String, Object> json = (Map<String, Object>) response.getEntity();
            assertEquals(null, json.get("depositVatDetail"));
        }
    }

    /**
     * The ingest of a till-created order (LC-06-03-01) is gated like the recall:
     * off-role answers 403, a bad token 401 — both without touching the
     * service.
     */
    @Test
    void ingestIsGated() {
        OrderRecallResource register = new OrderRecallResource();
        register.role = "register";
        register.token = Optional.empty();
        register.customerOrderService = mock(CustomerOrderService.class);
        assertEquals(403, register.ingest(null, null).getStatus());
        OrderRecallResource badToken = storeResource(Optional.of("secret"));
        assertEquals(401, badToken.ingest("wrong", new OrderRecallResource.OrderPushDto()).getStatus());
    }

    /**
     * The ingest refuses an unreadable push (400): a null body, a blank code
     * and an unknown kind — each leg of the guard, nothing written.
     */
    @Test
    void ingestRefusesAnUnreadablePush() {
        OrderRecallResource resource = storeResource(Optional.empty());
        assertEquals(400, resource.ingest(null, null).getStatus());
        OrderRecallResource.OrderPushDto blankCode = new OrderRecallResource.OrderPushDto();
        blankCode.code = "   ";
        blankCode.kind = "COMMANDE";
        assertEquals(400, resource.ingest(null, blankCode).getStatus());
        OrderRecallResource.OrderPushDto unknownKind = new OrderRecallResource.OrderPushDto();
        unknownKind.code = "C04-CMD000001";
        unknownKind.kind = "NOT_A_KIND";
        assertEquals(400, resource.ingest(null, unknownKind).getStatus());
    }

    /**
     * The ingest of a readable push is delegated to the shared mechanism
     * (LC-06-03-01): the pushed order becomes the store's own — code, kind,
     * total, deposit VAT detail and lines included, a null line skipped and a
     * missing quantity read as one piece.
     */
    @Test
    void ingestDelegatesThePushedOrder() {
        OrderRecallResource resource = storeResource(Optional.empty());
        OrderRecallResource.OrderPushDto dto = new OrderRecallResource.OrderPushDto();
        dto.code = "C04-CMD000001";
        dto.kind = "COMMANDE";
        dto.totalAmount = new BigDecimal("100.00");
        dto.depositVatDetail = "0.2:1";
        OrderRecallResource.OrderPushLineDto line = new OrderRecallResource.OrderPushLineDto();
        line.ean = "301";
        line.label = "LAIT";
        line.quantity = new BigDecimal("2");
        line.unitPrice = new BigDecimal("3.00");
        line.vatRate = new BigDecimal("0.20");
        dto.lines.add(line);
        dto.lines.add(null);
        OrderRecallResource.OrderPushLineDto unit = new OrderRecallResource.OrderPushLineDto();
        unit.ean = "302";
        unit.label = "PAIN";
        unit.quantity = null;
        unit.unitPrice = new BigDecimal("40.00");
        unit.vatRate = new BigDecimal("0.055");
        dto.lines.add(unit);
        // The shared mechanism answers with the stored order — a fresh mock
        // would answer null and read as a refusal the ingest never made.
        when(resource.customerOrderService.createFromTill(
                org.mockito.ArgumentMatchers.eq("C04-CMD000001"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(order());
        Response response = resource.ingest(null, dto);
        assertEquals(200, response.getStatus());
        org.mockito.ArgumentCaptor<java.util.List<CustomerOrder.OrderLine>> lines =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        org.mockito.ArgumentCaptor<String> detail =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(resource.customerOrderService).createFromTill(
                org.mockito.ArgumentMatchers.eq("C04-CMD000001"),
                org.mockito.ArgumentMatchers.eq(OrderKind.COMMANDE),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("100.00")),
                detail.capture(), lines.capture());
        assertEquals("0.2:1", detail.getValue());
        assertEquals(2, lines.getValue().size());
        assertEquals(0, new BigDecimal("6.00").compareTo(lines.getValue().get(0).totalPrice));
        assertEquals(0, new BigDecimal("40.00").compareTo(lines.getValue().get(1).totalPrice));
        assertEquals(new BigDecimal("1"), lines.getValue().get(1).quantity);
    }

    /**
     * The ingest answers 400 when the shared mechanism refuses the push (the
     * null-return arm).
     */
    @Test
    void ingestAnswersBadRequestWhenTheServiceRefuses() {
        OrderRecallResource resource = storeResource(Optional.empty());
        OrderRecallResource.OrderPushDto dto = new OrderRecallResource.OrderPushDto();
        dto.code = "C04-CMD000001";
        dto.kind = "COMMANDE";
        when(resource.customerOrderService.createFromTill(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(null);
        assertEquals(400, resource.ingest(null, dto).getStatus());
    }

    /**
     * The settle of an order a register closed (LC-06-03-07) is gated like the
     * recall: off-role answers 403, a bad token 401.
     */
    @Test
    void settledIsGated() {
        OrderRecallResource register = new OrderRecallResource();
        register.role = "register";
        register.token = Optional.empty();
        register.customerOrderService = mock(CustomerOrderService.class);
        assertEquals(403, register.settled("CMD1", null).getStatus());
        OrderRecallResource badToken = storeResource(Optional.of("secret"));
        assertEquals(401, badToken.settled("CMD1", "wrong").getStatus());
    }

    /**
     * The settle answers 404 when the store holds no such code, and otherwise
     * follows the shared mechanism: settled answers 200, refused answers 409.
     */
    @Test
    void settledFollowsTheSharedMechanism() {
        OrderRecallResource resource = storeResource(Optional.empty());
        io.quarkus.hibernate.orm.panache.PanacheQuery<CustomerOrder> ghost = query(null);
        io.quarkus.hibernate.orm.panache.PanacheQuery<CustomerOrder> known = query(order());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CustomerOrder.find("code", "GHOST")).thenReturn(ghost);
            assertEquals(404, resource.settled("GHOST", null).getStatus());
            CustomerOrder order = known.firstResult();
            mocked.when(() -> CustomerOrder.find("code", "CMD1")).thenReturn(known);
            when(resource.customerOrderService.settleAtTill(order)).thenReturn(true);
            assertEquals(200, resource.settled("CMD1", null).getStatus());
            when(resource.customerOrderService.settleAtTill(order)).thenReturn(false);
            assertEquals(409, resource.settled("CMD1", null).getStatus());
        }
    }

    /**
     * Wraps a first-result in a Panache query mock.
     *
     * @param row the order the query resolves, or null
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private io.quarkus.hibernate.orm.panache.PanacheQuery<CustomerOrder> query(CustomerOrder row) {
        io.quarkus.hibernate.orm.panache.PanacheQuery<CustomerOrder> query = mock(
                io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        return query;
    }
}
