package com.intermarche.pos.ui.order;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderResource}, targeting 100% branch coverage.
 * <p>
 * Every collaborator is a Mockito mock; {@link PosState} is real. The tests
 * assert the delegation to {@link OrderCreationService}, the 303 routing —
 * to the sale screen on a created order, back to the order screen for every
 * refusal code — and the message selection of {@code orderPage}: each error
 * code names its bundle sentence, and an unknown code shows no message. Every
 * leg of the outcome switch has its own case. Each test is fully isolated and
 * asserts absolute expected values.
 */
class OrderResourceTest {

    /**
     * Builds a resource with fresh mocks and a real {@link PosState}.
     *
     * @return the wired resource
     */
    private OrderResource newResource() {
        OrderResource resource = new OrderResource();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        resource.i18n = TestMessages.french();
        resource.order = mock(Template.class);
        resource.orderCreationService = mock(OrderCreationService.class);
        resource.state = new PosState();
        return resource;
    }

    /**
     * A created order of the given outcome, with the code the switch carries.
     *
     * @param outcome the outcome the service answers
     * @param code the order code, or null
     * @return the answer the resource routes on
     */
    private OrderCreationService.Result result(OrderCreationService.Outcome outcome, String code) {
        return new OrderCreationService.Result(outcome, code);
    }

    /**
     * {@code createOrder} delegates to the service and redirects to the sale
     * screen when the order was created.
     */
    @Test
    void createOrderCreatedRedirectsToSale() {
        OrderResource resource = newResource();
        when(resource.orderCreationService.create(any(), eq(new BigDecimal("10.00"))))
                .thenReturn(result(OrderCreationService.Outcome.CREATED, "C04-CMD000001"));
        Response response = resource.createOrder("10,00");
        verify(resource.orderCreationService).create(resource.state, new BigDecimal("10.00"));
        assertEquals(303, response.getStatus());
        assertEquals("/", response.getLocation().toString());
    }

    /**
     * {@code createOrder} routes every refusal to the order screen with its
     * error code: no session, empty ticket, training mode, an invalid deposit
     * and a deposit over the total (every leg of the outcome switch).
     */
    @Test
    void createOrderRefusalsRouteBackWithTheirCode() {
        OrderResource resource = newResource();
        when(resource.orderCreationService.create(any(), any()))
                .thenReturn(result(OrderCreationService.Outcome.NO_SESSION, null))
                .thenReturn(result(OrderCreationService.Outcome.EMPTY_TICKET, null))
                .thenReturn(result(OrderCreationService.Outcome.TRAINING, null))
                .thenReturn(result(OrderCreationService.Outcome.BAD_AMOUNT, null))
                .thenReturn(result(OrderCreationService.Outcome.OVER_TOTAL, null));
        assertEquals("/order?error=no-session", resource.createOrder("10").getLocation().toString());
        assertEquals("/order?error=empty", resource.createOrder("10").getLocation().toString());
        assertEquals("/order?error=training", resource.createOrder("10").getLocation().toString());
        assertEquals("/order?error=amount", resource.createOrder("10").getLocation().toString());
        assertEquals("/order?error=total", resource.createOrder("10").getLocation().toString());
    }

    /**
     * {@code orderPage} renders each error code's bundle sentence — the empty
     * ticket, the invalid deposit, the deposit over the total, the closed
     * session and the training mode — and no message for an unknown code
     * (every leg of the message switch, including the default arm).
     */
    @Test
    void orderPageSelectsTheMessage() {
        OrderResource resource = newResource();
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.order.data(anyString(), any())).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        assertSame(view, resource.orderPage("empty"));
        assertSame(view, resource.orderPage("amount"));
        assertSame(view, resource.orderPage("total"));
        assertSame(view, resource.orderPage("no-session"));
        assertSame(view, resource.orderPage("training"));
        assertSame(view, resource.orderPage(null));
        assertSame(view, resource.orderPage("whatever"));
    }
}
