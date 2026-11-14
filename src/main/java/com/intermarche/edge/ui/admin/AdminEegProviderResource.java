package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.catalog.EegProvider;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;

/**
 * The ELECTRONIC SHELF LABELS screen ({@code /admin/eeg-providers},
 * BO-02-03-37, BO-02-03-38): the EEG providers of the point of sale, several
 * when the store runs several, each with its connector. The flow itself is
 * subject to the {@code eeg.enabled} parameter; the article fiche reads the
 * displayed price and the article list sends prices, to every active
 * provider. POST&nbsp;→&nbsp;303&nbsp;→&nbsp;notice.
 */
@Path("/admin/eeg-providers")
public class AdminEegProviderResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminEegProviderResource.class);

    /** The screen the gestures return to. */
    static final String PAGE = "/admin/eeg-providers";

    /** The providers template. */
    @Inject
    @Location("admin-eeg-providers")
    Template adminEegProviders;

    /** The back-office parameters: whether the flow is active. */
    @Inject
    com.intermarche.edge.service.PosSettingsService posSettingsService;

    /**
     * Shows the providers, and the one being edited when one is named.
     *
     * @param edit the id of the provider to edit, or null
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.EEG_PROVIDER)
    @Transactional
    public TemplateInstance page(@QueryParam("edit") Long edit,
                                 @QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method page with edit: " + edit);
        List<EegProvider> providers = EegProvider.listAllOrdered();
        EegProvider edited = edit == null ? null : EegProvider.<EegProvider>findById(edit);
        LOGGER.info("Exiting method page");
        return adminEegProviders.data("providers", providers)
                .data("edited", edited)
                .data("enabled", posSettingsService.eegEnabled())
                .data("kinds", List.of(EegProvider.KIND_SIMULATED, EegProvider.KIND_HTTP))
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Creates or updates a provider.
     *
     * @param form the posted form (id, code, label, kind, baseUrl, active)
     * @return a 303 redirect carrying the notice
     */
    @POST
    @Path("/save")
    @Authenticated
    @FeatureAccess(value = Feature.EEG_PROVIDER, option = CrudOption.CREATE, updateKey = "id")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method save with form: " + form);
        String code = AdminForms.text(form, "code");
        String label = AdminForms.text(form, "label");
        String kind = EegProvider.KIND_HTTP.equals(AdminForms.text(form, "kind"))
                ? EegProvider.KIND_HTTP : EegProvider.KIND_SIMULATED;
        String baseUrl = AdminForms.text(form, "baseUrl");
        if (code == null || label == null) {
            LOGGER.info("Exiting method save");
            return AdminForms.redirect(PAGE, "Le code et le libellé du fournisseur sont obligatoires.", false);
        }
        if (EegProvider.KIND_HTTP.equals(kind) && (baseUrl == null || !baseUrl.matches("https?://.+"))) {
            LOGGER.info("Exiting method save");
            return AdminForms.redirect(PAGE, "Un fournisseur HTTP demande une adresse http(s)://.", false);
        }
        String normalized = code.toUpperCase(Locale.ROOT);
        Long id = AdminForms.id(AdminForms.text(form, "id"));
        EegProvider edited = id == null ? null : EegProvider.<EegProvider>findById(id);
        if (id != null && edited == null) {
            LOGGER.info("Exiting method save");
            return AdminForms.redirect(PAGE, "Fournisseur d'étiquettes introuvable.", false);
        }
        EegProvider holder = EegProvider.findByCode(normalized);
        if (holder != null && holder != edited) {
            LOGGER.info("Exiting method save");
            return AdminForms.redirect(PAGE, "Le code " + normalized + " est déjà porté par un autre fournisseur.", false);
        }
        EegProvider provider = edited == null ? new EegProvider() : edited;
        provider.code = normalized;
        provider.label = label;
        provider.kind = kind;
        provider.baseUrl = baseUrl;
        provider.active = edited == null || AdminForms.checkbox(form, "active");
        if (edited == null) {
            provider.persist();
        }
        LOGGER.info("Exiting method save");
        return AdminForms.redirect(PAGE, "Fournisseur « " + normalized + " » enregistré.", true);
    }

    /**
     * Removes a provider.
     *
     * @param form the posted form (id)
     * @return a 303 redirect carrying the notice
     */
    @POST
    @Path("/delete")
    @Authenticated
    @FeatureAccess(value = Feature.EEG_PROVIDER, option = CrudOption.DELETE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response delete(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method delete with form: " + form);
        Long id = AdminForms.id(AdminForms.text(form, "id"));
        EegProvider provider = id == null ? null : EegProvider.<EegProvider>findById(id);
        if (provider == null) {
            LOGGER.info("Exiting method delete");
            return AdminForms.redirect(PAGE, "Fournisseur d'étiquettes introuvable.", false);
        }
        provider.delete();
        LOGGER.info("Exiting method delete");
        return AdminForms.redirect(PAGE, "Fournisseur « " + provider.code + " » supprimé.", true);
    }
}
