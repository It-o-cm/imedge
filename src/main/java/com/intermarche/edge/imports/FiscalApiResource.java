package com.intermarche.edge.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intermarche.edge.domain.sale.FiscalDocument;
import com.intermarche.edge.domain.setting.ServiceProvider;
import com.intermarche.edge.service.fiscal.EInvoicingService;
import com.intermarche.edge.service.fiscal.EReportingService;
import com.intermarche.edge.service.fiscal.SaftStreamService;
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
import org.jboss.logging.Logger;

import java.time.LocalDateTime;

/**
 * The FISCAL API of the store node ({@code BO-05-01-02}, {@code -03},
 * {@code -05}): the machine doors the fiscal endpoints call.
 *
 * <ul>
 *   <li>{@code GET /api/fiscal/saft/documents?since=…&limit=…}: the Portuguese
 *       fiscal documents issued since an instant, each with its SAF-T (PT)
 *       file — the data at the flow, read by an endpoint holding the key of a
 *       service provider of protocol SAFT.</li>
 *   <li>{@code POST /api/fiscal/einvoicing/status}: a status of an electronic
 *       invoice's life cycle, pushed by the dematerialisation platform.</li>
 *   <li>{@code POST /api/fiscal/ereporting/ack}: the acknowledgement of an
 *       e-reporting transmission, pushed by the platform.</li>
 * </ul>
 *
 * <p>Each door asks for the key of the provider it serves, as a bearer
 * authorisation, and answers 401 without it: the fiscal data of a shop is not
 * readable by whoever reaches the node.
 */
@Path("/api/fiscal")
public class FiscalApiResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(FiscalApiResource.class);

    /** The largest page of documents served at once. */
    static final int MAX_LIMIT = 500;

    /** The SAF-T at the flow. */
    @Inject
    SaftStreamService saftStreamService;

    /** The electronic invoices and their platform. */
    @Inject
    EInvoicingService eInvoicingService;

    /** The e-reporting. */
    @Inject
    EReportingService eReportingService;

    /** Writes and reads the JSON bodies. */
    @Inject
    ObjectMapper objectMapper;

    /**
     * Serves the documents issued since an instant with their SAF-T file.
     *
     * @param authorization the bearer key of a SAFT provider
     * @param since the instant, ISO, exclusive; blank for the beginning
     * @param limit how many at most, 100 by default, 500 at most
     * @return 200 with the documents, 400 on an unreadable instant, 401 without a valid key
     */
    @GET
    @Path("/saft/documents")
    @Produces(MediaType.APPLICATION_JSON)
    public Response saftDocuments(@HeaderParam("Authorization") String authorization,
                                  @QueryParam("since") String since, @QueryParam("limit") Integer limit) {
        LOGGER.info("Entering method saftDocuments with since: " + since);
        if (!saftStreamService.authorized(bearer(authorization))) {
            LOGGER.info("Exiting method saftDocuments: unauthorized");
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        LocalDateTime from;
        try {
            from = since == null || since.isBlank() ? LocalDateTime.of(1970, 1, 1, 0, 0)
                    : LocalDateTime.parse(since.trim());
        } catch (RuntimeException unreadable) {
            LOGGER.info("Exiting method saftDocuments: unreadable instant");
            return error(Response.Status.BAD_REQUEST, "Instant illisible : " + since);
        }
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, MAX_LIMIT);
        ArrayNode documents = objectMapper.createArrayNode();
        for (Object[] entry : saftStreamService.documentsSince(from, size)) {
            FiscalDocument document = (FiscalDocument) entry[0];
            ObjectNode node = documents.addObject();
            node.put("number", document.number);
            node.put("type", document.documentType.name());
            node.put("issuedAt", document.issuedAt.toString());
            node.put("atcud", document.atcud);
            node.put("terminalId", document.terminalId);
            node.put("saft", (String) entry[1]);
        }
        LOGGER.info("Exiting method saftDocuments with " + documents.size() + " document(s)");
        return Response.ok(documents.toString()).build();
    }

    /**
     * Receives a status of an electronic invoice from the platform.
     *
     * @param authorization the bearer key of the platform
     * @param body {@code {"id"|"number": …, "status": …, "reason": …}}
     * @return 200 when applied, 400 on an unreadable body or status, 401 without
     *         the platform's key, 404 for an unknown invoice
     */
    @POST
    @Path("/einvoicing/status")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response einvoicingStatus(@HeaderParam("Authorization") String authorization, String body) {
        LOGGER.info("Entering method einvoicingStatus");
        if (!platformKey(bearer(authorization))) {
            LOGGER.info("Exiting method einvoicingStatus: unauthorized");
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        JsonNode node = eInvoicingService.read(body);
        String reference = EInvoicingService.text(node, "id", EInvoicingService.text(node, "number", null));
        String refusal = eInvoicingService.receive(reference, EInvoicingService.text(node, "status", null),
                EInvoicingService.text(node, "reason", null));
        LOGGER.info("Exiting method einvoicingStatus: " + refusal);
        return answer(refusal);
    }

    /**
     * Receives the acknowledgement of an e-reporting transmission.
     *
     * @param authorization the bearer key of the platform
     * @param body {@code {"id": …, "status": "ACCEPTED"|"REJECTED", "message": …}}
     * @return 200 when applied, 400 on an unreadable status, 401 without the
     *         platform's key, 404 for an unknown transmission
     */
    @POST
    @Path("/ereporting/ack")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response ereportingAck(@HeaderParam("Authorization") String authorization, String body) {
        LOGGER.info("Entering method ereportingAck");
        if (!platformKey(bearer(authorization))) {
            LOGGER.info("Exiting method ereportingAck: unauthorized");
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        JsonNode node = eInvoicingService.read(body);
        String refusal = eReportingService.receive(EInvoicingService.text(node, "id", null),
                EInvoicingService.text(node, "status", null), EInvoicingService.text(node, "message", null));
        LOGGER.info("Exiting method ereportingAck: " + refusal);
        return answer(refusal);
    }

    /**
     * Turns a service's refusal into the answer of a door.
     *
     * @param refusal the refusal, or null when applied
     * @return 200, 400 for an unknown status, 404 otherwise
     */
    private Response answer(String refusal) {
        if (refusal == null) {
            return Response.ok(objectMapper.createObjectNode().put("applied", true).toString()).build();
        }
        return error("Statut inconnu.".equals(refusal) ? Response.Status.BAD_REQUEST : Response.Status.NOT_FOUND,
                refusal);
    }

    /**
     * Builds a JSON error answer, the {@code {"error":…}} body every import
     * door answers.
     *
     * @param status the HTTP status
     * @param message the message
     * @return the answer
     */
    private Response error(Response.Status status, String message) {
        return Response.status(status).entity(ImporterCsvResource.errorAnswer(message)).build();
    }

    /**
     * Whether a key is the key of the dematerialisation platform.
     *
     * @param key the key presented, possibly null
     * @return true when it is the administered platform's
     */
    boolean platformKey(String key) {
        ServiceProvider platform = eInvoicingService.platform();
        return key != null && platform != null && platform.authKey.equals(key);
    }

    /**
     * Reads the key of a bearer authorisation.
     *
     * @param authorization the header, possibly null
     * @return the key, or null when the header carries none
     */
    static String bearer(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String key = authorization.substring(7).trim();
        return key.isEmpty() ? null : key;
    }
}
