package com.intermarche.pos.imports;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
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
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * REST Endpoint for bulk importing or updating Prices from a CSV file stream.
 * <p>
 * This class extends {@link ImporterCsvResource} to handle specific logic for {@link Price} entities.
 * It manages the composite key matching (EAN + StartDateTime + Priority) and
 * leverages the base class for the staged transaction management
 * (1000 -> 100 -> 10 -> 1).
 * <p>
 * Consumed columns (resolved by header name; unknown columns of the
 * shared feed are ignored): EAN (key), PRICE_EXCL_TAX, PRICE_INCL_TAX,
 * VAT_RATE, PRIORITY, START_DATE, END_DATE — plus the OPTIONAL
 * PRICE_USAGE discriminator of the union feed: the engine distinguishes
 * DEFAULT selling prices from discount bases (BASE_FOR_DISCOUNT…) that
 * share the same composite key, and the register only sells with DEFAULT
 * ones — when the feed declares the column, every non-DEFAULT row is
 * SKIPPED here (silently: those rows are for the engine, not for us).
 * Without the column, every row is a selling price (historical files).
 * <p>
 * Place in the POS architecture since the phase 6 centralized referentials:
 * these CSV endpoints exist on every node, but their proper home is the
 * STORE node — an import performed on a register is transient (the next
 * fingerprint pull overwrites or deactivates it), while an import on the
 * store node changes the domain fingerprint by construction and propagates
 * to every register within {@code pos.referential.pull-seconds}, with no
 * bump hook needed anywhere in this hierarchy.
 */
@Path("/prices/import")
@ApplicationScoped
@RunOnVirtualThread
public class PriceCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(PriceCsvResource.class);
    private static final String CTX_PRODUCTS = "__CTX_PRODUCTS__";
    private static final String CTX_PRICES = "__CTX_PRICES__";

    /** Header name of the natural key: the product EAN. */
    static final String COL_EAN = "EAN";
    /** Header name of the tax-exclusive price. */
    static final String COL_PRICE_EXCL_TAX = "PRICE_EXCL_TAX";
    /** Header name of the tax-inclusive price. */
    static final String COL_PRICE_INCL_TAX = "PRICE_INCL_TAX";
    /** Header name of the VAT rate. */
    static final String COL_VAT_RATE = "VAT_RATE";
    /** Header name of the price priority (composite-key part). */
    static final String COL_PRIORITY = "PRIORITY";
    /** Header name of the validity start (composite-key part). */
    static final String COL_START_DATE = "START_DATE";
    /** Header name of the validity end. */
    static final String COL_END_DATE = "END_DATE";
    /** Header name of the OPTIONAL engine-side price usage discriminator. */
    static final String COL_PRICE_USAGE = "PRICE_USAGE";
    /** The only price usage the register sells with. */
    static final String USAGE_DEFAULT = "DEFAULT";

    /** The columns this importer cannot work without. */
    private static final List<String> REQUIRED_COLUMNS = List.of(
            COL_PRICE_EXCL_TAX, COL_PRICE_INCL_TAX, COL_VAT_RATE,
            COL_PRIORITY, COL_START_DATE, COL_END_DATE);

    /**
     * Imports or updates prices from a CSV stream.
     * Delegates stream reading and chunking to the the abstract base class.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importPrices(InputStream inputStream) {
        LOGGER.info("Entering method importPrices with inputStream: " + inputStream);
        LOGGER.info("Exiting method importPrices");
        return this.importCsvStream(inputStream, COL_EAN, REQUIRED_COLUMNS);
    }

    /**
     * Names the engine feed captured by this importer: the raw file is
     * retained verbatim for the valuation engines (single import line).
     *
     * @return the PRICES feed code
     */
    @Override
    protected String feedCode() {
        return "PRICES";
    }

    /**
     * Implements the chunk processing logic for Prices.
     * <p>
     * <b>Phase 1 (Specific):</b>
     * Performs bulk fetching of Products, and Prices based on data in the current chunk.
     * Builds a "Context Map" containing all three maps to be passed to the generic algorithm.
     * <p>
     * <b>Phase 2 (Generic):</b>
     * Delegates to {@link ImporterCsvResource#processWithStages}.
     *
     * @param parsedLines The list of data for the current chunk.
     * @param targetCodes The set of unique EAN codes in this chunk (Column 0).
     * @param counters    An array of size 2 to hold [createdCount, updatedCount].
     * @param errors      List to collect definitive error messages.
     * @return A Map containing all entities (Products, Prices) needed for processing.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        if (parsedLines.isEmpty()) return new HashMap<>();
        // 1. Create Context Map
        Map<String, Object> contextMap = new HashMap<>();
        // Bulk Fetch Products
        Map<String, Product> productMap = getProductMap(targetCodes);
        contextMap.put(CTX_PRODUCTS, productMap);
        // Bulk Fetch Existing Prices (Composite Key: EAN|Start|Priority)
        Map<String, Price> priceMap = getPriceMap(targetCodes);
        contextMap.put(CTX_PRICES, priceMap);
        // 5. Return Context Map to trigger Generic Staged Fallback
        return contextMap;
    }

    /**
     * Retrieves a map of Prices based on Product.
     * <p>
     * Performs a broad query to fetch all prices matching the products in the current chunk.
     * Builds a map indexed by a composite key ("ean:usage:start:priority") for fast lookup.
     *
     * @param targetCodes      The set of product EANs.
     * @return A map of composite keys to Price entities.
     */
    private Map<String, Price> getPriceMap(Set<String> targetCodes) {
        // 3. Bulk Fetch Existing Prices
        // We use a broad query first, then build a map with a composite key
        List<Price> existingPrices = Price.list("product.ean IN ?1 ", targetCodes);
        Map<String, Price> priceMap = new HashMap<>();
        for (Price p : existingPrices) {
            String key = buildPriceKey(p.product.ean, p.startDateTime, p.priority);
            priceMap.put(key, p);
        }
        return priceMap;
    }

    /**
     * Retrieves a map of Products based on a set of EAN codes.
     *
     * @param targetCodes The set of product EANs to search for.
     * @return A map of EAN to Product entity.
     */
    private static Map<String, Product> getProductMap(Set<String> targetCodes) {
        // 2. Bulk Fetch Dependencies (Products & Stores)
        Map<String, Product> productMap = new HashMap<>();
        if (!targetCodes.isEmpty()) {
            List<Product> products = Product.list("ean IN ?1", targetCodes);
            for (Product p : products) {
                productMap.put(p.ean, p);
            }
        }
        return productMap;
    }

    /**
     * Implements the specific logic for creating or updating a Price entity.
     * <p>
     * This method is called by the generic staging algorithm for each line.
     * It retrieves the Product, Store, and Price maps from the context.
     * If maps are missing (1-by-1 fallback mode), it performs individual fetches.
     *
     * @param data       The parsed CSV line data.
     * @param entityMap  The context map containing Products, Stores, and Prices.
     * @param counters   An array of size 2 to hold [createdCount, updatedCount].
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        // Union-feed discriminator: rows carrying a non-DEFAULT usage are
        // discount bases meant for the engine — not selling prices — and
        // share the register's composite key with the DEFAULT row; keeping
        // them out is what keeps the register's price the SELLING price.
        if (data.has(COL_PRICE_USAGE)) {
            String usage = data.get(COL_PRICE_USAGE);
            if (usage != null && !usage.isEmpty() && !USAGE_DEFAULT.equals(usage)) {
                return;
            }
        }
        // 1. Retrieve or Fetch Dependencies
        @SuppressWarnings("unchecked")
        Map<String, Product> productMap = (Map<String, Product>) entityMap.get(CTX_PRODUCTS);
        Product product = getProduct(data, productMap);
        Map<String, Price> priceMap = retrievePrices(data, entityMap);
        // 3. Process Price Logic
        processPriceLogic(data, counters, priceMap, product);
    }

    /**
     * Core logic to create or update a Price entity.
     * <p>
     * It checks if a price with the same composite key (EAN + StartDateTime + Priority) already exists.
     * If it exists and the data has changed (checksum), it updates the fields. Otherwise, it creates a new one.
     *
     * @param data         The parsed CSV line data.
     * @param counters    An array of size 2 to hold [createdCount, updatedCount].
     * @param priceMap     The map of existing prices (indexed by composite key).
     * @param product      The Product entity associated with this price.
     */
    private void processPriceLogic(LineData data, int[] counters, Map<String, Price> priceMap, Product product) {
        LocalDateTime start = safeParseDateTime(data, COL_START_DATE);
        Integer priority = safeParseInt(data, COL_PRIORITY);
        String key = buildPriceKey(data.code, start, priority);
        Price price = priceMap.get(key);

        boolean isNew = (price == null);
        if (isNew) {
            price = new Price();
            price.product = product;
            feedPrice(data, price);
            counters[0]++;
            Panache.getEntityManager().persist(price);
        } else {
            // Checksum Optimization
            int incomingChecksum = computeIncomingChecksum(data, product);
            if (price.checksum != incomingChecksum) {
                price = Price.findById(price.id);
                product = Product.findById(product.id);
                price.product = product;
                feedPrice(data, price);
                counters[1]++;
            }
        }
    }

    /**
     * Retrieves the Product entity for the current line.
     * <p>
     * Looks up the product in the provided map. If the map is null (1-by-1 fallback mode), it fetches the product from the database.
     *
     * @param data       The parsed CSV line data.
     * @param productMap The map of products (can be null).
     * @return The Product entity.
     * @throws IllegalArgumentException if the product is not found.
     */
    private static Product getProduct(LineData data, Map<String, Product> productMap) {
        if (productMap == null) {
            productMap = new HashMap<>();
            Product p = Product.findByEan(data.code);
            if (p != null) productMap.put(p.ean, p);
        }
        Product product = productMap.get(data.code);
        if (product == null) {
            throw new IllegalArgumentException("Product with EAN " + data.code + " not found.");
        }
        return product;
    }

    /**
     * Retrieves the Price map for the current line.
     * <p>
     * Returns the map from the context. If the map is missing (1-by-1 fallback mode), it performs a database lookup
     * using the composite key.
     *
     * @param data      The parsed CSV line data.
     * @param entityMap The context map.
     * @param storeCode The store code (extracted from the line).
     * @return A map of composite keys to Price entities.
     */
    Map<String, Price> retrievePrices(LineData data, Map<String, Object> entityMap) {
        @SuppressWarnings("unchecked")
        Map<String, Price> priceMap = (Map<String, Price>) entityMap.get(CTX_PRICES);
        if (priceMap == null) {
            // 1-by-1 fallback: look for the specific price in DB
            LocalDateTime start = safeParseDateTime(data, COL_START_DATE);
            Integer priority = safeParseInt(data, COL_PRIORITY);
            Price existing = Price.find(
                    "product.ean = ?1 and priceUsage = ?2 and startDateTime = ?3 and priority = ?4",
                    data.code, start, priority
            ).firstResult();

            priceMap = new HashMap<>();
            if (existing != null) {
                String key = buildPriceKey(data.code, start, priority);
                priceMap.put(key, existing);
            }
        }
        return priceMap;
    }

    /**
     * Implements the specific logic to find a fresh Price from the database.
     * <p>
     * Used by the generic 1-by-1 fallback.
     * Note: Since Price uses a composite key, this method must perform a full lookup based on the CSV columns.
     *
     * @param data The parsed CSV line data.
     * @return The Price entity or null if not found.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        LocalDateTime start = safeParseDateTime(data, COL_START_DATE);
        Integer priority = safeParseInt(data, COL_PRIORITY);


        return Price.find(
                "product.ean = ?1 and startDateTime = ?2 and priority = ?3",
                data.code, start, priority
        ).firstResult();
    }

    /**
     * Builds a consistent composite key for map lookup.
     * <p>
     * The key combines Product EAN, Store Code, Price Usage, Start Date, and Priority.
     * Null values are represented by the string "NULL".
     *
     * @param ean       The Product EAN.
     * @param start     The Start Date Time.
     * @param priority  The Priority integer.
     * @return A string representing the composite key.
     */
    private String buildPriceKey(String ean, LocalDateTime start, Integer priority) {
        String startStr = start != null ? start.toString() : "NULL";
        String prioStr = priority != null ? priority.toString() : "NULL";
        return ean + ":" + startStr + ":" + prioStr;
    }

    /**
     * Populates a Price entity with data from the parsed CSV line.
     *
     * @param data  The parsed CSV line data.
     * @param price The Price entity to populate.
     */
    private void feedPrice(LineData data, Price price) {
        price.priceExcludingTax = safeParseBigDecimal(data, COL_PRICE_EXCL_TAX);
        price.priceIncludingTax = safeParseBigDecimal(data, COL_PRICE_INCL_TAX);
        price.vatRate = safeParseBigDecimal(data, COL_VAT_RATE);
        price.priority = safeParseInt(data, COL_PRIORITY);
        price.startDateTime = safeParseDateTime(data, COL_START_DATE);
        price.endDateTime = safeParseDateTime(data, COL_END_DATE);
    }

    /**
     * Computes the checksum for incoming CSV data.
     * <p>
     * Replicates {@link Price#getChecksum()} logic.
     *
     * @param data    The parsed CSV line data.
     * @param product The Product entity (to access ean).
     * @return The integer hash of incoming data.
     */
    private int computeIncomingChecksum(LineData data, Product product) {
        return Objects.hash(
                product.ean,
                safeParseBigDecimal(data, COL_PRICE_EXCL_TAX),
                safeParseBigDecimal(data, COL_PRICE_INCL_TAX),
                safeParseBigDecimal(data, COL_VAT_RATE),
                safeParseInt(data, COL_PRIORITY),
                safeParseDateTime(data, COL_START_DATE),
                safeParseDateTime(data, COL_END_DATE)
        );
    }

}