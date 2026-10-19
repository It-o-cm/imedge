package com.intermarche.pos.service;

import com.intermarche.pos.service.sync.register.SyncOutboxService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * The register's UPWARD NOTICES to the store node: a supervisor settled
 * this lane's open requests at the till ({@code BO-04-02-03}), and an
 * article the referential does not know was just scanned
 * ({@code BO-04-02-06}).
 *
 * <p>A cashier calls for a supervisor, and more often than not the
 * supervisor is standing three metres away and settles it before anybody at
 * the back office has read the banner. Until here the banner stayed up, and
 * a manager walked to a lane whose problem was already solved. The register
 * is the only one who knows, so the register says so.
 *
 * <p>FIRE AND FORGET, and deliberately so: neither an endorsement nor a
 * scan must fail because the node is down or slow. Every call carries a
 * three-second timeout and swallows everything — the worst case is a banner
 * a manager acknowledges by hand, or an alert that never shows, which is
 * exactly the behaviour of a shop that administers none.
 */
@ApplicationScoped
public class SupervisorSettleService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SupervisorSettleService.class);

    /** How long the register waits for the node before giving up. */
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** The client talking to the node, shared like the supervisor call's. */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    /** Says whether this register has a store node, and where it is. */
    @Inject
    SyncOutboxService syncOutboxService;

    /** This register's identifier, the address the node files the calls under. */
    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    /** Shared token sent with the notice; absent = the node is open. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /**
     * Tells the node that this register's open requests were settled at the
     * till; silent when the register stands alone.
     */
    public void settledAtTill() {
        LOGGER.info("Entering method settledAtTill");
        post("/api/supervisor/settled", "{\"terminalId\":\"" + terminalId + "\"}",
                "l'avis de traitement en caisse");
        LOGGER.info("Exiting method settledAtTill");
    }

    /**
     * Raises a service request at the back office for an article the
     * referential does not know ({@code BO-04-02-06}).
     *
     * <p>The SAME channel as a supervisor call, and on purpose: an unknown
     * article is a lane asking for help, and the back office already has a
     * board for that — with its priority, its status and its three answers.
     * A second channel would have meant a second board.
     *
     * @param code the code the cashier scanned, possibly null
     * @param operator who scanned it, possibly null
     */
    public void unknownItem(String code, String operator) {
        LOGGER.info("Entering method unknownItem with code: " + code);
        String scanned = code == null || code.isBlank() ? "?" : code.trim();
        post("/api/supervisor/call",
                "{\"terminalId\":\"" + terminalId + "\",\"operator\":\""
                        + escape(operator) + "\",\"reason\":\"ARTICLE INCONNU\","
                        + "\"message\":\"Code " + escape(scanned) + "\"}",
                "l'alerte article inconnu");
        LOGGER.info("Exiting method unknownItem");
    }

    /**
     * Posts a notice to the node, swallowing whatever it fails on; silent
     * when this register stands alone.
     *
     * @param path the node path to post to
     * @param body the JSON body
     * @param what the notice's name, for the log of a failure
     */
    private void post(String path, String body, String what) {
        if (!syncOutboxService.isEnabled()) {
            return;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(syncOutboxService.getStoreUrl() + path))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            String sharedToken = token.orElse("");
            if (!sharedToken.isBlank()) {
                builder.header("X-Sync-Token", sharedToken);
            }
            HttpResponse<String> response =
                    CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warnf("Le nœud a refusé %s (%d)", what, response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warnf("%s interrompu", what);
        } catch (Exception e) {
            // The gesture has already happened at the till: the notice is a
            // courtesy to the back office, never a condition.
            LOGGER.warnf("%s impossible (%s)", what, e.getMessage());
        }
    }

    /**
     * Escapes what goes into a JSON string literal: the two characters that
     * would end it or start an escape of their own.
     *
     * @param value the value, possibly null
     * @return the escaped value, empty for a null
     */
    private String escape(String value) {
        return value == null ? ""
                : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
