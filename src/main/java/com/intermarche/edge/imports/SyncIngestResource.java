package com.intermarche.edge.imports;

import com.intermarche.edge.service.sync.store.SyncIngestService;
import com.intermarche.edge.service.sync.SyncPayloads;
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

    /** Tells whether a voucher was already consumed in the shop (BO-11-01-18). */
    @Inject
    com.intermarche.edge.service.sync.VoucherBurnService voucherBurnService;

    /** The retrieval mode of the counter-scale baskets (BO-10-05-01). */
    @Inject
    com.intermarche.edge.service.PosSettingsService posSettingsService;

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
     * Ingests a document issued at a register (BO-02-04-19).
     *
     * @param presentedToken the shared token presented by the register
     * @param dto the document payload
     * @return 200 on upsert, 401 on a bad token, 403 off-role, 409 retryable
     */
    @POST
    @Path("/document")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response ingestDocument(@HeaderParam("X-Sync-Token") String presentedToken,
                                   SyncPayloads.DocumentDto dto) {
        LOGGER.info("Entering method ingestDocument with presentedToken: ***" + ", dto: " + dto);
        LOGGER.info("Exiting method ingestDocument");
        return handle(presentedToken, () -> syncIngestService.ingestDocument(dto));
    }

    /**
     * Ingests a ticket a register printed, in its 42-column format
     * (LC-08-02-02, LC-08-03-08).
     *
     * @param presentedToken the shared token presented by the register
     * @param dto the printed ticket
     * @return 200 on upsert, 401 on a bad token, 403 off-role, 409 retryable
     */
    @POST
    @Path("/printout")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response ingestPrintout(@HeaderParam("X-Sync-Token") String presentedToken,
                                   SyncPayloads.PrintoutDto dto) {
        LOGGER.info("Entering method ingestPrintout");
        LOGGER.info("Exiting method ingestPrintout");
        return handle(presentedToken, () -> syncIngestService.ingestPrintout(dto));
    }

    /**
     * Ingests a sale a register made in training mode (LC-13-05-06).
     *
     * @param presentedToken the shared token presented by the register
     * @param dto the training sale
     * @return 200 on upsert, 401 on a bad token, 403 off-role, 409 retryable
     */
    @POST
    @Path("/training")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response ingestTraining(@HeaderParam("X-Sync-Token") String presentedToken,
                                   SyncPayloads.TrainingTicketDto dto) {
        LOGGER.info("Entering method ingestTraining");
        LOGGER.info("Exiting method ingestTraining");
        return handle(presentedToken, () -> syncIngestService.ingestTrainingTicket(dto));
    }

    /**
     * Ingests the signed trace of a ticket a register put on hold, resumed or
     * purged (LC-04-01-03).
     *
     * @param presentedToken the shared token presented by the register
     * @param dto the record
     * @return 200 on upsert, 401 on a bad token, 403 off-role, 409 retryable
     */
    @POST
    @Path("/parking")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response ingestParking(@HeaderParam("X-Sync-Token") String presentedToken,
                                  SyncPayloads.ParkedTicketDto dto) {
        LOGGER.info("Entering method ingestParking");
        LOGGER.info("Exiting method ingestParking");
        return handle(presentedToken, () -> syncIngestService.ingestParkedTicket(dto));
    }

    /**
     * Ingests an instrument a register printed or redeemed (BO-03-06-62, -48).
     *
     * @param presentedToken the shared token presented by the register
     * @param dto the instrument as that register knows it
     * @return 200 on upsert, 401 on a bad token, 403 off-role
     */
    @POST
    @Path("/stored-value")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response ingestStoredValue(@HeaderParam("X-Sync-Token") String presentedToken,
                                      SyncPayloads.StoredValueDto dto) {
        LOGGER.info("Entering method ingestStoredValue");
        LOGGER.info("Exiting method ingestStoredValue");
        // BO-03-06-48: the central node takes the instruments its shops pass on.
        if ("central".equalsIgnoreCase(role)) {
            return handleOnCentral(presentedToken, () -> syncIngestService.ingestStoredValue(dto));
        }
        return handle(presentedToken, () -> syncIngestService.ingestStoredValue(dto));
    }

    /**
     * Ingests a payment barcode read at a till (BO-03-06-63).
     *
     * @param presentedToken the shared token presented by the register
     * @param dto the reading
     * @return 200 on upsert, 401 on a bad token, 403 off-role
     */
    @POST
    @Path("/scanned-barcode")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response ingestScannedBarcode(@HeaderParam("X-Sync-Token") String presentedToken,
                                         SyncPayloads.ScannedBarcodeDto dto) {
        LOGGER.info("Entering method ingestScannedBarcode");
        LOGGER.info("Exiting method ingestScannedBarcode");
        return handle(presentedToken, () -> syncIngestService.ingestScannedBarcode(dto));
    }

    /**
     * Answers a register asking about an instrument another register of the
     * shop printed (BO-03-06-48). A read: it writes nothing.
     *
     * @param presentedToken the shared token presented by the register
     * @param number the scanned number
     * @return 200 with the instrument, 401 on a bad token, 403 off-role, 404
     *         when no register of the shop printed it
     */
    @jakarta.ws.rs.GET
    @Path("/stored-value/{number}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response storedValue(@HeaderParam("X-Sync-Token") String presentedToken,
                                @jakarta.ws.rs.PathParam("number") String number) {
        LOGGER.info("Entering method storedValue with number: " + number);
        // BO-03-06-48: the central node answers its shops as a store node
        // answers its registers.
        if (!"store".equalsIgnoreCase(role) && !"central".equalsIgnoreCase(role)) {
            LOGGER.info("Exiting method storedValue");
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("Ce nœud n'a pas le rôle store").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            LOGGER.info("Exiting method storedValue");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("Jeton de synchronisation invalide").build();
        }
        SyncPayloads.StoredValueDto found = syncIngestService.findStoredValue(number);
        LOGGER.info("Exiting method storedValue");
        return found == null ? Response.status(Response.Status.NOT_FOUND).build() : Response.ok(found).build();
    }

    /**
     * Answers a register asking whether a voucher was already consumed by a
     * closed sale of the shop (BO-11-01-18): the centralised burn of the
     * vouchers. A read: it writes nothing.
     *
     * @param presentedToken the shared token presented by the register
     * @param number the voucher number
     * @return 200 with where it was burned, 401 on a bad token, 403 off-role,
     *         404 when no closed sale of the shop settled it
     */
    @jakarta.ws.rs.GET
    @Path("/voucher-burn/{number}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response voucherBurn(@HeaderParam("X-Sync-Token") String presentedToken,
                                @jakarta.ws.rs.PathParam("number") String number) {
        LOGGER.info("Entering method voucherBurn");
        if (!"store".equalsIgnoreCase(role)) {
            LOGGER.info("Exiting method voucherBurn");
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("Ce nœud n'a pas le rôle store").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            LOGGER.info("Exiting method voucherBurn");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("Jeton de synchronisation invalide").build();
        }
        com.intermarche.edge.service.sync.VoucherBurnService.Burn burn = voucherBurnService.findBurn(number);
        LOGGER.info("Exiting method voucherBurn");
        return burn == null ? Response.status(Response.Status.NOT_FOUND).build() : Response.ok(burn).build();
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
        // BO-10-05-01: a shop that reads its baskets from the FTP server alone,
        // or takes none, refuses them on the API — a basket pushed here would be
        // a second copy nobody arbitrates.
        if (posSettingsService != null && !posSettingsService.balanceTicketApiAccepted()) {
            LOGGER.info("Exiting method ingestBalanceTicket");
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("Récupération des tickets balance par l'API désactivée").build();
        }
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
        return handleOnCentral(presentedToken, ingestion);
    }

    /**
     * Checks the token and runs an ingestion, whatever the role: the caller
     * has already decided this node takes it (BO-03-06-48 on the central node).
     *
     * @param presentedToken the shared token presented by the caller
     * @param ingestion the ingestion to run
     * @return 200 on success, 401 on a bad token, 409 on a missing reference,
     *         500 otherwise
     */
    private Response handleOnCentral(String presentedToken, Runnable ingestion) {
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
