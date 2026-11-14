package com.intermarche.edge.ui.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.domain.catalog.EegProvider;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The connectors to the ELECTRONIC SHELF LABEL providers (BO-02-03-37,
 * BO-02-03-38): read the price a label displays right now, send the prices of
 * a list of articles.
 *
 * <p>Two connectors, chosen by the provider's kind:
 * <ul>
 *   <li>{@code HTTP} — the provider's REST API: {@code GET
 *       {baseUrl}/prices/{ean}} answers {@code {"price": 1.99}}, {@code POST
 *       {baseUrl}/prices} takes {@code [{"ean": "…", "price": 1.99}, …]};</li>
 *   <li>{@code SIMULATED} — an in-process label wall: it displays what was last
 *       sent to it, which is what a demonstration store needs to show the round
 *       trip without a label on its shelves.</li>
 * </ul>
 * A provider that does not answer reads as "no price" and a send that fails is
 * counted as failed, never as an exception reaching the screen: the labels are
 * an outside system, and the back office must stay usable when they are down.
 */
@ApplicationScoped
public class EegGateway {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EegGateway.class);

    /** The JSON reader and writer of the HTTP connector. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The prices the simulated label walls display, by provider code then EAN. */
    final Map<String, Map<String, BigDecimal>> simulated = new ConcurrentHashMap<>();

    /** The HTTP client of the REST connector. */
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /**
     * Reads the price a provider's label displays for an article right now.
     *
     * @param provider the provider
     * @param ean the article's EAN
     * @return the displayed price, or null when the provider has none or does
     *         not answer
     */
    public BigDecimal readPrice(EegProvider provider, String ean) {
        if (EegProvider.KIND_SIMULATED.equals(provider.kind)) {
            return simulated.getOrDefault(provider.code, Map.of()).get(ean);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(root(provider) + "/prices/"
                            + URLEncoder.encode(ean, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            JsonNode price = JSON.readTree(response.body()).get("price");
            return price == null || !price.isNumber() ? null : price.decimalValue();
        } catch (Exception e) {
            LOGGER.warnf("EEG %s injoignable en lecture: %s", provider.code, e.getMessage());
            return null;
        }
    }

    /**
     * Sends prices to a provider.
     *
     * @param provider the provider
     * @param prices the prices to display, by EAN
     * @return true when the provider took them
     */
    public boolean push(EegProvider provider, Map<String, BigDecimal> prices) {
        if (EegProvider.KIND_SIMULATED.equals(provider.kind)) {
            simulated.computeIfAbsent(provider.code, code -> new ConcurrentHashMap<>()).putAll(prices);
            return true;
        }
        try {
            List<Map<String, Object>> body = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> entry : prices.entrySet()) {
                body.add(Map.of("ean", entry.getKey(), "price", entry.getValue()));
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(root(provider) + "/prices"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() / 100 == 2;
        } catch (Exception e) {
            LOGGER.warnf("EEG %s injoignable en envoi: %s", provider.code, e.getMessage());
            return false;
        }
    }

    /**
     * The provider's root URL without its trailing slash.
     *
     * @param provider the provider
     * @return the root URL, empty when none is administered
     */
    private static String root(EegProvider provider) {
        String url = provider.baseUrl == null ? "" : provider.baseUrl.trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
