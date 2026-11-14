package com.intermarche.edge.service;

import com.intermarche.edge.domain.safe.ReceiptFlow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The EXISTING FEEDS of receipts collected outside the registers
 * ({@code BO-05-02-03}): the fuel stations and the gas suppliers, read in their
 * own layout and handed to the safe's receipt importer in its canonical one.
 *
 * <p>A general service and not a screen's: the feed door
 * ({@code POST /feeds/import/SAFE_RECEIPTS?mode=CODE}) and the back-office
 * screen of the flows both go through it.
 *
 * <p>THREE FLOWS ARE DECLARED BY DEFAULT, one per existing feed the client
 * named — a fuel station's daily takings with one VAT rate, a gas supplier's
 * bottles and deposits with two (the gas taxed, the deposit not), a gas
 * supplier's tank deliveries with none. Their columns are the back office's
 * to adjust: the layout of a feed is configuration, never code.
 *
 * <p>A reference is prefixed with its flow's code, so two feeds that number
 * their receipts the same way never overwrite each other's — the importer's
 * upsert on the reference stays what it is for: a feed resending its file
 * corrects, it never credits twice.
 */
@ApplicationScoped
public class ReceiptFlowService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ReceiptFlowService.class);

    /** The separator of the importer's canonical file. */
    static final String CANONICAL_SEPARATOR = "|";

    /** The canonical column carrying the reference. */
    static final String REFERENCE = "REFERENCE";

    /** The canonical column carrying the day. */
    static final String DATE = "DATE";

    /**
     * Declares the three existing feeds when no flow is declared yet.
     *
     * @return the flows, in code order
     */
    @Transactional
    public List<ReceiptFlow> ensureDefaults() {
        LOGGER.info("Entering method ensureDefaults");
        if (ReceiptFlow.count() == 0) {
            declare("STATION", "Station-service : carburants et boutique", ";",
                    "REFERENCE=NUM_REMISE;DATE=DATE_JOUR;LABEL=LIBELLE;AMOUNT=MONTANT_TTC;"
                            + "VAT_RATE=TAUX_TVA;VAT_BASE=BASE_HT;VAT_AMOUNT=MONTANT_TVA", "dd/MM/yyyy");
            declare("GAZ_BOUTEILLE", "Fournisseur de gaz : bouteilles et consignes", ";",
                    "REFERENCE=NUM_BL;DATE=DATE;LABEL=DESIGNATION;AMOUNT=TOTAL_TTC;"
                            + "VAT_RATE=TVA1_TAUX;VAT_BASE=TVA1_BASE;VAT_AMOUNT=TVA1_MONTANT;"
                            + "VAT_RATE_2=TVA2_TAUX;VAT_BASE_2=TVA2_BASE;VAT_AMOUNT_2=TVA2_MONTANT", "dd/MM/yyyy");
            declare("GAZ_CITERNE", "Fournisseur de gaz : citernes et livraisons", "|",
                    "REFERENCE=REF;DATE=DATE;LABEL=LIBELLE;AMOUNT=MONTANT", "");
        }
        List<ReceiptFlow> flows = ReceiptFlow.listOrdered();
        LOGGER.info("Exiting method ensureDefaults with " + flows.size() + " flow(s)");
        return flows;
    }

    /**
     * Declares one flow, every mandatory column set before it is persisted.
     *
     * @param code the code
     * @param label the name
     * @param separator the column separator
     * @param mapping the column mapping
     * @param datePattern the date pattern, blank for ISO days
     */
    private void declare(String code, String label, String separator, String mapping, String datePattern) {
        ReceiptFlow flow = new ReceiptFlow();
        flow.code = code;
        flow.label = label;
        flow.separator = separator;
        flow.mapping = mapping;
        flow.datePattern = datePattern;
        flow.active = true;
        flow.persist();
    }

    /**
     * Rewrites a feed's file in the importer's canonical layout.
     *
     * <p>The header of the file is read by NAME, so a feed that adds or moves a
     * column still integrates; a mapped column the file lacks is left empty,
     * which the importer reads as absent — a VAT triplet left empty is a receipt
     * bearing no VAT.
     *
     * @param flow the flow the file belongs to
     * @param content the file as the feed sent it
     * @return the canonical file, header first
     */
    public String translate(ReceiptFlow flow, String content) {
        LOGGER.info("Entering method translate with flow: " + flow.code);
        Map<String, String> columns = flow.columns();
        String separator = flow.separator == null || flow.separator.isEmpty() ? ";" : flow.separator;
        Pattern split = Pattern.compile(Pattern.quote(separator));
        List<String> lines = new ArrayList<>();
        for (String line : content.split("\\r?\\n")) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        StringBuilder out = new StringBuilder(String.join(CANONICAL_SEPARATOR, columns.keySet())).append('\n');
        if (lines.isEmpty()) {
            LOGGER.info("Exiting method translate: empty file");
            return out.toString();
        }
        List<String> header = new ArrayList<>();
        for (String cell : split.split(lines.get(0), -1)) {
            header.add(cell.trim().toUpperCase(Locale.ROOT));
        }
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = split.split(line, -1);
            List<String> values = new ArrayList<>();
            for (Map.Entry<String, String> column : columns.entrySet()) {
                int index = header.indexOf(column.getValue());
                String value = index < 0 || index >= cells.length ? "" : cells[index].trim();
                if (REFERENCE.equals(column.getKey()) && !value.isEmpty()) {
                    value = flow.code + "-" + value;
                }
                if (DATE.equals(column.getKey())) {
                    value = isoDay(value, flow.datePattern);
                }
                values.add(value.replace(CANONICAL_SEPARATOR, " "));
            }
            out.append(String.join(CANONICAL_SEPARATOR, values)).append('\n');
        }
        LOGGER.info("Exiting method translate with " + (lines.size() - 1) + " line(s)");
        return out.toString();
    }

    /**
     * Rewrites a day of the feed as an ISO day.
     *
     * @param value the day as the feed wrote it
     * @param pattern the feed's date pattern, blank for ISO days
     * @return the ISO day, or the value as it came when it cannot be read
     */
    static String isoDay(String value, String pattern) {
        if (value.isEmpty() || pattern == null || pattern.isBlank()) {
            return value;
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern.trim())).toString();
        } catch (RuntimeException unreadable) {
            return value;
        }
    }

    /**
     * Records what the last integration of a flow answered.
     *
     * @param code the flow's code
     * @param result the importer's answer
     */
    @Transactional
    public void recordImport(String code, String result) {
        LOGGER.info("Entering method recordImport with code: " + code);
        ReceiptFlow flow = ReceiptFlow.findByCode(code);
        if (flow != null) {
            flow.lastImportAt = LocalDateTime.now();
            flow.lastResult = result == null ? null : result.length() <= 255 ? result : result.substring(0, 255);
            flow.persist();
        }
        LOGGER.info("Exiting method recordImport");
    }

    /**
     * States what an integration did, in the operator's words: the notice of
     * the screen and the last result kept on the flow.
     *
     * @param code the flow's code
     * @param status the importer's HTTP status
     * @param entity the importer's JSON summary, or null for an empty file
     * @return the French sentence
     */
    public static String summary(String code, int status, Object entity) {
        if (status >= 300) {
            return "Flux " + code + " refusé (statut " + status + ") : fichier non conforme au flux.";
        }
        if (entity == null) {
            return "Flux " + code + " : aucune recette dans le fichier.";
        }
        com.fasterxml.jackson.databind.JsonNode answer;
        try {
            answer = new com.fasterxml.jackson.databind.ObjectMapper().readTree(String.valueOf(entity));
        } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            return tolerant(code, status, String.valueOf(entity));
        }
        return counts(code, answer.path("createdCount").asInt(), answer.path("updatedCount").asInt(),
                answer.path("errors").size());
    }

    /**
     * Reads the counts of an importer's summary that is not valid JSON — the
     * generic importer writes its error list without the opening quote of its
     * first message — rather than losing them.
     *
     * @param code the flow's code
     * @param status the importer's HTTP status
     * @param text the summary as written
     * @return the French sentence, the status alone when no count is written
     */
    static String tolerant(String code, int status, String text) {
        java.util.regex.Matcher created = Pattern.compile("\"createdCount\"\\s*:\\s*(\\d+)").matcher(text);
        java.util.regex.Matcher updated = Pattern.compile("\"updatedCount\"\\s*:\\s*(\\d+)").matcher(text);
        if (!created.find() || !updated.find()) {
            return "Flux " + code + " intégré (statut " + status + ").";
        }
        int errors = 0;
        int list = text.indexOf("\"errors\":[");
        if (list >= 0) {
            errors = text.substring(list).split("\",\"", -1).length;
        }
        return counts(code, Integer.parseInt(created.group(1)), Integer.parseInt(updated.group(1)), errors);
    }

    /**
     * States the counts of an integration.
     *
     * @param code the flow's code
     * @param created the receipts created
     * @param updated the receipts corrected
     * @param errors the lines refused
     * @return the French sentence
     */
    static String counts(String code, int created, int updated, int errors) {
        return "Flux " + code + " : " + created + " recette(s) créée(s), " + updated + " mise(s) à jour, " + errors
                + " erreur(s).";
    }
}
