package com.intermarche.edge.imports;

import com.intermarche.edge.service.NomenclatureImportService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the NOMENCLATURE file published by the gestion commerciale
 * ({@code POST /feeds/import/NOMENCLATURE}).
 * <p>
 * The file is the pipe-separated export of the group's hierarchy: one line per
 * node, carrying where the line comes from, the level, the code and the label.
 * The header names the columns, so a column added upstream never shifts the
 * reading:
 * <pre>SOURCE|NIVEAU|NUMERO|LIBELLE</pre>
 * The published extract also carries a {@code STIME_NUMBER} column naming the
 * point of sale; it is ignored here, because a node already knows which shop
 * it is on — the one running this store node.
 * <p>
 * Which scheme the file loads into is named on the query
 * ({@code ?scheme=ITM_FR&label=...&enseigne=ITM}), not guessed from the file:
 * the published lines carry {@code NUMERO}, which identifies a node INSIDE a
 * scheme, never the scheme itself, and two enseignes publish the same columns
 * and the same numbers. The foreign key is missing from the file, so the
 * caller supplies it.
 * <p>
 * No @Path: the ONE door is {@code POST /feeds/import/{CODE\}}, which
 * delegates here and forwards the three query parameters. This importer was
 * the last to keep an address of its own, on the grounds that the relay's
 * delegation took a stream and nothing else — a property of that table, not
 * of this domain. The table now carries the parameters.
 * <p>
 * {@code @ApplicationScoped} is what makes this class a bean now that it has
 * no {@code @Path}: the JAX-RS annotation used to be its ONLY bean-defining
 * annotation, so removing it also removed it from CDI discovery, and the two
 * classes that inject it — the relay and the back-office screen — no longer
 * resolved. Its nine sibling importers already carried the scope.
 */
@ApplicationScoped
public class NomenclatureCsvResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(NomenclatureCsvResource.class);

    /** The column separator of the published file. */
    private static final String SEPARATOR = "\\|";

    /** Header of the column naming where the line comes from. */
    static final String COL_SOURCE = "SOURCE";

    /** Header of the column naming the level. */
    static final String COL_LEVEL = "NIVEAU";

    /** Header of the column naming the node code. */
    static final String COL_CODE = "NUMERO";

    /** Header of the column naming the node label. */
    static final String COL_LABEL = "LIBELLE";

    /** The loader this resource is the HTTP surface of. */
    @Inject
    NomenclatureImportService nomenclatureImportService;

    /**
     * Reads the published file and loads it into one scheme.
     *
     * @param schemeCode the scheme to load into, its upsert key
     * @param schemeLabel the scheme label, used on creation
     * @param enseigneCode the enseigne the scheme classifies for, or null
     * @param inputStream the published file
     * @return a JSON summary of what was loaded and what was refused
     */
    public Response importNomenclature(String schemeCode,
            String schemeLabel,
            String enseigneCode,
            InputStream inputStream) {
        LOGGER.info("Entering method importNomenclature with scheme: " + schemeCode);
        if (schemeCode == null || schemeCode.isBlank()) {
            LOGGER.info("Exiting method importNomenclature");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"errors\":[\"Le paramètre scheme est obligatoire.\"]}").build();
        }
        List<String> complaints = new ArrayList<>();
        List<NomenclatureImportService.Row> rows = read(inputStream, complaints);
        if (!complaints.isEmpty()) {
            LOGGER.info("Exiting method importNomenclature");
            return Response.status(Response.Status.BAD_REQUEST).entity(json(0, 0, 0, complaints))
                    .build();
        }
        NomenclatureImportService.Outcome outcome =
                nomenclatureImportService.load(schemeCode, schemeLabel, enseigneCode, rows);
        LOGGER.info("Exiting method importNomenclature");
        return Response.status(outcome.isClean()
                        ? Response.Status.OK : Response.Status.CONFLICT)
                .entity(json(outcome.created, outcome.updated, outcome.levels, outcome.errors))
                .build();
    }

    /**
     * Reads the published file into rows, resolving the columns by header name.
     * <p>
     * Public because the back-office screen integrates the very same file
     * through a form (BO-02-01-08) and must read it the same way: one reading
     * of the published format, not two that can drift apart.
     *
     * @param inputStream the published file
     * @param complaints the list collecting what stopped the reading
     * @return the rows, empty when the reading was stopped
     */
    public List<NomenclatureImportService.Row> read(InputStream inputStream,
            List<String> complaints) {
        List<NomenclatureImportService.Row> rows = new ArrayList<>();
        if (inputStream == null) {
            complaints.add("Fichier absent.");
            return rows;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                complaints.add("Fichier vide.");
                return rows;
            }
            Map<String, Integer> columns = columns(header);
            for (String required : List.of(COL_LEVEL, COL_CODE, COL_LABEL)) {
                if (!columns.containsKey(required)) {
                    complaints.add("Colonne " + required + " absente.");
                }
            }
            if (!complaints.isEmpty()) {
                return rows;
            }
            String line;
            int number = 1;
            while ((line = reader.readLine()) != null) {
                number++;
                if (line.isBlank()) {
                    continue;
                }
                String[] cells = line.split(SEPARATOR, -1);
                Integer level = levelOf(cells, columns.get(COL_LEVEL));
                if (level == null) {
                    complaints.add("Ligne " + number + " : niveau illisible.");
                    continue;
                }
                rows.add(new NomenclatureImportService.Row(level,
                        cell(cells, columns.get(COL_CODE)),
                        cell(cells, columns.get(COL_LABEL)),
                        NomenclatureImportService.SOURCE_CUSTOM.equalsIgnoreCase(
                                cell(cells, columns.get(COL_SOURCE)))));
            }
        } catch (IOException problem) {
            complaints.add("Lecture interrompue : " + problem.getMessage());
        }
        return rows;
    }

    /**
     * Maps each header name to its position.
     *
     * @param header the header line
     * @return the position of each column, upper-cased and trimmed
     */
    private Map<String, Integer> columns(String header) {
        Map<String, Integer> positions = new HashMap<>();
        String[] names = header.split(SEPARATOR, -1);
        for (int i = 0; i < names.length; i++) {
            positions.put(names[i].trim().toUpperCase(), i);
        }
        return positions;
    }

    /**
     * Reads one cell, tolerating a short line.
     *
     * @param cells the split line
     * @param index the column position, possibly null
     * @return the trimmed cell, or the empty string
     */
    private String cell(String[] cells, Integer index) {
        if (index == null || index >= cells.length || cells[index] == null) {
            return "";
        }
        return cells[index].trim();
    }

    /**
     * Reads the level of a line.
     *
     * @param cells the split line
     * @param index the column position
     * @return the level, or null when the cell is not a number
     */
    private Integer levelOf(String[] cells, Integer index) {
        try {
            return Integer.valueOf(cell(cells, index));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * Builds the JSON summary the importers of this house all answer.
     *
     * @param created how many nodes were created
     * @param updated how many nodes were updated
     * @param levels how many levels the scheme declares
     * @param errors what was refused
     * @return the JSON body
     */
    private String json(int created, int updated, int levels, List<String> errors) {
        StringBuilder body = new StringBuilder("{\"createdCount\":").append(created)
                .append(", \"updatedCount\":").append(updated)
                .append(", \"levelCount\":").append(levels)
                .append(", \"errors\":[");
        for (int i = 0; i < errors.size(); i++) {
            body.append(i == 0 ? "\"" : ", \"")
                    .append(errors.get(i).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\"");
        }
        return body.append("]}").toString();
    }
}
