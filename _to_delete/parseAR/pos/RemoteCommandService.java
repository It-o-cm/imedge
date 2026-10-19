package com.intermarche.pos.ui.auth;

import com.intermarche.pos.service.sync.SyncEndpoints;
import com.intermarche.pos.ui.PosState;
import io.quarkus.runtime.StartupEvent;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects the orders the STORE NODE left for this register and carries them
 * out: the forced close of the operator session, asked for from the back
 * office (LC-01-02-07, BO-04-03-13) or by the end of period (LC-01-02-08,
 * BO-09-01-06); the close that stands down if the lane is mid-sale
 * (BO-09-01-06, BO-09-02-03); the refusal of a service request
 * (BO-04-02-05); and the supervisor endorsement granted from the dashboard
 * (BO-04-02-02).
 *
 * <p>The register PULLS, as it does for everything else. A back office that had
 * to reach a till would need every till of the shop addressable from the
 * office's network, which is exactly the assumption the synchronization was
 * built to avoid. So the back office writes a row, this loop collects it on its
 * next turn, and the ACKNOWLEDGEMENT is what tells the office the register
 * obeyed — a call that returns a 200 only proves the network worked.
 *
 * <p>Lives beside the closing it performs, and not in the sync package: what it
 * fetches is an order that has to touch {@link PosState} — the operator session
 * it closes, and the refusal it writes in the cashier's message zone
 * ({@code BO-04-02-05}). The register's sync services never touch that state;
 * the direction is kept (a screen's service may call the plumbing, the plumbing
 * may not call a screen). It is the register's order collector for EVERY order
 * that reaches the screen, which is what its name says: it was called
 * {@code RemoteCloseService} while the forced close was the only order, and
 * renamed once it carried four.
 *
 * <p>Silent when the register has no store node ({@code pos.sync.store-url}
 * absent), like every other half of the synchronization.
 */
@ApplicationScoped
public class RemoteCommandService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(RemoteCommandService.class);

    /** Reads the orders out of the node's answer: one object, two fields read. */
    private static final Pattern ORDER = Pattern.compile(
            "\\{\"uid\":\"([^\"]+)\",\"type\":\"([^\"]+)\"");

    /**
     * Reads the REASON of an order out of the tail of its object
     * ({@code BO-04-02-05}).
     *
     * <p>A SECOND pattern and not a longer first one: widening {@link #ORDER}
     * would mean a register that stops reading EVERY order — the forced close
     * included — the day the node adds a field between the two it matches.
     * Read apart, a reason that cannot be found costs only its own text.
     */
    private static final Pattern REASON = Pattern.compile(
            "\"reason\":\"((?:\\\\.|[^\"\\\\])*)\"");

    /** Close the session, whatever the register is doing (LC-01-02-07). */
    private static final String CLOSE_SESSION = "CLOSE_SESSION";

    /** Close the session only if the register is at rest (LC-01-02-08). */
    private static final String CLOSE_SESSION_IF_IDLE = "CLOSE_SESSION_IF_IDLE";

    /** The back office refused the supervisor request this lane sent (BO-04-02-05). */
    private static final String SUPERVISOR_REFUSED = "SUPERVISOR_REFUSED";

    /** The back office authorised the gesture this lane is holding (BO-04-02-02). */
    private static final String SUPERVISOR_GRANTED = "SUPERVISOR_GRANTED";

    /** Where the store node lives. */
    @Inject SyncEndpoints syncEndpoints;

    /** The closing itself, shared with the key on the sale screen. */
    @Inject SessionCloseService sessionCloseService;

    /** The register's state, whose operator is being logged out. */
    @Inject PosState state;

    /** This register's identifier, the address the orders carry. */
    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    /** Shared synchronization token; absent = the node is open. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** Seconds between two collections. */
    @ConfigProperty(name = "pos.commands.poll-seconds", defaultValue = "20")
    long pollSeconds;

    /** The HTTP client used to talk to the node. */
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** The collection loop, null when this register has no store node. */
    ScheduledExecutorService executor;

    /**
     * Starts the collection loop, unless this register stands alone.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        LOGGER.info("Entering method onStart");
        if (!syncEndpoints.hasStoreUrl()) {
            LOGGER.info("Exiting method onStart: no store node, no orders to collect");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "register-commands");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::collectSafely, 20, pollSeconds, TimeUnit.SECONDS);
        LOGGER.infof("Collecte des ordres du nœud %s toutes les %d s", syncEndpoints.storeUrl(), pollSeconds);
        LOGGER.info("Exiting method onStart");
    }

    /**
     * Runs one collection, swallowing whatever it fails on.
     *
     * <p>A node that is down must not take the register's loop with it: the
     * order stays pending and the next turn collects it. The failure is logged
     * once per turn, at WARN, because a register that has been unable to reach
     * its node for an hour is worth noticing.
     */
    void collectSafely() {
        try {
            collectOnce();
        } catch (Exception failure) {
            LOGGER.warnf("Collecte des ordres impossible (%s)", failure.getMessage());
        }
    }

    /**
     * Collects the pending orders and carries out the ones this register
     * understands.
     *
     * @throws Exception when the node cannot be reached or answers badly
     */
    void collectOnce() throws Exception {
        LOGGER.info("Entering method collectOnce");
        for (String[] order : orders(get("/api/commands/" + terminalId))) {
            if (SUPERVISOR_REFUSED.equals(order[1])) {
                refuse(order[0], order[2]);
                continue;
            }
            if (SUPERVISOR_GRANTED.equals(order[1])) {
                grant(order[0], order[2]);
                continue;
            }
            if (!CLOSE_SESSION.equals(order[1]) && !CLOSE_SESSION_IF_IDLE.equals(order[1])) {
                // An order this version does not know is left PENDING rather
                // than acknowledged: a newer register will carry it out, and a
                // blind acknowledgement would make the node believe it was.
                LOGGER.warnf("Ordre inconnu ignoré : %s", order[1]);
                continue;
            }
            apply(order[0], order[1]);
        }
        LOGGER.info("Exiting method collectOnce");
    }

    /**
     * Carries out one close order and acknowledges it.
     *
     * <p>Acknowledged even when the register was ALREADY closed: the order asked
     * for a closed register and the register is closed. Leaving it pending would
     * make the back office show a forced close that never completes, and would
     * close the next operator to take the post.
     *
     * <p>A PERIOD-END order spares a register in the middle of a sale: the cart
     * is left alone and the order is NOT acknowledged, so the next turn of the
     * loop takes it again — which, in practice, is the moment the cashier has
     * finished with the customer standing there. A back-office order, asked for
     * by a person looking at the screen, does not wait.
     *
     * @param uid the order's token
     * @param type what the order asks for
     * @throws Exception when the acknowledgement cannot be sent
     */
    void apply(String uid, String type) throws Exception {
        if (state.auth.operatorBadgeId != null) {
            if (CLOSE_SESSION_IF_IDLE.equals(type) && !state.ticket.items.isEmpty()) {
                LOGGER.infof("Vente en cours sur %s : ordre de fin de période laissé en attente", terminalId);
                return;
            }
            String cause = CLOSE_SESSION_IF_IDLE.equals(type) ? "fin de période" : "back-office";
            LOGGER.infof("Fermeture forcée demandée par le nœud sur %s (%s)", terminalId, cause);
            sessionCloseService.close(state, cause);
        }
        post("/api/commands/" + uid + "/ack");
    }

    /**
     * Shows the cashier that the back office REFUSED the request this lane
     * sent, and acknowledges the order ({@code BO-04-02-05}).
     *
     * <p>Acknowledged whatever the screen was doing: the refusal is an
     * ANSWER, and an answer delivered twice reads as a second refusal. The
     * wording is the back office's own — it is the sentence a manager typed
     * for this cashier — and an order carrying none says the least that is
     * still true.
     *
     * @param uid the order's token
     * @param reason the wording the back office sent, possibly null or blank
     * @throws Exception when the acknowledgement cannot be sent
     */
    void refuse(String uid, String reason) throws Exception {
        String wording = reason == null || reason.isBlank()
                ? "Demande refusée par le back-office" : reason.trim();
        LOGGER.infof("Refus du back-office reçu sur %s : %s", terminalId, wording);
        state.ticket.setError(wording);
        state.touch();
        post("/api/commands/" + uid + "/ack");
    }

    /**
     * Arms the back office's AUTHORISATION on the gesture this lane is
     * holding, and acknowledges the order ({@code BO-04-02-02}).
     *
     * <p>Armed and not performed: the authorisation waits on the endorsement
     * state until the cashier validates the gesture they were already
     * validating, and it is cleared with the request. An order that lands
     * when no gesture is pending arms nothing and is still acknowledged —
     * the back office answered a question the lane has stopped asking.
     *
     * @param uid the order's token
     * @param reason who granted it, possibly null or blank
     * @throws Exception when the acknowledgement cannot be sent
     */
    void grant(String uid, String reason) throws Exception {
        String by = reason == null || reason.isBlank() ? "le back-office" : reason.trim();
        if (state.endorsement != null && state.endorsement.active) {
            state.endorsement.remoteGrant = by;
            state.ticket.setNotice("Autorisation accordée par " + by + " : validez le geste.");
            LOGGER.infof("Autorisation du back-office armée sur %s (%s)", terminalId, by);
        } else {
            LOGGER.infof("Autorisation du back-office sans geste en attente sur %s", terminalId);
        }
        state.touch();
        post("/api/commands/" + uid + "/ack");
    }

    /**
     * Reads the orders out of the node's answer.
     *
     * @param body the JSON array the node returned
     * @return one {@code {uid, type, reason}} triplet per order, in the order
     *         received; the reason is null when the order carries none
     */
    List<String[]> orders(String body) {
        List<String[]> found = new ArrayList<>();
        String text = body == null ? "" : body;
        Matcher matcher = ORDER.matcher(text);
        while (matcher.find()) {
            found.add(new String[]{matcher.group(1), matcher.group(2),
                    reasonOf(text, matcher.end())});
        }
        return found;
    }

    /**
     * Reads the reason an order carries, looking only inside that order's
     * own object.
     *
     * @param body the whole answer
     * @param from where this order's matched head ends
     * @return the reason, unescaped, or null when the order carries none
     */
    String reasonOf(String body, int from) {
        int end = body.indexOf('}', from);
        String tail = end < 0 ? body.substring(from) : body.substring(from, end);
        Matcher matcher = REASON.matcher(tail);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /**
     * Runs an authenticated GET against the store node.
     *
     * @param path the path to fetch
     * @return the response body
     * @throws Exception on a non-2xx status or a transport failure
     */
    private String get(String path) throws Exception {
        HttpResponse<String> response = httpClient.send(
                authenticated(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " sur " + path);
        }
        return response.body();
    }

    /**
     * Runs an authenticated POST with no body against the store node.
     *
     * @param path the path to post to
     * @throws Exception on a non-2xx status or a transport failure
     */
    private void post(String path) throws Exception {
        HttpResponse<String> response = httpClient.send(
                authenticated(path).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " sur " + path);
        }
    }

    /**
     * Builds a request carrying the shared token when there is one.
     *
     * @param path the path to reach
     * @return the prepared builder
     */
    private HttpRequest.Builder authenticated(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(syncEndpoints.storeUrl() + path))
                .timeout(Duration.ofSeconds(15));
        String shared = token.orElse("");
        if (!shared.isBlank()) {
            builder.header("X-Sync-Token", shared);
        }
        return builder;
    }
}
