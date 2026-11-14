package com.intermarche.edge.imports;

import com.intermarche.edge.service.deploy.JournalUplinkService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * The MACHINE surface of the journal uplink (BO-02-05-05): where a store node
 * asks the central node the watermark of its point of sale and sends it up its
 * closed tickets, for the electronic journal regrouped by adhérent.
 *
 * <p>Served by the central node only, behind the same optional shared token
 * as the node reports: it is the same conversation with the same callers.
 *
 * <p>Rooted at {@code /} with full {@code /api/...} method paths, like the
 * report surface, so it never shadows the other {@code /}-rooted classes.
 */
@Path("/")
public class JournalUplinkResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(JournalUplinkResource.class);

    /** The role of this node: register, store or central. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** Shared token; absent = open. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** Files the tickets and answers the watermarks. */
    @Inject
    JournalUplinkService journalUplinkService;

    /**
     * Answers the latest ticket modification held for a point of sale.
     *
     * @param presentedToken the shared token presented
     * @param pdv the point of sale
     * @return 200 with the watermark, or 401/403 when gated
     */
    @GET
    @Path("/api/supervision/journal/mark")
    @Produces(MediaType.APPLICATION_JSON)
    public Response mark(@HeaderParam("X-Sync-Token") String presentedToken, @QueryParam("pdv") String pdv) {
        Response gate = gate(presentedToken);
        if (gate != null) {
            return gate;
        }
        return Response.ok(journalUplinkService.mark(pdv)).build();
    }

    /**
     * Files a batch of tickets a store node pushed.
     *
     * @param presentedToken the shared token presented
     * @param batch the batch
     * @return 200 with the acknowledgement, or 401/403 when gated
     */
    @POST
    @Path("/api/supervision/journal")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response receive(@HeaderParam("X-Sync-Token") String presentedToken, JournalUplinkService.Batch batch) {
        Response gate = gate(presentedToken);
        if (gate != null) {
            return gate;
        }
        LOGGER.info("Entering method receive from " + (batch == null ? null : batch.pdvNumber));
        return Response.ok(journalUplinkService.receive(batch)).build();
    }

    /**
     * Refuses a caller this node must not serve: only the central node holds
     * the journal of the organisation.
     *
     * @param presentedToken the token the caller presented
     * @return the refusal, or null when the caller may proceed
     */
    private Response gate(String presentedToken) {
        if (!"central".equalsIgnoreCase(role)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(ImporterCsvResource.errorAnswer("Seul le nœud central reçoit les journaux")).build();
        }
        if (token.isPresent() && !token.get().isBlank() && !token.get().equals(presentedToken)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ImporterCsvResource.errorAnswer("Jeton de synchronisation invalide")).build();
        }
        return null;
    }
}
