package com.intermarche.edge.ui.admin;

import com.intermarche.edge.service.sync.store.RefExportService;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReferentialChangeFilter} (BO-03-12-01): only an
 * accepted back-office POST moves the change stamp — every leg of the guard.
 */
class ReferentialChangeFilterTest {

    /**
     * Runs the filter on one request.
     *
     * @param method the HTTP method
     * @param path the request path, possibly null
     * @param status the response status
     * @return the exporter the filter talked to
     */
    private RefExportService run(String method, String path, int status) {
        ReferentialChangeFilter filter = new ReferentialChangeFilter();
        filter.refExportService = mock(RefExportService.class);
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(request.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn(path);
        when(request.getMethod()).thenReturn(method);
        ContainerResponseContext response = mock(ContainerResponseContext.class);
        when(response.getStatus()).thenReturn(status);
        filter.filter(request, response);
        return filter.refExportService;
    }

    /** An accepted back-office post, with or without a leading slash, moves the stamp. */
    @Test
    void anAcceptedAdminPostMovesTheStamp() {
        verify(run("POST", "/admin/settings", 303), times(1)).referentialChanged();
        verify(run("post", "admin/register-config/save", 200), times(1)).referentialChanged();
    }

    /** A read, a null path, another surface and a refused post move nothing. */
    @Test
    void anythingElseMovesNothing() {
        verify(run("GET", "/admin/settings", 200), never()).referentialChanged();
        verify(run("POST", null, 303), never()).referentialChanged();
        verify(run("POST", "/api/sync/tickets", 200), never()).referentialChanged();
        verify(run("POST", "/admin/settings", 500), never()).referentialChanged();
    }

    /** A post programmed for a later date (BO-03-12-03) leaves the stamp to its date. */
    @Test
    void aProgrammedPostMovesNothing() {
        ReferentialChangeFilter filter = new ReferentialChangeFilter();
        filter.refExportService = mock(RefExportService.class);
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(request.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn("/admin/articles/save");
        when(request.getMethod()).thenReturn("POST");
        when(request.getProperty(ReferentialReleaseFilter.CAPTURE)).thenReturn(new Object());
        ContainerResponseContext response = mock(ContainerResponseContext.class);
        when(response.getStatus()).thenReturn(303);
        filter.filter(request, response);
        verify(filter.refExportService, never()).referentialChanged();
    }
}
