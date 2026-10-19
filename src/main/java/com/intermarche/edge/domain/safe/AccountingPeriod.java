package com.intermarche.edge.domain.safe;

import com.intermarche.edge.domain.BaseEntity;
import com.intermarche.edge.domain.people.Employee;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * The ACCOUNTING PERIOD of the safe ({@code BO-05-02-12}): the stretch of days
 * whose financial movements are counted together and then frozen.
 *
 * <p>Closing is what makes a safe an account rather than a drawer. Until a
 * period is closed, its movements can still be corrected; once it is closed,
 * nothing may be entered on it any more and its closing balance is the opening
 * balance of the next one. That is the whole point of the requirement's order —
 * point, validate, then close — and the reason the closure refuses to run while
 * a movement of the period is still unvalidated.
 *
 * <p>ONE period is open at a time. The safe has one balance, and two open
 * periods would mean two answers to the question of which one a movement
 * entered today belongs to.
 */
@Entity
@Table(name = "accounting_periods",
        indexes = {
                @Index(name = "idx_period_status", columnList = "status"),
                @Index(name = "idx_period_start", columnList = "start_date")
        }
)
public class AccountingPeriod extends BaseEntity {

    /** Where a period stands. */
    public enum PeriodStatus {

        /** Movements may still be entered and corrected. */
        OPEN,

        /** The period is frozen: nothing more may be entered on it. */
        CLOSED
    }

    /** The operator-facing name of the period, such as {@code 2026-09}. */
    @Column(name = "label", nullable = false, length = 40)
    public String label;

    /** The first day the period counts. */
    @Column(name = "start_date", nullable = false)
    public LocalDate startDate;

    /** The last day the period counts, null while it is open-ended. */
    @Column(name = "end_date")
    public LocalDate endDate;

    /** Where the period stands. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    public PeriodStatus status = PeriodStatus.OPEN;

    /**
     * The balance the safe held when the period opened.
     *
     * <p>Stored and not recomputed: it is the closing balance of the period
     * before, and a chain that recomputed it from the beginning of time would
     * make the oldest correction move every balance since.
     */
    @Column(name = "opening_balance", nullable = false, precision = 19, scale = 4)
    public BigDecimal openingBalance = BigDecimal.ZERO;

    /** The balance the safe held when the period was closed, null while open. */
    @Column(name = "closing_balance", precision = 19, scale = 4)
    public BigDecimal closingBalance;

    /** Who closed the period, null while it is open. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by")
    public Employee closedBy;

    /** When the period was closed, null while it is open. */
    @Column(name = "closed_at")
    public LocalDateTime closedAt;

    /**
     * Tells whether the period still accepts movements.
     *
     * @return true while the period is open
     */
    public boolean isOpen() {
        return status == PeriodStatus.OPEN;
    }

    /**
     * Tells whether a day falls inside the period.
     *
     * @param day the day examined, null falling in no period
     * @return true when the day is on or after the start and on or before the end
     */
    public boolean covers(LocalDate day) {
        if (day == null || startDate == null) {
            return false;
        }
        if (day.isBefore(startDate)) {
            return false;
        }
        return endDate == null || !day.isAfter(endDate);
    }

    /**
     * The period currently open, or null when the safe has never been opened.
     *
     * @return the open period, or null
     */
    public static AccountingPeriod open() {
        return AccountingPeriod
                .<AccountingPeriod>find("status = ?1 order by startDate desc", PeriodStatus.OPEN)
                .firstResult();
    }

    /**
     * Every period, newest first.
     *
     * @return the periods, the most recent first
     */
    public static List<AccountingPeriod> listNewestFirst() {
        return AccountingPeriod.<AccountingPeriod>find("order by startDate desc").list();
    }

    /**
     * Returns the audit checksum of the period.
     *
     * @return a hash of the identifying and financial fields
     */
    @Override
    public int getChecksum() {
        return Objects.hash(label, startDate, endDate, status, openingBalance, closingBalance,
                closedAt);
    }
}
