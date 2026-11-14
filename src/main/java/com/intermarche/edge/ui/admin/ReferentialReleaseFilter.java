package com.intermarche.edge.ui.admin;

import com.intermarche.edge.service.sync.store.ReferentialReleaseService;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Gives a back-office change its DATE OF EFFECT (BO-03-12-03): when the
 * administrator who posts has armed a date on the programmed deployments
 * screen, the version the registers are exposed to is captured before his
 * form is handled, compared after, and every distributed domain the form
 * changed is held until that date.
 *
 * <p>No screen needs to know: whatever the form — an article, a price, a
 * tender, a template, an island, a parameter — the comparison of the
 * fingerprints tells which domains moved. An administrator who armed nothing
 * changes the registers immediately, as before; a refused post (an error
 * status) changed nothing and holds nothing.
 */
@Provider
public class ReferentialReleaseFilter implements ContainerRequestFilter, ContainerResponseFilter {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ReferentialReleaseFilter.class);

    /** The request property carrying the capture of a programmed post. */
    public static final String CAPTURE = ReferentialReleaseFilter.class.getName() + ".capture";

    /** The back-office paths that never change a distributed domain. */
    static final List<String> IGNORED = List.of("admin/releases", "admin/login", "admin/logout");

    /** The programmed deployment engine. */
    @Inject
    ReferentialReleaseService releaseService;

    /**
     * Captures the exposed version before an armed administrator's post.
     *
     * @param request the request
     */
    @Override
    public void filter(ContainerRequestContext request) {
        if (!programmable(request)) {
            return;
        }
        Principal principal = request.getSecurityContext().getUserPrincipal();
        String user = principal == null ? null : principal.getName();
        LocalDateTime at = releaseService.armedAt(user);
        if (at == null) {
            return;
        }
        LOGGER.info("Programmed back-office change by " + user + " for " + at);
        request.setProperty(CAPTURE, releaseService.capture(at, user));
    }

    /**
     * Holds the domains an accepted armed post changed.
     *
     * @param request the request
     * @param response the response
     */
    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        Object capture = request.getProperty(CAPTURE);
        if (capture == null || response.getStatus() >= 400) {
            return;
        }
        releaseService.record((ReferentialReleaseService.Capture) capture, request.getUriInfo().getPath());
    }

    /**
     * Tells whether a request is a back-office post that may change a distributed domain.
     *
     * @param request the request
     * @return true for a post under /admin, outside the ignored paths, on a store node
     */
    boolean programmable(ContainerRequestContext request) {
        String path = request.getUriInfo().getPath();
        if (!"POST".equalsIgnoreCase(request.getMethod()) || path == null) {
            return false;
        }
        String relative = path.replaceFirst("^/", "");
        if (!relative.startsWith("admin/")) {
            return false;
        }
        for (String ignored : IGNORED) {
            if (relative.startsWith(ignored)) {
                return false;
            }
        }
        return releaseService.enabled();
    }
}
