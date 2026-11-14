package com.intermarche.edge.service.deploy;

import com.intermarche.edge.domain.deploy.NodeUpdateLog;
import com.intermarche.edge.domain.deploy.NodeVersion;
import com.intermarche.edge.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

/**
 * The UPDATE STATE MACHINE every node runs on itself — register, store node
 * and central node alike (BO-08-03-04/05/07/09/10, BO-08-04-01/02/14).
 *
 * <p>The upstream node says which version this node must run; this class
 * decides whether to go and, if so, goes: DOWNLOADING the package, VERIFYING
 * it against the announced SHA-256, INSTALLING it, and running its
 * SELF-TEST. A package that fails its self-test is ROLLED BACK to the version
 * it replaced (BO-08-04-14) — the application only, never the referential
 * data, which this class does not touch. A failure is RETRIED at the next
 * report, up to the administered number of attempts (BO-08-04-01); a manual
 * forcing from the deployment console (BO-08-04-02) resets the count and
 * runs even outside the lot's window.
 *
 * <p>WHEN TO GO. Inside the lot's time window (BO-08-03-04) the node updates
 * at its next report; outside it, only at START-UP (BO-08-03-07) — the
 * seasonal register switched on after the deployment catches up when it
 * boots, and never in the middle of a day it was not scheduled for. A busy
 * node (a register with a sale on the screen) defers to its next report.
 *
 * <p>NO BINARY IS SWAPPED in this suite: installing records the new version
 * on the node, which is what every report then carries upstream. The steps,
 * their order, their checks and their journal are real; the jar on the disk
 * is the one thing a deployment tool outside the application replaces.
 */
@ApplicationScoped
public class NodeUpdateService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(NodeUpdateService.class);

    /** The line a healthy package carries; its absence fails the self-test. */
    static final String SELF_TEST_OK = "selftest=ok";

    /** What considering a target ended as. */
    public enum Decision {
        /** No target was given. */
        NO_TARGET,
        /** The node already runs that version or a higher one. */
        UP_TO_DATE,
        /** The lot's window is closed and this is not a start-up. */
        WAITING_WINDOW,
        /** The node is busy; it will try again at its next report. */
        DEFERRED_BUSY,
        /** The administered number of attempts is spent; only a forcing restarts it. */
        ABANDONED,
        /** The target version now runs. */
        APPLIED,
        /** The update failed and the node kept, or was left on, a version. */
        FAILED,
        /** The new version failed its self-test and the previous one runs again. */
        ROLLED_BACK
    }

    /** The version this node was built with. */
    @ConfigProperty(name = "quarkus.application.version", defaultValue = "0.0.0")
    String buildVersion;

    /** The administered attempts, rollback and start-up rules. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * The version this node runs.
     *
     * @return the installed version, the build version when the node never updated
     */
    @Transactional
    public String installedVersion() {
        return load().installedVersion;
    }

    /**
     * The update state of this node, for its report.
     *
     * @return the stored row, or a transient one carrying the build version
     */
    @Transactional
    public NodeVersion snapshot() {
        return load();
    }

    /**
     * The last steps of the updates of this node.
     *
     * @param limit how many steps to read
     * @return the steps, newest first
     */
    @Transactional
    public List<NodeUpdateLog> history(int limit) {
        return NodeUpdateLog.latest(limit);
    }

    /**
     * Considers the version the upstream node asks this node to run, and
     * runs the update when it is due.
     *
     * @param target the version to run, possibly null
     * @param startup whether this is the node's first report since it started
     * @param idle whether the node may be updated now
     * @param downloader fetches the package of a target from the upstream node
     * @return what the consideration ended as
     */
    @Transactional
    public Decision consider(DeployPayloads.Target target, boolean startup, boolean idle,
                             Function<DeployPayloads.Target, String> downloader) {
        if (target == null || target.version == null || target.version.isBlank()) {
            return Decision.NO_TARGET;
        }
        LOGGER.info("Entering method consider with target: " + target.version);
        NodeVersion row = load();
        if (!target.version.equals(row.targetVersion)) {
            // A new target starts clean: the state and error of the previous
            // target (a rolled-back 2.1) must not be reported as this one's.
            row.targetVersion = target.version;
            row.attempts = 0;
            row.state = NodeVersion.State.IDLE;
            row.lastError = null;
        }
        boolean forced = target.forceToken != null && !target.forceToken.equals(row.lastForceToken);
        if (forced) {
            row.lastForceToken = target.forceToken;
            row.attempts = 0;
        }
        Decision decision = gate(row, target, forced, startup, idle);
        if (decision == null) {
            row.attempts++;
            decision = run(row, target, downloader);
        }
        row.updatedAt = LocalDateTime.now();
        row.persist();
        LOGGER.info("Exiting method consider with " + decision);
        return decision;
    }

    /**
     * Decides whether the update may run now.
     *
     * @param row this node's version row
     * @param target the version to run
     * @param forced whether a new manual forcing came with it
     * @param startup whether this is a start-up report
     * @param idle whether the node may be updated now
     * @return the reason not to run, or null when the update must run
     */
    private Decision gate(NodeVersion row, DeployPayloads.Target target, boolean forced,
                          boolean startup, boolean idle) {
        if (!AppVersion.isLower(row.installedVersion, target.version)) {
            return Decision.UP_TO_DATE;
        }
        boolean atStartup = startup && posSettingsService.deploymentAutoUpdateAtStartup();
        if (!forced && !target.inWindow && !atStartup) {
            return Decision.WAITING_WINDOW;
        }
        if (!idle) {
            return Decision.DEFERRED_BUSY;
        }
        if (row.attempts >= posSettingsService.deploymentMaxAttempts()) {
            return Decision.ABANDONED;
        }
        return null;
    }

    /**
     * Downloads, verifies, installs and self-tests the target version.
     *
     * @param row this node's version row
     * @param target the version to run
     * @param downloader fetches the package
     * @return the outcome
     */
    private Decision run(NodeVersion row, DeployPayloads.Target target,
                         Function<DeployPayloads.Target, String> downloader) {
        String from = row.installedVersion;
        step(row, NodeVersion.State.DOWNLOADING, from, target.version, true,
                "Téléchargement du paquet " + target.version);
        String content;
        try {
            content = downloader.apply(target);
        } catch (RuntimeException failure) {
            return fail(row, from, target.version, "Téléchargement impossible : " + failure.getMessage());
        }
        step(row, NodeVersion.State.VERIFYING, from, target.version, true, "Vérification de l'empreinte");
        if (content == null || !AppVersion.sha256(content).equals(target.checksum)) {
            return fail(row, from, target.version, "Empreinte du paquet invalide");
        }
        step(row, NodeVersion.State.INSTALLING, from, target.version, true, "Installation de la version "
                + target.version);
        row.previousVersion = from;
        row.installedVersion = target.version;
        if (content.contains(SELF_TEST_OK)) {
            row.attempts = 0;
            row.lastError = null;
            step(row, NodeVersion.State.APPLIED, from, target.version, true, "Version " + target.version
                    + " en service");
            return Decision.APPLIED;
        }
        if (!posSettingsService.deploymentRollbackEnabled()) {
            return fail(row, from, target.version, "Autotest de la version " + target.version
                    + " en échec, retour arrière désactivé");
        }
        row.installedVersion = from;
        row.lastError = "Autotest de la version " + target.version + " en échec : retour à la version " + from;
        step(row, NodeVersion.State.ROLLED_BACK, from, target.version, false, row.lastError);
        return Decision.ROLLED_BACK;
    }

    /**
     * Records a failed attempt.
     *
     * @param row this node's version row
     * @param from the version the node ran when the attempt started
     * @param to the version it aimed at
     * @param error why it failed
     * @return {@link Decision#FAILED}
     */
    private Decision fail(NodeVersion row, String from, String to, String error) {
        row.lastError = error.length() > 300 ? error.substring(0, 300) : error;
        step(row, NodeVersion.State.FAILED, from, to, false, row.lastError);
        return Decision.FAILED;
    }

    /**
     * Moves the row to a state and journals the step.
     *
     * @param row this node's version row
     * @param state the state reached
     * @param from the version the node ran when the update started
     * @param to the version it aims at
     * @param success whether the step succeeded
     * @param message what to remember of it
     */
    private void step(NodeVersion row, NodeVersion.State state, String from, String to,
                      boolean success, String message) {
        row.state = state;
        NodeUpdateLog log = new NodeUpdateLog();
        log.loggedAt = LocalDateTime.now();
        log.fromVersion = from;
        log.toVersion = to;
        log.step = state.name();
        log.success = success;
        log.message = message;
        log.persist();
        LOGGER.infof("Mise à jour %s -> %s : %s (%s)", from, to, state, message);
    }

    /**
     * Reads the row of this node, or starts one on the build version.
     *
     * @return the row, persisted or transient
     */
    private NodeVersion load() {
        NodeVersion row = NodeVersion.current();
        if (row == null) {
            row = new NodeVersion();
            row.installedVersion = buildVersion;
        }
        return row;
    }
}
