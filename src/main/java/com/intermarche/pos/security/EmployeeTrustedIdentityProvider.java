package com.intermarche.pos.security;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Restores a back-office identity from the form authentication cookie.
 * <p>
 * Form authentication checks the password ONCE, on the login post, then puts
 * the principal in a signed session cookie; every later request asks the
 * identity providers to rebuild the identity from that name alone — a
 * {@link TrustedAuthenticationRequest}. Without this provider the sign-in
 * succeeds and the very next page bounces back to the login screen, which is
 * the classic symptom of a half-wired form setup.
 * <p>
 * The re-read is not a formality: it goes through
 * {@link EmployeeIdentityProvider#identityOf(String, String)} with no
 * password, so an employee deactivated or locked out AFTER signing in loses
 * the back office at their next request instead of keeping it until the
 * cookie expires.
 */
@ApplicationScoped
public class EmployeeTrustedIdentityProvider implements IdentityProvider<TrustedAuthenticationRequest> {

    /** The shared employee lookup, password check excepted. */
    @Inject
    EmployeeIdentityProvider employeeIdentityProvider;

    /**
     * Declares the request shape this provider answers: the session cookie
     * restore.
     *
     * @return the trusted request type
     */
    @Override
    public Class<TrustedAuthenticationRequest> getRequestType() {
        return TrustedAuthenticationRequest.class;
    }

    /**
     * Rebuilds the identity carried by the session cookie.
     *
     * @param request the trusted request holding the principal name
     * @param context the authentication context, used to leave the event loop
     * @return the identity of the employee, or a failure
     */
    @Override
    public Uni<SecurityIdentity> authenticate(TrustedAuthenticationRequest request,
                                              AuthenticationRequestContext context) {
        String principal = request.getPrincipal();
        return context.runBlocking(() -> inRequestContext(principal));
    }

    /**
     * Resolves the principal with an active CDI request context, activating
     * one for the call when the caller has none.
     *
     * @param principal the login name carried by the cookie
     * @return the identity of the employee
     */
    private SecurityIdentity inRequestContext(String principal) {
        ManagedContext requestContext = Arc.container().requestContext();
        if (requestContext.isActive()) {
            return employeeIdentityProvider.identityOf(principal, null);
        }
        requestContext.activate();
        try {
            return employeeIdentityProvider.identityOf(principal, null);
        } finally {
            requestContext.terminate();
        }
    }
}
