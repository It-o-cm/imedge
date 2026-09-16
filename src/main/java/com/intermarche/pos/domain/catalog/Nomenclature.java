package com.intermarche.pos.domain.catalog;

import com.intermarche.pos.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Objects;

/**
 * One classification scheme for articles — what the questionnaire calls a
 * NOMENCLATURE (BO-02-01-01, BO-02-03-14).
 * <p>
 * A scheme is a named set of levels, and the levels differ per enseigne. The
 * hierarchy file the group publishes states both: Intermarché France runs
 * Activité / Rayon / Famille / Sous-famille, Bricomarché FR runs Rayon /
 * Famille / Sous-famille / Segment, and the code lengths differ too. Nothing
 * in the register may assume four levels, or two-digit activities: the shape
 * is data, held here, not a constant in the code.
 * <p>
 * The node codes are POSITIONAL and CUMULATIVE — this is the property the
 * whole model rests on. In the Intermarché scheme {@code 10} is an activity,
 * {@code 1002} the rayon inside it, {@code 10020200} a famille inside that,
 * {@code 100202000001} a sous-famille. A node's parent is therefore its own
 * code truncated to the previous level's length: the parent is not a fact to
 * transmit and store, it is a fact to READ off the code. That is also why a
 * node has exactly one parent inside a scheme — the unicity the VAT and sales
 * reports need (BO-06-07-01, BO-06-04-01) is a property of the coding, not a
 * convention someone has to defend.
 * <p>
 * Several schemes coexist on the central node — one per enseigne, and the
 * lower levels vary per point of sale ({@code CUSTOM} in the published file
 * against {@code NATIONAL} for the upper ones). A store node holds the scheme
 * of its own point of sale.
 */
@Entity
@Table(name = "nomenclatures",
        uniqueConstraints = @UniqueConstraint(columnNames = "code"))
public class Nomenclature extends BaseEntity {

    /** The scheme code, its upsert key (e.g. {@code ITM_FR}). */
    @Column(name = "code", nullable = false, length = 50, unique = true)
    @NotBlank(message = "Nomenclature code is mandatory")
    public String code;

    /** The human label of the scheme, shown in the back office. */
    @Column(name = "label", length = 100)
    public String label;

    /**
     * The enseigne this scheme classifies for, as the published hierarchy file
     * names it; null when the scheme is not tied to one.
     */
    @Column(name = "enseigne_code", length = 20)
    public String enseigneCode;

    /** Whether the scheme is in use; an inactive one is kept but never applied. */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /** The levels, read on first use; see {@link #levels()}. */
    @Transient
    private List<NomenclatureLevel> cachedLevels;

    /**
     * Default constructor for JPA.
     */
    public Nomenclature() {
    }

    /**
     * Finds a scheme by its code.
     *
     * @param code the scheme code
     * @return the scheme, or null when no scheme carries that code
     */
    public static Nomenclature findByCode(String code) {
        return find("code", code).firstResult();
    }


    /**
     * Lists every scheme, active or not, in code order.
     *
     * @return every scheme
     */
    public static List<Nomenclature> listAllOrdered() {
        return list("order by code");
    }

    /**
     * The levels of this scheme, read once and kept for the life of this
     * instance.
     * <p>
     * Kept, because placing a code and truncating it to its parent both need
     * the level table, and an import walking two and a half thousand nodes
     * would otherwise issue two queries per node for a table of four rows that
     * does not move during the walk. The cache is per instance, so it lives
     * and dies with the persistence context that loaded it; a caller that has
     * just rewritten the levels calls {@link #reloadLevels()}.
     *
     * @return the levels ordered by rank, empty when none is declared
     */
    public List<NomenclatureLevel> levels() {
        if (cachedLevels == null) {
            cachedLevels = NomenclatureLevel.listFor(this);
        }
        return cachedLevels;
    }

    /**
     * Forgets the levels read earlier, so the next read goes back to the
     * database. To be called by whoever has just changed them.
     */
    public void reloadLevels() {
        cachedLevels = null;
    }

    /**
     * How deep this scheme goes.
     *
     * @return the number of declared levels
     */
    public int depth() {
        return levels().size();
    }

    /**
     * The level a node code sits at, read off the code's LENGTH.
     * <p>
     * This is the inverse of the cumulative coding: an eight-character code in
     * the Intermarché scheme can only be a famille. A code matching no level's
     * length belongs to no level of this scheme, and the caller is told so with
     * null rather than with a guess.
     *
     * @param nodeCode the node code, possibly null
     * @return the level whose cumulative length the code has, or null
     */
    public NomenclatureLevel levelOfCode(String nodeCode) {
        if (nodeCode == null) {
            return null;
        }
        for (NomenclatureLevel level : levels()) {
            if (level.codeLength == nodeCode.length()) {
                return level;
            }
        }
        return null;
    }

    /**
     * The level of a given rank.
     *
     * @param rank the 0-based rank, the top level being rank zero
     * @return the level, or null when the scheme does not go that deep
     */
    public NomenclatureLevel levelAt(int rank) {
        for (NomenclatureLevel level : levels()) {
            if (level.rank == rank) {
                return level;
            }
        }
        return null;
    }

    /**
     * The code of a node's parent, truncated off the node's own code.
     * <p>
     * Returns null for a top-level node, which has no parent, and for a code
     * this scheme cannot place — truncating a code of unknown level would
     * invent an ancestry.
     *
     * @param nodeCode the node code, possibly null
     * @return the parent's code, or null when there is none
     */
    public String parentCodeOf(String nodeCode) {
        NomenclatureLevel level = levelOfCode(nodeCode);
        if (level == null || level.rank == 0) {
            return null;
        }
        NomenclatureLevel above = levelAt(level.rank - 1);
        if (above == null || above.codeLength >= nodeCode.length()) {
            return null;
        }
        return nodeCode.substring(0, above.codeLength);
    }


    /**
     * The fingerprint input of this scheme for the referential export.
     *
     * @return a hash of the code, label, enseigne and active flag
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, label, enseigneCode, active);
    }
}
