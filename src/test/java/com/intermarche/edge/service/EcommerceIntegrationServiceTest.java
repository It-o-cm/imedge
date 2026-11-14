package com.intermarche.edge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.catalog.Price;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.VatRate;
import com.intermarche.edge.domain.payment.TicketPayment;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.sale.EcommerceOrder;
import com.intermarche.edge.domain.store.CheckoutIsland;
import com.intermarche.edge.domain.store.CheckoutPoint;
import com.intermarche.edge.domain.store.Store;
import com.intermarche.edge.service.sync.SyncPayloads;
import com.intermarche.edge.service.sync.store.SyncIngestService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EcommerceIntegrationService}: an order is booked as a
 * ticket of the virtual register under every rule of the e-commerce channel —
 * activation, price source, forbidden and unknown articles, payment means,
 * island, archive — and answered with its integration report; a repeated
 * reference is answered again, never booked twice.
 *
 * <p>The service is a spy whose referential lookups and persistence are
 * stubbed per test; the lookups themselves are proven once against the
 * mocked Panache statics.
 */
class EcommerceIntegrationServiceTest {

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
     * A spied service over an open channel: no generic EAN, the global price
     * rule, forbidden articles accepted, CARD by default, operator admin,
     * register ECOM, no island, archive on; the encashment knows CARD and CASH;
     * the node knows the operator and the store, and no order yet.
     *
     * @return the service
     */
    @SuppressWarnings("unchecked")
    private EcommerceIntegrationService service() {
        EcommerceIntegrationService service = spy(new EcommerceIntegrationService());
        service.posSettingsService = mock(PosSettingsService.class);
        service.syncIngestService = mock(SyncIngestService.class);
        service.objectMapper = new ObjectMapper();
        service.paymentFactories = mock(Instance.class);
        service.clock = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneId.of("Europe/Paris"));
        List<TicketPayment.Factory> factories = List.of(factory("CARD"), factory("CASH"));
        when(service.paymentFactories.iterator()).thenAnswer(call -> factories.iterator());
        PosSettingsService s = service.posSettingsService;
        when(s.ecommerceEnabled()).thenReturn(true);
        when(s.ecommerceUnknownItemEan()).thenReturn("");
        when(s.ecommercePriceSource()).thenReturn("");
        when(s.ecommerceForbiddenItems()).thenReturn("ACCEPT");
        when(s.ecommerceDefaultTender()).thenReturn("card");
        when(s.ecommerceOperator()).thenReturn("admin");
        when(s.ecommerceTerminal()).thenReturn("ECOM");
        when(s.ecommerceIsland()).thenReturn("");
        when(s.ecommerceArchive()).thenReturn(true);
        doReturn(null).when(service).existing(any());
        doReturn(null).when(service).product(any());
        doReturn(true).when(service).operatorKnown(any());
        doReturn(true).when(service).storeKnown();
        doReturn(new CheckoutPoint()).when(service).point(any());
        doNothing().when(service).save(any());
        doNothing().when(service).savePoint(any());
        service.ecommerceFiscalService = mock(EcommerceFiscalService.class);
        service.fidEventOutboxService = mock(com.intermarche.edge.service.sync.register.FidEventOutboxService.class);
        doReturn(true).when(service).storeCodeKnown(any());
        service.ecommerceBasketService = mock(EcommerceBasketService.class);
        service.customerOrderService = mock(CustomerOrderService.class);
        return service;
    }

    /**
     * An order line.
     *
     * @param ean the EAN, or null
     * @param label the label, or null
     * @param quantity the quantity, or null
     * @param price the unit price, or null
     * @param vat the VAT rate, or null
     * @return the line
     */
    private EcommerceIntegrationService.OrderLineDto line(String ean, String label, String quantity,
                                                          String price, String vat) {
        EcommerceIntegrationService.OrderLineDto line = new EcommerceIntegrationService.OrderLineDto();
        line.ean = ean;
        line.label = label;
        line.quantity = quantity == null ? null : new BigDecimal(quantity);
        line.unitPrice = price == null ? null : new BigDecimal(price);
        line.vatRate = vat == null ? null : new BigDecimal(vat);
        return line;
    }

    /**
     * An order payment.
     *
     * @param method the platform's means, or null
     * @param amount the amount, or null
     * @return the payment
     */
    private EcommerceIntegrationService.OrderPaymentDto payment(String method, String amount) {
        EcommerceIntegrationService.OrderPaymentDto payment = new EcommerceIntegrationService.OrderPaymentDto();
        payment.method = method;
        payment.amount = amount == null ? null : new BigDecimal(amount);
        return payment;
    }

    /**
     * An order of reference W1 with the given lines.
     *
     * @param lines the lines
     * @return the order
     */
    private EcommerceIntegrationService.OrderDto order(EcommerceIntegrationService.OrderLineDto... lines) {
        EcommerceIntegrationService.OrderDto order = new EcommerceIntegrationService.OrderDto();
        order.reference = "W1";
        order.lines = new ArrayList<>(Arrays.asList(lines));
        return order;
    }

    /**
     * A product of the referential.
     *
     * @param id its id
     * @param forbidden whether it is forbidden to sale
     * @return the product
     */
    private Product product(long id, boolean forbidden) {
        Product product = new Product();
        product.id = id;
        product.forbiddenToSale = forbidden;
        return product;
    }

    /**
     * Captures the ticket the service booked.
     *
     * @param service the service
     * @return the booked ticket
     */
    private SyncPayloads.TicketDto booked(EcommerceIntegrationService service) {
        ArgumentCaptor<SyncPayloads.TicketDto> ticket = ArgumentCaptor.forClass(SyncPayloads.TicketDto.class);
        verify(service.syncIngestService).ingestTicket(ticket.capture());
        return ticket.getValue();
    }

    /**
     * Captures the order row the service kept.
     *
     * @param service the service
     * @return the row
     */
    private EcommerceOrder kept(EcommerceIntegrationService service) {
        ArgumentCaptor<EcommerceOrder> row = ArgumentCaptor.forClass(EcommerceOrder.class);
        verify(service).save(row.capture());
        return row.getValue();
    }

    /**
     * A channel the point of sale did not open answers DISABLED and keeps and
     * books nothing (BO-10-01-01).
     */
    @Test
    void aClosedChannelBooksNothing() {
        EcommerceIntegrationService service = service();
        when(service.posSettingsService.ecommerceEnabled()).thenReturn(false);
        EcommerceIntegrationService.Report report = service.integrate(order(line("1", "A", "1", "2", "0.2")), "{}");
        assertEquals("DISABLED", report.status);
        assertEquals("W1", report.reference);
        verify(service.syncIngestService, never()).ingestTicket(any());
        verify(service, never()).save(any());
    }

    /**
     * A known order is booked as a closed ticket of the virtual register, its
     * lines priced from the order, its VAT ventilated, paid as stated, archived
     * with its report; the report says INTEGRATED with the ticket and total
     * (BO-10-01-03, -11).
     */
    @Test
    void aValidOrderIsBookedAsATicketOfTheVirtualRegister() {
        EcommerceIntegrationService service = service();
        doReturn(product(1L, false)).when(service).product("3001");
        EcommerceIntegrationService.OrderDto order = order(line(" 3001 ", "LAIT", "2", "1.20", "0.055"));
        order.orderDate = "2026-10-24T09:00:00";
        order.customerNumber = "C9";
        order.customerEmail = "a@b.c";
        order.payments = List.of(payment("card", "2.40"));
        EcommerceIntegrationService.Report report = service.integrate(order, "{\"reference\":\"W1\"}");
        assertEquals("INTEGRATED", report.status);
        assertEquals("ECOM-W1", report.ticketNumber);
        assertEquals(new BigDecimal("2.40"), report.total);
        assertEquals("Commande intégrée", report.message);
        assertEquals("OK", report.lines.get(0).outcome);
        assertEquals("CARD", report.payments.get(0).integratedAs);
        SyncPayloads.TicketDto ticket = booked(service);
        assertEquals("ECOM", ticket.terminalId);
        assertEquals("CLOSED", ticket.status);
        assertEquals("2026-10-24T09:00:00", ticket.closingDate);
        assertEquals("admin", ticket.cashierLogin);
        assertEquals("C9", ticket.fidelityCard);
        assertEquals("a@b.c", ticket.customerEmail);
        assertEquals("NOT_VALUATED", ticket.valuationStatus);
        assertEquals(2, ticket.itemCount);
        assertEquals("3001", ticket.lines.get(0).ean);
        assertEquals(new BigDecimal("2.40"), ticket.lines.get(0).totalPrice);
        assertEquals(new BigDecimal("0.13"), ticket.totalVat);
        assertEquals(new BigDecimal("2.27"), ticket.totalExcludingTax);
        assertEquals("CARD", ticket.payments.get(0).methodKey);
        EcommerceOrder row = kept(service);
        assertEquals(EcommerceOrder.Status.INTEGRATED, row.status);
        assertEquals("ECOM-W1", row.ticketNumber);
        assertEquals("{\"reference\":\"W1\"}", row.originalPayload);
        assertTrue(row.report.contains("\"status\":\"INTEGRATED\""));
        assertEquals(java.time.LocalDateTime.of(2026, 10, 24, 12, 0), row.receivedAt);
    }

    /**
     * An unknown article never blocks the order: without a generic EAN it keeps
     * its code, with one it is carried on it, its label marked with its own
     * code, and the report counts it (BO-10-01-06).
     */
    @Test
    void anUnknownArticleIsCarriedOnTheGenericEanAndMarked() {
        EcommerceIntegrationService service = service();
        EcommerceIntegrationService.Report kept = service.integrate(order(line("777", "MYSTERE", null, null, null)), "{}");
        assertEquals("INTEGRATED", kept.status);
        assertEquals(1, kept.unknownLines);
        assertEquals("UNKNOWN", kept.lines.get(0).outcome);
        assertNull(kept.lines.get(0).carriedOn);
        SyncPayloads.TicketDto first = booked(service);
        assertEquals("777", first.lines.get(0).ean);
        assertEquals(BigDecimal.ONE, first.lines.get(0).quantity);
        assertEquals(new BigDecimal("0.00"), first.lines.get(0).totalPrice);
        EcommerceIntegrationService generic = service();
        when(generic.posSettingsService.ecommerceUnknownItemEan()).thenReturn("2999");
        EcommerceIntegrationService.Report report = generic.integrate(order(line("777", "MYSTERE", "1", "3.00", "0.2"),
                line(null, null, "1", "1.00", "0.2")), "{}");
        assertEquals(2, report.unknownLines);
        assertEquals("2999", report.lines.get(0).carriedOn);
        SyncPayloads.TicketDto ticket = booked(generic);
        assertEquals("2999", ticket.lines.get(0).ean);
        assertEquals("MYSTERE [ART. INCONNU 777]", ticket.lines.get(0).productLabel);
        assertEquals(" [ART. INCONNU ]", ticket.lines.get(1).productLabel);
        assertEquals(2, kept(generic).unknownLines);
    }

    /**
     * A forbidden article is accepted and reported under ACCEPT; under REJECT
     * the whole order is refused, nothing booked, the row kept REJECTED with its
     * report (BO-10-01-14).
     */
    @Test
    void aForbiddenArticleIsAcceptedOrRejectsTheOrder() {
        EcommerceIntegrationService accepting = service();
        doReturn(product(1L, true)).when(accepting).product("3001");
        EcommerceIntegrationService.Report accepted = accepting.integrate(order(line("3001", "ALCOOL", "1", "9", "0.2")), "{}");
        assertEquals("INTEGRATED", accepted.status);
        assertEquals("FORBIDDEN", accepted.lines.get(0).outcome);
        assertEquals(1, accepted.forbiddenLines);
        EcommerceIntegrationService rejecting = service();
        when(rejecting.posSettingsService.ecommerceForbiddenItems()).thenReturn("REJECT");
        when(rejecting.posSettingsService.ecommerceArchive()).thenReturn(false);
        doReturn(product(1L, true)).when(rejecting).product("3001");
        EcommerceIntegrationService.Report rejected = rejecting.integrate(order(line("3001", "ALCOOL", "1", "9", "0.2")), "{}");
        assertEquals("REJECTED", rejected.status);
        assertEquals("Article en vente interdite", rejected.message);
        verify(rejecting.syncIngestService, never()).ingestTicket(any());
        EcommerceOrder row = kept(rejecting);
        assertEquals(EcommerceOrder.Status.REJECTED, row.status);
        assertNull(row.ticketNumber);
        assertNull(row.originalPayload);
        assertEquals(1, row.forbiddenLines);
    }

    /**
     * REJECT with no forbidden article books the order.
     */
    @Test
    void aRejectRuleWithoutForbiddenArticleBooks() {
        EcommerceIntegrationService service = service();
        when(service.posSettingsService.ecommerceForbiddenItems()).thenReturn("REJECT");
        assertEquals("INTEGRATED", service.integrate(order(line("1", "A", "1", "1", "0.2")), "{}").status);
    }

    /**
     * POS takes the point of sale's price and rate — the order's rate kept when
     * the price names none, the order's price kept when the article has none;
     * TRANSACTION keeps the order's; an empty source follows the global rule
     * (BO-10-01-07).
     */
    @Test
    void thePriceSourceDecidesTheBookedPrice() {
        Price priced = new Price();
        priced.priceIncludingTax = new BigDecimal("1.50");
        priced.vat = new VatRate();
        priced.vat.rate = new BigDecimal("0.2");
        Price rateless = new Price();
        rateless.priceIncludingTax = new BigDecimal("0.80");
        EcommerceIntegrationService pos = service();
        when(pos.posSettingsService.ecommercePriceSource()).thenReturn("POS");
        Product known = product(1L, false);
        Product noRate = product(2L, false);
        Product noPrice = product(3L, false);
        doReturn(known).when(pos).product("1");
        doReturn(noRate).when(pos).product("2");
        doReturn(noPrice).when(pos).product("3");
        doReturn(priced).when(pos).price(known);
        doReturn(rateless).when(pos).price(noRate);
        doReturn(null).when(pos).price(noPrice);
        pos.integrate(order(line("1", "A", "1", "9.99", "0.055"), line("2", "B", "1", "9.99", "0.055"),
                line("3", "C", "1", "9.99", "0.055")), "{}");
        SyncPayloads.TicketDto ticket = booked(pos);
        assertEquals(new BigDecimal("1.50"), ticket.lines.get(0).unitPrice);
        assertEquals(new BigDecimal("0.2"), ticket.lines.get(0).vatRate);
        assertEquals(new BigDecimal("0.80"), ticket.lines.get(1).unitPrice);
        assertEquals(new BigDecimal("0.055"), ticket.lines.get(1).vatRate);
        assertEquals(new BigDecimal("9.99"), ticket.lines.get(2).unitPrice);
        EcommerceIntegrationService transaction = service();
        when(transaction.posSettingsService.ecommercePriceSource()).thenReturn("TRANSACTION");
        when(transaction.posSettingsService.orderRecallPriceFromRegister()).thenReturn(true);
        doReturn(known).when(transaction).product("1");
        transaction.integrate(order(line("1", "A", "1", "9.99", "0.055")), "{}");
        assertEquals(new BigDecimal("9.99"), booked(transaction).lines.get(0).unitPrice);
        verify(transaction, never()).price(any());
        EcommerceIntegrationService global = service();
        when(global.posSettingsService.orderRecallPriceFromRegister()).thenReturn(true);
        doReturn(known).when(global).product("1");
        doReturn(priced).when(global).price(known);
        global.integrate(order(line("1", "A", "1", "9.99", "0.055")), "{}");
        assertEquals(new BigDecimal("1.50"), booked(global).lines.get(0).unitPrice);
    }

    /**
     * A means the encashment does not know is booked under the default tender,
     * the empty and non-positive payments are skipped, and an order stating
     * none is paid in full under the default tender (BO-10-01-09).
     */
    @Test
    void anUnknownMeansIsBookedUnderTheDefaultTender() {
        EcommerceIntegrationService service = service();
        EcommerceIntegrationService.OrderDto order = order(line("1", "A", "1", "10.00", "0.2"));
        order.payments = Arrays.asList(payment("PAYPAL", "6.00"), null, payment("CASH", null),
                payment("CASH", "0"), payment(" cash ", "4.00"));
        EcommerceIntegrationService.Report report = service.integrate(order, "{}");
        assertEquals("PAYPAL", report.payments.get(0).method);
        assertEquals("CARD", report.payments.get(0).integratedAs);
        assertEquals("CASH", report.payments.get(1).integratedAs);
        SyncPayloads.TicketDto ticket = booked(service);
        assertEquals(2, ticket.payments.size());
        assertEquals(1, ticket.payments.get(1).paymentIndex);
        EcommerceIntegrationService none = service();
        none.integrate(order(line("1", "A", "1", "10.00", "0.2")), "{}");
        SyncPayloads.TicketDto whole = booked(none);
        assertEquals(new BigDecimal("10.00"), whole.payments.get(0).amount);
        assertEquals("CARD", whole.payments.get(0).methodKey);
        EcommerceIntegrationService nameless = service();
        EcommerceIntegrationService.OrderDto unnamed = order(line("1", "A", "1", "10.00", "0.2"));
        unnamed.payments = List.of(payment(null, "10.00"));
        assertEquals("", nameless.integrate(unnamed, "{}").payments.get(0).method);
    }

    /**
     * A default tender the encashment does not know either rejects the order.
     */
    @Test
    void anUnknownDefaultTenderRejects() {
        EcommerceIntegrationService service = service();
        when(service.posSettingsService.ecommerceDefaultTender()).thenReturn("BITCOIN");
        EcommerceIntegrationService.OrderDto order = order(line("1", "A", "1", "10.00", "0.2"));
        order.payments = List.of(payment("PAYPAL", "10.00"));
        EcommerceIntegrationService.Report report = service.integrate(order, "{}");
        assertEquals("REJECTED", report.status);
        assertEquals("Moyen de paiement par défaut inconnu de l'encaissement", report.message);
    }

    /**
     * An operator or a store the node does not know rejects the order, with its
     * reason (BO-10-01-03).
     */
    @Test
    void anUnknownOperatorOrStoreRejects() {
        EcommerceIntegrationService operator = service();
        doReturn(false).when(operator).operatorKnown("admin");
        assertEquals("Opérateur d'intégration inconnu",
                operator.integrate(order(line("1", "A", "1", "1", "0.2")), "{}").message);
        EcommerceIntegrationService store = service();
        doReturn(false).when(store).storeKnown();
        assertEquals("Aucun point de vente sur le nœud magasin",
                store.integrate(order(line("1", "A", "1", "1", "0.2")), "{}").message);
    }

    /**
     * A reference already received is answered with the report it got, marked
     * as repeated, and nothing is booked again; an unreadable report is rebuilt
     * from the row.
     */
    @Test
    void aRepeatedReferenceIsAnsweredAgain() {
        EcommerceIntegrationService service = service();
        EcommerceOrder known = new EcommerceOrder();
        known.reference = "W1";
        known.status = EcommerceOrder.Status.INTEGRATED;
        known.ticketNumber = "ECOM-W1";
        known.totalAmount = BigDecimal.TEN;
        known.message = "Commande intégrée";
        known.report = "{\"reference\":\"W1\",\"status\":\"INTEGRATED\",\"ticketNumber\":\"ECOM-W1\"}";
        doReturn(known).when(service).existing("W1");
        EcommerceIntegrationService.Report report = service.integrate(order(line("1", "A", "1", "1", "0.2")), "{}");
        assertTrue(report.alreadyReceived);
        assertEquals("ECOM-W1", report.ticketNumber);
        verify(service.syncIngestService, never()).ingestTicket(any());
        known.report = null;
        EcommerceIntegrationService.Report rebuilt = service.reportOf("W1");
        assertEquals("INTEGRATED", rebuilt.status);
        assertEquals(BigDecimal.TEN, rebuilt.total);
        assertEquals("Commande intégrée", rebuilt.message);
        assertFalse(rebuilt.alreadyReceived);
        assertNull(service.reportOf("W2"));
    }

    /**
     * A report that cannot be written leaves the row without one, the order
     * still booked.
     *
     * @throws Exception never, the mapper being a mock
     */
    @Test
    void anUnwritableReportLeavesTheRowWithoutOne() throws Exception {
        EcommerceIntegrationService service = service();
        service.objectMapper = mock(ObjectMapper.class);
        when(service.objectMapper.writeValueAsString(any())).thenThrow(mock(JsonProcessingException.class));
        assertEquals("INTEGRATED", service.integrate(order(line("1", "A", "1", "1", "0.2")), "{}").status);
        assertNull(kept(service).report);
    }

    /**
     * The virtual register is declared the first time, attached to the
     * administered island when the node knows it; a known register is moved to
     * the island, left alone without one; an unknown island is reported and not
     * attached; an empty terminal reads as ECOM; an undated order is dated now;
     * a ticket number too long keeps its end (BO-10-01-08).
     */
    @Test
    void theVirtualRegisterIsAttachedToTheIsland() {
        EcommerceIntegrationService fresh = service();
        when(fresh.posSettingsService.ecommerceIsland()).thenReturn("WEB");
        doReturn(true).when(fresh).islandKnown("WEB");
        doReturn(null).when(fresh).point("ECOM");
        fresh.integrate(order(line("1", "A", "1", "1", "0.2")), "{}");
        ArgumentCaptor<CheckoutPoint> point = ArgumentCaptor.forClass(CheckoutPoint.class);
        verify(fresh).savePoint(point.capture());
        assertEquals("ECOM", point.getValue().terminalId);
        assertEquals("WEB", point.getValue().islandCode);
        assertEquals("2026-10-24T12:00", booked(fresh).closingDate);
        EcommerceIntegrationService moved = service();
        when(moved.posSettingsService.ecommerceIsland()).thenReturn("WEB");
        doReturn(true).when(moved).islandKnown("WEB");
        CheckoutPoint existing = new CheckoutPoint();
        existing.islandCode = "AVANT";
        doReturn(existing).when(moved).point("ECOM");
        moved.integrate(order(line("1", "A", "1", "1", "0.2")), "{}");
        assertEquals("WEB", existing.islandCode);
        verify(moved, never()).savePoint(any());
        EcommerceIntegrationService unknown = service();
        when(unknown.posSettingsService.ecommerceIsland()).thenReturn("NULLEPART");
        when(unknown.posSettingsService.ecommerceTerminal()).thenReturn("");
        doReturn(false).when(unknown).islandKnown("NULLEPART");
        CheckoutPoint kept = new CheckoutPoint();
        kept.islandCode = "AVANT";
        doReturn(kept).when(unknown).point("ECOM");
        EcommerceIntegrationService.OrderDto undated = order(line("1", "A", "1", "1", "0.2"));
        undated.orderDate = " ";
        EcommerceIntegrationService.Report report = unknown.integrate(undated, "{}");
        assertEquals(List.of("Îlot inconnu : NULLEPART"), report.warnings);
        assertEquals("AVANT", kept.islandCode);
        assertEquals("ECOM-W1", booked(unknown).ticketNumber);
        assertEquals("2026-10-24T12:00", booked(unknown).creationDate);
        EcommerceIntegrationService longer = service();
        when(longer.posSettingsService.ecommerceTerminal()).thenReturn("ECOMMERCE01");
        EcommerceIntegrationService.OrderDto longOrder = order(line("1", "A", "1", "1", "0.2"));
        longOrder.reference = "ABCDEFGHIJKLMNOPQRSTUVWX";
        longer.integrate(longOrder, "{}");
        assertEquals("RCE01-ABCDEFGHIJKLMNOPQRSTUVWX", booked(longer).ticketNumber);
    }

    /**
     * The lookups read the referential and the persistence writes through
     * Panache.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theLookupsReadTheReferential() {
        EcommerceIntegrationService service = new EcommerceIntegrationService();
        Product product = product(1L, false);
        Price price = new Price();
        EcommerceOrder order = new EcommerceOrder();
        CheckoutPoint point = new CheckoutPoint();
        PanacheQuery<Object> found = mock(PanacheQuery.class);
        when(found.firstResult()).thenReturn(new Object());
        PanacheQuery<Object> empty = mock(PanacheQuery.class);
        try (MockedStatic<EcommerceOrder> orders = mockStatic(EcommerceOrder.class);
             MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class);
             MockedStatic<CheckoutIsland> islands = mockStatic(CheckoutIsland.class);
             MockedStatic<CheckoutPoint> points = mockStatic(CheckoutPoint.class);
             MockedStatic<PanacheEntityBase> base = mockStatic(PanacheEntityBase.class)) {
            orders.when(() -> EcommerceOrder.findByReference("W1")).thenReturn(order);
            products.when(() -> Product.findByEan("1")).thenReturn(product);
            prices.when(() -> Price.findCurrentPrice(1L)).thenReturn(price);
            islands.when(() -> CheckoutIsland.findByCode("WEB")).thenReturn(new CheckoutIsland());
            points.when(() -> CheckoutPoint.findByTerminal("ECOM")).thenReturn(point);
            base.when(() -> Employee.find("loginName", "admin")).thenReturn(found);
            base.when(() -> Employee.find("loginName", "ghost")).thenReturn(empty);
            base.when(Store::findAll).thenReturn(found);
            assertSame(order, service.existing("W1"));
            assertSame(product, service.product("1"));
            assertSame(price, service.price(product));
            assertTrue(service.islandKnown("WEB"));
            assertFalse(service.islandKnown("NONE"));
            assertSame(point, service.point("ECOM"));
            assertTrue(service.operatorKnown("ADMIN"));
            assertFalse(service.operatorKnown("ghost"));
            assertFalse(service.operatorKnown(""));
            assertTrue(service.storeKnown());
            base.when(Store::findAll).thenReturn(empty);
            assertFalse(service.storeKnown());
            base.when(() -> Store.find("code", "07039")).thenReturn(found);
            base.when(() -> Store.find("code", "99999")).thenReturn(empty);
            assertTrue(service.storeCodeKnown("07039"));
            assertFalse(service.storeCodeKnown("99999"));
            PanacheQuery<Object> system = mock(PanacheQuery.class);
            com.intermarche.edge.domain.sale.ThirdPartySystem web = new com.intermarche.edge.domain.sale.ThirdPartySystem();
            when(system.firstResult()).thenReturn(web);
            base.when(() -> PanacheEntityBase.find("code", "WEB")).thenReturn(system);
            assertSame(web, service.system("WEB"));
        }
        CheckoutPoint saved = mock(CheckoutPoint.class);
        service.savePoint(saved);
        verify(saved).persist();
        EcommerceOrder row = mock(EcommerceOrder.class);
        service.save(row);
        verify(row).persist();
    }

    /**
     * BO-10-01-04 / -05: an e-commerce ticket is served to the register a
     * customer brings it back to, its lines as booked; a ticket of another
     * register, or none, is not served; an undated ticket states no date.
     */
    @Test
    void anEcommerceTicketIsServedToTheRegister() {
        EcommerceIntegrationService service = service();
        com.intermarche.edge.domain.sale.Ticket ticket = new com.intermarche.edge.domain.sale.Ticket();
        ticket.ticketNumber = "ECOM-W1";
        ticket.terminalId = "ECOM";
        ticket.creationDate = java.time.LocalDateTime.of(2026, 10, 24, 9, 0);
        ticket.closingDate = java.time.LocalDateTime.of(2026, 10, 24, 9, 5);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.customerTaxId = "999999990";
        com.intermarche.edge.domain.sale.TicketLine line = new com.intermarche.edge.domain.sale.TicketLine();
        line.lineNumber = 1;
        line.lineUid = "U1";
        line.ean = "3001";
        line.productLabel = "LAIT";
        line.quantity = new BigDecimal("2");
        line.unitPrice = new BigDecimal("6.00");
        line.vatRate = new BigDecimal("0.2");
        line.totalPrice = new BigDecimal("12.00");
        ticket.lines.add(line);
        com.intermarche.edge.domain.sale.Ticket other = new com.intermarche.edge.domain.sale.Ticket();
        other.terminalId = "C04";
        doReturn(ticket).when(service).bookedTicket("ECOM-W1");
        doReturn(other).when(service).bookedTicket("C04-1");
        doReturn(null).when(service).bookedTicket("NONE");
        EcommerceIntegrationService.TicketView view = service.ticketOf("ECOM-W1");
        assertEquals("ECOM-W1", view.ticketNumber);
        assertEquals("2026-10-24T09:05", view.closingDate);
        assertEquals("2026-10-24T09:00", view.creationDate);
        assertEquals(new BigDecimal("12.00"), view.totalIncludingTax);
        assertEquals("999999990", view.customerTaxId);
        assertEquals("LAIT", view.lines.get(0).label);
        assertEquals("U1", view.lines.get(0).lineUid);
        assertEquals(new BigDecimal("6.00"), view.lines.get(0).unitPrice);
        assertNull(service.ticketOf("C04-1"));
        assertNull(service.ticketOf("NONE"));
        ticket.creationDate = null;
        ticket.closingDate = null;
        assertNull(service.ticketOf("ECOM-W1").closingDate);
        assertNull(service.ticketOf("ECOM-W1").creationDate);
    }

    /**
     * The booked ticket is looked up by its trimmed number, a null number
     * naming none.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theBookedTicketIsLookedUpByNumber() {
        EcommerceIntegrationService service = new EcommerceIntegrationService();
        com.intermarche.edge.domain.sale.Ticket ticket = new com.intermarche.edge.domain.sale.Ticket();
        PanacheQuery<Object> found = mock(PanacheQuery.class);
        when(found.firstResult()).thenReturn(ticket);
        try (MockedStatic<PanacheEntityBase> base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> com.intermarche.edge.domain.sale.Ticket.find("ticketNumber", "ECOM-W1")).thenReturn(found);
            assertSame(ticket, service.bookedTicket(" ECOM-W1 "));
            assertNull(service.bookedTicket(null));
        }
    }

    /**
     * A declared third-party system.
     *
     * @param channel its channel
     * @param active whether it may deposit
     * @return the system, code WEB
     */
    private com.intermarche.edge.domain.sale.ThirdPartySystem system(com.intermarche.edge.domain.sale.SalesChannel channel,
                                                                    boolean active) {
        com.intermarche.edge.domain.sale.ThirdPartySystem system = new com.intermarche.edge.domain.sale.ThirdPartySystem();
        system.code = "WEB";
        system.channel = channel;
        system.active = active;
        return system;
    }

    /**
     * BO-07-01-01: an order deposited by a declared system is booked under the
     * system's channel and virtual register, for its point of sale, and the
     * system's counters move; the report and the row name the system.
     */
    @Test
    void aDeclaredSystemBooksUnderItsChannel() {
        EcommerceIntegrationService service = service();
        com.intermarche.edge.domain.sale.ThirdPartySystem mobility =
                system(com.intermarche.edge.domain.sale.SalesChannel.MOBILITY, true);
        mobility.terminalId = " MOB1 ";
        mobility.pdvNumber = " 07039 ";
        doReturn(mobility).when(service).system("WEB");
        doReturn(null).when(service).point("MOB1");
        EcommerceIntegrationService.Report report = service.integrate(order(line("1", "A", "1", "2", "0.2")), "{}", "WEB");
        assertEquals("INTEGRATED", report.status);
        assertEquals("MOBILITY", report.channel);
        assertEquals("WEB", report.systemCode);
        assertEquals("07039", report.pdvNumber);
        assertFalse(report.fidelityQueued);
        SyncPayloads.TicketDto ticket = booked(service);
        assertEquals("MOB1", ticket.terminalId);
        assertEquals("MOBILITY", ticket.salesChannel);
        assertEquals("07039", ticket.storeCode);
        assertEquals(1L, mobility.orderCount);
        assertEquals(java.time.LocalDateTime.of(2026, 10, 24, 12, 0), mobility.lastCallAt);
        ArgumentCaptor<CheckoutPoint> point = ArgumentCaptor.forClass(CheckoutPoint.class);
        verify(service).savePoint(point.capture());
        assertEquals("Caisse virtuelle mobilité", point.getValue().label);
        assertNull(point.getValue().islandCode);
        EcommerceOrder row = kept(service);
        assertEquals(com.intermarche.edge.domain.sale.SalesChannel.MOBILITY, row.channel);
        assertEquals("WEB", row.systemCode);
        assertEquals("07039", row.pdvNumber);
        verify(service.posSettingsService, never()).ecommerceIsland();
    }

    /**
     * BO-07-01-01: the integration activity closed answers DISABLED — a
     * deactivated system, the e-commerce channel closed even for a declared
     * system, a mobility sale no declared system deposited; an unknown system
     * code integrates as the shared token does.
     */
    @Test
    void theIntegrationActivityMayBeClosed() {
        EcommerceIntegrationService service = service();
        doReturn(system(com.intermarche.edge.domain.sale.SalesChannel.ECOMMERCE, false)).when(service).system("OFF");
        EcommerceIntegrationService.Report off = service.integrate(order(line("1", "A", "1", "1", "0.2")), "{}", "OFF");
        assertEquals("DISABLED", off.status);
        assertEquals("Système tiers désactivé", off.message);
        doReturn(system(com.intermarche.edge.domain.sale.SalesChannel.ECOMMERCE, true)).when(service).system("ON");
        when(service.posSettingsService.ecommerceEnabled()).thenReturn(false);
        assertEquals("E-commerce non activé sur ce point de vente",
                service.integrate(order(line("1", "A", "1", "1", "0.2")), "{}", "ON").message);
        EcommerceIntegrationService.OrderDto mobile = order(line("1", "A", "1", "1", "0.2"));
        mobile.channel = "mobility";
        EcommerceIntegrationService.Report orphan = service.integrate(mobile, "{}", null);
        assertEquals("Aucun système tiers de mobilité déclaré", orphan.message);
        assertEquals("MOBILITY", orphan.channel);
        when(service.posSettingsService.ecommerceEnabled()).thenReturn(true);
        doReturn(null).when(service).system("GHOST");
        EcommerceIntegrationService.Report ghost = service.integrate(order(line("1", "A", "1", "1", "0.2")), "{}", "GHOST");
        assertEquals("INTEGRATED", ghost.status);
        assertNull(ghost.systemCode);
        verify(service, never()).save(argThatRejected());
    }

    /**
     * Matches nothing: the closed activity keeps no row.
     *
     * @return a matcher of a rejected row
     */
    private EcommerceOrder argThatRejected() {
        return org.mockito.ArgumentMatchers.argThat(row -> row != null && row.status == EcommerceOrder.Status.REJECTED);
    }

    /**
     * BO-07-01-01: the channel is the system's, else the order's, the store's
     * own lanes and an unknown name reading as the e-commerce channel.
     */
    @Test
    void theChannelOfAnOrder() {
        EcommerceIntegrationService.OrderDto order = order();
        assertEquals(com.intermarche.edge.domain.sale.SalesChannel.ECOMMERCE, EcommerceIntegrationService.channel(order, null));
        order.channel = "STORE";
        assertEquals(com.intermarche.edge.domain.sale.SalesChannel.ECOMMERCE, EcommerceIntegrationService.channel(order, null));
        order.channel = "MOBILITY";
        assertEquals(com.intermarche.edge.domain.sale.SalesChannel.MOBILITY, EcommerceIntegrationService.channel(order, null));
        assertEquals(com.intermarche.edge.domain.sale.SalesChannel.ECOMMERCE, EcommerceIntegrationService.channel(order,
                system(com.intermarche.edge.domain.sale.SalesChannel.ECOMMERCE, true)));
    }

    /**
     * BO-07-01-02: the point of sale of origin is the order's, else its
     * system's, else none — a blank one naming none.
     */
    @Test
    void thePointOfSaleOfOrigin() {
        EcommerceIntegrationService.OrderDto order = order();
        com.intermarche.edge.domain.sale.ThirdPartySystem web =
                system(com.intermarche.edge.domain.sale.SalesChannel.ECOMMERCE, true);
        assertNull(EcommerceIntegrationService.pdv(order, null));
        assertNull(EcommerceIntegrationService.pdv(order, web));
        web.pdvNumber = " ";
        assertNull(EcommerceIntegrationService.pdv(order, web));
        web.pdvNumber = "07040";
        assertEquals("07040", EcommerceIntegrationService.pdv(order, web));
        order.pdvNumber = " ";
        assertEquals("07040", EcommerceIntegrationService.pdv(order, web));
        order.pdvNumber = " 07039 ";
        assertEquals("07039", EcommerceIntegrationService.pdv(order, web));
    }

    /**
     * BO-07-01-02: an order for a point of sale the node does not hold is
     * refused, nothing booked; one it holds books under its code.
     */
    @Test
    void anUnknownPointOfSaleOfOriginRejects() {
        EcommerceIntegrationService service = service();
        doReturn(false).when(service).storeCodeKnown("99999");
        EcommerceIntegrationService.OrderDto order = order(line("1", "A", "1", "1", "0.2"));
        order.pdvNumber = "99999";
        EcommerceIntegrationService.Report report = service.integrate(order, "{}");
        assertEquals("REJECTED", report.status);
        assertEquals("Point de vente d'origine inconnu du nœud magasin", report.message);
        verify(service.syncIngestService, never()).ingestTicket(any());
    }

    /**
     * BO-07-01-07, -08: the NIF sent is kept, without spaces and upper-cased;
     * none sent takes the store's default NIF, the final consumer's when the
     * store administers none; a malformed Portuguese NIF the store checks
     * refuses the order.
     */
    @Test
    void theCustomerNifIsStatedOrDefaulted() {
        EcommerceIntegrationService service = service();
        EcommerceIntegrationService.OrderDto sent = order(line("1", "A", "1", "1", "0.2"));
        sent.customerTaxId = " pt 123 456 789 ";
        EcommerceIntegrationService.Report kept = service.integrate(sent, "{}");
        assertEquals("PT123456789", kept.customerTaxId);
        assertFalse(kept.customerTaxIdDefaulted);
        assertEquals("PT123456789", booked(service).customerTaxId);
        assertEquals("PT123456789", kept(service).customerTaxId);
        EcommerceIntegrationService.Report none = service().integrate(order(line("1", "A", "1", "1", "0.2")), "{}");
        assertEquals("999999990", none.customerTaxId);
        assertTrue(none.customerTaxIdDefaulted);
        EcommerceIntegrationService blankDefault = service();
        when(blankDefault.posSettingsService.defaultTaxId()).thenReturn(" ");
        EcommerceIntegrationService.OrderDto empty = order(line("1", "A", "1", "1", "0.2"));
        empty.customerTaxId = " ";
        assertEquals("999999990", blankDefault.integrate(empty, "{}").customerTaxId);
        EcommerceIntegrationService administered = service();
        when(administered.posSettingsService.defaultTaxId()).thenReturn(" 999999990 ");
        assertEquals("999999990", administered.integrate(order(line("1", "A", "1", "1", "0.2")), "{}").customerTaxId);
        EcommerceIntegrationService checking = service();
        when(checking.posSettingsService.taxIdPortugueseCheck()).thenReturn(true);
        EcommerceIntegrationService.OrderDto wrong = order(line("1", "A", "1", "1", "0.2"));
        wrong.customerTaxId = "123456788";
        EcommerceIntegrationService.Report refused = checking.integrate(wrong, "{}");
        assertEquals("REJECTED", refused.status);
        assertEquals("NIF client invalide", refused.message);
        EcommerceIntegrationService.OrderDto right = order(line("1", "A", "1", "1", "0.2"));
        right.reference = "W2";
        right.customerTaxId = "123456789";
        assertEquals("INTEGRATED", checking.integrate(right, "{}").status);
    }

    /**
     * BO-07-01-06: where the store issues the Portuguese documents, the order
     * is stated on the document the fiscal service gives, pushed with its
     * ticket and named in the report; a document that cannot be stated is a
     * warning and the order is booked all the same.
     */
    @Test
    void theOrderIsStatedOnItsFiscalDocument() {
        EcommerceIntegrationService service = service();
        when(service.posSettingsService.ptDocuments()).thenReturn(true);
        SyncPayloads.FiscalDocumentDto document = new SyncPayloads.FiscalDocumentDto();
        document.number = "FS ECOM2026/1";
        when(service.ecommerceFiscalService.document(any(), any())).thenReturn(document);
        EcommerceIntegrationService.OrderDto order = order(line("1", "A", "1", "1", "0.2"));
        order.invoice = new EcommerceFiscalService.InvoiceDto();
        EcommerceIntegrationService.Report report = service.integrate(order, "{}");
        assertEquals("FS ECOM2026/1", report.fiscalDocument);
        assertSame(document, booked(service).fiscalDocuments.get(0));
        assertEquals("FS ECOM2026/1", kept(service).fiscalDocument);
        verify(service.ecommerceFiscalService).document(org.mockito.ArgumentMatchers.same(order.invoice), any());
        EcommerceIntegrationService failing = service();
        when(failing.posSettingsService.ptDocuments()).thenReturn(true);
        when(failing.ecommerceFiscalService.document(any(), any())).thenThrow(new IllegalStateException("clé"));
        EcommerceIntegrationService.Report warned = failing.integrate(order(line("1", "A", "1", "1", "0.2")), "{}");
        assertEquals("INTEGRATED", warned.status);
        assertEquals(List.of("Document fiscal non émis"), warned.warnings);
        assertNull(warned.fiscalDocument);
    }

    /**
     * BO-07-01-03: an e-commerce order carrying a card queues its loyalty
     * earn — the ticket-closed event of the register, the booked basket on
     * the Web channel; none without a card, with a blank card, with the earn
     * switched off; an event that cannot be queued is a warning.
     */
    @Test
    void aCardedOrderQueuesItsLoyaltyEarn() throws Exception {
        EcommerceIntegrationService service = service();
        EcommerceIntegrationService.OrderDto order = order(line("3001", "LAIT", "2", "1.20", "0.2"));
        order.customerNumber = " 4242 ";
        order.orderDate = "2026-10-24T09:00:00";
        order.pdvNumber = "07039";
        when(service.posSettingsService.ecommerceFidelityEarn()).thenReturn(true);
        EcommerceIntegrationService.Report report = service.integrate(order, "{}");
        assertTrue(report.fidelityQueued);
        assertTrue(kept(service).fidelityQueued);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(service.fidEventOutboxService).enqueue(
                org.mockito.ArgumentMatchers.eq(com.intermarche.edge.domain.sync.FidEvent.EventType.TICKET_CLOSED),
                payload.capture());
        com.fasterxml.jackson.databind.JsonNode event = new ObjectMapper().readTree(payload.getValue());
        assertEquals("2026-ECOM-W1", event.get("ticketRef").asText());
        assertEquals("4242", event.get("card").asText());
        assertEquals("2026-10-24", event.get("fiscalDate").asText());
        assertEquals("WEB", event.get("channel").asText());
        assertEquals("07039", event.get("valuationRequest").get("storeCode").asText());
        assertEquals("3001", event.get("valuationRequest").get("items").get(0).get("produceEan").asText());
        assertEquals(2.4, event.get("valuationResponse").get("totalPrice").get("amountIncludingTax").asDouble());
        EcommerceIntegrationService.OrderDto blank = order(line("1", "A", "1", "1", "0.2"));
        blank.customerNumber = " ";
        EcommerceIntegrationService noCard = service();
        when(noCard.posSettingsService.ecommerceFidelityEarn()).thenReturn(true);
        assertFalse(noCard.integrate(blank, "{}").fidelityQueued);
        assertFalse(noCard.integrate(order(line("1", "A", "1", "1", "0.2")), "{}").fidelityQueued);
        EcommerceIntegrationService off = service();
        EcommerceIntegrationService.OrderDto carded = order(line("1", "A", "1", "1", "0.2"));
        carded.customerNumber = "4242";
        assertFalse(off.integrate(carded, "{}").fidelityQueued);
        verify(off.fidEventOutboxService, never()).enqueue(any(), any());
        EcommerceIntegrationService failing = service();
        when(failing.posSettingsService.ecommerceFidelityEarn()).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("base")).when(failing.fidEventOutboxService).enqueue(any(), any());
        EcommerceIntegrationService.Report warned = failing.integrate(carded, "{}");
        assertFalse(warned.fidelityQueued);
        assertEquals(List.of("Cagnottage fidélité impossible"), warned.warnings);
    }

    /**
     * BO-07-01-03: the event's channel is the administered e-commerce
     * promotion channel, Web when none is administered.
     */
    @Test
    void theLoyaltyEventTakesTheAdministeredChannel() {
        EcommerceIntegrationService service = service();
        SyncPayloads.TicketDto ticket = new SyncPayloads.TicketDto();
        ticket.fidelityCard = "4242";
        ticket.closingDate = "2026-10-24T09:00";
        ticket.ticketNumber = "ECOM-W1";
        assertEquals("WEB", service.fidelityEvent(ticket, null).get("channel"));
        when(service.posSettingsService.ecommercePromotionChannel()).thenReturn(" ");
        assertEquals("WEB", service.fidelityEvent(ticket, null).get("channel"));
        when(service.posSettingsService.ecommercePromotionChannel()).thenReturn("OTHER");
        assertEquals("OTHER", service.fidelityEvent(ticket, null).get("channel"));
    }

    /**
     * BO-07-01-04: the platform's means rides each settlement, none when the
     * order stated none.
     */
    @Test
    void theMeansRidesTheSettlement() {
        EcommerceIntegrationService service = service();
        EcommerceIntegrationService.OrderDto order = order(line("1", "A", "1", "5", "0.2"));
        order.payments = List.of(payment("paypal", "5"));
        service.integrate(order, "{}");
        assertEquals("PAYPAL", booked(service).payments.get(0).tenderCode);
        assertEquals("CARD", booked(service).payments.get(0).methodKey);
        EcommerceIntegrationService unstated = service();
        unstated.integrate(order(line("1", "A", "1", "5", "0.2")), "{}");
        assertNull(booked(unstated).payments.get(0).tenderCode);
    }

    /**
     * BO-07-01-01: a system's blank virtual register falls back on the
     * channel's — the administered e-commerce one, MOBI for mobility.
     */
    @Test
    void aBlankSystemRegisterFallsBackOnTheChannel() {
        EcommerceIntegrationService service = service();
        com.intermarche.edge.domain.sale.ThirdPartySystem web =
                system(com.intermarche.edge.domain.sale.SalesChannel.ECOMMERCE, true);
        web.terminalId = " ";
        doReturn(web).when(service).system("WEB");
        service.integrate(order(line("1", "A", "1", "1", "0.2")), "{}", "WEB");
        assertEquals("ECOM", booked(service).terminalId);
        EcommerceIntegrationService mobile = service();
        doReturn(system(com.intermarche.edge.domain.sale.SalesChannel.MOBILITY, true)).when(mobile).system("WEB");
        mobile.integrate(order(line("1", "A", "1", "1", "0.2")), "{}", "WEB");
        assertEquals("MOBI", booked(mobile).terminalId);
    }

    /** The engine's evaluation of the final basket: milk 2 x 2.76, rice 1.80, wine 3.00, 0.50 off. */
    private static final String EVALUATION = """
            {"offers":[{"offerId":"BASE","items":[
                {"lineId":"L1","label":"Lait","amount":{"amountIncludingTax":5.52,"vatRate":0.20}},
                {"lineId":"L2","label":"Riz","amount":{"amountIncludingTax":1.80,"vatRate":0.055}},
                {"lineId":"L3","label":"Vin","amount":{"amountIncludingTax":3.00,"vatRate":0.20}}]}],
             "advantages":[{"type":"COUPON","offer":"BASE","offerCode":"COUPON:BIENVENUE",
                "discountAmount":{"amountIncludingTax":0.50}}],
             "totalPrice":{"amountIncludingTax":9.82}}
            """;

    /** The platform's final basket. */
    private static final String BASKET = """
            {"storeCode":"0101","cardNumber":"4242","couponCodes":["BIENVENUE"],"items":[
                {"lineId":"L1","produceEan":"3300000000002","quantity":2},
                {"lineId":"L2","produceEan":"3300000000022","quantity":1},
                {"lineId":"L3","produceEan":"3300000000099","quantity":1}]}
            """;

    /**
     * Reads a JSON object.
     *
     * @param json the text
     * @return the object
     * @throws Exception never, the text being JSON
     */
    private com.fasterxml.jackson.databind.node.ObjectNode object(String json) throws Exception {
        return (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree(json);
    }

    /**
     * DG-01-01-06: an order carrying its final basket has the engine value it
     * closed and is booked from that valuation — the engine's lines and
     * promotions, the ticket VALUATED with its per-line trace — its SALE
     * consumption declared, its loyalty event carrying the basket and the
     * evaluation, the snapshot handed back to the platform and not stored.
     *
     * @throws Exception never
     */
    @Test
    void anOrderOfABasketIsBookedValued() throws Exception {
        EcommerceIntegrationService service = service();
        when(service.posSettingsService.ecommerceFidelityEarn()).thenReturn(true);
        when(service.posSettingsService.pdvNumber()).thenReturn("07039");
        when(service.ecommerceBasketService.valueFinal(any(), any()))
                .thenAnswer(call -> new EcommerceBasketService.Valued(200, call.getArgument(0), EVALUATION, null));
        when(service.ecommerceBasketService.declareSale(any(), any(), any(), any())).thenReturn(200);
        doReturn(product(1, false)).when(service).product("3300000000002");
        doReturn(product(2, true)).when(service).product("3300000000022");
        doReturn(system(com.intermarche.edge.domain.sale.SalesChannel.ECOMMERCE, true)).when(service).system("WEB");
        EcommerceIntegrationService.OrderDto order = order();
        order.basketId = " P-1 ";
        order.basket = object(BASKET);
        order.customerNumber = "4242";
        EcommerceIntegrationService.Report report = service.integrate(order, "{}", "WEB");
        assertEquals("INTEGRATED", report.status);
        assertTrue(report.valuated);
        assertTrue(report.consumptionDeclared);
        assertEquals("P-1", report.basketId);
        assertEquals(List.of("BIENVENUE"), report.consumedCoupons);
        assertEquals(1, report.unknownLines);
        assertEquals(1, report.forbiddenLines);
        assertEquals(new BigDecimal("9.82"), report.total);
        assertTrue(report.snapshot instanceof java.util.Map);
        verify(service.ecommerceBasketService).valueFinal(any(), eq("WEB"));
        verify(service.ecommerceBasketService).declareSale(eq("W1"), eq("07039"), org.mockito.ArgumentMatchers.startsWith("2026-10-24"), any());
        SyncPayloads.TicketDto ticket = booked(service);
        assertEquals("VALUATED", ticket.valuationStatus);
        assertEquals(3, ticket.valuations.size());
        assertEquals("L1", ticket.lines.get(0).lineUid);
        assertEquals(new BigDecimal("0.26"), ticket.valuations.get(0).advantageAmount);
        assertEquals(new BigDecimal("5.26"), ticket.lines.get(0).totalPrice);
        assertFalse(kept(service).report.contains("\"snapshot\""));
        ArgumentCaptor<String> event = ArgumentCaptor.forClass(String.class);
        verify(service.fidEventOutboxService).enqueue(any(), event.capture());
        com.fasterxml.jackson.databind.JsonNode fid = new ObjectMapper().readTree(event.getValue());
        assertEquals("4242", fid.path("valuationRequest").path("cardNumber").asText());
        assertEquals("BASE", fid.path("valuationResponse").path("offers").get(0).path("offerId").asText());
    }

    /**
     * DG-01-01-06: a basket the engine does not value — a refusal, an answer
     * that cannot be read — leaves the order rejected and nothing booked; a
     * fid programme that could not be read is warned.
     *
     * @throws Exception never
     */
    @Test
    void aBasketTheEngineDoesNotValueRejectsTheOrder() throws Exception {
        EcommerceIntegrationService service = service();
        when(service.ecommerceBasketService.valueFinal(any(), any())).thenReturn(
                new EcommerceBasketService.Valued(400, object(BASKET), "{}", EcommerceBasketService.FID_WARNING),
                new EcommerceBasketService.Valued(200, object(BASKET), "not json", null));
        EcommerceIntegrationService.OrderDto order = order();
        order.basket = object(BASKET);
        EcommerceIntegrationService.Report refused = service.integrate(order, "{}");
        assertEquals("REJECTED", refused.status);
        assertEquals("Valorisation du panier refusée par le moteur (HTTP 400)", refused.message);
        assertEquals(List.of("Promotions carte indisponibles"), refused.warnings);
        assertNull(refused.basketId);
        assertEquals("Valorisation du panier illisible", service.integrate(order, "{}").message);
        verify(service.syncIngestService, never()).ingestTicket(any());
        verify(service.ecommerceBasketService, never()).declareSale(any(), any(), any(), any());
    }

    /**
     * DG-01-01-06: a basket that is not an object reads as none (the order's
     * own lines are booked); a consumption the engine refuses is warned and
     * does not undo the sale, nothing to declare is no warning; the order's
     * date and point of sale are the declared ones; a snapshot line without
     * article is booked as unknown; an evaluation that cannot be written is
     * declared empty.
     *
     * @throws Exception never
     */
    @Test
    void theBasketEdgesOfAnOrder() throws Exception {
        EcommerceIntegrationService service = service();
        EcommerceIntegrationService.OrderDto plain = order(line("1", "Lait", "1", "2.76", "0.2"));
        plain.basket = new ObjectMapper().readTree("[1]");
        assertEquals("INTEGRATED", service.integrate(plain, "{}").status);
        verify(service.ecommerceBasketService, never()).valueFinal(any(), any());
        EcommerceIntegrationService basket = service();
        when(basket.ecommerceBasketService.valueFinal(any(), any())).thenAnswer(call -> new EcommerceBasketService.Valued(
                200, call.getArgument(0), "{\"offers\":[]}", null));
        when(basket.ecommerceBasketService.declareSale(any(), any(), any(), any())).thenReturn(503, 204, 100);
        EcommerceIntegrationService.OrderDto order = order();
        order.basket = object("{\"items\":[{\"lineId\":\"X\",\"quantity\":1,\"pricePerUnitInclTax\":1.00}]}");
        order.orderDate = "2026-10-20T08:00:00";
        order.pdvNumber = "0101";
        EcommerceIntegrationService.Report warned = basket.integrate(order, "{}");
        assertEquals(1, warned.unknownLines);
        assertFalse(warned.consumptionDeclared);
        assertEquals(List.of("Consommation des offres non déclarée (HTTP 503)"), warned.warnings);
        assertEquals("", booked(basket).lines.get(0).ean);
        verify(basket.ecommerceBasketService).declareSale("W1", "0101", "2026-10-20T08:00:00", "{\"offers\":[]}");
        EcommerceIntegrationService.OrderDto second = order();
        second.reference = "W2";
        second.orderDate = " ";
        second.basket = order.basket;
        EcommerceIntegrationService.Report quiet = basket.integrate(second, "{}");
        assertTrue(quiet.warnings.isEmpty());
        assertFalse(quiet.consumptionDeclared);
        EcommerceIntegrationService.OrderDto informational = order();
        informational.reference = "W3";
        informational.basketId = " ";
        informational.basket = order.basket;
        EcommerceIntegrationService.Report early = basket.integrate(informational, "{}");
        assertNull(early.basketId);
        assertEquals(List.of("Consommation des offres non déclarée (HTTP 100)"), early.warnings);
        EcommerceIntegrationService failing = service();
        failing.objectMapper = org.mockito.Mockito.spy(new ObjectMapper());
        org.mockito.Mockito.doThrow(new JsonProcessingException("boom") { }).when(failing.objectMapper)
                .writeValueAsString(any(java.util.Map.class));
        when(failing.ecommerceBasketService.valueFinal(any(), any())).thenAnswer(call -> new EcommerceBasketService.Valued(
                200, call.getArgument(0), "{\"offers\":[]}", null));
        when(failing.ecommerceBasketService.declareSale(any(), any(), any(), any())).thenReturn(200);
        EcommerceIntegrationService.OrderDto third = order();
        third.basket = order.basket;
        failing.integrate(third, "{}");
        verify(failing.ecommerceBasketService).declareSale(eq("W1"), any(), any(), eq("{}"));
    }

    /**
     * DG-03-01-05: an order paid short is never booked as a closed sale: it is
     * kept as an e-commerce basket the till recalls by its reference, with the
     * platform's payment under the tender it was integrated as, and the
     * report answers PENDING_PAYMENT with what remains.
     */
    @Test
    void anOrderPaidShortIsKeptForTheTill() {
        EcommerceIntegrationService service = service();
        com.intermarche.edge.domain.sale.CustomerOrder stored = new com.intermarche.edge.domain.sale.CustomerOrder();
        when(service.customerOrderService.createFromTill(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(stored);
        EcommerceIntegrationService.OrderDto order = order(line("1", "Lait", "2", "2.76", "0.2"));
        order.payments = List.of(payment("paypal", "2.00"));
        order.customerNumber = "4242";
        EcommerceIntegrationService.Report report = service.integrate(order, "{}");
        assertEquals("PENDING_PAYMENT", report.status);
        assertEquals("W1", report.recallCode);
        assertEquals(new BigDecimal("3.52"), report.remaining);
        assertEquals(new BigDecimal("5.52"), report.total);
        assertEquals("Commande en attente de règlement en caisse : reste dû 3,52", report.message);
        verify(service.syncIngestService, never()).ingestTicket(any());
        ArgumentCaptor<List<com.intermarche.edge.domain.sale.CustomerOrder.OrderLine>> lines =
                ArgumentCaptor.forClass(List.class);
        verify(service.customerOrderService).createFromTill(eq("W1"),
                eq(com.intermarche.edge.domain.sale.OrderKind.ECOMMERCE), eq(new BigDecimal("5.52")), any(),
                lines.capture(), eq("Commande web W1"), any());
        assertEquals("1", lines.getValue().get(0).ean);
        assertEquals("CARD", stored.payments.get(0).tender);
        assertEquals(new BigDecimal("2.00"), stored.payments.get(0).amount);
        assertEquals("4242", stored.customerAccountNumber);
        EcommerceOrder row = kept(service);
        assertEquals(EcommerceOrder.Status.PENDING_PAYMENT, row.status);
        assertTrue(row.isPendingPayment());
    }

    /**
     * DG-03-01-05: under REJECT, an order paid short is refused and nothing is
     * kept for the till.
     */
    @Test
    void anOrderPaidShortIsRejectedWhereAdministered() {
        EcommerceIntegrationService service = service();
        when(service.posSettingsService.ecommercePartialPayment()).thenReturn("REJECT");
        EcommerceIntegrationService.OrderDto order = order(line("1", "Lait", "2", "2.76", "0.2"));
        order.payments = List.of(payment("CARD", "2.00"));
        EcommerceIntegrationService.Report report = service.integrate(order, "{}");
        assertEquals("REJECTED", report.status);
        assertEquals("Commande partiellement réglée refusée : reste dû 3,52", report.message);
        verify(service.customerOrderService, never()).createFromTill(any(), any(), any(), any(), any(), any(), any());
        verify(service.syncIngestService, never()).ingestTicket(any());
        assertEquals(EcommerceOrder.Status.REJECTED, kept(service).status);
    }

    /**
     * The ticket counts articles, not lines: the units of a piece line, one
     * for a weighed article, a decimal or non-positive quantity, none for a
     * returned line; a line without quantity or article counts one.
     */
    @Test
    void theTicketCountsArticlesNotLines() {
        EcommerceIntegrationService service = service();
        Product apples = product(1, false);
        apples.productType = com.intermarche.edge.domain.catalog.ProductType.WEIGHT;
        doReturn(apples).when(service).product("APPLE");
        doReturn(product(2, false)).when(service).product("MILK");
        List<SyncPayloads.LineDto> lines = new ArrayList<>();
        lines.add(dto("MILK", "3"));
        lines.add(dto("APPLE", "2"));
        lines.add(dto("MILK", "0.5"));
        lines.add(dto("MILK", "2.000"));
        lines.add(dto(null, null));
        lines.add(dto("", "-1"));
        SyncPayloads.LineDto returned = dto("MILK", "4");
        returned.returnLine = true;
        lines.add(returned);
        assertEquals(9, service.articleCount(lines));
        service.integrate(order(line("MILK", "Lait", "3", "1", "0.2")), "{}");
        assertEquals(3, booked(service).itemCount);
    }

    /**
     * A booked line of an EAN and a quantity.
     *
     * @param ean the EAN, or null
     * @param quantity the quantity, or null
     * @return the line
     */
    private SyncPayloads.LineDto dto(String ean, String quantity) {
        SyncPayloads.LineDto line = new SyncPayloads.LineDto();
        line.ean = ean;
        line.quantity = quantity == null ? null : new BigDecimal(quantity);
        return line;
    }
}
