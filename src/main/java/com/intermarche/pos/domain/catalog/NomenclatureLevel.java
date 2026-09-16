package com.intermarche.pos.domain.catalog;

import com.intermarche.pos.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.List;
import java.util.Objects;

/**
 * One level — one palier — of a {@link Nomenclature}.
 * <p>
 * The published hierarchy file declares these per enseigne and they are not
 * the same on both: Intermarché France runs Activité (2 digits) / Rayon (2,
 * 4 cumulated) / Famille (4, 8 cumulated) / Sous-famille (4, 12 cumulated);
 * Bricomarché France runs Rayon (3) / Famille (3, 6) / Sous-famille (2, 8) /
 * Segment (4, 12). Reports select a level by name — "rupture par rayon" in
 * BO-06-07-01 — so the name is data the register must carry, not a label
 * hard-coded in a screen.
 * <p>
 * {@code codeLength} is the CUMULATIVE length, the one a node code actually
 * has at this level, because that is what both jobs need: placing a code at
 * its level, and truncating it to its parent. The level's own contribution is
 * the difference with the level above, and is derived rather than stored, so
 * the two can never disagree.
 * <p>
 * {@code custom} marks the levels a point of sale may redefine. In the
 * Intermarché file the upper levels are published {@code NATIONAL} and the
 * sous-familles {@code CUSTOM} — which is exactly what BO-02-01-01 means by
 * two points of sale holding different nomenclatures for one article: the top
 * is shared, the bottom is the shop's.
 */
@Entity
@Table(name = "nomenclature_levels",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"nomenclature_id", "level_rank"}))
public class NomenclatureLevel extends BaseEntity {

    /** The scheme this level belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "nomenclature_id")
    public Nomenclature nomenclature;

    /** The 0-based depth of this level, the top level being rank zero. */
    @Column(name = "level_rank", nullable = false)
    public int rank;

    /** The level's name as the enseigne uses it: Activité, Rayon, Famille… */
    @Column(name = "label", length = 60)
    public String label;

    /**
     * The CUMULATIVE length of a node code at this level — 2, 4, 8 then 12 in
     * the Intermarché scheme. A node code of this exact length sits here, and
     * truncating any deeper code to this length names its ancestor here.
     */
    @Column(name = "code_length", nullable = false)
    public int codeLength;

    /**
     * Whether a point of sale may redefine this level ({@code CUSTOM} in the
     * published file) or whether it is shared by every store
     * ({@code NATIONAL}).
     */
    @Column(name = "custom", nullable = false)
    public boolean custom = false;

    /**
     * Default constructor for JPA.
     */
    public NomenclatureLevel() {
    }

    /**
     * Lists the levels of a scheme, from the top down.
     *
     * @param nomenclature the scheme, possibly null
     * @return the levels ordered by rank, empty when the scheme is null
     */
    public static List<NomenclatureLevel> listFor(Nomenclature nomenclature) {
        if (nomenclature == null || nomenclature.id == null) {
            return List.of();
        }
        return list("nomenclature.id = ?1 order by rank", nomenclature.id);
    }

    /**
     * Finds one level of a scheme by its rank.
     *
     * @param nomenclature the scheme, possibly null
     * @param rank the 0-based rank
     * @return the level, or null when the scheme does not declare that rank
     */
    public static NomenclatureLevel findByRank(Nomenclature nomenclature, int rank) {
        if (nomenclature == null || nomenclature.id == null) {
            return null;
        }
        return find("nomenclature.id = ?1 and rank = ?2", nomenclature.id, rank).firstResult();
    }


    /**
     * The fingerprint input of this level for the referential export.
     *
     * @return a hash of the rank, label, cumulative length and custom flag
     */
    @Override
    public int getChecksum() {
        return Objects.hash(rank, label, codeLength, custom);
    }
}
