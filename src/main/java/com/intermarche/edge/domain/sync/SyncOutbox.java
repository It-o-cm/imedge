package com.intermarche.edge.domain.sync;

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
        CUSTOMER,
        /**
         * A document issued at the register — invoice or delivery note
         * (BO-02-04-19). It references its TICKET, which the store node
         * resolves by number; the ticket is pushed before it because TICKET
         * comes earlier in this enumeration, and the drain follows the
         * ordinal.
         */
        DOCUMENT,
        /**
         * A ticket the register printed, in its 42-column format (LC-08-02-02).
         * It references nothing the node must resolve first.
         */
        PRINTOUT,
        /**
         * A sale made in training mode (LC-13-05-06), kept apart from the
         * tickets so no revenue or accounting flow ever reads it.
         */
        TRAINING,
        /**
         * The signed trace of a ticket put on hold, resumed or purged
         * (LC-04-01-03). It references nothing the node must resolve first.
         */
        PARKING,
        /**
         * An instrument the register printed, at issue and at every change of
         * its balance (BO-03-06-62, -48). It references nothing the node must
         * resolve first.
         */
        STORED_VALUE,
        /**
         * A payment barcode read at the till, known or not (BO-03-06-63). It
         * references nothing the node must resolve first.
         */
        SCANNED_BARCODE
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
