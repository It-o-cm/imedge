package com.intermarche.edge.imports;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.intermarche.edge.domain.sync.RegisterCommand;
import com.intermarche.edge.service.sync.RegisterCommandService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * The MACHINE surface of the order book: where a register collects what the
 * store node has asked of it, and says it has done it (LC-01-02-07,
 * BO-04-03-13).
 *
 * <p>Gated exactly like the referential export it travels beside — store role
 * only, the same optional shared token — because it is the same conversation
 * with the same caller, on the same cycle. A register that can pull its
 * catalogue can collect its orders; nothing else can do either.
 *
 * <p>Answers JSON by hand rather than through a mapper: two fields and a list,
 * on a path a register polls every few seconds.
 */
@Path("/api/commands")
public class RegisterCommandResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(RegisterCommandResource.class);

    /** The role of this node: "register" (default), "store" or "central". */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** Shared token; absent = open. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** The order book. */
    @Inject RegisterCommandService registerCommandService;

    /** Records that a register is still speaking to this node (BO-04-03-05). */
    @Inject com.intermarche.edge.service.sync.RegisterPresenceService registerPresenceService;

    /**
     * Hands a register the orders it has not acknowledged.
     *
     * @param terminalId the register asking
     * @param presentedToken the shared token presented by the register
     * @return the pending orders as JSON, or 401/403 when gated
     */
    @GET
    @Path("/{terminalId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response pending(@PathParam("terminalId") String terminalId,
                            @HeaderParam("X-Sync-Token") String presentedToken) {
        LOGGER.info("Entering method pending with terminalId: " + terminalId);
        Response gate = gate(presentedToken);
        if (gate != null) {
            LOGGER.info("Exiting method pending");
            return gate;
        }
        // BO-04-03-05: the register is speaking, so it is alive. Stamped here
        // and nowhere else: this is the one call every lane makes on its own
        // cycle whatever it is doing, which is exactly what presence means.
        registerPresenceService.touch(terminalId);
        ArrayNode orders = JsonNodeFactory.instance.arrayNode();
        for (RegisterCommand command : registerCommandService.pending(terminalId)) {
            orders.addObject()
                    .put("uid", command.commandUid)
                    .put("type", String.valueOf(command.commandType))
                    .put("issuedBy", command.issuedBy)
                    .put("reason", command.reason);
        }
        LOGGER.info("Exiting method pending");
        return Response.ok(orders.toString()).build();
    }

    /**
     * Records that a register carried an order out.
     *
     * @param commandUid the token the register presented
     * @param presentedToken the shared token presented by the register
     * @return 200 when the order was stamped, 404 when it is unknown or was
     *         already stamped, or 401/403 when gated
     */
    @POST
    @Path("/{commandUid}/ack")
    @Produces(MediaType.APPLICATION_JSON)
    public Response acknowledge(@PathParam("commandUid") String commandUid,
                                @HeaderParam("X-Sync-Token") String presentedToken) {
        LOGGER.info("Entering method acknowledge with commandUid: " + commandUid);
        Response gate = gate(presentedToken);
        if (gate != null) {
            LOGGER.info("Exiting method acknowledge");
            return gate;
        }
        boolean stamped = registerCommandService.acknowledge(commandUid);
        LOGGER.info("Exiting method acknowledge with " + stamped);
        return stamped
                ? Response.ok(acknowledged(true)).build()
                : Response.status(Response.Status.NOT_FOUND).entity(acknowledged(false)).build();
    }

    /**
     * Refuses a caller this node must not serve.
     *
     * @param presentedToken the token the caller presented
     * @return the refusal, or null when the caller may proceed
     */
    private Response gate(String presentedToken) {
        if ("register".equalsIgnoreCase(role)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(ImporterCsvResource.errorAnswer("Ce nœud ne distribue pas d'ordres")).build();
        }
        if (token.isPresent() && !token.get().isBlank() && !token.get().equals(presentedToken)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ImporterCsvResource.errorAnswer("Jeton de synchronisation invalide")).build();
        }
        return null;
    }

    /**
     * Builds the answer of an acknowledgement, by the JSON library.
     *
     * @param stamped whether the order was stamped
     * @return {@code {"acknowledged":stamped}}
     */
    private String acknowledged(boolean stamped) {
        return JsonNodeFactory.instance.objectNode().put("acknowledged", stamped).toString();
    }
}
