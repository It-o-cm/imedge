package com.intermarche.pos.security;

import com.intermarche.pos.domain.people.Employee;
import io.quarkus.qute.TemplateData;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Exposes the signed-in back-office identity to the Qute templates.
 * <p>
 * Templates reach it through {@code inject:currentUser}, which lets the
 * shared back-office layout show who is connected and adapt its navigation
 * without every resource passing the information down.
 * <p>
 * This only drives what the interface DISPLAYS. Access itself is enforced
 * server side by the {@code @RolesAllowed} annotations on the resources, so
 * hiding a link is a convenience, never a security measure.
 */
@Named("currentUser")
@RequestScoped
@TemplateData
public class CurrentUser {

    /** The identity resolved by Quarkus Security for the current request. */
    @Inject
    SecurityIdentity identity;

    /**
     * Returns the login name of the signed-in employee.
     *
     * @return the login name, or an empty string when unauthenticated
     */
    public String getName() {
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            return "";
        }
        return identity.getPrincipal().getName();
    }

    /**
     * Returns the display name of the signed-in employee.
     * <p>
     * Carried as an identity attribute by the provider, so rendering the
     * header costs no database read.
     *
     * @return the full name, falling back to the login name, empty when
     *         unauthenticated
     */
    public String getFullName() {
        if (!isAuthenticated()) {
            return "";
        }
        String fullName = identity.getAttribute("fullName");
        return fullName == null || fullName.isBlank() ? getName() : fullName;
    }

    /**
     * Indicates whether someone is signed in.
     *
     * @return true when the request carries an identity
     */
    public boolean isAuthenticated() {
        return !getName().isEmpty();
    }

    /**
     * Indicates whether the signed-in employee holds a given role.
     *
     * @param role the role to test
     * @return true when the role is granted
     */
    public boolean hasRole(String role) {
        return identity != null && identity.hasRole(role);
    }

    /**
     * Indicates whether the signed-in employee may administer the register
     * parameters.
     *
     * @return true when the administrator role is granted
     */
    public boolean isAdmin() {
        return hasRole(Employee.EmployeeRole.ADMIN.name());
    }
}
