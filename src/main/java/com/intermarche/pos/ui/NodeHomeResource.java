package com.intermarche.pos.ui;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;

/**
 * What the ROOT of the store node answers ({@code /}).
 * <p>
 * On a register the root is the sale screen; this node sells nothing, so its
 * root had nothing behind it and answered 404. An operator typing the address
 * of their shop's node — {@code http://poste-magasin:8070} — was told the page
 * did not exist and had to know to add {@code /admin}, which is exactly the
 * kind of thing nobody remembers between two visits.
 * <p>
 * The redirect goes to {@code /admin} and not straight to a screen, because
 * that is where the per-role landing is already decided: an administrator ends
 * on the parameters, a manager on the journal, and someone not signed in is
 * taken to the login form by the security layer. Naming a screen here would
 * fork that decision in two places.
 * <p>
 * A class of its own, and not a method added to the back office's own
 * {@code @Path("/")} resource, for ONE reason: the back office is kept
 * file-identical between imedge and impos so a change carries across by a
 * plain file copy. Putting the node's root inside one of those shared files
 * would make that copy break the register, where {@code /} is the sale screen.
 * Here it sits in a file impos does not have, so every shared file stays
 * copyable as it is.
 */
@Path("/")
public class NodeHomeResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(NodeHomeResource.class);

    /** Where the root sends the operator: the back office's own landing. */
    static final String LANDING = "/admin";

    /**
     * Sends the root to the back office.
     *
     * @return a 303 redirect to the back office landing
     */
    @GET
    public Response root() {
        LOGGER.info("Entering method root");
        LOGGER.info("Exiting method root");
        return Response.seeOther(URI.create(LANDING)).build();
    }
}
