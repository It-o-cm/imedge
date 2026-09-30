package com.intermarche.pos.imports;

import com.intermarche.pos.domain.sale.CustomerOrder;
import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.service.CustomerOrderService;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The till-facing RECALL endpoint ({@code GET /api/orders/{code}}) — the connected
 * mode of onglet G: a register reads the code the back office handed the customer
 * and the order's lines come back so it can settle them (EXP-90/91/92/85).
 *
 * <p>A machine surface, so it keeps its OWN gate — the node role plus the shared
 * sync token — exactly like the supervisor-call inbound endpoint, rather than the
 * back-office form login. It only ever recalls an order still recallable (open or
 * partially paid) through {@link CustomerOrderService#recall}; anything else is a
 * 404, so a paid, collected, cancelled or unknown code cannot be replayed.
 */
@Path("/api/orders")
public class OrderRecallResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(OrderRecallResource.class);

    /** The role of this node: only a "store" node serves recalls. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** Shared token of the inbound endpoint; absent = open. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** The shared recall-by-code mechanism. */
    @Inject
    CustomerOrderService customerOrderService;

    /**
     * Recalls an order by its code for a connected register.
     *
     * @param code the code handed to the customer
     * @param presentedToken the shared token presented by the register
     * @return 200 with the order JSON, 404 when nothing recallable, 401 on a bad
     *         token, 403 off-role
     */
    @GET
    @Path("/{code}")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response recall(@PathParam("code") String code,
                           @HeaderParam("X-Sync-Token") String presentedToken) {
        LOGGER.info("Entering method recall with code: " + code);
        if (!"store".equalsIgnoreCase(role)) {
            LOGGER.info("Exiting method recall: off-role");
            return Response.status(Response.Status.FORBIDDEN).entity("Ce nœud n'a pas le rôle store").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            LOGGER.info("Exiting method recall: bad token");
            return Response.status(Response.Status.UNAUTHORIZED).entity("Jeton invalide").build();
        }
        CustomerOrder order = customerOrderService.recall(code);
        if (order == null) {
            LOGGER.info("Exiting method recall: nothing recallable");
            return Response.status(Response.Status.NOT_FOUND).entity("Aucune commande rappelable").build();
        }
        LOGGER.info("Exiting method recall");
        return Response.ok(toJson(order)).build();
    }

    /**
     * Renders a recalled order in the shape the register's recalled-document model
     * deserializes (impos {@code RecalledDocument}: {@code kind}, {@code code},
     * {@code lines[]} of {@code {ean,label,quantity,unitPrice,vatRate}},
     * {@code acompteAlreadyPaid}). A few extra fields the back office finds handy
     * (status, remaining) ride along and are ignored by a lenient reader.
     *
     * @param order the recalled order
     * @return the order fields and lines, JSON-ready
     */
    private Map<String, Object> toJson(CustomerOrder order) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("code", order.code);
        json.put("kind", order.kind.name());
        java.math.BigDecimal deposit = order.depositPaid == null ? java.math.BigDecimal.ZERO : order.depositPaid;
        java.math.BigDecimal acompteAlreadyPaid = deposit.add(CashMovement.acompteTotalForOrder(order.code));
        json.put("acompteAlreadyPaid", acompteAlreadyPaid);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (CustomerOrder.OrderLine line : order.lines) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("ean", line.ean == null ? "" : line.ean);
            l.put("label", line.label == null ? "" : line.label);
            l.put("quantity", line.quantity);
            l.put("unitPrice", line.unitPrice);
            l.put("vatRate", line.vatRate);
            lines.add(l);
        }
        json.put("lines", lines);
        // Back-office extras, ignored by the register's lenient deserializer. The
        // remaining is recomputed against the full acompte (deposit + till acomptes
        // imputed by order reference), never negative, so it stays consistent with
        // the acompteAlreadyPaid the register actually reads.
        json.put("status", order.status.name());
        java.math.BigDecimal total = order.totalAmount == null ? java.math.BigDecimal.ZERO : order.totalAmount;
        java.math.BigDecimal remaining = total.subtract(acompteAlreadyPaid);
        json.put("remaining", remaining.signum() < 0 ? java.math.BigDecimal.ZERO : remaining);
        return json;
    }
}
