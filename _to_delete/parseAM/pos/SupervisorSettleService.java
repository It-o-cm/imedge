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
 * Tells the store node that a supervisor settled this register's open
 * requests AT THE TILL ({@code BO-04-02-03}).
 *
 * <p>A cashier calls for a supervisor, and more often than not the
 * supervisor is standing three metres away and settles it before anybody at
 * the back office has read the banner. Until here the banner stayed up, and
 * a manager walked to a lane whose problem was already solved. The register
 * is the only one who knows, so the register says so.
 *
 * <p>FIRE AND FORGET, and deliberately so: an endorsement must not fail
 * because the node is down or slow. The call carries a three-second timeout
 * and swallows everything — the worst case is a banner a manager
 * acknowledges by hand, which is exactly the behaviour of today.
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
        if (!syncOutboxService.isEnabled()) {
            LOGGER.info("Exiting method settledAtTill: no store node");
            return;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(syncOutboxService.getStoreUrl() + "/api/supervisor/settled"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"terminalId\":\"" + terminalId + "\"}"));
            String sharedToken = token.orElse("");
            if (!sharedToken.isBlank()) {
                builder.header("X-Sync-Token", sharedToken);
            }
            HttpResponse<String> response =
                    CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warnf("Le nœud a refusé l'avis de traitement en caisse (%d)",
                        response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Avis de traitement en caisse interrompu");
        } catch (Exception e) {
            // The endorsement has already been granted and journalled: the
            // notice is a courtesy to the back office, never a condition.
            LOGGER.warnf("Avis de traitement en caisse impossible (%s)", e.getMessage());
        }
        LOGGER.info("Exiting method settledAtTill");
    }
}
