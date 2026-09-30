package com.intermarche.pos.imports;

import com.intermarche.pos.service.ExternalRelayService;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The supplier GIFT-CARD balance endpoint ({@code GET /api/giftcard/{cardNumber}},
 * EXP-66): a register asks the store node whether a supplier gift card is valid
 * and what it holds; the node relays the question to the issuer registry it is
 * configured with and answers {@code {valid, balance}}.
 *
 * <p>A machine surface with the node-role-plus-token gate, like the other inbound
 * endpoints. When no issuer is configured, it answers a safe default (invalid,
 * zero) rather than failing — the register then declines the card, it never
 * crashes on a missing integration.
 */
@Path("/api/giftcard")
public class GiftCardResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(GiftCardResource.class);

    /** The role of this node: only a "store" node relays gift-card queries. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** Shared token of the inbound endpoint; absent = open. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** The issuer registry URL the node relays to; absent = disabled. */
    @ConfigProperty(name = "pos.giftcard.issuer-url")
    Optional<String> issuerUrl;

    /** Read timeout in milliseconds (a register is waiting on a scan). */
    @ConfigProperty(name = "pos.giftcard.timeout-millis", defaultValue = "2500")
    long timeoutMillis;

    /** The shared upstream relay. */
    @Inject
    ExternalRelayService relay;

    /**
     * Answers a gift-card balance query for a connected register.
     *
     * @param cardNumber the gift-card number scanned
     * @param presentedToken the shared token presented by the register
     * @return 200 with the issuer answer or the safe default, 401 on a bad token,
     *         403 off-role
     */
    @GET
    @Path("/{cardNumber}")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    public Response query(@PathParam("cardNumber") String cardNumber,
                          @HeaderParam("X-Sync-Token") String presentedToken) {
        LOGGER.info("Entering method query");
        if (!"store".equalsIgnoreCase(role)) {
            LOGGER.info("Exiting method query: off-role");
            return Response.status(Response.Status.FORBIDDEN).entity("Ce nœud n'a pas le rôle store").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            LOGGER.info("Exiting method query: bad token");
            return Response.status(Response.Status.UNAUTHORIZED).entity("Jeton invalide").build();
        }
        ExternalRelayService.RelayResult result = relay.relay(issuerUrl, "GET", "/" + cardNumber, null, timeoutMillis);
        if (!result.reached()) {
            LOGGER.info("Exiting method query: issuer unreachable, safe default");
            return Response.ok(Map.of("valid", false, "balance", 0)).build();
        }
        LOGGER.info("Exiting method query");
        return Response.status(result.status()).entity(result.body()).build();
    }
}
