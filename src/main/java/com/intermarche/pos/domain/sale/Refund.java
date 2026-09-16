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
        LOYALTY
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

    /** The refunded lines. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "refund_id", nullable = false)
    public List<RefundLine> lines = new ArrayList<>();

    /**
     * Recomputes the tax-included total from the lines (server side).
     */
    public void calculateTotal() {
        this.totalAmount = lines.stream()
                .map(l -> l.price.multiply(l.quantity))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Returns the audit checksum of the refund.
     *
     * @return a hash of the identifying and financial fields
     */
    @Override
    public int getChecksum() {
        return Objects.hash(refundNumber, originalTicketId, totalAmount, status, refundMethod);
    }
}
