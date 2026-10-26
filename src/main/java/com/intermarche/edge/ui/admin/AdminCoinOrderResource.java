package com.intermarche.edge.ui.admin;

import io.quarkus.security.Authenticated;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.safe.CoinOrder;
import com.intermarche.edge.domain.safe.CoinOrderLine;
import com.intermarche.edge.domain.safe.SafeDenomination;
import com.intermarche.edge.security.CurrentUser;
import com.intermarche.edge.service.SafeService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The COIN ORDER screen ({@code /admin/safe/coin-orders}): stating the coin a
 * shop needs ({@code BO-05-02-04}) and recording what came back
 * ({@code BO-05-02-05}).
 *
 * <p>ONE screen for the two requirements, because the second is unusable without
 * the first: the delivery is checked against the order, denomination by
 * denomination, and an operator recording a delivery is shown what was asked for
 * on the same row they type what arrived.
 *
 * <p>The order PRINTS — that is what the requirement means by a document
 * transmissible to the bank — and printing it changes nothing: only the delivery
 * moves the safe.
 *
 * <p>Access reserved to ADMIN; the profile grant {@code safe_coin_order} is what
 * puts it in an operator's menu.
 */
@Path("/admin/safe/coin-orders")
public class AdminCoinOrderResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminCoinOrderResource.class);

    /** The coin-order list template. */
    @Inject
    @Location("admin-coin-orders")
    Template adminCoinOrders;

    /** The safe: the delivery is written through it. */
    @Inject
    SafeService safeService;

    /** Who is asking, so an order can name who entered it. */
    @Inject
    CurrentUser currentUser;

    /**
     * Shows the orders, the ones still waiting for their delivery first.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the orders page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_COIN_ORDER)
    public TemplateInstance list(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list");
        List<CoinOrder> orders = CoinOrder.listNewestFirst();
        LOGGER.info("Exiting method list with " + orders.size() + " order(s)");
        return adminCoinOrders.data("orders", orders)
                .data("awaiting", CoinOrder.awaitingDelivery())
                .data("denominations", SafeDenomination.values())
                .data("today", LocalDate.now().toString())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Enters an order ({@code BO-05-02-04}).
     *
     * @param form the posted form
     * @return a 303 back to the list with the notice
     */
    @POST
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_COIN_ORDER, option = CrudOption.CREATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response order(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method order");
        String number = AdminForms.text(form, "orderNumber");
        if (number == null) {
            LOGGER.info("Exiting method order: no number");
            return back("Numéro de demande obligatoire.", false);
        }
        if (CoinOrder.find("orderNumber", number).firstResult() != null) {
            LOGGER.info("Exiting method order: duplicate number");
            return back("Ce numéro de demande existe déjà : " + number, false);
        }
        List<CoinOrderLine> lines = requestedLines(form);
        if (lines.isEmpty()) {
            LOGGER.info("Exiting method order: no quantity");
            return back("Aucune quantité demandée.", false);
        }
        CoinOrder created = new CoinOrder();
        created.orderNumber = number;
        created.label = AdminForms.text(form, "label");
        LocalDate day = AdminForms.day(form.getFirst("requestedOn"));
        created.requestedOn = day == null ? LocalDate.now() : day;
        created.requestedBy = signedIn();
        created.status = CoinOrder.OrderStatus.REQUESTED;
        created.lines = lines;
        created.persist();
        LOGGER.infof("Demande de monnaie %s saisie pour %s", number, created.requestedAmount());
        LOGGER.info("Exiting method order");
        return back("Demande de monnaie enregistrée.", true);
    }

    /**
     * Records the delivery of an order ({@code BO-05-02-05}).
     *
     * @param form the posted form
     * @return a 303 back to the list with the notice
     */
    @POST
    @Path("/receive")
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_COIN_ORDER, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response receive(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method receive");
        Long id = AdminForms.id(form.getFirst("id"));
        CoinOrder order = id == null ? null : CoinOrder.<CoinOrder>findById(id);
        if (order == null) {
            LOGGER.info("Exiting method receive: unknown order");
            return back("Demande inconnue.", false);
        }
        applyReceived(order, form);
        String refusal = safeService.receiveCoinOrder(order, signedIn());
        if (refusal != null) {
            LOGGER.info("Exiting method receive: " + refusal);
            return back(refusal, false);
        }
        LOGGER.info("Exiting method receive");
        return back(order.isFullyFulfilled()
                ? "Réception enregistrée, conforme à la demande."
                : "Réception enregistrée, différente de la demande.", true);
    }

    /**
     * Abandons an order nothing was ever delivered against
     * ({@code BO-05-02-04}).
     *
     * @param form the posted form
     * @return a 303 back to the list with the notice
     */
    @POST
    @Path("/cancel")
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_COIN_ORDER, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response cancel(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method cancel");
        Long id = AdminForms.id(form.getFirst("id"));
        CoinOrder order = id == null ? null : CoinOrder.<CoinOrder>findById(id);
        if (order == null || !order.isAwaitingDelivery()) {
            LOGGER.info("Exiting method cancel: nothing to cancel");
            return back("Cette demande ne peut plus être annulée.", false);
        }
        order.status = CoinOrder.OrderStatus.CANCELLED;
        order.persist();
        LOGGER.info("Exiting method cancel");
        return back("Demande annulée.", true);
    }

    /**
     * Prints an order as the slip handed to the bank ({@code BO-05-02-04}).
     *
     * <p>Plain text and not a PDF: the slip is a list of denominations and
     * quantities that the bank or the cash carrier reads, and every station this
     * runs on can print text.
     *
     * @param id the order to print
     * @return the slip, or a 404 when the order is unknown
     */
    @GET
    @Path("/{id}/slip")
    @Authenticated
    @FeatureAccess(value = Feature.SAFE_COIN_ORDER)
    @Produces("text/plain; charset=UTF-8")
    public Response slip(@PathParam("id") Long id) {
        LOGGER.info("Entering method slip with id: " + id);
        CoinOrder order = id == null ? null : CoinOrder.<CoinOrder>findById(id);
        if (order == null) {
            LOGGER.info("Exiting method slip: unknown order");
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        LOGGER.info("Exiting method slip");
        return Response.ok(renderSlip(order))
                .header("Content-Disposition",
                        "attachment; filename=\"demande-monnaie-" + order.orderNumber + ".txt\"")
                .build();
    }

    /**
     * Lays the order out as the slip the bank receives.
     *
     * @param order the order printed
     * @return the slip, one line per denomination
     */
    String renderSlip(CoinOrder order) {
        StringBuilder slip = new StringBuilder();
        slip.append("DEMANDE DE MONNAIE ").append(order.orderNumber).append('\n');
        slip.append("Date : ").append(order.requestedOn).append('\n');
        if (order.label != null && !order.label.isBlank()) {
            slip.append("Objet : ").append(order.label).append('\n');
        }
        slip.append('\n');
        for (CoinOrderLine line : order.lines) {
            slip.append(String.format("%-20s %6d %12s%n", line.getDenominationLabel(),
                    line.requestedQuantity,
                    line.requestedAmount().setScale(2, java.math.RoundingMode.HALF_UP)
                            .toPlainString()));
        }
        slip.append('\n');
        slip.append("TOTAL : ").append(order.getRequestedAmountFormatted()).append(" EUR\n");
        return slip.toString();
    }

    /**
     * Reads the quantities asked for, dropping the denominations left blank.
     *
     * @param form the posted form
     * @return the lines ordered, never null, possibly empty
     */
    List<CoinOrderLine> requestedLines(MultivaluedMap<String, String> form) {
        List<CoinOrderLine> lines = new ArrayList<>();
        for (SafeDenomination denomination : SafeDenomination.values()) {
            int quantity = AdminForms.integer(form.getFirst("qty" + denomination.name()), 0);
            if (quantity > 0) {
                lines.add(new CoinOrderLine(denomination, quantity));
            }
        }
        return lines;
    }

    /**
     * Writes the quantities that came back onto the order's own lines.
     *
     * <p>A denomination the delivery does not name gets zero and not null: the
     * bank sent nothing of it, which is a fact, where null would mean nobody
     * looked.
     *
     * @param order the order the delivery answers
     * @param form the posted form
     */
    void applyReceived(CoinOrder order, MultivaluedMap<String, String> form) {
        for (CoinOrderLine line : order.lines) {
            if (line.denomination == null) {
                continue;
            }
            line.receivedQuantity =
                    AdminForms.integer(form.getFirst("rcv" + line.denomination.name()), 0);
        }
    }

    /**
     * The employee signed in, as an order names them.
     *
     * @return the employee, or null when the session names nobody known
     */
    Employee signedIn() {
        String login = currentUser == null ? null : currentUser.getName();
        if (login == null || login.isBlank()) {
            return null;
        }
        return Employee.<Employee>find("loginName", login).firstResult();
    }

    /**
     * Builds the 303 back to this screen.
     *
     * @param notice the message
     * @param ok whether it reports a success
     * @return the redirect
     */
    private Response back(String notice, boolean ok) {
        return AdminForms.redirect("/admin/safe/coin-orders", notice, ok);
    }
}
