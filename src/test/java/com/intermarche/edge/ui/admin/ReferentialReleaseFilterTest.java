package com.intermarche.edge.ui.admin;

import com.intermarche.edge.service.sync.store.ReferentialReleaseService;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReferentialReleaseFilter} (BO-03-12-03): an armed
 * administrator's post is captured before and recorded after; everything else
 * goes through untouched — every leg of each guard.
 */
class ReferentialReleaseFilterTest {

    /**
     * Builds a filter on a mocked engine.
     *
     * @param enabled whether the node is a store
     * @return the filter
     */
    private ReferentialReleaseFilter filter(boolean enabled) {
        ReferentialReleaseFilter filter = new ReferentialReleaseFilter();
        filter.releaseService = mock(ReferentialReleaseService.class);
        when(filter.releaseService.enabled()).thenReturn(enabled);
        return filter;
    }

    /**
     * Builds a request.
     *
     * @param method the HTTP method
     * @param path the path, possibly null
     * @param user the signed-in login, or null for none
     * @return the request
     */
    private ContainerRequestContext request(String method, String path, String user) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(request.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn(path);
        when(request.getMethod()).thenReturn(method);
        SecurityContext security = mock(SecurityContext.class);
        when(request.getSecurityContext()).thenReturn(security);
        if (user != null) {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn(user);
            when(security.getUserPrincipal()).thenReturn(principal);
        }
        return request;
    }

    /** Only a post under /admin, outside the ignored paths, on a store, is programmable. */
    @Test
    void onlyAStoreAdminPostIsProgrammable() {
        ReferentialReleaseFilter filter = filter(true);
        assertTrue(filter.programmable(request("POST", "/admin/articles/save", "admin")));
        assertTrue(filter.programmable(request("post", "admin/tenders/save", "admin")));
        assertFalse(filter.programmable(request("GET", "/admin/articles", "admin")));
        assertFalse(filter.programmable(request("POST", null, "admin")));
        assertFalse(filter.programmable(request("POST", "/api/sync/tickets", "admin")));
        assertFalse(filter.programmable(request("POST", "/admin/releases/arm", "admin")));
        assertFalse(filter.programmable(request("POST", "/admin/logout", "admin")));
        assertFalse(filter(false).programmable(request("POST", "/admin/articles/save", "admin")));
    }

    /** An armed administrator's post is captured before it is handled. */
    @Test
    void anArmedPostIsCaptured() {
        ReferentialReleaseFilter filter = filter(true);
        LocalDateTime at = LocalDateTime.now().plusDays(1);
        when(filter.releaseService.armedAt("admin")).thenReturn(at);
        ContainerRequestContext request = request("POST", "/admin/articles/save", "admin");
        filter.filter(request);
        verify(filter.releaseService).capture(at, "admin");
        verify(request).setProperty(ReferentialReleaseFilter.CAPTURE, null);
    }

    /** A post nobody armed, an anonymous one and a read are not captured. */
    @Test
    void anUnarmedPostIsNotCaptured() {
        ReferentialReleaseFilter filter = filter(true);
        ContainerRequestContext unarmed = request("POST", "/admin/articles/save", "jdupont");
        filter.filter(unarmed);
        ContainerRequestContext anonymous = request("POST", "/admin/articles/save", null);
        filter.filter(anonymous);
        ContainerRequestContext read = request("GET", "/admin/articles", "admin");
        filter.filter(read);
        verify(filter.releaseService, never()).capture(any(), anyString());
        verify(unarmed, never()).setProperty(anyString(), any());
        verify(anonymous, never()).setProperty(anyString(), any());
    }

    /** An accepted captured post is recorded; a refused one and an uncaptured one are not. */
    @Test
    void anAcceptedCapturedPostIsRecorded() {
        ReferentialReleaseFilter filter = filter(true);
        ReferentialReleaseService.Capture capture = mock(ReferentialReleaseService.Capture.class);
        ContainerRequestContext captured = request("POST", "/admin/articles/save", "admin");
        when(captured.getProperty(ReferentialReleaseFilter.CAPTURE)).thenReturn(capture);
        ContainerResponseContext accepted = mock(ContainerResponseContext.class);
        when(accepted.getStatus()).thenReturn(303);
        ContainerResponseContext refused = mock(ContainerResponseContext.class);
        when(refused.getStatus()).thenReturn(400);
        filter.filter(captured, refused);
        filter.filter(request("POST", "/admin/articles/save", "admin"), accepted);
        verify(filter.releaseService, never()).record(any(), anyString());
        filter.filter(captured, accepted);
        verify(filter.releaseService).record(capture, "/admin/articles/save");
    }
}
