package com.intermarche.pos.domain.session;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A technical event journal entry (NF525 direction): ticket closures and
 * cancellations, duplicata printing, payment resets, draft recoveries.
 * <p>
 * One row per event, stamped with the register that produced it. Extends
 * {@link PanacheEntity} directly (pure append-only journal, no audit fields).
 * <p>
 * Every log entry is enqueued for store synchronization (EVENT is the last
 * drained kind, after sessions, tickets and refunds) and upserted on the
 * store node by {@link #eventUid} — a UUID minted at creation precisely
 * because journal entries have no natural business number; re-pushing is
 * therefore idempotent. On the store node itself the enqueue is a no-op (no
 * store URL configured), so ingestion cannot loop.
 */
@Entity
@Table(name = "technical_events",
        indexes = {
                @Index(name = "idx_tech_event_terminal", columnList = "terminal_id, event_date"),
                @Index(name = "idx_tech_event_type", columnList = "event_type")
        }
)
public class TechnicalEvent extends PanacheEntity {

    /** Event types recorded in the journal. */
    public enum EventType {
        /** A ticket was closed (validated). */
        TICKET_CLOSED,
        /** A draft was cancelled (abandoned cart). */
        TICKET_CANCELLED,
        /** A duplicata of an already-printed ticket was printed. */
        DUPLICATA_PRINTED,
        /** The payments of a draft were cleared. */
        PAYMENTS_CLEARED,
        /** An in-progress draft was recovered at register startup. */
        DRAFT_RECOVERED,
        /** The cashier confirmed a customer ID check for a restricted product. */
        AGE_CHECK_CONFIRMED,
        /** The cashier refused the sale of an age-restricted product. */
        AGE_CHECK_REFUSED,
        /** A cash session was opened with its initial float. */
        SESSION_OPENED,
        /** A cash session was closed by a Z report (count, variance, withdrawal). */
        SESSION_CLOSED,
        /** An X report (read-only session snapshot) was printed. */
        X_REPORT_PRINTED,
        /** A manager endorsement was granted. */
        ENDORSEMENT_GRANTED,
        /** A manager endorsement was refused. */
        ENDORSEMENT_DENIED,
        /** An account was locked after repeated PIN failures. */
        AUTH_LOCKED,
        /** The current cart was parked, waiting to be resumed. */
        TICKET_PARKED,
        /** A parked cart was resumed on this register. */
        TICKET_RESUMED,
        /** A refund was created (method and amount in the detail). */
        REFUND_CREATED,
        /** The digital receipt was sent to a customer email. */
        DIGITAL_TICKET_SENT,
        /** The cashier called a supervisor (reason in the detail). */
        SUPERVISOR_CALLED,
        /** The register entered training mode. */
        TRAINING_STARTED,
        /** The register left training mode. */
        TRAINING_ENDED,
        /**
         * The register entered lock mode (automatic idle pause): the operator
         * that was working it stepped away and the idle timeout paused the
         * register. The paused operator is named by {@link #operatorBadgeId},
         * never by a secret.
         */
        REGISTER_LOCKED,
        /**
         * The register left lock mode: an operator successfully unlocked it and
         * resumed selling. The resuming operator is named by
         * {@link #operatorBadgeId}, never by a secret.
         */
        REGISTER_UNLOCKED,
        /**
         * An operator changed a password — the register PIN or the back-office
         * password. The operator is named by {@link #operatorBadgeId}, never by
         * the old or the new secret, not even truncated.
         */
        PASSWORD_CHANGED,
        /**
         * A wrong password was entered against a known account (a bad register
         * PIN). The account is named by {@link #operatorBadgeId}, never by the
         * attempted secret, not even truncated.
         */
        PASSWORD_FAILED,
        /**
         * A back-office administrator forced an immediate referential pull from
         * the sync supervision screen (BO-08-04-09/10), outside the scheduled
         * cadence. The event has no operator badge: it is a machine action
         * triggered from an authenticated back-office session.
         */
        REFERENTIAL_PULL_FORCED,
        /**
         * A back-office administrator forced an immediate engine-feed delivery
         * from the sync supervision screen (BO-08-04-13), outside the scheduled
         * cadence. Like the pull, a machine action with no operator badge.
         */
        ENGINE_FEED_DELIVERY_FORCED,
        /**
         * A GS1 code was decoded at the till, and every identifier it carried is
         * written in {@link #detail} — those the register acted on and those it has
         * no rule for alike (LC-11-03-02). It is the technical half of that
         * requirement; the transactional half is carried by the ticket line.
         */
        GS1_DECODED,
        /**
         * A supervisor force-unlocked a register whose operator forgot their
         * password: the operator keeps the session and the drawer, a password
         * change is imposed on resume (EXP-14). {@link #operatorBadgeId} names the
         * supervisor. Appended at the end so the ordinal wire stays stable —
         * carry this value across to impos on the register side.
         */
        REGISTER_UNLOCK_FORCED,
        /**
         * A supervisor forced the end of day of a register left locked and
         * abandoned — distinct from a normal close: nobody counted the drawer
         * (EXP-96). {@link #operatorBadgeId} names the supervisor.
         */
        SESSION_FORCE_CLOSED,
        /**
         * The cashier CLOSED their till without clôturing it — removed the drawer
         * for a break or to carry it to another register (the retail « fermer sa
         * caisse », distinct from the short lock and from the Z close, EXP §3).
         * The session and its float survive.
         */
        TILL_CLOSED,
        /**
         * The cashier REOPENED a till closed earlier the same period — the
         * continuity of the morning: same session, same float, no new count
         * (EXP §3).
         */
        TILL_REOPENED,
        /**
         * A supervisor session was opened over a ticket: one endorsement covering
         * every negative gesture until it closes (EXP-33). {@link #operatorBadgeId}
         * names the supervisor.
         */
        SUPERVISOR_SESSION_OPENED,
        /**
         * A supervisor session closed — by the explicit FIN SUPERVISEUR key or by
         * the end of the ticket, said in {@link #detail} (EXP-33).
         */
        SUPERVISOR_SESSION_CLOSED,
        /**
         * The drawer content crossed its administered ceiling, and the register
         * showed the PRÉLÈVEMENT alert without blocking the sale (EXP-17).
         */
        DRAWER_CEILING_REACHED,
        /**
         * A supervisor validated a cash discrepancy above the administered
         * threshold so the closing could pass (EXP-82). {@link #operatorBadgeId}
         * names the supervisor and {@link #detail} carries the validated amount —
         * this is the nominative trace the requirement demands.
         */
        DISCREPANCY_VALIDATED,
        /**
         * The register refused to cancel the last article because it was the ONLY
         * one on the ticket — a refusal is journaled too, since it is often what
         * explains a complaint (EXP-35).
         */
        LAST_ITEM_CANCEL_REFUSED
    }

    /** The type of the event. */
    @Column(name = "event_type", nullable = false, length = 30)
    public EventType eventType;

    /** The identifier of the register that produced the event. */
    @Column(name = "terminal_id", nullable = false, length = 20)
    public String terminalId;

    /** The timestamp of the event. */
    @Column(name = "event_date", nullable = false)
    public LocalDateTime eventDate;

    /** A short human-readable detail (ticket number, count...). */
    @Column(name = "detail", length = 255)
    public String detail;

    /**
     * The badge (N° caissière) of the operator the event is about, or null for
     * an event that has no operator.
     * <p>
     * A first-class column rather than a mention inside {@link #detail}: the
     * journal searches operator ranges (BO-04-01-27..30), and a range compared
     * against free text would match any detail that happens to sort between the
     * bounds. Null never matches a range, so an event without an operator is
     * excluded rather than wrongly returned. Carried by the store push like the
     * rest of the event — it describes the event, not this register's state.
     */
    @Column(name = "operator_badge_id", length = 20)
    public String operatorBadgeId;

    /** Stable identity of the event across nodes (store-sync upsert key). */
    @Column(name = "event_uid", unique = true, length = 36)
    public String eventUid;
}
