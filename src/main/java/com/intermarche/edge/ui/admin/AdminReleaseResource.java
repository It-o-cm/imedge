package com.intermarche.edge.ui.admin;

import com.intermarche.edge.domain.people.CrudOption;
import com.intermarche.edge.domain.setting.Feature;
import com.intermarche.edge.security.FeatureAccess;
import com.intermarche.edge.service.sync.store.ReferentialReleaseService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The PROGRAMMED DEPLOYMENTS screen ({@code /admin/releases}) of the store node
 * (BO-03-12-03, BO-03-12-04).
 *
 * <p>Three gestures program the referential: ARMING a date for one's own next
 * back-office changes (a partial deployment: only the domains they touch are
 * held), programming a COMPLETE deployment (every distributed domain held until
 * the date), and posing a parameter with a date of effect on the echelon or
 * register configuration screens. The screen lists what is held and when it
 * goes, and lets each release be brought forward, moved or cancelled, as each
 * dated parameter.
 */
@Path("/admin/releases")
public class AdminReleaseResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminReleaseResource.class);

    /** The redirect target, equal to the resource path. */
    private static final String PAGE = "/admin/releases";

    /** The minute format of the notices. */
    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** The notice of a missing or past date. */
    static final String INVALID_DATE = "La date d'effet doit être une date à venir.";

    /** The notice of an unknown release or dated parameter. */
    static final String UNKNOWN = "Déploiement programmé introuvable.";

    /** The page template. */
    @Inject
    @Location("admin-releases")
    Template adminReleases;

    /** The programmed deployment engine. */
    @Inject
    ReferentialReleaseService releaseService;

    /** The signed-in administrator. */
    @Inject
    SecurityIdentity identity;

    /**
     * Shows the screen.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @Authenticated
    @FeatureAccess(value = Feature.REFERENTIAL_RELEASE)
    public TemplateInstance page(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        LOGGER.info("Entering method page");
        LocalDateTime armedAt = releaseService.armedAt(user());
        List<ReferentialReleaseService.PendingRow> pending = releaseService.pending();
        LOGGER.info("Exiting method page");
        return adminReleases.data("armedAt", armedAt == null ? null : armedAt.format(SHOWN))
                .data("enabled", releaseService.enabled())
                .data("pending", pending)
                .data("history", releaseService.history())
                .data("dated", releaseService.datedSettings())
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Arms a date of effect for the administrator's next changes (BO-03-12-03).
     *
     * @param form the posted form (at)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/arm")
    @Authenticated
    @FeatureAccess(value = Feature.REFERENTIAL_RELEASE, option = CrudOption.CREATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response arm(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method arm with form: " + form);
        LocalDateTime at = minute(AdminForms.text(form, "at"));
        if (!releaseService.arm(user(), at)) {
            LOGGER.info("Exiting method arm");
            return AdminForms.redirect(PAGE, INVALID_DATE, false);
        }
        LOGGER.info("Exiting method arm");
        return AdminForms.redirect(PAGE, "Vos prochaines modifications partiront le " + at.format(SHOWN) + ".", true);
    }

    /**
     * Disarms the administrator: his next changes are immediate again.
     *
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/disarm")
    @Authenticated
    @FeatureAccess(value = Feature.REFERENTIAL_RELEASE, option = CrudOption.CREATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response disarm() {
        LOGGER.info("Entering method disarm");
        releaseService.disarm(user());
        LOGGER.info("Exiting method disarm");
        return AdminForms.redirect(PAGE, "Vos modifications partent de nouveau immédiatement.", true);
    }

    /**
     * Programs a complete deployment (BO-03-12-04).
     *
     * @param form the posted form (at)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/complete")
    @Authenticated
    @FeatureAccess(value = Feature.REFERENTIAL_RELEASE, option = CrudOption.CREATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response complete(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method complete with form: " + form);
        LocalDateTime at = future(minute(AdminForms.text(form, "at")));
        if (at == null) {
            LOGGER.info("Exiting method complete");
            return AdminForms.redirect(PAGE, INVALID_DATE, false);
        }
        int held = releaseService.programComplete(user(), at).size();
        LOGGER.info("Exiting method complete");
        return AdminForms.redirect(PAGE, "Déploiement complet programmé le " + at.format(SHOWN) + " : "
                + held + " domaine(s) retenu(s).", true);
    }

    /**
     * Brings a release forward to now: the registers receive the pending version.
     *
     * @param form the posted form (id)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/advance")
    @Authenticated
    @FeatureAccess(value = Feature.REFERENTIAL_RELEASE, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response advance(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method advance with form: " + form);
        Long id = AdminForms.id(AdminForms.text(form, "id"));
        if (id == null || !releaseService.reschedule(id, LocalDateTime.now(), user())) {
            LOGGER.info("Exiting method advance");
            return AdminForms.redirect(PAGE, UNKNOWN, false);
        }
        LOGGER.info("Exiting method advance");
        return AdminForms.redirect(PAGE, "Déploiement avancé : les caisses reçoivent la nouvelle version.", true);
    }

    /**
     * Moves a release to another date to come.
     *
     * @param form the posted form (id, at)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/reschedule")
    @Authenticated
    @FeatureAccess(value = Feature.REFERENTIAL_RELEASE, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response reschedule(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method reschedule with form: " + form);
        LocalDateTime at = future(minute(AdminForms.text(form, "at")));
        if (at == null) {
            LOGGER.info("Exiting method reschedule");
            return AdminForms.redirect(PAGE, INVALID_DATE, false);
        }
        Long id = AdminForms.id(AdminForms.text(form, "id"));
        if (id == null || !releaseService.reschedule(id, at, user())) {
            LOGGER.info("Exiting method reschedule");
            return AdminForms.redirect(PAGE, UNKNOWN, false);
        }
        LOGGER.info("Exiting method reschedule");
        return AdminForms.redirect(PAGE, "Déploiement reprogrammé le " + at.format(SHOWN) + ".", true);
    }

    /**
     * Cancels a release: the back office takes back the version the registers hold.
     *
     * @param form the posted form (id)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/cancel")
    @Authenticated
    @FeatureAccess(value = Feature.REFERENTIAL_RELEASE, option = CrudOption.DELETE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response cancel(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method cancel with form: " + form);
        Long id = AdminForms.id(AdminForms.text(form, "id"));
        if (id == null || !releaseService.cancel(id, user())) {
            LOGGER.info("Exiting method cancel");
            return AdminForms.redirect(PAGE, UNKNOWN, false);
        }
        LOGGER.info("Exiting method cancel");
        return AdminForms.redirect(PAGE,
                "Déploiement annulé : le back-office reprend la version des caisses.", true);
    }

    /**
     * Brings a dated parameter into effect now.
     *
     * @param form the posted form (id)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/settings/advance")
    @Authenticated
    @FeatureAccess(value = Feature.REFERENTIAL_RELEASE, option = CrudOption.UPDATE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response advanceSetting(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method advanceSetting with form: " + form);
        Long id = AdminForms.id(AdminForms.text(form, "id"));
        if (id == null || !releaseService.advanceSetting(id)) {
            LOGGER.info("Exiting method advanceSetting");
            return AdminForms.redirect(PAGE, UNKNOWN, false);
        }
        LOGGER.info("Exiting method advanceSetting");
        return AdminForms.redirect(PAGE, "Paramètre daté appliqué dès maintenant.", true);
    }

    /**
     * Cancels a dated parameter: the echelon inherits again.
     *
     * @param form the posted form (id)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/settings/cancel")
    @Authenticated
    @FeatureAccess(value = Feature.REFERENTIAL_RELEASE, option = CrudOption.DELETE)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response cancelSetting(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method cancelSetting with form: " + form);
        Long id = AdminForms.id(AdminForms.text(form, "id"));
        if (id == null || !releaseService.cancelSetting(id)) {
            LOGGER.info("Exiting method cancelSetting");
            return AdminForms.redirect(PAGE, UNKNOWN, false);
        }
        LOGGER.info("Exiting method cancelSetting");
        return AdminForms.redirect(PAGE, "Paramètre daté annulé.", true);
    }

    /**
     * Reads a {@code datetime-local} value.
     *
     * @param value the posted value, possibly null
     * @return the minute, or null when absent or unreadable
     */
    static LocalDateTime minute(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim());
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    /**
     * Keeps a minute only when it is still to come.
     *
     * @param at the minute, possibly null
     * @return the minute, or null when absent or not in the future
     */
    static LocalDateTime future(LocalDateTime at) {
        return at != null && at.isAfter(LocalDateTime.now()) ? at : null;
    }

    /**
     * Reads the login of the signed-in administrator.
     *
     * @return the login, or null when nobody is signed in
     */
    private String user() {
        return identity == null || identity.getPrincipal() == null ? null : identity.getPrincipal().getName();
    }
}
