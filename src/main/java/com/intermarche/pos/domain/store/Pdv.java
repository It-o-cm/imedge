package com.intermarche.pos.domain.store;

import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.util.List;
import java.util.Objects;
import com.intermarche.pos.domain.BaseEntity;

/**
 * The CENTRAL registry of a point de vente (BO-02-05-01, BO-02-05-05): its
 * globally-unique five-digit number, its attachment to an {@link Enseigne} and
 * to an adhérent. This is the central node's view of a PDV, distinct from the
 * node-local {@link Store} row a register or a store node carries: a central
 * knows every PDV of the organisation, a store node knows only itself.
 * <p>
 * The number is exactly five digits and unique across all countries and
 * enseignes ({@link #isValidNumber}); the database unique constraint is the
 * last guard, {@link #isValidNumber} the first. It stays modifiable centrally
 * (BO-02-05-01 "son numéro doit pouvoir être modifié") while remaining "non
 * modifiable par le Point De Vente" (BO-03-12-06) — the local {@code /admin/store}
 * screen renders it read-only, only this central registry writes it.
 * <p>
 * The enseigne link is held by {@link #enseigneCode}, so re-parenting a PDV
 * under a new enseigne (BO-02-05-02) is a single field change that
 * immediately switches which echelon defaults it inherits. The
 * {@link #adherentCode} groups several PDVs under one adhérent (BO-02-05-05)
 * without adding an echelon: adhérent is a grouping key, not a settings level.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "pdvs",
        indexes = {
                @Index(name = "idx_pdv_enseigne", columnList = "enseigne_code"),
                @Index(name = "idx_pdv_adherent", columnList = "adherent_code")
        }
)
@Cacheable
public class Pdv extends BaseEntity {

    /** The exact length, in digits, of a valid PDV number (BO-02-05-01). */
    public static final int NUMBER_LENGTH = 5;

    /**
     * The five-digit point-of-vente number, unique across the whole
     * organisation (BO-02-05-01). Stored as text to preserve leading zeros.
     */
    @Column(name = "pdv_number", unique = true, nullable = false, length = 5)
    public String pdvNumber;

    /** The PDV's display name (e.g. "Intermarché Lyon Part-Dieu"). */
    @Column(name = "name", nullable = false, length = 120)
    public String name;

    /**
     * The code of the {@link Enseigne} this PDV is attached to, or null when
     * not yet attached. Re-parenting is a change of this single field
     * (BO-02-05-02).
     */
    @Column(name = "enseigne_code", length = 8)
    public String enseigneCode;

    /**
     * The code of the adhérent that operates this PDV (BO-02-05-05), or null
     * when none is set. Several PDVs may share it — it is a grouping key, not
     * an echelon.
     */
    @Column(name = "adherent_code", length = 32)
    public String adherentCode;

    /** Whether the PDV is active; a decommissioned PDV is deactivated, not deleted. */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /**
     * Tells whether a candidate PDV number is well-formed: exactly
     * {@link #NUMBER_LENGTH} ASCII digits (BO-02-05-01). Null, wrong length or
     * any non-digit character fails.
     *
     * @param candidate the number to validate, possibly null
     * @return true when the number is exactly five digits
     */
    public static boolean isValidNumber(String candidate) {
        if (candidate == null || candidate.length() != NUMBER_LENGTH) {
            return false;
        }
        for (int i = 0; i < candidate.length(); i++) {
            if (!Character.isDigit(candidate.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finds a PDV by its unique number.
     *
     * @param pdvNumber the five-digit number
     * @return the PDV, or null when none carries that number
     */
    public static Pdv findByNumber(String pdvNumber) {
        return find("pdvNumber", pdvNumber).firstResult();
    }

    /**
     * Lists the PDVs attached to an enseigne, ordered by number.
     *
     * @param enseigneCode the enseigne code
     * @return the PDVs of that enseigne, never null
     */
    public static List<Pdv> listByEnseigne(String enseigneCode) {
        return list("enseigneCode = ?1 order by pdvNumber", enseigneCode);
    }

    /**
     * Lists the PDVs operated by an adhérent, ordered by number — the
     * grouping-by-adhérent view (BO-02-05-05).
     *
     * @param adherentCode the adhérent code
     * @return the PDVs of that adhérent, never null
     */
    public static List<Pdv> listByAdherent(String adherentCode) {
        return list("adherentCode = ?1 order by pdvNumber", adherentCode);
    }

    /**
     * Lists every PDV, ordered by number for a stable display.
     *
     * @return the PDVs, never null
     */
    public static List<Pdv> listAllOrdered() {
        return list("order by pdvNumber");
    }

    /**
     * Hashes the business fields for change detection.
     *
     * @return the checksum of the PDV
     */
    @Override
    public int getChecksum() {
        return Objects.hash(pdvNumber, name, enseigneCode, adherentCode, active);
    }
}
