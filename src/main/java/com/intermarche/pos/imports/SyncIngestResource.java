package com.intermarche.pos.imports;

import com.intermarche.pos.service.sync.store.SyncIngestService;
import com.intermarche.pos.service.sync.SyncPayloads;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.HeaderParam;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Store-node ingestion endpoints of the register synchronization (phase 5).
 * <p>
 * Active only when this node runs the {@code store} role ({@code pos.role});
 * a register node answers 403 so a misconfigured push cannot pollute a
 * register database. Resolution failures (missing referenced entity) answer
 * 409 so the register keeps the item in its outbox and retries.
 * <p>
 * The 409 contract is what makes the register-side drain order safe end to
 * end: sessions arrive before the tickets that reference them, tickets
 * before the refunds that cite their lines (by lineUid), events last — and
 * any out-of-order arrival (first push of a fresh register, store-node
 * downtime window) degrades into a retry instead of a data hole.
 */
@Path("/api/sync")
public class SyncIngestResource {

    private static final Logger LOGGER = Logger.getLogger(SyncIngestResource.class);

    /** The role of this node: "register" (default) or "store". */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** Shared ingestion token; absent = no authentication required. */
    @ConfigProperty(name = "pos.sync.token")
    java.util.Optional<String> token;

    @Inject
    SyncIngestService syncIngestService;

    /**
     * Ingests a pushed cash session.
     *
     * @param dto the session payload
     * @return 200 on upsert, 403 off-role, 409 on a retryable resolution failure
     */
    @POST
    @Path("/session")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response ingestSession(@HeaderParam("X-Sync-Token") String presentedToken,
                                  SyncPayloads.SessionDto dto) {
        LOGGER.info("Entering method ingestSession with presentedToken: ***" + ", dto: " + dto);
        LOGGER.info("Exiting method ingestSession");
        return handle(presentedToken, () -> syncIngestService.ingestSession(dto));
    }

    /**
     * Ingests a pushed ticket.
     *
     * @param dto the ticket payload
     * @return 200 on upsert, 403 off-role, 409 on a retryable resolution failure
     */
    @POST
    @Path("/ticket")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response ingestTicket(@HeaderParam("X-Sync-Token") String presentedToken,
                                 SyncPayloads.TicketDto dto) {
        LOGGER.info("Entering method ingestTicket with presentedToken: ***" + ", dto: " + dto);
        LOGGER.info("Exiting method ingestTicket");
        return handle(presentedToken, () -> syncIngestService.ingestTicket(dto));
    }

    /**
     * Ingests a pushed cash movement.
     *
     * @param presentedToken the shared token presented by the register
     * @param dto the movement payload
     * @return 200 on upsert, 401 on a bad token, 403 off-role, 409 retryable
     */
    @POST
    @Path("/movement")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response ingestMovement(@HeaderParam("X-Sync-Token") String presentedToken,
                                   SyncPayloads.MovementDto dto) {
        LOGGER.info("Entering method ingestMovement with presentedToken: ***" + ", dto: " + dto);
        LOGGER.info("Exiting method ingestMovement");
        return handle(presentedToken, () -> syncIngestService.ingestMovement(dto));
    }

    /**
     * Ingests a pushed refund.
     *
     * @param dto the refund payload
     * @return 200 on upsert, 403 off-role, 409 on a retryable resolution failure
     */
    @POST
    @Path("/refund")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response ingestRefund(@HeaderParam("X-Sync-Token") String presentedToken,
                                 SyncPayloads.RefundDto dto) {
        LOGGER.info("Entering method ingestRefund with presentedToken: ***" + ", dto: " + dto);
        LOGGER.info("Exiting method ingestRefund");
        return handle(presentedToken, () -> syncIngestService.ingestRefund(dto));
    }

    /**
     * Ingests a pushed technical journal event.
     *
     * @param presentedToken the shared token presented by the register
     * @param dto the event payload
     * @return 200 on upsert, 401 on a bad token, 403 off-role, 409 retryable
     */
    @POST
    @Path("/event")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response ingestEvent(@HeaderParam("X-Sync-Token") String presentedToken,
                                SyncPayloads.EventDto dto) {
        LOGGER.info("Entering method ingestEvent with presentedToken: ***" + ", dto: " + dto);
        LOGGER.info("Exiting method ingestEvent");
        return handle(presentedToken, () -> syncIngestService.ingestEvent(dto));
    }

    /**
     * Ingests an account customer created at a register (LC-08-04-09).
     *
     * @param presentedToken the shared token presented by the register
     * @param dto the customer payload
     * @return 200 on upsert, 401 on a bad token, 403 off-role, 409 retryable
     */
    @POST
    @Path("/customer")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response ingestCustomer(@HeaderParam("X-Sync-Token") String presentedToken,
                                   SyncPayloads.CustomerDto dto) {
        LOGGER.info("Entering method ingestCustomer with presentedToken: ***" + ", dto: " + dto);
        LOGGER.info("Exiting method ingestCustomer");
        return handle(presentedToken, () -> syncIngestService.ingestCustomer(dto));
    }

    /**
     * Ingests a counter ticket pushed by a scale system (LC-06-01-02).
     *
     * <p>The scale is the emitter and it pushes — but it pushes HERE, never to a
     * register: at weighing time nobody knows which lane the customer will walk
     * to. The shop holds it until one of them asks.
     *
     * @param presentedToken the shared token presented by the scale system
     * @param dto the counter ticket payload
     * @return 200 on upsert, 401 on a bad token, 403 off-role, 409 retryable
     */
    @POST
    @Path("/balance-ticket")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response ingestBalanceTicket(@HeaderParam("X-Sync-Token") String presentedToken,
                                        SyncPayloads.BalanceTicketDto dto) {
        LOGGER.info("Entering method ingestBalanceTicket with presentedToken: ***" + ", dto: " + dto);
        LOGGER.info("Exiting method ingestBalanceTicket");
        return handle(presentedToken, () -> syncIngestService.ingestBalanceTicket(dto));
    }

    /**
     * Serves a counter ticket to the register picking it up, AND consumes it
     * (LC-06-01-02).
     *
     * <p>A GET that WRITES, deliberately, and the only one here. The shop is the
     * single place able to tell a first pick-up from a second; it can only tell
     * it if serving and marking are one act. A second scan of the same paper —
     * at this register or at another — gets 404, which is the whole point.
     *
     * @param presentedToken the shared token presented by the register
     * @param reference the reference scanned at the till
     * @param terminalId the register picking the ticket up
     * @return 200 with the detail, 401 on a bad token, 403 off-role, 404 when the
     *         shop holds no such reference or has already served it
     */
    @jakarta.ws.rs.GET
    @Path("/balance-ticket/{reference}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response consumeBalanceTicket(@HeaderParam("X-Sync-Token") String presentedToken,
                                         @jakarta.ws.rs.PathParam("reference") String reference,
                                         @jakarta.ws.rs.QueryParam("terminal") String terminalId) {
        LOGGER.info("Entering method consumeBalanceTicket with presentedToken: ***" + ", reference: " + reference + ", terminalId: " + terminalId);
        if (!"store".equalsIgnoreCase(role)) {
            LOGGER.info("Exiting method consumeBalanceTicket");
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("Ce nœud n'a pas le rôle store").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            LOGGER.info("Exiting method consumeBalanceTicket");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("Jeton de synchronisation invalide").build();
        }
        SyncPayloads.BalanceTicketDto served =
                syncIngestService.consumeBalanceTicket(reference, terminalId);
        if (served == null) {
            LOGGER.info("Exiting method consumeBalanceTicket");
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        LOGGER.info("Exiting method consumeBalanceTicket");
        return Response.ok(served).build();
    }

    /**
     * Serves the duplicata of a ticket the shop holds, to a register that does not
     * hold it (LC-08-05-05).
     *
     * <p>A READ, unlike every other route here: it writes nothing and bumps no
     * counter. It stands under the same role and token gates all the same — the
     * shop's sales are not public.
     *
     * @param presentedToken the shared token presented by the register
     * @param ticketNumber the number of the ticket asked for
     * @return 200 with the rendered duplicata, 401 on a bad token, 403 off-role,
     *         404 when the shop holds no such ticket
     */
    @jakarta.ws.rs.GET
    @Path("/ticket/{ticketNumber}/duplicata")
    @Produces(MediaType.TEXT_PLAIN)
    public Response ticketDuplicate(@HeaderParam("X-Sync-Token") String presentedToken,
                                    @jakarta.ws.rs.PathParam("ticketNumber") String ticketNumber) {
        LOGGER.info("Entering method ticketDuplicate with presentedToken: ***" + ", ticketNumber: " + ticketNumber);
        if (!"store".equalsIgnoreCase(role)) {
            LOGGER.info("Exiting method ticketDuplicate");
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("Ce nœud n'a pas le rôle store").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            LOGGER.info("Exiting method ticketDuplicate");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("Jeton de synchronisation invalide").build();
        }
        String rendered = syncIngestService.renderTicketDuplicate(ticketNumber);
        if (rendered == null) {
            LOGGER.info("Exiting method ticketDuplicate");
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Ticket inconnu du magasin").build();
        }
        LOGGER.info("Exiting method ticketDuplicate");
        return Response.ok(rendered).build();
    }

    /**
     * Runs an ingestion under the role gate, the shared-token gate and the
     * retryable-failure contract.
     *
     * @param presentedToken the token presented by the register, or null
     * @param ingestion the ingestion to run
     * @return the HTTP response
     */
    private Response handle(String presentedToken, Runnable ingestion) {
        if (!"store".equalsIgnoreCase(role)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("Ce nœud n'a pas le rôle store").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("Jeton de synchronisation invalide").build();
        }
        try {
            ingestion.run();
            return Response.ok("OK").build();
        } catch (IllegalStateException e) {
            // Missing referenced entity: the register retries later
            return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
        } catch (Exception e) {
            LOGGER.errorf("Ingestion en erreur: %s", e.getMessage());
            return Response.serverError().entity(e.getMessage()).build();
        }
    }
}
