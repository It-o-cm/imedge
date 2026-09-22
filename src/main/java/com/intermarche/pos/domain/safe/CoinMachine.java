package com.intermarche.pos.domain.safe;

import com.intermarche.pos.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * A COIN MACHINE ({@code BO-05-02-16}): the automatic cash unit of one register,
 * and what the checkout software believes it holds.
 *
 * <p>The requirement is precise about the point: the amount must be known
 * WITHOUT asking the machine. So the figure here is a running total the software
 * keeps — every settlement collected into the machine adds to it, every change
 * given back takes from it, every loading and emptying moves it — and the
 * machine is only ever asked when somebody wants to compare.
 *
 * <p>Which is why {@link #declaredAmount} and {@link #countedAmount} are two
 * different columns. The first is what the software computed; the second is what
 * the machine or a human said when last asked. A single column would lose the
 * discrepancy, and the discrepancy is the only thing worth looking at.
 */
@Entity
@Table(name = "coin_machines",
        indexes = {
                @Index(name = "idx_coin_machine_code", columnList = "code", unique = true),
                @Index(name = "idx_coin_machine_terminal", columnList = "terminal_id")
        }
)
public class CoinMachine extends BaseEntity {

    /** The machine's own code, as the shop names it. */
    @Column(name = "code", nullable = false, unique = true, length = 30)
    public String code;

    /** The operator-facing wording of the machine. */
    @Column(name = "label", length = 120)
    public String label;

    /** The register the machine is attached to, null when it serves none in particular. */
    @Column(name = "terminal_id", length = 20)
    public String terminalId;

    /** Whether the machine is in service. */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /** What the software believes the machine holds. */
    @Column(name = "declared_amount", nullable = false, precision = 19, scale = 4)
    public BigDecimal declaredAmount = BigDecimal.ZERO;

    /** What the machine held when last counted, null when never counted. */
    @Column(name = "counted_amount", precision = 19, scale = 4)
    public BigDecimal countedAmount;

    /** When the running total last moved, null while nothing has moved it. */
    @Column(name = "updated_on")
    public LocalDateTime updatedOn;

    /** When the machine was last counted, null when never counted. */
    @Column(name = "counted_on")
    public LocalDateTime countedOn;

    /**
     * The discrepancy between what was counted and what the software believes.
     *
     * @return the counted amount minus the declared one, zero while the machine
     *         has never been counted
     */
    public BigDecimal variance() {
        if (countedAmount == null) {
            return BigDecimal.ZERO;
        }
        return countedAmount.subtract(declaredAmount == null ? BigDecimal.ZERO : declaredAmount);
    }

    /**
     * Tells whether the last count agreed with the running total.
     *
     * @return true when the machine has been counted and the two agree
     */
    public boolean isReconciled() {
        return countedAmount != null && variance().signum() == 0;
    }

    /**
     * Returns the declared amount for a template, two decimals.
     *
     * @return the amount, two decimals
     */
    public String getDeclaredAmountFormatted() {
        return (declaredAmount == null ? BigDecimal.ZERO : declaredAmount)
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Returns the discrepancy for a template, two decimals.
     *
     * @return the variance, two decimals
     */
    public String getVarianceFormatted() {
        return variance().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Resolves a machine from its code.
     *
     * @param code the code, possibly null
     * @return the machine, or null when the code names none
     */
    public static CoinMachine findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return CoinMachine.<CoinMachine>find("code", code.trim()).firstResult();
    }

    /**
     * The machines attached to one register, in code order.
     *
     * @param terminalId the register identifier, null listing none
     * @return the machines of that register
     */
    public static List<CoinMachine> ofTerminal(String terminalId) {
        if (terminalId == null || terminalId.isBlank()) {
            return List.of();
        }
        return CoinMachine
                .<CoinMachine>find("terminalId = ?1 order by code", terminalId.trim()).list();
    }

    /**
     * Every machine, in code order.
     *
     * @return the machines
     */
    public static List<CoinMachine> listOrdered() {
        return CoinMachine.<CoinMachine>find("order by code").list();
    }

    /**
     * Returns the audit checksum of the machine.
     *
     * @return a hash of the identifying and financial fields
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, label, terminalId, active, declaredAmount, countedAmount);
    }
}
