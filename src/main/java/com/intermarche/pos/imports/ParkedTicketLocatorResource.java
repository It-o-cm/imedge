package com.intermarche.pos.imports;

import com.intermarche.pos.domain.sale.Ticket;
import jakarta.annotation.security.PermitAll;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The PARKED-TICKET LOCATOR ({@code GET /api/parked/{ticketNumber}}) — the
 * connected-mode half of inter-register resume (EXP-46).
 *
 * <p>Offline, a register resumes only its own parked tickets, and that stays the
 * assumed degraded rule ({@code TicketParkingService} filters on the terminal).
 * Connected, the store node is the one that knows WHERE a parked ticket sleeps:
 * this endpoint answers, for a ticket number, which register holds it and that it
 * is still parked, so another register may go and take it.
 *
 * <p>It answers a PARKED ticket only: a closed, cancelled or unknown number is a
 * 404, so nothing already sold or voided can be resumed. A machine surface with
 * the node-role-plus-token gate, like the other inbound endpoints.
 */
@Path("/api/parked")
public class ParkedTicketLocatorResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ParkedTicketLocatorResource.class);

    /** The role of this node: only a "store" node locates parked tickets. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** Shared token of the inbound endpoint; absent = open. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /**
     * Locates a parked ticket by its number for a connected register.
     *
     * @param ticketNumber the ticket number printed on the parked receipt
     * @param presentedToken the shared token presented by the register
     * @return 200 with the locator JSON, 404 when no parked ticket carries that
     *         number, 401 on a bad token, 403 off-role
     */
    @GET
    @Path("/{ticketNumber}")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response locate(@PathParam("ticketNumber") String ticketNumber,
                           @HeaderParam("X-Sync-Token") String presentedToken) {
        LOGGER.info("Entering method locate with ticketNumber: " + ticketNumber);
        if (!"store".equalsIgnoreCase(role)) {
            LOGGER.info("Exiting method locate: off-role");
            return Response.status(Response.Status.FORBIDDEN).entity("Ce nœud n'a pas le rôle store").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            LOGGER.info("Exiting method locate: bad token");
            return Response.status(Response.Status.UNAUTHORIZED).entity("Jeton invalide").build();
        }
        Ticket ticket = ticketNumber == null || ticketNumber.isBlank() ? null
                : Ticket.<Ticket>find("ticketNumber = ?1 and status = ?2",
                        ticketNumber.trim(), Ticket.TicketStatus.PARKED).firstResult();
        if (ticket == null) {
            LOGGER.info("Exiting method locate: no parked ticket");
            return Response.status(Response.Status.NOT_FOUND).entity("Aucun ticket en attente sous ce numéro").build();
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ticketNumber", ticket.ticketNumber);
        json.put("terminalId", ticket.terminalId);
        json.put("status", ticket.status.name());
        json.put("grandTotal", ticket.grandTotal);
        json.put("fidelityCard", ticket.fidelityCard == null ? "" : ticket.fidelityCard);
        LOGGER.info("Exiting method locate");
        return Response.ok(json).build();
    }
}
