package com.intermarche.edge.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.edge.imports.OrderRecallResource;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The PULL of the commercial management tool's documents (BO-10-01-25): where
 * the store administers that the documents travel from the tool to the node by
 * the node's initiative — the direction PULL, rather than the tool's own
 * deposit on {@code POST /api/orders} — the node asks the administered address
 * for them at the administered frequency and stores each one exactly as a
 * deposit would, so the registers recall it by its code.
 *
 * <p>The tool answers the JSON list of its documents in the deposit's shape; a
 * document already held is refreshed, never duplicated, so a pull may safely
 * hand the same documents again. Tolerant like every loop of the node: an
 * unreachable tool, a non-2xx answer or an unreadable list costs this round,
 * never the loop.
 */
@ApplicationScoped
public class CommercialOrderPullService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CommercialOrderPullService.class);

    /** How often the loop looks whether a pull is due, in seconds. */
    static final int TICK_SECONDS = 60;

    /** A lenient reader: the tool may send fields this node does not use. */
    private static final ObjectMapper READER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** The administered direction, address and frequency. */
    @Inject
    PosSettingsService posSettingsService;

    /** Stores each document pulled. */
    @Inject
    CustomerOrderService customerOrderService;

    /** The node's role: only a store node holds the documents. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** The HTTP client toward the tool. */
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** The clock of the rounds, in epoch milliseconds. */
    LongSupplier now = System::currentTimeMillis;

    /** When the last pull started, in epoch milliseconds. */
    private volatile long lastPull = 0L;

    /** The loop executor, or null when the loop is not running. */
    private ScheduledExecutorService executor;

    /**
     * Starts the loop on a store node.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        if (!"store".equalsIgnoreCase(role)) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "commercial-pull");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stops the loop.
     */
    @PreDestroy
    void onStop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * Runs a round in a transaction of its own, never letting a failure stop
     * the loop.
     */
    void tick() {
        try {
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(this::round);
        } catch (Exception e) {
            LOGGER.warnf("Tirage des commandes de la gestion commerciale en erreur : %s", e.getMessage());
        }
    }

    /**
     * Pulls the documents when the direction is PULL, an address is administered
     * and the administered interval has elapsed since the last pull.
     *
     * @return how many documents were stored this round
     * @throws Exception when the tool cannot be read
     */
    int round() throws Exception {
        String url = posSettingsService.commercialPullUrl();
        if (!"PULL".equals(posSettingsService.commercialTransmission()) || url.isEmpty()) {
            return 0;
        }
        long current = now.getAsLong();
        long interval = Math.max(1, posSettingsService.commercialPullMinutes()) * 60_000L;
        if (current - lastPull < interval) {
            return 0;
        }
        lastPull = current;
        return pull(url);
    }

    /**
     * Reads the tool's documents and stores each readable one.
     *
     * @param url the administered address
     * @return how many documents were stored
     * @throws Exception when the tool cannot be reached or its answer read
     */
    int pull(String url) throws Exception {
        LOGGER.info("Entering method pull with url: " + url);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            LOGGER.info("Exiting method pull: the tool answered " + response.statusCode());
            return 0;
        }
        OrderRecallResource.OrderPushDto[] documents =
                READER.readValue(response.body(), OrderRecallResource.OrderPushDto[].class);
        int stored = 0;
        for (OrderRecallResource.OrderPushDto document : documents) {
            if (OrderRecallResource.readable(document)
                    && OrderRecallResource.store(customerOrderService, document) != null) {
                stored++;
            }
        }
        LOGGER.info("Exiting method pull: " + stored + " document(s) stored");
        return stored;
    }
}
