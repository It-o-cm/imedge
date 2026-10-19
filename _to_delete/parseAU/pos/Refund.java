package com.intermarche.pos.domain.sale;

import com.intermarche.pos.domain.BaseEntity;
import com.intermarche.pos.domain.session.CashSession;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A customer refund against a closed ticket.
 * <p>
 * Phase 3 lot 4: the refund method is persisted, the VAT of the refunded
 * lines is restituted (tax-excluded and VAT totals), the refund is attached
 * to the cash session that performed it (cash refunds lower the theoretical
 * drawer amount), and the document is closed at creation.
 * <p>
 * Creation path: the four method buttons of the return screen request a
 * manager endorsement ("REFUND_&lt;METHOD&gt;_&lt;ticketId&gt;"); the
 * endorsement dispatch performs the refund transactionally. Two guards are
 * re-checked inside that transaction: per original line, the refunded
 * quantities summed across every past refund never exceed the sold quantity;
 * per ticket, past refunds plus the new one never exceed the ticket total
 * (this second cap also covers keyed-in free amounts). A guard refusal rolls
 * the transaction back with the message on the refund screen.
 * <p>
 * Method side effects: CASH opens the drawer and lowers the session's
 * theoretical amount; VOUCHER prints a store voucher whose number matches the
 * STORE_VOUCHER pattern (50 + 8-digit serial + 4-digit cents) and is thus
 * scannable as a payment on a later ticket when the amount fits 99,99 €;
 * LOYALTY stays a journal note until the phase 7 engine makes the balance
 * real; CARD logs the terminal gesture. {@link #refundNumber} (counter row
 * lock) is the store-sync upsert key. Blocked entirely in training mode.
 */
@Entity
@Table(name = "refunds",
        indexes = @Index(name = "idx_refund_original", columnList = "original_ticket_id")
)
public class Refund extends BaseEntity {

    /** Lifecycle status of a refund document. */
    public enum RefundStatus {
        /** Being built (unused since lot 4: refunds are created closed). */
        OPEN,
        /** Finalized. */
        CLOSED
    }

    /** How the customer was refunded. */
    public enum RefundMethod {
        /** Cash handed back from the drawer. */
        CASH,
        /** Credited back on the payment card. */
        CARD,
        /** A store voucher printed for the refunded amount. */
        VOUCHER,
        /** Credited on the customer's loyalty balance. */
        LOYALTY,
        /**
         * Credited on the customer's in-account credit (LC-05-06-05): the
         * refund lowers what the account owes the store.
         */
        CREDIT
    }

    /** The sequential refund number, e.g. "C04-R000012" (null on legacy rows). */
    @Column(name = "refund_number", unique = true, length = 30)
    public String refundNumber;

    /** The lifecycle status. */
    @Column(name = "status", nullable = false)
    @NotNull
    public RefundStatus status = RefundStatus.OPEN;

    /** The refund method chosen at validation (null on pre-lot-4 rows). */
    @Column(name = "refund_method", length = 20)
    @Enumerated(EnumType.STRING)
    public RefundMethod refundMethod;

    /** The database id of the refunded ticket. */
    @Column(name = "original_ticket_id", nullable = false)
    public Long originalTicketId;

    /** The identifier of the register that performed the refund (null on legacy rows). */
    @Column(name = "terminal_id", length = 20)
    public String terminalId;

    /** The cash session that performed the refund (null on legacy rows). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    public CashSession session;

    /** The creation timestamp. */
    @Column(name = "creation_date", nullable = false)
    public LocalDateTime creationDate;

    /** The refunded total including tax. */
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    public BigDecimal totalAmount;

    /** The refunded total excluding tax (null when unbreakable: manual amount without lines). */
    @Column(name = "total_ht", precision = 19, scale = 4)
    public BigDecimal totalExcludingTax;

    /** The refunded VAT total (null when unbreakable: manual amount without lines). */
    @Column(name = "total_vat", precision = 19, scale = 4)
    public BigDecimal totalVat;

    /**
     * The return reason chosen by the cashier, from the administered list
     * (LC-05-05-01/02), or null when none was chosen. It applies to the WHOLE
     * refund (all returned lines at once), is journaled and — when the store
     * administers it — printed on the return ticket, so it is exploitable in
     * the back-office reports.
     */
    @Column(name = "return_reason", length = 255)
    public String returnReason;

    /**
     * Badge of the operator who made the refund ({@code BO-06-06-05}), or
     * null on a refund whose register had nobody signed in.
     *
     * <p>Frozen on the document like the author of a cancellation: a state
     * edited months later must name who refunded, and the session alone does
     * not — a session outlives the operator who opened it.
     */
    @Column(name = "cashier_badge_id", length = 20)
    public String cashierBadgeId;

    /**
     * Badge of the supervisor who endorsed the refund
     * ({@code BO-06-06-05}), or null when the gesture needed no endorsement.
     */
    @Column(name = "supervisor_badge_id", length = 20)
    public String supervisorBadgeId;

    /**
     * Code of the administered return reason ({@code BO-06-06-05}), beside
     * the label {@link #returnReason} already carries, or null when the shop
     * administers no coded list.
     */
    @Column(name = "return_reason_code", length = 20)
    public String returnReasonCode;

    /**
     * Code of the administered return type the gesture was made under
     * ({@code BO-06-06-05}, {@code LC-05-04-01}), or null when the shop
     * administers none.
     */
    @Column(name = "return_type_code", length = 20)
    public String returnTypeCode;

    /**
     * Label of that return type ({@code BO-06-06-05}), or null — frozen
     * beside its code so a state restates the wording the cashier was offered.
     */
    @Column(name = "return_type_label", length = 60)
    public String returnTypeLabel;

    /**
     * The account number credited when the refund method is {@code CREDIT}
     * (LC-05-06-05), or null for every other method. It names the in-account
     * customer whose outstanding balance the refund lowered, so the refund
     * record is exploitable in the store's account statements.
     */
    @Column(name = "credit_account_number", length = 40)
    public String creditAccountNumber;

    /**
     * How far the recalculation of the KEPT basket against the promotion engine
     * got (LC-05-02-13): {@code NOT_APPLICABLE} (no engine administered, or the
     * whole ticket came back), {@code RECOMPUTED} (the engine answered) or
     * {@code UNAVAILABLE} (it was asked and could not answer — the refund went
     * through anyway, the customer being at the till). Null on rows written
     * before the recalculation existed.
     */
    @Column(name = "recalculation_status", length = 20)
    public String recalculationStatus;

    /**
     * The generosity the kept basket no longer deserves, tax included
     * (LC-05-02-13): what the return takes back BEYOND the price refunded,
     * because the advantages of the sale were granted on a basket that no longer
     * exists. Null unless the recalculation ran; never negative — a return
     * never grants a new advantage.
     */
    @Column(name = "withdrawn_generosity", precision = 19, scale = 4)
    public BigDecimal withdrawnGenerosity;

    /**
     * The labels of the advantages the kept basket STILL earns, joined by
     * {@code " | "} in the engine's own order, or null when the recalculation did
     * not run or the kept basket earns none (LC-05-07-02, printed on the return
     * ticket). Joined in one column, as the restricted tenders of a ticket line
     * are: the register restates these labels and computes nothing from them.
     */
    @Column(name = "kept_advantages", length = 500)
    public String keptAdvantages;

    /**
     * Whether the return was notified to the external loyalty service
     * (LC-05-02-13): true when the origin ticket carried a card, so the
     * {@code TICKET_RETURN} event was enqueued and the décagnottage is the
     * loyalty service's to compute. The register prints that it was sent and
     * never an amount it did not compute.
     */
    @Column(name = "loyalty_recalculation_sent", nullable = false)
    public boolean loyaltyRecalculationSent;

    /** The refunded lines. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "refund_id", nullable = false)
    public List<RefundLine> lines = new ArrayList<>();


    /**
     * Returns the audit checksum of the refund.
     *
     * @return a hash of the identifying and financial fields
     */
    @Override
    public int getChecksum() {
        return Objects.hash(refundNumber, originalTicketId, totalAmount, status, refundMethod,
                returnReason, creditAccountNumber);
    }
}
