package com.intermarche.edge.imports;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.intermarche.edge.domain.catalog.Product;
import com.intermarche.edge.domain.catalog.attribute.ProductAttributeCatalog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Integrates the LOT NUMBERS under withdrawal or recall published by an
 * external source (BO-02-03-44) — a recall platform, the quality department,
 * a supplier's notice — into the articles they concern.
 *
 * <p>The feed is one line per article: {@code EAN|LOTS}, the lots separated by
 * semicolons or commas. The lots land in the article's {@code RECALL_LOTS}
 * attribute, which travels to the registers with the article; a register
 * reading a GS1 code that names one of those lots refuses the item, and an
 * entry that names no lot lists the recalled lots for the cashier to check.
 *
 * <p>Two integrations, like every referential of the questionnaire: a DELTA
 * file (the default) touches only the articles it names, an empty LOTS cell
 * lifting the recall of that article; a FULL file states the whole list, so
 * an article it does not name has its recalled lots lifted.
 */
// No @Path: the ONE door is POST /feeds/import/{CODE}, which delegates here.
@ApplicationScoped
public class RecallLotCsvResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(RecallLotCsvResource.class);

    /** The field delimiter of the shared feeds. */
    static final String DELIMITER = "\\|";

    /** Header of the article's EAN. */
    static final String COL_EAN = "EAN";

    /** Header of the recalled lot numbers. */
    static final String COL_LOTS = "LOTS";

    /**
     * Imports a recall file.
     *
     * @param inputStream the file
     * @param mode {@code FULL} to lift the recalls the file does not state,
     *        anything else for a delta
     * @return a JSON summary: articles updated, recalls lifted, errors
     */
    @Transactional
    public Response importRecallLots(InputStream inputStream, String mode) {
        LOGGER.info("Entering method importRecallLots with mode: " + mode);
        List<String> lines;
        try {
            lines = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank()).toList();
        } catch (IOException e) {
            LOGGER.info("Exiting method importRecallLots");
            return Response.serverError().entity(ImporterCsvResource.errorAnswer("Lecture du fichier impossible")).build();
        }
        if (lines.isEmpty()) {
            LOGGER.info("Exiting method importRecallLots");
            return Response.status(Response.Status.BAD_REQUEST).entity(ImporterCsvResource.errorAnswer("Fichier vide")).build();
        }
        List<String> header = List.of(lines.get(0).trim().split(DELIMITER, -1));
        int ean = header.indexOf(COL_EAN);
        int lots = header.indexOf(COL_LOTS);
        if (ean < 0 || lots < 0) {
            LOGGER.info("Exiting method importRecallLots");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ImporterCsvResource.errorAnswer("Colonnes EAN et LOTS obligatoires")).build();
        }
        Set<String> named = new HashSet<>();
        List<String> errors = new ArrayList<>();
        int updated = 0;
        for (int i = 1; i < lines.size(); i++) {
            String[] cells = lines.get(i).split(DELIMITER, -1);
            String code = cell(cells, ean);
            Product product = code.isEmpty() ? null : Product.findByEan(code);
            if (product == null) {
                errors.add("Ligne " + (i + 1) + " : article " + code + " inconnu");
                continue;
            }
            named.add(product.ean);
            String recalled = normalize(cell(cells, lots));
            if (recalled.isEmpty()) {
                product.attributes.remove(ProductAttributeCatalog.RECALL_LOTS);
            } else {
                product.attributes.put(ProductAttributeCatalog.RECALL_LOTS, recalled);
            }
            updated++;
        }
        int lifted = "FULL".equalsIgnoreCase(mode) ? liftOthers(named) : 0;
        LOGGER.info("Exiting method importRecallLots");
        return Response.ok(JsonNodeFactory.instance.objectNode().put("updated", updated)
                .put("lifted", lifted).put("errors", errors.size()).toString()).build();
    }

    /**
     * Lifts the recall of every article a FULL file does not name.
     *
     * @param named the EANs the file names
     * @return the number of articles whose recall was lifted
     */
    private int liftOthers(Set<String> named) {
        int lifted = 0;
        List<Product> recalled = Product.list("select p from Product p join p.attributes a where key(a) = ?1",
                ProductAttributeCatalog.RECALL_LOTS);
        for (Product product : recalled) {
            if (!named.contains(product.ean)) {
                product.attributes.remove(ProductAttributeCatalog.RECALL_LOTS);
                lifted++;
            }
        }
        return lifted;
    }

    /**
     * Reads one cell, empty when the line is too short.
     *
     * @param cells the cells of the line
     * @param index the column index
     * @return the trimmed cell
     */
    static String cell(String[] cells, int index) {
        return index < cells.length ? cells[index].trim() : "";
    }

    /**
     * Normalizes a list of lots: split on semicolons or commas, trimmed, each
     * lot once, joined with semicolons.
     *
     * @param raw the raw cell
     * @return the normalized list, empty when it names no lot
     */
    static String normalize(String raw) {
        Set<String> lots = new LinkedHashSet<>();
        for (String entry : raw.split("[;,]")) {
            if (!entry.isBlank()) {
                lots.add(entry.trim());
            }
        }
        return String.join(";", lots);
    }
}
