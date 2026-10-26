package com.intermarche.edge.imports;

import com.intermarche.edge.domain.catalog.EgalimRegime;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
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
 * REST endpoint bulk importing the EGAlim regimes from a CSV stream.
 * <p>
 * Consumed columns (resolved by header name; unknown columns of the shared
 * feed are ignored): CODE (key), LABEL, CAP_RATE.
 * <p>
 * The CODE is the natural key, and it is what the article feed states: a legal
 * ceiling change is a new value on the same code, and no article row is
 * rewritten. The ceiling is read as a FRACTION — {@code 0.3400} for
 * thirty-four percent — like every rate of the suite; a file stating
 * {@code 34} would declare a regime capped at three thousand four hundred
 * percent, which is no cap at all.
 * <p>
 * An EMPTY ceiling is not zero: it is the ABSENCE of a ceiling, which is how
 * the exempt category is declared. Zero would mean "no promotional generosity
 * allowed on this article", the exact contrary of what the file intends, so
 * the cell is left empty and the column stays null.
 * <p>
 * Import ORDER: this file goes in BEFORE the articles, for the same reason the
 * VAT regimes go in before the prices — an article line naming a regime needs
 * that regime to exist for the classification to mean anything.
 * <p>
 * Place in the architecture: like every CSV endpoint of this node, it exists
 * on a register too but belongs on the STORE node — an import performed on a
 * register is transient, while an import on the node changes the domain
 * fingerprint and propagates.
 * <p>
 * This importer NAMES an engine feed ({@code EGALIM_REGIMES}), so the raw file
 * is retained verbatim and delivered to the valuation engines. It has to: the
 * engine is the only party that computes, the article feed carries the regime
 * CODE and not its number of percent, and without this feed the engine would
 * cap at figures compiled into its own code — a ceiling corrected upstream
 * would change nothing at all, which is two sources for one number and the
 * wrong one winning.
 */
// No @Path: the ONE door is POST /feeds/import/{CODE}, which delegates here.
@ApplicationScoped
public class EgalimRegimeCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(EgalimRegimeCsvResource.class);

    /** Key used to store the regime map in the generic context map. */
    private static final String CTX_EGALIM_REGIMES = "__CTX_EGALIM_REGIMES__";

    /** Header name of the natural key: the regime code. */
    static final String COL_CODE = "CODE";
    /** Header name of the human-readable label. */
    static final String COL_LABEL = "LABEL";
    /** Header name of the ceiling, as a fraction; empty means uncapped. */
    static final String COL_CAP_RATE = "CAP_RATE";

    /**
     * The columns this importer cannot work without.
     * <p>
     * The ceiling is NOT among them: a file declaring the exempt category
     * states a code and no ceiling, and demanding the column would make the
     * one regime that has no ceiling impossible to declare.
     */
    private static final List<String> REQUIRED_COLUMNS = List.of();

    /**
     * Imports or updates EGAlim regimes from a CSV stream.
     * Delegates stream reading and chunking to the abstract base class.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    public Response importEgalimRegimes(InputStream inputStream) {
        LOGGER.info("Entering method importEgalimRegimes with inputStream: " + inputStream);
        LOGGER.info("Exiting method importEgalimRegimes");
        return this.importCsvStream(inputStream, COL_CODE, REQUIRED_COLUMNS);
    }

    /**
     * Names the engine feed captured by this importer: the raw file is
     * retained verbatim for the valuation engines (single import line).
     * <p>
     * The regimes ARE an engine feed. The engine alone computes the EGAlim
     * correction, and the article feed hands it a regime CODE, never a
     * ceiling; the ceiling only exists in this referential. An engine that
     * never receives this file caps at the values compiled into its own enum,
     * and a rate corrected on the EGAlim screen then changes nothing — the
     * screen would administer a number nobody reads.
     *
     * @return the EGALIM_REGIMES feed code
     */
    @Override
    protected String feedCode() {
        return "EGALIM_REGIMES";
    }

    /**
     * Bulk-fetches the regimes named by the current chunk.
     *
     * @param parsedLines The list of data for the current chunk.
     * @param targetCodes The set of unique regime codes in this chunk.
     * @param counters    An array of size 2 to hold [createdCount, updatedCount].
     * @param errors      List to collect definitive error messages.
     * @return A Map holding the existing regimes, indexed by their code.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines,
                                                           Set<String> targetCodes,
                                                           int[] counters, List<String> errors) {
        if (parsedLines.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, Object> contextMap = new HashMap<>();
        contextMap.put(CTX_EGALIM_REGIMES, regimeMap(targetCodes));
        return contextMap;
    }

    /**
     * Loads the regimes carrying one of the given codes.
     *
     * @param targetCodes The regime codes of the chunk, as written in the file.
     * @return A map from the code as written to the regime entity.
     */
    private static Map<String, EgalimRegime> regimeMap(Set<String> targetCodes) {
        Map<String, EgalimRegime> regimes = new HashMap<>();
        if (targetCodes.isEmpty()) {
            return regimes;
        }
        for (EgalimRegime regime : EgalimRegime.<EgalimRegime>list("code IN ?1", codes(targetCodes))) {
            regimes.put(regime.code, regime);
        }
        return regimes;
    }

    /**
     * Normalizes the keys of a chunk the way the rows are stored.
     * <p>
     * Upper-cased, like the administration screen: a referential answering
     * differently to {@code food} and {@code FOOD} holds two categories where
     * the law has one, and the file is not always written by the same hand as
     * the screen.
     *
     * @param targetCodes The regime codes as written in the file.
     * @return The normalized codes, possibly empty.
     */
    private static List<String> codes(Set<String> targetCodes) {
        List<String> normalized = new java.util.ArrayList<>(targetCodes.size());
        for (String code : targetCodes) {
            String parsed = normalize(code);
            if (parsed != null) {
                normalized.add(parsed);
            }
        }
        return normalized;
    }

    /**
     * Normalizes one code, tolerating an empty cell.
     *
     * @param code The code as written in the file, possibly null.
     * @return The trimmed upper-cased code, or null when the cell is empty.
     */
    private static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim().toUpperCase(java.util.Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
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
        String code = normalize(data.code);
        if (code == null) {
            throw new IllegalArgumentException("EGAlim regime code is mandatory.");
        }
        BigDecimal cap = safeParseBigDecimal(data, COL_CAP_RATE);
        if (cap != null && (cap.signum() < 0 || cap.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("EGAlim cap '" + safeGet(data, COL_CAP_RATE)
                    + "' for regime " + code + " is not a fraction between 0 and 1.");
        }
        Map<String, EgalimRegime> regimes = retrieveRegimes(data, entityMap);
        EgalimRegime regime = regimes.get(code);
        boolean isNew = (regime == null);
        if (isNew) {
            regime = new EgalimRegime();
            regime.code = code;
            feedRegime(data, regime, cap);
            counters[0]++;
            Panache.getEntityManager().persist(regime);
        } else {
            int incomingChecksum = computeIncomingChecksum(code, data, cap);
            if (regime.changedFrom(incomingChecksum)) {
                regime = EgalimRegime.findById(regime.id);
                feedRegime(data, regime, cap);
                counters[1]++;
            }
        }
    }

    /**
     * Retrieves the regime map for the current line.
     * <p>
     * Returns the map from the context. If the map is missing (1-by-1 fallback
     * mode), it performs a database lookup on this line's code alone.
     *
     * @param data      The parsed CSV line data.
     * @param entityMap The context map.
     * @return A map from the code as stored to the regime entity.
     */
    private Map<String, EgalimRegime> retrieveRegimes(LineData data, Map<String, Object> entityMap) {
        @SuppressWarnings("unchecked")
        Map<String, EgalimRegime> regimes =
                (Map<String, EgalimRegime>) entityMap.get(CTX_EGALIM_REGIMES);
        if (regimes == null) {
            regimes = new HashMap<>();
            EgalimRegime existing = EgalimRegime.findByCode(normalize(data.code));
            if (existing != null) {
                regimes.put(existing.code, existing);
            }
        }
        return regimes;
    }

    /**
     * Populates a regime with the values of the parsed CSV line.
     *
     * @param data   The parsed CSV line data.
     * @param regime The regime to populate.
     * @param cap    The ceiling already read and validated, or null for uncapped.
     */
    private void feedRegime(LineData data, EgalimRegime regime, BigDecimal cap) {
        regime.label = safeGet(data, COL_LABEL);
        regime.capRate = cap;
    }

    /**
     * Implements the specific logic to find a fresh regime from the database.
     * <p>
     * Used by the generic 1-by-1 fallback.
     *
     * @param data The parsed CSV line data.
     * @return The regime, or null when the file names a code nothing carries.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return EgalimRegime.findByCode(normalize(data.code));
    }

    /**
     * Computes the checksum of the incoming line.
     * <p>
     * Replicates {@link EgalimRegime#getChecksum()} logic.
     *
     * @param code The normalized regime code of the line.
     * @param data The parsed CSV line data.
     * @param cap  The ceiling already read, or null.
     * @return The integer hash of the incoming data.
     */
    private int computeIncomingChecksum(String code, LineData data, BigDecimal cap) {
        return Objects.hash(code, safeGet(data, COL_LABEL), cap);
    }
}
