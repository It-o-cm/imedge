package com.intermarche.edge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intermarche.edge.domain.sale.ThirdPartySystem;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EcommerceBasketService} (DG-01-01, DG-01-03): the
 * stateless facade values the basket the platform sends with the engine's
 * {@code POST /valuation} — point of sale and card promotions completed,
 * declared in progress — under the state rules of the gesture, stores
 * nothing, declares the SALE consumption of a closed basket to
 * {@code /consumptions} and relays the return valuation.
 */
class EcommerceBasketServiceTest {

    /** Reads the relayed bodies. */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * A service over a mocked relay that answers 200 "{"totalPrice":1}", a
     * node of point of sale 0101 and no loyalty programme.
     *
     * @return the service
     */
    private EcommerceBasketService service() {
        EcommerceBasketService service = spy(new EcommerceBasketService());
        service.valuationService = mock(EcommerceValuationService.class);
        service.fidCardPromotionClient = mock(FidCardPromotionClient.class);
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.pdvNumber()).thenReturn("0101");
        when(service.valuationService.relay(anyString(), anyString(), any()))
                .thenReturn(new EcommerceValuationService.Answer(200, "{\"totalPrice\":1}"));
        doReturn(null).when(service).systemPdv(any());
        return service;
    }

    /**
     * Captures the body relayed on a path.
     *
     * @param service the service
     * @param path the path expected
     * @return the body, as an object
     * @throws Exception never, the body being JSON
     */
    private JsonNode relayed(EcommerceBasketService service, String path) throws Exception {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(service.valuationService).relay(eq("POST"), eq(path), body.capture());
        return mapper.readTree(body.getValue());
    }

    /**
     * DG-01-01-01: an opened basket is valued in progress for the node's
     * point of sale, the facade's own fields not sent; the answer carries the
     * platform's identifier, OPEN, the valued basket and the evaluation; an
     * unreadable basket is refused before any call.
     *
     * @throws Exception never
     */
    @Test
    void aBasketIsOpenedStatelessly() throws Exception {
        EcommerceBasketService service = service();
        EcommerceBasketService.Outcome opened = service.open(
                "{\"basketId\":\"P-1\",\"status\":\"OPEN\",\"items\":[{\"produceEan\":\"1\"}]}", null);
        Assertions.assertEquals(200, opened.status());
        JsonNode answer = mapper.readTree(opened.body());
        Assertions.assertEquals("P-1", answer.path("basketId").asText());
        Assertions.assertEquals("OPEN", answer.path("status").asText());
        Assertions.assertEquals(1, answer.path("evaluation").path("totalPrice").asInt());
        Assertions.assertEquals("0101", answer.path("basket").path("storeCode").asText());
        JsonNode sent = relayed(service, "/valuation");
        Assertions.assertFalse(sent.path("closed").asBoolean());
        Assertions.assertFalse(sent.has("basketId"));
        Assertions.assertFalse(sent.has("status"));
        Assertions.assertFalse(mapper.readTree(service.open("{}", null).body()).hasNonNull("basketId"));
        for (String unreadable : new String[] {null, "", "not json", "[1]"}) {
            Assertions.assertEquals(400, service.open(unreadable, null).status());
        }
    }

    /**
     * The state rules: modify and hold from OPEN, resume from ON_HOLD, a
     * stated state lower-case read the same, an absent one accepted; any
     * other answers 409 before any valuation; the hold answer carries its
     * reason; an unreadable identifier answers 400.
     *
     * @throws Exception never
     */
    @Test
    void theStateRulesApplyToTheStatedState() throws Exception {
        EcommerceBasketService service = service();
        Assertions.assertEquals(200, service.modify("B1", "{\"status\":\"open\"}", null).status());
        EcommerceBasketService.Outcome held = service.hold("B1", "{\"reason\":\"client absent\"}", null);
        JsonNode answer = mapper.readTree(held.body());
        Assertions.assertEquals("ON_HOLD", answer.path("status").asText());
        Assertions.assertEquals("client absent", answer.path("holdReason").asText());
        Assertions.assertEquals("B1", answer.path("basketId").asText());
        Assertions.assertEquals("OPEN",
                mapper.readTree(service.resume("B1", "{\"status\":\"ON_HOLD\"}", null).body()).path("status").asText());
        Assertions.assertEquals(409, service.modify("B1", "{\"status\":\"ON_HOLD\"}", null).status());
        Assertions.assertEquals(409, service.hold("B1", "{\"status\":\"CLOSED\"}", null).status());
        Assertions.assertEquals(409, service.resume("B1", "{\"status\":\"OPEN\"}", null).status());
        Assertions.assertEquals(400, service.modify("B/1", "{}", null).status());
        Assertions.assertEquals(400, service.resume("B1", "[]", null).status());
        verify(service.valuationService, org.mockito.Mockito.times(3)).relay(eq("POST"), eq("/valuation"), anyString());
    }

    /**
     * An engine that refuses the valuation is answered as it came, the
     * warning kept.
     */
    @Test
    void anEngineRefusalIsAnswered() {
        EcommerceBasketService service = service();
        when(service.valuationService.relay(anyString(), anyString(), any()))
                .thenReturn(new EcommerceValuationService.Answer(400, "{\"error\":\"items\"}"));
        EcommerceBasketService.Outcome refused = service.open("{}", null);
        Assertions.assertEquals(400, refused.status());
        Assertions.assertEquals("{\"error\":\"items\"}", refused.body());
    }

    /**
     * A body the engine answers that is not JSON rides as text.
     *
     * @throws Exception never
     */
    @Test
    void aTextAnswerRidesAsText() throws Exception {
        EcommerceBasketService service = service();
        when(service.valuationService.relay(anyString(), anyString(), any()))
                .thenReturn(new EcommerceValuationService.Answer(200, "not json"));
        Assertions.assertEquals("not json",
                mapper.readTree(service.open("{}", null).body()).path("evaluation").asText());
    }

    /**
     * DG-01-01-05: a delete stores and calls nothing; a closed basket is a
     * return's business, a deleted one or an unknown state is refused.
     */
    @Test
    void aDeleteChecksTheStateOnly() throws Exception {
        EcommerceBasketService service = service();
        Assertions.assertEquals("DELETED", mapper.readTree(service.delete(" B1 ", null).body()).path("status").asText());
        Assertions.assertEquals(200, service.delete("B1", " on_hold ").status());
        Assertions.assertEquals(200, service.delete("B1", " ").status());
        Assertions.assertEquals("Panier clos : utiliser un retour", service.delete("B1", "CLOSED").body());
        Assertions.assertEquals(409, service.delete("B1", "DELETED").status());
        Assertions.assertEquals(400, service.delete(null, null).status());
        verify(service.valuationService, never()).relay(anyString(), anyString(), any());
    }

    /**
     * DG-01-01-06: the final basket is valued closed, the facade's fields
     * stripped.
     *
     * @throws Exception never
     */
    @Test
    void theFinalBasketIsValuedClosed() throws Exception {
        EcommerceBasketService service = service();
        ObjectNode basket = mapper.createObjectNode().put("status", "OPEN");
        EcommerceBasketService.Valued valued = service.valueFinal(basket, null);
        Assertions.assertEquals(200, valued.status());
        Assertions.assertTrue(relayed(service, "/valuation").path("closed").asBoolean());
        Assertions.assertFalse(valued.basket().has("status"));
    }

    /**
     * DG-01-01-06: the SALE consumption declares each advantage with an offer
     * code and an amount, its share per line of its offer (by identifier or
     * type, residual on the largest share) and its funding; an advantage
     * whose offer holds no share declares no line; nothing to declare calls
     * nothing (204), an unreadable evaluation neither.
     *
     * @throws Exception never
     */
    @Test
    void theSaleConsumptionIsDeclared() throws Exception {
        EcommerceBasketService service = service();
        when(service.valuationService.relay(eq("POST"), eq("/consumptions"), anyString()))
                .thenReturn(new EcommerceValuationService.Answer(200, "{\"duplicate\":false}"));
        String evaluation = """
                {"offers":[{"offerId":"BASE","items":[
                  {"lineId":"L1","produceEan":"E1","amount":{"amountIncludingTax":2.00}},
                  {"lineId":"L2","produceEan":"E2","amount":{"amountIncludingTax":1.00}},
                  {"lineId":"L1","produceEan":"E1","amount":{"amountIncludingTax":1.00}},
                  {"lineId":"","amount":{"amountIncludingTax":1}},{"lineId":"L3"}]},
                  {"type":"FREE","items":[{"lineId":"L9","amount":{"amountIncludingTax":0}}]}],
                 "advantages":[
                  {"offerCode":"COUPON:B","offer":"BASE","funding":"STORE","discountAmount":{"amountIncludingTax":-1.00}},
                  {"offerCode":"FREE","offer":"FREE","discountAmount":{"amountIncludingTax":0.50}},
                  {"offerCode":"NOOFFER","discountAmount":{"amountIncludingTax":0.10}},
                  {"offer":"BASE","discountAmount":{"amountIncludingTax":0.10}},
                  {"type":"Ticket Discount: WEB10_07039","offer":"BASE","discountAmount":{"amountIncludingTax":0.40}},
                  {"offerCode":"UPSELL"}]}
                """;
        Assertions.assertEquals(200, service.declareSale("W1", "0101", "2026-10-28T10:00", evaluation));
        JsonNode declared = relayed(service, "/consumptions");
        Assertions.assertEquals("SALE", declared.path("direction").asText());
        Assertions.assertEquals("W1", declared.path("ticketId").asText());
        JsonNode coupon = declared.path("advantages").get(0);
        Assertions.assertEquals(1.0, coupon.path("amount").asDouble());
        Assertions.assertEquals("STORE", coupon.path("funding").asText());
        Assertions.assertEquals("E1", coupon.path("lines").get(0).path("ean").asText());
        Assertions.assertEquals(0.75, coupon.path("lines").get(0).path("amount").asDouble());
        Assertions.assertEquals(0.25, coupon.path("lines").get(1).path("amount").asDouble());
        Assertions.assertFalse(declared.path("advantages").get(1).has("lines"));
        Assertions.assertFalse(declared.path("advantages").get(2).has("lines"));
        JsonNode ticketDiscount = declared.path("advantages").get(3);
        Assertions.assertEquals("WEB10_07039", ticketDiscount.path("offerCode").asText());
        Assertions.assertEquals(0.40, ticketDiscount.path("amount").asDouble());
        Assertions.assertEquals(0.30, ticketDiscount.path("lines").get(0).path("amount").asDouble());
        Assertions.assertEquals(4, declared.path("advantages").size());
        EcommerceBasketService none = service();
        Assertions.assertEquals(204, none.declareSale("W2", null, "2026-10-28", "{\"advantages\":[]}"));
        Assertions.assertEquals(204, none.declareSale("W2", null, "2026-10-28", "not json"));
        verify(none.valuationService, never()).relay(anyString(), anyString(), any());
    }

    /**
     * The offer code of an advantage is read as the engine reads it: its
     * {@code offerCode}, else the first word after ": " in its label; a label
     * without colon or without word after it, or no label, names none.
     *
     * @throws Exception never
     */
    @Test
    void theOfferCodeIsReadAsTheEngineReadsIt() throws Exception {
        Assertions.assertEquals("C1", EcommerceBasketService.offerCodeOf(mapper.readTree("{\"offerCode\":\" C1 \",\"type\":\"X: C2\"}")));
        Assertions.assertEquals("C2", EcommerceBasketService.offerCodeOf(mapper.readTree("{\"offerCode\":\" \",\"type\":\"Ticket Discount: C2\"}")));
        Assertions.assertEquals("C3", EcommerceBasketService.offerCodeOf(mapper.readTree("{\"type\":\"Coupon Grant: C3 (x2)\"}")));
        Assertions.assertNull(EcommerceBasketService.offerCodeOf(mapper.readTree("{\"type\":\"MEAL_VOUCHER\"}")));
        Assertions.assertNull(EcommerceBasketService.offerCodeOf(mapper.readTree("{\"type\":\"Odd:  \"}")));
        Assertions.assertNull(EcommerceBasketService.offerCodeOf(mapper.readTree("{}")));
    }

    /**
     * DG-01-03, spec §5.4: the CANCELLATION of a return declares, under the
     * return reference, every advantage taken back that names an offer and a
     * non-zero amount, as an absolute amount; nothing to declare calls
     * nothing (204).
     *
     * @throws Exception never
     */
    @Test
    void theCancellationOfAReturnIsDeclared() throws Exception {
        EcommerceBasketService service = service();
        when(service.valuationService.relay(eq("POST"), eq("/consumptions"), anyString()))
                .thenReturn(new EcommerceValuationService.Answer(200, "{\"duplicate\":false}"));
        JsonNode takenBack = mapper.readTree("""
                [{"offerCode":"WEB10_07039","amount":1.35},{"offerCode":" ","amount":1},
                 {"offerCode":"NOAMOUNT"},{"offerCode":"ZERO","amount":0},{"offerCode":"NEG","amount":-0.30}]
                """);
        Assertions.assertEquals(200, service.declareCancellation("RET-1", "07039", "2026-10-28T11:00", takenBack));
        JsonNode declared = relayed(service, "/consumptions");
        Assertions.assertEquals("CANCELLATION", declared.path("direction").asText());
        Assertions.assertEquals("RET-1", declared.path("ticketId").asText());
        Assertions.assertEquals("07039", declared.path("storeCode").asText());
        Assertions.assertEquals("2026-10-28T11:00", declared.path("finalizedAt").asText());
        Assertions.assertEquals(2, declared.path("advantages").size());
        Assertions.assertEquals("WEB10_07039", declared.path("advantages").get(0).path("offerCode").asText());
        Assertions.assertEquals(1.35, declared.path("advantages").get(0).path("amount").asDouble());
        Assertions.assertEquals(0.30, declared.path("advantages").get(1).path("amount").asDouble());
        EcommerceBasketService none = service();
        Assertions.assertEquals(204, none.declareCancellation("RET-2", "07039", "2026-10-28", mapper.readTree("[]")));
        verify(none.valuationService, never()).relay(anyString(), anyString(), any());
    }

    /**
     * An engine failure is answered 502 with its status in words, never with
     * the engine's error page, on a basket gesture and on a return valuation;
     * the node's own 503 (engine out of reach) and a 4xx ride as they came.
     */
    @Test
    void anEngineFailureIsAnswered502() {
        EcommerceBasketService service = service();
        when(service.valuationService.relay(anyString(), anyString(), any()))
                .thenReturn(new EcommerceValuationService.Answer(500, "{\"details\":\"Error id 1\",\"stack\":\"\"}"));
        EcommerceBasketService.Outcome failed = service.open("{}", null);
        Assertions.assertEquals(502, failed.status());
        Assertions.assertEquals("Valorisation en échec dans le moteur (HTTP 500)", failed.body());
        Assertions.assertEquals(502, service.valueReturn("{}", null).status());
        EcommerceBasketService.Outcome away = EcommerceBasketService.engineAnswer(503, "Moteur de valorisation injoignable", "w");
        Assertions.assertEquals(503, away.status());
        Assertions.assertEquals("Moteur de valorisation injoignable", away.body());
        Assertions.assertEquals("w", away.warning());
        Assertions.assertEquals(422, EcommerceBasketService.engineAnswer(422, "{}", null).status());
    }

    /**
     * The point of sale is the declared system's when it has one, the
     * node's otherwise, and none at all leaves the basket unnamed; one the
     * basket names is kept.
     */
    @Test
    void thePointOfSaleComesFromTheSystemThenTheNode() {
        EcommerceBasketService service = service();
        doReturn("0303").when(service).systemPdv("WEB");
        ObjectNode fromSystem = mapper.createObjectNode().put("storeCode", " ");
        service.completeStore(fromSystem, "WEB");
        Assertions.assertEquals("0303", fromSystem.path("storeCode").asText());
        doReturn(" ").when(service).systemPdv("BLANK");
        ObjectNode fromNode = mapper.createObjectNode();
        service.completeStore(fromNode, "BLANK");
        Assertions.assertEquals("0101", fromNode.path("storeCode").asText());
        ObjectNode own = mapper.createObjectNode().put("storeCode", "0202");
        service.completeStore(own, null);
        Assertions.assertEquals("0202", own.path("storeCode").asText());
        when(service.posSettingsService.pdvNumber()).thenReturn("");
        ObjectNode none = mapper.createObjectNode();
        service.completeStore(none, null);
        Assertions.assertFalse(none.has("storeCode"));
    }

    /**
     * The declared system's point of sale is read from the system, null for
     * no system or an unknown one.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theSystemPointOfSaleIsReadFromTheSystem() {
        EcommerceBasketService service = new EcommerceBasketService();
        Assertions.assertNull(service.systemPdv(null));
        ThirdPartySystem system = new ThirdPartySystem();
        system.pdvNumber = "0303";
        try (MockedStatic<PanacheEntityBase> base = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(system, (PanacheEntityBase) null);
            base.when(() -> PanacheEntityBase.find(eq("code"), any(Object[].class))).thenReturn(query);
            Assertions.assertEquals("0303", service.systemPdv("WEB"));
            Assertions.assertNull(service.systemPdv("NONE"));
        }
    }

    /**
     * DG-01-01-01, -02: the card promotions are injected when the basket names
     * a card and a programme is configured; a programme that fails leaves the
     * basket as sent and warns — the warning reaching the answer; no card or
     * no programme asks nothing.
     *
     * @throws Exception never, the client being mocked
     */
    @Test
    void theCardPromotionsAreInjected() throws Exception {
        EcommerceBasketService service = service();
        when(service.fidCardPromotionClient.configured()).thenReturn(true);
        when(service.fidCardPromotionClient.promotions("2900000000001"))
                .thenReturn((com.fasterxml.jackson.databind.node.ArrayNode) mapper.readTree("[{\"ean\":\"3300000000022\"}]"));
        ObjectNode basket = mapper.createObjectNode().put("cardNumber", " 2900000000001 ");
        Assertions.assertNull(service.enrich(basket));
        Assertions.assertEquals("3300000000022", basket.path("cardPromotions").get(0).path("ean").asText());
        when(service.fidCardPromotionClient.promotions("2900000000002")).thenThrow(new IllegalStateException("down"));
        EcommerceBasketService.Outcome warned = service.open("{\"cardNumber\":\"2900000000002\"}", null);
        Assertions.assertEquals(EcommerceBasketService.FID_WARNING, warned.warning());
        Assertions.assertNull(service.enrich(mapper.createObjectNode()));
        when(service.fidCardPromotionClient.configured()).thenReturn(false);
        Assertions.assertNull(service.enrich(mapper.createObjectNode().put("cardNumber", "2900000000003")));
        verify(service.fidCardPromotionClient, never()).promotions("2900000000003");
    }

    /**
     * DG-01-03: a return valuation gets the point of sale and goes to the
     * engine's stateless return path; an unreadable request is refused.
     *
     * @throws Exception never
     */
    @Test
    void aReturnIsValued() throws Exception {
        EcommerceBasketService service = service();
        Assertions.assertEquals(200, service.valueReturn("{\"mode\":\"LINES\"}", null).status());
        Assertions.assertEquals("0101", relayed(service, "/returns/valuation").path("storeCode").asText());
        Assertions.assertEquals(400, service.valueReturn("x", null).status());
    }
}
