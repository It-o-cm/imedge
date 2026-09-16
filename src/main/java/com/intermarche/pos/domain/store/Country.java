package com.intermarche.pos.domain.store;

import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Objects;
import com.intermarche.pos.domain.BaseEntity;

/**
 * A country echelon (Pays), the top of the organisation tree the AO describes
 * (BO-02-05-04). It carries the defaults an enseigne — and through it, a point
 * of vente — inherits when nothing more specific overrides them: the default
 * language and, through {@link EchelonSetting} rows at
 * {@link EchelonLevel#COUNTRY}, back-office parameters.
 * <p>
 * Held on the CENTRAL node (route A, {@code pos.role=central}): it is part of
 * the referential the central serves down the two-level pull, unlike the
 * node-local {@link Store} row which stays outside the pull.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "countries")
@Cacheable
public class Country extends BaseEntity {

    /**
     * The country code, unique across the whole organisation (e.g. "FR").
     * The natural key the echelon rows and the pull payloads carry.
     */
    @Column(name = "code", unique = true, nullable = false, length = 8)
    @NotBlank(message = "Country code is mandatory")
    public String code;

    /** The country's display name (e.g. "France"). */
    @Column(name = "name", nullable = false, length = 120)
    @NotBlank(message = "Country name is mandatory")
    public String name;

    /**
     * The reference language of the echelon (e.g. "fr"), applied to a user
     * attached below it when no more specific preference is set
     * (BO-01-01-04), or null when not administered.
     */
    @Column(name = "default_language", length = 8)
    public String defaultLanguage;

    /**
     * Finds a country by its unique code.
     *
     * @param code the country code
     * @return the country, or null when none carries that code
     */
    public static Country findByCode(String code) {
        return find("code", code).firstResult();
    }

    /**
     * Lists every country, ordered by code for a stable display.
     *
     * @return the countries, never null
     */
    public static List<Country> listAllOrdered() {
        return list("order by code");
    }

    /**
     * Hashes the business fields for change detection.
     *
     * @return the checksum of the country
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, name, defaultLanguage);
    }
}
