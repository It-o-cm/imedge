package com.intermarche.edge.imports;

import com.intermarche.edge.domain.sale.CustomerOrder;
import com.intermarche.edge.domain.session.CashMovement;
import com.intermarche.edge.service.CustomerOrderService;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
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
 * The till-facing ORDER surface ({@code /api/orders}) — the connected mode of
 * onglet G and of the till-created order (LC-06-03): a register READS the code
 * the back office handed the customer (EXP-90/91/92), a register that just
 * took a deposit on an order it built at the till WRITES that order here
 * (LC-06-03-01), and a register that settled the order at the till CLOSES it
 * here (LC-06-03-07).
 *
 * <p>A machine surface, so it keeps its OWN gate — the node role plus the shared
 * sync token — exactly like the supervisor-call inbound endpoint, rather than the
 * back-office form login. It only ever recalls an order still recallable (open or
 * partially paid) through {@link CustomerOrderService#recall}; anything else is a
 * 404, so a paid, collected, cancelled or unknown code cannot be replayed. The
 * ingest is IDEMPOTENT by code — a register whose push was answered but never
 * acknowledged pushes again, and the second push lands on the same row rather
 * than a duplicate.
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
     * Ingests the order a register just created at its till (LC-06-03-01).
     *
     * <p>The register serialized its own recalled-document shape — kind, code,
     * lines, total and the deposit's per-rate VAT detail — and the node stores
     * it as the order it is: back-office listed, recallable at any register of
     * the store by its code (LC-06-03-07), and carrying the deposit's VAT for
     * the period-end suspense accounts (LC-06-03-05). A blank code, an
     * unknown kind or a null body answers 400 and nothing is written; a
     * repeated code overwrites the row it names, never a duplicate.
     *
     * @param dto the order the register pushed
     * @param presentedToken the shared token presented by the register
     * @return 200 when the order was stored, 400 on an unreadable push, 401 on
     *         a bad token, 403 off-role
     */
    @POST
    @Path("/")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    @Transactional
    public Response ingest(@HeaderParam("X-Sync-Token") String presentedToken, OrderPushDto dto) {
        LOGGER.info("Entering method ingest with code: " + (dto == null ? null : dto.code));
        if (!"store".equalsIgnoreCase(role)) {
            LOGGER.info("Exiting method ingest: off-role");
            return Response.status(Response.Status.FORBIDDEN).entity("Ce nœud n'a pas le rôle store").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            LOGGER.info("Exiting method ingest: bad token");
            return Response.status(Response.Status.UNAUTHORIZED).entity("Jeton invalide").build();
        }
        if (dto == null || dto.code == null || dto.code.isBlank()
                || com.intermarche.edge.domain.sale.OrderKind.fromName(dto.kind) == null) {
            LOGGER.info("Exiting method ingest: unreadable push");
            return Response.status(Response.Status.BAD_REQUEST).entity("Commande illisible").build();
        }
        CustomerOrder order = customerOrderService.createFromTill(dto.code,
                com.intermarche.edge.domain.sale.OrderKind.fromName(dto.kind),
                dto.totalAmount, dto.depositVatDetail, toOrderLines(dto.lines));
        if (order == null) {
            LOGGER.info("Exiting method ingest: refused");
            return Response.status(Response.Status.BAD_REQUEST).entity("Commande refusée").build();
        }
        LOGGER.info("Exiting method ingest: order " + order.code + " stored");
        return Response.ok("OK").build();
    }

    /**
     * Closes the order a register settled at its till (LC-06-03-07): the balance
     * was paid and the goods went with it, so the node marks the order paid and
     * collected and the code cannot be recalled and paid twice.
     *
     * @param code the code of the settled order
     * @param presentedToken the shared token presented by the register
     * @return 200 when the order stands settled, 404 when the store holds no
     *         such code, 401 on a bad token, 403 off-role
     */
    @POST
    @Path("/{code}/settled")
    @PermitAll
    @Produces(MediaType.TEXT_PLAIN)
    @Transactional
    public Response settled(@PathParam("code") String code,
                            @HeaderParam("X-Sync-Token") String presentedToken) {
        LOGGER.info("Entering method settled with code: " + code);
        if (!"store".equalsIgnoreCase(role)) {
            LOGGER.info("Exiting method settled: off-role");
            return Response.status(Response.Status.FORBIDDEN).entity("Ce nœud n'a pas le rôle store").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            LOGGER.info("Exiting method settled: bad token");
            return Response.status(Response.Status.UNAUTHORIZED).entity("Jeton invalide").build();
        }
        CustomerOrder order = CustomerOrder.findByCode(code);
        if (order == null) {
            LOGGER.info("Exiting method settled: unknown code");
            return Response.status(Response.Status.NOT_FOUND).entity("Commande inconnue").build();
        }
        boolean settled = customerOrderService.settleAtTill(order);
        LOGGER.info("Exiting method settled (" + settled + ")");
        return settled ? Response.ok("OK").build()
                : Response.status(Response.Status.CONFLICT).entity("Commande non réglable").build();
    }

    /**
     * Reads the pushed lines into the entity's embedded lines, deriving the
     * display total each line keeps.
     *
     * @param lines the pushed lines, possibly null
     * @return the embedded order lines, never null
     */
    private List<CustomerOrder.OrderLine> toOrderLines(List<OrderPushLineDto> lines) {
        List<CustomerOrder.OrderLine> orderLines = new ArrayList<>();
        if (lines == null) {
            return orderLines;
        }
        for (OrderPushLineDto line : lines) {
            if (line == null) {
                continue;
            }
            java.math.BigDecimal quantity = line.quantity == null
                    ? java.math.BigDecimal.ONE : line.quantity;
            java.math.BigDecimal unitPrice = line.unitPrice == null
                    ? java.math.BigDecimal.ZERO : line.unitPrice;
            java.math.BigDecimal totalPrice = unitPrice.multiply(quantity)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            orderLines.add(new CustomerOrder.OrderLine(line.ean, line.label,
                    quantity, unitPrice, line.vatRate, totalPrice));
        }
        return orderLines;
    }

    /**
     * The order a register pushes at creation (LC-06-03-01), in the register's
     * own recalled-document shape.
     */
    public static class OrderPushDto {

        /** The order code, the recall key. */
        public String code;

        /** The kind name (COMMANDE for a till-created order). */
        public String kind;

        /** The order total, TTC, or null. */
        public java.math.BigDecimal totalAmount;

        /** The per-rate VAT of the deposit taken, or null. */
        public String depositVatDetail;

        /** The order's article lines, never null. */
        public List<OrderPushLineDto> lines = new ArrayList<>();
    }

    /**
     * One article line of a pushed order.
     */
    public static class OrderPushLineDto {

        /** The article EAN, or null. */
        public String ean;

        /** The line label. */
        public String label;

        /** The quantity ordered. */
        public java.math.BigDecimal quantity;

        /** The unit price, in euros. */
        public java.math.BigDecimal unitPrice;

        /** The VAT rate of the line. */
        public java.math.BigDecimal vatRate;
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
        // LC-06-03-08: the deposit's per-rate VAT, ventilated at the till that
        // took it, hands back with the order so the final ticket restates it;
        // null on an order no till ventilated, and the register prints
        // nothing then.
        json.put("depositVatDetail", order.depositVatDetail);
        // Back-office extras, ignored by the register's lenient deserializer. The
        // remaining is recomputed against the full acompte (deposit + till acomptes
        // imputed by order reference), never negative, so it stays consistent with
        // the acompteAlreadyPaid the register actually reads.
        json.put("status", order.status.name());
        java.math.BigDecimal total = order.totalAmount == null ? java.math.BigDecimal.ZERO : order.totalAmount;
        json.put("totalAmount", total);
        java.math.BigDecimal remaining = total.subtract(acompteAlreadyPaid);
        json.put("remaining", remaining.signum() < 0 ? java.math.BigDecimal.ZERO : remaining);
        return json;
    }
}
