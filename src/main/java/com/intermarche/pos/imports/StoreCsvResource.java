package com.intermarche.pos.imports;

import com.intermarche.pos.domain.store.Address;
import com.intermarche.pos.domain.store.Store;
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
import java.util.*;

/**
 * REST Endpoint for bulk importing or updating Stores from a CSV file stream.
 * <p>
 * This class extends {@link ImporterCsvResource} to handle specific logic for {@link Store} entities.
 * It manages the embedded {@link Address} object and leverages the base class for the staged transaction management (1000 -> 100 -> 10 -> 1).
 * <p>
 * Consumed columns (resolved by header name; unknown columns of the
 * shared feed are ignored): CODE (key), NAME, STREET_LINE1, STREET_LINE2,
 * POSTAL_CODE, CITY, COUNTRY, and optionally LATITUDE and LONGITUDE
 * (absent column or empty cell = no coordinate).
 * <p>
 * The store's IDENTITY columns are optional too and were added with the invoice:
 * LEGAL_NAME, RCS, SHARE_CAPITAL, SIRET, VAT_NUMBER, PHONE, FAX and
 * BANK_ACCOUNT_NUMBER. A receipt can
 * be anonymous, an invoice cannot — its seller's block and its legal footer state
 * who is liable — and until now none of them could be fed at all, so a store row
 * carried them only if someone typed them into the database. They are optional so
 * that a feed written before they existed still imports unchanged.
 * <p>
 * Place in the POS architecture since the phase 6 centralized referentials:
 * these CSV endpoints exist on every node, but their proper home is the
 * STORE node — an import performed on a register is transient (the next
 * fingerprint pull overwrites or deactivates it), while an import on the
 * store node changes the domain fingerprint by construction and propagates
 * to every register within {@code pos.referential.pull-seconds}, with no
 * bump hook needed anywhere in this hierarchy.
 * <p>
 * Store itself sits OUTSIDE the centralized referential pull (one static
 * row per node): unlike the other imports, a store import propagates
 * nowhere and each node's row is maintained on that node.
 */
@Path("/stores/import")
@ApplicationScoped
@RunOnVirtualThread
public class StoreCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(StoreCsvResource.class);

    /** Header name of the natural key: the store code. */
    static final String COL_CODE = "CODE";
    /** Header name of the store name. */
    static final String COL_NAME = "NAME";
    /** Header name of the first address line. */
    static final String COL_STREET_LINE1 = "STREET_LINE1";
    /** Header name of the second address line. */
    static final String COL_STREET_LINE2 = "STREET_LINE2";
    /** Header name of the postal code. */
    static final String COL_POSTAL_CODE = "POSTAL_CODE";
    /** Header name of the city. */
    static final String COL_CITY = "CITY";
    /** Header name of the country. */
    static final String COL_COUNTRY = "COUNTRY";
    /** Header name of the optional latitude. */
    static final String COL_LATITUDE = "LATITUDE";
    /** Header name of the optional longitude. */
    static final String COL_LONGITUDE = "LONGITUDE";
    /** Header name of the optional legal entity operating the store. */
    static final String COL_LEGAL_NAME = "LEGAL_NAME";
    /** Header name of the optional trade and companies register entry. */
    static final String COL_RCS = "RCS";
    /** Header name of the optional share capital. */
    static final String COL_SHARE_CAPITAL = "SHARE_CAPITAL";
    /** Header name of the optional SIRET. */
    static final String COL_SIRET = "SIRET";
    /** Header name of the optional intra-community VAT number. */
    static final String COL_VAT_NUMBER = "VAT_NUMBER";
    /** Header name of the optional telephone number. */
    static final String COL_PHONE = "PHONE";
    /** Header name of the optional fax number. */
    static final String COL_FAX = "FAX";
    /**
     * Header name of the optional cheque account number.
     * <p>
     * Fed here although no document prints it yet, because {@link Store#getChecksum()}
     * hashes it: a column the feed cannot write is a field the incoming checksum
     * cannot know, and the comparison would report every line as changed forever.
     */
    static final String COL_BANK_ACCOUNT_NUMBER = "BANK_ACCOUNT_NUMBER";

    /** The columns this importer cannot work without (coordinates optional). */
    private static final List<String> REQUIRED_COLUMNS = List.of(
            COL_NAME, COL_STREET_LINE1, COL_STREET_LINE2,
            COL_POSTAL_CODE, COL_CITY, COL_COUNTRY);

    /**
     * Imports or updates stores from a CSV stream.
     * Delegates stream reading and chunking to the abstract base class.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importStores(InputStream inputStream) {
        LOGGER.info("Entering method importStores with inputStream: " + inputStream);
        LOGGER.info("Exiting method importStores");
        return this.importCsvStream(inputStream, COL_CODE, REQUIRED_COLUMNS);
    }

    /**
     * Names the engine feed captured by this importer: the raw file is
     * retained verbatim for the valuation engines (single import line).
     *
     * @return the STORES feed code
     */
    @Override
    protected String feedCode() {
        return "STORES";
    }

    /**
     * Implements the chunk processing logic for Stores.
     * <p>
     * <b>Phase 1 (Specific):</b>
     * Bulk fetches existing Stores based on the target codes.
     * <p>
     * <b>Phase 2 (Generic):</b>
     * Delegates to {@link ImporterCsvResource#processWithStages}.
     *
     * @param parsedLines The list of data for the current chunk.
     * @param targetCodes The set of unique Store codes in this chunk.
     * @param counters    An array of size 2 to hold [createdCount, updatedCount].
     * @param errors      List to collect definitive error messages.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        if (parsedLines.isEmpty()) return new HashMap<>();
        // 1. Bulk Fetch Existing Stores
        List<Store> existingStores = Store.list("code IN ?1", targetCodes);
        // Build the entity map (Code -> Store) for the generic algorithm
        Map<String, Object> storeMap = new HashMap<>();
        for (Store s : existingStores) {
            storeMap.put(s.code, s);
        }
        return storeMap;
    }

    /**
     * Implements the specific logic for creating or updating a Store entity.
     * <p>
     * This method is called by the generic staging algorithm for each line.
     * It retrieves the Store from the provided map.
     * If the store is new, it initializes the embedded {@link Address}.
     *
     * @param data       The parsed CSV line data.
     * @param entityMap  The map of existing stores (Key: Code, Value: Store).
     * @param counters   An array of size 2 to hold [createdCount, updatedCount].
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        // Retrieve Store
        Store store = (Store) entityMap.get(data.code);
        if (store == null) {
            // Create new
            store = new Store();
            store.code = data.code;
            feedStore(data, store);
            counters[0]++;
            Panache.getEntityManager().persist(store);
        } else {
            store = Store.findById(store.id);
            // Update existing
            int incomingChecksum = computeIncomingChecksum(data);
            if (store.checksum != incomingChecksum) {
                feedStore(data, store);
                counters[1]++;
            }
        }
    }

    /**
     * Implements the specific logic to find a fresh Store from the database.
     * <p>
     * Used by the generic 1-by-1 fallback.
     *
     * @param data The parsed CSV line data.
     * @return The Store entity or null if not found.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return Store.find("code", data.code).firstResult();
    }

    /**
     * Populates a Store entity (and its embedded Address) with data from the parsed CSV line.
     *
     * @param data  The parsed CSV line data.
     * @param store The Store entity to populate.
     */
    private void feedStore(LineData data, Store store) {
        store.name = safeGet(data, COL_NAME);
        // Ensure Address object exists
        if (store.address == null) {
            store.address = new Address();
        }
        store.address.streetLine1 = safeGet(data, COL_STREET_LINE1);
        store.address.streetLine2 = safeGet(data, COL_STREET_LINE2);
        store.address.postalCode = safeGet(data, COL_POSTAL_CODE);
        store.address.city = safeGet(data, COL_CITY);
        store.address.country = safeGet(data, COL_COUNTRY);
        store.address.latitude = safeParseDouble(data, COL_LATITUDE);
        store.address.longitude = safeParseDouble(data, COL_LONGITUDE);
        store.legalName = safeGet(data, COL_LEGAL_NAME);
        store.rcs = safeGet(data, COL_RCS);
        store.shareCapital = safeParseBigDecimal(data, COL_SHARE_CAPITAL);
        store.siret = safeGet(data, COL_SIRET);
        store.vatNumber = safeGet(data, COL_VAT_NUMBER);
        store.phone = safeGet(data, COL_PHONE);
        store.fax = safeGet(data, COL_FAX);
        store.bankAccountNumber = safeGet(data, COL_BANK_ACCOUNT_NUMBER);
    }

    /**
     * Computes the checksum the incoming CSV line WOULD have once stored.
     * <p>
     * Built by feeding a throw-away {@link Store} and asking it, rather than by
     * replaying the hash by hand. The hand-written replica this replaces had already
     * drifted: it hashed the code, the name and the address while
     * {@link Store#getChecksum()} also hashed the VAT number, the SIRET, the phone
     * and the bank account, so the two never matched and every line counted as
     * changed. A copy of a hash is a copy that goes stale on the next field added —
     * this one cannot.
     *
     * @param data The parsed CSV line data.
     * @return The integer hash the stored row would carry.
     */
    private int computeIncomingChecksum(LineData data) {
        Store candidate = new Store();
        candidate.code = data.code;
        feedStore(data, candidate);
        return candidate.getChecksum();
    }

}