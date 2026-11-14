package com.intermarche.edge.service.sync.register;

import com.intermarche.edge.domain.sync.FidEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Loyalty fiscal-event outbox (imfid integration spec §6-7), the fidelity
 * sibling of the store {@code SyncOutboxService}: events are ENQUEUED
 * transactionally with their payload frozen, then DRAINED every 10 seconds
 * to imfid, which answers 202 and is idempotent by ticket reference — rows
 * are retried freely after any outage, in any order (spec: no sequencing
 * POS-side; imfid parks an early return itself).
 * <p>
 * Placement note: this is a background service that never serves the IHM —
 * it lives in {@code service.sync} and carries its OWN minimal HTTP posting
 * (same {@code pos.fid.*} configuration keys as the UI client) so that no
 * service→ui dependency exists.
 * <p>
 * The two loyalty CALLS of the end of a ticket are named after the protocol
 * the store administers (BO-11-04-05, -06): the VT call sends the basket and
 * its settlements when the ticket ends — synchronously, the register waiting
 * for the answer, where the store asks for it — and the GT call resends, at
 * the administered cadence and on its own administered path, every transaction
 * that did not get through at the end of its ticket. Each request names its
 * call in the {@code X-Fidelity-Call} header. The loop resolves the switch,
 * the cadence and the address on every tick, so a value changed in the back
 * office applies at the next one.
 */
@ApplicationScoped
public class FidEventOutboxService {

    private static final Logger LOGGER = Logger.getLogger(FidEventOutboxService.class);

    /** Base URL of imfid; absent = loyalty disabled, the drain is a no-op. */
    @ConfigProperty(name = "pos.fid.url")
    Optional<String> url;

    /** Basic-auth user of the POS machine account. */
    @ConfigProperty(name = "pos.fid.user")
    Optional<String> user;

    /** Basic-auth password of the POS machine account. */
    @ConfigProperty(name = "pos.fid.password")
    Optional<String> password;

    /**
     * The administered parameters (BO-10-03-08, -10): the loyalty URL, the
     * machine account and the response delay the back office sets win over
     * the deployment keys, as on the register's other loyalty calls. Null
     * outside the container, the deployment keys then apply alone.
     */
    @jakarta.inject.Inject
    com.intermarche.edge.service.PosSettingsService posSettingsService;

    /** Shared HTTP client of the drain. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(1500))
            .build();

    /** The drain loop, on the house scheduler pattern (see SyncPushService). */
    private java.util.concurrent.ScheduledExecutorService executor;

    /** The header naming the loyalty call a request is (BO-11-04-05, -06). */
    static final String CALL_HEADER = "X-Fidelity-Call";

    /** When the last GT resend ran, null before the first. */
    LocalDateTime lastDrain;

    /**
     * Starts the GT loop at boot — same pattern as the store push: a single
     * daemon thread, exceptions never kill the loop. It ticks every second and
     * resends only when a resend is DUE (BO-11-04-06): the loyalty service
     * configured, the GT call switched on and the administered cadence
     * elapsed. Started whatever the configuration, because the address and
     * the switch are read on every tick: a register administered later must
     * not need a restart to declare its sales.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fid-events");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::drainSafely, 1, 1,
                java.util.concurrent.TimeUnit.SECONDS);
        LOGGER.info("Renvoi GT fidélité armé");
    }

    /**
     * Stops the drain loop at shutdown.
     */
    @PreDestroy
    void onStop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * Runs one GT tick, never letting an exception kill the loop.
     */
    private void drainSafely() {
        try {
            if (resendDue(LocalDateTime.now())) {
                drain();
            }
        } catch (Exception e) {
            LOGGER.errorf("Cycle d'outbox fidélité en erreur: %s", e.getMessage());
        }
    }

    /**
     * Enqueues a loyalty event with its frozen payload. Runs in (or joins)
     * the caller's transaction: an event exists if and only if the business
     * fact it describes was committed.
     *
     * @param type the event kind
     * @param payloadJson the composed event body, posted as-is at drain
     */
    @Transactional(Transactional.TxType.REQUIRED)
    public void enqueue(FidEvent.EventType type, String payloadJson) {
        LOGGER.info("Entering method enqueue with type: " + type + ", payloadJson: " + payloadJson);
        FidEvent event = new FidEvent();
        event.eventType = type;
        event.payloadJson = payloadJson;
        event.createdAt = LocalDateTime.now();
        event.persist();
        LOGGER.info("Exiting method enqueue");
    }

    /**
     * Tells whether a GT resend is due (BO-11-04-06), and starts its period
     * when it is: the GT call switched on and the administered cadence
     * elapsed since the last resend.
     *
     * @param now the current instant
     * @return true when the pending events are to be resent now
     */
    boolean resendDue(LocalDateTime now) {
        boolean enabled = posSettingsService == null || posSettingsService.fidelityGtEnabled();
        int seconds = posSettingsService == null ? 10 : posSettingsService.fidelityGtSeconds();
        if (!enabled || (lastDrain != null && now.isBefore(lastDrain.plusSeconds(seconds)))) {
            return false;
        }
        lastDrain = now;
        return true;
    }

    /**
     * Makes the VT call of a closed ticket SYNCHRONOUSLY (BO-11-04-05): the
     * basket goes to imfid now, the register waiting for its answer within the
     * administered delay, and the event is recorded either way — as SENT when
     * imfid accepted it, as PENDING otherwise, for the GT call to resend.
     * Nothing is lost: an unreachable service only defers the declaration.
     *
     * @param type the event kind
     * @param payloadJson the composed event body
     * @return true when imfid accepted the call
     */
    @Transactional(Transactional.TxType.REQUIRED)
    public boolean sendNow(FidEvent.EventType type, String payloadJson) {
        LOGGER.info("Entering method sendNow with type: " + type);
        FidEvent event = new FidEvent();
        event.eventType = type;
        event.payloadJson = payloadJson;
        event.createdAt = LocalDateTime.now();
        event.attempts = 1;
        boolean accepted = false;
        if (baseUrl().isEmpty()) {
            event.lastError = "service fidélité non configuré";
        } else {
            try {
                int status = post(vtPath(type), payloadJson, "VT");
                accepted = status == 202;
                event.lastError = accepted ? null : "HTTP " + status;
            } catch (Exception e) {
                event.lastError = e.getMessage();
            }
        }
        if (accepted) {
            event.sentAt = LocalDateTime.now();
        } else {
            LOGGER.warnf("Appel VT fidélité non abouti (%s): confié au renvoi GT", event.lastError);
        }
        event.persist();
        LOGGER.info("Exiting method sendNow");
        return accepted;
    }

    /**
     * The path of the VT call of an event kind.
     *
     * @param type the event kind
     * @return the imfid endpoint path
     */
    static String vtPath(FidEvent.EventType type) {
        return type == FidEvent.EventType.TICKET_CLOSED
                ? "/api/events/ticket-closed" : "/api/events/ticket-return";
    }

    /**
     * The path of the GT resend of an event kind (BO-11-04-06): the
     * administered GT path, or the VT path of the kind when none is.
     *
     * @param type the event kind
     * @return the imfid endpoint path
     */
    String gtPath(FidEvent.EventType type) {
        String administered = posSettingsService == null ? "" : posSettingsService.fidelityGtPath();
        return administered == null || administered.isBlank() ? vtPath(type) : administered.trim();
    }

    /**
     * Drains the pending events to imfid. 202 marks the row sent; anything
     * else (transport failure included) leaves it pending for the next
     * cycle — the retry IS the degraded mode (spec §2.2: defer and replay).
     * <p>
     * Runs on the executor thread: the transaction is opened EXPLICITLY
     * ({@code QuarkusTransaction}) because a self-call from the loop would
     * bypass the {@code @Transactional} interceptor.
     */
    public void drain() {
        LOGGER.info("Entering method drain");
        if (baseUrl().isEmpty()) {
            LOGGER.info("Exiting method drain");
            return;
        }
        QuarkusTransaction.requiringNew().run(this::drainInTransaction);
        LOGGER.info("Exiting method drain");
    }

    /**
     * The transactional body of one drain cycle.
     */
    private void drainInTransaction() {
        List<FidEvent> pending = FidEvent.findPending();
        for (FidEvent event : pending) {
            event.attempts++;
            try {
                int status = post(gtPath(event.eventType), event.payloadJson, "GT");
                if (status == 202) {
                    event.sentAt = LocalDateTime.now();
                    event.lastError = null;
                } else {
                    event.lastError = "HTTP " + status;
                    LOGGER.warnf("Événement fidélité %d refusé (%s): nouvel essai au prochain cycle",
                            event.id, event.lastError);
                }
            } catch (Exception e) {
                event.lastError = e.getMessage();
                LOGGER.debugf("imfid injoignable pour l'événement %d: rejoué au prochain cycle", event.id);
                return; // Down: no point hammering the rest of the queue now.
            }
        }
    }

    /**
     * Posts one event body to imfid.
     *
     * @param path the endpoint path
     * @param payloadJson the body
     * @param call the loyalty call the request is, VT or GT
     * @return the HTTP status
     * @throws Exception on transport failure
     */
    private int post(String path, String payloadJson, String call) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .header(CALL_HEADER, call)
                .timeout(readTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(payloadJson));
        String authUser = authUser();
        String authPassword = authPassword();
        if (authUser != null && authPassword != null) {
            builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((authUser + ":" + authPassword).getBytes()));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    /**
     * Resolves the imfid base URL (BO-10-03-08): the administered value wins,
     * the {@code pos.fid.url} deployment key otherwise.
     *
     * @return the base URL, empty when neither is set
     */
    String baseUrl() {
        String administered = posSettingsService != null ? posSettingsService.fidelityUrl() : null;
        if (administered != null && !administered.isBlank()) {
            return administered.trim();
        }
        return url.orElse("");
    }

    /**
     * Resolves the Basic-auth user (BO-10-03-08): the administered value wins,
     * the {@code pos.fid.user} deployment key otherwise.
     *
     * @return the user, or null when neither is set
     */
    String authUser() {
        String administered = posSettingsService != null ? posSettingsService.fidelityUser() : null;
        if (administered != null && !administered.isBlank()) {
            return administered.trim();
        }
        return user.orElse(null);
    }

    /**
     * Resolves the Basic-auth password (BO-10-03-08): the administered value
     * wins, the {@code pos.fid.password} deployment key otherwise.
     *
     * @return the password, or null when neither is set
     */
    String authPassword() {
        String administered = posSettingsService != null ? posSettingsService.fidelityPassword() : null;
        if (administered != null && !administered.isEmpty()) {
            return administered;
        }
        return password.orElse(null);
    }

    /**
     * Resolves the response delay of one event post (BO-10-03-10): the
     * administered read timeout, or the historical 2500 ms when none is set.
     *
     * @return the request timeout
     */
    Duration readTimeout() {
        int administered = posSettingsService != null ? posSettingsService.fidelityReadTimeoutMs() : 0;
        return Duration.ofMillis(administered > 0 ? administered : 2500);
    }
}
