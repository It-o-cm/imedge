package com.intermarche.edge.ui.report;

import com.intermarche.edge.domain.catalog.Nomenclature;
import com.intermarche.edge.domain.catalog.NomenclatureLevel;
import com.intermarche.edge.domain.catalog.ProductFamily;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a sold line's family code UP the store's nomenclature: the rayon a
 * family belongs to, the activity above it, the label of any node — what the
 * states broken « par rayon » (BO-06-04-01, BO-06-07-01) and edited at a
 * chosen LEVEL (activités, rayons, familles, sous-familles) need.
 *
 * <p>Nothing is stored for that: the codes are positional and cumulative, so
 * the rayon of {@code 10020200} is {@code 1002}, read off the code at the
 * length of the rayon level. A store whose nomenclature is not loaded, or a
 * line whose family is not a node of it, keeps its own code at every level —
 * the line is still counted, under the family it carried.
 *
 * <p>One lookup per report run: the labels are read once per node.
 */
public class NomenclatureLookup {

    /** The store's scheme, or null when none is loaded. */
    private final Nomenclature scheme;

    /** The labels already read, by node code. */
    private final Map<String, String> labels = new HashMap<>();

    /**
     * Builds a lookup over a scheme.
     *
     * @param scheme the scheme, possibly null
     */
    NomenclatureLookup(Nomenclature scheme) {
        this.scheme = scheme;
    }

    /**
     * Builds the lookup over the store's scheme: the first active one.
     *
     * @return the lookup, over no scheme when none is loaded
     */
    public static NomenclatureLookup current() {
        for (Nomenclature candidate : Nomenclature.listAllOrdered()) {
            if (candidate.active) {
                return new NomenclatureLookup(candidate);
            }
        }
        return new NomenclatureLookup(null);
    }

    /**
     * Returns the levels of the scheme, top first.
     *
     * @return the levels, empty without a scheme
     */
    public List<NomenclatureLevel> levels() {
        return scheme == null ? List.of() : scheme.levels();
    }

    /**
     * Returns the rank of the RAYON level: the level named so, else the
     * second level of the scheme.
     *
     * @return the rank, 1 when no level is named rayon
     */
    public int rayonRank() {
        for (NomenclatureLevel level : levels()) {
            if (level.label != null && level.label.toLowerCase(java.util.Locale.ROOT).contains("rayon")) {
                return level.rank;
            }
        }
        return 1;
    }

    /**
     * Reads a code's ancestor at a level: the code cut to that level's length.
     *
     * @param code the sold line's family code, possibly null
     * @param rank the 0-based level
     * @return the ancestor's code, the code itself when it is not deeper than
     *         the level, not positional, or the scheme has no such level, an
     *         empty text for null
     */
    public String ancestor(String code, int rank) {
        if (code == null) {
            return "";
        }
        NomenclatureLevel level = scheme == null ? null : scheme.levelAt(rank);
        // Only a POSITIONAL code carries its ancestors in its digits: a family
        // coded otherwise (EPICERIE) is its own rayon rather than a truncation.
        if (level == null || code.length() <= level.codeLength || !code.chars().allMatch(Character::isDigit)) {
            return code;
        }
        return code.substring(0, level.codeLength);
    }

    /**
     * Reads the label of a node, once.
     *
     * @param code the node's code
     * @return its description, an empty text when no node carries the code
     */
    public String label(String code) {
        return labels.computeIfAbsent(code, key -> {
            ProductFamily node = key.isEmpty() ? null : ProductFamily.findByCode(key);
            return node == null || node.description == null ? "" : node.description;
        });
    }
}
