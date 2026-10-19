package com.intermarche.pos.ui.supervision;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.home.HomeService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically PULLS the back-office message addressed to this caisse and shows
 * it (EXP-20), on the same scheduled-loop idiom as the referential pull.
 *
 * <p>Pull, not push: the caisse asks the node, so the node never needs a caisse
 * address. The loop starts only when {@code pos.message.node-url} is configured
 * (a standalone lane runs none), and each tick is TOLERANT — a null fetch (no
 * message, node down, malformed answer) leaves the current banner untouched, so
 * a network hiccup neither clears a shown message nor disturbs the sale. An
 * explicit blank message from the node clears the banner (through
 * {@link HomeService#receiveBackOfficeMessage}). The node producer is imedge.
 */
@ApplicationScoped
public class BackOfficeMessagePoller {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(BackOfficeMessagePoller.class);

    /** The pull client toward the node. */
    @Inject
    BackOfficeMessageClient client;

    /** Applies the pulled message to the shared sale state. */
    @Inject
    HomeService homeService;

    /** The shared POS state, touched so the poll surfaces the banner. */
    @Inject
    PosState state;

    /** The node's pending-message endpoint; absent = the loop never starts. */
    @ConfigProperty(name = "pos.message.node-url")
    Optional<String> nodeUrl;

    /** Seconds between two message polls. */
    @ConfigProperty(name = "pos.message.poll-seconds", defaultValue = "20")
    long pollSeconds;

    /** The poll loop executor, or null when the poll is disabled. */
    private ScheduledExecutorService executor;

    /**
     * Starts the message-poll loop at startup when a node message URL is
     * configured; a standalone lane starts none.
     *
     * @param event the Quarkus startup event
     */
    void onStart(@Observes StartupEvent event) {
        if (nodeUrl.isEmpty() || nodeUrl.get().isBlank()) {
            LOGGER.info("Sondage des messages back-office désactivé (URL du nœud absente)");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bo-message-poll");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::pollSafely, 20, pollSeconds, TimeUnit.SECONDS);
        LOGGER.infof("Sondage des messages back-office actif (toutes les %ds)", pollSeconds);
    }

    /**
     * Stops the poll loop, if one was started.
     */
    void onStop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * Runs one poll, swallowing any error so a failed tick never kills the loop.
     */
    void pollSafely() {
        try {
            pollOnce();
        } catch (Exception e) {
            LOGGER.warnf("Sondage message back-office: tick en échec (%s)", e.getMessage());
        }
    }

    /**
     * Fetches the pending message once and applies it. A null fetch is a no-op
     * (the current banner is kept); a fetched message — plain or survey — is
     * shown, an explicit blank one clearing the banner.
     */
    void pollOnce() {
        LOGGER.info("Entering method pollOnce");
        BackOfficeMessageClient.PendingMessage message = client.fetchPending();
        if (message == null) {
            LOGGER.info("Exiting method pollOnce");
            return;
        }
        homeService.receiveBackOfficeMessage(message.text(), surveyOf(message));
        state.touch();
        LOGGER.info("Exiting method pollOnce");
    }

    /**
     * Builds the survey object of a fetched message, or null for a plain
     * notice — question and options as the channel always carried them, plus
     * the answer machinery of LC-03-07-06 to -10 the node now administers.
     *
     * @param message the message pulled from the node
     * @return the survey, or null when the message is a plain notice
     */
    PosState.BackOfficeSurvey surveyOf(BackOfficeMessageClient.PendingMessage message) {
        LOGGER.info("Entering method surveyOf with message: " + message);
        if (message.question() == null || message.question().isBlank()) {
            LOGGER.info("Exiting method surveyOf");
            return null;
        }
        PosState.BackOfficeSurvey survey = new PosState.BackOfficeSurvey();
        survey.question = message.question().trim();
        if (message.options() != null) {
            for (String raw : message.options().split(";")) {
                if (!raw.isBlank()) {
                    survey.options.add(raw.trim());
                }
            }
        }
        survey.responseType = message.responseType();
        survey.required = message.required();
        survey.postalFormat = message.postalFormat();
        if (message.postalCodes() != null) {
            for (String raw : message.postalCodes().split(";")) {
                if (!raw.isBlank()) {
                    survey.postalCodes.add(raw.trim());
                }
            }
        }
        LOGGER.info("Exiting method surveyOf");
        return survey;
    }
}
