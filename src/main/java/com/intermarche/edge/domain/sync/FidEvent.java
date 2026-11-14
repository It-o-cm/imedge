package com.intermarche.edge.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox row of a loyalty FISCAL EVENT bound for imfid (integration spec
 * §6-7): {@code ticket-closed} and {@code ticket-return}. The payload is
 * COMPOSED AND FROZEN at enqueue time (it embeds the verbatim /valuation
 * couple that only exists in memory at the fiscal moment), then drained by
 * the scheduler. imfid answers 202 and is idempotent by ticket reference:
 * rows can be retried freely, in any order (spec §1.4 — a return arriving
 * before its origin is parked and replayed by imfid itself).
 */
@Entity
@Table(name = "fid_events")
public class FidEvent extends PanacheEntity {

    /** The event kind, selecting the target endpoint. */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 20, nullable = false)
    public EventType eventType;

    /** The frozen JSON body, posted as-is. */
    @Lob
    @Column(name = "payload_json", nullable = false)
    public String payloadJson;

    /** When the event was enqueued. */
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    /** When imfid accepted it (202), or null while pending. */
    @Column(name = "sent_at")
    public LocalDateTime sentAt;

    /** Delivery attempts so far. */
    @Column(name = "attempts", nullable = false)
    public int attempts = 0;

    /** The last delivery error, for diagnosis. */
    @Column(name = "last_error", length = 500)
    public String lastError;

    /** The loyalty event kinds. */
    public enum EventType {
        /** The real credit: a fiscally closed ticket (spec §6). */
        TICKET_CLOSED,
        /** A closed return referencing its origin (spec §7). */
        TICKET_RETURN
    }

    /**
     * Lists the pending events, oldest first.
     *
     * @return the events not yet accepted by imfid
     */
    public static List<FidEvent> findPending() {
        return list("sentAt is null order by id");
    }
}
