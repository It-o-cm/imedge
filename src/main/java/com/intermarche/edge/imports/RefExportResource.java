package com.intermarche.edge.imports;

import com.intermarche.edge.service.sync.store.RefExportService;
import com.intermarche.edge.service.sync.RefPayloads;
import jakarta.inject.Inject;
import java.util.List;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Store-node referential endpoints pulled by the registers (phase 6 lot 3):
 * the per-domain fingerprints and the paged snapshots. Gated like the sync
 * ingestion: store role only, optional shared token.
 * <p>
 * Cost profile: {@code /versions} is the cheap poll (fingerprints cached
 * 60 seconds by {@code RefExportService}, so register polling never rescans
 * a table more than once per minute); the paged snapshot endpoint is the
 * expensive path, only hit when a fingerprint actually changed.
 */
@Path("/api/referential")
public class RefExportResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(RefExportResource.class);

    /** The role of this node: "register" (default), "store" or "central". */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** Shared token; absent = open. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    @Inject
    RefExportService refExportService;

    /**
     * Returns the fingerprint of every referential domain, as seen by the
     * register that asked.
     *
     * <p>{@code X-Pos-Terminal} NAMES the asking register so the node can
     * resolve its parameters by checkout point, then by island, then by point of
     * sale before answering (BO-03-09, BO-03-10). It is not an authentication:
     * it names, it proves nothing, and the role gate and the shared token remain
     * the only judges. Absent or blank, the answer is exactly what it was before
     * the header existed — the PDV resolution — so a register that does not send
     * it keeps working unchanged.
     *
     * @param presentedToken the shared token presented by the register
     * @param terminalId the asking register's identifier, or null
     * @param pdv the asking store's point of sale, or null (BO-01-01-03)
     * @return the domain-to-fingerprint map, or 401/403 when gated
     */
    @GET
    @Path("/versions")
    @Produces(MediaType.APPLICATION_JSON)
    public Response versions(@HeaderParam("X-Sync-Token") String presentedToken,
                             @HeaderParam("X-Pos-Terminal") String terminalId,
                             @HeaderParam("X-Pos-Pdv") String pdv) {
        LOGGER.info("Entering method versions with presentedToken: ***, terminalId: " + terminalId);
        Response gate = gate(presentedToken);
        if (gate != null) { LOGGER.info("Exiting method versions"); return gate; }
        List<String> domains = "central".equalsIgnoreCase(role)
                ? RefPayloads.ECHELON_DOMAINS
                : RefPayloads.DOMAINS;
        LOGGER.info("Exiting method versions");
        return Response.ok(refExportService.getFingerprints(domains, terminalId, pdv)).build();
    }

    /**
     * Returns the CHANGE STAMP of the referential (BO-03-12-01): a number that
     * moves on every back-office modification of this node, the cheapest
     * question a register can ask every few seconds to learn that it must pull
     * now rather than at its next cycle.
     *
     * @param presentedToken the shared token presented by the register
     * @return the stamp as text, or 401/403 when gated
     */
    @GET
    @Path("/stamp")
    @Produces(MediaType.TEXT_PLAIN)
    public Response stamp(@HeaderParam("X-Sync-Token") String presentedToken) {
        LOGGER.info("Entering method stamp");
        Response gate = gate(presentedToken);
        if (gate != null) {
            LOGGER.info("Exiting method stamp");
            return gate;
        }
        LOGGER.info("Exiting method stamp");
        return Response.ok(String.valueOf(refExportService.changeStamp())).build();
    }

    /**
     * Returns one page of a domain's snapshot, as seen by the register that
     * asked.
     *
     * <p>The terminal is passed down for the same reason as on {@code /versions}
     * and changes exactly one domain, SETTINGS; the fingerprint and the content
     * must be computed from the same identifier or a register would re-pull for
     * ever.
     *
     * @param presentedToken the shared token presented by the register
     * @param terminalId the asking register's identifier, or null
     * @param pdv the asking store's point of sale, or null (BO-01-01-03)
     * @param domain the referential domain
     * @param page the 0-based page index
     * @param size the page size (bounded to 5000)
     * @return the page payloads, or 400/401/403 when refused
     */
    @GET
    @Path("/{domain}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response page(@HeaderParam("X-Sync-Token") String presentedToken,
                         @HeaderParam("X-Pos-Terminal") String terminalId,
                         @HeaderParam("X-Pos-Pdv") String pdv,
                         @PathParam("domain") String domain,
                         @QueryParam("page") int page,
                         @QueryParam("size") int size) {
        LOGGER.info("Entering method page with presentedToken: ***" + ", terminalId: " + terminalId
                + ", domain: " + domain + ", page: " + page + ", size: " + size);
        Response gate = gate(presentedToken);
        if (gate != null) { LOGGER.info("Exiting method page"); return gate; }
        int boundedSize = (size <= 0 || size > 5000) ? 1000 : size;
        try {
            LOGGER.info("Exiting method page");
            return Response.ok(refExportService.getPage(domain.toUpperCase(),
                    Math.max(0, page), boundedSize, terminalId, pdv)).build();
        } catch (IllegalArgumentException e) {
            LOGGER.info("Exiting method page");
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * Applies the role and token gates.
     *
     * @param presentedToken the token presented by the register, or null
     * @return the refusal response, or null when allowed
     */
    private Response gate(String presentedToken) {
        if (!"store".equalsIgnoreCase(role) && !"central".equalsIgnoreCase(role)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("Ce nœud n'a pas le rôle store ou central").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("Jeton invalide").build();
        }
        return null;
    }
}
