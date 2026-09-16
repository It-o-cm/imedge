package com.intermarche.pos.imports;

import com.intermarche.pos.service.sync.EngineFeedService;
import java.util.Map;
import java.util.function.Function;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * SINGLE front door of every CSV feed: {@code POST /feeds/import/{code}}
 * accepts all codes with one grammar, and routes each to its rightful
 * treatment.
 * <ul>
 *   <li>Codes the POS consumes itself (STORES, PRODUCTS, FAMILIES, PRICES,
 *       EMPLOYEES) are DELEGATED to their dedicated import resource: the
 *       file is applied to the POS referential, and the resource captures
 *       the verbatim copy for the engine when relevant — exactly as if the
 *       historical route ({@code /products/import}…) had been called. Those
 *       historical routes remain in place, strictly equivalent.</li>
 *   <li>Engine-specific codes (OFFERS, STORE_GROUPS, CATEGORY_STORAGES) are
 *       stored VERBATIM without being opened — the "sealed parcel" leg of
 *       the single-import-line doctrine; parsing belongs to imvaluation,
 *       which ingests the shared header-named format itself.</li>
 * </ul>
 */
@Path("/feeds/import")
@ApplicationScoped
@RunOnVirtualThread
public class EngineFeedRelayResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EngineFeedRelayResource.class);

    /** The feed keeper storing the sealed parcels. */
    @Inject
    EngineFeedService engineFeedService;

    /** The dedicated store importer (STORES delegation). */
    @Inject
    StoreCsvResource storeCsvResource;

    /** The dedicated product importer (PRODUCTS delegation). */
    @Inject
    ProductCsvResource productCsvResource;

    /** The dedicated family importer (FAMILIES delegation). */
    @Inject
    ProductFamilyCsvResource productFamilyCsvResource;

    /** The dedicated price importer (PRICES delegation). */
    @Inject
    PriceCsvResource priceCsvResource;

    /** The dedicated employee importer (EMPLOYEES delegation). */
    @Inject
    EmployeeCsvResource employeeCsvResource;

    /**
     * Imports one feed under the unified grammar: a code owned by a
     * dedicated importer is delegated to it (its own response shape —
     * created/updated counts — comes back), any other catalog code is
     * stored verbatim for the engine.
     *
     * @param code the feed code (case-insensitive)
     * @param inputStream the raw CSV stream
     * @return the delegated importer's response, or 200 with the stored
     *         version, or 400 on an unknown code or an empty body
     */
    @POST
    @Path("/{code}")
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importFeed(@PathParam("code") String code, InputStream inputStream) {
        LOGGER.info("Entering method importFeed with code: " + code + ", inputStream: " + inputStream);
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        Function<InputStream, Response> delegate = delegates().get(normalized);
        if (delegate != null) {
            LOGGER.info("Exiting method importFeed");
            return delegate.apply(inputStream);
        }
        if (EngineFeedService.catalogEntry(normalized).isEmpty()) {
            String known = String.join(", ", delegates().keySet().stream().sorted().toList())
                    + ", " + EngineFeedService.CATALOG.stream()
                    .map(EngineFeedService.FeedDef::code)
                    .filter(c -> !delegates().containsKey(c))
                    .collect(Collectors.joining(", "));
            LOGGER.info("Exiting method importFeed");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Unknown feed code '" + normalized
                            + "' - known codes: " + known + "\"}")
                    .build();
        }
        String content;
        try {
            content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.info("Exiting method importFeed");
            return Response.serverError().entity("Error reading file: " + e.getMessage()).build();
        }
        if (content.isBlank()) {
            LOGGER.info("Exiting method importFeed");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Empty feed body\"}")
                    .build();
        }
        String version = engineFeedService.store(normalized, content);
        LOGGER.info("Exiting method importFeed");
        return Response.ok("{\"code\":\"" + normalized + "\", \"version\":\"" + version + "\"}").build();
    }

    /**
     * The codes owned by a dedicated importer, each mapped to its import
     * entry — the delegation table of the unified grammar. Built per call:
     * five entries, no state worth caching against injection timing.
     *
     * @return the code-to-importer delegation map
     */
    private Map<String, Function<InputStream, Response>> delegates() {
        return Map.of(
                "STORES", storeCsvResource::importStores,
                "PRODUCTS", productCsvResource::importProducts,
                "FAMILIES", productFamilyCsvResource::importProductFamilies,
                "PRICES", priceCsvResource::importPrices,
                "EMPLOYEES", employeeCsvResource::importEmployees);
    }

    /**
     * Returns the engine sync state of every stored feed, in catalog
     * order: the stored version, the version the local engine last
     * acknowledged, whether the two match, and the last delivery error.
     * This is what supervision (and the demo stack) polls to know when the
     * engine is up to date.
     *
     * @return a JSON array of per-feed states
     */
    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response status() {
        LOGGER.info("Entering method status");
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (EngineFeedService.FeedDef def : EngineFeedService.CATALOG) {
            com.intermarche.pos.domain.sync.EngineFeed feed =
                    com.intermarche.pos.domain.sync.EngineFeed.findByCode(def.code());
            if (feed == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"code\":\"").append(feed.code)
                    .append("\",\"version\":\"").append(feed.version)
                    .append("\",\"appliedVersion\":").append(
                            feed.appliedVersion == null ? "null" : "\"" + feed.appliedVersion + "\"")
                    .append(",\"applied\":").append(feed.version.equals(feed.appliedVersion))
                    .append(",\"lastError\":").append(
                            feed.lastError == null ? "null"
                                    : "\"" + feed.lastError.replace("\\", "\\\\").replace("\"", "'") + "\"")
                    .append('}');
        }
        LOGGER.info("Exiting method status");
        return Response.ok(sb.append(']').toString()).build();
    }
}
