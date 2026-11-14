package com.intermarche.edge.imports;

import com.intermarche.edge.domain.store.Pdv;
import com.intermarche.edge.domain.store.PdvHistory;
import com.intermarche.edge.service.PdvRegistryService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Imports the POINTS OF SALE with their organisational attachment
 * (BO-02-05-03): each line names a point of sale, its enseigne and the
 * country of that enseigne, and its adhérent, through the one door of every
 * feed, {@code POST /feeds/import/PDVS}.
 *
 * <p>Consumed columns, resolved by header name: PDV_NUMBER (key), NAME
 * (required), and the optional PREVIOUS_NUMBER (renumbering, BO-02-05-01),
 * ENSEIGNE_CODE, ENSEIGNE_NAME, ENSEIGNE_LANGUAGE, COUNTRY_CODE, COUNTRY_NAME,
 * COUNTRY_LANGUAGE, ADHERENT_CODE and ACTIVE. A country and an enseigne the
 * line names are created or updated before the point of sale is attached to
 * them, so one file builds the whole tree.
 *
 * <p>Every point of sale goes through the registry, the very rules of the
 * echelons screen: five digits, unique across every country and enseigne, a
 * former number never handed to another, the history of every change. A line
 * the registry refuses is reported with the registry's reason and costs only
 * itself.
 *
 * <p>A REPLAYED line writes nothing: the checksum of the line last applied is
 * kept on the point of sale, so the same file coming round again — the replay
 * of a kept deposit — never undoes a correction made on the screen since.
 */
// No @Path: the ONE door is POST /feeds/import/{CODE}, which delegates here.
@ApplicationScoped
public class PdvCsvResource extends ImporterCsvResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PdvCsvResource.class);

    /** Header name of the point-of-sale number, the key. */
    static final String COL_NUMBER = "PDV_NUMBER";
    /** Header name of the point-of-sale name. */
    static final String COL_NAME = "NAME";
    /** Header name of the number the point of sale carried before, to renumber it. */
    static final String COL_PREVIOUS = "PREVIOUS_NUMBER";
    /** Header name of the enseigne code. */
    static final String COL_ENSEIGNE = "ENSEIGNE_CODE";
    /** Header name of the enseigne name. */
    static final String COL_ENSEIGNE_NAME = "ENSEIGNE_NAME";
    /** Header name of the enseigne's reference language. */
    static final String COL_ENSEIGNE_LANGUAGE = "ENSEIGNE_LANGUAGE";
    /** Header name of the country code. */
    static final String COL_COUNTRY = "COUNTRY_CODE";
    /** Header name of the country name. */
    static final String COL_COUNTRY_NAME = "COUNTRY_NAME";
    /** Header name of the country's reference language. */
    static final String COL_COUNTRY_LANGUAGE = "COUNTRY_LANGUAGE";
    /** Header name of the adhérent code. */
    static final String COL_ADHERENT = "ADHERENT_CODE";
    /** Header name of the active flag. */
    static final String COL_ACTIVE = "ACTIVE";

    /** The columns this importer cannot work without. */
    private static final List<String> REQUIRED_COLUMNS = List.of(COL_NAME);

    /** Every column a line is fingerprinted on. */
    private static final List<String> FINGERPRINTED = List.of(COL_NUMBER, COL_NAME, COL_PREVIOUS,
            COL_ENSEIGNE, COL_ENSEIGNE_NAME, COL_ENSEIGNE_LANGUAGE, COL_COUNTRY, COL_COUNTRY_NAME,
            COL_COUNTRY_LANGUAGE, COL_ADHERENT, COL_ACTIVE);

    /** The registry every point-of-sale write goes through. */
    @Inject
    PdvRegistryService pdvRegistry;

    /**
     * Imports or updates the points of sale from a CSV stream.
     *
     * @param inputStream the stream carrying the CSV
     * @return a JSON summary of the created and updated counts and the errors
     */
    public Response importPdvs(InputStream inputStream) {
        LOGGER.info("Entering method importPdvs");
        LOGGER.info("Exiting method importPdvs");
        return this.importCsvStream(inputStream, COL_NUMBER, REQUIRED_COLUMNS);
    }

    /**
     * Prepares nothing in bulk: a file of points of sale is counted in
     * thousands at most, and every line goes through the registry, which reads
     * what it needs itself.
     *
     * @param parsedLines the rows of the current chunk
     * @param targetCodes the numbers of the current chunk
     * @param counters the created and updated counters
     * @param errors the collected error messages
     * @return an empty context
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines,
                                                           Set<String> targetCodes,
                                                           int[] counters, List<String> errors) {
        return new HashMap<>();
    }

    /**
     * Writes one point of sale with its country and its enseigne, unless the
     * very same line was the last applied to it.
     *
     * @param data the parsed row
     * @param entityMap unused
     * @param counters the created and updated counters
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        int checksum = fingerprint(data);
        String previous = cell(data, COL_PREVIOUS);
        Pdv held = previous == null ? null : Pdv.findByNumber(previous);
        if (held == null) {
            // The renumbering a line asks for is already done when only the
            // new number exists: the line then updates the point of sale.
            held = Pdv.findByNumber(data.code);
            previous = held == null ? previous : null;
        }
        if (held != null && Objects.equals(held.importChecksum, checksum)) {
            return;
        }
        String country = cell(data, COL_COUNTRY);
        if (country != null) {
            pdvRegistry.upsertCountry(country, cell(data, COL_COUNTRY_NAME),
                    data.has(COL_COUNTRY_LANGUAGE) ? safeGet(data, COL_COUNTRY_LANGUAGE) : null);
        }
        String enseigne = cell(data, COL_ENSEIGNE);
        if (enseigne != null) {
            pdvRegistry.upsertEnseigne(enseigne, cell(data, COL_ENSEIGNE_NAME), country,
                    data.has(COL_ENSEIGNE_LANGUAGE) ? safeGet(data, COL_ENSEIGNE_LANGUAGE) : null);
        }
        boolean active = !data.has(COL_ACTIVE) || !"false".equalsIgnoreCase(safeGet(data, COL_ACTIVE));
        PdvRegistryService.Result result = pdvRegistry.save(data.code, previous,
                safeGet(data, COL_NAME), enseigne, cell(data, COL_ADHERENT), active,
                PdvHistory.Source.IMPORT);
        if (!result.ok()) {
            throw new IllegalArgumentException(result.message());
        }
        result.pdv().importChecksum = checksum;
        counters[result.created() ? 0 : 1]++;
    }

    /**
     * Finds one row afresh, for the one-by-one fallback of the base class.
     *
     * @param data the parsed row
     * @return the point of sale, or null when the number is unknown
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return Pdv.findByNumber(data.code);
    }

    /**
     * Reads an optional cell, an empty one counting as absent.
     *
     * @param data the parsed row
     * @param column the column
     * @return the trimmed cell, or null
     */
    private String cell(LineData data, String column) {
        String value = safeGet(data, column);
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * Fingerprints a line over every column this importer reads.
     *
     * @param data the parsed row
     * @return the checksum of the line
     */
    static int fingerprint(LineData data) {
        Object[] cells = new Object[FINGERPRINTED.size()];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = data.get(FINGERPRINTED.get(i));
        }
        return Objects.hash(cells);
    }
}
