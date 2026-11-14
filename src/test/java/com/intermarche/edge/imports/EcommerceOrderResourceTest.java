package com.intermarche.edge.imports;

import com.intermarche.edge.domain.sale.ThirdPartySystem;
import com.intermarche.edge.service.EcommerceBasketService;
import com.intermarche.edge.service.EcommerceIntegrationService;
import com.intermarche.edge.service.EcommerceOrderViewService;
import com.intermarche.edge.service.EcommerceReturnService;
import com.intermarche.edge.service.PosSettingsService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EcommerceOrderResource}: the platform's surface is
 * gated by the node role and the token, an unreadable order answers 400 with
 * nothing integrated, a readable one is integrated — the very text archived —
 * and its report answered, again on demand by reference.
 */
class EcommerceOrderResourceTest {

    /**
     * A store-role resource over a mocked integration, the given token.
     *
     * @param token the configured token
     * @return the resource
     */
    private EcommerceOrderResource resource(Optional<String> token) {
        EcommerceOrderResource resource = new EcommerceOrderResource();
        resource.role = "STORE";
        resource.token = token;
        resource.integrationService = mock(EcommerceIntegrationService.class);
        resource.posSettingsService = mock(PosSettingsService.class);
        resource.ticketStreamService = mock(com.intermarche.edge.service.sync.TicketStreamService.class);
        resource.ecommerceValuationService = mock(com.intermarche.edge.service.EcommerceValuationService.class);
        resource.ecommerceBasketService = mock(EcommerceBasketService.class);
        resource.ecommerceReturnService = mock(EcommerceReturnService.class);
        resource.ecommerceOrderViewService = mock(EcommerceOrderViewService.class);
        return resource;
    }

    /**
     * BO-11-04-03: an integrated order's report is handed to the status return,
     * and is the answer.
     */
    @Test
    void theReportOfAnIntegrationIsReturnedToThePlatforms() {
        EcommerceOrderResource resource = resource(Optional.empty());
        EcommerceIntegrationService.Report report = new EcommerceIntegrationService.Report();
        when(resource.integrationService.integrate(any(), any(), any())).thenReturn(report);
        assertEquals(200, resource.integrate(null, null,
                "{\"reference\":\"W1\",\"lines\":[{\"ean\":\"1\"}]}").getStatus());
        verify(resource.ticketStreamService).onEcommerceReport(report);
    }

    /**
     * BO-11-04-03: the valuation is gated like the deposit, and the engine's
     * answer is relayed with its status.
     */
    @Test
    void theValuationIsGatedThenRelayed() {
        EcommerceOrderResource guarded = resource(Optional.of("secret"));
        assertEquals(401, guarded.valuate("nope", null, "{}").getStatus());
        verify(guarded.ecommerceValuationService, never()).valuate(any());
        when(guarded.ecommerceValuationService.valuate("{\"lines\":[]}"))
                .thenReturn(new com.intermarche.edge.service.EcommerceValuationService.Answer(200, "{\"total\":1}"));
        jakarta.ws.rs.core.Response answer = guarded.valuate("secret", null, "{\"lines\":[]}");
        assertEquals(200, answer.getStatus());
        assertEquals("{\"total\":1}", answer.getEntity());
    }

    /**
     * Off-role answers 403, a bad token 401, on both entries; an open token or
     * the right one goes through.
     */
    @Test
    void theSurfaceIsGated() {
        EcommerceOrderResource offRole = resource(Optional.empty());
        offRole.role = "register";
        assertEquals(403, offRole.integrate(null, null, "{}").getStatus());
        assertEquals(403, offRole.report("W1", null, null).getStatus());
        assertEquals(403, offRole.integrate(null, "abc", "{}").getStatus());
        EcommerceOrderResource guarded = resource(Optional.of("secret"));
        assertEquals(401, guarded.integrate("nope", null, "{}").getStatus());
        assertEquals(401, guarded.report("W1", null, " ").getStatus());
        assertEquals(404, guarded.report("W1", "secret", null).getStatus());
        assertEquals(404, resource(Optional.of(" ")).report("W1", null, null).getStatus());
    }

    /**
     * Every unreadable order answers 400 and integrates nothing: no body, bad
     * JSON, a null document, no reference, a reference that cannot name a
     * ticket, no line, no line list, a null line.
     */
    @Test
    void anUnreadableOrderIsRefused() {
        EcommerceOrderResource resource = resource(Optional.empty());
        for (String body : new String[] {null, "not json", "null", "{\"lines\":[{}]}",
                "{\"reference\":\"W 1\",\"lines\":[{}]}", "{\"reference\":\"W1\",\"lines\":[]}",
                "{\"reference\":\"W1\",\"lines\":null}", "{\"reference\":\"W1\",\"lines\":[null]}"}) {
            assertEquals(400, resource.integrate(null, null, body).getStatus(), String.valueOf(body));
        }
        verify(resource.integrationService, never()).integrate(any(), any(), any());
    }

    /**
     * A readable order is integrated with the text it came in, unknown fields
     * tolerated and a missing payment list read as empty; the report is the
     * answer.
     */
    @Test
    void aReadableOrderIsIntegratedAndItsReportAnswered() {
        EcommerceOrderResource resource = resource(Optional.empty());
        EcommerceIntegrationService.Report report = new EcommerceIntegrationService.Report();
        when(resource.integrationService.integrate(any(), any(), any())).thenReturn(report);
        String body = "{\"reference\":\" W1 \",\"channel\":\"web\",\"lines\":[{\"ean\":\"1\"}],\"payments\":null}";
        Response response = resource.integrate(null, null, body);
        assertEquals(200, response.getStatus());
        assertSame(report, response.getEntity());
        ArgumentCaptor<EcommerceIntegrationService.OrderDto> order =
                ArgumentCaptor.forClass(EcommerceIntegrationService.OrderDto.class);
        verify(resource.integrationService).integrate(order.capture(), eq(body), isNull());
        assertTrue(order.getValue().payments.isEmpty());
        assertEquals("1", order.getValue().lines.get(0).ean);
        String paid = "{\"reference\":\"W2\",\"lines\":[{\"ean\":\"1\"}],\"payments\":[{\"method\":\"CARD\"}]}";
        resource.integrate(null, null, paid);
        verify(resource.integrationService).integrate(any(), eq(paid), isNull());
    }

    /**
     * DG-03-02-03: an order the node holds is answered again by reference, as
     * its unified view.
     */
    @Test
    void anOrderIsAnsweredAgainByReference() {
        EcommerceOrderResource resource = resource(Optional.empty());
        EcommerceOrderViewService.View view = new EcommerceOrderViewService.View();
        when(resource.ecommerceOrderViewService.view("W1")).thenReturn(view);
        Response response = resource.report("W1", null, null);
        assertEquals(200, response.getStatus());
        assertSame(view, response.getEntity());
    }

    /**
     * DG-03-02-04: the status is gated, 404 for an unknown order, and states
     * the view's status whatever it is.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theStatusIsAnsweredWhateverItIs() {
        EcommerceOrderResource guarded = resource(Optional.of("secret"));
        assertEquals(401, guarded.status("W1", "nope", null).getStatus());
        assertEquals(404, guarded.status("W1", "secret", null).getStatus());
        EcommerceOrderViewService.View view = new EcommerceOrderViewService.View();
        view.reference = "W1";
        view.kind = "ECOMMERCE";
        view.status = "COLLECTED";
        view.remaining = java.math.BigDecimal.ZERO;
        view.ticketNumber = "C04-1";
        when(guarded.ecommerceOrderViewService.view("W1")).thenReturn(view);
        Response response = guarded.status("W1", "secret", null);
        assertEquals(200, response.getStatus());
        java.util.Map<String, Object> status = (java.util.Map<String, Object>) response.getEntity();
        assertEquals("COLLECTED", status.get("status"));
        assertEquals("C04-1", status.get("ticketNumber"));
    }

    /**
     * DG-03-02-01: the closed orders are gated, filtered by the query and
     * refused on an unreadable day; blank filters read as absent.
     */
    @Test
    void theClosedOrdersAreListedWithTheirFilters() {
        EcommerceOrderResource guarded = resource(Optional.of("secret"));
        assertEquals(401, guarded.orders(null, null, null, null, "nope", null).getStatus());
        assertEquals(400, guarded.orders(null, "2026-13-01", null, null, "secret", null).getStatus());
        assertEquals(400, guarded.orders(null, null, "demain", null, "secret", null).getStatus());
        java.util.List<EcommerceOrderViewService.View> views = java.util.List.of(new EcommerceOrderViewService.View());
        when(guarded.ecommerceOrderViewService.closed("PAID", java.time.LocalDate.of(2026, 10, 1),
                java.time.LocalDate.of(2026, 10, 31), "MOBILITY")).thenReturn(views);
        Response filtered = guarded.orders("PAID", " 2026-10-01 ", "2026-10-31", " MOBILITY ", "secret", null);
        assertEquals(200, filtered.getStatus());
        assertSame(views, filtered.getEntity());
        when(guarded.ecommerceOrderViewService.closed(null, null, null, null)).thenReturn(views);
        assertSame(views, guarded.orders(" ", "", " ", "", "secret", null).getEntity());
        assertSame(views, guarded.orders(null, null, null, null, "secret", null).getEntity());
    }

    /**
     * DG-03-02-02: the pending baskets are gated, PENDING or no status lists
     * the store's pending documents, any other status is refused.
     */
    @Test
    void thePendingBasketsAreListed() {
        EcommerceOrderResource guarded = resource(Optional.of("secret"));
        assertEquals(401, guarded.baskets(null, "nope", null).getStatus());
        assertEquals(400, guarded.baskets("CLOSED", "secret", null).getStatus());
        java.util.List<EcommerceOrderViewService.View> pending = java.util.List.of(new EcommerceOrderViewService.View());
        when(guarded.ecommerceOrderViewService.pending()).thenReturn(pending);
        assertSame(pending, guarded.baskets(" pending ", "secret", null).getEntity());
        assertSame(pending, guarded.baskets(null, "secret", null).getEntity());
        assertSame(pending, guarded.baskets(" ", "secret", null).getEntity());
    }

    /**
     * DG-01-01: every basket gesture is gated, then played by the stateless
     * facade with the calling system, its status and warning answered.
     */
    @Test
    void theBasketCallsAreGatedThenPlayed() {
        EcommerceOrderResource guarded = resource(Optional.of("secret"));
        assertEquals(401, guarded.openBasket("nope", null, "{}").getStatus());
        assertEquals(401, guarded.replaceBasket("B1", "nope", null, "{}").getStatus());
        assertEquals(401, guarded.deleteBasket("B1", null, "nope", null).getStatus());
        assertEquals(401, guarded.holdBasket("B1", "nope", null, "{}").getStatus());
        assertEquals(401, guarded.resumeBasket("B1", "nope", null, "{}").getStatus());
        assertEquals(401, guarded.closeBasket("B1", "nope", null, "{}").getStatus());
        assertEquals(401, guarded.valueReturn("nope", null, "{}").getStatus());
        EcommerceBasketService.Outcome warned = new EcommerceBasketService.Outcome(200, "{\"status\":\"OPEN\"}",
                EcommerceBasketService.FID_WARNING);
        EcommerceBasketService.Outcome plain = new EcommerceBasketService.Outcome(200, "{}", null);
        when(guarded.ecommerceBasketService.open("{}", null)).thenReturn(warned);
        when(guarded.ecommerceBasketService.modify("B1", "{}", null)).thenReturn(plain);
        when(guarded.ecommerceBasketService.delete("B1", "OPEN")).thenReturn(plain);
        when(guarded.ecommerceBasketService.hold("B1", "{}", null)).thenReturn(plain);
        when(guarded.ecommerceBasketService.resume("B1", "{}", null)).thenReturn(plain);
        when(guarded.ecommerceBasketService.valueReturn("{}", null)).thenReturn(plain);
        Response opened = guarded.openBasket("secret", null, "{}");
        assertEquals(200, opened.getStatus());
        assertEquals(EcommerceBasketService.FID_WARNING, opened.getHeaderString("Warning"));
        Response modified = guarded.replaceBasket("B1", "secret", null, "{}");
        assertEquals(200, modified.getStatus());
        assertNull(modified.getHeaderString("Warning"));
        assertEquals(200, guarded.deleteBasket("B1", "OPEN", "secret", null).getStatus());
        assertEquals(200, guarded.holdBasket("B1", "secret", null, "{}").getStatus());
        assertEquals(200, guarded.resumeBasket("B1", "secret", null, "{}").getStatus());
        assertEquals(200, guarded.valueReturn("secret", null, "{}").getStatus());
    }

    /**
     * DG-01-01-06: a close carries the order and its final basket, the
     * basket's identifier taken from the path and the order's reference from
     * {@code orderReference} when no {@code reference} is given; an
     * unreadable close, a bad identifier, a missing basket or an unreadable
     * order answers 400, a basket that is not open 409; the report is
     * streamed and answered.
     */
    @Test
    void aCloseIntegratesTheOrderOfTheBasket() {
        EcommerceOrderResource resource = resource(Optional.empty());
        EcommerceIntegrationService.Report report = new EcommerceIntegrationService.Report();
        when(resource.integrationService.integrate(any(), any(), any())).thenReturn(report);
        String body = "{\"orderReference\":\"W1\",\"basket\":{\"status\":\"open\",\"items\":[{}]}}";
        Response closed = resource.closeBasket("B1", null, null, body);
        assertEquals(200, closed.getStatus());
        assertSame(report, closed.getEntity());
        ArgumentCaptor<EcommerceIntegrationService.OrderDto> order =
                ArgumentCaptor.forClass(EcommerceIntegrationService.OrderDto.class);
        verify(resource.integrationService).integrate(order.capture(), eq(body), isNull());
        assertEquals("W1", order.getValue().reference);
        assertEquals("B1", order.getValue().basketId);
        verify(resource.ticketStreamService).onEcommerceReport(report);
        assertEquals(200, resource.closeBasket("B1", null, null,
                "{\"reference\":\"W2\",\"orderReference\":\"X\",\"basket\":{}}").getStatus());
        for (String unreadable : new String[] {null, "not json", "[1]", "{\"orderReference\":\"W1\"}",
                "{\"orderReference\":\"W 1\",\"basket\":{}}"}) {
            assertEquals(400, resource.closeBasket("B1", null, null, unreadable).getStatus(), String.valueOf(unreadable));
        }
        assertEquals(400, resource.closeBasket("B/1", null, null, body).getStatus());
        assertEquals(409, resource.closeBasket("B1", null, null,
                "{\"orderReference\":\"W1\",\"basket\":{\"status\":\"ON_HOLD\"}}").getStatus());
        assertEquals(200, resource.closeBasket("B1", null, null,
                "{\"orderReference\":\"W3\",\"basket\":{\"status\":\" \"}}").getStatus());
    }

    /**
     * DG-01-03: a return is gated, refused when unreadable, recorded
     * otherwise with the text it came in; its report is answered again by
     * reference, 404 when never recorded.
     */
    @Test
    void aReturnIsRecordedAndAnsweredAgain() {
        EcommerceOrderResource guarded = resource(Optional.of("secret"));
        assertEquals(401, guarded.recordReturn("nope", null, "{}").getStatus());
        assertEquals(400, guarded.recordReturn("secret", null, "not json").getStatus());
        assertEquals(400, guarded.recordReturn("secret", null, "null").getStatus());
        String body = "{\"returnReference\":\"R1\",\"unknown\":1}";
        when(guarded.ecommerceReturnService.record(any(), eq(body)))
                .thenReturn(new EcommerceReturnService.Outcome(200, "report"));
        Response recorded = guarded.recordReturn("secret", null, body);
        assertEquals(200, recorded.getStatus());
        assertEquals("report", recorded.getEntity());
        assertEquals(401, guarded.returnReport("R1", "nope", null).getStatus());
        assertEquals(404, guarded.returnReport("R1", "secret", null).getStatus());
        EcommerceReturnService.ReturnReport report = new EcommerceReturnService.ReturnReport();
        when(guarded.ecommerceReturnService.reportOf("R1")).thenReturn(report);
        assertSame(report, guarded.returnReport("R1", "secret", null).getEntity());
    }

    /**
     * The order surface's DG rules: a quantity of zero or less is unreadable
     * (the defect of an order booked with a negative line); an order carrying
     * its final basket may carry no line, even a null line list, while a
     * basket that is not an object does not dispense with lines.
     */
    @Test
    void theBasketAndQuantityRulesOfAnOrder() {
        EcommerceOrderResource resource = resource(Optional.empty());
        for (String body : new String[] {"{\"reference\":\"W1\",\"lines\":[{\"ean\":\"1\",\"quantity\":-1}]}",
                "{\"reference\":\"W1\",\"lines\":[{\"ean\":\"1\",\"quantity\":0}]}",
                "{\"reference\":\"W1\",\"basket\":[1],\"lines\":[]}"}) {
            assertEquals(400, resource.integrate(null, null, body).getStatus(), body);
        }
        verify(resource.integrationService, never()).integrate(any(), any(), any());
        when(resource.integrationService.integrate(any(), any(), any()))
                .thenReturn(new EcommerceIntegrationService.Report());
        assertEquals(200, resource.integrate(null, null,
                "{\"reference\":\"W1\",\"basket\":{},\"lines\":null}").getStatus());
        assertEquals(200, resource.integrate(null, null,
                "{\"reference\":\"W2\",\"lines\":[{\"ean\":\"1\",\"quantity\":2}]}").getStatus());
    }

    /**
     * BO-10-01-04 / -05: the ticket entry is gated, answers 404 for a ticket the
     * node does not serve and the ticket otherwise.
     */
    @Test
    void theTicketEntryServesTheEcommerceTicket() {
        EcommerceOrderResource offRole = resource(Optional.empty());
        offRole.role = "central";
        assertEquals(403, offRole.ticket("ECOM-W1", null).getStatus());
        EcommerceOrderResource resource = resource(Optional.empty());
        assertEquals(404, resource.ticket("ECOM-W1", null).getStatus());
        EcommerceIntegrationService.TicketView view = new EcommerceIntegrationService.TicketView();
        when(resource.integrationService.ticketOf("ECOM-W1")).thenReturn(view);
        Response response = resource.ticket("ECOM-W1", null);
        assertEquals(200, response.getStatus());
        assertSame(view, response.getEntity());
    }

    /**
     * Mocks the lookup of a system by its token.
     *
     * @param ms the Panache statics
     * @param system the system the token names, or null
     */
    @SuppressWarnings("unchecked")
    private void systemFound(MockedStatic<PanacheEntityBase> ms, ThirdPartySystem system) {
        PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(system);
        ms.when(() -> PanacheEntityBase.find(eq("token"), any(Object[].class))).thenReturn(query);
    }

    /**
     * BO-07-01-01: a declared system's token names the system the order is
     * integrated for, the shared token being then irrelevant; an unknown
     * system token answers 401 and integrates nothing.
     */
    @Test
    void aSystemTokenNamesTheSystem() {
        EcommerceOrderResource resource = resource(Optional.of("secret"));
        when(resource.integrationService.integrate(any(), any(), any()))
                .thenReturn(new EcommerceIntegrationService.Report());
        ThirdPartySystem system = new ThirdPartySystem();
        system.code = "WEB";
        String body = "{\"reference\":\"W1\",\"lines\":[{\"ean\":\"1\"}]}";
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            systemFound(ms, system);
            assertEquals(200, resource.integrate("nope", "tok", body).getStatus());
            verify(resource.integrationService).integrate(any(), eq(body), eq("WEB"));
        }
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            systemFound(ms, null);
            Response refused = resource.integrate(null, "unknown", body);
            assertEquals(401, refused.getStatus());
            assertEquals("Jeton de système tiers inconnu", refused.getEntity());
        }
        verify(resource.integrationService, never()).integrate(any(), any(), isNull());
    }

    /**
     * BO-07-01-01: where the store requires a declared system's token, the
     * shared token no longer suffices; the gate names no system for it.
     */
    @Test
    void theSharedTokenNoLongerSufficesWhereRequired() {
        EcommerceOrderResource resource = resource(Optional.empty());
        when(resource.posSettingsService.integrationTokenRequired()).thenReturn(true);
        Response refused = resource.integrate(null, null, "{}");
        assertEquals(401, refused.getStatus());
        assertEquals("Jeton de système tiers obligatoire", refused.getEntity());
        when(resource.posSettingsService.integrationTokenRequired()).thenReturn(false);
        EcommerceOrderResource.Gate gate = resource.gate(null, "");
        assertNull(gate.denied());
        assertNull(gate.systemCode());
        verify(resource.integrationService, never()).integrate(any(), anyString(), any());
    }
}
