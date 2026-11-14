package com.intermarche.edge.domain.catalog;

import com.intermarche.edge.domain.BaseEntity;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Objects;

/**
 * One UNIT OF MEASURE of the referential (BO-02-03-17): the code the Gestion
 * Commerciale writes on an article, declared in the back office with what a
 * customer reads on the documents.
 *
 * <p>The article feed carries a CODE ({@code 1}, {@code 2}, {@code B}…) and
 * not a word, because the code is what the commercial management holds and
 * the word belongs to the shop's documents. This table is the dictionary
 * between the two: the store node resolves the code of an incoming article
 * into the symbol its tickets and invoices print, and that symbol travels to
 * the registers with the article ({@code Product#unitName}), so a register
 * never needs the table itself.
 *
 * <p>Up to {@link #MAX_UNITS} units, the ninety-nine the requirement asks for;
 * {@link #decimalAllowed} says whether a quantity in that unit may carry
 * decimals, which is what the register's decimal-quantity entry respects.
 */
@Entity
@Table(name = "measure_units", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
@Cacheable
public class MeasureUnit extends BaseEntity {

    /** The largest number of units the referential holds (BO-02-03-17). */
    public static final int MAX_UNITS = 99;

    /** The unit code the Gestion Commerciale writes, one or two characters. */
    @Column(name = "code", nullable = false, unique = true, length = 2)
    @NotBlank
    public String code;

    /** What the back office calls the unit. */
    @Column(name = "label", nullable = false, length = 40)
    @NotBlank
    public String label;

    /** What the documents print beside a quantity: kg, l, m2… */
    @Column(name = "symbol", nullable = false, length = 20)
    @NotBlank
    public String symbol;

    /** Whether a quantity in this unit may carry decimals. */
    @Column(name = "decimal_allowed", nullable = false)
    public boolean decimalAllowed;

    /** Whether the unit may be given to an article; deactivated, never deleted. */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /**
     * The units the questionnaire lists for Intermarché (BO-02-03-17), offered
     * as the starting referential of a store that declared none: code, label,
     * symbol and whether decimals are allowed.
     */
    public static final List<String[]> STANDARD = List.of(
            new String[]{"1", "Pièce", "pce", "false"},
            new String[]{"2", "Kilogramme", "kg", "true"},
            new String[]{"3", "Litre", "l", "true"},
            new String[]{"4", "Mètre", "m", "true"},
            new String[]{"5", "Mètre carré", "m2", "true"},
            new String[]{"6", "Mètre cube", "m3", "true"},
            new String[]{"7", "Douzaine", "dz", "false"},
            new String[]{"8", "Millilitre", "ml", "true"},
            new String[]{"9", "Gramme", "g", "true"},
            new String[]{"B", "Mètre bobine", "mb", "true"});

    /**
     * Finds a unit by its code, ignoring surrounding blanks and case.
     *
     * @param code the unit code, or null
     * @return the unit, or null when the code is blank or unknown
     */
    public static MeasureUnit findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return find("code", code.trim().toUpperCase(java.util.Locale.ROOT)).firstResult();
    }

    /**
     * Lists every unit, ordered by code.
     *
     * @return the units
     */
    public static List<MeasureUnit> listAllOrdered() {
        return list("order by code");
    }

    /**
     * Lists the units an article may be given, ordered by code.
     *
     * @return the active units
     */
    public static List<MeasureUnit> listActive() {
        return list("active = true order by code");
    }

    /**
     * Hashes the business fields, for the change detection of the base entity.
     *
     * @return the checksum of this row
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, label, symbol, decimalAllowed, active);
    }
}
