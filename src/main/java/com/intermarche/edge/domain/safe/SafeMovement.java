package com.intermarche.edge.domain.safe;

import com.intermarche.edge.domain.BaseEntity;
import com.intermarche.edge.domain.people.Employee;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One movement of the SAFE ({@code BO-05-02}): the single ledger every gesture
 * of the safe writes into.
 *
 * <p>ONE table for the expenses, the receipts, the coin deliveries, the pouches,
 * the bank deposits and the inventory corrections, because they are all the same
 * thing seen from the safe — money in or money out, on a day, in a period, for a
 * reason. The gestures that produce them keep their own entities (a coin order, a
 * pouch, a bank deposit have a life of their own), and each of those points at
 * the movement it caused. The balance is read HERE and nowhere else, so a gesture
 * that forgot to write its movement is a gesture that never happened, which is
 * exactly what an audit should conclude.
 *
 * <p>The amount is always POSITIVE; {@link SafeMovementType#isCredit()} says what
 * it does to the balance.
 *
 * <p>A movement is entered PENDING and becomes VALIDATED by the pointing screen
 * ({@code BO-05-02-06}). Both count towards the balance — money that left the
 * safe left it whether or not anybody has checked the entry — but a period
 * cannot be closed while one of its movements is still pending.
 */
@Entity
@Table(name = "safe_movements",
        indexes = {
                @Index(name = "idx_safe_movement_date", columnList = "movement_date"),
                @Index(name = "idx_safe_movement_type", columnList = "movement_type"),
                @Index(name = "idx_safe_movement_status", columnList = "status")
        }
)
public class SafeMovement extends BaseEntity {

    /** Where a movement stands in the pointing of its period. */
    public enum MovementStatus {

        /** Entered, not yet checked by the pointing screen. */
        PENDING,

        /** Checked and frozen: it may no longer be corrected, only reversed. */
        VALIDATED
    }

    /** What the movement does to the safe. */
    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 24)
    public SafeMovementType type;

    /** The amount, always positive; the kind says whether it is a debit or a credit. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    public BigDecimal amount = BigDecimal.ZERO;

    /** The operator-facing wording of the movement. */
    @Column(name = "label", nullable = false, length = 160)
    public String label;

    /** The day the movement counts for. */
    @Column(name = "movement_date", nullable = false)
    public LocalDate movementDate;

    /** When the row was entered, which is not always the day it counts for. */
    @Column(name = "entered_at", nullable = false)
    public LocalDateTime enteredAt;

    /** The accounting period the movement falls in, null on a row entered outside one. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "period_id")
    public AccountingPeriod period;

    /** Who entered the movement, null when it came from an integration. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entered_by")
    public Employee enteredBy;

    /**
     * The employee the movement concerns ({@code BO-05-02-15}), null when it
     * concerns none.
     *
     * <p>An advance, a reimbursement or a down-payment is paid TO somebody, and
     * the requirement asks the list of those somebodies to be held. Naming them
     * here is what lets a shop answer what it advanced to whom.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    public Employee employee;

    /** Where the movement stands in the pointing of its period. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    public MovementStatus status = MovementStatus.PENDING;

    /** Who validated the movement, null while it is pending. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "validated_by")
    public Employee validatedBy;

    /** When the movement was validated, null while it is pending. */
    @Column(name = "validated_at")
    public LocalDateTime validatedAt;

    /**
     * The external reference of the movement — a bag number, an invoice number,
     * the identifier of the integrated feed — null when it carries none.
     */
    @Column(name = "reference", length = 60)
    public String reference;

    /** The VAT rates the movement bears, empty when it bears none. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "safe_movement_vat",
            joinColumns = @JoinColumn(name = "movement_id"))
    public List<SafeVatLine> vatLines = new ArrayList<>();

    /**
     * What the movement adds to the safe: the amount for a credit, its opposite
     * for a debit.
     *
     * @return the signed amount, never null
     */
    public BigDecimal signedAmount() {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return type != null && type.isDebit() ? value.negate() : value;
    }

    /**
     * The tax-included total of the VAT breakdown.
     *
     * @return the sum of the lines, zero when the movement bears none
     */
    public BigDecimal vatTotal() {
        BigDecimal total = BigDecimal.ZERO;
        if (vatLines == null) {
            return total;
        }
        for (SafeVatLine line : vatLines) {
            total = total.add(line.getTotal());
        }
        return total;
    }

    /**
     * Tells whether the movement has been checked by the pointing screen.
     *
     * @return true when the movement is validated
     */
    public boolean isValidated() {
        return status == MovementStatus.VALIDATED;
    }

    /**
     * Tells whether the movement may still be corrected ({@code BO-05-02-06}).
     *
     * <p>Two conditions, and both matter: a validated movement is frozen by the
     * pointing, and ANY movement of a closed period is frozen by the closure even
     * if nobody ever pointed it.
     *
     * @return true when a correction is still allowed
     */
    public boolean isCorrectable() {
        return status == MovementStatus.PENDING && (period == null || period.isOpen());
    }

    /**
     * Returns the movement's kind label, for a template.
     *
     * @return the French label, empty when the row carries no kind
     */
    public String getTypeLabel() {
        return type == null ? "" : type.getLabel();
    }

    /**
     * Returns the amount as the journal prints it, signed
     * ({@code BO-05-02-13}).
     *
     * @return the signed amount, two decimals
     */
    public String getSignedAmountFormatted() {
        return signedAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Returns the audit checksum of the movement.
     *
     * @return a hash of the identifying and financial fields
     */
    @Override
    public int getChecksum() {
        return Objects.hash(type, amount, label, movementDate, status, reference,
                vatLines == null ? 0 : vatLines.size());
    }
}
