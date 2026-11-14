package com.intermarche.edge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.payment.CardPayment;
import com.intermarche.edge.domain.payment.CashPayment;
import com.intermarche.edge.domain.sale.CustomerOrder;
import com.intermarche.edge.domain.sale.EcommerceOrder;
import com.intermarche.edge.domain.sale.OrderKind;
import com.intermarche.edge.domain.sale.OrderStatus;
import com.intermarche.edge.domain.sale.SalesChannel;
import com.intermarche.edge.domain.sale.Ticket;
import com.intermarche.edge.domain.sale.TicketLine;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EcommerceOrderViewService} (DG-03-02-01 to -04): an
 * order is read as one view whatever it became — integrated, refused, paid
 * short, a document of the till open, paid or collected — with the
 * platform's payments and the till's, the closed ones and the pending ones
 * are listed under their filters.
 */
class EcommerceOrderViewServiceTest {

    /**
     * A spied service with no row anywhere.
     *
     * @return the service
     */
    private EcommerceOrderViewService service() {
        EcommerceOrderViewService service = spy(new EcommerceOrderViewService());
        doReturn(null).when(service).integration(any());
        doReturn(null).when(service).document(any());
        doReturn(null).when(service).ticket(any());
        doReturn(new ArrayList<>()).when(service).integrations();
        doReturn(new ArrayList<>()).when(service).documents();
        return service;
    }

    /**
     * An integration row.
     *
     * @param reference its reference
     * @param status its status
     * @param report its report JSON
     * @return the row
     */
    private EcommerceOrder integration(String reference, EcommerceOrder.Status status, String report) {
        EcommerceOrder row = new EcommerceOrder();
        row.reference = reference;
        row.status = status;
        row.report = report;
        row.totalAmount = new BigDecimal("5.52");
        row.receivedAt = LocalDateTime.of(2026, 10, 28, 10, 0);
        return row;
    }

    /**
     * A document of the till.
     *
     * @param code its code
     * @param kind its kind
     * @param status its status
     * @return the document
     */
    private CustomerOrder document(String code, OrderKind kind, OrderStatus status) {
        CustomerOrder order = new CustomerOrder();
        order.code = code;
        order.kind = kind;
        order.status = status;
        order.totalAmount = new BigDecimal("9.72");
        order.updatedAt = LocalDateTime.of(2026, 10, 28, 11, 0);
        order.lines.add(new CustomerOrder.OrderLine("E1", "Lait", BigDecimal.ONE, new BigDecimal("9.72"),
                new BigDecimal("0.20"), new BigDecimal("9.72")));
        return order;
    }

    /**
     * DG-03-02-03: an integrated order reads as INTEGRATED with its platform
     * payments, its ticket and its lines; a refused one as REJECTED; a row
     * whose report cannot be read keeps its own fields.
     */
    @Test
    void anIntegratedOrderIsReadFromItsReport() {
        EcommerceOrderViewService service = service();
        EcommerceOrder row = integration("W1", EcommerceOrder.Status.INTEGRATED,
                "{\"reference\":\"W1\",\"status\":\"INTEGRATED\",\"payments\":[{\"method\":\"PAYPAL\",\"integratedAs\":\"CARD\",\"amount\":5.52}],\"other\":1}");
        row.ticketNumber = "ECOM-W1";
        row.channel = SalesChannel.ECOMMERCE;
        doReturn(row).when(service).integration("W1");
        Ticket ticket = new Ticket();
        TicketLine line = new TicketLine();
        line.ean = "E1";
        line.productLabel = "Lait";
        line.quantity = BigDecimal.ONE;
        ticket.lines.add(line);
        doReturn(ticket).when(service).ticket("ECOM-W1");
        EcommerceOrderViewService.View view = service.view("W1");
        Assertions.assertEquals("INTEGRATED", view.status);
        Assertions.assertEquals("ECOMMERCE", view.kind);
        Assertions.assertEquals("ECOM-W1", view.ticketNumber);
        Assertions.assertEquals("PAYPAL", view.payments.get(0).method());
        Assertions.assertEquals("PLATFORM", view.payments.get(0).source());
        Assertions.assertEquals(BigDecimal.ZERO, view.remaining);
        Assertions.assertEquals("Lait", view.lines.get(0).label());
        Assertions.assertNull(view.code);
        EcommerceOrder refused = integration("W2", EcommerceOrder.Status.REJECTED, "not json");
        refused.channel = SalesChannel.MOBILITY;
        doReturn(refused).when(service).integration("W2");
        EcommerceOrderViewService.View rejected = service.view("W2");
        Assertions.assertEquals("REJECTED", rejected.status);
        Assertions.assertEquals("MOBILITY", rejected.kind);
        Assertions.assertNull(rejected.report);
        Assertions.assertTrue(rejected.payments.isEmpty());
        EcommerceOrder pending = integration("W3", EcommerceOrder.Status.PENDING_PAYMENT,
                "{\"remaining\":3.52}");
        doReturn(pending).when(service).integration("W3");
        EcommerceOrderViewService.View waiting = service.view("W3");
        Assertions.assertEquals("PENDING_PAYMENT", waiting.status);
        Assertions.assertEquals(new BigDecimal("3.52"), waiting.remaining);
        Assertions.assertNull(service.view("NONE"));
    }

    /**
     * DG-03-01-05 / DG-03-02-03: a document paid short by the platform and
     * settled at the till reads with the platform's payment and the till's
     * own (NEMO) payment, the one the till restated counted once, and the
     * ticket the till named; the platform's own means is read from the
     * integration report when it kept them.
     */
    @Test
    void aSettledDocumentShowsThePlatformAndTillPayments() {
        EcommerceOrderViewService service = service();
        CustomerOrder order = document("W4", OrderKind.ECOMMERCE, OrderStatus.COLLECTED);
        order.payments.add(new CustomerOrder.OrderPayment("CARD", new BigDecimal("2.00")));
        order.payments.add(new CustomerOrder.OrderPayment("CASH", null));
        order.depositPaid = new BigDecimal("7.72");
        order.settledTicketNumber = "C04-000012";
        doReturn(order).when(service).document("W4");
        doReturn(integration("W4", EcommerceOrder.Status.PENDING_PAYMENT, "{}")).when(service).integration("W4");
        CustomerOrder paypal = document("W7", OrderKind.ECOMMERCE, OrderStatus.OPEN);
        paypal.payments.add(new CustomerOrder.OrderPayment("CARD", new BigDecimal("2.00")));
        doReturn(paypal).when(service).document("W7");
        doReturn(integration("W7", EcommerceOrder.Status.PENDING_PAYMENT,
                "{\"payments\":[{\"method\":\"PAYPAL\",\"integratedAs\":\"CARD\",\"amount\":2.00}]}"))
                .when(service).integration("W7");
        doReturn(null).when(service).settlingTicket(paypal);
        Assertions.assertEquals("PAYPAL", service.view("W7").payments.get(0).method());
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "C04-000012";
        CardPayment restated = new CardPayment(new BigDecimal("2.00"));
        CardPayment otherAmount = new CardPayment(new BigDecimal("1.00"));
        otherAmount.tenderCode = "CB";
        CashPayment cash = new CashPayment(new BigDecimal("6.72"), new BigDecimal("10.00"));
        CashPayment unpriced = new CashPayment(null, null);
        ticket.payments.add(otherAmount);
        ticket.payments.add(restated);
        ticket.payments.add(cash);
        ticket.payments.add(unpriced);
        doReturn(ticket).when(service).ticket("C04-000012");
        EcommerceOrderViewService.View view = service.view("W4");
        Assertions.assertEquals("COLLECTED", view.status);
        Assertions.assertEquals("W4", view.code);
        Assertions.assertEquals("C04-000012", view.ticketNumber);
        Assertions.assertEquals(0, view.remaining.signum());
        Assertions.assertEquals(5, view.payments.size());
        Assertions.assertEquals("PLATFORM", view.payments.get(0).source());
        Assertions.assertEquals("CB", view.payments.get(2).method());
        Assertions.assertEquals("NEMO", view.payments.get(2).source());
        Assertions.assertEquals("CASH", view.payments.get(3).method());
        Assertions.assertEquals("Lait", view.lines.get(0).label());
    }

    /**
     * DG-03-02-02 / -04: an open mobility document reads as PENDING_PAYMENT
     * with what remains; a document the till did not name a ticket for is
     * linked by the sale's recalled orders, or by nothing; a document of a
     * kind the platform does not follow is not served; a document whose total
     * is unknown reads as zero.
     */
    @Test
    void anOpenDocumentIsPending() {
        EcommerceOrderViewService service = service();
        CustomerOrder order = document("MO-1", OrderKind.MOBILITY, OrderStatus.OPEN);
        order.payments.add(new CustomerOrder.OrderPayment("CARD", new BigDecimal("5.00")));
        doReturn(order).when(service).document("MO-1");
        doReturn(null).when(service).settlingTicket(order);
        EcommerceOrderViewService.View view = service.view(order);
        Assertions.assertEquals("PENDING_PAYMENT", view.status);
        Assertions.assertEquals("MOBILITY", view.kind);
        Assertions.assertEquals(new BigDecimal("4.72"), view.remaining);
        Assertions.assertNull(view.ticketNumber);
        Assertions.assertEquals("MO-1", service.view("MO-1").code);
        CustomerOrder devis = document("D-1", OrderKind.DEVIS, OrderStatus.OPEN);
        doReturn(devis).when(service).document("D-1");
        Assertions.assertNull(service.view("D-1"));
        CustomerOrder untotalled = document("C-1", OrderKind.CLICK_COLLECT, OrderStatus.PARTIALLY_PAID);
        untotalled.totalAmount = null;
        doReturn(null).when(service).settlingTicket(untotalled);
        EcommerceOrderViewService.View partial = service.view(untotalled);
        Assertions.assertEquals("PARTIALLY_PAID", partial.status);
        Assertions.assertEquals(BigDecimal.ZERO, partial.total);
    }

    /**
     * The settling ticket is the one the till named, else the sale whose
     * recalled orders name the document.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theSettlingTicketIsFound() {
        EcommerceOrderViewService service = service();
        CustomerOrder named = document("W5", OrderKind.ECOMMERCE, OrderStatus.COLLECTED);
        named.settledTicketNumber = "C04-1";
        Ticket ticket = new Ticket();
        doReturn(ticket).when(service).ticket("C04-1");
        Assertions.assertSame(ticket, service.settlingTicket(named));
        CustomerOrder unnamed = document("W6", OrderKind.ECOMMERCE, OrderStatus.COLLECTED);
        Ticket linked = new Ticket();
        try (MockedStatic<PanacheEntityBase> base = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(linked);
            base.when(() -> PanacheEntityBase.find(anyString(), eq("W6"), eq("W6;%"), eq("%;W6"))).thenReturn(query);
            Assertions.assertSame(linked, service.settlingTicket(unnamed));
        }
    }

    /**
     * DG-03-02-01: the closed list keeps the integrated orders and the
     * documents paid or collected, under the status, period and channel
     * asked for; the orders paid short appear through their document.
     */
    @Test
    void theClosedOrdersAreFiltered() {
        EcommerceOrderViewService service = service();
        EcommerceOrder integrated = integration("W1", EcommerceOrder.Status.INTEGRATED, "{}");
        EcommerceOrder shortPaid = integration("W4", EcommerceOrder.Status.PENDING_PAYMENT, "{}");
        doReturn(List.of(integrated, shortPaid)).when(service).integrations();
        CustomerOrder collected = document("W4", OrderKind.ECOMMERCE, OrderStatus.COLLECTED);
        CustomerOrder open = document("EC-1", OrderKind.ECOMMERCE, OrderStatus.OPEN);
        CustomerOrder mobility = document("MO-1", OrderKind.MOBILITY, OrderStatus.PAID);
        CustomerOrder undated = document("MO-2", OrderKind.MOBILITY, OrderStatus.PAID);
        undated.updatedAt = null;
        doReturn(List.of(collected, open, mobility, undated)).when(service).documents();
        doReturn(null).when(service).settlingTicket(any());
        Assertions.assertEquals(4, service.closed(null, null, null, null).size());
        Assertions.assertEquals(1, service.closed(" integrated ", null, null, null).size());
        Assertions.assertEquals(2, service.closed(null, null, null, "mobility").size());
        LocalDate day = LocalDate.of(2026, 10, 28);
        Assertions.assertEquals(3, service.closed(null, day, day, null).size());
        Assertions.assertEquals(0, service.closed(null, day.plusDays(1), null, null).size());
        Assertions.assertEquals(0, service.closed(null, null, day.minusDays(1), null).size());
        Assertions.assertEquals(3, service.closed(null, null, day, null).size());
    }

    /**
     * DG-03-02-02: the pending baskets are the documents open or partially
     * paid, never the paid ones.
     */
    @Test
    void thePendingBasketsAreTheOpenDocuments() {
        EcommerceOrderViewService service = service();
        CustomerOrder open = document("EC-1", OrderKind.ECOMMERCE, OrderStatus.OPEN);
        CustomerOrder paid = document("EC-2", OrderKind.ECOMMERCE, OrderStatus.PAID);
        doReturn(List.of(open, paid)).when(service).documents();
        doReturn(null).when(service).settlingTicket(any());
        List<EcommerceOrderViewService.View> pending = service.pending();
        Assertions.assertEquals(1, pending.size());
        Assertions.assertEquals("EC-1", pending.get(0).code);
    }

    /**
     * The lookups go to the entities: no ticket for no number, the query
     * otherwise; the rows by reference and code; the listings.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theLookupsGoToTheEntities() {
        EcommerceOrderViewService service = new EcommerceOrderViewService();
        Assertions.assertNull(service.ticket(null));
        try (MockedStatic<PanacheEntityBase> base = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<PanacheEntityBase> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            base.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class))).thenReturn(query);
            base.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class))).thenReturn(new ArrayList<>());
            Assertions.assertNull(service.ticket("C04-1"));
            Assertions.assertNull(service.integration("W1"));
            Assertions.assertNull(service.document("W1"));
            Assertions.assertTrue(service.integrations().isEmpty());
            Assertions.assertTrue(service.documents().isEmpty());
        }
        Assertions.assertEquals("PENDING_PAYMENT", EcommerceOrderViewService.status(OrderStatus.OPEN));
        Assertions.assertEquals("CANCELLED", EcommerceOrderViewService.status(OrderStatus.CANCELLED));
        Assertions.assertNotNull(new ObjectMapper());
    }

    /**
     * DG-03-01-05: an order paid short and settled at the till reads PAID
     * from its row alone, and the closed list shows it through its document
     * only.
     */
    @Test
    void anOrderSettledAtTheTillIsListedOnce() {
        EcommerceOrderViewService service = service();
        EcommerceOrder settled = integration("W8", EcommerceOrder.Status.SETTLED, "{}");
        doReturn(settled).when(service).integration("W8");
        Assertions.assertEquals("PAID", service.view("W8").status);
        doReturn(List.of(settled)).when(service).integrations();
        Assertions.assertTrue(service.closed(null, null, null, null).isEmpty());
    }
}
