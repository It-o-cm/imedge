package com.intermarche.pos.security;

import com.intermarche.pos.domain.people.Employee;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Authenticates a back-office request against the {@link Employee} table.
 * <p>
 * Written by hand rather than obtained from {@code quarkus-security-jpa},
 * for a structural reason: that extension's {@code @Roles} requires a
 * PERSISTED STRING column, whereas the role of an employee is the
 * {@link Employee.EmployeeRole} enum. Annotating the entity would have meant
 * adding a second, redundant column, teaching the referential pull to carry
 * it, and keeping the two in step forever. This provider reads the enum
 * directly, and gains three properties on the way:
 * <ul>
 *   <li>the password is verified by
 *       {@link Employee#verifyBackOfficePassword(String)}, so the back
 *       office makes NO assumption about the hash format;</li>
 *   <li>{@code active} is honoured: a deactivated employee cannot sign in,
 *       which is how the suite retires an account (deletion is impossible,
 *       historical documents reference it);</li>
 *   <li>the PIN lockout is honoured, ONE WAY — an account locked at the
 *       till is refused here too, because an account under suspicion is
 *       under suspicion everywhere; but a wrong back-office password never
 *       feeds that counter, so nobody can lock a cashier out of their till
 *       by hammering the login form. A lockout of the back office itself is
 *       a gap, and a known one (BO-01-01-01).</li>
 * </ul>
 * <p>
 * Credentials are the login name plus the BACK-OFFICE PASSWORD — never the
 * register PIN. The two secrets are distinct on purpose: the PIN is a
 * four-digit gesture made on a touch keypad in front of customers and opens
 * the till only; the back-office password is a conventional one, travels
 * over HTTP and opens the administration and the machine surfaces. An
 * employee with no back-office password has no back office, which is the
 * normal state of a cashier and needs no extra flag to enforce.
 * <p>
 * One staff file, two doors: that is what makes {@code @RolesAllowed} on the
 * CSV imports and on the GraphQL mutations meaningful without inventing a
 * second set of accounts.
 * <p>
 * Mechanics: authentication runs BEFORE the JAX-RS request scope exists, so
 * the lookup activates a CDI request context when none is active and opens
 * its own transaction ({@code QuarkusTransaction.requiringNew}) — the same
 * precaution the outbox drain needs for the same reason.
 */
@ApplicationScoped
public class EmployeeIdentityProvider implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    private static final Logger LOGGER = Logger.getLogger(EmployeeIdentityProvider.class);

    /**
     * Declares the request shape this provider answers: the login form's
     * post and the HTTP Basic header both produce this one.
     *
     * @return the username/password request type
     */
    @Override
    public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
        return UsernamePasswordAuthenticationRequest.class;
    }

    /**
     * Authenticates a login name and its PIN.
     *
     * @param request the presented credentials
     * @param context the authentication context, used to leave the event loop
     * @return the identity of the employee, or a failure
     */
    @Override
    public Uni<SecurityIdentity> authenticate(UsernamePasswordAuthenticationRequest request,
                                              AuthenticationRequestContext context) {
        String loginName = request.getUsername();
        String password = request.getPassword() == null
                ? null
                : new String(request.getPassword().getPassword());
        return context.runBlocking(() -> inRequestContext(() -> identityOf(loginName, password)));
    }

    /**
     * Resolves the identity of an employee, checking the password when one
     * is presented.
     * <p>
     * Called with a null password by {@link EmployeeTrustedIdentityProvider}
     * when the form session cookie is restored: at that point the identity
     * was already proven, only the account's current state has to be
     * re-checked — which is what makes a deactivation or a lockout take
     * effect on an already open session.
     *
     * @param loginName the presented login name
     * @param password the presented password, or null to skip the password
     *                 check
     * @return the identity of the employee
     * @throws AuthenticationFailedException when the account is unknown,
     *         inactive, locked, without back-office access, or the password
     *         is wrong
     */
    SecurityIdentity identityOf(String loginName, String password) {
        if (loginName == null || loginName.isBlank()) {
            throw new AuthenticationFailedException();
        }
        Employee employee = QuarkusTransaction.requiringNew()
                .call(() -> Employee.find("loginName", loginName).firstResult());
        if (employee == null) {
            LOGGER.debugf("Back-office sign-in refused: unknown login name '%s'", loginName);
            throw new AuthenticationFailedException();
        }
        if (!employee.isEnabled()) {
            LOGGER.debugf("Back-office sign-in refused: account '%s' is deactivated", loginName);
            throw new AuthenticationFailedException();
        }
        if (employee.isCurrentlyLocked()) {
            LOGGER.debugf("Back-office sign-in refused: account '%s' is locked out", loginName);
            throw new AuthenticationFailedException();
        }
        if (!employee.hasBackOfficeAccess()) {
            LOGGER.debugf("Back-office sign-in refused: '%s' has no back-office password", loginName);
            throw new AuthenticationFailedException();
        }
        if (password != null && !employee.verifyBackOfficePassword(password)) {
            LOGGER.debugf("Back-office sign-in refused: wrong password for '%s'", loginName);
            throw new AuthenticationFailedException();
        }
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(employee.loginName))
                .addRoles(grantedRoles(employee))
                .addAttribute("fullName", employee.getFullName())
                .addAttribute("mustChangePassword", employee.mustChangePassword)
                .build();
    }

    /**
     * Expands the employee's single role into the set actually granted.
     * <p>
     * ADMIN implies MANAGER. This is not decoration: the annotations that
     * already guard the GraphQL surface read the catalog under MANAGER and
     * write it under ADMIN, so without the implication the administrator
     * could create a product but not list one. The register's own
     * endorsement check keeps reading {@code Employee.role} directly and is
     * untouched by this — the implication exists only in the HTTP identity.
     *
     * @param employee the authenticated employee
     * @return the roles granted to the request
     */
    private Set<String> grantedRoles(Employee employee) {
        Set<String> roles = new LinkedHashSet<>(employee.getRoles());
        if (roles.contains(Employee.EmployeeRole.ADMIN.name())) {
            roles.add(Employee.EmployeeRole.MANAGER.name());
        }
        return roles;
    }

    /**
     * Runs the lookup with an active CDI request context, activating one for
     * the call when the caller has none.
     * <p>
     * Authentication happens before the JAX-RS request scope is set up, and
     * Panache needs the context to reach its entity manager; without this
     * guard the first sign-in fails with a context-not-active error.
     *
     * @param supplier the lookup to run
     * @return whatever the lookup returned
     */
    private SecurityIdentity inRequestContext(Supplier<SecurityIdentity> supplier) {
        ManagedContext requestContext = Arc.container().requestContext();
        if (requestContext.isActive()) {
            return supplier.get();
        }
        requestContext.activate();
        try {
            return supplier.get();
        } finally {
            requestContext.terminate();
        }
    }
}
