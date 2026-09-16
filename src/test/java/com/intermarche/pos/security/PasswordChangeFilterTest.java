package com.intermarche.pos.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PasswordChangeFilter}.
 * <p>
 * Every leg of the guard is exercised separately, because each one is a
 * distinct way to let a request through, and letting the wrong one through
 * either locks a machine client out of the store node or lets an operator
 * keep a password that was handed to them.
 */
class PasswordChangeFilterTest {

    /** The filter under test. */
    private PasswordChangeFilter filter;

    /** The intercepted request. */
    private ContainerRequestContext request;

    /** Prepares a filter and a request on a guarded path with no header. */
    @BeforeEach
    void setUp() {
        filter = new PasswordChangeFilter();
        request = mock(ContainerRequestContext.class);
        onPath("/admin/settings");
        when(request.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn(null);
    }

    /**
     * Points the request at a path.
     *
     * @param path the path returned by the URI info
     */
    private void onPath(String path) {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn(path);
        when(request.getUriInfo()).thenReturn(uriInfo);
    }

    /**
     * Injects an identity carrying the forced-change attribute.
     *
     * @param mustChange the attribute value, possibly null
     */
    private void signedInWith(Boolean mustChange) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.<Boolean>getAttribute("mustChangePassword")).thenReturn(mustChange);
        filter.identity = identity;
    }

    /** No identity injected at all: nothing to divert. */
    @Test
    void passesWhenIdentityIsNull() throws Exception {
        filter.identity = null;
        filter.filter(request);
        verify(request, never()).abortWith(any());
    }

    /** An anonymous request is left to the authorization layer. */
    @Test
    void passesWhenAnonymous() throws Exception {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(true);
        filter.identity = identity;
        filter.filter(request);
        verify(request, never()).abortWith(any());
    }

    /** An identity without the attribute is not diverted. */
    @Test
    void passesWhenAttributeIsAbsent() throws Exception {
        signedInWith(null);
        filter.filter(request);
        verify(request, never()).abortWith(any());
    }

    /** An operator who already chose their password is not diverted. */
    @Test
    void passesWhenChangeIsNotRequired() throws Exception {
        signedInWith(false);
        filter.filter(request);
        verify(request, never()).abortWith(any());
    }

    /**
     * A request carrying an Authorization header is a machine client: it is
     * left alone even on a guarded path, or the store node's supervision
     * data becomes unreadable to its pollers.
     */
    @Test
    void passesWhenTheRequestCarriesBasicCredentials() throws Exception {
        signedInWith(true);
        onPath("/dashboard-data");
        when(request.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Basic bWFuYWdlcjp4");
        filter.filter(request);
        verify(request, never()).abortWith(any());
    }

    /** A path outside the back office is never diverted. */
    @Test
    void passesOnANonBackOfficePath() throws Exception {
        signedInWith(true);
        onPath("/products/import");
        filter.filter(request);
        verify(request, never()).abortWith(any());
    }

    /** The password screen itself must answer, or the redirect loops. */
    @Test
    void passesOnThePasswordScreen() throws Exception {
        signedInWith(true);
        onPath("/admin/password");
        filter.filter(request);
        verify(request, never()).abortWith(any());
    }

    /** Signing out stays possible during a pending change. */
    @Test
    void passesOnSignOut() throws Exception {
        signedInWith(true);
        onPath("/admin/logout");
        filter.filter(request);
        verify(request, never()).abortWith(any());
    }

    /** The sign-in page stays reachable during a pending change. */
    @Test
    void passesOnSignIn() throws Exception {
        signedInWith(true);
        onPath("/admin/login");
        filter.filter(request);
        verify(request, never()).abortWith(any());
    }

    /** A guarded back-office screen is diverted to the password page. */
    @Test
    void divertsAGuardedAdminScreen() throws Exception {
        signedInWith(true);
        filter.filter(request);
        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(request).abortWith(captor.capture());
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), captor.getValue().getStatus());
        assertEquals("/admin/password", captor.getValue().getLocation().toString());
    }

    /** The supervision screen is diverted too. */
    @Test
    void divertsTheSupervisionScreen() throws Exception {
        signedInWith(true);
        onPath("/dashboard");
        filter.filter(request);
        verify(request).abortWith(any());
    }

    /**
     * The URI info hands the path without its leading slash: the filter
     * normalizes it, otherwise every prefix test silently fails open.
     */
    @Test
    void normalizesAPathWithoutItsLeadingSlash() throws Exception {
        signedInWith(true);
        onPath("admin/settings");
        filter.filter(request);
        verify(request).abortWith(any());
    }
}
