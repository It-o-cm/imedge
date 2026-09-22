package com.intermarche.pos.imports;

import com.intermarche.pos.domain.catalog.VatRate;
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
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * REST endpoint bulk importing the VAT referential from a CSV stream
 * (BO-02-03-14).
 * <p>
 * Consumed columns (resolved by header name; unknown columns of the shared
 * feed are ignored): VAT_NUMBER (key), RATE, LABEL.
 * <p>
 * The NUMBER is the natural key, not the rate: a legal rate change is a new
 * value on the same number, and every price row keeps pointing at that number
 * without being rewritten. The rate is read as a FRACTION — {@code 0.0550} for
 * five and a half percent — which is the form the whole suite computes with;
 * a file stating {@code 5.5} would declare a 550% regime, so the feed has to
 * be the fraction, like the VAT_RATE column of the price feed it replaces.
 * <p>
 * Import ORDER matters, as it already did for products before families: this
 * file goes in BEFORE the prices, because a price line names a regime that has
 * to exist for the link to be made.
 * <p>
 * Place in the POS architecture since the phase 6 centralized referentials:
 * these CSV endpoints exist on every node, but their proper home is the STORE
 * node — an import performed on a register is transient (the next fingerprint
 * pull overwrites it), while an import on the store node changes the domain
 * fingerprint by construction and propagates to every register within
 * {@code pos.referential.pull-seconds}.
 * <p>
 * This importer names NO engine feed: the valuation engines read the rate on
 * the price feed, so the VAT table is a POS referential and nothing is
 * retained for delivery here.
 */
// No @Path: the ONE door is POST /feeds/import/{CODE}, which delegates here.
// A second address for the same import is a second thing to secure, to
// document and to keep in step, and the permission policy had to name them
// one by one — an importer forgotten there was a door left open.
@ApplicationScoped
@RunOnVirtualThread
public class VatRateCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(VatRateCsvResource.class);

    /** Key used to store the regime map in the generic context map. */
    private static final String CTX_VAT_RATES = "__CTX_VAT_RATES__";

    /** Header name of the natural key: the regime number. */
    static final String COL_VAT_NUMBER = "VAT_NUMBER";
    /** Header name of the rate, as a fraction. */
    static final String COL_RATE = "RATE";
    /** Header name of the human-readable label. */
    static final String COL_LABEL = "LABEL";

    /** The columns this importer cannot work without. */
    private static final List<String> REQUIRED_COLUMNS = List.of(COL_RATE);

    /**
     * Imports or updates VAT regimes from a CSV stream.
     * Delegates stream reading and chunking to the abstract base class.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    public Response importVatRates(InputStream inputStream) {
        LOGGER.info("Entering method importVatRates with inputStream: " + inputStream);
        LOGGER.info("Exiting method importVatRates");
        return this.importCsvStream(inputStream, COL_VAT_NUMBER, REQUIRED_COLUMNS);
    }

    /**
     * Names the engine feed captured by this importer: the raw file is
     * retained verbatim for the valuation engines (single import line).
     *
     * <p>The VAT referential IS an engine feed, and leaving it out was a
     * mistake: the engine attaches every price to the regime carrying its
     * rate, so an engine with an empty regime table refuses the whole price
     * file and prices nothing.
     *
     * @return the VAT_RATES feed code
     */
    @Override
    protected String feedCode() {
        return "VAT_RATES";
    }

    /**
     * Bulk-fetches the regimes named by the current chunk.
     *
     * @param parsedLines The list of data for the current chunk.
     * @param targetCodes The set of unique regime numbers in this chunk.
     * @param counters    An array of size 2 to hold [createdCount, updatedCount].
     * @param errors      List to collect definitive error messages.
     * @return A Map holding the existing regimes, indexed by their number.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        if (parsedLines.isEmpty()) return new HashMap<>();
        Map<String, Object> contextMap = new HashMap<>();
        contextMap.put(CTX_VAT_RATES, vatRateMap(targetCodes));
        return contextMap;
    }

    /**
     * Loads the regimes carrying one of the given numbers.
     *
     * @param targetCodes The regime numbers of the chunk, as written in the file.
     * @return A map from the number as written to the regime entity.
     */
    private static Map<String, VatRate> vatRateMap(Set<String> targetCodes) {
        Map<String, VatRate> rateMap = new HashMap<>();
        if (targetCodes.isEmpty()) {
            return rateMap;
        }
        for (VatRate rate : VatRate.<VatRate>list("number IN ?1", numbers(targetCodes))) {
            rateMap.put(String.valueOf(rate.number), rate);
        }
        return rateMap;
    }

    /**
     * Converts the keys of a chunk to the integers the query binds.
     * <p>
     * A key that is not a number is left out rather than failing the whole
     * chunk: the line itself is reported when it is processed.
     *
     * @param targetCodes The regime numbers as written in the file.
     * @return The numbers that parse, possibly empty.
     */
    private static List<Integer> numbers(Set<String> targetCodes) {
        List<Integer> parsed = new java.util.ArrayList<>(targetCodes.size());
        for (String code : targetCodes) {
            Integer number = parseNumber(code);
            if (number != null) {
                parsed.add(number);
            }
        }
        return parsed;
    }

    /**
     * Parses a regime number, tolerating a malformed cell.
     *
     * @param code The number as written in the file, possibly null.
     * @return The number, or null when the cell is empty or not an integer.
     */
    private static Integer parseNumber(String code) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(code.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Creates or updates one regime from a parsed CSV line.
     *
     * @param data       The parsed CSV line data.
     * @param entityMap  The context map holding the existing regimes.
     * @param counters   An array of size 2 to hold [createdCount, updatedCount].
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        Integer number = parseNumber(data.code);
        if (number == null) {
            throw new IllegalArgumentException("VAT number '" + data.code + "' is not an integer.");
        }
        Map<String, VatRate> rateMap = retrieveVatRates(data, entityMap);
        VatRate rate = rateMap.get(data.code);
        boolean isNew = (rate == null);
        if (isNew) {
            rate = new VatRate();
            rate.number = number;
            feedVatRate(data, rate);
            counters[0]++;
            Panache.getEntityManager().persist(rate);
        } else {
            int incomingChecksum = computeIncomingChecksum(number, data);
            if (rate.checksum != incomingChecksum) {
                rate = VatRate.findById(rate.id);
                feedVatRate(data, rate);
                counters[1]++;
            }
        }
    }

    /**
     * Retrieves the regime map for the current line.
     * <p>
     * Returns the map from the context. If the map is missing (1-by-1 fallback
     * mode), it performs a database lookup on this line's number alone.
     *
     * @param data      The parsed CSV line data.
     * @param entityMap The context map.
     * @return A map from the number as written to the regime entity.
     */
    private Map<String, VatRate> retrieveVatRates(LineData data, Map<String, Object> entityMap) {
        @SuppressWarnings("unchecked")
        Map<String, VatRate> rateMap = (Map<String, VatRate>) entityMap.get(CTX_VAT_RATES);
        if (rateMap == null) {
            rateMap = new HashMap<>();
            VatRate existing = VatRate.findByNumber(parseNumber(data.code));
            if (existing != null) {
                rateMap.put(data.code, existing);
            }
        }
        return rateMap;
    }

    /**
     * Populates a regime with the values of the parsed CSV line.
     *
     * @param data The parsed CSV line data.
     * @param rate The regime to populate.
     */
    private void feedVatRate(LineData data, VatRate rate) {
        rate.rate = safeParseBigDecimal(data, COL_RATE);
        rate.label = safeGet(data, COL_LABEL);
    }

    /**
     * Implements the specific logic to find a fresh regime from the database.
     * <p>
     * Used by the generic 1-by-1 fallback.
     *
     * @param data The parsed CSV line data.
     * @return The regime, or null when the file names a number nothing carries.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return VatRate.findByNumber(parseNumber(data.code));
    }

    /**
     * Computes the checksum of the incoming line.
     * <p>
     * Replicates {@link VatRate#getChecksum()} logic.
     *
     * @param number The regime number of the line.
     * @param data   The parsed CSV line data.
     * @return The integer hash of the incoming data.
     */
    private int computeIncomingChecksum(Integer number, LineData data) {
        BigDecimal rate = safeParseBigDecimal(data, COL_RATE);
        return Objects.hash(number, rate, safeGet(data, COL_LABEL));
    }
}
