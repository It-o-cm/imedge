package com.intermarche.edge.domain.session;

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
         * An article's price was CHECKED at the register (BO-04-01-38): a
         * cashier or a customer asked the article-info screen what an article
         * costs, without selling anything.
         *
         * <p>Journalised because it is the one gesture that leaves no ticket
         * and that a shop still wants to find afterwards — a price disputed at
         * the till is answered by what the register displayed, and that is
         * nowhere else.
         */
        PRICE_CHECKED,
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
         * A back-office administrator forced an immediate at-the-flow delivery pass
         * from the sync supervision screen (BO-11-02-05), outside the cadence. The
         * detail carries how many events were acknowledged, which is what makes the
         * recovery auditable: an operator who pressed the button must be able to
         * show afterwards whether it emptied the backlog.
         */
        STREAM_DELIVERY_FORCED,
        /**
         * A GS1 code was decoded at the till, and every identifier it carried is
         * written in {@link #detail} — those the register acted on and those it has
         * no rule for alike (LC-11-03-02). It is the technical half of that
         * requirement; the transactional half is carried by the ticket line.
         */
        GS1_DECODED,
        /**
         * A PAID ticket was cancelled (EXP-49): an already-CLOSED sale recalled and
         * reversed under a supervisor endorsement. The original ticket is left
         * untouched; the reversal is a second document. The detail carries the
         * original ticket number and the endorsing supervisor.
         */
        PAID_TICKET_CANCELLED,
        /**
         * A SOGEC manufacturer coupon was settled through the compensation circuit
         * (EXP-58): the detail carries the bon code and the amount, for the supplier
         * reimbursement — distinct from the generic GS1 coupon which grants an
         * immediate discount and expects no reimbursement.
         */
        SOGEC_COMPENSATION,
        /**
         * A financial-state Z document was emitted on the node (X/Z campaign, §6):
         * node-local, like {@code STREAM_DELIVERY_FORCED}, since the X/Z states are
         * a store-node feature the registers do not carry.
         */
        FINANCIAL_STATE_EMITTED,
        /**
         * A financial-state X reading was consulted (X/Z campaign, §1, §6): the X is
         * never persisted, so its only trace is this journal entry.
         */
        FINANCIAL_STATE_READ,
        /**
         * An operator signed in to the BACK OFFICE and the credentials were
         * accepted (BO-04-03-04). Node-local, like the financial states: the
         * registers have no back office to sign in to. The operator is named by
         * {@link #operatorBadgeId} and the login name travels in
         * {@link #detail}; the password never appears, not even truncated.
         *
         * <p>Only a sign-in that VERIFIED a password is recorded. A session
         * cookie replayed on every later request re-checks the account's state
         * without a secret, and journalising that would write one line per page
         * view instead of one line per arrival.
         */
        BACK_OFFICE_SIGNED_IN
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
