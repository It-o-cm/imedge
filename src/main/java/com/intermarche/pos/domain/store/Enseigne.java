package com.intermarche.pos.domain.store;

import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Objects;
import com.intermarche.pos.domain.BaseEntity;

/**
 * An enseigne echelon (e.g. ITM France, Netto), between {@link Country} and
 * {@link Pdv} in the organisation tree (BO-02-05-04). Its {@link #code} is the
 * two-letter FID enseigne code the loyalty setting reuses (BO-10-03-01: IF, NF,
 * BF, IB, IP&hellip;), and it points UP to its country by {@link #countryCode}
 * rather than by object reference so the resolution stays a cheap key walk.
 * <p>
 * A point of vente inherits an enseigne's default parameters unless it
 * overrides them locally; an enseigne inherits its country's. Changing a PDV's
 * {@link Pdv#enseigneCode} re-parents it under a new enseigne with the new
 * inheritance in force (BO-02-05-02) — no field on this entity changes for
 * that, the link lives on the PDV side.
 * <p>
 * Held on the CENTRAL node, part of the served referential (route A).
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "enseignes",
        indexes = {
                @Index(name = "idx_enseigne_country", columnList = "country_code")
        }
)
@Cacheable
public class Enseigne extends BaseEntity {

    /**
     * The enseigne code, unique across the organisation (e.g. "IF" for ITM
     * France). The natural key echelon rows and PDVs point to.
     */
    @Column(name = "code", unique = true, nullable = false, length = 8)
    @NotBlank(message = "Enseigne code is mandatory")
    public String code;

    /** The enseigne's display name (e.g. "Intermarché France"). */
    @Column(name = "name", nullable = false, length = 120)
    @NotBlank(message = "Enseigne name is mandatory")
    public String name;

    /**
     * The code of the {@link Country} this enseigne belongs to, or null when
     * not yet attached to a country. Held by code, not reference.
     */
    @Column(name = "country_code", length = 8)
    public String countryCode;

    /**
     * The reference language of the echelon (e.g. "fr"), or null to inherit
     * the country's (BO-01-01-04).
     */
    @Column(name = "default_language", length = 8)
    public String defaultLanguage;

    /**
     * Finds an enseigne by its unique code.
     *
     * @param code the enseigne code
     * @return the enseigne, or null when none carries that code
     */
    public static Enseigne findByCode(String code) {
        return find("code", code).firstResult();
    }

    /**
     * Lists the enseignes attached to a country, ordered by code.
     *
     * @param countryCode the country code
     * @return the enseignes of that country, never null
     */
    public static List<Enseigne> listByCountry(String countryCode) {
        return list("countryCode = ?1 order by code", countryCode);
    }

    /**
     * Lists every enseigne, ordered by code for a stable display.
     *
     * @return the enseignes, never null
     */
    public static List<Enseigne> listAllOrdered() {
        return list("order by code");
    }

    /**
     * Hashes the business fields for change detection.
     *
     * @return the checksum of the enseigne
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, name, countryCode, defaultLanguage);
    }
}
