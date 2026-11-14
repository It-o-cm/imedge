package com.intermarche.edge.service.deploy;

import com.intermarche.edge.service.PosSettingsService;
import com.intermarche.edge.service.sync.EngineFeedDeliveryService;
import com.intermarche.edge.service.sync.IntegrationRetryService;
import com.intermarche.edge.service.sync.RefPullService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;

/**
 * Carries out, on a STORE OR CENTRAL node, the remote maintenance orders
 * addressed to the node itself (BO-08-04-17, and the manual relaunches of
 * BO-08-04-02 and BO-08-04-09 to -13 issued from afar).
 *
 * <p>The same gestures as the buttons of the node's own supervision screen —
 * pull the referentials, deliver the engine feeds, replay the failed
 * deposits — reached from the screen of another echelon. Never inside a
 * transaction: the pull and the replay open their own.
 */
@ApplicationScoped
public class EdgeMaintenanceService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EdgeMaintenanceService.class);

    /** The administered activation of the remote maintenance. */
    @Inject
    PosSettingsService posSettingsService;

    /** The referential pull. */
    @Inject
    RefPullService refPullService;

    /** The engine-feed delivery. */
    @Inject
    EngineFeedDeliveryService engineFeedDeliveryService;

    /** The replay of the failed deposits. */
    @Inject
    IntegrationRetryService integrationRetryService;

    /**
     * Carries one order out.
     *
     * @param order the order
     * @return its outcome
     */
    public DeployPayloads.ActionResult execute(DeployPayloads.Action order) {
        LOGGER.info("Entering method execute with type: " + order.type);
        if (!posSettingsService.remoteMaintenanceEnabled()) {
            return outcome(order, false, "Prise en main à distance désactivée sur ce nœud");
        }
        return switch (order.type) {
            case "REFERENTIAL_PULL" -> relaunch(order, refPullService.triggerPull(),
                    "Tirage des référentiels relancé");
            case "ENGINE_FEED_DELIVERY" -> relaunch(order, engineFeedDeliveryService.triggerDelivery(),
                    "Livraison des flux moteur relancée");
            case "INTEGRATION_RETRY" -> outcome(order, true,
                    integrationRetryService.retryAll() + " dépôt(s) réintégré(s)");
            default -> outcome(order, false, "Ordre non applicable à ce nœud");
        };
    }

    /**
     * Turns a relaunch's error into an outcome.
     *
     * @param order the order
     * @param error the relaunch's error, null on success
     * @param success the wording of a success
     * @return the outcome
     */
    private DeployPayloads.ActionResult relaunch(DeployPayloads.Action order, String error, String success) {
        return error == null ? outcome(order, true, success) : outcome(order, false, error);
    }

    /**
     * Builds an outcome, dated now.
     *
     * @param order the order
     * @param success whether it succeeded
     * @param text what to say
     * @return the outcome
     */
    private DeployPayloads.ActionResult outcome(DeployPayloads.Action order, boolean success, String text) {
        return MaintenanceService.result(order.uid, success, text, LocalDateTime.now());
    }
}
