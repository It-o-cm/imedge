package com.intermarche.pos.security;

import com.intermarche.pos.domain.people.Employee;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CurrentUser}.
 * <p>
 * The bean is what the back-office layout reads, so every branch that can
 * make it render an empty header is covered: no identity at all, an
 * anonymous one, an identity whose principal is null, and the display-name
 * fallbacks.
 */
class CurrentUserTest {

    /** The bean under test. */
    private CurrentUser currentUser;

    /** Prepares a bean with no identity injected. */
    @BeforeEach
    void setUp() {
        currentUser = new CurrentUser();
    }

    /**
     * Builds a signed-in identity.
     *
     * @param name the principal name
     * @return the mocked identity
     */
    private SecurityIdentity signedIn(String name) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn(name);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(principal);
        return identity;
    }

    /** A null identity yields an empty name rather than an exception. */
    @Test
    void nameIsEmptyWithoutIdentity() {
        currentUser.identity = null;
        assertEquals("", currentUser.getName());
        assertFalse(currentUser.isAuthenticated());
    }

    /** An anonymous identity yields an empty name. */
    @Test
    void nameIsEmptyWhenAnonymous() {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(true);
        currentUser.identity = identity;
        assertEquals("", currentUser.getName());
        assertFalse(currentUser.isAuthenticated());
    }

    /** An identity carrying no principal yields an empty name. */
    @Test
    void nameIsEmptyWhenPrincipalIsNull() {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(null);
        currentUser.identity = identity;
        assertEquals("", currentUser.getName());
        assertFalse(currentUser.isAuthenticated());
    }

    /** A signed-in identity yields its principal name. */
    @Test
    void nameIsThePrincipalName() {
        currentUser.identity = signedIn("manager");
        assertEquals("manager", currentUser.getName());
        assertTrue(currentUser.isAuthenticated());
    }

    /** Without an identity the display name is empty too. */
    @Test
    void fullNameIsEmptyWhenUnauthenticated() {
        currentUser.identity = null;
        assertEquals("", currentUser.getFullName());
    }

    /** The display name comes from the identity attribute when present. */
    @Test
    void fullNameComesFromTheAttribute() {
        SecurityIdentity identity = signedIn("manager");
        when(identity.<String>getAttribute("fullName")).thenReturn("Le Manager");
        currentUser.identity = identity;
        assertEquals("Le Manager", currentUser.getFullName());
    }

    /** A missing attribute falls back to the login name. */
    @Test
    void fullNameFallsBackToTheLoginNameWhenAttributeIsNull() {
        SecurityIdentity identity = signedIn("manager");
        when(identity.<String>getAttribute("fullName")).thenReturn(null);
        currentUser.identity = identity;
        assertEquals("manager", currentUser.getFullName());
    }

    /** A blank attribute falls back to the login name as well. */
    @Test
    void fullNameFallsBackToTheLoginNameWhenAttributeIsBlank() {
        SecurityIdentity identity = signedIn("manager");
        when(identity.<String>getAttribute("fullName")).thenReturn("   ");
        currentUser.identity = identity;
        assertEquals("manager", currentUser.getFullName());
    }

    /** A null identity grants no role. */
    @Test
    void hasRoleIsFalseWithoutIdentity() {
        currentUser.identity = null;
        assertFalse(currentUser.hasRole(Employee.EmployeeRole.ADMIN.name()));
        assertFalse(currentUser.isAdmin());
    }

    /** A granted role is reported. */
    @Test
    void hasRoleReadsTheIdentity() {
        SecurityIdentity identity = signedIn("manager");
        when(identity.hasRole("MANAGER")).thenReturn(true);
        currentUser.identity = identity;
        assertTrue(currentUser.hasRole("MANAGER"));
    }

    /** The administrator flag reads the ADMIN role. */
    @Test
    void isAdminReadsTheAdminRole() {
        SecurityIdentity identity = signedIn("manager");
        when(identity.hasRole(Employee.EmployeeRole.ADMIN.name())).thenReturn(true);
        currentUser.identity = identity;
        assertTrue(currentUser.isAdmin());
    }

    /** A signed-in non-administrator is not an administrator. */
    @Test
    void isAdminIsFalseForAnotherRole() {
        SecurityIdentity identity = signedIn("mcurie");
        when(identity.hasRole(Employee.EmployeeRole.ADMIN.name())).thenReturn(false);
        currentUser.identity = identity;
        assertFalse(currentUser.isAdmin());
    }
}
