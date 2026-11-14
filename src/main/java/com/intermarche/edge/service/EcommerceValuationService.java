package com.intermarche.edge.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * EXPOSES the store's valuation engine to the e-commerce platform
 * (BO-11-04-03): the basket the platform sends is valued by the very engine
 * the registers of the point of sale call, with the offers of the point of
 * sale, and the engine's answer goes back to the platform as it came.
 *
 * <p>A relay and not a second engine: the request travels verbatim to
 * {@code {pos.valuation.url}/valuation} with the node's engine account, so an
 * e-commerce basket and a lane basket can never be valued by two different
 * readings of the same offers. The node adds nothing but the gate and the
 * administered switch.
 */
@ApplicationScoped
public class EcommerceValuationService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EcommerceValuationService.class);

    /** The read timeout of one valuation, in milliseconds. */
    static final long TIMEOUT_MILLIS = 5000L;

    /** The base URL of the shop's valuation engine; absent = no engine. */
    @ConfigProperty(name = "pos.valuation.url")
    Optional<String> url;

    /** The engine account's user. */
    @ConfigProperty(name = "pos.valuation.user")
    Optional<String> user;

    /** The engine account's password. */
    @ConfigProperty(name = "pos.valuation.password")
    Optional<String> password;

    /** Whether the valuation is exposed at this echelon. */
    @Inject
    PosSettingsService posSettingsService;

    /** The HTTP client toward the engine. */
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /**
     * What the engine answered, or why it was not asked.
     *
     * @param status the HTTP status to answer the platform with
     * @param body the engine's body, or the reason in words
     */
    public record Answer(int status, String body) {
    }

    /**
     * Values one e-commerce basket with the store's engine.
     *
     * @param requestJson the valuation request, as the platform sent it
     * @return the engine's answer; 403 when the exposure is off, 400 on an
     *     empty request, 503 when no engine is configured or it cannot be reached
     */
    public Answer valuate(String requestJson) {
        LOGGER.info("Entering method valuate");
        if (!posSettingsService.ecommerceValuationEnabled()) {
            LOGGER.info("Exiting method valuate: not exposed");
            return new Answer(403, "Moteur de valorisation non exposé au e-commerce");
        }
        if (requestJson == null || requestJson.isBlank()) {
            LOGGER.info("Exiting method valuate: empty request");
            return new Answer(400, "Panier à valoriser absent");
        }
        LOGGER.info("Exiting method valuate");
        return relay("POST", "/valuation", requestJson);
    }

    /**
     * Relays one call to the store's engine (DG-01-01, DG-01-03): the same
     * timeout, the same engine account and the same administered switch as the
     * valuation, for the stateless valuation of the platform's baskets, the
     * declaration of their consumption and the valuation of their returns.
     *
     * @param method the HTTP method (GET, POST, PUT, DELETE)
     * @param path the engine path, starting with a slash
     * @param body the JSON body, or null for none
     * @return the engine's answer; 403 when the exposure is off, 503 when no
     *     engine is configured or it cannot be reached
     */
    public Answer relay(String method, String path, String body) {
        LOGGER.info("Entering method relay with method: " + method + ", path: " + path);
        if (!posSettingsService.ecommerceValuationEnabled()) {
            LOGGER.info("Exiting method relay: not exposed");
            return new Answer(403, "Moteur de valorisation non exposé au e-commerce");
        }
        String base = url.orElse("").trim();
        if (base.isEmpty()) {
            LOGGER.info("Exiting method relay: no engine");
            return new Answer(503, "Moteur de valorisation non configuré");
        }
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(base + path))
                    .timeout(Duration.ofMillis(TIMEOUT_MILLIS))
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (user.isPresent() && password.isPresent()) {
                request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (user.get() + ":" + password.get()).getBytes(StandardCharsets.UTF_8)));
            }
            HttpResponse<String> response = httpClient.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            LOGGER.info("Exiting method relay with status " + response.statusCode());
            return new Answer(response.statusCode(), response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOGGER.info("Exiting method relay: interrupted");
            return new Answer(503, "Moteur de valorisation injoignable");
        } catch (Exception failure) {
            LOGGER.warnf("Moteur de valorisation injoignable pour le e-commerce (%s)", failure.getMessage());
            return new Answer(503, "Moteur de valorisation injoignable");
        }
    }
}
