package com.intermarche.pos.service.sync;

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
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Delivery loop of the verbatim feeds toward the valuation engine attached
 * to THIS node — the last hop of the single-import-line chain (central →
 * store POS → register POS → local imvaluation). Runs on every role: the
 * store node feeds its own engine exactly like a register feeds its own.
 * <p>
 * Every {@code pos.valuation.feed-delivery-seconds}, walks the catalog IN
 * ORDER and, for each feed whose stored version differs from the version
 * the engine last acknowledged, POSTs the raw content to the engine's
 * import endpoint (HTTP Basic, same credentials as the valuation calls).
 * A 2xx answer is the ACK: the applied version is recorded. On the first
 * failure the walk STOPS — shipping OFFERS while PRODUCTS failed would
 * break the shared-before-specific application order — the error is
 * recorded on the feed and the next tick retries.
 * <p>
 * Disabled when {@code pos.valuation.url} is absent, like every engine
 * interaction on this register.
 */
@ApplicationScoped
public class EngineFeedDeliveryService {

    private static final Logger LOGGER = Logger.getLogger(EngineFeedDeliveryService.class);

    /** Base URL of the local engine; absent = delivery disabled. */
    @ConfigProperty(name = "pos.valuation.url")
    Optional<String> url;

    /** Basic-auth user of the engine; absent = no authentication header. */
    @ConfigProperty(name = "pos.valuation.user")
    Optional<String> user;

    /** Basic-auth password of the engine. */
    @ConfigProperty(name = "pos.valuation.password")
    Optional<String> password;

    /** Seconds between two delivery cycles. */
    @ConfigProperty(name = "pos.valuation.feed-delivery-seconds", defaultValue = "30")
    long deliverySeconds;

    /** Import call timeout in milliseconds (bulk ingestion can be slow). */
    @ConfigProperty(name = "pos.valuation.feed-timeout-millis", defaultValue = "60000")
    long timeoutMillis;

    /** The feed keeper (catalog, contents, acknowledgements). */
    @Inject
    EngineFeedService engineFeedService;

    /** The keeper of the deferred deliveries this loop runs on its wake. */
    @Inject
    ScheduledDeliveryService scheduledDeliveryService;

    /** The delivery loop executor, or null when delivery is disabled. */
    private ScheduledExecutorService executor;

    /** The HTTP client toward the local engine. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * Starts the delivery loop at startup when an engine URL is configured;
     * the first cycle runs shortly after boot so a fresh node fills its
     * engine quickly.
     *
     * @param event the Quarkus startup event
     */
    void onStart(@Observes StartupEvent event) {
        if (url.isEmpty() || url.get().isBlank()) {
            LOGGER.info("Livraison des flux moteur désactivée (pos.valuation.url absent)");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "engine-feed-delivery");
            thread.setDaemon(true);
            return thread;
        });
        // First run after min(20s, period): a demo stack with a short
        // period gets its first delivery quickly, production keeps the
        // boot-quiet 20 seconds.
        executor.scheduleWithFixedDelay(this::deliverSafely,
                Math.min(20, deliverySeconds), deliverySeconds, TimeUnit.SECONDS);
        LOGGER.infof("Livraison des flux moteur active vers %s (toutes les %ds)", url.get(), deliverySeconds);
    }

    /**
     * Stops the delivery loop when the bean is destroyed (application
     * shutdown) — the same lifecycle pattern as the other scheduled
     * services: without this the thread outlives its CDI container,
     * harmless in production where the JVM dies with the application, but
     * a zombie in a test JVM where Quarkus instances succeed one another,
     * each dead instance's loop then failing every cycle against a closed
     * container.
     */
    @PreDestroy
    void onStop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * Runs one delivery cycle, never letting an exception kill the loop.
     */
    void deliverSafely() {
        try {
            deliverPending();
            runScheduled();
        } catch (Exception e) {
            LOGGER.warnf("Cycle de livraison moteur en échec: %s", e.getMessage());
        }
    }

    /**
     * Runs the deferred deliveries whose minute has passed (GB-01-01-02), once
     * per loop wake. Each due booking is claimed as a detached snapshot, run
     * through the same delivery path as the manual actions — the global
     * {@link #triggerDelivery()} when it targets every feed, the targeted
     * {@link #triggerDelivery(String)} when it names one — and its outcome is
     * recorded (DONE or FAILED). A booking that fails does not stop the others.
     */
    void runScheduled() {
        for (ScheduledDeliveryService.Due due : scheduledDeliveryService.claimDue()) {
            String error = due.feedCode() == null
                    ? triggerDelivery()
                    : triggerDelivery(due.feedCode());
            scheduledDeliveryService.markResult(due.id(), error);
        }
    }

    /**
     * Delivers every pending feed, in catalog order, from the detached
     * snapshot handed by the transactional keeper (this thread has no
     * persistence context of its own); stops at the first failure to
     * preserve the application order.
     */
    void deliverPending() {
        for (EngineFeedService.PendingFeed feed : engineFeedService.pendingFeeds()) {
            if (!deliver(feed)) return;
        }
    }

    /**
     * Runs one delivery cycle NOW, on the caller's thread, on demand from the
     * sync supervision screen (BO-08-04-13). It is the manual counterpart of
     * the scheduled {@link #deliverSafely()}: same {@link #deliverPending()},
     * but the outcome is returned rather than swallowed. A per-feed HTTP
     * failure is recorded on the feed itself (visible in the feed table) and
     * does NOT surface here — only an error reaching the pending-feed lookup
     * does.
     *
     * @return null when the cycle ran, or the failure message otherwise
     */
    public String triggerDelivery() {
        LOGGER.info("Entering method triggerDelivery");
        try {
            deliverPending();
            LOGGER.info("Exiting method triggerDelivery");
            return null;
        } catch (Exception e) {
            LOGGER.warnf("Livraison manuelle des flux moteur en échec: %s", e.getMessage());
            LOGGER.info("Exiting method triggerDelivery");
            return e.getMessage();
        }
    }

    /**
     * Delivers ONE feed NOW, by code, on the caller's thread — the targeted
     * variant of {@link #triggerDelivery()} behind the per-feed "Livrer
     * maintenant" button (GB-01-01-01). Same delivery path, same transactional
     * keeper, same journal as the global relaunch; only the reached feed is the
     * one whose code matches, the others are left untouched.
     * <p>
     * It is ALSO the retry of a feed left in error (GB-01-01-19/30): the button
     * on an errored row is this same call. Re-running it is safe by construction
     * because the whole chain downstream is idempotent — the engine imports each
     * parcel by its checksum and the till consumes each result by ticket — so a
     * feed already applied is a no-op on the engine and a feed half-applied is
     * completed rather than doubled. A code with nothing pending (already
     * acknowledged, or unknown to this node) therefore returns cleanly without
     * touching the engine.
     *
     * @param code the feed code to deliver
     * @return null when the feed was delivered (or nothing was pending), or the
     *         failure message when this feed's delivery failed
     */
    public String triggerDelivery(String code) {
        LOGGER.info("Entering method triggerDelivery with code: " + code);
        try {
            for (EngineFeedService.PendingFeed feed : engineFeedService.pendingFeeds()) {
                if (feed.code().equals(code)) {
                    boolean delivered = deliver(feed);
                    LOGGER.info("Exiting method triggerDelivery");
                    return delivered ? null : deliveryError(code);
                }
            }
            LOGGER.info("Exiting method triggerDelivery");
            return null;
        } catch (Exception e) {
            LOGGER.warnf("Livraison ciblée du flux moteur %s en échec: %s", code, e.getMessage());
            LOGGER.info("Exiting method triggerDelivery");
            return e.getMessage();
        }
    }

    /**
     * Reads back the error the keeper recorded for a feed whose targeted
     * delivery just failed, so the screen and the schedule can show WHY rather
     * than a bare "failed".
     *
     * @param code the feed code whose failure was just recorded
     * @return the recorded error, or a generic message when none is readable
     */
    private String deliveryError(String code) {
        for (EngineFeedService.FeedState state : engineFeedService.feedStates()) {
            if (code.equals(state.code()) && state.lastError() != null) {
                return state.lastError();
            }
        }
        return "livraison du flux " + code + " en échec";
    }

    /**
     * The configured cadence between two scheduled delivery cycles, surfaced
     * read-only on the supervision screen (BO-08-03-08).
     *
     * @return the delivery period in seconds
     */
    public long getDeliverySeconds() {
        LOGGER.info("Entering method getDeliverySeconds");
        LOGGER.info("Exiting method getDeliverySeconds");
        return deliverySeconds;
    }

    /**
     * Delivers one feed to the engine and records the outcome.
     *
     * @param feed the detached snapshot to deliver
     * @return true when the engine acknowledged (2xx), false otherwise
     */
    private boolean deliver(EngineFeedService.PendingFeed feed) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url.orElseThrow() + feed.enginePath()))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(feed.content()));
            if (user.isPresent() && !user.get().isBlank()) {
                String token = Base64.getEncoder().encodeToString(
                        (user.get() + ":" + password.orElse("")).getBytes());
                builder.header("Authorization", "Basic " + token);
            }
            HttpResponse<String> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                engineFeedService.markApplied(feed.code(), feed.version());
                // THE ACKNOWLEDGEMENT IS ALREADY DONE WHEN THIS LINE RUNS, so the log may
                // not throw: an unguarded substring on a version shorter than twelve
                // characters raised StringIndexOutOfBoundsException after markApplied and
                // before the return, and the method's own catch turned a delivered feed
                // into a recorded failure that stopped the walk.
                LOGGER.infof("Flux moteur %s livré (version %s)", feed.code(), shortVersion(feed.version()));
                return true;
            }
            recordFailure(feed, "HTTP " + response.statusCode() + " sur " + feed.enginePath()
                    + ": " + Objects.toString(response.body(), ""));
            return false;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            recordFailure(feed, e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Shortens a feed version to what a log line needs.
     *
     * <p>A VERSION IS NOT GUARANTEED TO BE TWELVE CHARACTERS LONG. Today's versions
     * happen to be, which is a naming convention and not a contract; a shorter one —
     * a hand-set version, a test fixture, a future format — must shorten to itself
     * rather than throw, because the only caller runs after the feed has already been
     * acknowledged and a throw there is read as a delivery failure.
     *
     * @param version the feed version, or null
     * @return its first twelve characters at most, empty when there is no version
     */
    private String shortVersion(String version) {
        if (version == null) {
            return "";
        }
        return version.substring(0, Math.min(12, version.length()));
    }

    /**
     * Records a delivery failure, logging it only when the message changed
     * since the previous attempt — an engine down for an hour is one line,
     * not one per tick.
     *
     * @param feed the snapshot whose delivery failed
     * @param error the failure message
     */
    private void recordFailure(EngineFeedService.PendingFeed feed, String error) {
        if (!Objects.equals(feed.lastError(), error)) {
            LOGGER.warnf("Livraison du flux moteur %s en échec: %s", feed.code(), error);
        }
        engineFeedService.markError(feed.code(), error);
    }
}
