package com.intermarche.edge.domain.safe;

import com.intermarche.edge.domain.BaseEntity;
import com.intermarche.edge.domain.people.Employee;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A BANK DEPOSIT ({@code BO-05-02-07}): the cash of one or several pouches
 * leaving the safe for the bank.
 *
 * <p>Carries a BAG number and a wording because that is what the requirement
 * asks and what the accounting pieces print — the shops that use no bag (the
 * requirement names Bricorama) simply write their own reference in the same
 * field rather than needing a second shape of the object.
 *
 * <p>The deposit debits the safe once, for the total of the pouches it takes,
 * and the two accounting pieces the requirement names — the {@code bordereau
 * espèces} and the {@code versement espèces} — are printed from this row and
 * its pouches, never recomputed from the movements.
 */
@Entity
@Table(name = "bank_deposits",
        indexes = {
                @Index(name = "idx_bank_deposit_bag", columnList = "bag_number", unique = true),
                @Index(name = "idx_bank_deposit_day", columnList = "deposit_day")
        }
)
public class BankDeposit extends BaseEntity {

    /** The bag number, or the shop's own reference when it uses no bag. */
    @Column(name = "bag_number", nullable = false, unique = true, length = 40)
    public String bagNumber;

    /** The operator-facing wording of the deposit. */
    @Column(name = "label", length = 120)
    public String label;

    /** The day the deposit left for the bank. */
    @Column(name = "deposit_day", nullable = false)
    public LocalDate depositDay;

    /** How much left the safe. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    public BigDecimal amount = BigDecimal.ZERO;

    /** Who declared the deposit, null when not recorded. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "declared_by")
    public Employee declaredBy;

    /** The movement the deposit wrote into the safe. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movement_id")
    public SafeMovement movement;

    /** The pouches the deposit took away. */
    @OneToMany(mappedBy = "bankDeposit", fetch = FetchType.LAZY)
    public List<CashPouch> pouches = new ArrayList<>();

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
     * Every deposit, newest first.
     *
     * @return the deposits, the most recent day first
     */
    public static List<BankDeposit> listNewestFirst() {
        return BankDeposit.<BankDeposit>find("order by depositDay desc, id desc").list();
    }

    /**
     * Returns the audit checksum of the deposit.
     *
     * @return a hash of the identifying and financial fields
     */
    @Override
    public int getChecksum() {
        return Objects.hash(bagNumber, label, depositDay, amount);
    }
}
