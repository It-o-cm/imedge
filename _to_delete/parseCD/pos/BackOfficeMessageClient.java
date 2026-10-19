package com.intermarche.pos.ui.supervision;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * The caisse-side SEAM that PULLS a pending back-office message from the store
 * node (EXP-20).
 *
 * <p>The topology is pull, not push: the caisse interrogates the node, the node
 * never has to know a caisse address. This client asks the node
 * {@code pos.message.node-url} — naming this terminal — for the message currently
 * addressed to it, if any. It is TOLERANT by construction: no URL configured (a
 * standalone lane), an unreachable node, or a non-2xx answer all resolve to
 * "no message" rather than an exception, so a network hiccup never disturbs a
 * sale. The node producer lives in imedge; the real fetch is connected-mode
 * ({@code [N]}) territory.
 */
@ApplicationScoped
public class BackOfficeMessageClient {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(BackOfficeMessageClient.class);

    /** The store node's pending-message endpoint; absent = standalone lane. */
    @ConfigProperty(name = "pos.message.node-url")
    Optional<String> nodeUrl;

    /** Shared token sent to the node; absent = none. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** This register's identifier, named on the request so the node targets it. */
    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    /** The JSON reader for the node's answer. */
    @jakarta.inject.Inject
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** The HTTP client used toward the node. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * A pending back-office message pulled from the node: a plain notice, or a
     * survey when {@code question} is present, carrying the answer machinery
     * of LC-03-07-06 to -10.
     *
     * @param text the message text, or null when there is only a survey
     * @param question the survey question, or null for a plain notice
     * @param options the survey options as a semicolon list, or null
     * @param responseType the survey's response type name, or null for options
     * @param required true when the survey may not be passed by
     * @param postalFormat the postal-code mask, or null
     * @param postalCodes the valid postal codes as a semicolon list, or null
     */
    public record PendingMessage(String text, String question, String options,
            String responseType, boolean required, String postalFormat,
            String postalCodes) {
    }

    /**
     * Pulls the message the node currently addresses to this terminal, or null
     * when there is none, no node is configured, or the node could not be reached.
     *
     * @return the pending message, or null
     */
    public PendingMessage fetchPending() {
        LOGGER.info("Entering method fetchPending");
        PendingMessage message = fetchPending(null);
        LOGGER.info("Exiting method fetchPending");
        return message;
    }

    /**
     * Pulls the message the node addresses to this terminal AT A TRANSACTION
     * MOMENT (LC-03-07-01 / -02): the register names the moment it just
     * reached and the node answers the survey administered for it, or
     * nothing. Same tolerance as the ordinary poll: a null fetch is a no-op.
     *
     * @param moment the transaction moment name, or null for the ordinary poll
     * @return the pending message, or null
     */
    public PendingMessage fetchPending(String moment) {
        LOGGER.info("Entering method fetchPending with moment: " + moment);
        if (nodeUrl.isEmpty() || nodeUrl.get().isBlank()) {
            LOGGER.info("Exiting method fetchPending");
            return null;
        }
        try {
            String query = "?terminal=" + terminalId
                    + (moment == null || moment.isBlank() ? "" : "&moment=" + moment);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(nodeUrl.get() + query))
                    .timeout(Duration.ofSeconds(3))
                    .GET();
            token.filter(t -> !t.isBlank()).ifPresent(t -> builder.header("X-Sync-Token", t));
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body() == null || response.body().isBlank()) {
                LOGGER.info("Exiting method fetchPending");
                return null;
            }
            PendingMessage message = objectMapper.readValue(response.body(), PendingMessage.class);
            LOGGER.info("Exiting method fetchPending");
            return message;
        } catch (Exception e) {
            // Tolerant: a node down or a malformed answer is "no message", never a
            // thrown error that could disturb the lane.
            LOGGER.warnf("Message back-office indisponible (%s)", e.getMessage());
            LOGGER.info("Exiting method fetchPending");
            return null;
        }
    }

    /**
     * Pulls the survey a scanned code-barre names (LC-03-07-03): the register
     * asks for the administered message by its id, and the node serves it or
     * nothing. Same tolerance as the ordinary poll.
     *
     * @param surveyId the administered id the scanned code carries
     * @return the survey, or null
     */
    public PendingMessage fetchSurvey(long surveyId) {
        LOGGER.info("Entering method fetchSurvey with surveyId: " + surveyId);
        if (nodeUrl.isEmpty() || nodeUrl.get().isBlank()) {
            LOGGER.info("Exiting method fetchSurvey");
            return null;
        }
        try {
            // The survey endpoint lives beside the pending one the URL names:
            // the configured URL ends with the /pending path, and the survey
            // path is its sibling under the same base.
            String base = nodeUrl.get();
            int lastSegment = base.lastIndexOf('/');
            String root = lastSegment > 0 ? base.substring(0, lastSegment) : base;
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(root + "/survey/" + surveyId + "?terminal=" + terminalId))
                    .timeout(Duration.ofSeconds(3))
                    .GET();
            token.filter(t -> !t.isBlank()).ifPresent(t -> builder.header("X-Sync-Token", t));
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body() == null || response.body().isBlank()) {
                LOGGER.info("Exiting method fetchSurvey");
                return null;
            }
            PendingMessage message = objectMapper.readValue(response.body(), PendingMessage.class);
            LOGGER.info("Exiting method fetchSurvey");
            return message;
        } catch (Exception e) {
            LOGGER.warnf("Sondage par scan indisponible (%s)", e.getMessage());
            LOGGER.info("Exiting method fetchSurvey");
            return null;
        }
    }
}
