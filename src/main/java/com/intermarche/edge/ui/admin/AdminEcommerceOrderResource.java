package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.sale.EcommerceOrder;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.security.FeatureAccess;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

/**
 * The E-COMMERCE ORDERS back office ({@code /admin/ecommerce-orders}) — where
 * the support reads every order the platform deposited (BO-10-01-03, -11): its
 * reference, what became of it, the ticket it was booked as, the unknown and
 * forbidden articles it carried, its integration report and, where the store
 * archives them, the order exactly as the platform sent it — with the system
 * and channel it came through, the point of sale of origin, the customer's
 * NIF, its fiscal document and its loyalty earn (BO-07-01-01 to -08), above
 * the revenue of the day by sales channel (BO-07-01-02) — and every return
 * the platform recorded at the point of sale, with its reason, its return
 * type and its refund (DG-01-03).
 *
 * <p>Read-only by construction: an integration is the platform's to replay, and
 * an archived order leaves with the end-of-period purge after the administered
 * number of days.
 */
@Path("/admin/ecommerce-orders")
public class AdminEcommerceOrderResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminEcommerceOrderResource.class);

    /** The page template. */
    @Inject
    @Location("admin-ecommerce-orders")
    Template adminEcommerceOrders;

    /** The revenue of the day by sales channel (BO-07-01-02). */
    @Inject
    com.intermarche.edge.ui.report.ChannelRevenueReport channelRevenueReport;

    /**
     * Shows the orders the platform deposited, most recent first.
     *
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.ECOMMERCE_ORDER)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance ordersPage() {
        LOGGER.info("Entering method ordersPage");
        // BO-07-01-02: the e-commerce revenue of the day, distinguished inside
        // the point of sale's revenue it is counted in.
        // DG-01-03: the returns the platform recorded, beside its orders.
        TemplateInstance page = adminEcommerceOrders.data("orders", EcommerceOrder.listAllOrdered())
                .data("returns", com.intermarche.edge.domain.sale.EcommerceReturn.listAllOrdered())
                .data("revenue", channelRevenueReport.rows(new com.intermarche.edge.ui.report.ReportCriteria()));
        LOGGER.info("Exiting method ordersPage");
        return page;
    }
}
