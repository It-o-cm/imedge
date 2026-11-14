package com.intermarche.edge.security;

import com.intermarche.edge.domain.people.Employee;
import com.intermarche.edge.domain.session.TechnicalEvent;
import com.intermarche.edge.service.TechnicalEventService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.oidc.OidcSession;
import io.quarkus.security.Authenticated;
import io.quarkus.vertx.http.runtime.security.PersistentLoginManager;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;

/**
 * The back office's door to the group's single sign-on (BO-01-02-01).
 *
 * <p>This path alone is protected by the OIDC authorization-code flow
 * ({@code quarkus.http.auth.permission.sso}): the identity provider
 * authenticates the person — no password is typed or kept here — and hands
 * back an ID token. The account is matched on the administered claim
 * ({@code pos.sso.claim}, the login by default, the e-mail address as a
 * fallback), must be active and admit the SSO, and the back-office session
 * is then opened exactly as a password sign-in opens it: same cookie, same
 * profiles, same echelons. The OIDC session itself is closed at once, so the
 * provider's tokens are not kept either.
 */
@Path("/admin/sso")
public class SsoLoginResource {

    private static final Logger LOGGER = Logger.getLogger(SsoLoginResource.class);

    /** Whether the single sign-on is offered on this node. */
    @ConfigProperty(name = "pos.sso.enabled", defaultValue = "false")
    boolean enabled;

    /** The ID-token claim matched against the account's login. */
    @ConfigProperty(name = "pos.sso.claim", defaultValue = "preferred_username")
    String claim;

    /** The key the session cookie is encrypted with, shared with form authentication. */
    @ConfigProperty(name = "quarkus.http.auth.session.encryption-key")
    Optional<String> encryptionKey;

    /** The session cookie name. */
    @ConfigProperty(name = "quarkus.http.auth.form.cookie-name", defaultValue = "quarkus-credential")
    String cookieName;

    /** The session inactivity timeout. */
    @ConfigProperty(name = "quarkus.http.auth.form.timeout", defaultValue = "PT30M")
    Duration timeout;

    /** How often the session cookie is renewed. */
    @ConfigProperty(name = "quarkus.http.auth.form.new-cookie-interval", defaultValue = "PT1M")
    Duration newCookieInterval;

    /** Whether the session cookie is HTTP only. */
    @ConfigProperty(name = "quarkus.http.auth.form.http-only-cookie", defaultValue = "false")
    boolean httpOnlyCookie;

    /** The session cookie's SameSite policy. */
    @ConfigProperty(name = "quarkus.http.auth.form.cookie-same-site", defaultValue = "STRICT")
    String cookieSameSite;

    /** The session cookie's path. */
    @ConfigProperty(name = "quarkus.http.auth.form.cookie-path", defaultValue = "/")
    String cookiePath;

    /** The OIDC session the code flow opened on this path. */
    @Inject
    OidcSession oidcSession;

    /** The HTTP exchange, on which the session cookie is written. */
    @Inject
    RoutingContext routingContext;

    /** The shop's event journal (BO-04-03-04). */
    @Inject
    TechnicalEventService technicalEventService;

    /** The sign-in mode posed at this node's echelon (BO-01-02-09). */
    @Inject
    com.intermarche.edge.service.PosSettingsService posSettingsService;

    /**
     * Completes a single sign-on: matches the account and opens its
     * back-office session.
     *
     * @return a page that continues to the back office, or a redirect to the
     *         sign-in page when the SSO is off on this node or its echelon, or
     *         the account is not admitted
     */
    @GET
    @Path("/login")
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public Response login() {
        LOGGER.info("Entering method login");
        if (!enabled || encryptionKey.isEmpty()
                || posSettingsService.backOfficeSignIn() == Employee.AuthMode.PASSWORD) {
            closeOidcSession();
            LOGGER.info("Exiting method login: SSO disabled");
            return refused("disabled");
        }
        JsonWebToken token = oidcSession.getIdToken();
        String subject = claimValue(token, claim);
        String email = claimValue(token, "email");
        Employee account = QuarkusTransaction.requiringNew().call(() -> match(subject, email));
        closeOidcSession();
        if (account == null) {
            LOGGER.infof("Connexion SSO refusée pour %s / %s", subject, email);
            LOGGER.info("Exiting method login: no account");
            return refused("refused");
        }
        loginManager().save(account.loginName, routingContext, cookieName, null,
                routingContext.request().isSSL());
        journal(account);
        LOGGER.info("Exiting method login");
        return Response.ok("<!DOCTYPE html><html lang=\"fr\"><head><meta charset=\"UTF-8\">"
                        + "<meta http-equiv=\"refresh\" content=\"0;url=/admin\"><title>Connexion</title></head>"
                        + "<body><p>Connexion réussie. <a href=\"/admin\">Continuer vers le back-office</a></p>"
                        + "</body></html>", MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    /**
     * Finds the account an SSO identity names.
     *
     * @param subject the administered claim's value, possibly null
     * @param email the e-mail claim's value, possibly null
     * @return the active account admitting the SSO, or null
     */
    Employee match(String subject, String email) {
        Employee account = subject == null ? null : Employee.<Employee>find("loginName", subject).firstResult();
        if (account == null && email != null) {
            account = Employee.<Employee>find("email", email).firstResult();
        }
        if (account == null || !account.isEnabled() || !account.allowsSsoLogin()) {
            return null;
        }
        return account;
    }

    /**
     * Reads one claim of the ID token as text.
     *
     * @param token the ID token, possibly null
     * @param name the claim name
     * @return the claim's text, or null when absent or blank
     */
    static String claimValue(JsonWebToken token, String name) {
        if (token == null) {
            return null;
        }
        Object value = token.getClaim(name);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        if (text.length() > 1 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        }
        return text.isEmpty() ? null : text;
    }

    /**
     * Builds the session manager form authentication uses, so the cookie
     * written here is the one it restores.
     *
     * @return the session manager
     */
    PersistentLoginManager loginManager() {
        return new PersistentLoginManager(encryptionKey.orElse(null), cookieName, timeout.toMillis(),
                newCookieInterval.toMillis(), httpOnlyCookie, cookieSameSite.toUpperCase(), cookiePath, -1L, null);
    }

    /**
     * Closes the OIDC session the code flow opened: the provider's tokens are
     * not kept.
     */
    void closeOidcSession() {
        try {
            oidcSession.logout().await().indefinitely();
        } catch (RuntimeException e) {
            LOGGER.debugf("Fermeture de la session OIDC impossible (%s)", e.getMessage());
        }
    }

    /**
     * Records the sign-in in the shop's event journal.
     *
     * @param account the account signed in
     */
    void journal(Employee account) {
        try {
            technicalEventService.log(TechnicalEvent.EventType.BACK_OFFICE_SIGNED_IN,
                    "connexion SSO " + account.loginName, account.badgeId);
        } catch (RuntimeException e) {
            LOGGER.debugf("Journalisation de la connexion SSO impossible (%s)", e.getMessage());
        }
    }

    /**
     * Sends the visitor back to the sign-in page with the reason's code.
     *
     * @param reason the code the sign-in page translates: disabled or refused
     * @return a 303 to the sign-in page
     */
    private Response refused(String reason) {
        return Response.seeOther(UriBuilder.fromPath("/admin/login").queryParam("sso", reason).build()).build();
    }
}
