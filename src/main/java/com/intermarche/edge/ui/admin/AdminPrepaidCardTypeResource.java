package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.payment.PrepaidCardType;
import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.security.FeatureAccess;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Locale;

/**
 * The PREPAID CARD TYPE screen ({@code /admin/prepaid-cards}, BO-03-02-37):
 * the types of prepaid card — gift cards, gift boxes — the registers know,
 * each with the protocol it is spoken in (NEXO through the third-party hub,
 * LOCAL by the register's registry), its provider, the ranges its numbers
 * fall in and the gestures it allows: payment, balance, activation.
 *
 * <p>Distributed by the {@code PREPAID_CARD_TYPES} pull. At the register a
 * card number is matched against the active types, the longest prefix
 * winning, and the type rules the payment, the balance consultation and the
 * activation of the card.
 */
@Path("/admin/prepaid-cards")
public class AdminPrepaidCardTypeResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminPrepaidCardTypeResource.class);

    /** The redirect target, equal to the resource path. */
    static final String PAGE = "/admin/prepaid-cards";

    /** The longest code the screen accepts. */
    static final int MAX_CODE = 40;

    /** The prepaid-card-types template. */
    @Inject
    @Location("admin-prepaid-cards")
    Template adminPrepaidCards;

    /**
     * Shows the types of prepaid card.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.PREPAID_CARD_TYPE)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance list(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method list");
        LOGGER.info("Exiting method list");
        return adminPrepaidCards.data("types", PrepaidCardType.findAllOrdered())
                .data("protocols", PrepaidCardType.PROTOCOLS)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Creates or updates a type of prepaid card by its code. Every mandatory
     * column is set before the row is persisted.
     *
     * @param form the posted form
     * @return a 303 back to the screen with the outcome
     */
    @POST
    @Path("/save")
    @Authenticated
    @FeatureAccess(value = Feature.PREPAID_CARD_TYPE, option = CrudOption.CREATE, updateKey = "code",
            entity = PrepaidCardType.class)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save");
        String code = AdminForms.text(form, "code");
        if (code == null || code.length() > MAX_CODE) {
            LOGGER.info("Exiting method save: no code");
            return AdminForms.redirect(PAGE, "Le code du type de carte est obligatoire (40 caractères au plus).", false);
        }
        String label = AdminForms.text(form, "label");
        if (label == null) {
            LOGGER.info("Exiting method save: no label");
            return AdminForms.redirect(PAGE, "Le libellé du type de carte est obligatoire.", false);
        }
        String protocol = AdminForms.text(form, "protocol");
        String upperProtocol = protocol == null ? "" : protocol.toUpperCase(Locale.ROOT);
        if (!PrepaidCardType.PROTOCOLS.contains(upperProtocol)) {
            LOGGER.info("Exiting method save: unknown protocol");
            return AdminForms.redirect(PAGE, "Protocole de carte inconnu.", false);
        }
        String ranges = AdminForms.text(form, "ranges");
        String invalid = invalidRange(ranges);
        if (invalid != null) {
            LOGGER.info("Exiting method save: invalid range");
            return AdminForms.redirect(PAGE, "Plage de cartes invalide : " + invalid, false);
        }
        String key = code.toUpperCase(Locale.ROOT);
        PrepaidCardType existing = PrepaidCardType.findByCode(key);
        PrepaidCardType type = existing == null ? new PrepaidCardType() : existing;
        type.code = key;
        type.label = label;
        type.protocol = upperProtocol;
        type.provider = AdminForms.text(form, "provider");
        type.ranges = ranges;
        type.paymentAllowed = AdminForms.checkbox(form, "paymentAllowed");
        type.balanceAllowed = AdminForms.checkbox(form, "balanceAllowed");
        type.activationAllowed = AdminForms.checkbox(form, "activationAllowed");
        type.displayOrder = AdminForms.integer(AdminForms.text(form, "displayOrder"), 0);
        type.active = AdminForms.checkbox(form, "active");
        type.persist();
        LOGGER.info("Exiting method save");
        return AdminForms.redirect(PAGE, existing == null ? "Type de carte créé." : "Type de carte enregistré.", true);
    }

    /**
     * Deletes a type of prepaid card.
     *
     * @param form the posted form (code)
     * @return a 303 back to the screen with the outcome
     */
    @POST
    @Path("/delete")
    @Authenticated
    @FeatureAccess(value = Feature.PREPAID_CARD_TYPE, option = CrudOption.DELETE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response delete(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method delete");
        PrepaidCardType type = PrepaidCardType.findByCode(AdminForms.text(form, "code"));
        if (type == null) {
            LOGGER.info("Exiting method delete: unknown type");
            return AdminForms.redirect(PAGE, "Type de carte inconnu.", false);
        }
        type.delete();
        LOGGER.info("Exiting method delete");
        return AdminForms.redirect(PAGE, "Type de carte supprimé.", true);
    }

    /**
     * Switches a type of prepaid card in or out of the registers in one click.
     *
     * @param form the posted form (code)
     * @return a 303 back to the screen with the outcome
     */
    @POST
    @Path("/toggle")
    @Authenticated
    @FeatureAccess(value = Feature.PREPAID_CARD_TYPE, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response toggle(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method toggle");
        PrepaidCardType type = PrepaidCardType.findByCode(AdminForms.text(form, "code"));
        if (type == null) {
            LOGGER.info("Exiting method toggle: unknown type");
            return AdminForms.redirect(PAGE, "Type de carte inconnu.", false);
        }
        type.active = !type.active;
        type.persist();
        LOGGER.info("Exiting method toggle");
        return AdminForms.redirect(PAGE, type.active ? "Type de carte activé." : "Type de carte désactivé.", true);
    }

    /**
     * Finds the first unreadable entry of written ranges: an entry is
     * {@code prefix} or {@code prefix:length}, the prefix made of letters and
     * digits, the length a strictly positive number.
     *
     * @param ranges the written ranges, possibly null
     * @return the first unreadable entry, the empty string when nothing is written, or null when all read
     */
    static String invalidRange(String ranges) {
        if (ranges == null) {
            return "";
        }
        for (String raw : ranges.split(";")) {
            String entry = raw.trim();
            if (!entry.matches("[A-Za-z0-9]+(:[1-9][0-9]*)?")) {
                return entry;
            }
        }
        return null;
    }
}
