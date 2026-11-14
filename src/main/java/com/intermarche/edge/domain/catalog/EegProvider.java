package com.intermarche.edge.domain.catalog;

import com.intermarche.edge.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Objects;

/**
 * One ELECTRONIC SHELF LABEL provider of the point of sale (BO-02-03-37,
 * BO-02-03-38). A store may run several — one aisle bought from one supplier,
 * the fresh counters from another — and every price sent to the labels is
 * sent to each active provider.
 *
 * <p>{@link #kind} names the connector: {@code HTTP} speaks the provider's
 * REST API at {@link #baseUrl}; {@code SIMULATED} is the in-process stand-in
 * a demonstration or a test store runs without any label on its shelves.
 */
@Entity
@Table(name = "eeg_providers", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
public class EegProvider extends BaseEntity {

    /** The connector speaking a provider's REST API. */
    public static final String KIND_HTTP = "HTTP";

    /** The in-process connector of a store without labels. */
    public static final String KIND_SIMULATED = "SIMULATED";

    /** The provider code, unique. */
    @Column(name = "code", nullable = false, unique = true, length = 30)
    @NotBlank
    public String code;

    /** What the back office calls the provider. */
    @Column(name = "label", nullable = false, length = 80)
    @NotBlank
    public String label;

    /** The connector: {@link #KIND_HTTP} or {@link #KIND_SIMULATED}. */
    @Column(name = "kind", nullable = false, length = 20)
    public String kind = KIND_SIMULATED;

    /** The root URL of the provider's API, for an HTTP provider. */
    @Column(name = "base_url", length = 255)
    public String baseUrl;

    /** Whether the provider receives and answers the price flow. */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /**
     * Lists every provider, by code.
     *
     * @return the providers
     */
    public static List<EegProvider> listAllOrdered() {
        return list("order by code");
    }

    /**
     * Lists the providers the price flow reaches.
     *
     * @return the active providers, by code
     */
    public static List<EegProvider> listActive() {
        return list("active = true order by code");
    }

    /**
     * Finds a provider by its code.
     *
     * @param code the code, or null
     * @return the provider, or null
     */
    public static EegProvider findByCode(String code) {
        return code == null ? null : find("code", code).firstResult();
    }

    /**
     * Hashes the business fields, for the change detection of the base entity.
     *
     * @return the checksum of this row
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, label, kind, baseUrl, active);
    }
}
