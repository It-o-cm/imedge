package com.intermarche.edge.imports;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.sale.ThirdPartySystem;
import com.intermarche.edge.service.EcommerceBasketService;
import com.intermarche.edge.service.EcommerceIntegrationService;
import com.intermarche.edge.service.EcommerceOrderViewService;
import com.intermarche.edge.service.EcommerceReturnService;
import com.intermarche.edge.service.PosSettingsService;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The e-commerce platform's surface on the store node ({@code /api/ecommerce})
 * — BO-10-01-03: the platform deposits each VALIDATED order and receives, order
 * by order, the report of its integration into the store's encashment; it may
 * read that report again later by the order's reference.
 *
 * <p>A machine surface, gated like the register-facing ones: the node role plus
 * the shared sync token. The order is read here, as text, so the very bytes the
 * platform sent can be archived beside the report (BO-10-01-11); an unreadable
 * order — no reference, a reference that cannot name a ticket, no line, a null
 * line, a line whose quantity is zero or less — answers 400 and nothing is
 * written; an order carrying its final basket may carry no line (DG-01-01-06). Every business outcome — booked,
 * refused, channel not open — is a 200 carrying the report: the platform reads
 * the outcome in the report, not in a status code.
 *
 * <p>BO-07-01-01: a DECLARED third-party system presents its own token in
 * {@code X-Integration-Token}; the node recognises the system by it, books its
 * sales under its channel and refuses a token it does not know. Where the
 * store requires it, the shared synchronization token no longer suffices.
 */
@Path("/api/ecommerce")
public class EcommerceOrderResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EcommerceOrderResource.class);

    /** A lenient reader: the platform may send fields this node does not use. */
    private static final ObjectMapper READER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** The role of this node: only a "store" node integrates orders. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** Shared token of the inbound endpoint; absent = open. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** The integration itself. */
    @Inject
    EcommerceIntegrationService integrationService;

    /** Whether a declared system's token is required (BO-07-01-01). */
    @Inject
    PosSettingsService posSettingsService;

    /** Returns each integration status to the platforms (BO-11-04-03). */
    @Inject
    com.intermarche.edge.service.sync.TicketStreamService ticketStreamService;

    /** Values the platform's baskets with the store's engine (BO-11-04-03). */
    @Inject
    com.intermarche.edge.service.EcommerceValuationService ecommerceValuationService;

    /** Relays the basket session and the return valuation to the engine (DG-01-01, DG-01-03). */
    @Inject
    EcommerceBasketService ecommerceBasketService;

    /** Records the platform's returns at the point of sale (DG-01-03). */
    @Inject
    EcommerceReturnService ecommerceReturnService;

    /** Reads the orders as the platform follows them (DG-03-02). */
    @Inject
    EcommerceOrderViewService ecommerceOrderViewService;

    /**
     * What the gate decided.
     *
     * @param denied the refusal, or null when the caller may go on
     * @param systemCode the declared system the caller is, or null
     */
    record Gate(Response denied, String systemCode) {
    }

    /**
     * Integrates one validated order and answers its report (BO-10-01-03).
     *
     * @param presentedToken the shared token presented by the platform
     * @param systemToken the declared system's token, or null
     * @param body the order, JSON
     * @return 200 with the report, 400 on an unreadable order, 401 on a bad
     *         token, 403 off-role
     */
    @POST
    @Path("/orders")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response integrate(@HeaderParam("X-Sync-Token") String presentedToken,
                              @HeaderParam("X-Integration-Token") String systemToken, String body) {
        LOGGER.info("Entering method integrate");
        Gate gate = gate(presentedToken, systemToken);
        if (gate.denied() != null) {
            LOGGER.info("Exiting method integrate: denied");
            return gate.denied();
        }
        EcommerceIntegrationService.OrderDto order = read(body);
        if (order == null) {
            LOGGER.info("Exiting method integrate: unreadable order");
            return Response.status(Response.Status.BAD_REQUEST).entity("Commande illisible").build();
        }
        EcommerceIntegrationService.Report report = integrationService.integrate(order, body, gate.systemCode());
        // BO-11-04-03: the status of the integration at this point of sale
        // goes back to the platforms, replayed until they acknowledge it.
        ticketStreamService.onEcommerceReport(report);
        LOGGER.info("Exiting method integrate");
        return Response.ok(report).build();
    }

    /**
     * Values a basket of the platform with the store's valuation engine
     * (BO-11-04-03), behind the same gate as the order deposit.
     *
     * @param presentedToken the shared token presented by the platform
     * @param systemToken the declared system's token, or null
     * @param body the valuation request, JSON
     * @return the engine's answer, or the gate's refusal
     */
    @POST
    @Path("/valuation")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response valuate(@HeaderParam("X-Sync-Token") String presentedToken,
                            @HeaderParam("X-Integration-Token") String systemToken, String body) {
        LOGGER.info("Entering method valuate");
        Gate gate = gate(presentedToken, systemToken);
        if (gate.denied() != null) {
            LOGGER.info("Exiting method valuate: denied");
            return gate.denied();
        }
        com.intermarche.edge.service.EcommerceValuationService.Answer answer =
                ecommerceValuationService.valuate(body);
        LOGGER.info("Exiting method valuate");
        return Response.status(answer.status()).entity(answer.body()).build();
    }

    /**
     * Reads an order the node received or holds for the till (BO-10-01-03,
     * DG-03-02-03): its status, what remains due, the ticket that settled it,
     * every payment — the platform's and the till's — and its integration
     * report. Never filtered by "recallable".
     *
     * @param reference the platform's reference or the document's code
     * @param presentedToken the shared token presented by the platform
     * @param systemToken the declared system's token, or null
     * @return 200 with the order's view, 404 when unknown, 401 on a bad token,
     *         403 off-role
     */
    @GET
    @Path("/orders/{reference}")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    @jakarta.transaction.Transactional
    public Response report(@PathParam("reference") String reference,
                           @HeaderParam("X-Sync-Token") String presentedToken,
                           @HeaderParam("X-Integration-Token") String systemToken) {
        LOGGER.info("Entering method report with reference: " + reference);
        Gate gate = gate(presentedToken, systemToken);
        if (gate.denied() != null) {
            LOGGER.info("Exiting method report: denied");
            return gate.denied();
        }
        EcommerceOrderViewService.View view = ecommerceOrderViewService.view(reference);
        LOGGER.info("Exiting method report");
        return view == null
                ? Response.status(Response.Status.NOT_FOUND).entity("Commande inconnue").build()
                : Response.ok(view).build();
    }

    /**
     * Reads the status of an order (DG-03-02-04), whatever it is: never
     * filtered by "recallable", so a paid, collected or cancelled order still
     * answers.
     *
     * @param reference the platform's reference or the document's code
     * @param presentedToken the shared token presented by the platform
     * @param systemToken the declared system's token, or null
     * @return 200 with the status, 404 when unknown, 401 on a bad token, 403 off-role
     */
    @GET
    @Path("/orders/{reference}/status")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    @jakarta.transaction.Transactional
    public Response status(@PathParam("reference") String reference,
                           @HeaderParam("X-Sync-Token") String presentedToken,
                           @HeaderParam("X-Integration-Token") String systemToken) {
        LOGGER.info("Entering method status with reference: " + reference);
        Gate gate = gate(presentedToken, systemToken);
        if (gate.denied() != null) {
            LOGGER.info("Exiting method status: denied");
            return gate.denied();
        }
        EcommerceOrderViewService.View view = ecommerceOrderViewService.view(reference);
        if (view == null) {
            LOGGER.info("Exiting method status: unknown");
            return Response.status(Response.Status.NOT_FOUND).entity("Commande inconnue").build();
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("reference", view.reference);
        status.put("kind", view.kind);
        status.put("status", view.status);
        status.put("total", view.total);
        status.put("remaining", view.remaining);
        status.put("ticketNumber", view.ticketNumber);
        status.put("updatedAt", view.updatedAt);
        LOGGER.info("Exiting method status");
        return Response.ok(status).build();
    }

    /**
     * Lists the closed baskets (DG-03-02-01): the orders integrated as
     * tickets and the documents settled at the till, with their ticket.
     *
     * @param status a status to keep (INTEGRATED, PAID, COLLECTED...), or null
     *     for every closed one
     * @param from the first day, ISO-8601, or null
     * @param to the last day, ISO-8601, or null
     * @param channel ECOMMERCE or MOBILITY, or null for both
     * @param presentedToken the shared token presented by the platform
     * @param systemToken the declared system's token, or null
     * @return 200 with the orders, 400 on an unreadable day, 401 on a bad
     *     token, 403 off-role
     */
    @GET
    @Path("/orders")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    @jakarta.transaction.Transactional
    public Response orders(@QueryParam("status") String status, @QueryParam("from") String from,
                           @QueryParam("to") String to, @QueryParam("channel") String channel,
                           @HeaderParam("X-Sync-Token") String presentedToken,
                           @HeaderParam("X-Integration-Token") String systemToken) {
        LOGGER.info("Entering method orders");
        Gate gate = gate(presentedToken, systemToken);
        if (gate.denied() != null) {
            LOGGER.info("Exiting method orders: denied");
            return gate.denied();
        }
        LocalDate first;
        LocalDate last;
        try {
            first = blank(from) ? null : LocalDate.parse(from.trim());
            last = blank(to) ? null : LocalDate.parse(to.trim());
        } catch (DateTimeParseException e) {
            LOGGER.info("Exiting method orders: unreadable day");
            return Response.status(Response.Status.BAD_REQUEST).entity("Date illisible (AAAA-MM-JJ attendu)").build();
        }
        List<EcommerceOrderViewService.View> views = ecommerceOrderViewService.closed(
                blank(status) ? null : status, first, last, blank(channel) ? null : channel.trim());
        LOGGER.info("Exiting method orders");
        return Response.ok(views).build();
    }

    /**
     * Lists the baskets waiting for their payment (DG-03-02-02): the
     * e-commerce, mobility and click-and-collect documents the store holds
     * open or partially paid for the till. The platform's own baskets in
     * progress are the platform's: the store keeps none.
     *
     * @param status PENDING, or null
     * @param presentedToken the shared token presented by the platform
     * @param systemToken the declared system's token, or null
     * @return 200 with the baskets, 400 on another status, 401 on a bad token,
     *     403 off-role
     */
    @GET
    @Path("/baskets")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    @jakarta.transaction.Transactional
    public Response baskets(@QueryParam("status") String status,
                            @HeaderParam("X-Sync-Token") String presentedToken,
                            @HeaderParam("X-Integration-Token") String systemToken) {
        LOGGER.info("Entering method baskets with status: " + status);
        Gate gate = gate(presentedToken, systemToken);
        if (gate.denied() != null) {
            LOGGER.info("Exiting method baskets: denied");
            return gate.denied();
        }
        if (!blank(status) && !"PENDING".equalsIgnoreCase(status.trim())) {
            LOGGER.info("Exiting method baskets: unknown status");
            return Response.status(Response.Status.BAD_REQUEST).entity("Statut de panier inconnu (PENDING attendu)").build();
        }
        List<EcommerceOrderViewService.View> pending = ecommerceOrderViewService.pending();
        LOGGER.info("Exiting method baskets");
        return Response.ok(pending).build();
    }

    /**
     * Opens a basket (DG-01-01-01): the platform's basket is valued in
     * progress by the store's engine, its card promotions read from the
     * loyalty programme; nothing is stored.
     *
     * @param presentedToken the shared token presented by the platform
     * @param systemToken the declared system's token, or null
     * @param body the platform's basket, JSON
     * @return the valued basket, or the gate's refusal
     */
    @POST
    @Path("/baskets")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response openBasket(@HeaderParam("X-Sync-Token") String presentedToken,
                               @HeaderParam("X-Integration-Token") String systemToken, String body) {
        LOGGER.info("Entering method openBasket");
        Gate gate = gate(presentedToken, systemToken);
        Response response = gate.denied() != null ? gate.denied()
                : outcome(ecommerceBasketService.open(body, gate.systemCode()));
        LOGGER.info("Exiting method openBasket");
        return response;
    }

    /**
     * Modifies a basket (DG-01-01-04, DG-01-02-01): the whole basket the
     * platform sends is valued again.
     *
     * @param id the platform's basket identifier
     * @param presentedToken the shared token presented by the platform
     * @param systemToken the declared system's token, or null
     * @param body the platform's basket, JSON, with its state
     * @return the valued basket, or the gate's refusal
     */
    @PUT
    @Path("/baskets/{id}")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response replaceBasket(@PathParam("id") String id, @HeaderParam("X-Sync-Token") String presentedToken,
                                  @HeaderParam("X-Integration-Token") String systemToken, String body) {
        LOGGER.info("Entering method replaceBasket with id: " + id);
        Gate gate = gate(presentedToken, systemToken);
        Response response = gate.denied() != null ? gate.denied()
                : outcome(ecommerceBasketService.modify(id, body, gate.systemCode()));
        LOGGER.info("Exiting method replaceBasket");
        return response;
    }

    /**
     * Deletes a basket (DG-01-01-05): nothing was consumed before the close,
     * nothing is stored, so the gesture only checks the state.
     *
     * @param id the platform's basket identifier
     * @param status the state the platform states, or null
     * @param presentedToken the shared token presented by the platform
     * @param systemToken the declared system's token, or null
     * @return DELETED, 409 for a closed basket, or the gate's refusal
     */
    @DELETE
    @Path("/baskets/{id}")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteBasket(@PathParam("id") String id, @QueryParam("status") String status,
                                 @HeaderParam("X-Sync-Token") String presentedToken,
                                 @HeaderParam("X-Integration-Token") String systemToken) {
        LOGGER.info("Entering method deleteBasket with id: " + id);
        Gate gate = gate(presentedToken, systemToken);
        Response response = gate.denied() != null ? gate.denied() : outcome(ecommerceBasketService.delete(id, status));
        LOGGER.info("Exiting method deleteBasket");
        return response;
    }

    /**
     * Puts a basket on hold (DG-01-01-03): valued and answered without any
     * commitment.
     *
     * @param id the platform's basket identifier
     * @param presentedToken the shared token presented by the platform
     * @param systemToken the declared system's token, or null
     * @param body the platform's basket, JSON, with its state and {@code reason}
     * @return the valued basket ON_HOLD, or the gate's refusal
     */
    @POST
    @Path("/baskets/{id}/hold")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response holdBasket(@PathParam("id") String id, @HeaderParam("X-Sync-Token") String presentedToken,
                               @HeaderParam("X-Integration-Token") String systemToken, String body) {
        LOGGER.info("Entering method holdBasket with id: " + id);
        Gate gate = gate(presentedToken, systemToken);
        Response response = gate.denied() != null ? gate.denied()
                : outcome(ecommerceBasketService.hold(id, body, gate.systemCode()));
        LOGGER.info("Exiting method holdBasket");
        return response;
    }

    /**
     * Resumes a basket on hold (DG-01-01-02): valued again at the current
     * prices and offers, the card promotions read again.
     *
     * @param id the platform's basket identifier
     * @param presentedToken the shared token presented by the platform
     * @param systemToken the declared system's token, or null
     * @param body the platform's basket, JSON, with its state
     * @return the valued basket OPEN, or the gate's refusal
     */
    @POST
    @Path("/baskets/{id}/resume")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response resumeBasket(@PathParam("id") String id, @HeaderParam("X-Sync-Token") String presentedToken,
                                 @HeaderParam("X-Integration-Token") String systemToken, String body) {
        LOGGER.info("Entering method resumeBasket with id: " + id);
        Gate gate = gate(presentedToken, systemToken);
        Response response = gate.denied() != null ? gate.denied()
                : outcome(ecommerceBasketService.resume(id, body, gate.systemCode()));
        LOGGER.info("Exiting method resumeBasket");
        return response;
    }

    /**
     * Closes a basket (DG-01-01-06): the platform sends its final basket with
     * the order; the order is integrated valued — the basket valued closed,
     * the ticket booked from that valuation, the SALE consumption declared —
     * and the report answered with the closing snapshot the platform keeps
     * for a return.
     *
     * @param id the platform's basket identifier
     * @param presentedToken the shared token presented by the platform
     * @param systemToken the declared system's token, or null
     * @param body the order ({@code orderReference} or {@code reference},
     *     {@code basket}, payments...), JSON
     * @return 200 with the report, 400 on an unreadable order, 409 when the
     *     basket is not open, or the gate's refusal
     */
    @POST
    @Path("/baskets/{id}/close")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response closeBasket(@PathParam("id") String id, @HeaderParam("X-Sync-Token") String presentedToken,
                                @HeaderParam("X-Integration-Token") String systemToken, String body) {
        LOGGER.info("Entering method closeBasket with id: " + id);
        Gate gate = gate(presentedToken, systemToken);
        if (gate.denied() != null) {
            LOGGER.info("Exiting method closeBasket: denied");
            return gate.denied();
        }
        com.fasterxml.jackson.databind.node.ObjectNode tree;
        try {
            tree = (com.fasterxml.jackson.databind.node.ObjectNode) READER.readTree(body);
        } catch (Exception e) {
            tree = null;
        }
        if (tree == null || !EcommerceBasketService.validId(id) || !tree.path("basket").isObject()) {
            LOGGER.info("Exiting method closeBasket: unreadable");
            return Response.status(Response.Status.BAD_REQUEST).entity("Commande illisible").build();
        }
        String state = tree.path("basket").path("status").asText("");
        if (!state.isBlank() && !EcommerceBasketService.OPEN.equalsIgnoreCase(state.trim())) {
            LOGGER.info("Exiting method closeBasket: not open");
            return Response.status(Response.Status.CONFLICT).entity("Clôture refusée : panier " + state.trim()).build();
        }
        if (!tree.hasNonNull("reference")) {
            tree.put("reference", tree.path("orderReference").asText(null));
        }
        tree.put("basketId", id.trim());
        EcommerceIntegrationService.OrderDto order = read(tree.toString());
        if (order == null) {
            LOGGER.info("Exiting method closeBasket: unreadable order");
            return Response.status(Response.Status.BAD_REQUEST).entity("Commande illisible").build();
        }
        EcommerceIntegrationService.Report report = integrationService.integrate(order, body, gate.systemCode());
        ticketStreamService.onEcommerceReport(report);
        LOGGER.info("Exiting method closeBasket");
        return Response.ok(report).build();
    }

    /**
     * Values a return with the engine (DG-01-03-01, -03, -04, -06, -07, -08),
     * without recording it.
     *
     * @param presentedToken the shared token presented by the platform
     * @param systemToken the declared system's token, or null
     * @param body the return valuation request, JSON
     * @return the engine's answer, or the gate's refusal
     */
    @POST
    @Path("/returns/valuation")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response valueReturn(@HeaderParam("X-Sync-Token") String presentedToken,
                                @HeaderParam("X-Integration-Token") String systemToken, String body) {
        LOGGER.info("Entering method valueReturn");
        Gate gate = gate(presentedToken, systemToken);
        Response response = gate.denied() != null ? gate.denied()
                : outcome(ecommerceBasketService.valueReturn(body, gate.systemCode()));
        LOGGER.info("Exiting method valueReturn");
        return response;
    }

    /**
     * Records a return of the platform at the point of sale (DG-01-03): reason
     * and return type checked, amounts valued, refund booked and streamed,
     * loyalty told; idempotent by return reference.
     *
     * @param presentedToken the shared token presented by the platform
     * @param systemToken the declared system's token, or null
     * @param body the return, JSON
     * @return 200 with the return report, 400 on an unreadable or refused
     *     return, 404 on an unknown order, 401 on a bad token, 403 off-role
     */
    @POST
    @Path("/returns")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response recordReturn(@HeaderParam("X-Sync-Token") String presentedToken,
                                 @HeaderParam("X-Integration-Token") String systemToken, String body) {
        LOGGER.info("Entering method recordReturn");
        Gate gate = gate(presentedToken, systemToken);
        if (gate.denied() != null) {
            LOGGER.info("Exiting method recordReturn: denied");
            return gate.denied();
        }
        EcommerceReturnService.ReturnDto request;
        try {
            request = READER.readValue(body, EcommerceReturnService.ReturnDto.class);
        } catch (Exception e) {
            request = null;
        }
        if (request == null) {
            LOGGER.info("Exiting method recordReturn: unreadable");
            return Response.status(Response.Status.BAD_REQUEST).entity("Retour illisible").build();
        }
        EcommerceReturnService.Outcome outcome = ecommerceReturnService.record(request, body);
        LOGGER.info("Exiting method recordReturn");
        return Response.status(outcome.status()).entity(outcome.body()).build();
    }

    /**
     * Answers again the report of a return the node recorded (DG-01-03).
     *
     * @param reference the platform's return reference
     * @param presentedToken the shared token presented by the platform
     * @param systemToken the declared system's token, or null
     * @return 200 with the report, 404 when never recorded, 401 on a bad token,
     *     403 off-role
     */
    @GET
    @Path("/returns/{reference}")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    public Response returnReport(@PathParam("reference") String reference,
                                 @HeaderParam("X-Sync-Token") String presentedToken,
                                 @HeaderParam("X-Integration-Token") String systemToken) {
        LOGGER.info("Entering method returnReport with reference: " + reference);
        Gate gate = gate(presentedToken, systemToken);
        if (gate.denied() != null) {
            LOGGER.info("Exiting method returnReport: denied");
            return gate.denied();
        }
        EcommerceReturnService.ReturnReport report = ecommerceReturnService.reportOf(reference);
        LOGGER.info("Exiting method returnReport");
        return report == null
                ? Response.status(Response.Status.NOT_FOUND).entity("Retour inconnu").build()
                : Response.ok(report).build();
    }

    /**
     * Answers the engine's outcome, its warning in the {@code Warning} header.
     *
     * @param outcome the engine's outcome
     * @return the response
     */
    static Response outcome(EcommerceBasketService.Outcome outcome) {
        Response.ResponseBuilder response = Response.status(outcome.status()).entity(outcome.body());
        if (outcome.warning() != null) {
            response.header("Warning", outcome.warning());
        }
        return response.build();
    }

    /**
     * Whether a query value is absent.
     *
     * @param value the value, possibly null
     * @return true when null or blank
     */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Serves an e-commerce ticket to the register a customer brings it back to
     * (BO-10-01-04, -05), so the return runs under the e-commerce return rules.
     *
     * @param number the ticket number
     * @param presentedToken the shared token presented by the register
     * @return 200 with the ticket, 404 when the node holds no such e-commerce
     *         ticket, 401 on a bad token, 403 off-role
     */
    @GET
    @Path("/tickets/{number}")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    @jakarta.transaction.Transactional
    public Response ticket(@PathParam("number") String number,
                           @HeaderParam("X-Sync-Token") String presentedToken) {
        LOGGER.info("Entering method ticket with number: " + number);
        Response denied = gate(presentedToken);
        if (denied != null) {
            LOGGER.info("Exiting method ticket: denied");
            return denied;
        }
        EcommerceIntegrationService.TicketView ticket = integrationService.ticketOf(number);
        LOGGER.info("Exiting method ticket");
        return ticket == null
                ? Response.status(Response.Status.NOT_FOUND).entity("Ticket e-commerce inconnu").build()
                : Response.ok(ticket).build();
    }

    /**
     * The gate of the order surface (BO-07-01-01): a declared system's token
     * names the system, an unknown one is refused, and where the store
     * requires it the shared token no longer suffices.
     *
     * @param presentedToken the shared token presented
     * @param systemToken the declared system's token, or null
     * @return the decision
     */
    Gate gate(String presentedToken, String systemToken) {
        if (systemToken != null && !systemToken.isBlank()) {
            if (!"store".equalsIgnoreCase(role)) {
                return new Gate(gate(presentedToken), null);
            }
            ThirdPartySystem system = ThirdPartySystem.findByToken(systemToken);
            return system == null
                    ? new Gate(Response.status(Response.Status.UNAUTHORIZED)
                            .entity("Jeton de système tiers inconnu").build(), null)
                    : new Gate(null, system.code);
        }
        Response denied = gate(presentedToken);
        if (denied == null && posSettingsService.integrationTokenRequired()) {
            denied = Response.status(Response.Status.UNAUTHORIZED)
                    .entity("Jeton de système tiers obligatoire").build();
        }
        return new Gate(denied, null);
    }

    /**
     * The role and token gate of the surface.
     *
     * @param presentedToken the token presented
     * @return the refusal, or null when the caller may go on
     */
    private Response gate(String presentedToken) {
        if (!"store".equalsIgnoreCase(role)) {
            return Response.status(Response.Status.FORBIDDEN).entity("Ce nœud n'a pas le rôle store").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Jeton invalide").build();
        }
        return null;
    }

    /**
     * Reads an order, or nothing when it cannot be integrated as sent.
     *
     * @param body the JSON text, possibly null
     * @return the order, its payments never null, or null when unreadable
     */
    private static EcommerceIntegrationService.OrderDto read(String body) {
        EcommerceIntegrationService.OrderDto order;
        try {
            order = READER.readValue(body, EcommerceIntegrationService.OrderDto.class);
        } catch (Exception e) {
            return null;
        }
        if (order == null || order.reference == null
                || !order.reference.trim().matches(EcommerceIntegrationService.REFERENCE_PATTERN)) {
            return null;
        }
        boolean basket = order.basket != null && order.basket.isObject();
        if (order.lines == null) {
            order.lines = new ArrayList<>();
        }
        if (order.lines.isEmpty() && !basket || order.lines.contains(null)) {
            return null;
        }
        // A validated order sells: a quantity of zero or less is a return, which
        // goes through the return process, never through an order (DG defect).
        for (EcommerceIntegrationService.OrderLineDto line : order.lines) {
            if (line.quantity != null && line.quantity.signum() <= 0) {
                return null;
            }
        }
        if (order.payments == null) {
            order.payments = new ArrayList<>();
        }
        return order;
    }
}
