package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.sale.CustomerOrder;
import com.intermarche.pos.domain.sale.OrderKind;
import com.intermarche.pos.domain.sale.OrderStatus;
import com.intermarche.pos.service.CustomerOrderService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The CUSTOMER ORDER back office ({@code /admin/orders}) — where the store
 * enters a quote, an order, a sale voucher or a click-and-collect basket and
 * hands the customer its code (onglet G, EXP-90/91/92). The till later recalls
 * the code and settles it in full or by a deposit (EXP-85).
 *
 * <p>The four documents are one entity ({@link CustomerOrder}) told apart by its
 * kind, and the money transitions go through the shared
 * {@link CustomerOrderService} — this screen never re-implements the deposit
 * rule, it delegates it.
 */
@Path("/admin/orders")
public class AdminSalesOrderResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminSalesOrderResource.class);

    /** The redirect target, equal to the resource path. */
    private static final String PAGE = "/admin/orders";

    /** The orders page template. */
    @Inject
    @Location("admin-orders")
    Template adminOrders;

    /** The shared recall-by-code mechanism. */
    @Inject
    CustomerOrderService customerOrderService;

    /**
     * Shows the orders page.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @RolesAllowed("ADMIN")
    @Transactional
    public TemplateInstance ordersPage(@QueryParam("notice") String notice,
                                       @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method ordersPage with notice: " + notice + ", noticeOk: " + noticeOk);
        List<CustomerOrder> orders = CustomerOrder.listAllOrdered();
        LOGGER.info("Exiting method ordersPage");
        return adminOrders.data("orders", orders)
                .data("kinds", OrderKind.values())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Creates an order and hands out its code (EXP-90/91/92).
     *
     * @param form the posted form (code, kind, label, customerAccountNumber, totalAmount)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/create")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response createOrder(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method createOrder with form: " + form);
        String code = AdminForms.text(form, "code");
        if (code == null) {
            LOGGER.info("Exiting method createOrder");
            return AdminForms.redirect(PAGE, "Le code de la commande est obligatoire.", false);
        }
        if (CustomerOrder.findByCode(code) != null) {
            LOGGER.info("Exiting method createOrder");
            return AdminForms.redirect(PAGE, "Une commande porte déjà le code " + code + ".", false);
        }
        OrderKind kind = OrderKind.fromName(AdminForms.text(form, "kind"));
        if (kind == null) {
            LOGGER.info("Exiting method createOrder");
            return AdminForms.redirect(PAGE, "Type de document inconnu.", false);
        }
        BigDecimal total = AdminForms.amount(AdminForms.text(form, "totalAmount"));
        if (total == null || total.signum() < 0) {
            LOGGER.info("Exiting method createOrder");
            return AdminForms.redirect(PAGE, "Le montant total doit être un nombre positif.", false);
        }
        CustomerOrder order = new CustomerOrder();
        order.code = code;
        order.kind = kind;
        order.status = OrderStatus.OPEN;
        order.label = AdminForms.text(form, "label");
        order.customerAccountNumber = AdminForms.text(form, "customerAccountNumber");
        order.totalAmount = total;
        order.depositPaid = BigDecimal.ZERO;
        order.persist();
        LOGGER.info("Exiting method createOrder");
        return AdminForms.redirect(PAGE, kind.getLabel() + " créé(e), code " + code + " remis au client.", true);
    }

    /**
     * Records a deposit on an order (EXP-85), delegating the rule to the service.
     *
     * @param form the posted form (code, amount)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/deposit")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response recordDeposit(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method recordDeposit with form: " + form);
        CustomerOrder order = CustomerOrder.findByCode(AdminForms.text(form, "code"));
        if (order == null) {
            LOGGER.info("Exiting method recordDeposit");
            return AdminForms.redirect(PAGE, "Commande inconnue.", false);
        }
        BigDecimal amount = AdminForms.amount(AdminForms.text(form, "amount"));
        if (!customerOrderService.applyDeposit(order, amount)) {
            LOGGER.info("Exiting method recordDeposit");
            return AdminForms.redirect(PAGE, "Acompte refusé : montant invalide, ou document non éligible à un acompte.", false);
        }
        LOGGER.info("Exiting method recordDeposit");
        return AdminForms.redirect(PAGE, "Acompte enregistré sur la commande " + order.code + ".", true);
    }

    /**
     * Cancels a recallable order.
     *
     * @param form the posted form (code)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/cancel")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response cancelOrder(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method cancelOrder with form: " + form);
        CustomerOrder order = CustomerOrder.findByCode(AdminForms.text(form, "code"));
        if (order == null) {
            LOGGER.info("Exiting method cancelOrder");
            return AdminForms.redirect(PAGE, "Commande inconnue.", false);
        }
        if (!order.isRecallable()) {
            LOGGER.info("Exiting method cancelOrder");
            return AdminForms.redirect(PAGE, "Commande déjà payée, retirée ou annulée : rien à annuler.", false);
        }
        order.status = OrderStatus.CANCELLED;
        LOGGER.info("Exiting method cancelOrder");
        return AdminForms.redirect(PAGE, "Commande " + order.code + " annulée.", true);
    }
}
