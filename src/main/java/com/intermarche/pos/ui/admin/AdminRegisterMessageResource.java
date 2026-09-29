package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.sync.RegisterCommand;
import com.intermarche.pos.domain.store.CheckoutPoint;
import com.intermarche.pos.security.CurrentUser;
import com.intermarche.pos.service.sync.RegisterCommandService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.util.List;
import org.jboss.logging.Logger;

/**
 * The BACK-OFFICE MESSAGE screen ({@code /admin/register-messages}) — the
 * descending channel that carries a message from the store back office to a
 * register (EXP-20).
 *
 * <p>It reuses the existing register-command channel
 * ({@link RegisterCommandService#issue}) rather than inventing a second one: a
 * {@link RegisterCommand.CommandType#DISPLAY_MESSAGE} order is left for the
 * chosen register, which collects it on its next poll. The message shows on the
 * register's CASHIER screen only, never the customer display, and never
 * interrupts a sale — that rule is the register's rendering concern (signalled
 * to the caisse campaign); this screen only issues the order.
 *
 * <p>The registers offered are the administered checkout points (BO-03-09), so a
 * message can only be aimed at a register the store actually knows.
 */
@Path("/admin/register-messages")
public class AdminRegisterMessageResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminRegisterMessageResource.class);

    /** The redirect target, equal to the resource path. */
    private static final String PAGE = "/admin/register-messages";

    /** The register-messages page template. */
    @Inject
    @Location("admin-register-messages")
    Template adminRegisterMessages;

    /** The order book the message is dropped into. */
    @Inject
    RegisterCommandService registerCommandService;

    /** The signed-in operator, recorded as the message's issuer. */
    @Inject
    CurrentUser currentUser;

    /**
     * Shows the register-messages page.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @RolesAllowed("ADMIN")
    @Transactional
    public TemplateInstance messagesPage(@QueryParam("notice") String notice,
                                         @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method messagesPage with notice: " + notice + ", noticeOk: " + noticeOk);
        List<CheckoutPoint> points = CheckoutPoint.listAllOrdered();
        LOGGER.info("Exiting method messagesPage");
        return adminRegisterMessages.data("points", points)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Issues a display-message order to the chosen register (EXP-20).
     *
     * @param form the posted form (terminalId, message)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/send")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response sendMessage(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method sendMessage with form: " + form);
        String terminalId = AdminForms.text(form, "terminalId");
        if (terminalId == null) {
            LOGGER.info("Exiting method sendMessage");
            return AdminForms.redirect(PAGE, "Choisissez une caisse destinataire.", false);
        }
        if (CheckoutPoint.findByTerminal(terminalId) == null) {
            LOGGER.info("Exiting method sendMessage");
            return AdminForms.redirect(PAGE, "Caisse inconnue : " + terminalId, false);
        }
        String message = AdminForms.text(form, "message");
        if (message == null) {
            LOGGER.info("Exiting method sendMessage");
            return AdminForms.redirect(PAGE, "Le message est obligatoire.", false);
        }
        registerCommandService.issue(terminalId, RegisterCommand.CommandType.DISPLAY_MESSAGE,
                issuer(), message);
        LOGGER.info("Exiting method sendMessage");
        return AdminForms.redirect(PAGE, "Message déposé pour la caisse " + terminalId + ".", true);
    }

    /**
     * The login of the signed-in operator, recorded as the message's issuer, or
     * null when no identity is bound.
     *
     * @return the issuer login, or null
     */
    private String issuer() {
        return currentUser == null ? null : currentUser.getName();
    }
}
