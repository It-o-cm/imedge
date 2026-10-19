package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.DrawerMustBeClosed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;

/**
 * JAX-RS resource of the THIRD-PARTY application touches (BO-03-09-27): the
 * register's half of a touch that leads out of the register.
 *
 * <p>The applications themselves are administered — a wording and an address
 * per entry — and this resource is what a touch calls. It resolves the entry by
 * its administered RANK and redirects to its address, so no address ever
 * travels in a register URL: a touch posting the address itself would let
 * anything be opened by hand-crafting a link, which is precisely what a
 * referential of allowed applications is there to prevent.
 *
 * <p>ADDRESSES ONLY. The requirement says "URL ou exécutable"; a register that
 * runs an arbitrary binary because a referential row said so is a security
 * hole, so the executable half is deliberately not implemented.
 */
@Path("/external-app")
@DrawerMustBeClosed
public class ExternalAppResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ExternalAppResource.class);

    /** The administered applications (BO-03-09-27). */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * Opens the administered application of the given rank.
     *
     * @param index the 0-based rank of the application in administered order
     * @return a 303 redirect to the application, or a 404 when no application
     *         carries that rank
     */
    @GET
    @Path("/{index}")
    public Response open(@PathParam("index") int index) {
        LOGGER.info("Entering method open with index: " + index);
        List<PosSettingsService.ExternalApp> apps = posSettingsService.externalApps();
        if (index < 0 || index >= apps.size()) {
            // A touch pointing at an entry the back office has since removed:
            // nothing is opened, and the register says nothing — the touch is
            // simply gone with the row that described it.
            LOGGER.info("Exiting method open");
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        PosSettingsService.ExternalApp app = apps.get(index);
        LOGGER.info("Exiting method open");
        return Response.seeOther(URI.create(app.url())).build();
    }
}
