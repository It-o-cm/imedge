package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.setting.ServiceProvider;
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

import java.util.List;

/**
 * The back office's SERVICE PROVIDERS page ({@code /admin/providers}): which third
 * parties this store is connected to, how they are reached and when they are
 * called (BO-03-11-01 to 06).
 *
 * <p>The page is the declaration, never the conversation: it records that gift
 * cards are issued by a provider spoken to in NEXO Retailer at such an address,
 * called at the scan. Implementing a protocol remains a delivery, and a provider
 * declared in a protocol this release does not carry is simply never called — the
 * page says so rather than pretending the link exists.
 *
 * <p>The CREDENTIAL never comes back out. The form writes it, {@code hasCredential}
 * is all the page displays, there is no export, and — the trap of this screen — the
 * posted form is NOT logged: every other admin resource here logs its form on
 * entry, and doing that once on this one would put the key in the technical log
 * for good. An empty credential field on a save LEAVES the stored one alone, so
 * correcting a label does not silently disconnect the provider.
 *
 * <p>Same admin cycle as the other back-office pages: POST &rarr; 303 &rarr; a
 * one-shot notice, {@code @RolesAllowed("ADMIN")} throughout.
 */
@Path("/admin/providers")
public class AdminProviderResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminProviderResource.class);

    /** The page this resource redirects to after every POST. */
    private static final String PAGE = "/admin/providers";

    /** The providers page template. */
    @Inject
    @Location("admin-providers")
    Template adminProviders;

    /**
     * Shows the providers page: the declared rows and the catalogs the form offers.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @RolesAllowed("ADMIN")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance providersPage(@QueryParam("notice") String notice,
            @QueryParam("noticeOk") String noticeOk) {
        LOGGER.info("Entering method providersPage");
        List<ServiceProvider> providers = ServiceProvider.listAllOrdered();
        LOGGER.info("Exiting method providersPage");
        return adminProviders
                .data("providers", providers)
                .data("types", ServiceProvider.ProviderType.values())
                .data("protocols", ServiceProvider.ProviderProtocol.values())
                .data("phases", ServiceProvider.CallPhase.values())
                .data("messages", com.intermarche.edge.domain.setting.ProviderMessage.listAllOrdered())
                .data("notice", notice)
                .data("noticeOk", !"false".equals(noticeOk));
    }

    /**
     * Creates or updates a provider, keyed on its functional code (BO-03-11-01/03/
     * 04/05/06).
     *
     * <p>The form is deliberately absent from the logs: it carries the credential.
     *
     * @param form the posted description
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/save")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveProvider(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveProvider");
        String code = AdminForms.text(form, "code");
        if (code == null) {
            LOGGER.info("Exiting method saveProvider");
            return AdminForms.redirect(PAGE, "Le code du fournisseur est obligatoire.", false);
        }
        ServiceProvider.ProviderType type = providerType(AdminForms.text(form, "providerType"));
        if (type == null) {
            LOGGER.info("Exiting method saveProvider");
            return AdminForms.redirect(PAGE, "Type de fournisseur inconnu.", false);
        }
        ServiceProvider.ProviderProtocol protocol = protocol(AdminForms.text(form, "protocol"));
        if (protocol == null) {
            LOGGER.info("Exiting method saveProvider");
            return AdminForms.redirect(PAGE, "Protocole inconnu.", false);
        }
        ServiceProvider.CallPhase phase = callPhase(AdminForms.text(form, "callPhase"));
        if (phase == null) {
            LOGGER.info("Exiting method saveProvider");
            return AdminForms.redirect(PAGE, "Phase d'appel inconnue.", false);
        }
        ServiceProvider existing = ServiceProvider.findByCode(code);
        ServiceProvider provider = existing;
        if (provider == null) {
            provider = new ServiceProvider();
            provider.code = code;
            provider.persist();
        }
        provider.label = AdminForms.text(form, "label");
        provider.providerType = type;
        provider.protocol = protocol;
        provider.callPhase = phase;
        provider.endpoint = AdminForms.text(form, "endpoint");
        provider.articleSelection = AdminForms.text(form, "articleSelection");
        provider.documentTemplateCode = AdminForms.text(form, "documentTemplateCode");
        provider.active = AdminForms.checkbox(form, "active");
        applyCredential(provider, AdminForms.text(form, "authKey"));
        LOGGER.infof("Fournisseur %s enregistré (%s, %s, %s).", provider.code,
                provider.providerType, provider.protocol, provider.callPhase);
        LOGGER.info("Exiting method saveProvider");
        return AdminForms.redirect(PAGE, existing == null
                ? "Fournisseur déclaré." : "Fournisseur enregistré.", true);
    }

    /**
     * Clears the stored credential of a provider, without touching the rest.
     *
     * <p>There has to be a way to REMOVE a credential, since a blank field on a
     * save keeps the stored one: without this the only way out would be to delete
     * the provider and declare it again.
     *
     * @param form the posted form (code)
     * @return a 303 redirect with the notice
     */
    /**
     * Declares or corrects what a provider's message code says in the shop's
     * words (BO-03-08-06 and -07).
     *
     * <p>On THIS page and not on one of its own, because a substitution has no
     * meaning without the provider it belongs to: the code 4 of one issuer is
     * not the code 4 of another, and the pair is the key.
     *
     * <p>An empty screen text keeps the provider's own wording, an empty ticket
     * text prints nothing at all — a provider's technical answer has no
     * business on a customer's paper unless the shop wrote the sentence.
     *
     * @param form the posted substitution
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/message/save")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveProviderMessage(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveProviderMessage");
        String providerCode = AdminForms.text(form, "providerCode");
        String messageCode = AdminForms.text(form, "messageCode");
        if (providerCode == null || messageCode == null) {
            LOGGER.info("Exiting method saveProviderMessage");
            return AdminForms.redirect(PAGE,
                    "Le fournisseur et le code message sont obligatoires.", false);
        }
        com.intermarche.edge.domain.setting.ProviderMessage message =
                com.intermarche.edge.domain.setting.ProviderMessage
                        .find("providerCode = ?1 and messageCode = ?2", providerCode, messageCode)
                        .firstResult();
        if (message == null) {
            message = new com.intermarche.edge.domain.setting.ProviderMessage();
            message.providerCode = providerCode;
            message.messageCode = messageCode;
            message.persist();
        }
        message.screenText = AdminForms.text(form, "screenText");
        message.ticketText = AdminForms.text(form, "ticketText");
        message.active = AdminForms.checkbox(form, "messageActive");
        LOGGER.infof("Message fournisseur %s/%s enregistré", providerCode, messageCode);
        LOGGER.info("Exiting method saveProviderMessage");
        return AdminForms.redirect(PAGE, "Message fournisseur enregistré.", true);
    }

    /**
     * Removes a substitution, so the provider's own wording stands again.
     *
     * @param form the posted key
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/message/delete")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deleteProviderMessage(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deleteProviderMessage");
        com.intermarche.edge.domain.setting.ProviderMessage message =
                com.intermarche.edge.domain.setting.ProviderMessage
                        .find("providerCode = ?1 and messageCode = ?2",
                                AdminForms.text(form, "providerCode"),
                                AdminForms.text(form, "messageCode"))
                        .firstResult();
        if (message == null) {
            LOGGER.info("Exiting method deleteProviderMessage");
            return AdminForms.redirect(PAGE, "Message fournisseur inconnu.", false);
        }
        message.delete();
        LOGGER.info("Exiting method deleteProviderMessage");
        return AdminForms.redirect(PAGE, "Message fournisseur supprimé.", true);
    }

    @POST
    @Path("/clear-credential")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response clearCredential(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method clearCredential");
        ServiceProvider provider = ServiceProvider.findByCode(AdminForms.text(form, "code"));
        if (provider == null) {
            LOGGER.info("Exiting method clearCredential");
            return AdminForms.redirect(PAGE, "Fournisseur inconnu.", false);
        }
        provider.authKey = null;
        LOGGER.info("Exiting method clearCredential");
        return AdminForms.redirect(PAGE, "Clé d'authentification effacée.", true);
    }

    /**
     * Removes a provider from the declaration (BO-03-11-02).
     *
     * @param form the posted form (code)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/delete")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deleteProvider(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deleteProvider");
        ServiceProvider provider = ServiceProvider.findByCode(AdminForms.text(form, "code"));
        if (provider == null) {
            LOGGER.info("Exiting method deleteProvider");
            return AdminForms.redirect(PAGE, "Fournisseur inconnu.", false);
        }
        provider.delete();
        LOGGER.info("Exiting method deleteProvider");
        return AdminForms.redirect(PAGE, "Fournisseur supprimé.", true);
    }

    /**
     * Writes a credential only when one was actually posted.
     *
     * <p>A blank field means "unchanged", not "none": the form cannot show the
     * stored key, so an operator correcting a label posts an empty credential field
     * every time, and taking that literally would disconnect the provider on the
     * next save.
     *
     * @param provider the provider being saved
     * @param posted the posted credential, null when the field was left empty
     */
    private void applyCredential(ServiceProvider provider, String posted) {
        if (posted == null) {
            return;
        }
        provider.authKey = posted;
    }

    /**
     * Reads a provider type by name.
     *
     * @param name the posted name, possibly null
     * @return the type, or null when the name is absent or unknown
     */
    private ServiceProvider.ProviderType providerType(String name) {
        for (ServiceProvider.ProviderType candidate : ServiceProvider.ProviderType.values()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Reads a protocol by name.
     *
     * @param name the posted name, possibly null
     * @return the protocol, or null when the name is absent or unknown
     */
    private ServiceProvider.ProviderProtocol protocol(String name) {
        for (ServiceProvider.ProviderProtocol candidate
                : ServiceProvider.ProviderProtocol.values()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Reads a call phase by name.
     *
     * @param name the posted name, possibly null
     * @return the phase, or null when the name is absent or unknown
     */
    private ServiceProvider.CallPhase callPhase(String name) {
        for (ServiceProvider.CallPhase candidate : ServiceProvider.CallPhase.values()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        return null;
    }
}
