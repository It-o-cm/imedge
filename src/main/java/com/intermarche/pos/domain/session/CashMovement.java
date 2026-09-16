package com.intermarche.pos.domain.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.BaseEntity;

/**
 * A cash movement of one register: a first-class WITNESS of a drawer
 * operation the cashier performed during a session — a withdrawal, a deposit,
 * an expense, a customer down-payment, or a cash-count declaration. The cash
 * session keeps five AMOUNTS (float, counted, theoretical, variance,
 * withdrawn) but no MOVEMENTS; this entity records the suite of gestures that
 * produced them, which is what the electronic journal needs to search on
 * (BO-04-01-12/33/35/36/37/40/44). It is the ground floor of the conditional
 * safe lot (D) as well.
 * <p>
 * Semantic contract, mirroring {@link CashSession} and the ticket graph:
 * <ul>
 *   <li>Written in the register's own database, so it survives restarts by
 *       construction, and pushed to the store node THROUGH THE OUTBOX (entity
 *       type {@code MOVEMENT}, drained right after its session so the session
 *       it references is already consolidated), never by a synchronous call in
 *       the sale thread — a register cut off from the network keeps recording
 *       movements. Upsert by {@link #movementUid}, so re-pushing is idempotent.</li>
 *   <li>Every field describes the MOVEMENT itself — the sale-floor fact to
 *       consolidate — and is therefore CARRIED by the store push, on the same
 *       side of the frontier as a ticket or a session. None of them is local
 *       register auth state (unlike {@code failedAttempts}/{@code lockedUntil}):
 *       {@link #endorsedBy} names the manager who approved a large movement, a
 *       fact of the operation, never a secret.</li>
 * </ul>
 */
@Entity
@Table(name = "cash_movements",
        indexes = {
                @Index(name = "idx_movement_uid", columnList = "movement_uid", unique = true),
                @Index(name = "idx_movement_terminal_date", columnList = "terminal_id, movement_date"),
                @Index(name = "idx_movement_type", columnList = "movement_type")
        }
)
public class CashMovement extends BaseEntity {

    /** The kind of drawer movement recorded. */
    public enum MovementType {
        /** A withdrawal of cash from the drawer (prelevement). */
        WITHDRAWAL,
        /** An addition of cash into the drawer (apport). */
        DEPOSIT,
        /** A cash expense paid from the drawer (depense: stamps, pharmacy...). */
        EXPENSE,
        /** A customer down-payment cashed in (acompte). */
        CUSTOMER_DEPOSIT,
        /** A cashier cash-count declaration of the drawer (declaration/comptage). */
        DECLARATION,
        /**
         * A transfer of an amount from one tender to another inside the drawer
         * ({@code LC-12-10}), used to repair a cashier's mis-keying: the money never
         * left, it was recorded under the wrong tender.
         */
        TRANSFER
    }

    /** The tender key of a payment method, as the register names it. */
    public static final String CASH = "CASH";

    /** Stable identity of the movement across nodes (store-sync upsert key). */
    @Column(name = "movement_uid", unique = true, length = 36)
    public String movementUid;

    /**
     * The tender this movement concerns ({@code LC-12-03-02}), or null for the
     * historical movements, which were all cash.
     * <p>
     * NULL READS AS CASH everywhere: a withdrawal recorded before this field existed
     * took cash out of the drawer, and a migration that silently turned those rows
     * into "no tender at all" would move the theoretical of every closed session.
     */
    @Column(name = "payment_method", length = 30)
    public String paymentMethod;

    /**
     * The tender an amount is transferred TO ({@code LC-12-10-01}), null on every
     * movement that is not a transfer. The source is {@link #paymentMethod}.
     */
    @Column(name = "transfer_to", length = 30)
    public String transferTo;

    /**
     * The per-denomination detail of a cash withdrawal ({@code LC-12-03-04}), as the
     * JSON the count screen produces, or null when the movement carries none.
     */
    @Column(name = "denomination_detail", length = 2000)
    public String denominationDetail;

    /** The identifier of the register (TPV) this movement was recorded on. */
    @Column(name = "terminal_id", nullable = false, length = 20)
    public String terminalId;

    /** The cash session this movement belongs to, or null on a legacy row. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    public CashSession session;

    /** The cashier who performed the movement, or null on a legacy row. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cashier_id")
    public Employee cashier;

    /** The kind of movement. */
    @Column(name = "movement_type", nullable = false, length = 20)
    public MovementType type;

    /** The amount of the movement (BigDecimal, scale 2). */
    @Column(name = "amount", precision = 19, scale = 4)
    public BigDecimal amount;

    /** The free-text reason of the movement, or null. */
    @Column(name = "reason", length = 255)
    public String reason;

    /** The timestamp at which the movement was recorded. */
    @Column(name = "movement_date", nullable = false)
    public LocalDateTime movementDate;

    /**
     * The badge (N° caissiere) of the manager who endorsed the movement when it
     * exceeded the endorsement threshold, or null when no endorsement was
     * required or given. A badge, never a secret — the journal is readable by
     * any MANAGER.
     */
    @Column(name = "endorsed_by", length = 20)
    public String endorsedBy;

    /**
     * Returns the audit checksum of the movement.
     *
     * @return a hash of the identifying and financial fields
     */
    @Override
    public int getChecksum() {
        return Objects.hash(movementUid, terminalId, type, amount, movementDate,
                paymentMethod, transferTo);
    }
}
