package com.intermarche.pos.ui.order;

import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.net.URI;
import org.jboss.logging.Logger;

/**
 * The ORDER-CREATION screen (LC-06-03): the cashier registered the articles
 * of an order as a sale's (LC-06-03-02), keyed the deposit the customer
 * leaves (LC-06-03-03) and the register turns the basket into an order —
 * deposit movement, per-rate VAT, printed slip with its barcode, order
 * pushed to the store node (LC-06-03-01).
 *
 * <p>The screen itself is ONE panel on the basket: the articles as
 * registered, the total they make and the deposit to key. The guards answer
 * on the screen — no session, no article, training mode, an unreadable or
 * oversized deposit — and the creation's success lands on the SALE screen,
 * whose message band carries the order code the customer leaves with.
 */
@Path("/")
public class OrderResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(OrderResource.class);

    /**
     * The localised operator messages: every sentence this screen shows the
     * operator is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /** The order-creation screen template. */
    @Inject
    @Location("order")
    Template order;

    /** The creation flow this screen drives. */
    @Inject
    OrderCreationService orderCreationService;

    /** The register state, holder of the basket the order is made of. */
    @Inject
    PosState state;

    /**
     * Shows the order-creation screen with the basket it will turn into an
     * order and an optional outcome message.
     *
     * @param error an optional error code from a redirect
     * @return the order-creation page
     */
    @GET
    @Path("/order")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance orderPage(@QueryParam("error") String error) {
        LOGGER.info("Entering method orderPage");
        String message = switch (error == null ? "" : error) {
            case "empty" -> i18n.get("order.ticketVide");
            case "amount" -> i18n.get("order.acompteInvalide");
            case "total" -> i18n.get("order.acompteSuperieur");
            case "no-session" -> i18n.get("op.aucuneSessionOuverte");
            case "training" -> i18n.get("op.fonctionIndisponibleFormation");
            default -> null;
        };
        LOGGER.info("Exiting method orderPage");
        return order.data("state", state).data("error", message);
    }

    /**
     * Creates the order from the registered articles and the keyed deposit,
     * then returns to the sale screen — carrying the creation notice and the
     * order code on success, the screen and its refusal message otherwise.
     *
     * @param amountStr the deposit to collect, TTC (French comma tolerated)
     * @return a redirect to the sale screen or back to the order screen
     */
    @POST
    @Path("/action/order/create")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createOrder(@FormParam("amount") String amountStr) {
        LOGGER.info("Entering method createOrder");
        OrderCreationService.Result result =
                orderCreationService.create(state, parseAmount(amountStr));
        if (result.created()) {
            LOGGER.info("Exiting method createOrder (order created)");
            return Response.seeOther(URI.create("/")).build();
        }
        String error = switch (result.outcome()) {
            case NO_SESSION -> "no-session";
            case EMPTY_TICKET -> "empty";
            case TRAINING -> "training";
            case BAD_AMOUNT -> "amount";
            case OVER_TOTAL -> "total";
            default -> "amount";
        };
        LOGGER.info("Exiting method createOrder (refused: " + error + ")");
        return Response.seeOther(URI.create("/order?error=" + error)).build();
    }

    /**
     * Parses a form amount (French comma tolerated) into a BigDecimal.
     *
     * @param value the raw form value
     * @return the parsed amount, or ZERO when blank or invalid
     */
    private BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.replace(",", "."));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
