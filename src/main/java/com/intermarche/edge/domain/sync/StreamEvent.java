package com.intermarche.edge.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One AT-THE-FLOW event awaiting delivery to one subscriber (BO-11-02-02/03/04).
 *
 * <p>One row per (subscriber, event), not one row per event: two third parties
 * fail independently — the video system can be down for an hour while the
 * decisional collects fine — and a shared row would make the slowest subscriber
 * the cadence of all of them.
 *
 * <p>The PAYLOAD is frozen here, at enqueue time, and never rebuilt at delivery
 * time. That is the whole point of an at-the-flow stream: the third party is told
 * what happened then, not what the database looks like when the link finally comes
 * back. It also makes a delivery a pure write — no lazy relation to re-read, no
 * transaction to hold open while an external host thinks about it.
 *
 * <p>IDENTITY is (subscriber, kind, reference), so a register re-pushing the same
 * ticket twenty times leaves ONE pending row carrying the latest payload rather
 * than twenty rows the subscriber has to de-duplicate. A delivered row is deleted,
 * so the same ticket pushed again after a delivery is a new event, which is
 * correct: it changed.
 *
 * <p>Nothing is ever dropped — the requirement asks for recovery "without data
 * loss", so there is no attempt ceiling and no discard path. A row that cannot be
 * delivered keeps its place, counts its attempts and surfaces on the healthcheck
 * (BO-11-02-05); giving up would be the one failure mode the requirement forbids.
 */
@Entity
@Table(name = "stream_event",
        indexes = {
                @Index(name = "idx_stream_event_order", columnList = "delivered_at, id"),
                @Index(name = "idx_stream_event_uid", columnList = "event_uid", unique = true)
        })
public class StreamEvent extends PanacheEntity {

    /**
     * What an event carries.
     *
     * <p>Three kinds and not one, because the subscribers are not the same: a
     * monetics collector has no use for a parked cart, and a queue-management
     * system has none for a card slip.
     */
    public enum Kind {

        /**
         * A ticket in whatever state the node holds it — sale, parked cart or
         * cancelled cart (BO-11-02-02), with its printed image.
         */
        TICKET,

        /** A refund (BO-11-02-02). */
        REFUND,

        /** The monetics slip of a ticket settled by card (BO-11-02-03). */
        MONETICS,

        /**
         * The integration report of one e-commerce order returned to its
         * platform (BO-11-04-03), keyed by the order reference.
         */
        ECOMMERCE
    }

    /**
     * The unique identity of the event: subscriber, kind and reference joined.
     *
     * <p>Stored rather than derived so the database can enforce it: the upsert on
     * re-push depends on exactly one pending row existing per identity.
     */
    @Column(name = "event_uid", nullable = false, length = 120)
    public String eventUid;

    /** The code of the subscribed provider this row is for. */
    @Column(name = "provider_code", nullable = false, length = 30)
    public String providerCode;

    /** What the event carries. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    public Kind kind;

    /** The business reference the event is about: a ticket or refund number. */
    @Column(name = "reference", nullable = false, length = 40)
    public String reference;

    /** The frozen JSON body to POST, as it was at enqueue time. */
    @Lob
    @Column(name = "payload", nullable = false)
    public String payload;

    /** When the event was enqueued. */
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();

    /** Number of failed delivery attempts so far. */
    @Column(name = "attempts", nullable = false)
    public int attempts;

    /** The last delivery error, or null while none has happened. */
    @Column(name = "last_error", length = 255)
    public String lastError;

    /**
     * When the subscriber acknowledged it, or null while pending.
     *
     * <p>Set rather than relied upon: a delivered row is deleted right away, and
     * this column exists for the one case where it is not — a row kept for the
     * healthcheck to report the last successful delivery per subscriber.
     */
    @Column(name = "delivered_at")
    public LocalDateTime deliveredAt;

    /**
     * Builds the identity of an event.
     *
     * @param providerCode the subscriber's code
     * @param kind what the event carries
     * @param reference the ticket or refund number
     * @return the identity string
     */
    public static String uidOf(String providerCode, Kind kind, String reference) {
        return providerCode + "|" + kind.name() + "|" + reference;
    }

    /**
     * Finds the row of one identity, delivered or not.
     *
     * @param eventUid the identity, as {@link #uidOf} builds it
     * @return the row, or null when none carries that identity
     */
    public static StreamEvent findByUid(String eventUid) {
        return find("eventUid", eventUid).firstResult();
    }

    /**
     * Reads the next events awaiting delivery, oldest first.
     *
     * <p>Oldest first because a third party reading a transaction stream expects
     * it in the order the shop lived it, and an event overtaken by a newer one is
     * a stream nobody can reconcile.
     *
     * @param batchSize how many to take at most
     * @return the pending events, oldest first
     */
    public static List<StreamEvent> nextPending(int batchSize) {
        return find("deliveredAt is null order by id").page(0, batchSize).list();
    }

    /**
     * Counts the events awaiting delivery.
     *
     * @return the pending count, zero when the stream is caught up
     */
    public static long countPending() {
        return count("deliveredAt is null");
    }

    /**
     * Counts the events awaiting delivery for one subscriber.
     *
     * @param providerCode the subscriber's code
     * @return its pending count
     */
    public static long countPendingFor(String providerCode) {
        return count("providerCode = ?1 and deliveredAt is null", providerCode);
    }

    /**
     * Lists the subscribers that have something awaiting delivery.
     *
     * @return the pending events of every subscriber, oldest first
     */
    public static List<StreamEvent> listPending() {
        return list("deliveredAt is null order by providerCode, id");
    }
}
