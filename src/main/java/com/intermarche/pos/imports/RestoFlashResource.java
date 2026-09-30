package com.intermarche.pos.imports;

import com.intermarche.pos.service.ExternalRelayService;
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
 * The RESTO FLASH confirmation endpoint ({@code POST /api/restoflash/confirm},
 * EXP-67): a register confirms a mobile meal-voucher (Resto Flash) payment
 * through the store node, which relays it to the Resto Flash service it is
 * configured with and answers accepted/refused.
 *
 * <p>Machine surface with the node-role-plus-token gate. When the service is not
 * configured, it answers {@code {accepted:false}} — the register then declines
 * the mobile voucher rather than crashing on a missing integration.
 */
@Path("/api/restoflash")
public class RestoFlashResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(RestoFlashResource.class);

    /** The role of this node: only a "store" node relays Resto Flash. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** Shared token of the inbound endpoint; absent = open. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** The Resto Flash service URL the node relays to; absent = disabled. */
    @ConfigProperty(name = "pos.restoflash.url")
    Optional<String> restoFlashUrl;

    /** Read timeout in milliseconds (a register is waiting at payment). */
    @ConfigProperty(name = "pos.restoflash.timeout-millis", defaultValue = "3000")
    long timeoutMillis;

    /** The shared upstream relay. */
    @Inject
    ExternalRelayService relay;

    /**
     * Confirms a Resto Flash payment for a connected register.
     *
     * @param body the confirmation payload ({@code {token, amount}}) forwarded as-is
     * @param presentedToken the shared token presented by the register
     * @return 200 with the service answer or {@code {accepted:false}}, 401 on a bad
     *         token, 403 off-role
     */
    @POST
    @Path("/confirm")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response confirm(String body, @HeaderParam("X-Sync-Token") String presentedToken) {
        LOGGER.info("Entering method confirm");
        if (!"store".equalsIgnoreCase(role)) {
            LOGGER.info("Exiting method confirm: off-role");
            return Response.status(Response.Status.FORBIDDEN).entity("Ce nœud n'a pas le rôle store").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            LOGGER.info("Exiting method confirm: bad token");
            return Response.status(Response.Status.UNAUTHORIZED).entity("Jeton invalide").build();
        }
        ExternalRelayService.RelayResult result = relay.relay(restoFlashUrl, "POST", null, body, timeoutMillis);
        if (!result.reached()) {
            LOGGER.info("Exiting method confirm: service unreachable, refused");
            return Response.ok(Map.of("accepted", false)).build();
        }
        LOGGER.info("Exiting method confirm");
        return Response.status(result.status()).entity(result.body()).build();
    }
}
