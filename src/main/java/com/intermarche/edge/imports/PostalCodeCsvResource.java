package com.intermarche.edge.imports;

import com.intermarche.edge.domain.store.PostalCode;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Imports the POSTAL CODES ({@code BO-02-04-15}, {@code BO-02-04-21}).
 *
 * <p>National, enseigne-wide or shop-wide: the requirement names three scales
 * and they are the same file imported on three nodes. Nothing here has to know
 * which — a code list posed on the central node reaches the shops by the
 * referential draw, one posed here stays here, and that is the rule every
 * referential of this project already follows.
 *
 * <p>The PORTUGUESE list is this same import ({@code BO-02-04-21}): its codes
 * are written {@code 1234-567} and its places are upper-case, but a format is
 * not a schema — the column holds what the country writes, and a validator
 * taught two formats would refuse the third country the group opens in.
 *
 * <p>Consumed columns, resolved by header name: CODE (key), PLACE and the
 * optional COUNTRY. The natural key of a row is the PAIR (code, place): a
 * French code covers several communes, a Portuguese one covers a street, and
 * keying on the code alone would keep one of them and lose the others.
 */
// No @Path: the ONE door is POST /feeds/import/{CODE}, which delegates here.
// A second address for the same import is a second thing to secure, to
// document and to keep in step, and the permission policy had to name them
// one by one — an importer forgotten there was a door left open.
@ApplicationScoped
public class PostalCodeCsvResource extends ImporterCsvResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PostalCodeCsvResource.class);

    /** Header name of the postal code. */
    static final String COL_CODE = "CODE";
    /** Header name of the place the code designates. */
    static final String COL_PLACE = "PLACE";
    /** Header name of the optional ISO country. */
    static final String COL_COUNTRY = "COUNTRY";

    /** The columns this importer cannot work without. */
    private static final List<String> REQUIRED_COLUMNS = List.of(COL_PLACE);

    /**
     * Imports or updates the postal codes from a CSV stream.
     *
     * @param inputStream the stream carrying the CSV
     * @return a JSON summary of the created and updated counts and the errors
     */
    public Response importPostalCodes(InputStream inputStream) {
        LOGGER.info("Entering method importPostalCodes");
        LOGGER.info("Exiting method importPostalCodes");
        return this.importCsvStream(inputStream, COL_CODE, REQUIRED_COLUMNS);
    }

    /**
     * Bulk-fetches the rows of the chunk, keyed by the pair they carry.
     *
     * <p>The base class hands a chunk keyed by the CODE column, which is not
     * the natural key here: the rows are fetched by code and then indexed by
     * the pair, so a code covering three communes finds all three.
     *
     * @param parsedLines the rows of the current chunk
     * @param targetCodes the postal codes of the current chunk
     * @param counters the created and updated counters
     * @param errors the collected error messages
     * @return the pair-to-entity map the generic algorithm works on
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines,
                                                           Set<String> targetCodes,
                                                           int[] counters, List<String> errors) {
        if (parsedLines.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, Object> rows = new HashMap<>();
        for (PostalCode postalCode : PostalCode.<PostalCode>list("code IN ?1", targetCodes)) {
            rows.put(pair(postalCode.code, postalCode.place), postalCode);
        }
        return rows;
    }

    /**
     * Creates or updates one row from its line.
     *
     * @param data the parsed row
     * @param entityMap the rows already in the database, by pair
     * @param counters the created and updated counters
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        String place = safeGet(data, COL_PLACE);
        PostalCode postalCode = (PostalCode) entityMap.get(pair(data.code, place));
        if (postalCode == null) {
            postalCode = new PostalCode();
            postalCode.code = data.code;
            postalCode.place = place;
            postalCode.country = safeGet(data, COL_COUNTRY);
            counters[0]++;
            Panache.getEntityManager().persist(postalCode);
        } else {
            postalCode = PostalCode.findById(postalCode.id);
            String country = safeGet(data, COL_COUNTRY);
            if (!java.util.Objects.equals(postalCode.country, country)) {
                postalCode.country = country;
                counters[1]++;
            }
        }
    }

    /**
     * Finds one row afresh, for the one-by-one fallback of the base class.
     *
     * @param data the parsed row
     * @return the row, or null when that pair is unknown
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return PostalCode.findPair(data.code, safeGet(data, COL_PLACE));
    }

    /**
     * Builds the key of a row: the pair, the code alone not being unique.
     *
     * @param code the postal code, possibly null
     * @param place the place, possibly null
     * @return the composed key
     */
    static String pair(String code, String place) {
        return (code == null ? "" : code) + '\u0000' + (place == null ? "" : place);
    }
}
