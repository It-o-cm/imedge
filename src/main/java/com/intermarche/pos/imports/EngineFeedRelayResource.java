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
import jakarta.ws.rs.QueryParam;
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
 *   <li>Codes the node consumes itself (STORES, PRODUCTS, FAMILIES, VAT_RATES,
 *       EGALIM_REGIMES,
 *       PRICES, EMPLOYEES, CUSTOMERS, POSTAL_CODES, SAFE_RECEIPTS) are
 *       DELEGATED to their import bean: the file is applied to the node's
 *       referential, and the bean captures the verbatim copy for the engine
 *       when relevant. Those beans used to carry a route of their own
 *       ({@code /products/import}…); the routes are GONE, and this is the
 *       only address left. One door to secure, to document and to keep in
 *       step — the permission policy named them one by one, and an importer
 *       forgotten in that list was a door left ajar.</li>
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

    /** The dedicated VAT importer (VAT_RATES delegation). */
    @Inject
    VatRateCsvResource vatRateCsvResource;

    /** The dedicated EGAlim importer (EGALIM_REGIMES delegation). */
    @Inject
    EgalimRegimeCsvResource egalimRegimeCsvResource;

    /** The dedicated customer importer (CUSTOMERS delegation). */
    @Inject
    AccountCustomerCsvResource accountCustomerCsvResource;

    /** The dedicated postal-code importer (POSTAL_CODES delegation). */
    @Inject
    PostalCodeCsvResource postalCodeCsvResource;

    /** The dedicated safe-receipt importer (SAFE_RECEIPTS delegation). */
    @Inject
    SafeReceiptCsvResource safeReceiptCsvResource;

    /** The dedicated nomenclature importer (NOMENCLATURE delegation). */
    @Inject
    NomenclatureCsvResource nomenclatureCsvResource;

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
    public Response importFeed(@PathParam("code") String code,
                               @QueryParam("mode") String mode,
                               @QueryParam("scheme") String scheme,
                               @QueryParam("label") String label,
                               @QueryParam("enseigne") String enseigne,
                               InputStream inputStream) {
        LOGGER.info("Entering method importFeed with code: " + code + ", mode: " + mode
                + ", scheme: " + scheme);
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        Function<InputStream, Response> delegate =
                delegates(mode, scheme, label, enseigne).get(normalized);
        if (delegate != null) {
            LOGGER.info("Exiting method importFeed");
            return delegate.apply(inputStream);
        }
        if (EngineFeedService.catalogEntry(normalized).isEmpty()) {
            String known = String.join(", ",
                    delegates(mode, scheme, label, enseigne).keySet().stream().sorted().toList())
                    + ", " + EngineFeedService.CATALOG.stream()
                    .map(EngineFeedService.FeedDef::code)
                    .filter(c -> !delegates(mode, scheme, label, enseigne).containsKey(c))
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
     * nine entries, no state worth caching against injection timing.
     *
     * <p>EVERY importer of the node is here now. They used to carry a second
     * address of their own, {@code /products/import} and its kin, so the same
     * import answered to two spellings and the permission policy had to name
     * them one by one. One door, one vocabulary — the referential domain code,
     * the same token as the engine feed catalog and as the register's own
     * dispatch. The nomenclature stays out, and that is not an oversight: two
     * retailers publish the same columns and nothing in the file says which
     * scheme to load, so the scheme is named on the request.
     *
     * <p>Three feeds need more than their code, and the table carries what they
     * need rather than sending them to an address of their own: the customer
     * population is full or delta ({@code mode}), and a nomenclature must be
     * told which scheme it lands in ({@code scheme}, {@code label},
     * {@code enseigne}) because the published lines number nodes INSIDE a
     * scheme and never name the scheme. Four parameters no other code reads is
     * a visible blemish on one method; two front doors was an invisible one on
     * the whole node.
     *
     * <p>{@code Map.ofEntries} and not {@code Map.of}: the latter stops at ten
     * pairs, and the tenth is already here.
     *
     * @param mode the import mode, read by the customer feed alone
     * @param scheme the nomenclature scheme to load into
     * @param label the nomenclature scheme label, used on creation
     * @param enseigne the enseigne the nomenclature scheme classifies for
     * @return the code-to-importer delegation map
     */
    private Map<String, Function<InputStream, Response>> delegates(
            String mode, String scheme, String label, String enseigne) {
        return Map.ofEntries(
                Map.entry("STORES", storeCsvResource::importStores),
                Map.entry("PRODUCTS", productCsvResource::importProducts),
                Map.entry("FAMILIES", productFamilyCsvResource::importProductFamilies),
                Map.entry("VAT_RATES", vatRateCsvResource::importVatRates),
                Map.entry("EGALIM_REGIMES", egalimRegimeCsvResource::importEgalimRegimes),
                Map.entry("PRICES", priceCsvResource::importPrices),
                Map.entry("EMPLOYEES", employeeCsvResource::importEmployees),
                Map.entry("CUSTOMERS",
                        stream -> accountCustomerCsvResource.importCustomers(stream, mode)),
                Map.entry("POSTAL_CODES", postalCodeCsvResource::importPostalCodes),
                Map.entry("SAFE_RECEIPTS", safeReceiptCsvResource::importReceipts),
                Map.entry("NOMENCLATURE", stream -> nomenclatureCsvResource
                        .importNomenclature(scheme, label, enseigne, stream)));
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
