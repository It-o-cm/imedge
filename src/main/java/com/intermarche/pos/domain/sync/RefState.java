package com.intermarche.pos.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Last applied referential fingerprint per domain on a register (phase 6
 * lot 3). The store node computes a fingerprint per referential; a register
 * pulls a domain only when the remote fingerprint differs from the one
 * recorded here, and records the remote value after a successful apply.
 * <p>
 * Absence of a row IS the bootstrap signal: a fresh register has recorded
 * nothing, every remote fingerprint differs, and the first pull cycle
 * (15 seconds after boot) fills the whole referential unaided.
 */
@Entity
@Table(name = "ref_states")
public class RefState extends PanacheEntity {

    /** The referential domain (FAMILIES, PRODUCTS, PRICES, EMPLOYEES, COUPON_TYPES). */
    @Column(name = "domain", nullable = false, unique = true, length = 20)
    public String domain;

    /** The fingerprint of the last successfully applied snapshot. */
    @Column(name = "fingerprint", nullable = false, length = 64)
    public String fingerprint;

    /** When the snapshot was applied. */
    @Column(name = "applied_at", nullable = false)
    public LocalDateTime appliedAt;
}
