package com.intermarche.edge.imports;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.intermarche.edge.service.sync.EngineFeedService;
import java.util.Map;
import java.util.function.Function;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

    /** The referential fingerprints a relayed article file is stamped with (BO-02-03-33). */
    @Inject
    com.intermarche.edge.service.sync.store.RefExportService refExportService;

    /** The feeds the registers apply from the file itself, hence stamped. */
    static final List<String> STAMPED_FEEDS = List.of("PRODUCTS", "FAMILIES", "PRICES");

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

    /** The dedicated point-of-sale importer (PDVS delegation, BO-02-05-03). */
    @Inject
    PdvCsvResource pdvCsvResource;

    /** The dedicated recalled-lots importer (RECALL_LOTS delegation, BO-02-03-44). */
    @Inject
    RecallLotCsvResource recallLotCsvResource;

    /** Records every deposit and replays the failed ones (BO-08-04-01 to -13). */
    @Inject
    com.intermarche.edge.service.sync.IntegrationRetryService integrationRetryService;

    /**
     * Imports one feed under the unified grammar: a code owned by a
     * dedicated importer is delegated to it (its own response shape —
     * created/updated counts — comes back), any other catalog code is
     * stored verbatim for the engine.
     *
     * <p>Every deposit of a known code is RECORDED with its outcome
     * (BO-08-01-17, BO-08-04-01 to -13): a file that failed or left rejected
     * lines keeps its content, and the integration replay puts it in later,
     * automatically or from the integration console. The file is therefore
     * read once, here, and handed to the delegate as a fresh stream.
     *
     * @param code the feed code (case-insensitive)
     * @param mode the customer import mode
     * @param scheme the nomenclature scheme
     * @param label the nomenclature scheme label
     * @param enseigne the nomenclature enseigne
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
        if (!delegates(mode, scheme, label, enseigne).containsKey(normalized)
                && EngineFeedService.catalogEntry(normalized).isEmpty()) {
            String known = String.join(", ",
                    delegates(mode, scheme, label, enseigne).keySet().stream().sorted().toList())
                    + ", " + EngineFeedService.CATALOG.stream()
                    .map(EngineFeedService.FeedDef::code)
                    .filter(c -> !delegates(mode, scheme, label, enseigne).containsKey(c))
                    .collect(Collectors.joining(", "));
            LOGGER.info("Exiting method importFeed");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ImporterCsvResource.errorAnswer("Unknown feed code '" + normalized
                            + "' - known codes: " + known))
                    .build();
        }
        String content;
        try {
            content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.info("Exiting method importFeed");
            return Response.serverError()
                    .entity(ImporterCsvResource.errorAnswer("Error reading file: " + e.getMessage())).build();
        }
        Response response = integrate(normalized, mode, scheme, label, enseigne, content);
        integrationRetryService.record(normalized, mode, scheme, label, enseigne, content, response);
        LOGGER.info("Exiting method importFeed");
        return response;
    }

    /**
     * Renders the fingerprint of an empty referential as an empty string, the
     * mark of a file integrated over nothing: a register that never applied
     * the domain starts from there too.
     *
     * @param fingerprint the fingerprint
     * @return the fingerprint, or empty for the empty referential
     */
    private String emptyAsBlank(String fingerprint) {
        return fingerprint.equals(refExportService.fingerprintOf(List.of())) ? "" : fingerprint;
    }

    /**
     * Integrates one file of a known code: the deposit itself, and every
     * replay of a kept deposit (BO-08-04-01 to -13). The automatic replay
     * runs on a plain scheduler thread, without the HTTP request's context:
     * the importers read Panache entities outside their chunk transactions,
     * so the request context is activated here when none is active.
     *
     * @param code the normalized feed code
     * @param mode the customer import mode
     * @param scheme the nomenclature scheme
     * @param label the nomenclature scheme label
     * @param enseigne the nomenclature enseigne
     * @param content the file
     * @return the delegated importer's response, or 200 with the stored
     *         version, or 400 on an empty body
     */
    @ActivateRequestContext
    public Response integrate(String code, String mode, String scheme, String label, String enseigne,
                              String content) {
        Function<InputStream, Response> delegate = delegates(mode, scheme, label, enseigne).get(code);
        if (delegate != null) {
            // BO-02-03-33: a file the registers apply as such is stamped with the
            // state it was integrated over and the state it left, so a register
            // that missed a file or a back-office change takes the pages instead.
            boolean stamped = STAMPED_FEEDS.contains(code);
            String base = stamped ? emptyAsBlank(refExportService.publishedFingerprint(code)) : null;
            Response answer = delegate.apply(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            if (stamped) {
                engineFeedService.stamp(code, base, refExportService.publishedFingerprint(code));
            }
            return answer;
        }
        if (content.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ImporterCsvResource.errorAnswer("Empty feed body"))
                    .build();
        }
        String version = engineFeedService.store(code, content);
        return Response.ok(JsonNodeFactory.instance.objectNode().put("code", code)
                .put("version", version).toString()).build();
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
                Map.entry("SAFE_RECEIPTS", stream -> safeReceiptCsvResource.importReceipts(stream, mode)),
                Map.entry("PDVS", pdvCsvResource::importPdvs),
                Map.entry("NOMENCLATURE", stream -> nomenclatureCsvResource
                        .importNomenclature(scheme, label, enseigne, stream)),
                Map.entry("RECALL_LOTS", stream -> recallLotCsvResource.importRecallLots(stream, mode)));
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
        ArrayNode states = JsonNodeFactory.instance.arrayNode();
        for (EngineFeedService.FeedDef def : EngineFeedService.CATALOG) {
            com.intermarche.edge.domain.sync.EngineFeed feed =
                    com.intermarche.edge.domain.sync.EngineFeed.findByCode(def.code());
            if (feed == null) continue;
            states.addObject().put("code", feed.code)
                    .put("version", feed.version)
                    .put("appliedVersion", feed.appliedVersion)
                    .put("applied", feed.version.equals(feed.appliedVersion))
                    .put("lastError", feed.lastError);
        }
        LOGGER.info("Exiting method status");
        return Response.ok(states.toString()).build();
    }
}
