package com.intermarche.pos.domain.safe;

import com.intermarche.pos.domain.BaseEntity;
import com.intermarche.pos.domain.people.Employee;
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
import java.util.List;
import java.util.Objects;

/**
 * A POUCH of a day's takings ({@code BO-05-02-10}): what the registers'
 * withdrawals become once they are handed to the safe.
 *
 * <p>The register already knows how much was taken out of each drawer — the
 * closing writes it on the session. What was missing is the step AFTER: the
 * money physically walks to the safe inside a pouch, and until somebody records
 * that pouch the safe has not received anything. Hence an entity of its own
 * rather than a flag on the session: one pouch gathers several sessions, and it
 * is the pouch, not the session, that later goes to the bank.
 *
 * <p>A pouch credits the safe when it is deposited, and is debited from it again
 * as part of a bank deposit ({@code BO-05-02-07}) — the two movements are
 * written by the safe service, never by this entity.
 */
@Entity
@Table(name = "cash_pouches",
        indexes = {
                @Index(name = "idx_pouch_number", columnList = "pouch_number", unique = true),
                @Index(name = "idx_pouch_status", columnList = "status"),
                @Index(name = "idx_pouch_day", columnList = "accounting_day")
        }
)
public class CashPouch extends BaseEntity {

    /** Where a pouch stands. */
    public enum PouchStatus {

        /** Held in the safe, not yet sent to the bank. */
        IN_SAFE,

        /** Gone to the bank inside a deposit. */
        BANKED
    }

    /** The pouch's own number, written on its seal. */
    @Column(name = "pouch_number", nullable = false, unique = true, length = 30)
    public String pouchNumber;

    /** The operator-facing wording of the pouch. */
    @Column(name = "label", length = 120)
    public String label;

    /** The accounting day whose takings the pouch holds. */
    @Column(name = "accounting_day", nullable = false)
    public LocalDate accountingDay;

    /** How much the pouch holds. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    public BigDecimal amount = BigDecimal.ZERO;

    /**
     * The registers whose withdrawals the pouch gathers, as a comma-separated
     * list of terminal identifiers, or null when it gathers none in particular.
     */
    @Column(name = "terminals", length = 200)
    public String terminals;

    /** Where the pouch stands. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    public PouchStatus status = PouchStatus.IN_SAFE;

    /** Who brought the pouch to the safe, null when not recorded. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deposited_by")
    public Employee depositedBy;

    /** The movement the deposit wrote into the safe. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movement_id")
    public SafeMovement movement;

    /** The bank deposit that took the pouch away, null while it is in the safe. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_deposit_id")
    public BankDeposit bankDeposit;

    /**
     * Tells whether the pouch is still in the safe.
     *
     * @return true while the pouch has not gone to the bank
     */
    public boolean isInSafe() {
        return status == PouchStatus.IN_SAFE;
    }

    /**
     * Returns the amount for a template, two decimals.
     *
     * @return the amount, two decimals
     */
    public String getAmountFormatted() {
        return (amount == null ? BigDecimal.ZERO : amount)
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * The pouches still held in the safe, oldest first.
     *
     * @return the pouches available for a bank deposit
     */
    public static List<CashPouch> inSafe() {
        return CashPouch
                .<CashPouch>find("status = ?1 order by accountingDay, id", PouchStatus.IN_SAFE)
                .list();
    }

    /**
     * Every pouch, newest first.
     *
     * @return the pouches, the most recent day first
     */
    public static List<CashPouch> listNewestFirst() {
        return CashPouch.<CashPouch>find("order by accountingDay desc, id desc").list();
    }

    /**
     * Returns the audit checksum of the pouch.
     *
     * @return a hash of the identifying and financial fields
     */
    @Override
    public int getChecksum() {
        return Objects.hash(pouchNumber, label, accountingDay, amount, terminals, status);
    }
}
