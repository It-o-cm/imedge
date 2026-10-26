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
        /**
         * A closed ticket was REASSIGNED to a fidelity customer
         * (LC-03-06-01..06): the card, the till, the operator, the ticket
         * amount and the acquired advantages travel in the detail — the
         * reporting movement the questionnaire asks the system to record.
         */
        TICKET_REASSIGNED,
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
        BACK_OFFICE_SIGNED_IN,
        /**
         * The reports the shop programmed were edited automatically at the end of
         * a period (BO-09-01-10). Node-local, like the financial states: a
         * register has no back-office report to edit. The detail carries how many
         * editions were written, failures included — a state that was programmed
         * and did not come out is what an operator needs to find the next morning.
         */
        SCHEDULED_REPORTS_EDITED,
        /** Supervisor-authorised workstation control request (LC-13-03-04). */
        WORKSTATION_REQUEST,
        /** A dynamically identified configured key was used (LC-13-05-01). */
        TOUCH_USED,
        /**
         * The drawer was opened outside any transaction with the supervisor's
         * authorisation (LC-12-01-01); the detail carries the administered reason's
         * code and label when one was chosen (LC-12-01-02) and who authorised it.
         * Appended last: the type is stored by its rank.
         */
        DRAWER_FORCED_OPEN,
        /**
         * An administered return TYPE was selected in caisse (its label in the
         * detail, LC-05-04-01), so the store reports can tell a container return
         * from a defective-article one. Appended after
         * {@link #DRAWER_FORCED_OPEN} for the same reason: the type is stored by
         * its rank.
         */
        RETURN_TYPE_SELECTED,

        /**
         * A cashier declared the vignettes she handed out on her shift
         * (BO-11-01-20); the detail reads {@code session=<number>;count=<n>}.
         * Appended last: the type is stored by its rank.
         */
        VIGNETTES_DECLARED,
        // ---- lot 21: S event types (appended: the type is stored by its rank) ----
        /**
         * A self-scanning basket was put under a total control the hostess
         * decided at the till (BO-04-01-42); the operator is the hostess.
         */
        SELF_SCAN_CONTROLLED,
        /**
         * A self-scanning basket was put under a control the system drew or the
         * basket required (BO-04-01-43).
         */
        SELF_SCAN_VERIFIED,
        /** A code no article answers was scanned; {@link #code} carries it (BO-04-01-34). */
        UNKNOWN_ARTICLE_SCANNED,
        /**
         * One settlement was cancelled on a ticket; {@link #amount} and
         * {@link #code} (the payment method) carry it (BO-04-01-19).
         */
        PAYMENT_CANCELLED,
        /** The drawer received its opening pulse (BO-04-03-07). */
        DRAWER_OPENED,
        /**
         * A price modification was applied to a line or to the ticket — a
         * remise, a percentage discount or a forced price; {@link #code} names
         * the kind (BO-04-03-07).
         */
        PRICE_MODIFIED,
        /** An article line was cancelled (BO-04-03-07). */
        LINE_CANCELLED,
        /**
         * An administered control alert fired (BO-04-03-08/09/14); {@link #code}
         * names the rule's target and the detail its type and outcome.
         */
        CONTROL_ALERT,
        /**
         * A justification was chosen for a register function (BO-04-03-10/12/14);
         * {@link #code} carries the function and the detail the justification.
         */
        JUSTIFICATION_CHOSEN,
        /** A watched back-office menu was opened (BO-04-03-04); {@link #code} names it. */
        BACK_OFFICE_MENU_OPENED,
        /**
         * The back office handled a register request — acknowledged, authorised,
         * sent back to the till, refused, or a forced close (BO-04-03-14).
         */
        SUPERVISION_HANDLED,

        /**
         * A CASH OPERATION timed at the till — a count, a withdrawal, a cash
         * movement (BO-06-06-03): the detail carries the kind and the seconds
         * it took, {@code kind;seconds}, so the cashier state can tell the time
         * spent on the drawer from the idle time. Appended for the same reason
         * as the others: the type is stored by its rank.
         */
        CASH_OPERATION_TIMED
    }

    /** The detail of a lock the cashier made herself (BO-06-06-02). */
    public static final String LOCK_MANUAL = "MANUEL";

    /** The detail of the lock the idle delay triggered (BO-06-06-02). */
    public static final String LOCK_AUTOMATIC = "AUTO";

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

    /**
     * The number of the ticket the event is about, or null (BO-04-01-19/34):
     * a first-class column so the journal joins an event to its ticket.
     */
    @Column(name = "ticket_number", length = 40)
    public String ticketNumber;

    /** The amount the event bears, or null (BO-04-01-19, BO-04-03-07). */
    @Column(name = "amount", precision = 19, scale = 4)
    public java.math.BigDecimal amount;

    /**
     * A searchable code the event carries — a payment method, a scanned code,
     * a rule target, a menu — or null (BO-04-01-19/34, BO-04-03-04/08).
     */
    @Column(name = "event_code", length = 120)
    public String code;
}
