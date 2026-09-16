package com.intermarche.pos.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Outbox of the store synchronization (phase 5): one row per entity awaiting
 * push to the store node, written in the same transaction as the business
 * event (ticket closing, session lifecycle, refund creation, cash movement)
 * so nothing is ever lost. The background push loop drains it in type order
 * (sessions first, then movements, then tickets, then refunds, events last)
 * and deletes each row once the store node acknowledged it; failures stay in
 * place with their error and are retried on the next cycle.
 * <p>
 * {@code attempts} and {@code lastError} make a stuck item observable with
 * one query on the register (poison items keep accumulating attempts and
 * never block the rest of the batch); an empty table means "fully
 * synchronized". Enqueueing is a no-op without a configured store URL, so
 * a standalone register and the store node itself never accumulate rows.
 */
@Entity
@Table(name = "sync_outbox",
        indexes = @Index(name = "idx_sync_outbox_order", columnList = "entity_type, id")
)
public class SyncOutbox extends PanacheEntity {

    /** Kinds of synchronized entities; the ordinal drives the drain order. */
    public enum EntityType {
        /** A cash session (pushed first: tickets and movements reference it). */
        SESSION,
        /**
         * A cash movement (pushed right after sessions): it references its
         * session and NOTHING else, so it drains as soon as that sole
         * dependency is guaranteed present, ahead of the tickets and refunds it
         * is independent of.
         */
        MOVEMENT,
        /** A closed or cancelled ticket. */
        TICKET,
        /** A refund (it references a ticket). */
        REFUND,
        /** A technical journal event (pushed last). */
        EVENT,
        /**
         * An account customer created at the register (LC-08-04-09). It
         * references NOTHING, so its rank in the drain order is free; last is
         * where a new kind belongs, since the order of everything before it is
         * a contract the store node relies on.
         */
        CUSTOMER
    }

    /** The kind of entity to push. */
    @Column(name = "entity_type", nullable = false, length = 10)
    public EntityType entityType;

    /** The local database id of the entity to push. */
    @Column(name = "entity_id", nullable = false)
    public Long entityId;

    /** When the row was enqueued. */
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    /** Number of failed push attempts so far. */
    @Column(name = "attempts", nullable = false)
    public int attempts;

    /** The last push error, or null. */
    @Column(name = "last_error", length = 255)
    public String lastError;
}
