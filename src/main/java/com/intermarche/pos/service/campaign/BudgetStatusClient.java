package com.intermarche.pos.service.campaign;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intermarche.pos.domain.util.DateTimeProvider;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Reads a campaign's consumption from the engine, LIVE, over GraphQL
 * ({@code groupBudgetStatus}) — the store node keeps no counter of its own
 * (spec §5).
 *
 * <p>The read URL is a DEDICATED property, {@code pos.valuation.query-url}, and
 * NOT the feed-delivery URL {@code pos.valuation.url}: in the demo stack the
 * node's delivery URL is deliberately blanked to cut its own feed delivery, and
 * the consumption read must keep working regardless. When the query URL is not
 * configured, the read is disabled and the screen shows "engine unreachable"
 * rather than failing.
 *
 * <p>The read is a DISPLAY read: it never blocks and never throws. When the
 * engine is not configured or answers badly, an {@code unavailable} status is
 * returned so the screen can say so rather than fail. Every status carries the
 * instant it was read, because the global budget is approximate by construction
 * and the screen must show when it was looked at.
 */
@ApplicationScoped
public class BudgetStatusClient {

    /** Logger for this client. */
    private static final Logger LOGGER = Logger.getLogger(BudgetStatusClient.class);

    /** Dedicated read URL of the engine; absent = status read disabled. */
    @ConfigProperty(name = "pos.valuation.query-url")
    Optional<String> url;

    /** Basic-auth user of the engine; absent = no authentication header. */
    @ConfigProperty(name = "pos.valuation.user")
    Optional<String> user;

    /** Basic-auth password of the engine. */
    @ConfigProperty(name = "pos.valuation.password")
    Optional<String> password;

    /** Read timeout in milliseconds (a screen is waiting). */
    @ConfigProperty(name = "pos.valuation.timeout-millis", defaultValue = "2500")
    long timeoutMillis;

    /** BigDecimal-safe, tolerant JSON mapper. */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** The HTTP client toward the engine. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /**
     * One point of sale's slice of a campaign's consumption.
     *
     * @param storeCode the point-of-sale code
     * @param consumedAmount the amount consumed at that store
     * @param consumedCount the applications counted at that store
     */
    public record StoreBudgetStatus(String storeCode, java.math.BigDecimal consumedAmount,
            Integer consumedCount) {
    }

    /**
     * A campaign's consumption as read from the engine: consumed / ceiling /
     * remaining, in euros and in count, global and per store, with the read
     * instant.
     *
     * @param available whether the engine answered
     * @param consumedAmount the amount consumed
     * @param maxAmount the amount ceiling
     * @param remainingAmount the amount remaining
     * @param consumedCount the applications consumed
     * @param maxApplications the applications ceiling
     * @param remainingCount the applications remaining
     * @param byStore the per-store breakdown, never null
     * @param readAt when the engine was read
     */
    public record BudgetStatus(boolean available, java.math.BigDecimal consumedAmount,
            java.math.BigDecimal maxAmount, java.math.BigDecimal remainingAmount,
            Integer consumedCount, Integer maxApplications, Integer remainingCount,
            List<StoreBudgetStatus> byStore, LocalDateTime readAt) {

        /**
         * Builds the status returned when the engine could not be read.
         *
         * @param readAt when the read was attempted
         * @return an unavailable status
         */
        static BudgetStatus unavailable(LocalDateTime readAt) {
            return new BudgetStatus(false, null, null, null, null, null, null, List.of(), readAt);
        }
    }

    /** Tolerant DTO for the GraphQL envelope. */
    static class GraphQlResponse {
        /** The data block. */
        public DataDto data;
    }

    /** Tolerant DTO for the data block. */
    static class DataDto {
        /** The queried group budget status. */
        public StatusDto groupBudgetStatus;
    }

    /** Tolerant DTO for the group budget status. */
    static class StatusDto {
        /** Consumed amount. */
        public java.math.BigDecimal consumedAmount;
        /** Amount ceiling. */
        public java.math.BigDecimal maxAmount;
        /** Remaining amount. */
        public java.math.BigDecimal remainingAmount;
        /** Consumed applications. */
        public Integer consumedCount;
        /** Applications ceiling. */
        public Integer maxApplications;
        /** Remaining applications. */
        public Integer remainingCount;
        /** Per-store breakdown. */
        public List<StoreDto> byStore;
    }

    /** Tolerant DTO for one per-store row. */
    static class StoreDto {
        /** Point-of-sale code. */
        public String storeCode;
        /** Amount consumed at the store. */
        public java.math.BigDecimal consumedAmount;
        /** Applications counted at the store. */
        public Integer consumedCount;
    }

    /**
     * Tells whether the engine is configured for a status read.
     *
     * @return true when {@code pos.valuation.query-url} is set and non-blank
     */
    public boolean isEnabled() {
        return url.filter(u -> !u.isBlank()).isPresent();
    }

    /**
     * Reads a campaign's consumption from the engine, live.
     *
     * @param campaignCode the campaign (budget group) code
     * @return the status, or an unavailable status when the engine is off or
     *         unreadable — never null, never thrown
     */
    public BudgetStatus groupBudgetStatus(String campaignCode) {
        LOGGER.info("Entering method groupBudgetStatus with campaignCode: " + campaignCode);
        LocalDateTime readAt = DateTimeProvider.now();
        if (!isEnabled()) {
            LOGGER.info("Exiting method groupBudgetStatus: disabled");
            return BudgetStatus.unavailable(readAt);
        }
        try {
            HttpResponse<String> response = httpClient.send(request(campaignCode),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warnf("Statut budget %s: HTTP %d", campaignCode, response.statusCode());
                return BudgetStatus.unavailable(readAt);
            }
            GraphQlResponse parsed = objectMapper.readValue(response.body(), GraphQlResponse.class);
            if (parsed.data == null || parsed.data.groupBudgetStatus == null) {
                LOGGER.info("Exiting method groupBudgetStatus: no data");
                return BudgetStatus.unavailable(readAt);
            }
            LOGGER.info("Exiting method groupBudgetStatus");
            return toStatus(parsed.data.groupBudgetStatus, readAt);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.warnf("Statut budget %s illisible: %s", campaignCode, e.getMessage());
            return BudgetStatus.unavailable(readAt);
        }
    }

    /**
     * Builds the GraphQL request for a campaign's status.
     *
     * @param campaignCode the campaign code
     * @return the HTTP request
     * @throws Exception when the query cannot be serialized
     */
    private HttpRequest request(String campaignCode) throws Exception {
        ObjectNode variables = objectMapper.createObjectNode();
        variables.put("code", campaignCode);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("query", "query($code:String!){groupBudgetStatus(code:$code){consumedAmount "
                + "maxAmount remainingAmount consumedCount maxApplications remainingCount "
                + "byStore{storeCode consumedAmount consumedCount}}}");
        payload.set("variables", variables);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url.orElseThrow() + "/graphql"))
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
        String basicUser = user.orElse("");
        if (!basicUser.isBlank()) {
            String token = Base64.getEncoder().encodeToString(
                    (basicUser + ":" + password.orElse("")).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + token);
        }
        return builder.build();
    }

    /**
     * Maps the engine DTO to the public status.
     *
     * @param dto the engine status DTO
     * @param readAt when it was read
     * @return the public status
     */
    private BudgetStatus toStatus(StatusDto dto, LocalDateTime readAt) {
        List<StoreBudgetStatus> byStore = new ArrayList<>();
        if (dto.byStore != null) {
            for (StoreDto store : dto.byStore) {
                byStore.add(new StoreBudgetStatus(store.storeCode, store.consumedAmount,
                        store.consumedCount));
            }
        }
        return new BudgetStatus(true, dto.consumedAmount, dto.maxAmount, dto.remainingAmount,
                dto.consumedCount, dto.maxApplications, dto.remainingCount, byStore, readAt);
    }
}
