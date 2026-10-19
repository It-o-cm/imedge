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
     * Answers the message this register must show now, or nothing.
     *
     * @param presentedToken the token the register presented
     * @param terminal the register asking
     * @return 200 with the message, 204 when there is none, 401/403 off-gate
     */
    @GET
    @Path("/pending")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response pending(@HeaderParam("X-Sync-Token") String presentedToken,
            @QueryParam("terminal") String terminal) {
        LOGGER.info("Entering method pending with terminal: " + terminal);
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
        StoreMessage message = resolve(terminal, LocalDateTime.now());
        if (message == null) {
            LOGGER.info("Exiting method pending");
            return Response.noContent().build();
        }
        LOGGER.info("Exiting method pending");
        return Response.ok(new PendingMessage(
                message.text, message.question, message.options)).build();
    }

    /**
     * The message that applies to one register at one moment, the newest first.
     *
     * <p>ONE MESSAGE AND NOT A LIST, because the register shows one banner: the
     * most recently written message that applies wins, which is what a store
     * correcting a notice expects of it.
     *
     * @param terminal the register asking, possibly null
     * @param now the moment of the question
     * @return the message to serve, or null
     */
    StoreMessage resolve(String terminal, LocalDateTime now) {
        String islandCode = islandOf(terminal);
        for (StoreMessage message : StoreMessage.<StoreMessage>listServable()) {
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
     * survey when the question is present.
     *
     * @param text the message text
     * @param question the survey question, or null for a plain notice
     * @param options the survey options as a semicolon list, or null
     */
    public record PendingMessage(String text, String question, String options) {
    }
}
