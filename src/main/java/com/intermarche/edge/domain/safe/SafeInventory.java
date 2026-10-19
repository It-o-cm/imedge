package com.intermarche.edge.domain.safe;

import com.intermarche.edge.domain.BaseEntity;
import com.intermarche.edge.domain.people.Employee;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An INVENTORY of the safe ({@code BO-05-02-11}): what was physically counted,
 * against what the ledger says should be there.
 *
 * <p>The THEORETICAL is frozen on the row at the moment of the count. It is not
 * recomputed when the inventory is read back, because a movement entered five
 * minutes later would silently change a discrepancy somebody has already
 * explained and signed off.
 *
 * <p>An inventory does not by itself correct the safe. The shop decides whether
 * to write the discrepancy off, and doing so writes an
 * {@link SafeMovementType#INVENTORY_ADJUSTMENT} movement — so the balance only
 * ever moves through the ledger, and the count that justified it stays readable
 * beside it.
 */
@Entity
@Table(name = "safe_inventories",
        indexes = {
                @Index(name = "idx_inventory_date", columnList = "counted_at")
        }
)
public class SafeInventory extends BaseEntity {

    /** When the count was taken. */
    @Column(name = "counted_at", nullable = false)
    public LocalDateTime countedAt;

    /** Who counted, null when not recorded. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "counted_by")
    public Employee countedBy;

    /** The accounting period the count was taken in, null outside one. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "period_id")
    public AccountingPeriod period;

    /** What the ledger said the safe held, frozen at the moment of the count. */
    @Column(name = "theoretical_amount", nullable = false, precision = 19, scale = 4)
    public BigDecimal theoreticalAmount = BigDecimal.ZERO;

    /** What was actually found, the sum of the counted lines. */
    @Column(name = "counted_amount", nullable = false, precision = 19, scale = 4)
    public BigDecimal countedAmount = BigDecimal.ZERO;

    /** The discrepancy: what was found minus what was expected. */
    @Column(name = "variance", nullable = false, precision = 19, scale = 4)
    public BigDecimal variance = BigDecimal.ZERO;

    /** What the operator wrote to explain the discrepancy, null when none. */
    @Column(name = "comment", length = 255)
    public String comment;

    /** The movement that wrote the discrepancy off, null while it stands. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "adjustment_id")
    public SafeMovement adjustment;

    /** The denominations counted, in the order the screen shows them. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "safe_inventory_lines",
            joinColumns = @JoinColumn(name = "inventory_id"))
    @OrderColumn(name = "line_number")
    public List<SafeInventoryLine> lines = new ArrayList<>();

    /**
     * The sum of the counted lines.
     *
     * @return the counted amount, never null
     */
    public BigDecimal countedTotal() {
        BigDecimal total = BigDecimal.ZERO;
        if (lines == null) {
            return total;
        }
        for (SafeInventoryLine line : lines) {
            total = total.add(line.amount());
        }
        return total;
    }

    /**
     * Tells whether the count matched the ledger.
     *
     * @return true when there is no discrepancy at all
     */
    public boolean isBalanced() {
        return variance == null || variance.signum() == 0;
    }

    /**
     * Tells whether the discrepancy is still standing ({@code BO-05-02-11}).
     *
     * @return true when there is a discrepancy and nothing has written it off
     */
    public boolean isAdjustable() {
        return !isBalanced() && adjustment == null;
    }

    /**
     * Returns the discrepancy for a template, two decimals and signed.
     *
     * @return the variance, two decimals
     */
    public String getVarianceFormatted() {
        return (variance == null ? BigDecimal.ZERO : variance)
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Returns the counted amount for a template, two decimals.
     *
     * @return the counted amount, two decimals
     */
    public String getCountedAmountFormatted() {
        return (countedAmount == null ? BigDecimal.ZERO : countedAmount)
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Returns the theoretical amount for a template, two decimals.
     *
     * @return the theoretical amount, two decimals
     */
    public String getTheoreticalAmountFormatted() {
        return (theoreticalAmount == null ? BigDecimal.ZERO : theoreticalAmount)
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Every inventory, newest first.
     *
     * @return the counts, the most recent first
     */
    public static List<SafeInventory> listNewestFirst() {
        return SafeInventory.<SafeInventory>find("order by countedAt desc, id desc").list();
    }

    /**
     * Returns the audit checksum of the inventory.
     *
     * @return a hash of the identifying and financial fields
     */
    @Override
    public int getChecksum() {
        return Objects.hash(countedAt, theoreticalAmount, countedAmount, variance, comment,
                lines == null ? 0 : lines.size());
    }
}
