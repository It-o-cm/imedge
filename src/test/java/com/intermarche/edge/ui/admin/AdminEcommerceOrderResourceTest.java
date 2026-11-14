package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.sale.EcommerceOrder;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminEcommerceOrderResource}: the page lists the orders
 * the platform deposited, most recent first.
 */
class AdminEcommerceOrderResourceTest {

    /**
     * The page hands the template the orders of the listing, the returns the
     * platform recorded (DG-01-03) and the revenue of the day by sales
     * channel (BO-07-01-02).
     */
    @Test
    void thePageListsTheOrders() {
        AdminEcommerceOrderResource resource = new AdminEcommerceOrderResource();
        resource.adminEcommerceOrders = mock(Template.class);
        TemplateInstance page = mock(TemplateInstance.class);
        List<EcommerceOrder> orders = List.of(new EcommerceOrder());
        when(resource.adminEcommerceOrders.data("orders", orders)).thenReturn(page);
        resource.channelRevenueReport = mock(com.intermarche.edge.ui.report.ChannelRevenueReport.class);
        List<List<Object>> revenue = List.of(List.of("E-commerce", 1L));
        when(resource.channelRevenueReport.rows(org.mockito.ArgumentMatchers.any())).thenReturn(revenue);
        when(page.data("revenue", revenue)).thenReturn(page);
        when(page.data("returns", orders)).thenReturn(page);
        try (MockedStatic<PanacheEntityBase> base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> EcommerceOrder.list("order by receivedAt desc, id desc")).thenReturn(orders);
            assertSame(page, resource.ordersPage());
        }
        org.mockito.Mockito.verify(page).data("revenue", revenue);
        org.mockito.Mockito.verify(page).data("returns", orders);
    }
}
