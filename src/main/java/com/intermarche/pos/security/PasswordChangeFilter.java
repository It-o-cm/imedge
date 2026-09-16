package com.intermarche.pos.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * Confines an operator to the password screen while their back-office
 * password is one they did not choose.
 * <p>
 * {@code Employee.mustChangePassword} is set whenever the password comes
 * from somewhere other than its owner — the seed today, an administrator's
 * reset tomorrow. Until it is replaced, every back-office screen redirects
 * to {@code /admin/password}, so a password known outside the account never
 * survives its first sign-in.
 * <p>
 * Scope, deliberately narrow:
 * <ul>
 *   <li>only the SCREEN surfaces are guarded ({@code /admin},
 *       {@code /dashboard}). The machine surfaces — CSV imports, engine
 *       feeds, GraphQL — are outside those prefixes anyway;</li>
 *   <li>and any request carrying an {@code Authorization} header is left
 *       alone whatever its path: it came in through HTTP Basic, it expects
 *       a response, and it cannot fill a form. That is what keeps the
 *       store node's {@code /dashboard-data} readable by a machine while
 *       the browser sitting on {@code /dashboard} is diverted;</li>
 *   <li>the password screen itself, the sign-out and the sign-in page are
 *       excluded, or the redirect would loop.</li>
 * </ul>
 * <p>
 * Runs after the authorization check, so an operator who may not reach a
 * page is refused on the role before being asked for a password.
 */
@Provider
@Priority(Priorities.AUTHORIZATION + 10)
public class PasswordChangeFilter implements ContainerRequestFilter {

    /** The back-office screens this filter watches over. */
    private static final List<String> GUARDED_PREFIXES = List.of("/admin", "/dashboard");

    /** The screens that must keep answering while the change is pending. */
    private static final List<String> EXCLUDED_PREFIXES =
            List.of("/admin/password", "/admin/logout", "/admin/login");

    /** The identity of the current request. */
    @Inject
    SecurityIdentity identity;

    /**
     * Diverts a back-office request to the password screen while the
     * signed-in operator still carries an imposed password.
     *
     * @param requestContext the JAX-RS request context
     * @throws IOException never in practice (filter contract)
     */
    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (identity == null || identity.isAnonymous()) {
            return;
        }
        Boolean mustChange = identity.getAttribute("mustChangePassword");
        if (mustChange == null || !mustChange) {
            return;
        }
        if (requestContext.getHeaderString(HttpHeaders.AUTHORIZATION) != null) {
            // HTTP Basic: a machine client, which cannot fill a form.
            return;
        }
        String raw = requestContext.getUriInfo().getPath();
        String path = raw.startsWith("/") ? raw : "/" + raw;
        if (!isGuarded(path) || isExcluded(path)) {
            return;
        }
        requestContext.abortWith(
                Response.seeOther(URI.create("/admin/password")).build()
        );
    }

    /**
     * Returns true when the path is one of the back-office screens.
     *
     * @param path the normalized request path
     * @return true for a guarded screen
     */
    private boolean isGuarded(String path) {
        for (String prefix : GUARDED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true when the path must keep answering during the change.
     *
     * @param path the normalized request path
     * @return true for an excluded screen
     */
    private boolean isExcluded(String path) {
        for (String prefix : EXCLUDED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
