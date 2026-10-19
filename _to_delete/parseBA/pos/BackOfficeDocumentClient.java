package com.intermarche.pos.ui.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * The seam to the store node's DOCUMENT store (EXP-90/91/92): quotes, orders,
 * sale vouchers and click-and-collect baskets are emitted and kept by imedge
 * (a separate node, out of this repo), and recalled at the till by the code the
 * back office gave the customer.
 *
 * <p>The real store lives behind {@code pos.recall.document-url}, reached over
 * HTTP like {@link com.intermarche.pos.ui.fidelity.ImfidClient}: when a URL is
 * configured the register issues {@code GET <url>/api/orders/{code}} carrying the
 * shared {@code X-Sync-Token}, and parses the answer into a {@link RecalledDocument}.
 * When no URL is configured this client answers a LOCAL stub verdict — it knows no
 * document, so every code resolves to {@code null} (not found) — which keeps a
 * standalone lane deterministic: the register cannot invent a document imedge never
 * sent. The call is TOLERANT by construction: a node down, unreachable, a non-2xx
 * answer or an empty body all resolve to {@code null} rather than an exception, so a
 * network hiccup never disturbs a sale. The endpoint is guarded node-side by
 * {@code pos.role=store} + the token; the connected fetch is [N] e2e residue (it
 * needs imedge running).
 */
@ApplicationScoped
public class BackOfficeDocumentClient {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(BackOfficeDocumentClient.class);

    /** The document-store base URL; absent = the local stub (no document known). */
    @ConfigProperty(name = "pos.recall.document-url")
    Optional<String> documentUrl;

    /** Shared ingestion token presented to the store node; absent = none. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** The JSON reader for the node's answer. */
    @jakarta.inject.Inject
    ObjectMapper objectMapper;

    /** Shared HTTP client toward the node (connection reuse, connect timeout). */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * Fetches the document a recall code names (EXP-90/91/92).
     *
     * @param code the recall code the customer presented
     * @return the recalled document, or null when the code is blank, no store is
     *         configured, the store knows no such document, or the node could not
     *         be reached
     */
    public RecalledDocument fetch(String code) {
        LOGGER.info("Entering method fetch");
        if (code == null || code.isBlank()) {
            LOGGER.info("Exiting method fetch with blank code");
            return null;
        }
        if (documentUrl.isEmpty() || documentUrl.get().isBlank()) {
            // No document store configured: the standalone lane knows no
            // document, so nothing resolves (the connected fetch needs imedge).
            LOGGER.info("Exiting method fetch with local stub (no document)");
            return null;
        }
        try {
            String encoded = URLEncoder.encode(code.trim(), StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(documentUrl.get().trim() + "/api/orders/" + encoded))
                    .timeout(Duration.ofSeconds(3))
                    .GET();
            token.filter(t -> !t.isBlank()).ifPresent(t -> builder.header("X-Sync-Token", t));
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body() == null || response.body().isBlank()) {
                LOGGER.info("Exiting method fetch (node answered no document)");
                return null;
            }
            RecalledDocument document =
                    objectMapper.readValue(response.body(), RecalledDocument.class);
            LOGGER.info("Exiting method fetch (document recalled from node)");
            return document;
        } catch (Exception e) {
            // Tolerant: a node down or a malformed answer is "no document",
            // never a thrown error that could disturb the lane.
            LOGGER.warnf("Nœud magasin injoignable pour le document %s : %s", code, e.getMessage());
            LOGGER.info("Exiting method fetch (node unreachable, no document)");
            return null;
        }
    }

    /**
     * Pushes an order CREATED at this till to the store node (LC-06-03-01),
     * so the node holds it, the back office lists it and any register of the
     * store can recall it by its code (LC-06-03-07).
     *
     * <p>The push is the document alone — kind, code, lines, total and the
     * deposit's per-rate VAT detail; the deposit's MONEY is not pushed here
     * because it already travelled as its {@code CUSTOMER_DEPOSIT} movement
     * through the outbox, and the node imputes that movement to the order by
     * its reference. Tolerant like the fetch: a node down, a non-2xx answer
     * or an unreachable store answers false rather than an exception, and
     * the cashier keeps the printed deposit slip whatever the network says.
     *
     * @param order the order to push; its code is the recall key
     * @return true when the node accepted the order, false when it refused
     *         or could not be reached
     */
    public boolean pushOrder(RecalledDocument order) {
        LOGGER.info("Entering method pushOrder with order code: "
                + (order == null ? null : order.code));
        if (order == null || order.code == null || order.code.isBlank()) {
            LOGGER.info("Exiting method pushOrder (no order to push)");
            return false;
        }
        if (documentUrl.isEmpty() || documentUrl.get().isBlank()) {
            // No document store configured: a standalone lane keeps the
            // order on its slip only, so the recall of a till-created order
            // needs imedge — the connected push is [N] e2e residue.
            LOGGER.info("Exiting method pushOrder (no document store configured)");
            return false;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(documentUrl.get().trim() + "/api/orders"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(order)));
            token.filter(t -> !t.isBlank()).ifPresent(t -> builder.header("X-Sync-Token", t));
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            boolean accepted = response.statusCode() >= 200 && response.statusCode() < 300;
            LOGGER.info("Exiting method pushOrder (accepted: " + accepted + ")");
            return accepted;
        } catch (Exception e) {
            // Tolerant: the deposit was collected and the slip printed; the
            // push failing is a warning the operator never sees, never a
            // refused order.
            LOGGER.warnf("Nœud magasin injoignable pour la commande %s : %s",
                    order.code, e.getMessage());
            LOGGER.info("Exiting method pushOrder (node unreachable)");
            return false;
        }
    }

    /**
     * Tells the store node that the order a recall loaded has been settled at
     * this till (LC-06-03-07): the node marks it paid and collected, so the
     * same code cannot be recalled and paid a second time.
     *
     * <p>Idempotent by construction: a node that already settled the order
     * answers success, and a register whose settle push was lost can safely
     * push it again. Tolerant like every call of this seam — a settle the
     * network ate is a warning, never a refused sale.
     *
     * @param code the code of the settled order
     * @return true when the node settled it or had already settled it, false
     *         when it could not be told
     */
    public boolean settleOrder(String code) {
        LOGGER.info("Entering method settleOrder with code: " + code);
        if (code == null || code.isBlank()) {
            LOGGER.info("Exiting method settleOrder (no code)");
            return false;
        }
        if (documentUrl.isEmpty() || documentUrl.get().isBlank()) {
            LOGGER.info("Exiting method settleOrder (no document store configured)");
            return false;
        }
        try {
            String encoded = URLEncoder.encode(code.trim(), StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(documentUrl.get().trim() + "/api/orders/" + encoded
                            + "/settled"))
                    .timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.noBody());
            token.filter(t -> !t.isBlank()).ifPresent(t -> builder.header("X-Sync-Token", t));
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            boolean settled = response.statusCode() >= 200 && response.statusCode() < 300;
            LOGGER.info("Exiting method settleOrder (settled: " + settled + ")");
            return settled;
        } catch (Exception e) {
            // Tolerant: the sale is closed whatever the node says; the order
            // staying recallable is a back-office correction, not a refusal.
            LOGGER.warnf("Nœud magasin injoignable pour le règlement de la commande %s : %s",
                    code, e.getMessage());
            LOGGER.info("Exiting method settleOrder (node unreachable)");
            return false;
        }
    }
}
