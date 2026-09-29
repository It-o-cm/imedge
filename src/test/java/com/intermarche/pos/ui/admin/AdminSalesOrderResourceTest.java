package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.people.Menu;
import com.intermarche.pos.domain.sale.CustomerOrder;
import com.intermarche.pos.domain.sale.OrderStatus;
import com.intermarche.pos.domain.setting.Feature;
import com.intermarche.pos.service.CustomerOrderService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminSalesOrderResource}.
 * <p>
 * Plain JUnit 5 + Mockito, no Quarkus and no database. Every guard of create,
 * deposit and cancel is exercised (EXP-90/91/92/85).
 */
class AdminSalesOrderResourceTest {

    /**
     * Builds a resource with a mocked template and order service.
     *
     * @return the resource
     */
    private AdminSalesOrderResource newResource() {
        AdminSalesOrderResource resource = new AdminSalesOrderResource();
        resource.adminOrders = mock(Template.class);
        resource.customerOrderService = mock(CustomerOrderService.class);
        return resource;
    }

    /**
     * Builds a posted form from key/value pairs.
     *
     * @param pairs the alternating keys and values
     * @return the form
     */
    private MultivaluedMap<String, String> form(String... pairs) {
        MultivaluedMap<String, String> posted = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            posted.putSingle(pairs[i], pairs[i + 1]);
        }
        return posted;
    }

    /**
     * Wraps a first-result in a Panache query mock.
     *
     * @param row the order the query resolves, or null
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<CustomerOrder> query(CustomerOrder row) {
        PanacheQuery<CustomerOrder> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        return query;
    }

    /**
     * The screen is declared in the supervision menu (EXP-90).
     */
    @Test
    void theScreenIsDeclaredInTheMenu() {
        Feature feature = Feature.CUSTOMER_ORDER;
        assertEquals("customer_order", feature.getKey());
        assertEquals("/admin/orders", feature.getPath());
        assertEquals(Menu.SUPERVISION, feature.getMenu());
    }

    /**
     * The page lists the orders.
     */
    @Test
    void ordersPageListsOrders() {
        AdminSalesOrderResource resource = newResource();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminOrders.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        CustomerOrder order = new CustomerOrder();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CustomerOrder.list("order by id desc")).thenReturn(List.of(order));
            assertSame(instance, resource.ordersPage(null, true));
            verify(resource.adminOrders).data("orders", List.of(order));
        }
    }

    /**
     * Create refuses a blank code, a duplicate code, an unknown kind and a
     * missing/negative total (each guard).
     */
    @Test
    void createRefusesItsGuards() {
        AdminSalesOrderResource resource = newResource();
        assertTrue(resource.createOrder(form()).getLocation().toString().contains("noticeOk=false"));
        PanacheQuery<CustomerOrder> existing = query(new CustomerOrder());
        PanacheQuery<CustomerOrder> absent = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CustomerOrder.find("code", "CMD1")).thenReturn(existing);
            assertTrue(resource.createOrder(form("code", "CMD1")).getLocation().toString().contains("noticeOk=false"));
            panache.when(() -> CustomerOrder.find("code", "CMD2")).thenReturn(absent);
            assertTrue(resource.createOrder(form("code", "CMD2", "kind", "NOPE"))
                    .getLocation().toString().contains("noticeOk=false"));
            assertTrue(resource.createOrder(form("code", "CMD2", "kind", "ORDER", "totalAmount", "-1"))
                    .getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * A valid create persists an OPEN order and hands out the code.
     */
    @Test
    void createPersistsAnOpenOrder() {
        AdminSalesOrderResource resource = newResource();
        PanacheQuery<CustomerOrder> absent = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CustomerOrder> created = mockConstruction(CustomerOrder.class)) {
            panache.when(() -> CustomerOrder.find("code", "CMD9")).thenReturn(absent);
            Response response = resource.createOrder(form("code", "CMD9", "kind", "QUOTE",
                    "label", "Cuisine", "customerAccountNumber", "CC1", "totalAmount", "1200,00"));
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
            CustomerOrder order = created.constructed().get(0);
            assertEquals("CMD9", order.code);
            assertEquals(OrderStatus.OPEN, order.status);
            assertEquals(new BigDecimal("1200.00"), order.totalAmount);
            verify(order, times(1)).persist();
        }
    }

    /**
     * Deposit refuses an unknown order and a service refusal, and confirms a
     * recorded deposit (three arms).
     */
    @Test
    void depositDelegatesToTheService() {
        AdminSalesOrderResource resource = newResource();
        CustomerOrder order = new CustomerOrder();
        order.code = "CMD1";
        PanacheQuery<CustomerOrder> absent = query(null);
        PanacheQuery<CustomerOrder> present = query(order);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CustomerOrder.find("code", "GHOST")).thenReturn(absent);
            assertTrue(resource.recordDeposit(form("code", "GHOST", "amount", "5"))
                    .getLocation().toString().contains("noticeOk=false"));
            panache.when(() -> CustomerOrder.find("code", "CMD1")).thenReturn(present);
            when(resource.customerOrderService.applyDeposit(any(), any())).thenReturn(false);
            assertTrue(resource.recordDeposit(form("code", "CMD1", "amount", "5"))
                    .getLocation().toString().contains("noticeOk=false"));
            when(resource.customerOrderService.applyDeposit(any(), any())).thenReturn(true);
            assertTrue(resource.recordDeposit(form("code", "CMD1", "amount", "5"))
                    .getLocation().toString().contains("noticeOk=true"));
        }
    }

    /**
     * Cancel refuses an unknown order and one no longer recallable, and cancels a
     * recallable one (three arms).
     */
    @Test
    void cancelCoversItsArms() {
        AdminSalesOrderResource resource = newResource();
        CustomerOrder paid = new CustomerOrder();
        paid.status = OrderStatus.PAID;
        CustomerOrder open = new CustomerOrder();
        open.code = "OPEN";
        open.status = OrderStatus.OPEN;
        PanacheQuery<CustomerOrder> absent = query(null);
        PanacheQuery<CustomerOrder> paidQ = query(paid);
        PanacheQuery<CustomerOrder> openQ = query(open);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CustomerOrder.find("code", "GHOST")).thenReturn(absent);
            assertTrue(resource.cancelOrder(form("code", "GHOST"))
                    .getLocation().toString().contains("noticeOk=false"));
            panache.when(() -> CustomerOrder.find("code", "PAID")).thenReturn(paidQ);
            assertTrue(resource.cancelOrder(form("code", "PAID"))
                    .getLocation().toString().contains("noticeOk=false"));
            panache.when(() -> CustomerOrder.find("code", "OPEN")).thenReturn(openQ);
            assertTrue(resource.cancelOrder(form("code", "OPEN"))
                    .getLocation().toString().contains("noticeOk=true"));
            assertEquals(OrderStatus.CANCELLED, open.status);
        }
    }
}
