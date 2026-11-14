package com.intermarche.edge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.Base64;
import java.util.Optional;

/**
 * READS THE CARD PROMOTIONS of a loyalty card from the loyalty programme
 * (DG-01-01-01, -02), for the e-commerce baskets the store node relays to
 * the valuation engine: the immediate-discount promotions a card carries are
 * injected as the basket's {@code cardPromotions}, so a web basket is valued
 * with the same personal advantages a lane basket gets.
 *
 * <p>The very contract the register's {@code ImfidClient.promotions} uses:
 * {@code GET {fid}/api/accounts/{card}/promotions?benefit=IMMEDIATE_DISCOUNT},
 * a 404 meaning "no promotion", the base URL and the machine account
 * resolved like the node's loyalty outbox (administered value first, the
 * {@code pos.fid.*} deployment keys otherwise).
 */
@ApplicationScoped
public class FidCardPromotionClient {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(FidCardPromotionClient.class);

    /** The benefit asked for: the discounts the engine applies, never the cagnotte ones. */
    static final String BENEFIT_IMMEDIATE_DISCOUNT = "IMMEDIATE_DISCOUNT";

    /** The read timeout applying when none is administered, in milliseconds. */
    static final int DEFAULT_READ_TIMEOUT_MS = 2500;

    /** The loyalty programme's base URL, deployment key. */
    @ConfigProperty(name = "pos.fid.url")
    Optional<String> url;

    /** The machine account's user, deployment key. */
    @ConfigProperty(name = "pos.fid.user")
    Optional<String> user;

    /** The machine account's password, deployment key. */
    @ConfigProperty(name = "pos.fid.password")
    Optional<String> password;

    /** The administered loyalty address, account and timeout. */
    @Inject
    PosSettingsService posSettingsService;

    /** Reads the programme's answer. */
    ObjectMapper objectMapper = new ObjectMapper();

    /** The HTTP client toward the programme. */
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1500)).build();

    /**
     * Whether a loyalty programme is configured for this node.
     *
     * @return true when a base URL is administered or deployed
     */
    public boolean configured() {
        return !baseUrl().isEmpty();
    }

    /**
     * Reads the immediate-discount promotions of a card.
     *
     * @param card the card number
     * @return the promotions as the programme states them, an empty array
     *     for a card the programme does not know
     * @throws Exception when the programme cannot be reached or answers
     *     anything but 200 or 404
     */
    public ArrayNode promotions(String card) throws Exception {
        LOGGER.info("Entering method promotions");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/accounts/" + URLEncoder.encode(card.trim(), StandardCharsets.UTF_8)
                        + "/promotions?benefit=" + BENEFIT_IMMEDIATE_DISCOUNT))
                .timeout(readTimeout())
                .GET();
        String authUser = authUser();
        String authPassword = authPassword();
        if (authUser != null && authPassword != null) {
            builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((authUser + ":" + authPassword).getBytes(StandardCharsets.UTF_8)));
        }
        HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) {
            LOGGER.info("Exiting method promotions: unknown card");
            return objectMapper.createArrayNode();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Fidélité : promotions carte HTTP " + response.statusCode());
        }
        JsonNode body = objectMapper.readTree(response.body());
        if (!body.isArray()) {
            throw new IllegalStateException("Fidélité : promotions carte illisibles");
        }
        LOGGER.info("Exiting method promotions");
        return (ArrayNode) body;
    }

    /**
     * Resolves the programme's base URL: the administered value wins, the
     * {@code pos.fid.url} deployment key otherwise.
     *
     * @return the base URL without trailing blank, empty when neither is set
     */
    String baseUrl() {
        String administered = posSettingsService.fidelityUrl();
        if (administered != null && !administered.isBlank()) {
            return administered.trim();
        }
        return url.orElse("").trim();
    }

    /**
     * Resolves the machine account's user: the administered value wins, the
     * {@code pos.fid.user} deployment key otherwise.
     *
     * @return the user, or null when neither is set
     */
    String authUser() {
        String administered = posSettingsService.fidelityUser();
        if (administered != null && !administered.isBlank()) {
            return administered.trim();
        }
        return user.orElse(null);
    }

    /**
     * Resolves the machine account's password: the administered value wins,
     * the {@code pos.fid.password} deployment key otherwise.
     *
     * @return the password, or null when neither is set
     */
    String authPassword() {
        String administered = posSettingsService.fidelityPassword();
        if (administered != null && !administered.isEmpty()) {
            return administered;
        }
        return password.orElse(null);
    }

    /**
     * Resolves the response delay of the call: the administered loyalty read
     * timeout, or 2500 ms when none is administered.
     *
     * @return the request timeout
     */
    Duration readTimeout() {
        int administered = posSettingsService.fidelityReadTimeoutMs();
        return Duration.ofMillis(administered > 0 ? administered : DEFAULT_READ_TIMEOUT_MS);
    }
}
