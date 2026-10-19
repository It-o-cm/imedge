package com.intermarche.edge.imports;

import com.intermarche.edge.service.ExternalRelayService;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The SMS DIGITAL-RECEIPT endpoint ({@code POST /api/sms/send}, EXP-48): a
 * register asks the store node to text a customer their dematerialized receipt;
 * the node relays it to the SMS gateway it is configured with.
 *
 * <p>Machine surface with the node-role-plus-token gate. When no gateway is
 * configured, it answers {@code {sent:false}} — the receipt simply is not texted
 * rather than the sale failing on a missing integration.
 */
@Path("/api/sms")
public class SmsResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SmsResource.class);

    /** The role of this node: only a "store" node relays SMS. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** Shared token of the inbound endpoint; absent = open. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** The SMS gateway URL the node relays to; absent = disabled. */
    @ConfigProperty(name = "pos.sms.gateway-url")
    Optional<String> gatewayUrl;

    /** Read timeout in milliseconds. */
    @ConfigProperty(name = "pos.sms.timeout-millis", defaultValue = "3000")
    long timeoutMillis;

    /** The shared upstream relay. */
    @Inject
    ExternalRelayService relay;

    /**
     * Sends a customer's receipt by SMS for a connected register.
     *
     * @param body the send payload ({@code {ticket, phone}}) forwarded as-is
     * @param presentedToken the shared token presented by the register
     * @return 200 with the gateway answer or {@code {sent:false}}, 401 on a bad
     *         token, 403 off-role
     */
    @POST
    @Path("/send")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response send(String body, @HeaderParam("X-Sync-Token") String presentedToken) {
        LOGGER.info("Entering method send");
        if (!"store".equalsIgnoreCase(role)) {
            LOGGER.info("Exiting method send: off-role");
            return Response.status(Response.Status.FORBIDDEN).entity("Ce nœud n'a pas le rôle store").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            LOGGER.info("Exiting method send: bad token");
            return Response.status(Response.Status.UNAUTHORIZED).entity("Jeton invalide").build();
        }
        ExternalRelayService.RelayResult result = relay.relay(gatewayUrl, "POST", null, body, timeoutMillis);
        if (!result.reached()) {
            LOGGER.info("Exiting method send: gateway unreachable, not sent");
            return Response.ok(Map.of("sent", false)).build();
        }
        LOGGER.info("Exiting method send");
        return Response.status(result.status()).entity(result.body()).build();
    }
}
