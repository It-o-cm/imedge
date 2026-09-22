package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.safe.CoinOrder;
import com.intermarche.pos.domain.safe.CoinOrderLine;
import com.intermarche.pos.domain.safe.SafeDenomination;
import com.intermarche.pos.security.CurrentUser;
import com.intermarche.pos.service.SafeService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link AdminCoinOrderResource}.
 *
 * <p>The order and its delivery. Every refusal is asserted on its own leg — no
 * number, a number already taken, no quantity at all, an unknown order, one that
 * awaits nothing — and the two readers are proved on the rows they keep and the
 * rows they drop. The slip is asserted on its printed shape, wording and all.
 */
class AdminCoinOrderResourceTest {

    /**
     * Builds a resource with mocked template, safe and identity.
     *
     * @return the wired resource
     */
    private AdminCoinOrderResource newResource() {
        AdminCoinOrderResource resource = new AdminCoinOrderResource();
        resource.adminCoinOrders = mock(Template.class);
        resource.safeService = mock(SafeService.class);
        resource.currentUser = mock(CurrentUser.class);
        return resource;
    }

    /**
     * Stubs a template's fluent chain to a recognizable view.
     *
     * @param template the template to stub
     * @return the view every chain call returns
     */
    private TemplateInstance stub(Template template) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(template.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Stubs the duplicate-number lookup.
     *
     * @param panache the active PanacheEntityBase mock
     * @param number the number looked up
     * @param found the order to resolve, null when the number is free
     */
    @SuppressWarnings("unchecked")
    private void stubNumber(MockedStatic<PanacheEntityBase> panache, String number,
            CoinOrder found) {
        PanacheQuery<CoinOrder> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(found);
        panache.when(() -> CoinOrder.find("orderNumber", number)).thenReturn(query);
    }

    /**
     * Builds a posted order form.
     *
     * @param number the order number
     * @return the form, one denomination filled
     */
    private MultivaluedMap<String, String> orderForm(String number) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("orderNumber", number);
        form.add("label", "Monnaie de la semaine");
        form.add("requestedOn", "2026-09-14");
        form.add("qtyC1", "50");
        form.add("qtyN10", "0");
        form.add("qtyN20", "");
        return form;
    }

    /**
     * The list page carries the orders, the ones awaiting delivery and the
     * denominations the screen offers.
     */
    @Test
    void thelistPageCarriesTheOrdersAndTheDenominations() {
        AdminCoinOrderResource resource = newResource();
        TemplateInstance view = stub(resource.adminCoinOrders);
        CoinOrder order = new CoinOrder();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<CoinOrder> all = mock(PanacheQuery.class);
            when(all.list()).thenReturn(List.of(order));
            PanacheQuery<CoinOrder> awaiting = mock(PanacheQuery.class);
            when(awaiting.list()).thenReturn(List.of(order));
            panache.when(() -> CoinOrder.find("order by requestedOn desc, id desc"))
                    .thenReturn(all);
            panache.when(() -> CoinOrder.find("status = ?1 order by requestedOn",
                    CoinOrder.OrderStatus.REQUESTED)).thenReturn(awaiting);
            assertSame(view, resource.list(null, true));
        }
        verify(resource.adminCoinOrders).data("orders", List.of(order));
        verify(view).data("awaiting", List.of(order));
        verify(view).data("denominations", SafeDenomination.values());
    }

    /**
     * An order without a number, and one whose number is already taken, are
     * refused — the two legs of that guard.
     */
    @Test
    void anUnnamedOrDuplicateOrderIsRefused() {
        AdminCoinOrderResource resource = newResource();
        MultivaluedMap<String, String> unnamed = orderForm("   ");
        assertTrue(resource.order(unnamed).getLocation().toString().contains("noticeOk=false"));
        CoinOrder existing = new CoinOrder();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CoinOrder> created = mockConstruction(CoinOrder.class)) {
            stubNumber(panache, "DM-1", existing);
            assertTrue(resource.order(orderForm("DM-1")).getLocation().toString()
                    .contains("noticeOk=false"));
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * An order asking for nothing is refused: a slip with no line is not a slip.
     */
    @Test
    void anOrderAskingForNothingIsRefused() {
        AdminCoinOrderResource resource = newResource();
        MultivaluedMap<String, String> empty = new MultivaluedHashMap<>();
        empty.add("orderNumber", "DM-1");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CoinOrder> created = mockConstruction(CoinOrder.class)) {
            stubNumber(panache, "DM-1", null);
            assertTrue(resource.order(empty).getLocation().toString().contains("noticeOk=false"));
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * A complete order is written with its lines, its day and its author.
     */
    @Test
    void acompleteOrderIsWritten() {
        AdminCoinOrderResource resource = newResource();
        Employee author = new Employee();
        when(resource.currentUser.getName()).thenReturn("alice");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CoinOrder> created = mockConstruction(CoinOrder.class)) {
            stubNumber(panache, "DM-1", null);
            PanacheQuery<Employee> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(author);
            panache.when(() -> Employee.find("loginName", "alice")).thenReturn(query);
            assertTrue(resource.order(orderForm("DM-1")).getLocation().toString()
                    .contains("noticeOk=true"));
            CoinOrder written = created.constructed().get(0);
            assertEquals("DM-1", written.orderNumber);
            assertEquals("Monnaie de la semaine", written.label);
            assertEquals(LocalDate.of(2026, 9, 14), written.requestedOn);
            assertSame(author, written.requestedBy);
            assertEquals(CoinOrder.OrderStatus.REQUESTED, written.status);
            assertEquals(1, written.lines.size());
            assertSame(SafeDenomination.C1, written.lines.get(0).denomination);
            verify(written).persist();
        }
    }

    /**
     * An order with no day is entered today — the null arm of that guard.
     */
    @Test
    void anOrderWithoutAdayIsEnteredToday() {
        AdminCoinOrderResource resource = newResource();
        MultivaluedMap<String, String> form = orderForm("DM-2");
        form.remove("requestedOn");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CoinOrder> created = mockConstruction(CoinOrder.class)) {
            stubNumber(panache, "DM-2", null);
            resource.order(form);
            assertEquals(LocalDate.now(), created.constructed().get(0).requestedOn);
        }
    }

    /**
     * The reader keeps the denominations asked for and drops the ones left blank
     * or at zero — the two arms of that filter.
     */
    @Test
    void thereaderKeepsOnlyTheDenominationsAskedFor() {
        AdminCoinOrderResource resource = newResource();
        List<CoinOrderLine> lines = resource.requestedLines(orderForm("DM-1"));
        assertEquals(1, lines.size());
        assertEquals(50, lines.get(0).requestedQuantity);
        assertTrue(resource.requestedLines(new MultivaluedHashMap<>()).isEmpty());
    }

    /**
     * The delivery writes the received quantities onto the order's own lines,
     * a denomination the form does not name counting as nothing received.
     */
    @Test
    void thedeliveryWritesTheReceivedQuantities() {
        AdminCoinOrderResource resource = newResource();
        CoinOrder order = new CoinOrder();
        order.lines = new ArrayList<>(List.of(new CoinOrderLine(SafeDenomination.C1, 50),
                new CoinOrderLine(SafeDenomination.N10, 20), new CoinOrderLine(null, 5)));
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("rcvC1", "40");
        resource.applyReceived(order, form);
        assertEquals(Integer.valueOf(40), order.lines.get(0).receivedQuantity);
        assertEquals(Integer.valueOf(0), order.lines.get(1).receivedQuantity);
        assertNull(order.lines.get(2).receivedQuantity);
    }

    /**
     * A delivery against an unknown order is refused, whether the identifier is
     * absent or names nothing — the two legs of that guard.
     */
    @Test
    void adeliveryAgainstAnUnknownOrderIsRefused() {
        AdminCoinOrderResource resource = newResource();
        assertTrue(resource.receive(new MultivaluedHashMap<>()).getLocation().toString()
                .contains("noticeOk=false"));
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "9");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CoinOrder.findById(9L)).thenReturn(null);
            assertTrue(resource.receive(form).getLocation().toString().contains("noticeOk=false"));
        }
        verify(resource.safeService, never()).receiveCoinOrder(any(), any());
    }

    /**
     * A delivery the safe refuses is announced as refused; one it accepts says
     * whether it matched the order — the two arms of that message.
     */
    @Test
    void adeliveryReportsWhatTheSafeAnswered() {
        AdminCoinOrderResource resource = newResource();
        CoinOrder order = mock(CoinOrder.class);
        order.lines = new ArrayList<>();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "3");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CoinOrder.findById(3L)).thenReturn(order);
            when(resource.safeService.receiveCoinOrder(any(), any()))
                    .thenReturn("Refusé.", null, null);
            assertTrue(resource.receive(form).getLocation().toString().contains("noticeOk=false"));
            when(order.isFullyFulfilled()).thenReturn(true, false);
            String matched = resource.receive(form).getLocation().toString();
            assertTrue(matched.contains("noticeOk=true"));
            assertTrue(matched.contains("conforme"));
            String differing = resource.receive(form).getLocation().toString();
            assertTrue(differing.contains("noticeOk=true"));
            assertTrue(differing.contains("diff"));
        }
    }

    /**
     * An order awaiting its delivery may be cancelled; an unknown one and one
     * that awaits nothing may not — the three legs of that guard.
     */
    @Test
    void onlyAnOrderAwaitingDeliveryMayBeCancelled() {
        AdminCoinOrderResource resource = newResource();
        assertTrue(resource.cancel(new MultivaluedHashMap<>()).getLocation().toString()
                .contains("noticeOk=false"));
        CoinOrder waiting = mock(CoinOrder.class);
        when(waiting.isAwaitingDelivery()).thenReturn(true);
        CoinOrder closed = mock(CoinOrder.class);
        when(closed.isAwaitingDelivery()).thenReturn(false);
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("id", "3");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CoinOrder.findById(3L)).thenReturn(waiting, closed);
            assertTrue(resource.cancel(form).getLocation().toString().contains("noticeOk=true"));
            assertEquals(CoinOrder.OrderStatus.CANCELLED, waiting.status);
            verify(waiting).persist();
            assertTrue(resource.cancel(form).getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * The slip states the order, its lines and its total, and is handed back as
     * an attachment named for it.
     */
    @Test
    void theslipStatesTheOrderAndItsLines() {
        AdminCoinOrderResource resource = newResource();
        CoinOrder order = new CoinOrder();
        order.orderNumber = "DM-1";
        order.label = "Monnaie de la semaine";
        order.requestedOn = LocalDate.of(2026, 9, 14);
        order.lines = new ArrayList<>(List.of(new CoinOrderLine(SafeDenomination.C1, 50)));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CoinOrder.findById(3L)).thenReturn(order);
            Response response = resource.slip(3L);
            assertEquals(200, response.getStatus());
            String slip = String.valueOf(response.getEntity());
            assertTrue(slip.startsWith("DEMANDE DE MONNAIE DM-1\n"));
            assertTrue(slip.contains("Date : 2026-09-14"));
            assertTrue(slip.contains("Objet : Monnaie de la semaine"));
            assertTrue(slip.contains("Pièce 1 €"));
            assertTrue(slip.contains("TOTAL : 50.00 EUR"));
            assertTrue(response.getHeaderString("Content-Disposition")
                    .contains("demande-monnaie-DM-1.txt"));
        }
    }

    /**
     * A slip of an order carrying no wording leaves the line off — the blank arm
     * of that guard — and an unknown order answers a 404, on both legs.
     */
    @Test
    void theslipToleratesAmissingWordingAndRefusesAnUnknownOrder() {
        AdminCoinOrderResource resource = newResource();
        CoinOrder bare = new CoinOrder();
        bare.orderNumber = "DM-2";
        bare.requestedOn = LocalDate.of(2026, 9, 14);
        bare.label = "  ";
        bare.lines = new ArrayList<>();
        assertTrue(!resource.renderSlip(bare).contains("Objet :"));
        assertEquals(404, resource.slip(null).getStatus());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CoinOrder.findById(9L)).thenReturn(null);
            assertEquals(404, resource.slip(9L).getStatus());
        }
    }

    /**
     * The signed-in employee is resolved by login, and is nobody when the
     * session names nobody — the three legs of that guard.
     */
    @Test
    void thesignedInEmployeeIsResolvedByLogin() {
        AdminCoinOrderResource resource = newResource();
        Employee author = new Employee();
        when(resource.currentUser.getName()).thenReturn("alice", "", null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Employee> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(author);
            panache.when(() -> Employee.find("loginName", "alice")).thenReturn(query);
            assertSame(author, resource.signedIn());
            assertNull(resource.signedIn());
            assertNull(resource.signedIn());
        }
        AdminCoinOrderResource anonymous = newResource();
        anonymous.currentUser = null;
        assertNull(anonymous.signedIn());
    }
}
