package com.intermarche.edge.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A DEFERRED engine-feed delivery booked from the sync supervision screen
 * (GB-01-01-02): "deliver, at this minute, this feed (or every feed)". It is
 * not a second delivery mechanism — the existing delivery loop runs it when it
 * next wakes and finds the row due; this entity only records the intent and the
 * outcome.
 * <p>
 * {@code feedCode} null means EVERY feed (the global delivery variant); a code
 * means that feed alone (the targeted variant). The schedule is due when
 * {@code scheduledAt} has passed; precision is the minute, which is what the
 * screen offers and all offer diffusion needs — this is not trading.
 * <p>
 * A row lives {@code PENDING} until the loop runs it, then becomes {@code DONE}
 * or {@code FAILED} (with a {@code message}). A {@code PENDING} row may be
 * cancelled (deleted) from the screen; once run it is a historical record and
 * cancellation no longer applies.
 */
@Entity
@Table(name = "scheduled_deliveries")
public class ScheduledDelivery extends PanacheEntity {

    /** Lifecycle state of a booked delivery. */
    public enum Status {
        /** Booked, not yet run by the delivery loop. */
        PENDING,
        /** Run by the delivery loop without a surfaced error. */
        DONE,
        /** Run by the delivery loop and failed; the reason is in {@code message}. */
        FAILED
    }

    /** The feed code to deliver, or null to deliver every feed. */
    @Column(name = "feed_code", length = 40)
    public String feedCode;

    /** The minute at or after which the loop should run this delivery. */
    @Column(name = "scheduled_at", nullable = false)
    public LocalDateTime scheduledAt;

    /** The login of the administrator who booked the delivery. */
    @Column(name = "created_by", length = 60)
    public String createdBy;

    /** When the delivery was booked. */
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    /** The lifecycle state (PENDING at creation). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    public Status status;

    /** The failure reason when {@code FAILED}, otherwise null. */
    @Column(name = "message", length = 500)
    public String message;

    /**
     * Returns the PENDING schedules whose minute has passed, oldest first —
     * the rows the delivery loop must run on its next wake.
     *
     * @param now the current instant
     * @return the due PENDING schedules, oldest first, never null
     */
    public static List<ScheduledDelivery> findDuePending(LocalDateTime now) {
        return list("status = ?1 and scheduledAt <= ?2 order by scheduledAt",
                Status.PENDING, now);
    }

    /**
     * Returns every schedule, most recent booking first — the "Planifications"
     * section of the supervision screen.
     *
     * @return all schedules, newest first, never null
     */
    public static List<ScheduledDelivery> listRecent() {
        return list("order by scheduledAt desc");
    }
}
