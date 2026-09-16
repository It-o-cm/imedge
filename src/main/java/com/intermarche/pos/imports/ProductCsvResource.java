package com.intermarche.pos.imports;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductType;
import com.intermarche.pos.domain.catalog.ProductFamily;
import io.quarkus.hibernate.orm.panache.Panache;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * REST Endpoint for bulk importing or updating Products from a CSV file stream.
 * <p>
 * This specific implementation extends {@link ImporterCsvResource} to handle
 * {@link Product} entities. It names the columns it consumes from the
 * shared feed (header-driven, see the COL_* dictionary) and the business
 * logic for creating/updating products.
 * <p>
 * It leverages the parent's Staged Fallback algorithm (1000 -> 100 -> 10 -> 1).
 * <p>
 * Place in the POS architecture since the phase 6 centralized referentials:
 * these CSV endpoints exist on every node, but their proper home is the
 * STORE node — an import performed on a register is transient (the next
 * fingerprint pull overwrites or deactivates it), while an import on the
 * store node changes the domain fingerprint by construction and propagates
 * to every register within {@code pos.referential.pull-seconds}, with no
 * bump hook needed anywhere in this hierarchy.
 * <p>
 * The register-only columns of the union feed (PLU, ICON,
 * FORBIDDEN_TO_SALE) are OPTIONAL: when the header declares one, its value
 * applies (an empty cell clears the field); when the feed does not carry
 * it, the local value is left alone — so a feed built before the union
 * extension keeps its historical behavior. The engine ignores these
 * columns entirely.
 */
@Path("/products/import")
@ApplicationScoped
@RunOnVirtualThread
public class ProductCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(ProductCsvResource.class);

    /** Header name of the natural key: the product EAN. */
    static final String COL_EAN = "EAN";
    /** Header name of the product label. */
    static final String COL_NAME = "NAME";
    /** Header name of the product description. */
    static final String COL_DESCRIPTION = "DESCRIPTION";
    /** Header name of the brand. */
    static final String COL_BRAND = "BRAND";
    /** Header name of the reference weight. */
    static final String COL_REFERENCE_WEIGHT = "REFERENCE_WEIGHT";
    /** Header name of the reference volume. */
    static final String COL_REFERENCE_VOLUME = "REFERENCE_VOLUME";
    /** Header name of the product type enum. */
    static final String COL_PRODUCT_TYPE = "PRODUCT_TYPE";
    /** Header name of the unit label. */
    static final String COL_UNIT_NAME = "UNIT_NAME";
    /** Header name of the active flag. */
    static final String COL_ACTIVE = "ACTIVE";
    /** Header name of the OPTIONAL register-only PLU code. */
    static final String COL_PLU = "PLU";

    /** Header name of the OPTIONAL variable-weight (bulk) marker. */
    static final String COL_VARIABLE_WEIGHT = "VARIABLE_WEIGHT";
    /** Header name of the OPTIONAL register-only display icon. */
    static final String COL_ICON = "ICON";
    /** Header name of the OPTIONAL forbidden-to-sale flag. */
    static final String COL_FORBIDDEN_TO_SALE = "FORBIDDEN_TO_SALE";
    /** Header name of the OPTIONAL checkout label (libellé encaissement, BO-02-03-02). */
    static final String COL_CHECKOUT_LABEL = "CHECKOUT_LABEL";
    /** Header name of the OPTIONAL internal code (code interne, BO-02-03-04). */
    static final String COL_INTERNAL_CODE = "INTERNAL_CODE";
    /**
     * Header name of the OPTIONAL generic attributes column (BO-02-03-18): a
     * single cell of {@code code=value} pairs separated by semicolons, e.g.
     * {@code MEAL_VOUCHER_ELIGIBLE=true;DISCOUNT_FORBIDDEN=true}. It exposes the
     * whole open {@link Product#attributes} map to the shared feed — the
     * well-known behavioural attributes (BO-02-03-06/09/11/21/22/25) and any
     * Gestion-Commerciale code alike — through ONE column rather than one per
     * attribute, mirroring the open-map design. The pipe field delimiter means
     * {@code ;} and {@code =} are safe inside the cell.
     */
    static final String COL_ATTRIBUTES = "ATTRIBUTES";

    /**
     * Header of the column naming the article's place in the NOMENCLATURE
     * (BO-02-03-14): the code of the node it is filed under, usually the
     * sous-famille. Optional — a feed that does not carry it leaves the
     * article's filing untouched.
     */
    static final String COL_NOMENCLATURE = "NOMENCLATURE";

    /** The columns this importer cannot work without. */
    private static final List<String> REQUIRED_COLUMNS = List.of(
            COL_NAME, COL_DESCRIPTION, COL_BRAND, COL_REFERENCE_WEIGHT,
            COL_REFERENCE_VOLUME, COL_PRODUCT_TYPE, COL_UNIT_NAME, COL_ACTIVE);

    /**
     * Imports or updates products from a CSV stream.
     * <p>
     * Delegates the stream reading and chunking to the abstract base class.
     * <p>
     * Consumed columns (resolved by header name; unknown columns of the
     * shared feed are ignored): EAN (key), NAME, DESCRIPTION, BRAND,
     * REFERENCE_WEIGHT, REFERENCE_VOLUME, PRODUCT_TYPE, UNIT_NAME, ACTIVE.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importProducts(InputStream inputStream) {
        LOGGER.info("Entering method importProducts with inputStream: " + inputStream);
        LOGGER.info("Exiting method importProducts");
        return this.importCsvStream(inputStream, COL_EAN, REQUIRED_COLUMNS);
    }

    /**
     * Names the engine feed captured by this importer: the raw file is
     * retained verbatim for the valuation engines (single import line).
     *
     * @return the PRODUCTS feed code
     */
    @Override
    protected String feedCode() {
        return "PRODUCTS";
    }

    /**
     * Implements the chunk processing logic for Products.
     * <p>
     * <b>Phase 1 (Specific):</b> Bulk fetches existing Products for the current chunk.
     * <b>Phase 2 (Generic):</b> Delegates to {@link ImporterCsvResource#processWithStages}
     * to handle the transactional staging logic.
     *
     * @param parsedLines The list of data for the current chunk.
     * @param targetEans  The set of unique EAN codes in this chunk.
     * @param counters    An array of size 2 to hold [createdCount, updatedCount].
     * @param errors      List to collect definitive error messages.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetEans, int[] counters, List<String> errors) {
        if (parsedLines.isEmpty()) return new HashMap<>();
        // SPECIFIC: Bulk Fetch existing products
        Map<String, Object> contextMap = new HashMap<>();
        if (!targetEans.isEmpty()) {
            List<Product> existingProducts = Product.list("ean IN ?1", targetEans);
            for (Product p : existingProducts) {
                contextMap.put(p.ean, p);
            }
        }
        return contextMap;
    }

    /**
     * Implements the specific logic for creating or updating a Product entity.
     * <p>
     * This method is called by the generic staging algorithm for each line.
     * It uses the provided entityMap (which may contain pre-fetched or fresh entities).
     *
     * @param data       The parsed CSV line data.
     * @param entityMap  A map of existing entities (Key: EAN, Value: Product).
     * @param counters   An array of size 2 to hold [createdCount, updatedCount].
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        // Retrieve product from map (cast from Object)
        Product product = (Product) entityMap.get(data.code);
        if (product == null) {
            // Create new
            product = new Product();
            product.ean = data.code;
            feedProduct(data, product);
            counters[0]++; // Created
            Panache.getEntityManager().persist(product);
        } else {
            // Update existing if data changed (Checksum Optimization)
            int incomingChecksum = computeIncomingChecksum(data, product);
            if (product.checksum != incomingChecksum) {
                product = Product.findById(product.id);
                feedProduct(data, product);
                counters[1]++; // Updated
            }
        }
        // OUTSIDE the checksum shortcut, deliberately: the filing lives on the
        // family side, so it is not part of the article's checksum, and an
        // article moved from one sous-famille to another would otherwise be
        // skipped as unchanged and stay filed where it was.
        if (data.has(COL_NOMENCLATURE)) {
            ProductFamily.fileUnderNomenclature(product, safeGet(data, COL_NOMENCLATURE));
        }
    }

    /**
     * Implements the specific logic to find a fresh Product from the database.
     * <p>
     * Used by the generic 1-by-1 fallback to ensure data freshness.
     *
     * @param data The parsed CSV line data.
     * @return The Product entity or null if not found.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return Product.find("ean", data.code).firstResult();
    }

    // --------------------------------------------------
    // Specific Helpers for Product
    // --------------------------------------------------

    /**
     * Populates a Product entity with data from the parsed CSV line.
     *
     * @param data    The parsed CSV line data.
     * @param product The Product entity to populate.
     */
    private void feedProduct(LineData data, Product product) {
        product.name = data.get(COL_NAME);
        product.description = safeGet(data, COL_DESCRIPTION);
        product.brand = safeGet(data, COL_BRAND);
        product.referenceWeight = safeParseBigDecimal(data, COL_REFERENCE_WEIGHT);
        product.referenceVolume = safeParseBigDecimal(data, COL_REFERENCE_VOLUME);
        product.productType = safeParseProductType(data, COL_PRODUCT_TYPE);
        product.unitName = safeGet(data, COL_UNIT_NAME);
        product.active = safeParseBoolean(data, COL_ACTIVE);
        if (data.has(COL_PLU)) {
            String plu = safeGet(data, COL_PLU);
            // A blank PLU cell must land as NULL, never "": the plu column is
            // unique, and empty strings are equal values in a unique index, so
            // a feed holding two products without a PLU would break the import
            // on the second one. NULLs never collide in a unique index, and
            // every register-side read treats a null PLU as "no PLU".
            product.plu = (plu == null || plu.isEmpty()) ? null : plu;
        }
        if (data.has(COL_ICON)) {
            product.icon = safeGet(data, COL_ICON);
        }
        if (data.has(COL_FORBIDDEN_TO_SALE)) {
            product.forbiddenToSale = safeParseBoolean(data, COL_FORBIDDEN_TO_SALE);
        }
        if (data.has(COL_CHECKOUT_LABEL)) {
            product.checkoutLabel = safeGet(data, COL_CHECKOUT_LABEL);
        }
        if (data.has(COL_INTERNAL_CODE)) {
            product.internalCode = safeGet(data, COL_INTERNAL_CODE);
        }
        if (data.has(COL_VARIABLE_WEIGHT)) {
            product.variableWeight = safeParseBoolean(data, COL_VARIABLE_WEIGHT);
        }
        if (data.has(COL_ATTRIBUTES)) {
            mergeAttributes(product.attributes, safeGet(data, COL_ATTRIBUTES));
        }
    }

    /**
     * Merges the {@code code=value} pairs of the generic ATTRIBUTES cell into a
     * product's attribute map (BO-02-03-18). Pairs are separated by semicolons
     * and each pair by the first {@code =}; a blank cell (declared but empty)
     * merges nothing, so it never wipes attributes set through the fiche — a map
     * column clears no key, it only poses the ones it names. A token without an
     * {@code =} or with a blank code is skipped. Any code is accepted: the map
     * is open by design, only the subset the register acts upon is declared in
     * the catalog.
     *
     * @param target the product's attribute map (mutated in place)
     * @param raw the raw ATTRIBUTES cell value, or null
     */
    private void mergeAttributes(Map<String, String> target, String raw) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        for (String pair : raw.split(";")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String code = pair.substring(0, eq).trim();
            if (code.isEmpty()) {
                continue;
            }
            target.put(code, pair.substring(eq + 1).trim());
        }
    }

    /**
     * Computes the attribute map a product WOULD carry after {@code feedProduct}
     * for the change-detection checksum: a copy of its current attributes with
     * the ATTRIBUTES cell merged in when the feed declares the column, the
     * current attributes untouched when it does not (mirroring the touch rule of
     * every other optional column).
     *
     * @param data the parsed CSV line
     * @param existing the product the row would update
     * @return the incoming attribute map (a fresh copy, never the live map)
     */
    private Map<String, String> incomingAttributes(LineData data, Product existing) {
        Map<String, String> attributes = new HashMap<>(
                existing.attributes == null ? Map.of() : existing.attributes);
        if (data.has(COL_ATTRIBUTES)) {
            mergeAttributes(attributes, safeGet(data, COL_ATTRIBUTES));
        }
        return attributes;
    }

    /**
     * Computes the checksum the existing product WOULD have after
     * {@code feedProduct}: same field list and null conventions as
     * {@link Product#getChecksum()}, with the optional register-only
     * columns taken from the CSV when the feed declares them and from the
     * existing row when it does not (mirroring the touch rules). The icon
     * is deliberately absent — the entity checksum ignores it — so an
     * icon-only change rides along with the next real change.
     *
     * @param data The parsed CSV line data.
     * @param existing The product the row would update.
     * @return The integer hash of the incoming state.
     */
    private int computeIncomingChecksum(LineData data, Product existing) {
        String plu = data.has(COL_PLU) ? safeGet(data, COL_PLU) : existing.plu;
        boolean forbidden = data.has(COL_FORBIDDEN_TO_SALE)
                ? safeParseBoolean(data, COL_FORBIDDEN_TO_SALE) : existing.forbiddenToSale;
        String checkoutLabel = data.has(COL_CHECKOUT_LABEL)
                ? safeGet(data, COL_CHECKOUT_LABEL) : existing.checkoutLabel;
        String internalCode = data.has(COL_INTERNAL_CODE)
                ? safeGet(data, COL_INTERNAL_CODE) : existing.internalCode;
        boolean variableWeight = data.has(COL_VARIABLE_WEIGHT)
                ? safeParseBoolean(data, COL_VARIABLE_WEIGHT) : existing.variableWeight;
        return Objects.hash(
                data.code,                                      // ean
                plu == null ? "" : plu,                         // plu
                data.get(COL_NAME),                             // name
                safeGet(data, COL_DESCRIPTION),                 // description
                safeGet(data, COL_BRAND),                       // brand
                safeParseBigDecimal(data, COL_REFERENCE_WEIGHT),// referenceWeight
                safeParseBigDecimal(data, COL_REFERENCE_VOLUME),// referenceVolume
                safeParseProductType(data, COL_PRODUCT_TYPE),   // productType
                safeGet(data, COL_UNIT_NAME),                   // unitName
                safeParseBoolean(data, COL_ACTIVE),             // active
                forbidden,                                      // forbiddenToSale
                checkoutLabel,                                  // checkoutLabel
                internalCode,                                   // internalCode
                variableWeight,                                 // variableWeight
                incomingAttributes(data, existing)              // attributes (BO-02-03-18)
        );
    }

    /**
     * Safely parses a ProductType Enum from a column resolved by name.
     *
     * @param data The parsed CSV line.
     * @param column The header name of the column.
     * @return The ProductType value, or null on any missing/invalid input.
     */
    ProductType safeParseProductType(LineData data, String column) {
        String val = data.get(column);
        if (val == null || val.isEmpty()) return null;
        try {
            // Assuming enum constants are stored as strings (e.g., "UNIT", "WEIGHT")
            return ProductType.valueOf(val.toUpperCase());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Unknown ProductType value: " + val + " in column " + column);
            return null;
        }
    }
}