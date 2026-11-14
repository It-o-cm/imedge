package com.intermarche.edge.imports;

import com.intermarche.edge.domain.sale.PrintedTicket;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Puts the tickets the registers printed at the disposal of third-party services
 * — the customer's online account, the enseigne's mobile application
 * (LC-08-02-02): every ticket generated, the customer's and the store's
 * technical ones alike, in the 42-column format the register printed, the text
 * encoded in Base64URL so it travels intact in any JSON or URL.
 *
 * <p>A reader polls in turn: it asks for the tickets after the last id it holds
 * and gets them oldest first, one page at a time. The training-mode tickets are
 * only served to a reader that asks for them by their audience (LC-13-05-06).
 *
 * <p>A machine surface, gated like the other ones of this node: the store role
 * and the shared token.
 */
@Path("/api/printouts")
public class PrintoutApiResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PrintoutApiResource.class);

    /** The largest page a reader may ask for. */
    static final int MAX_LIMIT = 500;

    /** The role of this node: only a "store" node serves the printouts. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** Shared token of the machine surfaces; absent = open. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /**
     * Lists the printed tickets after a given id.
     *
     * @param presentedToken the shared token presented by the reader
     * @param after the last id the reader holds, 0 or absent for the first read
     * @param audience CUSTOMER (default), STORE or TRAINING
     * @param limit the page size, 100 by default, at most {@value #MAX_LIMIT}
     * @return 200 with the page, 400 on an unknown audience, 401 on a bad token,
     *         403 off-role
     */
    @GET
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@HeaderParam("X-Sync-Token") String presentedToken,
                         @QueryParam("after") Long after,
                         @QueryParam("audience") String audience,
                         @QueryParam("limit") Integer limit) {
        LOGGER.info("Entering method list with after: " + after + ", audience: " + audience);
        if (!"store".equalsIgnoreCase(role)) {
            LOGGER.info("Exiting method list: off-role");
            return Response.status(Response.Status.FORBIDDEN).entity("Ce nœud n'a pas le rôle store").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            LOGGER.info("Exiting method list: bad token");
            return Response.status(Response.Status.UNAUTHORIZED).entity("Jeton invalide").build();
        }
        PrintedTicket.Audience read;
        try {
            read = audience == null || audience.isBlank()
                    ? PrintedTicket.Audience.CUSTOMER : PrintedTicket.Audience.valueOf(audience.trim());
        } catch (IllegalArgumentException e) {
            LOGGER.info("Exiting method list: unknown audience");
            return Response.status(Response.Status.BAD_REQUEST).entity("Audience inconnue").build();
        }
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, MAX_LIMIT);
        List<Map<String, Object>> page = new ArrayList<>();
        for (PrintedTicket printout : PrintedTicket.listAfter(after == null ? 0L : after, read, size)) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", printout.id);
            json.put("terminalId", printout.terminalId);
            json.put("issuedAt", printout.issuedAt == null ? null : printout.issuedAt.toString());
            json.put("source", printout.source);
            json.put("audience", printout.audience.name());
            json.put("encoding", "base64url");
            json.put("content", Base64.getUrlEncoder().encodeToString(
                    (printout.content == null ? "" : printout.content).getBytes(StandardCharsets.UTF_8)));
            page.add(json);
        }
        LOGGER.info("Exiting method list (" + page.size() + " printouts)");
        return Response.ok(page).build();
    }
}
