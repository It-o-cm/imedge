package com.intermarche.edge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.sale.EcommerceOrder;
import com.intermarche.edge.domain.sale.EcommerceReturn;
import com.intermarche.edge.domain.sale.RefundLine;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import com.intermarche.edge.domain.setting.PosList;
import com.intermarche.edge.domain.setting.PosListItem;
import com.intermarche.edge.domain.sync.FidEvent;
import com.intermarche.edge.service.sync.SyncPayloads;
import com.intermarche.edge.service.sync.register.FidEventOutboxService;
import com.intermarche.edge.service.sync.store.SyncIngestService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EcommerceReturnService} (DG-01-03-01 to -08): a return
 * of the platform is checked — reference, lines, reason on the administered
 * list, origin, quantities, return type — then priced by the engine or from
 * the booked ticket, booked as a refund of its ticket (or as a return ticket
 * when it names none), streamed through the ingestion, told to the loyalty
 * programme, kept and answered; a reference already recorded is answered
 * again, never refunded twice.
 */
class EcommerceReturnServiceTest {

    /** Reads the bodies the service sends. */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * A payment factory of a key.
     *
     * @param key the settlement key
     * @return the factory
     */
    private TicketPayment.Factory factory(String key) {
        TicketPayment.Factory factory = mock(TicketPayment.Factory.class);
        when(factory.getKey()).thenReturn(key);
        return factory;
    }

    /**
     * A spied service: reasons from the settings, returns without origin
     * allowed, every return type allowed, CARD by default, register ECOM; no
     * return recorded yet, no refund taken back, the engine answering 200 "{}".
     *
     * @return the service
     */
    @SuppressWarnings("unchecked")
    private EcommerceReturnService service() {
        EcommerceReturnService service = spy(new EcommerceReturnService());
        service.posSettingsService = mock(PosSettingsService.class);
        service.ecommerceBasketService = mock(EcommerceBasketService.class);
        service.syncIngestService = mock(SyncIngestService.class);
        service.fidEventOutboxService = mock(FidEventOutboxService.class);
        service.objectMapper = new ObjectMapper();
        service.clock = Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneId.of("Europe/Paris"));
        service.paymentFactories = mock(Instance.class);
        List<TicketPayment.Factory> factories = List.of(factory("CARD"), factory("CASH"));
        when(service.paymentFactories.iterator()).thenAnswer(call -> factories.iterator());
        PosSettingsService s = service.posSettingsService;
        when(s.returnReasons()).thenReturn(List.of("Article défectueux", "Changement d'avis"));
        when(s.returnWithoutOriginAllowed()).thenReturn(true);
        when(s.ecommerceReturnTicketTypes()).thenReturn("");
        when(s.ecommerceReturnArticleTypes()).thenReturn("");
        when(s.ecommerceDefaultTender()).thenReturn(" card ");
        when(s.ecommerceTerminal()).thenReturn("ECOM");
        when(s.ecommerceOperator()).thenReturn("admin");
        when(s.ecommerceArchive()).thenReturn(true);
        when(service.ecommerceBasketService.valueReturn(anyString(), any()))
                .thenReturn(new EcommerceBasketService.Outcome(200, "{}", null));
        doReturn(null).when(service).existing(any());
        doReturn(null).when(service).integration(any());
        doReturn(null).when(service).ticket(any());
        doReturn(new ArrayList<>()).when(service).refundedLines(any());
        doReturn(new ArrayList<>()).when(service).reasonLists();
        doReturn("Libellé").when(service).label(any());
        doNothing().when(service).save(any());
        return service;
    }

    /**
     * A ticket line.
     *
     * @param id its id
     * @param uid its identity
     * @param ean its EAN
     * @param quantity its quantity
     * @param total its total, TTC
     * @param vat its VAT rate, or null
     * @return the line
     */
    private TicketLine line(long id, String uid, String ean, String quantity, String total, String vat) {
        TicketLine line = new TicketLine();
        line.id = id;
        line.lineUid = uid;
        line.ean = ean;
        line.productLabel = "Article " + ean;
        line.quantity = new BigDecimal(quantity);
        line.totalPrice = new BigDecimal(total);
        line.unitPrice = line.totalPrice.divide(line.quantity);
        line.vatRate = vat == null ? null : new BigDecimal(vat);
        return line;
    }

    /**
     * The ticket ECOM-W1 of the virtual register: milk 2 x 2.76, rice 1.80,
     * a cancelled line and a return line; card 4242.
     *
     * @return the ticket
     */
    private Ticket ticket() {
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "ECOM-W1";
        ticket.terminalId = "ECOM";
        ticket.fidelityCard = "4242";
        ticket.lines.add(line(1, "u1", "E1", "2", "5.52", "0.20"));
        ticket.lines.add(line(2, "u2", "E2", "1", "1.80", null));
        TicketLine cancelled = line(3, "u3", "E1", "5", "5.00", "0.20");
        cancelled.cancelled = true;
        ticket.lines.add(cancelled);
        TicketLine returned = line(4, "u4", "E1", "5", "5.00", "0.20");
        returned.returnLine = true;
        ticket.lines.add(returned);
        return ticket;
    }

    /**
     * The order W1, integrated as ECOM-W1, closed from a basket or not.
     *
     * @param snapshot unused: the node keeps no snapshot, the platform sends it
     * @return the row
     */
    private EcommerceOrder order(String snapshot) {
        EcommerceOrder order = new EcommerceOrder();
        order.reference = "W1";
        order.ticketNumber = "ECOM-W1";
        return order;
    }

    /**
     * The closing snapshot the platform kept from the close of W1.
     *
     * @return the snapshot
     */
    private JsonNode snapshot() {
        try {
            return mapper.readTree("{\"request\":{\"items\":[]},\"evaluation\":{\"offers\":[{\"offerId\":\"BASE\"}]}}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A returned line.
     *
     * @param ean the EAN
     * @param quantity the quantity
     * @param source the price source, or null
     * @return the line
     */
    private EcommerceReturnService.ReturnLineDto returned(String ean, String quantity, String source) {
        EcommerceReturnService.ReturnLineDto line = new EcommerceReturnService.ReturnLineDto();
        line.ean = ean;
        line.quantity = new BigDecimal(quantity);
        line.priceSource = source;
        return line;
    }

    /**
     * A return R1 of the given lines, reason "Article défectueux".
     *
     * @param lines the lines
     * @return the return
     */
    private EcommerceReturnService.ReturnDto request(EcommerceReturnService.ReturnLineDto... lines) {
        EcommerceReturnService.ReturnDto request = new EcommerceReturnService.ReturnDto();
        request.returnReference = "R1";
        request.reasonCode = "Article défectueux";
        request.returnType = "Retour web";
        request.lines = new ArrayList<>(List.of(lines));
        return request;
    }

    /**
     * The valuation request the service sent to the engine.
     *
     * @param service the service
     * @return the request
     * @throws Exception never, the request being JSON
     */
    private JsonNode valuation(EcommerceReturnService service) throws Exception {
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(service.ecommerceBasketService).valueReturn(sent.capture(), eq(null));
        return mapper.readTree(sent.getValue());
    }

    /**
     * The refund the service booked.
     *
     * @param service the service
     * @return the refund
     */
    private SyncPayloads.RefundDto refund(EcommerceReturnService service) {
        ArgumentCaptor<SyncPayloads.RefundDto> refund = ArgumentCaptor.forClass(SyncPayloads.RefundDto.class);
        verify(service.syncIngestService).ingestRefund(refund.capture());
        return refund.getValue();
    }

    /**
     * DG-01-03-01: a whole order closed from a basket is valued FULL from its
     * snapshot, booked as a refund of its ticket with the reason and the
     * ticket return type, told to the loyalty programme and kept.
     *
     * @throws Exception never
     */
    @Test
    void aWholeOrderIsReturnedFromItsSnapshot() throws Exception {
        EcommerceReturnService service = service();
        doReturn(order("B1")).when(service).integration("W1");
        doReturn(ticket()).when(service).ticket("ECOM-W1");
        when(service.ecommerceBasketService.valueReturn(anyString(), any())).thenReturn(new EcommerceBasketService.Outcome(200,
                "{\"lines\":[{\"amount\":-5.00,\"referencePrice\":2.50,\"vatRate\":0.20},{\"amountIncludingTax\":-1.70}]}", null));
        EcommerceReturnService.ReturnDto request = request(returned("E1", "2", "order"), returned("E2", "1", null));
        request.orderReference = " W1 ";
        request.orderSnapshot = snapshot();
        EcommerceReturnService.Outcome outcome = service.record(request, "{}");
        Assertions.assertEquals(200, outcome.status());
        EcommerceReturnService.ReturnReport report = (EcommerceReturnService.ReturnReport) outcome.body();
        Assertions.assertEquals("RECORDED", report.status);
        Assertions.assertEquals("ECOM-R-R1", report.refundNumber);
        Assertions.assertEquals(new BigDecimal("6.70"), report.total);
        Assertions.assertEquals("TICKET", report.returnScope);
        Assertions.assertTrue(report.fidelityQueued);
        Assertions.assertEquals("CARD", report.refunds.get(0).integratedAs());
        JsonNode sent = valuation(service);
        Assertions.assertEquals("FULL", sent.path("mode").asText());
        Assertions.assertEquals("BASE", sent.path("snapshot").path("evaluation").path("offers").get(0).path("offerId").asText());
        Assertions.assertEquals("u1", sent.path("lines").get(0).path("lineId").asText());
        SyncPayloads.RefundDto refund = refund(service);
        Assertions.assertEquals("ECOM-W1", refund.originalTicketNumber);
        Assertions.assertEquals("Article défectueux", refund.returnReasonCode);
        Assertions.assertEquals("TICKET", refund.returnTypeCode);
        Assertions.assertEquals("CARD", refund.refundMethod);
        Assertions.assertEquals("u2", refund.lines.get(1).originalLineUid);
        Assertions.assertEquals(new BigDecimal("2.5000"), refund.lines.get(0).price);
        Assertions.assertEquals(new BigDecimal("2.50"), report.lines.get(0).unitPrice);
        Assertions.assertEquals(new BigDecimal("1.70"), report.lines.get(1).unitPrice);
        ArgumentCaptor<String> event = ArgumentCaptor.forClass(String.class);
        verify(service.fidEventOutboxService).enqueue(eq(FidEvent.EventType.TICKET_RETURN), event.capture());
        JsonNode fid = mapper.readTree(event.getValue());
        Assertions.assertEquals("2026-ECOM-W1", fid.path("originTicketRef").asText());
        Assertions.assertEquals("u1", fid.path("lines").get(0).path("lineId").asText());
        ArgumentCaptor<EcommerceReturn> row = ArgumentCaptor.forClass(EcommerceReturn.class);
        verify(service).save(row.capture());
        Assertions.assertEquals("{}", row.getValue().originalPayload);
        Assertions.assertEquals("Retour enregistré", row.getValue().message);
        Assertions.assertNotNull(row.getValue().report);
    }

    /**
     * DG-01-03-01 with the real engine: a full return answers every order
     * line in the order's own sequence, so each returned line takes the
     * engine line of its order line, not of its rank; the advantages the
     * engine took back are declared as a CANCELLATION under the return
     * reference and the order's point of sale.
     *
     * @throws Exception never
     */
    @Test
    void aFullReturnMatchesTheEngineLinesAndCancelsTheAdvantages() throws Exception {
        EcommerceReturnService service = service();
        EcommerceOrder order = order("B1");
        order.pdvNumber = " 07039 ";
        doReturn(order).when(service).integration("W1");
        doReturn(ticket()).when(service).ticket("ECOM-W1");
        when(service.ecommerceBasketService.valueReturn(anyString(), any())).thenReturn(new EcommerceBasketService.Outcome(200,
                "{\"lines\":[{\"lineId\":\"u2\",\"amount\":1.62,\"referencePrice\":1.80,\"vatRate\":0.055},"
                        + "{\"lineId\":\"u1\",\"amount\":4.97,\"referencePrice\":2.76,\"vatRate\":0.20}],"
                        + "\"advantagesTakenBack\":[{\"offerCode\":\"WEB10_07039\",\"amount\":0.73}]}", null));
        when(service.ecommerceBasketService.declareCancellation(anyString(), anyString(), anyString(), any())).thenReturn(200);
        EcommerceReturnService.ReturnDto request = request(returned("E1", "2", "ORDER"), returned("E2", "1", "ORDER"));
        request.orderReference = "W1";
        request.orderSnapshot = snapshot();
        EcommerceReturnService.ReturnReport report =
                (EcommerceReturnService.ReturnReport) service.record(request, "{}").body();
        Assertions.assertEquals(new BigDecimal("4.97"), report.lines.get(0).amount);
        Assertions.assertEquals(new BigDecimal("2.76"), report.lines.get(0).unitPrice);
        Assertions.assertEquals(new BigDecimal("1.62"), report.lines.get(1).amount);
        Assertions.assertEquals(new BigDecimal("6.59"), report.total);
        Assertions.assertTrue(report.consumptionCancelled);
        Assertions.assertTrue(report.warnings.isEmpty());
        ArgumentCaptor<JsonNode> takenBack = ArgumentCaptor.forClass(JsonNode.class);
        verify(service.ecommerceBasketService).declareCancellation(eq("R1"), eq("07039"), eq("2026-10-28T11:00"),
                takenBack.capture());
        Assertions.assertEquals("WEB10_07039", takenBack.getValue().get(0).path("offerCode").asText());
    }

    /**
     * Two returned lines of the same order line take its engine lines in turn,
     * the rank answering once that order line's engine lines are used up.
     *
     * @throws Exception never
     */
    @Test
    void returnedLinesOfOneOrderLineTakeItsEngineLinesInTurn() throws Exception {
        EcommerceReturnService service = service();
        doReturn(order("B1")).when(service).integration("W1");
        doReturn(ticket()).when(service).ticket("ECOM-W1");
        when(service.ecommerceBasketService.valueReturn(anyString(), any())).thenReturn(new EcommerceBasketService.Outcome(200,
                "{\"lines\":[{\"lineId\":\"u1\",\"amount\":2.70},{\"amount\":2.60}]}", null));
        EcommerceReturnService.ReturnDto request = request(returned("E1", "1", "ORDER"), returned("E1", "1", "ORDER"));
        request.orderReference = "W1";
        request.orderSnapshot = snapshot();
        EcommerceReturnService.ReturnReport report =
                (EcommerceReturnService.ReturnReport) service.record(request, "{}").body();
        Assertions.assertEquals(new BigDecimal("2.70"), report.lines.get(0).amount);
        Assertions.assertEquals(new BigDecimal("2.60"), report.lines.get(1).amount);
        Assertions.assertFalse(report.consumptionCancelled);
        verify(service.ecommerceBasketService, never()).declareCancellation(anyString(), anyString(), anyString(), any());
    }

    /**
     * A successive partial return of the same order tells the engine the quantities earlier
     * returns already took back, keyed by the snapshot line ({@code lineUid}), so it values the
     * return on the remaining basket and can refuse an excess. The current return's own line is
     * excluded (it is not yet booked), and a line with no earlier return is absent from the map.
     *
     * @throws Exception never
     */
    @Test
    void aSuccessivePartialReturnTransmitsPriorReturns() throws Exception {
        EcommerceReturnService service = service();
        doReturn(order("B1")).when(service).integration("W1");
        doReturn(ticket()).when(service).ticket("ECOM-W1");
        RefundLine prior = new RefundLine();
        prior.quantity = new BigDecimal("1");
        doReturn(List.of(prior)).when(service).refundedLines(1L);
        when(service.ecommerceBasketService.valueReturn(anyString(), any())).thenReturn(new EcommerceBasketService.Outcome(200,
                "{\"lines\":[{\"lineId\":\"u1\",\"amount\":-2.76,\"referencePrice\":2.76,\"vatRate\":0.20}]}", null));
        EcommerceReturnService.ReturnDto request = request(returned("E1", "1", "ORDER"));
        request.orderReference = "W1";
        request.orderSnapshot = snapshot();
        Assertions.assertEquals(200, service.record(request, "{}").status());
        JsonNode sent = valuation(service);
        Assertions.assertEquals("PARTIAL", sent.path("mode").asText());
        Assertions.assertEquals(0,
                new BigDecimal("1").compareTo(sent.path("previousReturns").path("returned").path("u1").decimalValue()));
        Assertions.assertTrue(sent.path("previousReturns").path("returned").path("u2").isMissingNode());
    }

    /**
     * With no earlier return on any line, the request carries no {@code previousReturns} node:
     * the fix is inert on a first return.
     *
     * @throws Exception never
     */
    @Test
    void aFirstReturnCarriesNoPreviousReturns() throws Exception {
        EcommerceReturnService service = service();
        doReturn(order("B1")).when(service).integration("W1");
        doReturn(ticket()).when(service).ticket("ECOM-W1");
        when(service.ecommerceBasketService.valueReturn(anyString(), any())).thenReturn(new EcommerceBasketService.Outcome(200,
                "{\"lines\":[{\"lineId\":\"u1\",\"amount\":-2.76,\"referencePrice\":2.76,\"vatRate\":0.20}]}", null));
        EcommerceReturnService.ReturnDto request = request(returned("E1", "1", "ORDER"));
        request.orderReference = "W1";
        request.orderSnapshot = snapshot();
        Assertions.assertEquals(200, service.record(request, "{}").status());
        JsonNode sent = valuation(service);
        Assertions.assertTrue(sent.path("previousReturns").isMissingNode());
    }

    /**
     * The CANCELLATION is declared for the node's point of sale when the
     * order names none (absent, blank or no order at all); a declaration the
     * engine refuses, cannot be reached for, or finds nothing in is reported
     * as not taken, the first two with a warning.
     *
     * @throws Exception never
     */
    @Test
    void theCancellationIsReportedAsTheEngineTakesIt() throws Exception {
        EcommerceReturnService service = service();
        when(service.posSettingsService.pdvNumber()).thenReturn("0101");
        ArrayNode takenBack =
                (ArrayNode) mapper.readTree("[{\"offerCode\":\"C\",\"amount\":1}]");
        EcommerceOrder blank = order(null);
        blank.pdvNumber = " ";
        int[] statuses = {0, 503, 204, 200};
        EcommerceOrder[] orders = {null, blank, order(null), null};
        boolean[] taken = {false, false, false, true};
        int[] warnings = {1, 1, 0, 0};
        for (int i = 0; i < statuses.length; i++) {
            when(service.ecommerceBasketService.declareCancellation(eq("R" + i), eq("0101"), anyString(), any()))
                    .thenReturn(statuses[i]);
            EcommerceReturnService.ReturnReport report = new EcommerceReturnService.ReturnReport();
            report.returnReference = "R" + i;
            service.cancel(report, orders[i], takenBack);
            Assertions.assertEquals(taken[i], report.consumptionCancelled, "status " + statuses[i]);
            Assertions.assertEquals(warnings[i], report.warnings.size(), "status " + statuses[i]);
        }
        Assertions.assertEquals("Annulation de consommation des offres non déclarée (HTTP 503)",
                ((EcommerceReturnService.ReturnReport) reportCancelled(service, 503)).warnings.get(0));
    }

    /**
     * Cancels a return whose engine declaration answers a status.
     *
     * @param service the service
     * @param status the status the engine answers
     * @return the report
     * @throws Exception never
     */
    private Object reportCancelled(EcommerceReturnService service, int status) throws Exception {
        when(service.ecommerceBasketService.declareCancellation(eq("RX"), anyString(), anyString(), any())).thenReturn(status);
        EcommerceReturnService.ReturnReport report = new EcommerceReturnService.ReturnReport();
        report.returnReference = "RX";
        service.cancel(report, null, (ArrayNode) mapper.readTree("[{\"offerCode\":\"C\"}]"));
        return report;
    }

    /**
     * DG-01-03-03: part of an order closed from a basket is valued PARTIAL,
     * as an article return; a rate the engine does not state is the line's.
     *
     * @throws Exception never
     */
    @Test
    void partOfAnOrderIsReturnedFromItsSnapshot() throws Exception {
        EcommerceReturnService service = service();
        doReturn(order("B1")).when(service).integration("W1");
        Ticket ticket = ticket();
        ticket.fidelityCard = " ";
        doReturn(ticket).when(service).ticket("ECOM-W1");
        when(service.ecommerceBasketService.valueReturn(anyString(), any())).thenReturn(new EcommerceBasketService.Outcome(200,
                "{\"lines\":[{\"amount\":-2.40}]}", null));
        EcommerceReturnService.ReturnDto request = request(returned("E1", "1", "ORDER"));
        request.orderReference = "W1";
        request.orderSnapshot = snapshot();
        request.refunds = new ArrayList<>(List.of(refundOf("PAYPAL", "2.40")));
        EcommerceReturnService.ReturnReport report =
                (EcommerceReturnService.ReturnReport) service.record(request, "{}").body();
        Assertions.assertEquals("PARTIAL", valuation(service).path("mode").asText());
        EcommerceReturnService current = service();
        doReturn(order("B1")).when(current).integration("W1");
        doReturn(ticket).when(current).ticket("ECOM-W1");
        when(current.ecommerceBasketService.valueReturn(anyString(), any())).thenReturn(new EcommerceBasketService.Outcome(200,
                "{\"lines\":[{\"amount\":-2.76}]}", null));
        EcommerceReturnService.ReturnDto atToday = request(returned("E1", "1", "CURRENT"));
        atToday.orderReference = "W1";
        atToday.orderSnapshot = snapshot();
        current.record(atToday, "{}");
        Assertions.assertEquals("LINES", valuation(current).path("mode").asText());
        Assertions.assertEquals("ARTICLE", report.returnScope);
        Assertions.assertFalse(report.fidelityQueued);
        Assertions.assertEquals(new BigDecimal("0.20"), refund(service).lines.get(0).vatRate);
        Assertions.assertEquals("CARD", report.refunds.get(0).integratedAs());
        Assertions.assertEquals("PAYPAL", report.refunds.get(0).method());
        Assertions.assertTrue(report.warnings.isEmpty());
    }

    /**
     * A platform refund.
     *
     * @param method the means
     * @param amount the amount
     * @return the refund
     */
    private EcommerceReturnService.RefundDto refundOf(String method, String amount) {
        EcommerceReturnService.RefundDto refund = new EcommerceReturnService.RefundDto();
        refund.method = method;
        refund.amount = amount == null ? null : new BigDecimal(amount);
        return refund;
    }

    /**
     * DG-01-03-06 / -04: an order closed before the engine kept snapshots
     * takes the price paid on its ticket for an ORDER line, without calling
     * the engine; a CURRENT line of the same return goes to the engine in
     * LINES mode alone, its unit price derived from its amount; a ticket
     * named by its number serves as origin.
     *
     * @throws Exception never
     */
    @Test
    void anOrderWithoutSnapshotTakesThePricePaid() throws Exception {
        EcommerceReturnService service = service();
        doReturn(ticket()).when(service).ticket("ECOM-W1");
        EcommerceReturnService.ReturnDto onlyOrder = request(returned("E1", "1", "ORDER"));
        onlyOrder.ticketNumber = "ECOM-W1";
        EcommerceReturnService.ReturnReport paid =
                (EcommerceReturnService.ReturnReport) service.record(onlyOrder, "{}").body();
        Assertions.assertEquals(new BigDecimal("2.76"), paid.total);
        Assertions.assertNull(paid.orderReference);
        verify(service.ecommerceBasketService, never()).valueReturn(anyString(), any());
        EcommerceReturnService mixed = service();
        doReturn(order(null)).when(mixed).integration("W1");
        doReturn(ticket()).when(mixed).ticket("ECOM-W1");
        when(mixed.ecommerceBasketService.valueReturn(anyString(), any())).thenReturn(new EcommerceBasketService.Outcome(200,
                "{\"lines\":[{\"refundAmount\":-2.00}]}", null));
        EcommerceReturnService.ReturnDto request = request(returned("E1", "1", "ORDER"), returned("E2", "1", "CURRENT"));
        request.lines.get(1).lineUid = "u2";
        request.orderReference = "W1";
        EcommerceReturnService.ReturnReport report =
                (EcommerceReturnService.ReturnReport) mixed.record(request, "{}").body();
        JsonNode sent = valuation(mixed);
        Assertions.assertEquals("LINES", sent.path("mode").asText());
        Assertions.assertEquals("W1", sent.path("orderReference").asText());
        Assertions.assertFalse(sent.has("snapshot"));
        Assertions.assertEquals(1, sent.path("lines").size());
        Assertions.assertEquals(new BigDecimal("2.00"), report.lines.get(1).unitPrice);
        Assertions.assertEquals(BigDecimal.ZERO, refund(mixed).lines.get(1).vatRate);
        Assertions.assertEquals(new BigDecimal("4.76"), report.total);
    }

    /**
     * DG-01-03-04, -07, -08: a return naming no order is valued line by line
     * (current price, price at a date, pushed price) and booked as a return
     * ticket of the virtual register, its lines and refunds negative; the
     * refunds stated are kept under their tenders, a total that differs is
     * reported.
     *
     * @throws Exception never
     */
    @Test
    void aReturnWithoutOrderIsBookedAsAReturnTicket() throws Exception {
        EcommerceReturnService service = service();
        when(service.ecommerceBasketService.valueReturn(anyString(), any())).thenReturn(new EcommerceBasketService.Outcome(200,
                "{\"lines\":[{\"amount\":-2.76,\"unitPrice\":2.76,\"vatRate\":0.20},"
                        + "{\"amount\":-0.96,\"pricePerUnitInclTax\":0.96},{\"amount\":-1.80}]}", null));
        EcommerceReturnService.ReturnLineDto current = returned("E1", "1", "CURRENT");
        EcommerceReturnService.ReturnLineDto dated = returned("E2", "1", "DATE");
        dated.priceDate = " 2025-06-15T10:00:00 ";
        dated.vatRate = new BigDecimal("0.055");
        EcommerceReturnService.ReturnLineDto pushed = returned("E3", "1", "PUSHED");
        pushed.unitPrice = new BigDecimal("1.80");
        pushed.vatRate = new BigDecimal("0.20");
        EcommerceReturnService.ReturnDto request = request(current, dated, pushed);
        request.reasonCode = "changement d'avis";
        request.returnType = null;
        request.refunds = new ArrayList<>(java.util.Arrays.asList(refundOf("cash", "4.00"), null,
                refundOf("CARD", null), refundOf("CARD", "0"), refundOf(null, "1.00")));
        EcommerceReturnService.ReturnReport report =
                (EcommerceReturnService.ReturnReport) service.record(request, "{}").body();
        JsonNode lines = valuation(service).path("lines");
        Assertions.assertFalse(lines.get(0).has("lineId"));
        Assertions.assertEquals("2025-06-15T10:00:00", lines.get(1).path("priceDate").asText());
        Assertions.assertFalse(lines.get(1).has("pricePerUnitInclTax"));
        Assertions.assertEquals(1.5, lines.get(2).path("pricePerUnitExclTax").asDouble());
        Assertions.assertEquals(new BigDecimal("5.52"), report.total);
        Assertions.assertNull(report.originalTicketNumber);
        Assertions.assertEquals(1, report.warnings.size());
        Assertions.assertEquals("CASH", report.refunds.get(0).integratedAs());
        Assertions.assertEquals("CARD", report.refunds.get(1).integratedAs());
        ArgumentCaptor<SyncPayloads.TicketDto> booked = ArgumentCaptor.forClass(SyncPayloads.TicketDto.class);
        verify(service.syncIngestService).ingestTicket(booked.capture());
        SyncPayloads.TicketDto ticket = booked.getValue();
        Assertions.assertEquals("ECOM-R-R1", ticket.ticketNumber);
        Assertions.assertEquals(new BigDecimal("-5.52"), ticket.totalIncludingTax);
        Assertions.assertTrue(ticket.lines.get(0).returnLine);
        Assertions.assertEquals(new BigDecimal("-1"), ticket.lines.get(0).quantity);
        Assertions.assertEquals("Libellé", ticket.lines.get(0).productLabel);
        Assertions.assertEquals(new BigDecimal("0.055"), ticket.lines.get(1).vatRate);
        Assertions.assertEquals("CASH", ticket.payments.get(0).tenderCode);
        Assertions.assertNull(ticket.payments.get(1).tenderCode);
        Assertions.assertEquals(new BigDecimal("-4.00"), ticket.payments.get(0).amount);
        verify(service.syncIngestService, never()).ingestRefund(any());
        verify(service.fidEventOutboxService, never()).enqueue(any(), any());
    }

    /**
     * An unreadable return answers 400: no or a bad reference, no line, a null
     * line, a line without article or with a quantity that is not strictly
     * positive, an unknown price source.
     */
    @Test
    void anUnreadableReturnIsRefused() {
        EcommerceReturnService service = service();
        EcommerceReturnService.ReturnDto noReference = request(returned("E1", "1", null));
        noReference.returnReference = null;
        EcommerceReturnService.ReturnDto badReference = request(returned("E1", "1", null));
        badReference.returnReference = "R 1";
        EcommerceReturnService.ReturnDto noLines = request();
        noLines.lines = null;
        EcommerceReturnService.ReturnDto emptyLines = request();
        EcommerceReturnService.ReturnDto nullLine = request();
        nullLine.lines.add(null);
        EcommerceReturnService.ReturnDto noEan = request(returned(null, "1", null));
        EcommerceReturnService.ReturnDto blankEan = request(returned(" ", "1", null));
        EcommerceReturnService.ReturnDto noQuantity = request(returned("E1", "1", null));
        noQuantity.lines.get(0).quantity = null;
        EcommerceReturnService.ReturnDto negative = request(returned("E1", "-1", null));
        EcommerceReturnService.ReturnDto unknownSource = request(returned("E1", "1", "LATER"));
        for (EcommerceReturnService.ReturnDto refused : List.of(noReference, badReference, noLines, emptyLines,
                nullLine, noEan, blankEan, noQuantity, negative, unknownSource)) {
            Assertions.assertEquals(400, service.record(refused, "{}").status());
        }
        Assertions.assertEquals("Source de prix inconnue : LATER", service.invalid(unknownSource));
        EcommerceReturnService.ReturnDto blankSource = request(returned("E1", "1", " "));
        Assertions.assertNull(service.invalid(blankSource));
        Assertions.assertEquals("ORDER", blankSource.lines.get(0).priceSource);
        verify(service, never()).save(any());
    }

    /**
     * Idempotence: a reference already recorded is answered again with its
     * report, marked as a repeat; a report that cannot be read is rebuilt
     * from the row; nothing is booked twice.
     */
    @Test
    void aRecordedReturnIsAnsweredAgain() {
        EcommerceReturnService service = service();
        EcommerceReturn known = new EcommerceReturn();
        known.reference = "R1";
        known.refundNumber = "ECOM-R-R1";
        known.totalAmount = new BigDecimal("6.70");
        known.report = "{\"returnReference\":\"R1\",\"status\":\"RECORDED\",\"total\":6.70}";
        doReturn(known).when(service).existing("R1");
        EcommerceReturnService.ReturnReport again =
                (EcommerceReturnService.ReturnReport) service.record(request(returned("E1", "1", null)), "{}").body();
        Assertions.assertTrue(again.alreadyReceived);
        Assertions.assertEquals(new BigDecimal("6.70"), again.total);
        known.report = "not json";
        EcommerceReturnService.ReturnReport rebuilt = service.reportOf("R1");
        Assertions.assertEquals("ECOM-R-R1", rebuilt.refundNumber);
        Assertions.assertTrue(rebuilt.alreadyReceived);
        Assertions.assertNull(service.reportOf("R2"));
        verify(service.syncIngestService, never()).ingestRefund(any());
    }

    /**
     * DG-01-03-02, -05: a reason off the administered list answers 400; an
     * empty list asks for no reason and refuses a code nobody administered.
     */
    @Test
    void theReasonMustBeOnTheList() {
        EcommerceReturnService service = service();
        EcommerceReturnService.ReturnDto offList = request(returned("E1", "1", "CURRENT"));
        offList.reasonCode = "Taille";
        EcommerceReturnService.Outcome refused = service.record(offList, "{}");
        Assertions.assertEquals(400, refused.status());
        Assertions.assertEquals("Motif de retour hors liste : Taille", refused.body());
        EcommerceReturnService.ReturnDto none = request(returned("E1", "1", "CURRENT"));
        none.reasonCode = null;
        Assertions.assertEquals("Motif de retour hors liste : null", service.record(none, "{}").body());
        when(service.posSettingsService.returnReasons()).thenReturn(List.of());
        Assertions.assertNull(service.reason(null).code());
        Assertions.assertNull(service.reason(" ").label());
        Assertions.assertNull(service.reason("Taille"));
    }

    /**
     * DG-01-03-02: the store's RETURN_REASON list wins over the setting, its
     * active entries matched by code or label.
     */
    @Test
    void theReasonListOfTheStoreWins() {
        EcommerceReturnService service = service();
        PosList list = new PosList();
        PosListItem broken = new PosListItem();
        broken.code = "DEF";
        broken.label = "Défectueux";
        PosListItem retired = new PosListItem();
        retired.code = "OLD";
        retired.label = "Ancien";
        retired.active = false;
        list.items.add(broken);
        list.items.add(retired);
        doReturn(List.of(list)).when(service).reasonLists();
        Assertions.assertEquals("Défectueux", service.reason("def").label());
        Assertions.assertEquals("DEF", service.reason("défectueux").code());
        Assertions.assertNull(service.reason("OLD"));
        Assertions.assertNull(service.reason("Article défectueux"));
    }

    /**
     * The origin must exist: an unknown order, an order booked as no ticket,
     * an unknown ticket or one of another register answer 404; a return naming
     * none is refused where the store takes no return without origin, and so
     * is a line priced from an order it does not name.
     */
    @Test
    void theOriginMustExist() {
        EcommerceReturnService service = service();
        EcommerceReturnService.ReturnDto unknownOrder = request(returned("E1", "1", "ORDER"));
        unknownOrder.orderReference = "W9";
        Assertions.assertEquals(404, service.record(unknownOrder, "{}").status());
        EcommerceOrder unbooked = order(null);
        unbooked.ticketNumber = null;
        doReturn(unbooked).when(service).integration("W1");
        EcommerceReturnService.ReturnDto noTicket = request(returned("E1", "1", "ORDER"));
        noTicket.orderReference = "W1";
        Assertions.assertEquals(404, service.record(noTicket, "{}").status());
        EcommerceReturnService.ReturnDto unknownTicket = request(returned("E1", "1", "ORDER"));
        unknownTicket.ticketNumber = "C04-9";
        Assertions.assertEquals(404, service.record(unknownTicket, "{}").status());
        Ticket lane = ticket();
        lane.terminalId = "C04";
        doReturn(lane).when(service).ticket("C04-1");
        EcommerceReturnService.ReturnDto laneTicket = request(returned("E1", "1", "ORDER"));
        laneTicket.ticketNumber = "C04-1";
        laneTicket.orderReference = " ";
        Assertions.assertEquals(404, service.record(laneTicket, "{}").status());
        EcommerceReturnService.ReturnDto withoutOrder = request(returned("E1", "1", "ORDER"));
        withoutOrder.ticketNumber = " ";
        Assertions.assertEquals("Prix de la commande demandé sans commande d'origine : E1",
                service.record(withoutOrder, "{}").body());
        when(service.posSettingsService.returnWithoutOriginAllowed()).thenReturn(false);
        Assertions.assertEquals(400, service.record(request(returned("E1", "1", "CURRENT")), "{}").status());
        verify(service, never()).save(any());
    }

    /**
     * The lines must match the ticket: an article it does not hold, or a
     * quantity beyond what was sold and not yet returned, is refused; a line
     * named by its identity matches that line only.
     */
    @Test
    void theLinesMustMatchTheTicket() {
        EcommerceReturnService service = service();
        doReturn(ticket()).when(service).ticket("ECOM-W1");
        EcommerceReturnService.ReturnDto absent = request(returned("E9", "1", "ORDER"));
        absent.ticketNumber = "ECOM-W1";
        Assertions.assertEquals("Article absent de la commande ou déjà retourné : E9",
                service.record(absent, "{}").body());
        EcommerceReturnService.ReturnDto tooMany = request(returned("E1", "2", "ORDER"), returned("E1", "1", "ORDER"));
        tooMany.ticketNumber = "ECOM-W1";
        Assertions.assertEquals(400, service.record(tooMany, "{}").status());
        EcommerceReturnService.ReturnDto wrongUid = request(returned("E1", "1", "ORDER"));
        wrongUid.lines.get(0).lineUid = "u2";
        wrongUid.ticketNumber = "ECOM-W1";
        Assertions.assertEquals(200, service.record(wrongUid, "{}").status());
        EcommerceReturnService refunded = service();
        doReturn(ticket()).when(refunded).ticket("ECOM-W1");
        RefundLine before = new RefundLine();
        before.quantity = new BigDecimal("2");
        doReturn(List.of(before)).when(refunded).refundedLines(1L);
        EcommerceReturnService.ReturnDto again = request(returned("E1", "1", "ORDER"));
        again.lines.get(0).lineUid = " ";
        again.ticketNumber = "ECOM-W1";
        Assertions.assertEquals(400, refunded.record(again, "{}").status());
    }

    /**
     * BO-10-01-04, -05: the channel's ticket and article return types are
     * enforced, an empty list allowing every type.
     */
    @Test
    void theReturnTypeMustBeAllowed() {
        EcommerceReturnService service = service();
        when(service.posSettingsService.ecommerceReturnArticleTypes()).thenReturn("Retour colis; ;Casse");
        EcommerceReturnService.ReturnDto refused = request(returned("E1", "1", "CURRENT"));
        Assertions.assertEquals("Type de retour non autorisé : Retour web", service.record(refused, "{}").body());
        Assertions.assertTrue(service.typeAllowed(" casse ", "ARTICLE"));
        Assertions.assertFalse(service.typeAllowed(null, "ARTICLE"));
        Assertions.assertTrue(service.typeAllowed("Retour web", "TICKET"));
        when(service.posSettingsService.ecommerceReturnTicketTypes()).thenReturn("Retour total");
        Assertions.assertFalse(service.typeAllowed("Retour web", "TICKET"));
        EcommerceReturnService.ReturnDto noType = request(returned("E1", "1", "CURRENT"));
        noType.returnType = null;
        Assertions.assertEquals("Type de retour non autorisé : null", service.record(noType, "{}").body());
    }

    /**
     * The engine's refusal is answered: its own status below 500, 502 for an
     * engine failure or a status out of the success range, and an answer
     * holding fewer lines than asked is unreadable.
     */
    @Test
    void theEnginesRefusalIsAnswered() {
        EcommerceReturnService service = service();
        when(service.ecommerceBasketService.valueReturn(anyString(), any())).thenReturn(
                new EcommerceBasketService.Outcome(404, "unknown", null),
                new EcommerceBasketService.Outcome(503, "down", null),
                new EcommerceBasketService.Outcome(100, "", null),
                new EcommerceBasketService.Outcome(200, "not json", null),
                new EcommerceBasketService.Outcome(200, "{\"lines\":[]}", null));
        EcommerceReturnService.ReturnDto request = request(returned("E1", "1", "CURRENT"));
        EcommerceReturnService.Outcome notFound = service.record(request, "{}");
        Assertions.assertEquals(404, notFound.status());
        Assertions.assertEquals("Valorisation du retour refusée par le moteur (HTTP 404) : unknown", notFound.body());
        Assertions.assertEquals(502, service.record(request, "{}").status());
        Assertions.assertEquals(100, service.record(request, "{}").status());
        Assertions.assertEquals("Valorisation du retour illisible", service.record(request, "{}").body());
        Assertions.assertEquals(502, service.record(request, "{}").status());
        verify(service, never()).save(any());
    }

    /**
     * A report that cannot be written leaves the row without it, the return
     * still recorded; the archive off keeps no payload; a price pushed without
     * its rate and a blank date are not sent, no refund list reads as none.
     *
     * @throws Exception never
     */
    @Test
    void aReportThatCannotBeWrittenIsNotKept() throws Exception {
        EcommerceReturnService service = service();
        service.objectMapper = spy(new ObjectMapper());
        doThrow(new JsonProcessingException("boom") { }).when(service.objectMapper)
                .writeValueAsString(any(EcommerceReturnService.ReturnReport.class));
        when(service.posSettingsService.ecommerceArchive()).thenReturn(false);
        when(service.posSettingsService.ecommerceTerminal()).thenReturn("");
        when(service.ecommerceBasketService.valueReturn(anyString(), any())).thenReturn(new EcommerceBasketService.Outcome(200,
                "{\"lines\":[{}]}", null));
        EcommerceReturnService.ReturnDto request = request(returned("E1", "1", "CURRENT"));
        request.lines.get(0).unitPrice = new BigDecimal("2.76");
        request.lines.get(0).priceDate = " ";
        request.refunds = null;
        Assertions.assertEquals(200, service.record(request, "{}").status());
        ArgumentCaptor<EcommerceReturn> row = ArgumentCaptor.forClass(EcommerceReturn.class);
        verify(service).save(row.capture());
        Assertions.assertNull(row.getValue().report);
        Assertions.assertNull(row.getValue().originalPayload);
        Assertions.assertEquals(new BigDecimal("0.00"), row.getValue().totalAmount);
    }

    /**
     * A refund method is the tender's when it names one, CARD otherwise; a
     * number keeps its last thirty characters.
     */
    @Test
    void theRefundMethodAndNumberAreDerived() {
        Assertions.assertEquals("CASH", EcommerceReturnService.refundMethod("CASH"));
        Assertions.assertEquals("CARD", EcommerceReturnService.refundMethod("TR"));
        Assertions.assertEquals("ECOM-R-R1", EcommerceReturnService.number("ECOM-R-R1"));
        Assertions.assertEquals(30, EcommerceReturnService.number("ECOM-R-ABCDEFGHIJKLMNOPQRSTUVWXYZ").length());
    }

    /**
     * The original ticket line's label is used for the refund line, its EAN
     * when it has none.
     */
    @Test
    void theRefundLineTakesTheOriginalLabel() {
        EcommerceReturnService service = service();
        Ticket ticket = ticket();
        ticket.lines.get(0).productLabel = null;
        ticket.fidelityCard = null;
        doReturn(ticket).when(service).ticket("ECOM-W1");
        EcommerceReturnService.ReturnDto request = request(returned("E1", "1", "ORDER"), returned("E2", "1", "ORDER"));
        request.ticketNumber = "ECOM-W1";
        service.record(request, "{}");
        SyncPayloads.RefundDto refund = refund(service);
        Assertions.assertEquals("E1", refund.lines.get(0).productLabel);
        Assertions.assertEquals("Article E2", refund.lines.get(1).productLabel);
        Assertions.assertEquals(BigDecimal.ZERO, refund.lines.get(1).vatRate);
        verify(service.fidEventOutboxService, never()).enqueue(any(), any());
    }

    /**
     * The lookups go to the entities.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theLookupsGoToTheEntities() {
        EcommerceReturnService service = new EcommerceReturnService();
        Assertions.assertNull(service.ticket(null));
        Product milk = new Product();
        milk.name = "Lait";
        Product unnamed = new Product();
        try (MockedStatic<PanacheEntityBase> base = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null, null, null, milk, unnamed, null);
            base.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class))).thenReturn(query);
            base.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class))).thenReturn(new ArrayList<>());
            Assertions.assertNull(service.ticket("C04-1"));
            Assertions.assertNull(service.existing("R1"));
            Assertions.assertNull(service.integration("W1"));
            Assertions.assertEquals("Lait", service.label("E1"));
            Assertions.assertEquals("E2", service.label("E2"));
            Assertions.assertEquals("E3", service.label("E3"));
            Assertions.assertTrue(service.refundedLines(1L).isEmpty());
            Assertions.assertTrue(service.reasonLists().isEmpty());
        }
        EcommerceReturn row = mock(EcommerceReturn.class);
        service.save(row);
        verify(row).persist();
    }

    /**
     * DG-01-03: a return naming no booked order but sending the original
     * order's snapshot is valued by the engine from that snapshot, ORDER lines
     * included, the platform's line identity carried; it is booked as a
     * return ticket.
     *
     * @throws Exception never
     */
    @Test
    void aSnapshotWithoutBookedOrderIsValuedFromTheSnapshot() throws Exception {
        EcommerceReturnService service = service();
        when(service.ecommerceBasketService.valueReturn(anyString(), any())).thenReturn(new EcommerceBasketService.Outcome(200,
                "{\"lines\":[{\"amount\":-2.50},{\"amount\":-1.00}]}", null));
        EcommerceReturnService.ReturnDto request = request(returned("E1", "1", "ORDER"), returned("E2", "1", "ORDER"));
        request.lines.get(0).lineUid = " L1 ";
        request.lines.get(1).lineUid = " ";
        request.orderSnapshot = snapshot();
        EcommerceReturnService.ReturnReport report =
                (EcommerceReturnService.ReturnReport) service.record(request, "{}").body();
        JsonNode sent = valuation(service);
        Assertions.assertEquals("PARTIAL", sent.path("mode").asText());
        Assertions.assertEquals("L1", sent.path("lines").get(0).path("lineId").asText());
        Assertions.assertFalse(sent.path("lines").get(1).has("lineId"));
        Assertions.assertEquals(new BigDecimal("3.50"), report.total);
        verify(service.syncIngestService).ingestTicket(any());
        EcommerceReturnService.ReturnDto notAnObject = request(returned("E1", "1", "ORDER"));
        notAnObject.orderSnapshot = mapper.readTree("[1]");
        Assertions.assertEquals(400, service.record(notAnObject, "{}").status());
        EcommerceReturnService.ReturnDto current = request(returned("E1", "1", "CURRENT"));
        current.orderSnapshot = mapper.readTree("[1]");
        when(service.ecommerceBasketService.valueReturn(anyString(), any())).thenReturn(new EcommerceBasketService.Outcome(200,
                "{\"lines\":[{\"amount\":-2.76}]}", null));
        service.record(current, "{}");
        Assertions.assertFalse(mapper.readTree(valuationOf(service, 1)).has("snapshot"));
    }

    /**
     * The valuation request the service sent at a rank.
     *
     * @param service the service
     * @param rank the rank of the call
     * @return the request
     */
    private String valuationOf(EcommerceReturnService service, int rank) {
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(service.ecommerceBasketService, org.mockito.Mockito.atLeastOnce()).valueReturn(sent.capture(), eq(null));
        return sent.getAllValues().get(rank);
    }
}
