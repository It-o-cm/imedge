package com.intermarche.pos.service;

import com.intermarche.pos.domain.catalog.Nomenclature;
import com.intermarche.pos.domain.catalog.NomenclatureLevel;
import com.intermarche.pos.domain.catalog.ProductFamily;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads a NOMENCLATURE published by the gestion commerciale.
 * <p>
 * The file the group publishes states one node per line: its level, its code
 * and its label, plus where the line comes from ({@code NATIONAL} for the
 * levels every store shares, {@code CUSTOM} for the ones a point of sale
 * redefines). It does NOT state the parentage, and it does not need to: the
 * codes are cumulative, so a node's parent is its own code truncated to the
 * length of the level above. The importer therefore never wires an edge — it
 * places a node at its level and the ancestry follows.
 * <p>
 * It does not state the LEVEL TABLE either. The importer derives it from what
 * it reads: for each level present in the file, the code length observed there.
 * A level the file does not name keeps the generic label {@code Niveau n} until
 * someone renames it in the back office; renaming never breaks the placement,
 * because the placement is the length.
 * <p>
 * Two guarantees the shop depends on. The import is IDEMPOTENT: re-importing
 * the same file changes nothing, so a retry after a network failure is free.
 * And it is CONSERVATIVE about lengths: a level whose lines disagree on the
 * code length is refused rather than half-loaded, because a nomenclature whose
 * lengths are wrong silently misplaces every node under it.
 */
@ApplicationScoped
public class NomenclatureImportService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(NomenclatureImportService.class);

    /** The source marking a line a point of sale may redefine. */
    public static final String SOURCE_CUSTOM = "CUSTOM";

    /**
     * One node as the published file states it.
     */
    public static class Row {

        /** The 0-based level of the node. */
        public final int level;

        /** The node code, cumulative from the top. */
        public final String code;

        /** The node label. */
        public final String label;

        /** Whether the line is a point-of-sale one ({@code CUSTOM}). */
        public final boolean custom;

        /**
         * Builds a row.
         *
         * @param level the 0-based level
         * @param code the cumulative node code
         * @param label the node label
         * @param custom whether the line is point-of-sale specific
         */
        public Row(int level, String code, String label, boolean custom) {
            this.level = level;
            this.code = code;
            this.label = label;
            this.custom = custom;
        }
    }

    /**
     * What one import did, as the screen and the caller report it.
     */
    public static class Outcome {

        /** How many nodes were created. */
        public int created;

        /** How many nodes were updated. */
        public int updated;

        /** How many levels the scheme now declares. */
        public int levels;

        /** What was refused, line by line; empty on a clean import. */
        public final List<String> errors = new ArrayList<>();

        /**
         * Whether the import went through without a single refusal.
         *
         * @return true when nothing was refused
         */
        public boolean isClean() {
            return errors.isEmpty();
        }

        /**
         * How many nodes were written in all.
         *
         * @return the created plus the updated
         */
        public int getTouched() {
            return created + updated;
        }

        /**
         * Returns how many nodes were created.
         *
         * @return the created count
         */
        public int getCreated() {
            return created;
        }

        /**
         * Returns how many nodes were updated.
         *
         * @return the updated count
         */
        public int getUpdated() {
            return updated;
        }

        /**
         * Returns how many levels the scheme declares.
         *
         * @return the level count
         */
        public int getLevels() {
            return levels;
        }

        /**
         * Returns what was refused.
         *
         * @return the refusals, empty on a clean import
         */
        public List<String> getErrors() {
            return errors;
        }
    }

    /**
     * Loads a nomenclature: creates or updates the scheme, derives its levels
     * from the rows, then places every node.
     *
     * @param schemeCode the scheme code, its upsert key
     * @param schemeLabel the scheme label, used only on creation
     * @param enseigneCode the enseigne the scheme classifies for, or null
     * @param rows the published nodes
     * @return what the import did
     */
    @Transactional
    public Outcome load(String schemeCode, String schemeLabel, String enseigneCode,
                        List<Row> rows) {
        LOGGER.info("Entering method load with schemeCode: " + schemeCode);
        Outcome outcome = new Outcome();
        Nomenclature scheme = upsertScheme(schemeCode, schemeLabel, enseigneCode);
        Map<Integer, Integer> lengths = observedLengths(rows, outcome);
        if (!outcome.isClean()) {
            LOGGER.info("Exiting method load");
            return outcome;
        }
        applyLevels(scheme, lengths, rows);
        scheme.reloadLevels();
        outcome.levels = scheme.depth();
        for (Row row : rows) {
            placeNode(scheme, row, outcome);
        }
        LOGGER.infof("Nomenclature %s: %d créé(s), %d mis à jour, %d niveau(x)",
                schemeCode, outcome.created, outcome.updated, outcome.levels);
        LOGGER.info("Exiting method load");
        return outcome;
    }

    /**
     * Creates the scheme or refreshes what a re-import may legitimately change.
     *
     * @param code the scheme code
     * @param label the scheme label
     * @param enseigneCode the enseigne, or null
     * @return the persisted scheme
     */
    private Nomenclature upsertScheme(String code, String label, String enseigneCode) {
        Nomenclature scheme = Nomenclature.findByCode(code);
        if (scheme == null) {
            scheme = new Nomenclature();
            scheme.code = code;
        }
        scheme.label = label == null || label.isBlank() ? code : label;
        scheme.enseigneCode = enseigneCode;
        scheme.active = true;
        scheme.persist();
        return scheme;
    }

    /**
     * The code length observed at each level, and a refusal for every level
     * whose lines disagree.
     * <p>
     * Disagreeing lengths are the one thing that must stop an import: the
     * length IS the level, so two lengths at one level means every node below
     * would be placed by chance.
     *
     * @param rows the published nodes
     * @param outcome the outcome collecting the refusals
     * @return the length of each level, by level
     */
    private Map<Integer, Integer> observedLengths(List<Row> rows, Outcome outcome) {
        Map<Integer, Integer> lengths = new TreeMap<>();
        Map<Integer, String> witness = new LinkedHashMap<>();
        for (Row row : rows) {
            if (row.code == null || row.code.isBlank()) {
                outcome.errors.add("Niveau " + row.level + " : code vide.");
                continue;
            }
            Integer known = lengths.get(row.level);
            if (known == null) {
                lengths.put(row.level, row.code.length());
                witness.put(row.level, row.code);
            } else if (known != row.code.length()) {
                outcome.errors.add("Niveau " + row.level + " : longueurs incohérentes — « "
                        + witness.get(row.level) + " » (" + known + ") et « " + row.code
                        + " » (" + row.code.length() + ").");
            }
        }
        return lengths;
    }

    /**
     * Creates or updates the level table from the observed lengths, keeping a
     * label somebody already set.
     *
     * @param scheme the scheme being loaded
     * @param lengths the code length of each level
     * @param rows the published nodes, read for the custom flag
     */
    private void applyLevels(Nomenclature scheme, Map<Integer, Integer> lengths,
                             List<Row> rows) {
        for (Map.Entry<Integer, Integer> entry : lengths.entrySet()) {
            int rank = entry.getKey();
            NomenclatureLevel level = NomenclatureLevel.findByRank(scheme, rank);
            if (level == null) {
                level = new NomenclatureLevel();
                level.nomenclature = scheme;
                level.rank = rank;
                level.label = "Niveau " + rank;
            }
            level.codeLength = entry.getValue();
            level.custom = anyCustomAt(rows, rank);
            level.persist();
        }
    }

    /**
     * Whether any line of a level is a point-of-sale one.
     *
     * @param rows the published nodes
     * @param rank the level to look at
     * @return true when at least one line of that level is CUSTOM
     */
    private boolean anyCustomAt(List<Row> rows, int rank) {
        for (Row row : rows) {
            if (row.level == rank && row.custom) {
                return true;
            }
        }
        return false;
    }

    /**
     * Places one node: upsert by code, level from the file, consistency
     * checked against the level table before the row is kept.
     *
     * @param scheme the scheme being loaded
     * @param row the published node
     * @param outcome the outcome collecting counts and refusals
     */
    private void placeNode(Nomenclature scheme, Row row, Outcome outcome) {
        ProductFamily node = ProductFamily.findByCode(row.code);
        boolean creating = node == null;
        if (creating) {
            node = new ProductFamily();
            node.code = row.code;
        }
        node.description = row.label;
        node.nomenclature = scheme;
        node.level = row.level;
        if (!node.isConsistentWithNomenclature()) {
            outcome.errors.add("Code « " + row.code + " » : longueur "
                    + row.code.length() + " incompatible avec le niveau " + row.level + ".");
            return;
        }
        node.persist();
        if (creating) {
            outcome.created++;
        } else {
            outcome.updated++;
        }
    }
}
