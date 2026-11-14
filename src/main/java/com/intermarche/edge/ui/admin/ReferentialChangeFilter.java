package com.intermarche.edge.ui.admin;

import com.intermarche.edge.service.sync.store.RefExportService;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Makes every back-office modification IMMEDIATELY available to the registers
 * (BO-03-12-01).
 *
 * <p>A register pulls its referential on a cycle of minutes; between two
 * cycles it watches a change stamp it can read every few seconds. Every
 * accepted form posted to the back office moves that stamp and drops the
 * cached fingerprints, so the next look of a register sees the change and
 * pulls at once. A refused post — an error status — changed nothing and moves
 * nothing. A post PROGRAMMED for a later date (BO-03-12-03) moves nothing
 * either: the stamp moves when its date comes.
 */
@Provider
public class ReferentialChangeFilter implements ContainerResponseFilter {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ReferentialChangeFilter.class);

    /** The exporter whose stamp moves. */
    @Inject
    RefExportService refExportService;

    /**
     * Moves the change stamp after an accepted back-office post.
     *
     * @param request the request
     * @param response the response
     */
    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        String path = request.getUriInfo().getPath();
        if (!"POST".equalsIgnoreCase(request.getMethod()) || path == null
                || !path.replaceFirst("^/", "").startsWith("admin/") || response.getStatus() >= 400) {
            return;
        }
        if (request.getProperty(ReferentialReleaseFilter.CAPTURE) != null) {
            LOGGER.info("Back-office modification on " + path + " programmed: the stamp moves at its date");
            return;
        }
        LOGGER.info("Back-office modification on " + path + ": referential change recorded");
        refExportService.referentialChanged();
    }
}
