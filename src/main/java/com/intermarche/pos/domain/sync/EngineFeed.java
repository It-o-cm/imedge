package com.intermarche.pos.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One VERBATIM feed file retained for the attached valuation engine: the
 * raw CSV exactly as it entered the POS chain (central import on the store
 * node, referential pull on a register). The POS never interprets these
 * contents for the engine — it stores the sealed parcel, ships it down the
 * chain and delivers it to its local imvaluation, which parses the shared
 * header-named format itself.
 * <p>
 * One row per feed code (the last received file wins). {@code version} is
 * the SHA-256 of the content and doubles as the change detector at every
 * hop; {@code appliedVersion} is the last version the local engine
 * ACKNOWLEDGED — while the two differ, the delivery loop keeps retrying,
 * and the pair is the "engine sync state" surfaced to supervision.
 */
@Entity
@Table(name = "engine_feeds")
public class EngineFeed extends PanacheEntity {

    /** The feed code (PRODUCTS, PRICES, FAMILIES, STORES, OFFERS…). */
    @Column(name = "code", nullable = false, unique = true, length = 40)
    public String code;

    /** The raw CSV content, verbatim (header line included). */
    @Lob
    @Column(name = "content", nullable = false)
    public String content;

    /** SHA-256 of the content, hex — the version of this parcel. */
    @Column(name = "version", nullable = false, length = 64)
    public String version;

    /** When this version reached this node. */
    @Column(name = "received_at", nullable = false)
    public LocalDateTime receivedAt;

    /** The last version the local engine acknowledged, or null. */
    @Column(name = "applied_version", length = 64)
    public String appliedVersion;

    /** When the engine acknowledged {@code appliedVersion}. */
    @Column(name = "applied_at")
    public LocalDateTime appliedAt;

    /** The last delivery error toward the engine, or null when clean. */
    @Column(name = "last_error", length = 500)
    public String lastError;

    /**
     * Finds a feed by its code.
     *
     * @param code the feed code
     * @return the feed row, or null when this node never received it
     */
    public static EngineFeed findByCode(String code) {
        return find("code", code).firstResult();
    }
}
