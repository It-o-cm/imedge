package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.setting.StoreMessage;
import com.intermarche.edge.domain.setting.StoreMessageAck;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The back office's MESSAGES page ({@code /admin/messages}): what the store has
 * to say to its registers, to whom, when, and who has read it
 * (BO-03-08-01, -02, -04 and -08).
 *
 * <p>The register end of this channel was built long ago and polled an endpoint
 * nobody served. This page is the missing end: it writes the message, and the
 * feed resource resolves it per register.
 *
 * <p>WHAT IT SHOWS BESIDE EACH MESSAGE is the confirmation list, and that is
 * the point of the screen rather than a detail: a store that sends a safety
 * notice to forty registers needs to know WHICH cashier has not read it. A
 * count would answer the wrong question.
 *
 * <p>Same admin cycle as the other back-office pages: POST &rarr; 303 &rarr; a
 * one-shot notice, {@code @RolesAllowed("ADMIN")} throughout.
 */
@Path("/admin/messages")
public class AdminMessageResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminMessageResource.class);

    /** The page this resource redirects to after every POST. */
    private static final String PAGE = "/admin/messages";

    /** How a date and time are typed on this screen. */
    private static final java.time.format.DateTimeFormatter MASK =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** The messages page template. */
    @Inject
    @Location("admin-messages")
    Template adminMessages;

    /**
     * Shows the messages page: every message, newest first, each with the
     * confirmations it has drawn.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @RolesAllowed("ADMIN")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance messagesPage(@QueryParam("notice") String notice,
            @QueryParam("noticeOk") String noticeOk) {
        LOGGER.info("Entering method messagesPage");
        List<Row> rows = new ArrayList<>();
        for (StoreMessage message : StoreMessage.<StoreMessage>listAllOrdered()) {
            rows.add(new Row(message, StoreMessageAck.listFor(message.id)));
        }
        LOGGER.info("Exiting method messagesPage");
        return adminMessages
                .data("rows", rows)
                .data("audiences", StoreMessage.Audience.values())
                .data("triggers", StoreMessage.Trigger.values())
                .data("confirmations", StoreMessage.Confirmation.values())
                .data("responseTypes", StoreMessage.ResponseType.values())
                .data("notice", notice)
                .data("noticeOk", !"false".equals(noticeOk));
    }

    /**
     * Creates or updates a message (BO-03-08-04 and -08).
     *
     * <p>A message with NEITHER text NOR question says nothing, and is refused:
     * an empty banner served to forty registers is a defect, not a message. To
     * take a notice down, the store deactivates it or empties its text on a
     * message that is already out — which is a blank text WITH an id, and that
     * one is accepted.
     *
     * @param form the posted message
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/save")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveMessage(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveMessage");
        String text = AdminForms.text(form, "text");
        String question = AdminForms.text(form, "question");
        Long id = longOrNull(AdminForms.text(form, "id"));
        if (id == null && text == null && question == null) {
            LOGGER.info("Exiting method saveMessage");
            return AdminForms.redirect(PAGE, "Un message sans texte ni question ne dit rien.", false);
        }
        StoreMessage.Audience audience = audienceOf(AdminForms.text(form, "audience"));
        if (audience == null) {
            LOGGER.info("Exiting method saveMessage");
            return AdminForms.redirect(PAGE, "Population inconnue.", false);
        }
        StoreMessage.Trigger trigger = triggerOf(AdminForms.text(form, "trigger"));
        if (trigger == null) {
            LOGGER.info("Exiting method saveMessage");
            return AdminForms.redirect(PAGE, "Moment d'affichage inconnu.", false);
        }
        StoreMessage.Confirmation confirmation = confirmationOf(AdminForms.text(form, "confirmation"));
        if (confirmation == null) {
            LOGGER.info("Exiting method saveMessage");
            return AdminForms.redirect(PAGE, "Mode de confirmation inconnu.", false);
        }
        StoreMessage.ResponseType responseType = responseTypeOf(AdminForms.text(form, "responseType"));
        if (responseType == null) {
            LOGGER.info("Exiting method saveMessage");
            return AdminForms.redirect(PAGE, "Type de réponse inconnu.", false);
        }
        if (audience != StoreMessage.Audience.ALL && AdminForms.text(form, "audienceValue") == null) {
            LOGGER.info("Exiting method saveMessage");
            return AdminForms.redirect(PAGE,
                    "Cette population demande de nommer l'îlot, la caisse ou l'opérateur.", false);
        }
        LocalDateTime startsAt;
        LocalDateTime endsAt;
        try {
            startsAt = momentOf(AdminForms.text(form, "startsAt"));
            endsAt = momentOf(AdminForms.text(form, "endsAt"));
        } catch (DateTimeParseException malformed) {
            LOGGER.info("Exiting method saveMessage");
            return AdminForms.redirect(PAGE,
                    "Période de diffusion invalide (attendu JJ/MM/AAAA HH:MM).", false);
        }
        if (startsAt != null && endsAt != null && endsAt.isBefore(startsAt)) {
            LOGGER.info("Exiting method saveMessage");
            return AdminForms.redirect(PAGE, "La fin de diffusion précède son début.", false);
        }
        StoreMessage message = id == null ? new StoreMessage() : StoreMessage.findById(id);
        if (message == null) {
            LOGGER.info("Exiting method saveMessage");
            return AdminForms.redirect(PAGE, "Message inconnu.", false);
        }
        message.text = text == null ? "" : text;
        message.question = question;
        message.options = AdminForms.text(form, "options");
        // LC-03-07-06 to -09: how the survey's answer is typed, and whether
        // it is declared informative rather than an options list of one.
        message.responseType = responseType;
        // LC-03-07-10: a mandatory survey stays until it is answered; an
        // optional one carries the skip key on the register.
        message.required = AdminForms.checkbox(form, "required");
        // LC-03-07-07 / -08: the postal-code mask and, when the shop keeps
        // one, the list of codes a typed answer must belong to.
        message.postalFormat = AdminForms.text(form, "postalFormat");
        message.postalCodes = AdminForms.text(form, "postalCodes");
        message.startsAt = startsAt;
        message.endsAt = endsAt;
        message.durationSeconds = Math.max(0, intOr(AdminForms.text(form, "durationSeconds"), 0));
        message.audience = audience;
        message.audienceValue = AdminForms.text(form, "audienceValue");
        message.trigger = trigger;
        message.confirmation = confirmation;
        message.active = AdminForms.checkbox(form, "active");
        if (id == null) {
            message.persist();
        }
        LOGGER.infof("Message %s enregistré (%s, %s, %s)", message.id, message.audience,
                message.trigger, message.confirmation);
        LOGGER.info("Exiting method saveMessage");
        return AdminForms.redirect(PAGE, "Message enregistré.", true);
    }

    /**
     * Takes a message down, confirmations included.
     *
     * <p>The confirmations go with it: they name a message that no longer
     * exists, and keeping them would leave a list of readings of nothing.
     *
     * @param form the posted identifier
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/delete")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deleteMessage(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deleteMessage");
        Long id = longOrNull(AdminForms.text(form, "id"));
        StoreMessage message = id == null ? null : StoreMessage.findById(id);
        if (message == null) {
            LOGGER.info("Exiting method deleteMessage");
            return AdminForms.redirect(PAGE, "Message inconnu.", false);
        }
        StoreMessageAck.delete("messageId", id);
        message.delete();
        LOGGER.info("Exiting method deleteMessage");
        return AdminForms.redirect(PAGE, "Message supprimé.", true);
    }

    /**
     * Reads a posted identifier, tolerating an absent or malformed one.
     *
     * @param raw the posted value, possibly null
     * @return the identifier, or null for a creation
     */
    private Long longOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    /**
     * Reads a posted whole number, falling back when it is absent or malformed.
     *
     * @param raw the posted value
     * @param fallback the value to use when nothing readable was posted
     * @return the parsed number, or the fallback
     */
    private int intOr(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException malformed) {
            return fallback;
        }
    }

    /**
     * Reads a posted moment, an absent one meaning no bound.
     *
     * @param raw the posted value, possibly null
     * @return the moment, or null when none was typed
     * @throws DateTimeParseException when something unreadable was typed
     */
    private LocalDateTime momentOf(String raw) {
        if (raw == null) {
            return null;
        }
        return LocalDateTime.parse(raw.trim(), MASK);
    }

    /**
     * Parses an audience name, tolerating an unknown or blank value.
     *
     * @param raw the posted name
     * @return the audience, or null when unrecognised
     */
    private StoreMessage.Audience audienceOf(String raw) {
        for (StoreMessage.Audience value : StoreMessage.Audience.values()) {
            if (value.name().equals(raw)) {
                return value;
            }
        }
        return null;
    }

    /**
     * Parses a trigger name, tolerating an unknown or blank value.
     *
     * @param raw the posted name
     * @return the trigger, or null when unrecognised
     */
    private StoreMessage.Trigger triggerOf(String raw) {
        for (StoreMessage.Trigger value : StoreMessage.Trigger.values()) {
            if (value.name().equals(raw)) {
                return value;
            }
        }
        return null;
    }

    /**
     * Parses a confirmation name, tolerating an unknown or blank value.
     *
     * @param raw the posted name
     * @return the confirmation mode, or null when unrecognised
     */
    private StoreMessage.Confirmation confirmationOf(String raw) {
        for (StoreMessage.Confirmation value : StoreMessage.Confirmation.values()) {
            if (value.name().equals(raw)) {
                return value;
            }
        }
        return null;
    }

    /**
     * Parses a response-type name, tolerating an unknown or blank value.
     *
     * @param raw the posted name
     * @return the response type, or null when unrecognised
     */
    private StoreMessage.ResponseType responseTypeOf(String raw) {
        for (StoreMessage.ResponseType value : StoreMessage.ResponseType.values()) {
            if (value.name().equals(raw)) {
                return value;
            }
        }
        return null;
    }

    /**
     * ONE LINE of the page: a message and what it has drawn back.
     */
    public static class Row {

        /** The message. */
        public final StoreMessage message;

        /** Its confirmations, newest first. */
        public final List<StoreMessageAck> acks;

        /**
         * Builds a row.
         *
         * @param message the message
         * @param acks its confirmations, newest first
         */
        public Row(StoreMessage message, List<StoreMessageAck> acks) {
            this.message = message;
            this.acks = acks;
        }

        /**
         * Returns the message.
         *
         * @return the message
         */
        public StoreMessage getMessage() {
            return message;
        }

        /**
         * Returns the confirmations.
         *
         * @return the confirmations, newest first
         */
        public List<StoreMessageAck> getAcks() {
            return acks;
        }

        /**
         * The diffusion period, written as the screen types it.
         *
         * @return the period, or a dash when the message has no bound
         */
        public String getPeriod() {
            if (message.startsAt == null && message.endsAt == null) {
                return "—";
            }
            String from = message.startsAt == null ? "" : message.startsAt.format(MASK);
            String to = message.endsAt == null ? "" : message.endsAt.format(MASK);
            return from + " → " + to;
        }
    }
}
