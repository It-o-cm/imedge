package com.intermarche.edge.service.sync.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.service.sync.SyncPayloads;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
 * The store node's link to the CENTRAL instrument base (BO-03-06-48).
 *
 * <p>An instrument a register prints — a credit note, a gift card — is known
 * to its register and to its store node. A customer may hand it back in
 * ANOTHER shop of the network, whose registers and node never saw it: the
 * requirement asks that a code absent from the point-of-sale base be looked
 * for in the central one before it is declared unknown. So the store node
 * passes every instrument it receives on to the central node, under its own
 * point of sale, and asks the central node about a number it does not hold.
 *
 * <p>Only a STORE node connected to a central one ({@code pos.sync.central-url})
 * does either; anywhere else this class answers nothing. The central node
 * answers on the very ingestion endpoints the registers use toward their store.
 * Every failure — an unreachable node, a refused status, an unreadable answer —
 * is silent: the instrument stays known to its shop, and an unknown one is
 * reported to the operator as the store node always did.
 */
@ApplicationScoped
public class CentralInstrumentRelay {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CentralInstrumentRelay.class);

    /** How long the store node waits for the central node, the customer being at the till. */
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    /** The role of this node: register, store or central. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** The central node a store reports to; absent = none. */
    @ConfigProperty(name = "pos.sync.central-url")
    Optional<String> centralUrl;

    /** Shared token sent to the central node; absent = none. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** The JSON mapper of the wire format. */
    @Inject
    ObjectMapper objectMapper;

    /** The HTTP client toward the central node, replaceable by a test. */
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /**
     * Whether this node relays to a central node.
     *
     * @return true on a store node with a central node configured
     */
    boolean active() {
        return "store".equalsIgnoreCase(role) && centralUrl.isPresent() && !centralUrl.get().isBlank();
    }

    /**
     * Passes an instrument on to the central node.
     *
     * @param dto the instrument, its point of sale set
     * @return true when the central node accepted it
     */
    public boolean forward(SyncPayloads.StoredValueDto dto) {
        if (!active()) {
            return false;
        }
        try {
            HttpRequest request = request("/api/sync/stored-value")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(dto)))
                    .build();
            int status = httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
            return status >= 200 && status < 300;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            LOGGER.warnf("Instrument %s non transmis au nœud central (%s)", dto.number, e.getMessage());
            return false;
        }
    }

    /**
     * Asks the central node about an instrument this shop never printed.
     *
     * @param number the scanned number
     * @return the instrument as the central node holds it, or null when it
     *         holds none or cannot be asked
     */
    public SyncPayloads.StoredValueDto lookup(String number) {
        if (!active()) {
            return null;
        }
        try {
            HttpRequest request = request("/api/sync/stored-value/"
                    + URLEncoder.encode(number, StandardCharsets.UTF_8).replace("+", "%20")).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            return objectMapper.readValue(response.body(), SyncPayloads.StoredValueDto.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            LOGGER.warnf("Nœud central non interrogeable pour %s (%s)", number, e.getMessage());
            return null;
        }
    }

    /**
     * Starts a request to the central node, its token presented.
     *
     * @param path the path under the central node's address
     * @return the request builder
     */
    private HttpRequest.Builder request(String path) {
        String base = centralUrl.get().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(TIMEOUT)
                .header("X-Sync-Token", token.orElse(""));
    }
}
