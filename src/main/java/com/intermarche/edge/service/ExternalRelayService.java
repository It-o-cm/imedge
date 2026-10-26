package com.intermarche.edge.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * A tolerant credentialed RELAY the store node puts in front of the third-party
 * payment/SMS services the registers cannot reach directly (EXP-66 gift-card
 * issuer, EXP-67 Resto Flash, EXP-48 SMS gateway).
 *
 * <p>A register is configured to call the STORE NODE, not the external service:
 * the node holds the upstream URL and credentials and forwards the call. This
 * service is the shared forwarding half — it never throws and never blocks a
 * sale: when the upstream is not configured or answers badly, it returns a
 * {@link RelayResult} the caller turns into a safe default, so a missing
 * integration degrades to "refused/undelivered" rather than an error.
 */
@ApplicationScoped
public class ExternalRelayService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ExternalRelayService.class);

    /** The HTTP client toward the upstream; package-private so a test can replace it. */
    HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /**
     * The outcome of a relay: whether the upstream answered, its HTTP status and
     * its body. A not-reached relay carries {@code reached=false} so the caller
     * applies its safe default.
     *
     * @param reached whether the upstream was configured and answered
     * @param status the upstream HTTP status, or 0 when not reached
     * @param body the upstream response body, or an empty string
     */
    public record RelayResult(boolean reached, int status, String body) {
    }

    /**
     * Forwards a request to the configured upstream, returning its answer or a
     * not-reached result when the upstream is absent or fails.
     *
     * @param upstreamUrl the configured upstream URL, or empty
     * @param method the HTTP method ({@code GET} or {@code POST})
     * @param pathSuffix an extra path appended to the URL (e.g. a card number), or null
     * @param body the request body for a POST, or null
     * @param timeoutMillis the read timeout in milliseconds
     * @return the relay outcome, never null
     */
    public RelayResult relay(Optional<String> upstreamUrl, String method, String pathSuffix,
                             String body, long timeoutMillis) {
        return relay(upstreamUrl, method, pathSuffix, body, timeoutMillis, null);
    }

    /**
     * Forwards a request to the configured upstream, presenting a credential.
     *
     * <p>Same forward as above with one addition: the credential travels as a
     * bearer authorization header. It is a parameter and not a field because the
     * callers that need one hold a DIFFERENT credential per destination — the
     * at-the-flow stream posts to several declared providers, each with its own
     * key — while the callers that do not pass null and are unchanged.
     *
     * <p>The credential is never logged, here or anywhere: the entry log of this
     * method states the method and nothing else.
     *
     * @param upstreamUrl the configured upstream URL, or empty
     * @param method the HTTP method ({@code GET} or {@code POST})
     * @param pathSuffix an extra path appended to the URL, or null
     * @param body the request body for a POST, or null
     * @param timeoutMillis the read timeout in milliseconds
     * @param credential the credential to present, or null for none
     * @return the relay outcome, never null
     */
    public RelayResult relay(Optional<String> upstreamUrl, String method, String pathSuffix,
                             String body, long timeoutMillis, String credential) {
        return relay(upstreamUrl, method, pathSuffix, body, timeoutMillis, credential, "application/json");
    }

    /**
     * Forwards a request presenting a credential, declaring the media type of
     * the body: a third party that reads a flat record or a SOAP envelope
     * (BO-11-02-01, BO-11-04-02) must be told it is not receiving JSON.
     *
     * @param upstreamUrl the configured upstream URL, or empty
     * @param method the HTTP method ({@code GET} or {@code POST})
     * @param pathSuffix an extra path appended to the URL, or null
     * @param body the request body for a POST, or null
     * @param timeoutMillis the read timeout in milliseconds
     * @param credential the credential to present, or null for none
     * @param contentType the media type of the body
     * @return the relay outcome, never null
     */
    public RelayResult relay(Optional<String> upstreamUrl, String method, String pathSuffix,
                             String body, long timeoutMillis, String credential, String contentType) {
        LOGGER.info("Entering method relay with method: " + method);
        if (upstreamUrl == null || upstreamUrl.isEmpty() || upstreamUrl.get().isBlank()) {
            LOGGER.info("Exiting method relay: upstream not configured");
            return new RelayResult(false, 0, "");
        }
        try {
            String target = upstreamUrl.get() + (pathSuffix == null ? "" : pathSuffix);
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(target))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("Content-Type", contentType);
            if (credential != null && !credential.isBlank()) {
                request.header("Authorization", "Bearer " + credential);
            }
            if ("POST".equalsIgnoreCase(method)) {
                request.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
            } else {
                request.GET();
            }
            HttpResponse<String> response = httpClient.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            LOGGER.info("Exiting method relay with status " + response.statusCode());
            return new RelayResult(true, response.statusCode(), response.body());
        } catch (Exception failure) {
            LOGGER.warnf("Relais amont impossible (%s): repli sur le défaut", failure.getMessage());
            return new RelayResult(false, 0, "");
        }
    }
}
