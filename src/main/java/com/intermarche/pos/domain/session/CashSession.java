package com.intermarche.pos.domain.session;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Objects;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.BaseEntity;

/**
 * A cash session of one register: opened with an initial float by a cashier,
 * closed by the Z report with the counted amount, the theoretical amount, the
 * variance and the withdrawal. Every ticket created while the session is open
 * is attached to it; sales are refused while no session is open.
 * <p>
 * The session lives in the register's own database, so it survives register
 * restarts by construction.
 * <p>
 * Semantic contract: at most one OPEN session per register (selling is
 * gated on it, except in training mode); the theoretical drawer amount is
 * opening float + cash payments of the session's closed tickets − its cash
 * refunds; the Z closing counts the drawer ({@code countDetail} keeps the
 * per-denomination JSON), persists the variance, and kills the register's
 * PARKED tickets. Pushed to the store node at opening AND closing (upsert
 * by {@code sessionNumber}), so consolidated tickets can reference their
 * session as soon as it exists.
 */
@Entity
@Table(name = "cash_sessions",
        indexes = {
                @Index(name = "idx_session_number", columnList = "session_number", unique = true),
                @Index(name = "idx_session_terminal_status", columnList = "terminal_id, status")
        }
)
public class CashSession extends BaseEntity {

    /** Lifecycle status of a cash session. */
    public enum SessionStatus {
        /** Session in progress: sales allowed on this register. */
        OPEN,
        /** Session closed by a Z report: sales blocked until a new opening. */
        CLOSED
    }

    /** The sequential session number, e.g. "C04-S00012", unique per register database. */
    @Column(name = "session_number", unique = true, nullable = false, length = 30)
    @NotBlank
    public String sessionNumber;

    /** The identifier of the register this session belongs to (pos.terminal.id). */
    @Column(name = "terminal_id", nullable = false, length = 20)
    @NotNull
    public String terminalId;

    /** The lifecycle status; defaults to OPEN. */
    @Column(name = "status", nullable = false, length = 10)
    @NotNull
    public SessionStatus status = SessionStatus.OPEN;

    /** The opening timestamp. */
    @Column(name = "opening_date", nullable = false)
    @NotNull
    public LocalDateTime openingDate;

    /** The closing timestamp (Z report), or null while open. */
    @Column(name = "closing_date")
    public LocalDateTime closingDate;

    /** The cashier who opened the session. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "opening_cashier_id", nullable = false)
    @NotNull
    public Employee openingCashier;

    /** The cashier who closed the session, or null while open. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closing_cashier_id")
    public Employee closingCashier;

    /** The initial cash float placed in the drawer at opening. */
    @Column(name = "opening_float", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal openingFloat;

    /** The cash amount counted at closing, or null while open. */
    @Column(name = "counted_amount", precision = 19, scale = 4)
    public BigDecimal countedAmount;

    /** The theoretical cash amount at closing (float + cash payments), or null while open. */
    @Column(name = "theoretical_amount", precision = 19, scale = 4)
    public BigDecimal theoreticalAmount;

    /** The variance at closing (counted minus theoretical), or null while open. */
    @Column(name = "variance", precision = 19, scale = 4)
    public BigDecimal variance;

    /** The cash withdrawn from the drawer at closing, or null while open. */
    @Column(name = "withdrawn_amount", precision = 19, scale = 4)
    public BigDecimal withdrawnAmount;

    /** The counted denominations detail as entered (JSON of id to quantity), or null. */
    @Column(name = "count_detail", length = 1000)
    public String countDetail;

    /**
     * Finds the open session of the given register, if any.
     *
     * @param terminalId the register identifier
     * @return the open session, or null when none is open
     */
    public static CashSession findOpenByTerminal(String terminalId) {
        return find("terminalId = ?1 and status = ?2", terminalId, SessionStatus.OPEN).firstResult();
    }

    /**
     * Returns the opening float formatted for display (2 decimals, French comma).
     *
     * @return the formatted opening float
     */
    public String getOpeningFloatFormatted() {
        if (openingFloat == null) return "0,00";
        return String.format("%.2f", openingFloat.setScale(2, RoundingMode.HALF_UP)).replace(".", ",");
    }

    /**
     * Returns the audit checksum of the session.
     *
     * @return a hash of the identifying and financial fields
     */
    @Override
    public int getChecksum() {
        return Objects.hash(sessionNumber, terminalId, status, openingFloat, countedAmount, variance);
    }
}
