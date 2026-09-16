package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.session.TechnicalEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

import java.net.URI;
import org.jboss.logging.Logger;

/**
 * Sign-in and sign-out screens of the back office.
 * <p>
 * Authentication itself is performed by Quarkus form authentication: the
 * form posts to {@code /j_security_check}, which hands the credentials to
 * {@code EmployeeIdentityProvider} and issues the session cookie. This
 * resource only renders the surrounding pages — it never reads a password.
 * <p>
 * The password screen sits here too: it is the one place where this code
 * base reads a back-office password, and it does so only to verify the
 * current one before replacing it.
 * <p>
 * The login page is deliberately open to anonymous requests, and so are the
 * two stylesheets it loads (they sit under {@code /admin/} as static
 * resources, outside any permission policy). Every other back-office screen
 * carries {@code @RolesAllowed}.
 * <p>
 * Placement: with {@link AdminSettingsResource} in {@code ui.admin}, because
 * these are back-office SCREENS; the identity plumbing they lean on lives in
 * {@code pos.security}, the mirror of the imvaluation split.
 */
@Path("/")
public class AdminAuthResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminAuthResource.class);

    /**
     * Name of the cookie holding the form authentication session.
     * <p>
     * Clearing it is what actually signs the operator out.
     */
    private static final String SESSION_COOKIE = "quarkus-credential";

    /** The minimum length of a back-office password. */
    private static final int MIN_PASSWORD_LENGTH = 8;

    /** The sign-in page template. */
    @Inject
    @Location("admin-login")
    Template adminLogin;

    /** The password-change page template. */
    @Inject
    @Location("admin-password")
    Template adminPassword;

    /** The identity of the signed-in operator. */
    @Inject
    SecurityIdentity identity;

    /**
     * The journal emitter. A back-office password change (BO-04-01-29) is
     * recorded through the single event mechanism, naming the operator by
     * badge (N° caissière) and never the secret.
     */
    @Inject
    com.intermarche.pos.service.TechnicalEventService technicalEventService;

    /**
     * Shows the sign-in page.
     *
     * @param error present when form authentication rejected the credentials
     * @param notice the one-shot message carried back from a sign-out
     * @return the sign-in page
     */
    @GET
    @Path("/admin/login")
    @PermitAll
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance loginPage(@QueryParam("error") String error,
                                      @QueryParam("notice") String notice) {
        LOGGER.info("Entering method loginPage with error: " + error + ", notice: " + notice);
        String message = error == null
                ? null
                : "Identifiant ou code incorrect, ou compte désactivé ou verrouillé.";
        LOGGER.info("Exiting method loginPage");
        return adminLogin.data("error", message).data("notice", notice);
    }

    /**
     * Signs the operator out by clearing the session cookie.
     *
     * @return a redirect to the sign-in page, carrying the cleared cookie
     */
    @POST
    @Path("/admin/logout")
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public Response logout() {
        LOGGER.info("Entering method logout");
        NewCookie cleared = new NewCookie.Builder(SESSION_COOKIE)
                .value("")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .build();
        URI target = UriBuilder.fromPath("/admin/login")
                .queryParam("notice", "Vous êtes déconnecté.")
                .build();
        LOGGER.info("Exiting method logout");
        return Response.seeOther(target).cookie(cleared).build();
    }

    /**
     * Shows the password-change page.
     *
     * @param error the one-shot refusal message, or null
     * @return the password-change page
     */
    @GET
    @Path("/admin/password")
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance passwordPage(@QueryParam("error") String error) {
        LOGGER.info("Entering method passwordPage with error: " + error);
        Boolean imposed = identity.getAttribute("mustChangePassword");
        LOGGER.info("Exiting method passwordPage");
        return adminPassword.data("error", error)
                .data("imposed", imposed != null && imposed);
    }

    /**
     * Applies a password change and forces a fresh sign-in.
     * <p>
     * The current password is required even though the operator is already
     * signed in: a session left open on an unattended screen must not be
     * enough to seize the account. The new password must differ from the
     * current one, or an imposed password could be "changed" for itself.
     * <p>
     * On success the session cookie is cleared: it was issued against the
     * old credential, and making the operator sign in again is the shortest
     * proof that the new one works.
     *
     * @param current the current password
     * @param renewed the new password
     * @param confirmation the new password, typed again
     * @return a redirect to the sign-in page, or back to the form with a
     *         refusal
     */
    @POST
    @Path("/admin/password")
    @Authenticated
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response changePassword(@FormParam("current") String current,
                                   @FormParam("renewed") String renewed,
                                   @FormParam("confirmation") String confirmation) {
        LOGGER.info("Entering method changePassword with current: " + current + ", renewed: " + renewed + ", confirmation: " + confirmation);
        if (renewed == null || renewed.length() < MIN_PASSWORD_LENGTH) {
            LOGGER.info("Exiting method changePassword");
            return refuse("Le nouveau mot de passe doit compter au moins "
                    + MIN_PASSWORD_LENGTH + " caractères.");
        }
        if (!renewed.equals(confirmation)) {
            LOGGER.info("Exiting method changePassword");
            return refuse("Les deux saisies du nouveau mot de passe diffèrent.");
        }
        if (renewed.equals(current)) {
            LOGGER.info("Exiting method changePassword");
            return refuse("Le nouveau mot de passe doit être différent de l'actuel.");
        }
        String loginName = identity.getPrincipal().getName();
        boolean applied = QuarkusTransaction.requiringNew().call(() -> {
            Employee employee = Employee.find("loginName", loginName).firstResult();
            if (employee == null || !employee.verifyBackOfficePassword(current)) {
                return false;
            }
            employee.setBackOfficePassword(renewed);
            employee.mustChangePassword = false;
            technicalEventService.log(TechnicalEvent.EventType.PASSWORD_CHANGED,
                    null, employee.badgeId);
            return true;
        });
        if (!applied) {
            LOGGER.info("Exiting method changePassword");
            return refuse("Mot de passe actuel incorrect.");
        }
        NewCookie cleared = new NewCookie.Builder(SESSION_COOKIE)
                .value("")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .build();
        URI target = UriBuilder.fromPath("/admin/login")
                .queryParam("notice", "Mot de passe modifié. Reconnectez-vous.")
                .build();
        LOGGER.info("Exiting method changePassword");
        return Response.seeOther(target).cookie(cleared).build();
    }

    /**
     * Builds the redirect back to the password form carrying a refusal.
     *
     * @param message the reason shown to the operator
     * @return a 303 redirect to the password page
     */
    private Response refuse(String message) {
        URI target = UriBuilder.fromPath("/admin/password")
                .queryParam("error", message)
                .build();
        return Response.seeOther(target).build();
    }
}
