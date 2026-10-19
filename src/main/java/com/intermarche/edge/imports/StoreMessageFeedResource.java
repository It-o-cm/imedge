package com.intermarche.edge.imports;

import com.intermarche.edge.domain.session.CashSession;
import com.intermarche.edge.domain.setting.StoreMessage;
import com.intermarche.edge.domain.store.CheckoutPoint;
import jakarta.annotation.security.PermitAll;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * THE MESSAGE THE STORE ADDRESSES TO ONE REGISTER
 * ({@code GET /api/messages/pending?terminal=}) — the node end of a channel
 * whose register end has existed all along (BO-03-08-01, -02, -04 and -08).
 *
 * <p>THE SHAPE IS NOT NEGOTIABLE, and that is the point of this class. Registers
 * are deployed and they poll a {@code {text, question, options}} object every
 * twenty seconds; serving anything else would mean a release on every lane of
 * the store. So the node answers exactly that, and everything the administration
 * screen knows beyond those three fields — the period, the audience, the
 * trigger, the confirmation mode — is resolved HERE, before answering.
 *
 * <p>Pull and not push: the register asks, so the node never needs to know a
 * register's address. A register the store has nothing to say to gets a 204, and
 * the lane's poller leaves its banner alone; a message whose text is blank is a
 * message all the same, and it is how a notice is taken down.
 *
 * <p>HOW LONG THE BANNER STAYS is decided here too ({@code LC-01-08-05}), on
 * the serving memory of {@link StoreMessageServing}: a message whose
 * administered display duration has elapsed, a message the back office took
 * down, and a message the cashier acknowledged are all served the BLANK
 * answer exactly once — the lane clears its banner — and then nothing: the
 * three ways the questionnaire says a message can disappear.
 *
 * <p>A machine surface with the node-role-plus-token gate, like the other
 * inbound endpoints.
 */
@Path("/api/messages")
public class StoreMessageFeedResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(StoreMessageFeedResource.class);

    /** The role of this node: only a "store" node speaks to registers. */
    @ConfigProperty(name = "pos.role", defaultValue = "store")
    String role;

    /** The shared token the registers present, blank to accept any caller. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /**
     * What has already been served to which register, and for how long it
     * must keep being served (LC-01-08-05).
     */
    @jakarta.inject.Inject
    StoreMessageServing serving;

    /**
     * Answers the message this register must show now, or nothing.
     *
     * @param presentedToken the token the register presented
     * @param terminal the register asking
     * @param moment the TRANSACTION moment the register asks on
     *               (LC-03-07-01 / -02), or null for the ordinary poll
     * @return 200 with the message, 204 when there is none, 401/403 off-gate
     */
    @GET
    @Path("/pending")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response pending(@HeaderParam("X-Sync-Token") String presentedToken,
            @QueryParam("terminal") String terminal,
            @QueryParam("moment") String moment) {
        LOGGER.info("Entering method pending with terminal: " + terminal + ", moment: " + moment);
        if (!"store".equalsIgnoreCase(role)) {
            LOGGER.info("Exiting method pending");
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("Ce nœud n'a pas le rôle store").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            LOGGER.info("Exiting method pending");
            return Response.status(Response.Status.UNAUTHORIZED).entity("Jeton invalide").build();
        }
        java.time.LocalDateTime now = LocalDateTime.now();
        StoreMessage message = resolve(terminal, now, triggerOf(moment));
        if (message == null) {
            // LC-01-08-05: the register that was shown a message the store has
            // taken down (deactivated, period over, audience changed) gets the
            // BLANK answer exactly once, so its banner goes — then nothing.
            if (serving.consume(terminal)) {
                LOGGER.info("Exiting method pending (message taken down, banner cleared)");
                return Response.ok(new PendingMessage("", null, null,
                        null, false, null, null)).build();
            }
            LOGGER.info("Exiting method pending");
            return Response.noContent().build();
        }
        // LC-01-08-05: a message the register has already been shown for its
        // administered display duration is served the BLANK answer exactly
        // once — the banner goes — and consumed, so the notice does not come
        // back on the next poll.
        if (serving.durationOver(terminal, now)) {
            serving.consume(terminal);
            LOGGER.info("Exiting method pending (duration elapsed, banner cleared)");
            return Response.ok(new PendingMessage("", null, null,
                    null, false, null, null)).build();
        }
        // The display window is counted from the FIRST service: serving the
        // same message again changes nothing on the lane, and restarting the
        // window on every poll would make the banner never expire.
        serving.record(terminal, message.id, message.durationSeconds, now);
        LOGGER.info("Exiting method pending");
        return Response.ok(pendingMessage(message)).build();
    }

    /**
     * Answers the survey a SCANNED code-barre names (LC-03-07-03): the
     * register asks for the message by its administered id, and the node
     * serves it when the store still stands behind it — active, inside its
     * period, addressed to that register. The serving memory is not consulted
     * and not written: the scan asks, the scan gets, once.
     *
     * @param presentedToken the token the register presented
     * @param terminal the register asking
     * @param id the administered message id the scanned code carries
     * @return 200 with the survey, 204 when there is nothing to serve,
     *         401/403 off-gate
     */
    @GET
    @Path("/survey/{id}")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response survey(@HeaderParam("X-Sync-Token") String presentedToken,
            @QueryParam("terminal") String terminal,
            @jakarta.ws.rs.PathParam("id") Long id) {
        LOGGER.info("Entering method survey with terminal: " + terminal + ", id: " + id);
        if (!"store".equalsIgnoreCase(role)) {
            LOGGER.info("Exiting method survey");
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("Ce nœud n'a pas le rôle store").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            LOGGER.info("Exiting method survey");
            return Response.status(Response.Status.UNAUTHORIZED).entity("Jeton invalide").build();
        }
        StoreMessage message = id == null ? null : StoreMessage.findById(id);
        if (message == null || !message.appliesTo(terminal, islandOf(terminal), LocalDateTime.now())) {
            LOGGER.info("Exiting method survey");
            return Response.noContent().build();
        }
        LOGGER.info("Exiting method survey");
        return Response.ok(pendingMessage(message)).build();
    }

    /**
     * Builds the wire object the registers deserialize, carrying the survey's
     * answer machinery (LC-03-07-06 to -10) alongside the text the banner
     * always showed.
     *
     * @param message the message to serve
     * @return the pending-message object for the register
     */
    PendingMessage pendingMessage(StoreMessage message) {
        return new PendingMessage(message.text, message.question, message.options,
                message.responseType == null ? null : message.responseType.name(),
                message.required, message.postalFormat, message.postalCodes);
    }

    /**
     * Reads the TRANSACTION moment a register asks on, tolerating an unknown
     * or absent one: anything the trigger enum does not name reads as the
     * ordinary poll, which serves every trigger exactly as before.
     *
     * @param raw the posted moment name, possibly null
     * @return the trigger the register asks for, or null for the ordinary poll
     */
    private StoreMessage.Trigger triggerOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (StoreMessage.Trigger value : StoreMessage.Trigger.values()) {
            if (value.name().equals(raw.trim())) {
                return value;
            }
        }
        return null;
    }

    /**
     * The message that applies to one register at one moment, the newest first.
     *
     * <p>ONE MESSAGE AND NOT A LIST, because the register shows one banner: the
     * most recently written message that applies wins, which is what a store
     * correcting a notice expects of it.
     *
     * <p>The TRANSACTION moments (LC-03-07-01 / -02) are asked ON DEMAND: a
     * register names the moment it just reached, and only the messages
     * administered for that moment are candidates — the ordinary poll that
     * serves the immediate, session and scan-driven ones is unchanged.
     *
     * @param terminal the register asking, possibly null
     * @param now the moment of the question
     * @param moment the transaction moment the register asks on, or null for
     *               the ordinary poll
     * @return the message to serve, or null
     */
    StoreMessage resolve(String terminal, LocalDateTime now, StoreMessage.Trigger moment) {
        String islandCode = islandOf(terminal);
        for (StoreMessage message : StoreMessage.<StoreMessage>listServable()) {
            // LC-01-08-05: a message this register has consumed — its display
            // duration elapsed, the store took it down, or the cashier
            // acknowledged it — is never served to it again.
            if (serving.isConsumed(terminal, message.id)) {
                continue;
            }
            if (moment != null && message.trigger != moment) {
                continue;
            }
            if (message.appliesTo(terminal, islandCode, now) && momentHasCome(message, terminal)) {
                return message;
            }
        }
        return null;
    }

    /**
     * Whether the MOMENT a message waits for has come for this register
     * (BO-03-08-01 and -02).
     *
     * <p>An immediate message is served at the next poll, which is within
     * twenty seconds. The two others wait for a register's session, and the
     * node is the one who can tell: it holds the sessions the registers push to
     * it at their opening and at their closing.
     *
     * <p>AT THE OPENING means the session now open on that register began AFTER
     * the message did. A cashier who was already at their post when the notice
     * was written does not get it — that is the difference between this
     * trigger and the immediate one, and the only reading of "à l'ouverture
     * de caisse" that does not amount to "tout de suite".
     *
     * <p>AT THE CLOSING is its mirror: nothing while a session is open, and the
     * message once the register has no open session — which is where the
     * cashier reads it, on the session screen, after the Z.
     *
     * <p>A message whose start is open-ended waits for nothing at the opening:
     * every session began after "always", and the store asked for the notice to
     * be seen by whoever takes the post.
     *
     * @param message the message being considered
     * @param terminal the register asking
     * @return true when the register must be served it now
     */
    boolean momentHasCome(StoreMessage message, String terminal) {
        if (message.trigger == StoreMessage.Trigger.IMMEDIATE) {
            return true;
        }
        // LC-03-07-01 / -02: the two TRANSACTION moments are asked ON DEMAND
        // by the register the moment it reaches them — the first article rung
        // up, the paid ticket closing — so the moment HAS come by the time
        // this question runs; asking again here would double the decision.
        if (message.trigger == StoreMessage.Trigger.TRANSACTION_START
                || message.trigger == StoreMessage.Trigger.TRANSACTION_END) {
            return true;
        }
        CashSession session = terminal == null || terminal.isBlank()
                ? null : CashSession.findOpenByTerminal(terminal);
        if (message.trigger == StoreMessage.Trigger.SESSION_CLOSE) {
            return session == null;
        }
        if (session == null) {
            return false;
        }
        return message.startsAt == null || !session.openingDate.isBefore(message.startsAt);
    }

    /**
     * The island a register belongs to, as the checkout points declare it.
     *
     * <p>Null for an unknown register rather than an error: a lane the store has
     * not declared still polls, and answering it "no message" is better than
     * answering it a failure every twenty seconds.
     *
     * @param terminal the register, possibly null
     * @return its island code, or null
     */
    private String islandOf(String terminal) {
        if (terminal == null || terminal.isBlank()) {
            return null;
        }
        CheckoutPoint point = CheckoutPoint.findByTerminal(terminal);
        return point == null ? null : point.islandCode;
    }

    /**
     * The object the registers have been polling for: a plain notice, or a
     * survey when the question is present, carrying the answer machinery of
     * LC-03-07-06 to -10 — the response TYPE, whether the cashier may pass it
     * by, and for a postal code the administered mask and, when the shop keeps
     * one, the list a typed answer must belong to.
     *
     * @param text the message text
     * @param question the survey question, or null for a plain notice
     * @param options the survey options as a semicolon list, or null
     * @param responseType the survey's response type name, or null for options
     * @param required true when the survey may not be passed by
     * @param postalFormat the postal-code mask, or null
     * @param postalCodes the valid postal codes as a semicolon list, or null
     */
    public record PendingMessage(String text, String question, String options,
            String responseType, boolean required, String postalFormat,
            String postalCodes) {
    }
}
